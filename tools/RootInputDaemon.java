package com.aosen.shizukudex;

import android.content.Intent;
import android.os.Binder;
import android.os.Bundle;
import android.os.IBinder;
import android.os.Parcel;
import android.view.InputDevice;
import android.view.KeyCharacterMap;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.Surface;

import java.io.BufferedInputStream;
import java.io.DataInputStream;
import java.io.EOFException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Root 常驻进程的服务端（由 su 以 app_process 启动，CLASSPATH 指向 assets 里释放的 dex）。
 * 一个进程同时提供两类能力，绝不逐事件起进程：
 *
 *  1) 输入注入：读 stdin 的 37 字节定长帧，直接经 IInputManager binder 注入。
 *     鼠标移动/悬停根本不会送到这里（应用侧只自绘光标），空闲时阻塞在 read，CPU 为 0。
 *  2) 虚拟屏管理：注册 Binder 服务 "aosen.rootbridge"，以 root UID 调 IDisplayManager。
 *     应用 UID 创建的虚拟屏会被 DisplayManagerService 静默剥掉
 *     SHOULD_SHOW_SYSTEM_DECORATIONS(512) —— 那正是「官方桌面没壁纸 / 没 dock /
 *     应用全屏没标题栏」的根因。root UID(0) 自带全部权限，TRUSTED(1024)+512 能保住。
 *     创建/释放/改尺寸/换 Surface 都是一次性低频事务，不进高频输入通道。
 *
 * 修改本文件后重新生成 app/src/main/assets/root_bridge.dex：
 *   JBR="C:/Program Files/Android/Android Studio/jbr"; AJ=".../Sdk/platforms/android-34/android.jar"; \
 *   D8=".../Sdk/build-tools/37.0.0/lib/d8.jar"; \
 *   "$JBR/bin/javac" -cp "$AJ" -d tools/bridge_classes tools/RootInputDaemon.java && \
 *   "$JBR/bin/java" -cp "$D8" com.android.tools.r8.D8 --min-api 26 \
 *       --output app/src/main/assets tools/bridge_classes/com/aosen/shizukudex/RootInputDaemon*.class && \
 *   mv app/src/main/assets/classes.dex app/src/main/assets/root_bridge.dex
 */
public final class RootInputDaemon {

    private static final String SERVICE_NAME = "aosen.rootbridge";
    private static final int TX_CREATE_VD = 1;
    private static final int TX_RELEASE_VD = 2;
    private static final int TX_RESIZE_VD = 3;
    private static final int TX_SET_SURFACE_VD = 4;
    /** 设置该屏的 IME 策略：非主屏默认是 FALLBACK_DISPLAY，输入法会被送回手机。 */
    private static final int TX_SET_IME_POLICY = 5;

    private static final byte CMD_INJECT = 1;
    /** 键盘帧：displayId/action/keyCode/metaState/source，完全绕开 shell 命令。 */
    private static final byte CMD_KEY = 2;
    /**
     * uinput 真设备帧：op + dx + dy + btn + down。
     * 和 CMD_INJECT 的本质区别：这里不经过 IInputManager.injectInputEvent，
     * 是往 /dev/uinput 造出来的真鼠标节点写事件，系统当作物理硬件读走。
     * 不占手机屏的 PointerCapture、不会被「系统拒绝注入」、也不受注入校验限制。
     */
    private static final byte CMD_UINPUT = 3;
    private static final byte CMD_GRAB = 4;
    private static final byte CMD_UNGRAB = 5;

    private static final int UOP_MOVE = 0;
    private static final int UOP_BUTTON = 1;
    private static final int UOP_SCROLL = 2;

    private static volatile boolean sUinputReady;
    /** uinput 相对位移的亚像素累积。 */
    private static float sAccX;
    private static float sAccY;

    /** displayId -> 创建该屏时的 callback binder；持引用防止 binder 死亡导致虚拟屏被回收。 */
    private static final Map<Integer, Object> sCallbacks = new ConcurrentHashMap<>();

    private static Object sDisplayManager;
    private static Object sWindowManager;
    private static Class<?> sCallbackIface;
    private static Class<?> sProjectionIface;

    // ==================== 物理鼠标独占（pointer lock） ====================

    /* 抢走那颗真鼠标以后，系统完全看不到它：手机屏上没有第二根指针，也就不需要
     * PointerCapture，更不会在捕获被收回的那几秒里把指针漏到手机边缘触发系统手势。
     * 事件由这里读出来、按行送上去（RBM 位移 / RBB 按键 / RBS 滚轮），
     * 应用侧再写进我们自己造的 "Aosen Virtual Mouse"。 */
    private static volatile int sGrabFd = -1;

    private static synchronized void startGrabPhysicalMouse() {
        if (sGrabFd >= 0) { rawOut("RBG ok " + sGrabFd + "|" + grabNameSafe()); return; }
        int fd;
        try {
            fd = UinputNative.grabFirstMouse();
        } catch (Throwable t) {
            rawOut("RBG fail " + t);
            return;
        }
        if (fd < 0) {
            rawOut("RBG fail " + fd);
            return;
        }
        sGrabFd = fd;
        rawOut("RBG ok " + fd + "|" + grabNameSafe());
        Thread t = new Thread(() -> readGrabLoop(fd), "raw-mouse");
        t.setDaemon(true);
        t.start();
    }

