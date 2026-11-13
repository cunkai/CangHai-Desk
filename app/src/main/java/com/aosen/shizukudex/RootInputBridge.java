package com.aosen.shizukudex;

import android.content.Context;
import android.os.IBinder;
import android.os.Parcel;
import android.util.Log;
import android.view.Surface;

import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import rikka.shizuku.SystemServiceHelper;

/**
 * 应用侧的常驻 Root 桥客户端。
 *
 * 只启动一次 root app_process（RootInputDaemon），之后：
 *  - 每个输入事件写成 37 字节定长帧，由对端直接经 IInputManager binder 注入；
 *    没有逐事件进程创建、没有 shell 命令解析；鼠标移动/悬停完全不进这条通道
 *    （应用侧只自绘光标），高频滚轮按 16ms 合并，空闲 CPU 为 0。
 *  - 虚拟屏的创建/释放/改尺寸/换 Surface 走同一个进程注册的 Binder 服务
 *    "aosen.rootbridge"，以 root UID 调用 IDisplayManager，从而保住
 *    TRUSTED(1024) + SHOULD_SHOW_SYSTEM_DECORATIONS(512)。
 *    这些都是一次性低频事务，绝不进高频输入通道。
 *
 * 仅在纯 Root 通道启用；Shizuku 通道仍走原有 IInputManager binder。
 */
public final class RootInputBridge {

    private static final String TAG = "AosenRootInputBridge";
    private static final String ASSET_DEX = "root_bridge.dex";
    private static final String DAEMON_CLASS = "com.aosen.shizukudex.RootInputDaemon";
    private static final String SERVICE_NAME = "aosen.rootbridge";

    private static final int TX_CREATE_VD = 1;
    private static final int TX_RELEASE_VD = 2;
    private static final int TX_RESIZE_VD = 3;
    private static final int TX_SET_SURFACE_VD = 4;
    private static final int TX_SET_IME_POLICY = 5;

    // DisplayManager 的 @hide 标志，数值与 AOSP 一致
    // PUBLIC 决定 Display.type = TYPE_EXTERNAL，这是虚拟屏唯一能承载输入法的类型。
    // PRESENTATION 会得到 TYPE_PRESENTATION，WMS 的 canHostIme() 直接排除该类型，
    // 表现就是 setDisplayImePolicy 成功、mCurTokenDisplayId 也切过去了，
    // 但 IME 永远 mImeWindowVis=0（真机实测：微信输入法/三星键盘都一样）。
    private static final int VD_FLAG_PUBLIC = 1;
    private static final int VD_FLAG_PRESENTATION = 2;
    private static final int VD_FLAG_SUPPORTS_TOUCH = 64;
    private static final int VD_FLAG_DESTROY_CONTENT_ON_REMOVAL = 256;
    private static final int VD_FLAG_SHOULD_SHOW_SYSTEM_DECORATIONS = 512;
    private static final int VD_FLAG_TRUSTED = 1024;
    private static final int VD_FLAG_OWN_DISPLAY_GROUP = 2048;
    private static final int VD_FLAG_ALWAYS_UNLOCKED = 4096;

    private static Process process;
    private static DataOutputStream out;
    private static long lastStartAttemptMs;
    private static volatile long lastAutoRestartMs;
    private static boolean permanentlyFailed;

    /**
     * 守护进程最近报过注入故障 —— 只用于去重提示，**不再阻断后续注入**。
     * 之前拿它当闸门，结果一次试错（形态 0 被拒、形态 1 成功）就把整场输入全变成
     * 「写入失败」：面板上看着像通道全废，其实是我们自己关的门。注入必须每帧真发，
     * 能不能成由系统和形态自适应说了算。
     */
    private static volatile boolean injectBroken;
    /** 守护进程报告过 /dev/uinput 真鼠标设备已创建（root 通道） */
    public static volatile boolean sUinputReady = false;

    /** 由 root UID 创建的虚拟屏；释放/改尺寸/换 Surface 必须路由回 root 桥。 */
    private static final Set<Integer> rootDisplays = ConcurrentHashMap.newKeySet();

    /**
     * root 常驻进程通过 RootBridgeLinkService 回送的 Binder 句柄。
     * 不能依赖 ServiceManager.getService()：自定义服务名落到 default_android_service，
     * SELinux 会拒绝 untrusted_app 的 { find }（真机 dmesg 已确认）。
     */
    private static volatile IBinder deliveredBridge;