    /** 被独占的真实设备名；拿不到就用占位，日志里必须能看出「握住了谁」。 */
    private static String grabNameSafe() {
        try {
            String n = UinputNative.grabbedName();
            return (n == null || n.isEmpty()) ? "未知设备" : n;
        } catch (Throwable t) {
            return "未知设备";
        }
    }

    private static synchronized void stopGrabPhysicalMouse() {
        int fd = sGrabFd;
        sGrabFd = -1;
        if (fd >= 0) {
            try { UinputNative.releaseGrab(fd); } catch (Throwable ignored) {}
        }
        rawOut("RBG released");
    }

    private static void readGrabLoop(int fd) {
        int[] ev = new int[3];
        int accX = 0, accY = 0;
        while (sGrabFd == fd) {
            int r;
            try { r = UinputNative.pollDevice(fd, 500); } catch (Throwable t) { break; }
            if (r < 0) break;
            if (r == 0) continue;
            while (sGrabFd == fd) {
                int n;
                try { n = UinputNative.readEvent(fd, ev); } catch (Throwable t) { n = -1; }
                if (n <= 0) break;
                final int type = ev[0], code = ev[1], val = ev[2];
                if (type == UinputNative.EV_REL) {
                    if (code == UinputNative.REL_X) accX += val;
                    else if (code == UinputNative.REL_Y) accY += val;
                    else if (code == UinputNative.REL_WHEEL) rawOut("RBS " + val + " 0");
                    else if (code == UinputNative.REL_HWHEEL) rawOut("RBS 0 " + val);
                } else if (type == UinputNative.EV_KEY) {
                    int mask = androidButtonMask(code);
                    if (mask != 0) rawOut("RBB " + mask + " " + (val != 0 ? 1 : 0));
                } else if (type == UinputNative.EV_SYN) {
                    if (accX != 0 || accY != 0) {
                        rawOut("RBM " + accX + " " + accY);
                        accX = 0;
                        accY = 0;
                    }
                }
            }
        }
    }

    private static int androidButtonMask(int kernelCode) {
        switch (kernelCode) {
            case UinputNative.BTN_LEFT: return android.view.MotionEvent.BUTTON_PRIMARY;
            case UinputNative.BTN_RIGHT: return android.view.MotionEvent.BUTTON_SECONDARY;
            case UinputNative.BTN_MIDDLE: return android.view.MotionEvent.BUTTON_TERTIARY;
            case UinputNative.BTN_SIDE: return android.view.MotionEvent.BUTTON_BACK;
            case UinputNative.BTN_EXTRA: return android.view.MotionEvent.BUTTON_FORWARD;
            default: return 0;
        }
    }

    private static void rawOut(String s) {
        try {
            System.out.println(s);
            System.out.flush();
        } catch (Throwable ignored) {}
    }

    public static void main(String[] args) {
        // args[0] = libaosen_uinput.so 的绝对路径（应用侧从 APK 的 lib 目录解出来）。
        // 没有它也不影响老通道：注入、虚拟屏照旧工作，只是没真设备。
        if (args != null && args.length > 0 && args[0] != null && args[0].length() > 0) {
            try {
                System.load(args[0]);
                int rc = UinputNative.open("Aosen Virtual Mouse");
                sUinputReady = (rc == 0);
                info(sUinputReady
                        ? "uinput 真鼠标设备已创建"
                        : "uinput 创建失败 rc=" + rc + " (-13=没权限 -2=没有 /dev/uinput)");
            } catch (Throwable t) {
                info("uinput 加载失败: " + t);
            }
        }
        // 显示服务初始化失败不能拖垮输入通道：两者互相独立，各自 try。
        // 纯 Root 通道下滚轮/次级按键已经验证可用，这条链路必须始终保住。
        try {
            initSystemBinders();
        } catch (Throwable t) {
            System.err.println("RootBridge display init failed: " + t);
        }
        try {
            registerBridgeService();
        } catch (Throwable t) {
            System.err.println("RootBridge service register failed: " + t);
        }
        // 阻塞读 stdin；app_process 启动的 binder 线程池并行处理 onTransact。
        serveInputFrames();
    }

    // ======================= 系统服务初始化 =======================

    private static void initSystemBinders() throws Exception {
        Class<?> sm = Class.forName("android.os.ServiceManager");
        Method getService = sm.getDeclaredMethod("getService", String.class);
        sCallbackIface = Class.forName("android.hardware.display.IVirtualDisplayCallback");
        sProjectionIface = Class.forName("android.media.projection.IMediaProjection");

        IBinder displayBinder = (IBinder) getService.invoke(null, "display");
        Class<?> stub = Class.forName("android.hardware.display.IDisplayManager$Stub");
        sDisplayManager = stub.getDeclaredMethod("asInterface", IBinder.class)
                .invoke(null, displayBinder);
    }

    /**
     * 非主屏的默认 IME 策略是 DISPLAY_IME_POLICY_FALLBACK_DISPLAY(1)：输入法被送回手机主屏，
     * 于是候选框出现在手机上、有时两边都不显示。改成 LOCAL(0) 才会在串流画面里弹出。
     * 一次串流只调用一次，不进任何高频路径。
     */
    private static synchronized boolean setDisplayImePolicy(int displayId, int policy) {
        try {
            if (sWindowManager == null) {
                Class<?> sm = Class.forName("android.os.ServiceManager");
                IBinder wmBinder = (IBinder) sm.getDeclaredMethod("getService", String.class)
                        .invoke(null, "window");
                if (wmBinder == null) return false;
                Class<?> stub = Class.forName("android.view.IWindowManager$Stub");
                sWindowManager = stub.getDeclaredMethod("asInterface", IBinder.class)
                        .invoke(null, wmBinder);
            }
            java.lang.reflect.Method target = null;
            for (java.lang.reflect.Method m : sWindowManager.getClass().getMethods()) {
                if ("setDisplayImePolicy".equals(m.getName())
                        && m.getParameterTypes().length == 2) {
                    target = m;
                    break;
                }
            }
            if (target == null) {
                System.err.println("RootBridge IWindowManager.setDisplayImePolicy not found");
                return false;
            }
            Object r = target.invoke(sWindowManager, displayId, policy);
            // 不同版本一个返回 void、一个返回 boolean。
            if (r instanceof Boolean) return ((Boolean) r).booleanValue();
            return true;
        } catch (Throwable t) {
            System.err.println("RootBridge set ime policy failed: " + t);
            return false;
        }
    }

    private static void registerBridgeService() throws Exception {
        BridgeBinder binder = new BridgeBinder();
        Class<?> sm = Class.forName("android.os.ServiceManager");
        Method addService = sm.getDeclaredMethod("addService", String.class, IBinder.class);
        addService.invoke(null, SERVICE_NAME, binder);
        System.out.println("RootBridge service registered");
        // 应用进程查不到这个服务（SELinux 拒绝 untrusted_app 对
        // default_android_service 的 { find }），必须把句柄主动送回去。
        // ContentProvider.call 走应用进程的 Binder 线程，不受主线程阻塞影响；
        // startService 作为兜底（它的 onStartCommand 排主线程，可能晚到）。
        deliverBridgeViaProvider(binder);
        deliverBridgeToApp(binder);
    }

    private static final String PROVIDER_AUTHORITY = "com.cunkai.canghai.aosenbridge";
    private static final String BINDER_KEY = "aosen_root_bridge";

    /** 主通道：把句柄送到应用进程的 Binder 线程（ContentProvider.call），绕开主线程排队。 */
    private static void deliverBridgeViaProvider(IBinder bridge) {
        Throwable last = null;
        // 路线 1：systemMain 的 system Context + ContentResolver.call
        try {
            // 非 zygote 的 app_process 默认没有主 Looper，systemMain() 会因此抛异常。
            try { android.os.Looper.prepareMainLooper(); } catch (Throwable ignored) {}
            Class<?> at = Class.forName("android.app.ActivityThread");
            Object thread = at.getDeclaredMethod("systemMain").invoke(null);
            android.content.Context ctx = (android.content.Context)
                    at.getDeclaredMethod("getSystemContext").invoke(thread);
            Bundle extras = new Bundle();
            extras.putBinder(BINDER_KEY, bridge);
            ctx.getContentResolver().call(
                    android.net.Uri.parse("content://" + PROVIDER_AUTHORITY),
                    "setBridge", null, extras);
            System.out.println("RootBridge binder delivered via provider(context)");
            return;
        } catch (Throwable t) {
            last = unwrap(t);
        }
        // 路线 2：直接拿 IActivityManager.getContentProvider 的 IContentProvider 再 call，
        // 完全不需要 Context / Looper，兼容性更好。
        try {
            Bundle extras = new Bundle();
            extras.putBinder(BINDER_KEY, bridge);
            Object icp = acquireContentProvider();
            if (icp == null) throw new IllegalStateException("IContentProvider is null");
            Method call = null;
            for (Method m : icp.getClass().getMethods()) {
                if (m.getName().equals("call") && m.getParameterTypes().length >= 5) {
                    call = m;
                    break;
                }
            }
            if (call == null) throw new IllegalStateException("IContentProvider.call not found");
            Class<?>[] types = call.getParameterTypes();
            Object[] args = new Object[types.length];
            int stringCount = 0;
            for (Class<?> c : types) if (c == String.class) stringCount++;
            int stringIndex = 0;
            for (int i = 0; i < types.length; i++) {
                Class<?> c = types[i];
                if (c == Bundle.class) args[i] = extras;
                else if (c == String.class) {
                    stringIndex++;
                    if (stringIndex == 1) args[i] = "com.android.shell";
                    else if (stringIndex == stringCount) args[i] = null;        // arg
                    else if (stringIndex == stringCount - 1) args[i] = "setBridge"; // method
                    else args[i] = PROVIDER_AUTHORITY;                          // authority/attributionTag
                } else args[i] = null;
            }
            call.invoke(icp, args);
            System.out.println("RootBridge binder delivered via provider(direct)");
            return;
        } catch (Throwable t) {
            last = unwrap(t);
        }
        System.err.println("RootBridge provider deliver failed: " + last
                + " at " + (last != null && last.getStackTrace().length > 0
                ? last.getStackTrace()[0] : "?"));
    }