    static {
        // ServiceManager.getService 是 @hide；MainActivity 已放开，这里再兜一次，
        // 避免独立进程 / 提前调用时静态初始化失败。幂等且开销可忽略。
        try {
            org.lsposed.hiddenapibypass.HiddenApiBypass.addHiddenApiExemptions("");
        } catch (Throwable ignored) {}
    }

    private RootInputBridge() {}

    /**
     * 应用启动即预热常驻桥：root 进程会通过 startService 把 Binder 句柄送回主线程，
     * 而 surfaceCreated 又跑在主线程。必须让句柄在虚拟屏创建之前就送达，
     * 否则 createVirtualDisplay 的等待会把自己的回调堵死（真机实测晚 1.97s）。
     * 只在纯 Root 通道、且后台线程里执行，不占主线程、不逐事件起进程。
     */
    public static void warmUp(final Context context) {
        if (context == null) return;
        final Context app = context.getApplicationContext();
        Thread t = new Thread(() -> {
            try {
                // probe 是同步且带缓存的；顺带让 PrivilegeRoute 在 surfaceCreated 之前就确定 ROOT。
                if (!RootCmd.probe(app)) return;
                ensureStarted(app);
            } catch (Throwable t2) {
                Log.w(TAG, "预热 root 常驻桥失败: " + t2.getMessage());
            }
        }, "AosenRootBridge-warmup");
        t.setDaemon(true);
        t.start();
    }

    /** 幂等启动；返回是否可写。失败时标记冷却 2s，避免高频重试拖垮 CPU。 */
    private static synchronized boolean ensureStarted(Context context) {
        if (out != null && isAlive(process)) return true;
        if (permanentlyFailed) return false;
        long now = System.currentTimeMillis();
        if (now - lastStartAttemptMs < 2000) return false;
        lastStartAttemptMs = now;
        stopLocked();

        try {
            File dex = prepareDex(context);
            if (dex == null) return false;
            File so = prepareUinputSo(context);
            String cmd = "export CLASSPATH=" + dex.getAbsolutePath()
                    + "; exec app_process /system/bin " + DAEMON_CLASS;
            // 把 native 库路径作为 argv[0] 交给守护进程：它在 main 里 System.load 之后
            // 才能往 /dev/uinput 造真鼠标设备。没有这个参数也不影响老的注入通道。
            if (so != null) cmd += " " + so.getAbsolutePath();
            Process p = Runtime.getRuntime().exec(new String[]{"su", "-c", cmd});
            // app_process 正常不写 stdout/stderr；仍开一个后台读线程防止管道写满阻塞注入。
            drain(p.getInputStream(), false);
            drain(p.getErrorStream(), true);
            out = new DataOutputStream(new BufferedOutputStream(p.getOutputStream(), 256));
            process = p;
            injectBroken = false;
            Log.i(TAG, "Root 常驻桥已启动");
            return true;
        } catch (Throwable t) {
            Log.w(TAG, "Root 常驻桥启动失败: " + t.getMessage());
            stopLocked();
            return false;
        }
    }

    /**
     * 输入帧到来时 daemon 不在：后台节流重建，绝不在调用线程（可能就是主线程）
     * 同步执行 su / 读 assets。2s 节流避免 daemon 死亡时高频事件刷线程把 CPU 打满。
     */
    private static void restartInBackground(Context context) {
        long now = System.currentTimeMillis();
        if (now - lastAutoRestartMs < 2000L) return;
        lastAutoRestartMs = now;
        final Context app = context.getApplicationContext();
        Thread t = new Thread(() -> {
            try {
                if (RootCmd.probe(app)) ensureStarted(app);
            } catch (Throwable ignored) {}
        }, "AosenRootBridge-restart");
        t.setDaemon(true);
        t.start();
    }

    /**
     * 注入一个完整 MotionEvent。返回 false 表示通道不可用，调用方应走原有兜底。
     * 高频事件（滚轮）由调用方先合并，这里不做额外排队。
     */
    public static synchronized boolean inject(Context context, int displayId, int action,
                                              int buttonState, int actionButton,
                                              float x, float y, float vscroll, float hscroll,
                                              int source) {
        if (displayId <= 0 || PrivilegeRoute.mode() != PrivilegeRoute.Mode.ROOT) return false;
        if (out == null || !isAlive(process)) {
            restartInBackground(context);
            return false;
        }
        try {
            out.writeByte(1);
            out.writeInt(displayId);
            out.writeInt(action);
            out.writeInt(buttonState);
            out.writeInt(actionButton);
            out.writeFloat(x);
            out.writeFloat(y);
            out.writeFloat(vscroll);
            out.writeFloat(hscroll);
            out.writeInt(source);
            out.flush();
            return true;
        } catch (Throwable t) {
            Log.w(TAG, "注入帧写入失败，重建通道: " + t.getMessage());
            stopLocked();
            return false;
        }
    }

    /**
     * 注入一个 KeyEvent（21 字节定长帧）。键盘/右键等按键事件绝不能走 shell 命令：
     * RootCmd.execute 会同步 readLine() 等结束标记，命令一旦在虚拟屏上卡住就会
     * 把主线程堵死 10 秒触发 ANR，还会让 sShellLock 永久占住、整条 root 命令链瘫痪。
     * 这里只写帧，空闲时 daemon 阻塞在 read，CPU 为 0。
     */
    public static synchronized boolean injectKey(Context context, int displayId, int action,
                                                  int keyCode, int metaState, int source) {
        if (displayId <= 0 || PrivilegeRoute.mode() != PrivilegeRoute.Mode.ROOT) return false;
        if (out == null || !isAlive(process)) {
            restartInBackground(context);
            return false;
        }
        try {
            out.writeByte(2);              // CMD_KEY
            out.writeInt(displayId);
            out.writeInt(action);
            out.writeInt(keyCode);
            out.writeInt(metaState);
            out.writeInt(source);
            out.flush();
            return true;
        } catch (Throwable t) {
            Log.w(TAG, "按键帧写入失败，重建通道: " + t.getMessage());
            stopLocked();
            return false;
        }
    }

    // ======================= Root UID 虚拟屏 =======================

    /**
     * 用 root UID 创建虚拟屏。返回 displayId，失败返回 -1（调用方回落应用侧 DisplayManager）。
     * 只带 PRESENTATION（私有屏）+ TRUSTED + 512，不加 PUBLIC：真机验证过私有屏照样能
     * 由 root/shell 用 am start --display 拉起三星 SecondaryLauncher，且不会把通知甩到外屏。
     */
    public static synchronized int createVirtualDisplay(Context context, String name,
                                                        int width, int height, int dpi,
                                                        Surface surface, boolean decorations) {
        if (surface == null || !surface.isValid()
                || PrivilegeRoute.mode() != PrivilegeRoute.Mode.ROOT) return -1;
        if (!ensureStarted(context)) return -1;
        IBinder svc = awaitBridgeService();
        if (svc == null) {
            Log.w(TAG, "root 桥 Binder 服务未就绪，回落应用侧创建虚拟屏");
            return -1;
        }
        // 必须用 PUBLIC 而不是 PRESENTATION：见 VD_FLAG_PUBLIC 处的说明。
        int flags = VD_FLAG_PUBLIC
                | VD_FLAG_SUPPORTS_TOUCH
                | VD_FLAG_DESTROY_CONTENT_ON_REMOVAL
                | VD_FLAG_TRUSTED
                | VD_FLAG_OWN_DISPLAY_GROUP
                | VD_FLAG_ALWAYS_UNLOCKED;
        if (decorations) flags |= VD_FLAG_SHOULD_SHOW_SYSTEM_DECORATIONS;

        final int finalFlags = flags;
        int id = transactWithTimeout(svc, TX_CREATE_VD, data -> {
            data.writeString(name);
            data.writeInt(width);
            data.writeInt(height);
            data.writeInt(dpi);
            data.writeInt(finalFlags);
            surface.writeToParcel(data, 0);
        });
        if (id > 0) {
            rootDisplays.add(id);
            Log.i(TAG, "root UID 创建虚拟屏成功 ID=" + id + " flags=" + finalFlags
                    + " decorations=" + decorations);
            return id;
        }
        Log.w(TAG, "root UID 创建虚拟屏返回 " + id + "，回落应用侧 DisplayManager");
        return -1;
    }

    public static boolean ownsDisplay(int displayId) {
        return displayId > 0 && rootDisplays.contains(displayId);
    }

    public static boolean hasTrackedDisplays() {
        return !rootDisplays.isEmpty();
    }

    public static java.util.List<Integer> trackedDisplayIds() {
        return new java.util.ArrayList<>(rootDisplays);
    }