    /** 通过 IActivityManager.getContentProvider 拿到应用 provider 的 IContentProvider。 */
    private static Object acquireContentProvider() throws Exception {
        Class<?> sm = Class.forName("android.os.ServiceManager");
        IBinder amBinder = (IBinder) sm.getDeclaredMethod("getService", String.class)
                .invoke(null, "activity");
        Object am = Class.forName("android.app.IActivityManager$Stub")
                .getDeclaredMethod("asInterface", IBinder.class).invoke(null, amBinder);
        Method getCp = null;
        for (Method m : am.getClass().getMethods()) {
            if (m.getName().equals("getContentProvider")) { getCp = m; break; }
        }
        if (getCp == null) throw new IllegalStateException("getContentProvider not found");
        // AMS 拒绝 caller == null（真机报 "null IApplicationThread when getting content
        // provider"）。root 进程没有 ApplicationThread，给一个只实现 asBinder 的轻量代理。
        final IBinder callerBinder = new Binder();
        Object caller = null;
        try {
            Class<?> iface = Class.forName("android.app.IApplicationThread");
            caller = Proxy.newProxyInstance(iface.getClassLoader(), new Class<?>[]{iface},
                    (proxy, method, a) -> {
                        if ("asBinder".equals(method.getName())) return callerBinder;
                        Class<?> r = method.getReturnType();
                        if (r == boolean.class) return false;
                        if (r == int.class) return 0;
                        if (r == long.class) return 0L;
                        if (r == float.class) return 0f;
                        if (r == double.class) return 0d;
                        if (r == short.class) return (short) 0;
                        if (r == byte.class) return (byte) 0;
                        if (r == char.class) return (char) 0;
                        return null;
                    });
        } catch (Throwable ignored) {}

        Class<?>[] types = getCp.getParameterTypes();
        Object[] args = new Object[types.length];
        int stringCount = 0;
        for (Class<?> c : types) if (c == String.class) stringCount++;
        int stringIndex = 0;
        for (int i = 0; i < types.length; i++) {
            Class<?> c = types[i];
            if (c == String.class) {
                stringIndex++;
                if (stringIndex == 1) args[i] = "com.android.shell";
                else if (stringIndex == stringCount) args[i] = PROVIDER_AUTHORITY; // name
                else args[i] = null;
            } else if (c.getName().contains("IApplicationThread")) args[i] = caller;
            else if (c == int.class) args[i] = 0;      // userId
            else if (c == boolean.class) args[i] = true; // stable
            else args[i] = null;
        }
        Object holder = getCp.invoke(am, args);
        if (holder == null) return null;
        for (java.lang.reflect.Field f : holder.getClass().getDeclaredFields()) {
            if (f.getType().getName().contains("IContentProvider")) {
                f.setAccessible(true);
                return f.get(holder);
            }
        }
        return null;
    }

    private static Throwable unwrap(Throwable t) {
        Throwable c = (t instanceof java.lang.reflect.InvocationTargetException)
                ? t.getCause() : t;
        return c == null ? t : c;
    }

    /**
     * 通过 IActivityManager.startService 把 Binder 句柄塞进 Intent 的 Bundle 送回应用。
     * Bundle.putBinder / getBinder 是公开 API，Binder 引用会随 Parcel 跨进程保留。
     * 一次性启动动作，绝不进高频输入通道。
     */
    private static void deliverBridgeToApp(IBinder bridge) {
        try {
            Class<?> sm = Class.forName("android.os.ServiceManager");
            IBinder amBinder = (IBinder) sm.getDeclaredMethod("getService", String.class)
                    .invoke(null, "activity");
            Class<?> stub = Class.forName("android.app.IActivityManager$Stub");
            Object am = stub.getDeclaredMethod("asInterface", IBinder.class)
                    .invoke(null, amBinder);
            if (am == null) throw new IllegalStateException("IActivityManager unavailable");

            Intent intent = new Intent();
            intent.setClassName("com.cunkai.canghai",
                    "com.aosen.shizukudex.RootBridgeLinkService");
            Bundle inner = new Bundle();
            inner.putBinder("aosen_root_bridge", bridge);
            Bundle outer = new Bundle();
            outer.putBundle("aosen_root_bridge_bundle", inner);
            intent.putExtras(outer);

            Method start = null;
            for (Method m : am.getClass().getMethods()) {
                if ("startService".equals(m.getName())
                        && m.getParameterTypes().length >= 6) {
                    start = m;
                    break;
                }
            }
            if (start == null) throw new IllegalStateException("startService not found");
            Class<?>[] types = start.getParameterTypes();
            Object[] args = new Object[types.length];
            for (int i = 0; i < types.length; i++) {
                Class<?> t = types[i];
                if (t == Intent.class) args[i] = intent;
                else if (t == String.class) args[i] = (i == 2) ? null : "com.android.shell";
                else if (t == boolean.class || t == Boolean.class) args[i] = false;
                else if (t == int.class || t == Integer.class) args[i] = 0;
                else args[i] = null;
            }
            start.invoke(am, args);
            System.out.println("RootBridge binder delivered to app");
        } catch (Throwable t) {
            System.err.println("RootBridge deliver failed: " + t);
        }
    }

    // ======================= 虚拟屏 Binder 服务 =======================

    private static final class BridgeBinder extends Binder {
        BridgeBinder() { attachInterface(null, SERVICE_NAME); }