    public static void releaseVirtualDisplay(int displayId) {
        if (displayId <= 0 || !rootDisplays.remove(displayId)) return;
        IBinder svc = lookupBridgeService();
        if (svc == null) return;
        int r = transactWithTimeout(svc, TX_RELEASE_VD, data -> data.writeInt(displayId));
        Log.i(TAG, "root UID 释放虚拟屏 ID=" + displayId + " result=" + r);
    }

    public static void resizeVirtualDisplay(int displayId, int width, int height, int dpi) {
        if (!ownsDisplay(displayId)) return;
        IBinder svc = lookupBridgeService();
        if (svc == null) return;
        transactWithTimeout(svc, TX_RESIZE_VD, data -> {
            data.writeInt(displayId);
            data.writeInt(width);
            data.writeInt(height);
            data.writeInt(dpi);
        });
    }

    public static void setVirtualDisplaySurface(int displayId, Surface surface) {
        if (!ownsDisplay(displayId) || surface == null || !surface.isValid()) return;
        IBinder svc = lookupBridgeService();
        if (svc == null) return;
        transactWithTimeout(svc, TX_SET_SURFACE_VD, data -> {
            data.writeInt(displayId);
            surface.writeToParcel(data, 0);
        });
    }

    /**
     * 以 root UID 设置某块屏的 IME 策略。0 = ON_SCREEN（输入法留在该屏）。
     * 纯 Root 下没有 Shizuku binder，而 setDisplayImePolicy 只存在于 IWindowManager，
     * 所以走常驻 root 服务的一次性事务；不在任何高频路径上。
     */
    public static boolean setDisplayImePolicy(int displayId, int policy) {
        if (displayId <= 0) return false;
        IBinder svc = lookupBridgeService();
        if (svc == null) return false;
        int r = transactWithTimeout(svc, TX_SET_IME_POLICY, data -> {
            data.writeInt(displayId);
            data.writeInt(policy);
        });
        Log.i(TAG, "root UID 设置 IME 策略 display=" + displayId
                + " policy=" + policy + " result=" + r);
        return r == 1;
    }

    private interface ParcelWriter {
        void write(Parcel data);
    }

    /**
     * Binder 同步事务没有超时，虚拟屏创建又跑在 surfaceCreated（UI 线程）上：
     * 一旦 root 端异常漏写 reply 就会直接 ANR。这里放到独立线程 + 3.5s 上限，
     * 超时即回落应用侧 DisplayManager。一次虚拟屏生命周期只有几次事务，开销可忽略。
     */
    private static int transactWithTimeout(IBinder svc, int code, ParcelWriter writer) {
        if (svc == null) return -1;
        java.util.concurrent.FutureTask<Integer> task =
                new java.util.concurrent.FutureTask<>(() -> {
                    Parcel data = Parcel.obtain();
                    Parcel reply = Parcel.obtain();
                    try {
                        data.writeInterfaceToken(SERVICE_NAME);
                        writer.write(data);
                        svc.transact(code, data, reply, 0);
                        reply.readException();
                        return reply.readInt();
                    } finally {
                        data.recycle();
                        reply.recycle();
                    }
                });
        Thread t = new Thread(task, "AosenRootBridge-tx-" + code);
        t.setDaemon(true);
        t.start();
        try {
            return task.get(3500L, java.util.concurrent.TimeUnit.MILLISECONDS);
        } catch (Throwable e) {
            Log.w(TAG, "root 桥事务 " + code + " 超时/异常: " + e);
            return -1;
        }
    }