        @Override
        protected boolean onTransact(int code, Parcel data, Parcel reply, int flags)
                throws android.os.RemoteException {
            // 任何分支都必须写 reply：Binder 事务没有超时，服务端漏写会让调用方 UI 线程永久阻塞。
            try {
                data.enforceInterface(SERVICE_NAME);
            } catch (Throwable t) {
                reply.writeNoException();
                reply.writeInt(-1);
                return true;
            }
            if (code == TX_CREATE_VD) {
                try {
                    String name = data.readString();
                    int w = data.readInt();
                    int h = data.readInt();
                    int dpi = data.readInt();
                    int vdFlags = data.readInt();
                    Surface surface = Surface.CREATOR.createFromParcel(data);
                    int id = createVirtualDisplay(name, w, h, dpi, surface, vdFlags);
                    reply.writeNoException();
                    reply.writeInt(id);
                } catch (Throwable t) {
                    System.err.println("RootBridge create VD failed: " + t);
                    reply.writeNoException();
                    reply.writeInt(-1);
                }
                return true;
            }
            if (code == TX_RELEASE_VD) {
                int id = data.readInt();
                boolean ok = releaseVirtualDisplay(id);
                reply.writeNoException();
                reply.writeInt(ok ? 1 : 0);
                return true;
            }
            if (code == TX_RESIZE_VD) {
                int id = data.readInt();
                int w = data.readInt();
                int h = data.readInt();
                int dpi = data.readInt();
                boolean ok = resizeVirtualDisplay(id, w, h, dpi);
                reply.writeNoException();
                reply.writeInt(ok ? 1 : 0);
                return true;
            }
            if (code == TX_SET_SURFACE_VD) {
                try {
                    int id = data.readInt();
                    Surface surface = Surface.CREATOR.createFromParcel(data);
                    boolean ok = setVirtualDisplaySurface(id, surface);
                    reply.writeNoException();
                    reply.writeInt(ok ? 1 : 0);
                } catch (Throwable t) {
                    System.err.println("RootBridge set surface failed: " + t);
                    reply.writeNoException();
                    reply.writeInt(0);
                }
                return true;
            }
            if (code == TX_SET_IME_POLICY) {
                try {
                    int id = data.readInt();
                    int policy = data.readInt();
                    boolean ok = setDisplayImePolicy(id, policy);
                    reply.writeNoException();
                    reply.writeInt(ok ? 1 : 0);
                } catch (Throwable t) {
                    System.err.println("RootBridge set ime policy tx failed: " + t);
                    reply.writeNoException();
                    reply.writeInt(0);
                }
                return true;
            }
            // 未知 code 也必须写回包，绝不让调用方悬在同步 transact 上。
            reply.writeNoException();
            reply.writeInt(-1);
            return true;
        }
    }

    /**
     * 以 root UID 创建虚拟屏：优先 Android 14+ VirtualDisplayConfig，回退 10-arg 老接口。
     * root UID(0) 不属于任何已安装包，DMS 的 validatePackageName 可能拒绝任意包名，
     * 因此依次尝试 shell / android / null（null 表示不校验）。都是一次性低频调用。
     */
    private static int createVirtualDisplay(String name, int w, int h, int dpi,
                                            Surface surface, int vdFlags) {
        if (sDisplayManager == null || surface == null) return -1;
        Object callback = newCallback();
        for (String pkg : new String[]{"com.android.shell", "android", null}) {
            int id = tryCreateVirtualDisplay(name, w, h, dpi, surface, vdFlags, callback, pkg);
            if (id > 0) {
                sCallbacks.put(id, callback);
                System.out.println("RootBridge VD created id=" + id + " pkg=" + pkg
                        + " flags=" + vdFlags);
                return id;
            }
        }
        return -1;
    }

    private static int tryCreateVirtualDisplay(String name, int w, int h, int dpi,
                                               Surface surface, int vdFlags,
                                               Object callback, String pkg) {
        // Android 14+ : createVirtualDisplay(VirtualDisplayConfig, callback, projection, package)
        try {
            Class<?> builderCls = Class.forName("android.hardware.display.VirtualDisplayConfig$Builder");
            Object builder = builderCls
                    .getConstructor(String.class, int.class, int.class, int.class)
                    .newInstance(name, w, h, dpi);
            builderCls.getMethod("setSurface", Surface.class).invoke(builder, surface);
            builderCls.getMethod("setFlags", int.class).invoke(builder, vdFlags);
            Object config = builderCls.getMethod("build").invoke(builder);
            Method m = sDisplayManager.getClass().getMethod("createVirtualDisplay",
                    Class.forName("android.hardware.display.VirtualDisplayConfig"),
                    sCallbackIface, sProjectionIface, String.class);
            Object r = m.invoke(sDisplayManager, config, callback, null, pkg);
            int id = ((Number) r).intValue();
            if (id > 0) return id;
        } catch (Throwable ignored) { /* 落到老接口 */ }

        // Android 13- : 10-arg createVirtualDisplay(callback, projection, package, name,
        //                                           w, h, dpi, surface, flags, extras)
        try {
            Method m = sDisplayManager.getClass().getMethod("createVirtualDisplay",
                    sCallbackIface, sProjectionIface, String.class, String.class,
                    int.class, int.class, int.class, Surface.class, int.class, Bundle.class);
            Object r = m.invoke(sDisplayManager, callback, null, pkg,
                    name, w, h, dpi, surface, vdFlags, null);
            return ((Number) r).intValue();
        } catch (Throwable ignored) {
            return -1;
        }
    }