    /**
     * 等待 app_process 把 Binder 服务注册到 servicemanager。
     *
     * 实测：冷启动（刚装完 APK / 刚重启）时桥进程要 1.5~3s 才把 Binder 送到
     * RootBridgeLinkService，原来 1s 的等待会在这里超时，然后回落应用侧建屏，
     * 而应用侧建屏没有 CAPTURE_VIDEO_OUTPUT 权限必然失败 → 桌面起不来。
     * 所以这里放宽到 4s：只在首次建屏时付这个代价，后续 Binder 已缓存。
     */
    private static IBinder awaitBridgeService() {
        for (int i = 0; i < 80; i++) {
            IBinder b = lookupBridgeService();
            if (b != null && b.pingBinder()) return b;
            try {
                Thread.sleep(50L);
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        return null;
    }

    /** root 常驻进程把句柄送到 RootBridgeLinkService 后调用。 */
    public static void setBridgeBinder(IBinder binder) {
        deliveredBridge = binder;
    }

    private static IBinder lookupBridgeService() {
        IBinder delivered = deliveredBridge;
        if (delivered != null && delivered.pingBinder()) return delivered;
        // 兜底：部分设备/自定义 ROM 若允许 untrusted_app find，仍可直接从 servicemanager 取。
        try {
            Class<?> sm = Class.forName("android.os.ServiceManager");
            java.lang.reflect.Method m = sm.getDeclaredMethod("getService", String.class);
            m.setAccessible(true);
            return (IBinder) m.invoke(null, SERVICE_NAME);
        } catch (Throwable t) {
            try {
                return SystemServiceHelper.getSystemService(SERVICE_NAME);
            } catch (Throwable ignored) {
                return null;
            }
        }
    }

    // ======================= 生命周期 =======================

    /** 应用退出 / 任务被划掉时调用：stdin 关闭 → root 进程退出 → 虚拟屏随 callback 死亡自动回收。 */
    public static synchronized void shutdown() {
        for (Integer id : new java.util.ArrayList<>(rootDisplays)) {
            releaseVirtualDisplay(id);
        }
        rootDisplays.clear();
        deliveredBridge = null;
        stopLocked();
        permanentlyFailed = false;
        lastStartAttemptMs = 0;
    }

    private static void stopLocked() {
        deliveredBridge = null;
        DataOutputStream o = out;
        out = null;
        if (o != null) {
            try { o.close(); } catch (Throwable ignored) {}
        }
        Process p = process;
        process = null;
        if (p != null) {
            try { p.destroy(); } catch (Throwable ignored) {}
        }
    }

    private static boolean isAlive(Process p) {
        if (p == null) return false;
        try {
            p.exitValue();
            return false;
        } catch (IllegalThreadStateException e) {
            return true;
        }
    }

    /** dex 内容做 FNV 指纹并写进文件名：APK 覆盖安装后绝不复用旧版 root_bridge.dex。 */
    private static File prepareDex(Context context) throws IOException {
        File dir = context.getFilesDir();
        byte[] bytes;
        try (InputStream is = context.getAssets().open(ASSET_DEX)) {
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            byte[] buf = new byte[4096];
            int n;
            while ((n = is.read(buf)) > 0) bos.write(buf, 0, n);
            bytes = bos.toByteArray();
        }
        long hash = 1469598103934665603L;
        for (byte b : bytes) {
            hash ^= (b & 0xFF);
            hash *= 1099511628211L;
        }
        File dex = new File(dir, "root_bridge_" + Long.toHexString(hash) + ".dex");
        if (dex.exists() && dex.length() == bytes.length) return dex;
        File tmp = new File(dir, dex.getName() + ".tmp");
        try (OutputStream os = new FileOutputStream(tmp)) {
            os.write(bytes);
        }
        if (!tmp.renameTo(dex)) {
            try (OutputStream os = new FileOutputStream(dex)) {
                os.write(bytes);
            }
            // 保留 tmp 无妨，下次启动会覆盖
        }
        try { dex.setReadable(true, false); } catch (Throwable ignored) {}
        return dex;
    }

    /**
     * 把 libaosen_uinput.so 从 APK 的 lib 目录复制到应用私有目录。
     * app_process（root 域）读 /data/app/.../lib 有可能被 SELinux 拦，私有目录更稳。
     * 只在长度变化时重写，避免每次重建通道都做一次无意义拷贝。
     */
    private static File prepareUinputSo(Context context) {
        try {
            File src = new File(context.getApplicationInfo().nativeLibraryDir, "libaosen_uinput.so");
            if (!src.exists()) return null;
            File dst = new File(context.getFilesDir(), "libaosen_uinput.so");
            if (!dst.exists() || dst.length() != src.length()) {
                byte[] buf = new byte[8192];
                try (InputStream is = new java.io.FileInputStream(src);
                     OutputStream os = new FileOutputStream(dst)) {
                    int n;
                    while ((n = is.read(buf)) > 0) os.write(buf, 0, n);
                }
            }
            try {
                dst.setReadable(true, false);
                dst.setExecutable(true, false);
            } catch (Throwable ignored) {}
            return dst;
        } catch (Throwable t) {
            Log.w(TAG, "uinput 库准备失败: " + t);
            return null;
        }
    }

    // ===================== uinput 真设备通道 =====================
    // 和 inject() 走同一个守护进程、同一条管道，只是帧类型不同：那边是把事件塞进
    // 系统派发队列（会被校验、会抢指针），这边是往 /dev/uinput 造的真鼠标写事件
    // （系统当硬件读，不占手机屏的 PointerCapture）。

    private static synchronized boolean writeUinputFrame(int op, float dx, float dy,
                                                         int btn, int down) {
        if (out == null || !isAlive(process)) return false;
        try {
            out.writeByte(3);          // CMD_UINPUT
            out.writeInt(op);
            out.writeFloat(dx);
            out.writeFloat(dy);
            out.writeInt(btn);
            out.writeInt(down);
            out.flush();
            return true;
        } catch (Throwable t) {
            Log.w(TAG, "uinput 帧写入失败，重建通道: " + t.getMessage());
            stopLocked();
            return false;
        }
    }

    public static boolean uinputMove(Context context, float dx, float dy) {
        if (PrivilegeRoute.mode() != PrivilegeRoute.Mode.ROOT) return false;
        if (out == null || !isAlive(process)) {
            restartInBackground(context);
            return false;
        }
        return writeUinputFrame(0, dx, dy, 0, 0);
    }

    public static boolean uinputButton(Context context, int btn, boolean down) {
        if (PrivilegeRoute.mode() != PrivilegeRoute.Mode.ROOT) return false;
        if (out == null || !isAlive(process)) {
            restartInBackground(context);
            return false;
        }
        return writeUinputFrame(1, 0f, 0f, btn, down ? 1 : 0);
    }

    public static boolean uinputScroll(Context context, float v, float h) {
        if (PrivilegeRoute.mode() != PrivilegeRoute.Mode.ROOT) return false;
        if (out == null || !isAlive(process)) {
            restartInBackground(context);
            return false;
        }
        return writeUinputFrame(2, h, v, 0, 0);
    }

    // ==================== 物理鼠标独占通道 ====================

    /**
     * 抢到真鼠标之后，事件从 daemon 以文本行送上来（RBM/RBB/RBS），
     * 由这里转成上层能用的动作。走这条路时系统根本收不到这颗鼠标的事件，
     * 所以手机屏上没有指针、不需要 PointerCapture、也不会误触边缘手势。
     */
    public interface RawMouseSink {
        void onRawMotion(float dx, float dy);
        void onRawButton(int androidButton, boolean down);
        void onRawScroll(float v, float h);
    }

    private static volatile RawMouseSink rawSink;
    private static volatile boolean sRawGrabbed;
    /** 被独占的真实设备名与 fd：日志对账用（大脑握着谁 / 造出了谁）。 */
    private static volatile String sGrabName = "";
    private static volatile int sGrabFd = -1;

    public static void setRawMouseSink(RawMouseSink sink) { rawSink = sink; }

    public static boolean isRawGrabbed() { return sRawGrabbed; }

    /** 大脑当前握着的真实设备名（没握住返回空串）。 */
    public static String grabbedName() { return sGrabName; }

    public static int grabbedFd() { return sGrabFd; }

    /** 请求独占物理鼠标（幂等）。成功与否看日志里的 RBG 行。 */
    public static boolean grabPhysicalMouse(Context context) {
        if (PrivilegeRoute.mode() != PrivilegeRoute.Mode.ROOT) return false;
        if (out == null || !isAlive(process)) {
            restartInBackground(context);
            return false;
        }
        try {
            synchronized (RootInputBridge.class) {
                out.writeByte(4);   // CMD_GRAB
                out.flush();
            }
            return true;
        } catch (Throwable t) {
            Log.w(TAG, "grab 请求失败: " + t.getMessage());
            return false;
        }
    }

    public static void releasePhysicalMouse(Context context) {
        if (PrivilegeRoute.mode() != PrivilegeRoute.Mode.ROOT) return;
        if (out == null || !isAlive(process)) return;
        try {
            synchronized (RootInputBridge.class) {
                out.writeByte(5);   // CMD_UNGRAB
                out.flush();
            }
        } catch (Throwable ignored) {}
        sRawGrabbed = false;
    }

    private static void handleRawLine(String line) {
        try {
            RawMouseSink sink = rawSink;
            if (line.startsWith("RBM ")) {
                int i = line.indexOf(' ');
                int j = line.indexOf(' ', i + 1);
                if (j < 0) return;
                float dx = Float.parseFloat(line.substring(i + 1, j));
                float dy = Float.parseFloat(line.substring(j + 1));
                if (sink != null) sink.onRawMotion(dx, dy);
            } else if (line.startsWith("RBB ")) {
                int i = line.indexOf(' ');
                int j = line.indexOf(' ', i + 1);
                if (j < 0) return;
                int mask = Integer.parseInt(line.substring(i + 1, j));
                boolean down = !"0".equals(line.substring(j + 1).trim());
                if (sink != null) sink.onRawButton(mask, down);
            } else if (line.startsWith("RBS ")) {
                int i = line.indexOf(' ');
                int j = line.indexOf(' ', i + 1);
                if (j < 0) return;
                float v = Float.parseFloat(line.substring(i + 1, j));
                float h = Float.parseFloat(line.substring(j + 1));
                if (sink != null) sink.onRawScroll(v, h);
            } else if (line.startsWith("RBG ")) {
                String payload = line.substring(4).trim();          // ok <fd>|<name> / fail <rc> / released
                sRawGrabbed = payload.startsWith("ok");
                sGrabName = "";
                sGrabFd = -1;
                if (sRawGrabbed) {
                    int bar = payload.indexOf('|');
                    String head = (bar >= 0) ? payload.substring(0, bar) : payload;   // ok <fd>
                    if (bar >= 0) sGrabName = payload.substring(bar + 1).trim();
                    int sp = head.indexOf(' ');
                    if (sp >= 0) {
                        try { sGrabFd = Integer.parseInt(head.substring(sp + 1).trim()); } catch (Throwable ignored) {}
                    }
                }
                /* 「大脑之前」的唯一证据：真实外设到底有没有被握进大脑。
                 * 没有这一行，后面的虚拟设备再正常也无法判断输入是从哪来的。 */
                if (sRawGrabbed) {
                    CanghaiErrorCollector.logExt("[大脑前] 真实鼠标已握住: " + sGrabName
                            + "  fd=" + sGrabFd + "（EVIOCGRAB，系统已看不见它）");
                } else if (payload.startsWith("released")) {
                    CanghaiErrorCollector.logExt("[大脑前] 真实鼠标已交还系统");
                } else {
                    CanghaiErrorCollector.logExt("[大脑前] 真实鼠标没能握住: " + payload
                            + "（-1=没找到符合的鼠标 -13=EACCES）");
                }
            }
        } catch (Throwable ignored) {}
    }

    private static void drain(final InputStream in, final boolean logLines) {        if (in == null) return;
        Thread t = new Thread(() -> {
            try {
                java.io.BufferedReader reader = new java.io.BufferedReader(
                        new java.io.InputStreamReader(in, "UTF-8"));
                String line;
                // daemon 只在启动/出错时写少量文本；正常高频输入通道完全不走这里。
                while ((line = reader.readLine()) != null) {
                    if (logLines || line.startsWith("RootBridge")) {
                        Log.i(TAG, "daemon: " + line);
                    }
                    // 守护进程的报告分两级：
                    //  INFO = 过程（形态切换成功、某形态被系统试用性拒绝），只记面板，不锁通道。
                    //  ERR  = 真故障（反射调用抛异常等），锁通道让上层走 shell 兜底，但 2 秒后自动放开，
                    //         否则一次抖动就永久瘫痪，日志里看到的全是无意义的「写入失败」。
                    if (line.contains("RootBridge INFO")) {
                        String msg = line.substring(
                                Math.min(line.length(), line.indexOf("RootBridge INFO") + 15));
                        CanghaiErrorCollector.logExt("[Root注入] " + msg);
                        if (msg.contains("uinput 真鼠标设备已创建")) sUinputReady = true;
                    } else if (line.contains("RootBridge ERR")) {
                        injectBroken = true;
                        CanghaiErrorCollector.logExt("[Root注入] " + line.substring(
                                Math.min(line.length(), line.indexOf("RootBridge ERR") + 15)));
                    } else if (line.startsWith("RB")) {
                        // 物理鼠标独占通道：daemon 把抢到的那颗真鼠标的原始事件按行送上来。
                        handleRawLine(line);
                    }
                }
            } catch (Throwable ignored) {
            } finally {
                try { in.close(); } catch (Throwable ignored) {}
            }
        }, "AosenRootBridge-drain");
        t.setDaemon(true);
        t.start();
    }
}