    private static boolean releaseVirtualDisplay(int id) {
        Object callback = sCallbacks.remove(id);
        if (callback == null || sDisplayManager == null) return false;
        try {
            Method m = sDisplayManager.getClass().getMethod("releaseVirtualDisplay", sCallbackIface);
            m.invoke(sDisplayManager, callback);
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    private static boolean resizeVirtualDisplay(int id, int w, int h, int dpi) {
        Object callback = sCallbacks.get(id);
        if (callback == null || sDisplayManager == null) return false;
        try {
            Method m = sDisplayManager.getClass().getMethod("resizeVirtualDisplay",
                    sCallbackIface, int.class, int.class, int.class);
            m.invoke(sDisplayManager, callback, w, h, dpi);
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    private static boolean setVirtualDisplaySurface(int id, Surface surface) {
        Object callback = sCallbacks.get(id);
        if (callback == null || sDisplayManager == null || surface == null) return false;
        try {
            Method m = sDisplayManager.getClass().getMethod("setVirtualDisplaySurface",
                    sCallbackIface, Surface.class);
            m.invoke(sDisplayManager, callback, surface);
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    /** IVirtualDisplayCallback 的动态代理：DMS 只用 asBinder() 与三个 void 回调。 */
    private static Object newCallback() {
        final IBinder binder = new Binder();
        return Proxy.newProxyInstance(sCallbackIface.getClassLoader(),
                new Class<?>[]{sCallbackIface},
                (proxy, method, a) -> {
                    if ("asBinder".equals(method.getName())) return binder;
                    return null; // onPaused / onResumed / onStopped 都是 void
                });
    }

    // ======================= 输入注入 =======================

    private static void serveInputFrames() {
        try {
            Class<?> sm = Class.forName("android.os.ServiceManager");
            java.lang.reflect.Method getService = sm.getDeclaredMethod("getService", String.class);
            getService.setAccessible(true);
            IBinder inputBinder = (IBinder) getService.invoke(null, "input");
            Class<?> stub = Class.forName("android.hardware.input.IInputManager$Stub");
            java.lang.reflect.Method asInterface = stub.getDeclaredMethod("asInterface", IBinder.class);
            asInterface.setAccessible(true);
            Object im = asInterface.invoke(null, inputBinder);
            if (im == null) return;
            // 关键：asInterface 返回的是 IInputManager.Stub.Proxy —— 包私有内部类。
            // 不 setAccessible 时 Method.invoke 会抛 IllegalAccessException，而它又被
            // 外层 catch(Throwable) 吞掉，表现就是「root 下所有点击静默失效、
            // shizuku 下正常」。这里必须显式放开，并把失败打出来。
            Method inject = null;
            for (Method m : im.getClass().getDeclaredMethods()) {
                if (!m.getName().equals("injectInputEvent")) continue;
                Class<?>[] ps = m.getParameterTypes();
                // AIDL 声明是 injectInputEvent(in InputEvent event, int mode)：
                // 第一个参数是 MotionEvent 的父类 InputEvent，不能用 == MotionEvent.class 精确匹配。
                if (ps.length != 2 || ps[1] != int.class) continue;
                if (!ps[0].isAssignableFrom(MotionEvent.class)) continue;
                m.setAccessible(true);
                inject = m;
                break;
            }
            if (inject == null) {
                fail("找不到 IInputManager.injectInputEvent(MotionEvent,int)");
                return;
            }
            Method setDisplayId = Class.forName("android.view.InputEvent")
                    .getDeclaredMethod("setDisplayId", int.class);
            setDisplayId.setAccessible(true);
            Method setActionButton = MotionEvent.class.getDeclaredMethod("setActionButton", int.class);
            setActionButton.setAccessible(true);
            sInjector = im;
            sInject = inject;
            sSetDisplayId = setDisplayId;
            sSetActionButton = setActionButton;

            DataInputStream in = new DataInputStream(new BufferedInputStream(System.in, 1024));

            // 一次手势内 downTime 必须恒定不变：InputDispatcher 会丢弃 downTime 与
            // 当前手势起始时间不符的帧。之前每帧都写 obtain(now, now, ...)，
            // 结果 UP 全被系统丢掉 —— 表现就是「点一下抬起后一动鼠标变成框选」。
            long gestureDownTime = 0L;

            while (true) {
                final byte cmd;
                try { cmd = in.readByte(); }
                catch (EOFException e) { return; }

                // 键盘/按键走二进制帧，绝不逐事件起 su / 解析 shell 字符串。
                // 旧版应用侧用 `input -d <id> keyevent` 时主线程会同步等
                // BufferedReader.readLine() 的结束标记，按键卡住直接触发 ANR。
                if (cmd == CMD_GRAB) {
                    startGrabPhysicalMouse();
                    continue;
                }
                if (cmd == CMD_UNGRAB) {
                    stopGrabPhysicalMouse();
                    continue;
                }

                if (cmd == CMD_KEY) {
                    int kDisplayId = in.readInt();
                    int kAction = in.readInt();
                    int kKeyCode = in.readInt();
                    int kMeta = in.readInt();
                    int kSource = in.readInt();
                    long kNow = android.os.SystemClock.uptimeMillis();
                    KeyEvent ke = new KeyEvent(kNow, kNow, kAction, kKeyCode, 0, kMeta,
                            KeyCharacterMap.VIRTUAL_KEYBOARD, 0, 0, kSource);
                    // 不调 recycle()：InputEvent.recycle 是 @hide，SDK stub 里没有。
                    // KeyEvent 只是普通 Java 对象，交给 GC 即可，按键频率远不足以
                    // 造成分配压力；比每次反射查 hidden 方法更省。
                    setDisplayId.invoke(ke, kDisplayId);
                    try {
                        inject.invoke(im, ke, 0);
                    } catch (Throwable t) {
                        reportInjectFailure(t, kDisplayId, kAction, kSource);
                    }
                    continue;
                }

                if (cmd == CMD_UINPUT) {
                    int op = in.readInt();
                    float dx = in.readFloat();
                    float dy = in.readFloat();
                    int btn = in.readInt();
                    int down = in.readInt();
                    if (!sUinputReady) continue;
                    try {
                        if (op == UOP_MOVE) {
                            // 亚像素位移必须累积，否则慢速移动会被四舍五入成 0，
                            // 表现为「慢慢推鼠标指针完全不动」。
                            sAccX += dx;
                            sAccY += dy;
                            int ix = (int) sAccX;
                            int iy = (int) sAccY;
                            sAccX -= ix;
                            sAccY -= iy;
                            if (ix != 0) UinputNative.emit(UinputNative.EV_REL, UinputNative.REL_X, ix);
                            if (iy != 0) UinputNative.emit(UinputNative.EV_REL, UinputNative.REL_Y, iy);
                            if (ix != 0 || iy != 0) UinputNative.sync();
                        } else if (op == UOP_BUTTON) {
                            UinputNative.emit(UinputNative.EV_KEY, btn, down != 0 ? 1 : 0);
                            UinputNative.sync();
                        } else if (op == UOP_SCROLL) {
                            int v = Math.round(dy);
                            int h = Math.round(dx);
                            if (v != 0) UinputNative.emit(UinputNative.EV_REL, UinputNative.REL_WHEEL, v);
                            if (h != 0) UinputNative.emit(UinputNative.EV_REL, UinputNative.REL_HWHEEL, h);
                            if (v != 0 || h != 0) UinputNative.sync();
                        }
                    } catch (Throwable t) {
                        info("uinput 写事件失败: " + t);
                    }
                    continue;
                }

                if (cmd != CMD_INJECT) continue;

                int displayId = in.readInt();
                int action = in.readInt();
                int buttonState = in.readInt();
                int actionButton = in.readInt();
                float x = in.readFloat();
                float y = in.readFloat();
                float vscroll = in.readFloat();
                float hscroll = in.readFloat();
                int source = in.readInt();

                long now = android.os.SystemClock.uptimeMillis();
                final int masked = action & MotionEvent.ACTION_MASK;
                if (masked == MotionEvent.ACTION_DOWN
                        || masked == MotionEvent.ACTION_BUTTON_PRESS
                        || gestureDownTime == 0L) {
                    gestureDownTime = now;
                }
                // 悬停帧不属于任何手势，downTime 必须等于 eventTime，
                // 否则会被并进上一轮手势里。
                long downTime = (masked == MotionEvent.ACTION_HOVER_MOVE) ? now : gestureDownTime;

                boolean ok;
                if (source == InputDevice.SOURCE_MOUSE) {
                    ok = injectMouseAdaptive(displayId, action, buttonState, actionButton,
                            x, y, vscroll, hscroll, downTime, now);
                } else {
                    ok = tryInject(displayId, action, buttonState, actionButton,
                            x, y, vscroll, hscroll, source, false, downTime, now);
                }
                if (!ok) reportRejected(displayId, action, buttonState, source);
                // 不清空 gestureDownTime：右键是「BUTTON_PRESS → touch DOWN → BUTTON_RELEASE
                // → 440ms 后 UP」的混合序列，抬起帧必须还能拿到这一轮的 downTime。
                // 下一个 DOWN/BUTTON_PRESS 会覆盖它，所以不存在长期残留。
            }
        } catch (Throwable t) {
            fail("注入线程退出: " + describe(t));
        }
    }

    private static long sLastInjectErrMs;
    private static int sInjectErrCount;
    private static long sLastRejectMs;
    private static int sRejectCount;

    // 注入用的反射句柄。抽成静态字段是为了让「按形态重试」在方法之间复用，
    // 而不是每帧重新遍历一遍 Proxy 的方法表。
    private static Object sInjector;
    private static java.lang.reflect.Method sInject;
    private static java.lang.reflect.Method sSetDisplayId;
    private static java.lang.reflect.Method sSetActionButton;

    /**
     * 本机到底认哪种鼠标注入形态。不同固件对注入事件的校验不一样：
     * 有的要 actionButton、有的 UP 时必须没有 buttonState、有的只认触摸。
     * 0=buttonState+actionButton 1=只有 buttonState 2=只有 source 3=降级触摸。
     */
    private static int sMouseVariant = 0;

    /** 组一帧、注一帧；返回系统是否接受（injectInputEvent 的返回值就是答案）。 */
    private static boolean tryInject(int displayId, int action, int buttonState, int actionButton,
                                     float x, float y, float vscroll, float hscroll,
                                     int source, boolean mouseTool, long downTime, long now) {
        MotionEvent.PointerProperties props = new MotionEvent.PointerProperties();
        props.id = 0;
        props.toolType = mouseTool ? MotionEvent.TOOL_TYPE_MOUSE : MotionEvent.TOOL_TYPE_FINGER;
        MotionEvent.PointerCoords coords = new MotionEvent.PointerCoords();
        coords.x = x;
        coords.y = y;
        coords.pressure = (action == MotionEvent.ACTION_DOWN
                || action == MotionEvent.ACTION_MOVE) ? 1f : 0f;
        coords.size = 1f;
        coords.setAxisValue(MotionEvent.AXIS_VSCROLL, vscroll);
        coords.setAxisValue(MotionEvent.AXIS_HSCROLL, hscroll);
        MotionEvent ev = MotionEvent.obtain(downTime, now, action, 1,
                new MotionEvent.PointerProperties[]{props},
                new MotionEvent.PointerCoords[]{coords},
                0, buttonState, 1f, 1f, 0, 0, source, 0);
        try {
            if (actionButton != 0 && sSetActionButton != null) sSetActionButton.invoke(ev, actionButton);
            sSetDisplayId.invoke(ev, displayId);
            Object r = sInject.invoke(sInjector, ev, 0);
            return !(r instanceof Boolean) || ((Boolean) r).booleanValue();
        } catch (Throwable t) {
            // 单帧失败绝不拖垮整条通道：打印一次（限频），继续读下一帧。
            reportInjectFailure(t, displayId, action, source);
            return false;
        } finally {
            ev.recycle();
        }
    }

    /**
     * 鼠标帧自适应。上一版固定按「buttonState + actionButton」发，Android 16 直接
     * 回 false（系统拒绝注入）→ 左键全死。这里一个形态被拒就换下一个，成功就沿用
     * 并写进日志；鼠标形态全被拒时才降级成触摸（至少还能点，只是拖拽回到旧行为）。
     */
    private static boolean injectMouseAdaptive(int displayId, int action, int buttonState,
                                               int actionButton, float x, float y,
                                               float vscroll, float hscroll,
                                               long downTime, long now) {
        boolean hover = (action & MotionEvent.ACTION_MASK) == MotionEvent.ACTION_HOVER_MOVE;
        final int last = hover ? 3 : 4;   // 触摸没有悬停帧，hover 只试鼠标形态
        for (int i = 0; i <= last; i++) {
            final int v = (sMouseVariant + i) % (last + 1);
            final int source = (v == 4) ? InputDevice.SOURCE_TOUCHSCREEN : InputDevice.SOURCE_MOUSE;
            // v3 是「鼠标 source + FINGER toolType」，和 shell 的 `input mouse` 造出来的帧一致。
            final boolean mouseTool = (v != 3 && v != 4);
            final int bs = (v == 2 || v == 4) ? 0 : buttonState;
            final int ab = (v == 0) ? actionButton : 0;
            boolean ok = tryInject(displayId, action, bs, ab, x, y, vscroll, hscroll,
                    source, mouseTool, downTime, now);
            if (ok) {
                if (v != sMouseVariant) {
                    sMouseVariant = v;
                    info("鼠标注入形态改为 " + v
                            + " (0=按钮+动作键 1=仅按钮 2=仅source 3=鼠标源+手指工具 4=触摸)");
                }
                return true;
            }
        }
        return false;
    }

    /**
     * 被系统拒绝一次（injectInputEvent 返回 false）。这是自适应试错的正常一环：
     * 形态 0 被拒就换形态 1 再试，属于过程而不是故障，所以走 info 不走 fail。
     * 只有所有形态都失败才会由调用方报到界面。
     */
    private static void reportRejected(int displayId, int action, int buttonState, int source) {
        sRejectCount++;
        long now = android.os.SystemClock.uptimeMillis();
        if (sRejectCount > 10 && now - sLastRejectMs < 5000) return;
        sLastRejectMs = now;
        info("形态拒绝 x" + sRejectCount + " display=" + displayId
                + " action=" + action + " button=" + buttonState + " source=" + source);
    }

    /** 注入失败可见化：前 20 次逐条报，之后每 5s 报一次累计数，避免刷屏。 */
    private static void reportInjectFailure(Throwable t, int displayId, int action, int source) {
        sInjectErrCount++;
        long now = android.os.SystemClock.uptimeMillis();
        if (sInjectErrCount > 20 && now - sLastInjectErrMs < 5000) return;
        sLastInjectErrMs = now;
        fail("inject 失败 x" + sInjectErrCount + " display=" + displayId
                + " action=" + action + " source=" + source + " -> " + describe(t));
    }

    private static String describe(Throwable t) {
        Throwable c = unwrap(t);
        if (c == null) return "unknown";
        String s = c.getClass().getSimpleName() + ": " + c.getMessage();
        StackTraceElement[] st = c.getStackTrace();
        if (st != null && st.length > 0) s += " @ " + st[0];
        return s;
    }

    /** 错误出口：写 stderr（应用侧 drain 会转进 logcat 与调试面板），绝不静默。 */
    private static void fail(String msg) {
        try {
            System.err.println("RootBridge ERR " + msg);
            System.err.flush();
        } catch (Throwable ignored) {
        }
    }

    /**
     * 普通信息出口。必须和 fail 分开：应用侧收到 ERR 会把整条通道标记为坏掉，
     * 而「形态切换成功」「试错被拒」这类都是正常过程，走 ERR 会把自己锁死。
     */
    private static void info(String msg) {
        try {
            System.out.println("RootBridge INFO " + msg);
            System.out.flush();
        } catch (Throwable ignored) {
        }
    }

    private RootInputDaemon() {}
}
