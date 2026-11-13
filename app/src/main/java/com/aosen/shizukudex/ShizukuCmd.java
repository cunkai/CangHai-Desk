package com.aosen.shizukudex;

import android.content.ComponentName;
import android.content.Context;
import android.content.ServiceConnection;
import android.content.pm.PackageManager;
import android.hardware.display.DisplayManager;
import android.hardware.display.IDisplayManager;
import android.hardware.display.IVirtualDisplayCallback;
import android.hardware.display.VirtualDisplay;
import android.hardware.display.VirtualDisplayConfig;
import android.hardware.input.IInputManager;
import android.os.Build;
import android.os.IBinder;
import android.util.Log;
import android.view.Display;
import android.view.DisplayAddress;
import android.view.DisplayInfo;
import android.view.IWindowManager;
import android.view.InputDevice;
import android.view.InputEvent;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.Surface;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import rikka.shizuku.Shizuku;
import rikka.shizuku.ShizukuBinderWrapper;
import rikka.shizuku.SystemServiceHelper;

public class ShizukuCmd {

    private static final String TAG = "ShizukuDeX_Cmd";

    private static volatile IUserService userService;
    private static volatile boolean isBinding = false;

    /**
     * 应用级 Context。纯 Root 下不少能力（外设绑定 / 解绑）需要枚举 InputDevice，
     * 而调用方不一定带着 Context，这里统一留一份，避免每处都加参数。
     */
    private static volatile Context appContext;

    public static void setAppContext(Context ctx) {
        if (ctx != null) appContext = ctx.getApplicationContext();
    }

    public static Context appContext() {
        return appContext;
    }

    private static final Map<Integer, VirtualDisplay> localDisplays = new ConcurrentHashMap<>();
    private static final Map<Integer, IVirtualDisplayCallback> displayCallbacks = new ConcurrentHashMap<>();

    private static final Shizuku.UserServiceArgs userServiceArgs =
            new Shizuku.UserServiceArgs(new ComponentName(BuildConfig.APPLICATION_ID, UserService.class.getName()))
                    .daemon(false)
                    .tag("shizuku_dex")
                    .processNameSuffix("service")
                    .debuggable(false)
                    .version(BuildConfig.VERSION_CODE);

    private static final ServiceConnection userServiceConnection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder binder) {
            if (binder != null && binder.pingBinder()) {
                userService = IUserService.Stub.asInterface(binder);
                isBinding = false;
                Log.i(TAG, "Shizuku UserService 已连接: " + name);
            }
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            userService = null;
            isBinding = false;
            Log.w(TAG, "Shizuku UserService 断开连接: " + name);
        }
    };

    public static boolean isShizukuAvailable() {
        if (RootCmd.isAvailable()) return true;
        try {
            if (Shizuku.isPreV11() || !Shizuku.pingBinder()) {
                return false;
            }
            return Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED;
        } catch (Throwable ignored) {
            return false;
        }
    }

    /**
     * Shizuku 通道是否真的能用：binder 活着 <b>并且</b> 本应用已被授权。
     *
     * <p>所有「是否走 Shizuku」的判断都必须用这个，绝不能只用 {@link Shizuku#pingBinder()}：
     * 手机上装了 Shizuku 但从未授权时，binder 依然是活的，pingBinder() 返回 true，
     * 代码于是走进 Shizuku 分支，拿到的是裸 binder，一调用就
     * {@code SecurityException: transactRemote requires permission}。
     * 这类异常大多被外层 catch 吞掉，表现为「功能静默不生效」（外设绑不上屏、
     * 虚拟屏创建退回兜底、IME 策略不生效），排查时完全看不到报错。
     */
    public static boolean isShizukuReady() {
        try {
            if (Shizuku.isPreV11()) return false;
            return Shizuku.pingBinder()
                    && Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED;
        } catch (Throwable ignored) {
            return false;
        }
    }

    /**
     * 输入设备关联（addUniqueIdAssociationByDescriptor，把 USB/蓝牙鼠标键盘路由到外接屏）
     * 只能通过 ShizukuBinderWrapper 拿到 IInputManager，Root shell 无法替代。
     * 注意与 isShizukuAvailable() 区分：后者在纯 Root 环境也返回 true，不能用来判断外设路由能力。
     */
    public static boolean isInputBindingAvailable() {
        return isShizukuReady();
    }

    public static void init() {
        if (!isShizukuAvailable()) {
            Log.w(TAG, "Shizuku 权限不可用");
            return;
        }
        bindUserService();
    }

    private static synchronized void bindUserService() {
        if (userService != null && userService.asBinder().pingBinder()) {
            return;
        }
        if (isBinding) return;

        try {
            isBinding = true;
            Log.i(TAG, "发起 Shizuku bindUserService...");
            Shizuku.bindUserService(userServiceArgs, userServiceConnection);
        } catch (Throwable t) {
            isBinding = false;
            Log.e(TAG, "bindUserService 失败: " + t.getMessage(), t);
        }
    }

    /** 需要 shell/系统特权的命令前缀。
     *  bindUserService 起的 UserService 以「App 自己的 UID」运行，am force-stop /
     *  am task resize / am stack list / input 等命令在该 UID 下会静默失败（stderr 原本还没被读），
     *  表现为「点关闭没反应、窗口拉不满全屏」。这类命令必须优先走 Shizuku.newProcess（shell UID）。
     *  am start 不在此列：现有路径已验证可用，改走会引入重复启动风险。 */
    private static boolean needsShellPrivilege(String cmd) {
        String c = cmd == null ? "" : cmd.trim();
        // 只收窄到确实需要特权的命令，避免影响 app 其它既有命令的行为
        return c.startsWith("am force-stop")
                || c.startsWith("am task ")
                || c.startsWith("am stack ")
                || c.startsWith("wm ")
                || c.startsWith("input ")
                /* ime 是 shell 脚本，改的是 Settings.Secure 里的输入法名单，
                   App UID 调用必然 Permission Denial（表面上还返回空串，看不出失败）。 */
                || c.startsWith("ime ");
    }

    /** 走 Shizuku.newProcess 以 shell UID 执行命令；不可用返回 null（调用方自行回退）。 */
    private static String runViaShizukuShell(String cmd) {
        try {
            if (!isShizukuReady()) return null;
            java.lang.reflect.Method m = Shizuku.class.getDeclaredMethod(
                    "newProcess", String[].class, String[].class, String.class);
            m.setAccessible(true);
            Process process = (Process) m.invoke(null,
                    new String[]{"sh", "-c", cmd + " 2>&1"}, null, null);
            if (process == null) return null;
            BufferedReader reader = new BufferedReader(
                    new InputStreamReader(process.getInputStream()));
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) sb.append(line).append("\n");
            reader.close();
            process.waitFor();
            return sb.toString().trim();
        } catch (Throwable t) {
            Log.w(TAG, "Shizuku.newProcess 反射执行提示: " + t.getMessage());
            return null;
        }
    }

    public static String execute(String cmd) {
        // 若具备 Root 特权，优先直接走超低延迟 Root Shell
        if (RootCmd.isAvailable()) {
            String r = RootCmd.execute(cmd);
            if (r != null) return r;
        }

        // 特权命令优先用 shell UID，成功后直接返回，避免 App UID 静默失败
        if (needsShellPrivilege(cmd)) {
            String viaShell = runViaShizukuShell(cmd);
            if (viaShell != null) return viaShell;
        }

        if (userService != null) {
            try {
                return userService.executeCommand(cmd);
            } catch (Throwable t) {
                Log.w(TAG, "userService.executeCommand 异常: " + t.getMessage());
            }
        }

        // 未绑定 UserService 时，仍尝试 Shizuku shell 通道
        String viaShell = runViaShizukuShell(cmd);
        if (viaShell != null) return viaShell;
        return runLocalCommand(cmd);
    }

    // ==================== /dev/uinput 真鼠标（Shizuku 通道） ====================
    // 设备在 Shizuku 拉起的独立进程里建（见 UserService.uinputOpen），应用侧只
    // 通过 binder 往里写相对位移/按键。root 走的是一条完全独立的 daemon 通道，
    // 两者由 VirtualMouse 统一对外。

    /** 建虚拟鼠标；返回 0 成功，负数见 UserService 里的约定（-13 = EACCES）。 */
    public static int uinputOpen(Context ctx) {
        if (!isShizukuReady()) return -100;
        Context c = (ctx != null) ? ctx : appContext;
        if (c == null) return -101;
        if (userService == null) bindUserService();
        // 绑定是异步的，这里等一小会儿——调用方（VirtualMouse）本来就在后台线程
        for (int i = 0; i < 24 && userService == null; i++) {
            try { Thread.sleep(50); } catch (InterruptedException ignored) {}
        }
        if (userService == null) return -102;
        String soPath = "";
        String apkPath = "";
        try {
            soPath = new java.io.File(c.getApplicationInfo().nativeLibraryDir,
                    "libaosen_uinput.so").getAbsolutePath();
        } catch (Throwable ignored) {}
        try {
            apkPath = c.getApplicationInfo().sourceDir;
        } catch (Throwable ignored) {}
        try {
            int rc = userService.uinputOpen(soPath, apkPath, "Aosen Virtual Mouse");
            Log.i(TAG, "uinputOpen rc=" + rc);
            return rc;
        } catch (Throwable t) {
            Log.w(TAG, "uinputOpen 异常: " + t.getMessage());
            return -103;
        }
    }

    public static boolean uinputReady() {
        if (userService == null) return false;
        try {
            return userService.uinputReady();
        } catch (Throwable t) {
            return false;
        }
    }

    public static void uinputMove(int dx, int dy) {
        if (userService == null || (dx == 0 && dy == 0)) return;
        try {
            userService.uinputMove(dx, dy);
        } catch (Throwable ignored) {}
    }

    public static void uinputButton(int btn, boolean down) {
        if (userService == null) return;
        try {
            userService.uinputButton(btn, down);
        } catch (Throwable ignored) {}
    }

    public static void uinputScroll(int v, int h) {
        if (userService == null) return;
        try {
            userService.uinputScroll(v, h);
        } catch (Throwable ignored) {}
    }

    public static void uinputClose() {
        if (userService == null) return;
        try {
            userService.uinputClose();
        } catch (Throwable ignored) {}
    }

    /**
     * 把某个输入设备（这里就是 uinput 造出来的虚拟鼠标）绑定到指定显示屏。
     * 和 bindSingleInputDevice 的区别：那个被外设大脑整体接管后屏蔽了，这里专供
     * 虚拟鼠标自己用，不再走「统一调度」的闸门。
     */
    public static boolean bindDescriptorToDisplay(String descriptor, int displayId) {
        if (descriptor == null || descriptor.isEmpty() || displayId <= 0) return false;
        try {
            if (PrivilegeRoute.mode() == PrivilegeRoute.Mode.ROOT) {
                return RootInputBinder.bindSingle(appContext, descriptor, displayId);
            }
            if (!isShizukuReady()) return false;
            IInputManager iim = getInputManager();
            if (iim == null) return false;
            try { iim.removeUniqueIdAssociationByDescriptor(descriptor); } catch (Throwable ignored) {}
            int actualDisplayId = StreamTarget.get().resolveDisplayId(displayId);
            if (actualDisplayId < 0) return false;
            IBinder displayBinder = new ShizukuBinderWrapper(
                    SystemServiceHelper.getSystemService(Context.DISPLAY_SERVICE));
            IDisplayManager idm = IDisplayManager.Stub.asInterface(displayBinder);
            DisplayInfo info = idm.getDisplayInfo(actualDisplayId);
            if (info == null || info.uniqueId == null) return false;
            iim.addUniqueIdAssociationByDescriptor(descriptor, String.valueOf(info.uniqueId));
            Log.i(TAG, "虚拟鼠标 [" + descriptor + "] 绑定到 display " + actualDisplayId);
            return true;
        } catch (Throwable t) {
            Log.w(TAG, "虚拟鼠标绑定异常: " + t.getMessage());
            return false;
        }
    }

    /**
     * 摘掉某一台虚拟外设的屏关联，让它回到系统默认路由（手机自身屏幕）。
     * 用户把真实鼠标从外接屏拖回手机时走这里；旧的全量解绑接口已被大脑接管屏蔽，不能用。
     */
    public static boolean releaseDescriptor(String descriptor) {
        if (descriptor == null || descriptor.isEmpty()) return false;
        try {
            if (PrivilegeRoute.mode() == PrivilegeRoute.Mode.ROOT) {
                return RootInputBinder.bindSingle(appContext, descriptor, 0);
            }
            if (!isShizukuReady()) return false;
            IInputManager iim = getInputManager();
            if (iim == null) return false;
            iim.removeUniqueIdAssociationByDescriptor(descriptor);
            return true;
        } catch (Throwable t) {
            Log.w(TAG, "虚拟外设解除屏关联异常: " + t.getMessage());
            return false;
        }
    }

    public static String uinputStatus() {
        if (userService == null) return "userService=null";
        try {
            return userService.uinputStatus();
        } catch (Throwable t) {
            return "uinputStatus 异常: " + t.getMessage();
        }
    }

    private static String runLocalCommand(String cmd) {        try {
            Process process = Runtime.getRuntime().exec(new String[]{"sh", "-c", cmd});
            BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()));
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                sb.append(line).append("\n");
            }
            reader.close();
            process.waitFor();
            return sb.toString().trim();
        } catch (Throwable ignored) {}

        return "";
    }

    /**
     * 走 Shizuku shell 通道执行命令并读取原始字节（screencap 这类二进制输出用）。
     * 注意必须把 stderr 丢掉，否则错误文本会混进 PNG 数据里。
     */
    private static byte[] runBinaryViaShizukuShell(String cmd, int maxBytes) {
        Process process = null;
        try {
            if (!isShizukuReady()) return null;
            java.lang.reflect.Method m = Shizuku.class.getDeclaredMethod(
                    "newProcess", String[].class, String[].class, String.class);
            m.setAccessible(true);
            process = (Process) m.invoke(null,
                    new String[]{"sh", "-c", cmd + " 2>/dev/null"}, null, null);
            if (process == null) return null;
            ByteArrayOutputStream bos = new ByteArrayOutputStream(512 * 1024);
            byte[] buf = new byte[32 * 1024];
            int n;
            int total = 0;
            InputStream is = process.getInputStream();
            while ((n = is.read(buf)) > 0) {
                total += n;
                if (total > maxBytes) break;
                bos.write(buf, 0, n);
            }
            process.waitFor();
            return bos.toByteArray();
        } catch (Throwable t) {
            Log.w(TAG, "Shizuku 二进制执行失败: " + t.getMessage());
            return null;
        } finally {
            if (process != null) {
                try { process.destroy(); } catch (Throwable ignored) {}
            }
        }
    }

    /** SurfaceFlinger 的 display token 缓存：虚拟屏重建后 token 会变，失败时清空重解析。 */
    private static volatile String sSfToken;
    private static volatile String sSfTokenName;

    /**
     * 在 `dumpsys SurfaceFlinger --display-id` 里按 displayName 找显示 token。
     *
     * 三星 One UI（Android 16）的 screencap -d 接受的是 SurfaceFlinger token
     * （如 11529215048040110181），而不是 DisplayManager 的 logical displayId（如 55）；
     * 传 logical id 会报 "Display Id '55' is not valid"。
     */
    private static String findSurfaceFlingerToken(String displayName) {
        String out = runViaShizukuShell("dumpsys SurfaceFlinger --display-id");
        if (out == null || out.isEmpty()) out = execute("dumpsys SurfaceFlinger --display-id");
        if (out == null) return null;
        for (String line : out.split("\n")) {
            if (!line.contains("displayName=\"" + displayName + "\"")) continue;
            int i = line.indexOf("Display ");
            if (i < 0) continue;
            int start = i + "Display ".length();
            int end = line.indexOf(' ', start);
            if (end < 0) end = line.length();
            String token = line.substring(start, end).trim();
            if (token.matches("\\d+")) return token;
        }
        return null;
    }

    /**
     * 截取指定名字的虚拟屏，返回 PNG 字节；失败返回 null。
     *
     * 给 MJPEG 兼容模式用：浏览器没有 WebCodecs（或页面是 http 局域网红）时，
     * 由 server 端按帧抓屏压 JPEG 推 multipart 流，任何浏览器都能直接显示。
     */
    public static byte[] captureVirtualDisplayPng(String displayName, int maxBytes) {
        try {
            String token = sSfToken;
            if (token == null || !displayName.equals(sSfTokenName)) {
                token = findSurfaceFlingerToken(displayName);
                if (token == null) {
                    Log.w(TAG, "找不到虚拟屏 SurfaceFlinger token: " + displayName);
                    return null;
                }
                sSfToken = token;
                sSfTokenName = displayName;
            }
            byte[] png = runBinaryViaShizukuShell("screencap -d " + token + " -p", maxBytes);
            if (png != null && png.length > 1024 && (png[0] & 0xFF) == 0x89) return png;
            // token 过期（虚拟屏重建过）：清缓存，下一帧重新解析
            sSfToken = null;
            return null;
        } catch (Throwable t) {
            sSfToken = null;
            return null;
        }
    }

    public static void invalidateScreencapToken() {
        sSfToken = null;
        sSfTokenName = null;
    }

    /**
     * 核心：创建特权 Samsung DeX 虚拟显示屏
     * 关键特权标志组合：
     * - VIRTUAL_DISPLAY_FLAG_PUBLIC (1)
     * - VIRTUAL_DISPLAY_FLAG_SUPPORTS_TOUCH (64)
     * - VIRTUAL_DISPLAY_FLAG_DESTROY_CONTENT_ON_REMOVAL (256)
     * - VIRTUAL_DISPLAY_FLAG_SHOULD_SHOW_SYSTEM_DECORATIONS (512, 唤醒三星官方 Taskbar 与 SystemUI 装饰)
     * - VIRTUAL_DISPLAY_FLAG_TRUSTED (1024, 授信屏幕)
     * - VIRTUAL_DISPLAY_FLAG_OWN_DISPLAY_GROUP (2048, 独立显示组，彻底杜绝任务栈逃逸)
     * - VIRTUAL_DISPLAY_FLAG_ALWAYS_UNLOCKED (4096, 永不锁屏)
     */
    /**
     * 系统装饰开关（DeX 任务栏/状态栏由 SHOULD_SHOW_SYSTEM_DECORATIONS 标志 512 控制）。
     * 真全屏模式 = 全屏窗口模式(1) + 关闭装饰（标志在虚拟屏创建时固定，切换后需重建虚拟屏）。
     */
    private static volatile boolean sSystemDecorationsEnabled = true;

    public static void setSystemDecorationsEnabled(boolean enabled) {
        sSystemDecorationsEnabled = enabled;
    }

    public static boolean isSystemDecorationsEnabled() {
        return sSystemDecorationsEnabled;
    }

    public static int createVirtualDisplay(Context context, String name, int width, int height, int dpi, Surface surface) {
        int finalFlags = DisplayManager.VIRTUAL_DISPLAY_FLAG_PUBLIC // 1
                | 64   // VIRTUAL_DISPLAY_FLAG_SUPPORTS_TOUCH
                | 256  // VIRTUAL_DISPLAY_FLAG_DESTROY_CONTENT_ON_REMOVAL
                | 1024 // VIRTUAL_DISPLAY_FLAG_TRUSTED
                | 2048 // VIRTUAL_DISPLAY_FLAG_OWN_DISPLAY_GROUP
                | 4096; // VIRTUAL_DISPLAY_FLAG_ALWAYS_UNLOCKED
        if (sSystemDecorationsEnabled) {
            finalFlags |= 512; // VIRTUAL_DISPLAY_FLAG_SHOULD_SHOW_SYSTEM_DECORATIONS (DeX 任务栏与状态栏)
        }

        // 1. 首选：通过 ShizukuBinderWrapper 原生 Binder 直连创建 (运行在 Shell UID 2000)
        try {
            if (isShizukuReady()) {
                IBinder displayBinder = new ShizukuBinderWrapper(SystemServiceHelper.getSystemService(Context.DISPLAY_SERVICE));
                IDisplayManager idm = IDisplayManager.Stub.asInterface(displayBinder);
                IVirtualDisplayCallback callback = new IVirtualDisplayCallback.Stub() {
                    @Override
                    public void onPaused() {}

                    @Override
                    public void onResumed() {}

                    @Override
                    public void onStopped() {}
                };

                // Android 14+ VirtualDisplayConfig 接口
                try {
                    VirtualDisplayConfig config = new VirtualDisplayConfig.Builder(name, width, height, dpi)
                            .setSurface(surface)
                            .setFlags(finalFlags)
                            .build();
                    int dId = idm.createVirtualDisplay(config, callback, null, "com.android.shell");
                    if (dId > 0) {
                        displayCallbacks.put(dId, callback);
                        Log.i(TAG, "通过 ShizukuBinderWrapper(Config) 成功创建 DeX 虚拟屏 ID: " + dId);
                        return dId;
                    }
                } catch (Throwable t1) {
                    Log.w(TAG, "ShizukuBinderWrapper(Config) 提示: " + t1.getMessage());
                }

                // Android 13 及以下 10-args 接口
                try {
                    int dId = idm.createVirtualDisplay(callback, null, "com.android.shell", name, width, height, dpi, surface, finalFlags, null);
                    if (dId > 0) {
                        displayCallbacks.put(dId, callback);
                        Log.i(TAG, "通过 ShizukuBinderWrapper(10-args) 成功创建 DeX 虚拟屏 ID: " + dId);
                        return dId;
                    }
                } catch (Throwable t2) {
                    Log.w(TAG, "ShizukuBinderWrapper(10-args) 提示: " + t2.getMessage());
                }
            }
        } catch (Throwable t) {
            Log.w(TAG, "ShizukuBinderWrapper 直连创建特权虚拟屏异常: " + t.getMessage(), t);
        }

        // 2. 纯 Root 通道：交给 root UID 常驻桥创建。
        //    DisplayManagerService 会静默剥掉「非 TRUSTED 虚拟屏」的 512 系统装饰标志，
        //    这正是「官方桌面没壁纸 / 应用全屏无标题栏 / 没有底部 dock」的根因。
        //    root UID(0) 可以同时保住 TRUSTED(1024) 与 SHOULD_SHOW_SYSTEM_DECORATIONS(512)。
        //    只是一次性 Binder 事务，不进高频输入通道。
        if (PrivilegeRoute.mode() == PrivilegeRoute.Mode.ROOT) {
            int rootId = RootInputBridge.createVirtualDisplay(
                    context, name, width, height, dpi, surface, sSystemDecorationsEnabled);
            if (rootId > 0) return rootId;
        }

        // 3. 本地 DisplayManager 创建 (兜底路径)
        // 这里必须带 512 (VIRTUAL_DISPLAY_FLAG_SHOULD_SHOW_SYSTEM_DECORATIONS)，与
        // Shizuku 通道、以及 Moonlight 侧 VirtualDisplayUserService 已验证的标志对齐。
        // 缺了它的表现就是用户反馈的：官方桌面没有壁纸、应用全屏无标题栏、底部没有
        // DeX 任务栏/dock —— SystemUI 根本不会往这块屏摆装饰。
        // 仍保持 FLAG_PRIVATE（不加 PUBLIC）：真机验证过私有屏也能由 root/shell 发起
        // `am start --display <id>` 把三星 SecondaryLauncher 放上去，且不会让通知跑到外屏。
        // 若固件拒绝该标志（SecurityException），自动回退到基础 flags，保证不黑屏。
        if (context != null && surface != null && surface.isValid()) {
            try {
                DisplayManager dm = (DisplayManager) context.getSystemService(Context.DISPLAY_SERVICE);
                if (dm != null) {
                    // 必须 PUBLIC（Display.type=TYPE_EXTERNAL）而不是 PRESENTATION：
                    // TYPE_PRESENTATION 被 WMS 的 canHostIme() 排除，输入法永远不会在这块屏
                    // 画软键盘和候选栏（表现为 mImeWindowVis 恒为 0）。
                    //
                    // 还必须 OWN_CONTENT_ONLY：PUBLIC 屏若不带它，DisplayManagerService 会认为
                    // 这是「投屏/mirror」屏，要求 CAPTURE_VIDEO_OUTPUT / ADD_MIRROR_DISPLAY，
                    // 普通应用 uid 直接 SecurityException（真机日志：
                    // Permission Denial: createVirtualDisplayInternal from uid=10380）。
                    int baseFlags = DisplayManager.VIRTUAL_DISPLAY_FLAG_PUBLIC
                            | DisplayManager.VIRTUAL_DISPLAY_FLAG_OWN_CONTENT_ONLY;
                    int flags = sSystemDecorationsEnabled ? (baseFlags | 512) : baseFlags;
                    VirtualDisplay vd = null;
                    try {
                        vd = dm.createVirtualDisplay(name, width, height, dpi, surface, flags);
                    } catch (Throwable decorated) {
                        Log.w(TAG, "带系统装饰创建虚拟屏失败，回退基础 flags: " + decorated.getMessage());
                    }
                    if (vd == null) {
                        vd = dm.createVirtualDisplay(name, width, height, dpi, surface, baseFlags);
                    }
                    if (vd != null && vd.getDisplay() != null) {
                        int displayId = vd.getDisplay().getDisplayId();
                        localDisplays.put(displayId, vd);
                        Log.i(TAG, "通过本地 DisplayManager 创建虚拟屏 ID: " + displayId
                                + " flags=" + flags + " systemDecorations=" + sSystemDecorationsEnabled);
                        return displayId;
                    }
                }
            } catch (Throwable t) {
                Log.e(TAG, "本地 DisplayManager.createVirtualDisplay 异常: " + t.getMessage(), t);
            }
        }

        return -1;
    }

    /**
     * 【已废弃 · 历史实现】应用专属虚拟屏。
     *
     * 已被 AppShellManager 的「自由窗口同屏」模型取代：系统应用直接以 freeform
     * 窗口跑在桌面那块屏上，不再需要一窗口一块虚拟屏（多屏 = 输入绑定互相争抢，
     * 手柄/鼠标会在壳屏与桌面屏之间来回被抢）。
     *
     * 历史要点：不带 512 SHOULD_SHOW_SYSTEM_DECORATIONS，配合 configureAppDisplay()
     * 把该屏设成全屏；OWN_DISPLAY_GROUP 让每个应用自成显示组；
     * DESTROY_CONTENT_ON_REMOVAL 保证释放虚拟屏时其上任务一并销毁。
     *
     * 保留代码仅为必要时回退旧模型，正常运行路径不再调用。
     *
     * @deprecated 用 AppShellManager.open() 启动系统应用
     */
    @Deprecated
    public static int createAppVirtualDisplay(Context context, String name, int width, int height, int dpi, Surface surface) {
        final int flags = DisplayManager.VIRTUAL_DISPLAY_FLAG_PUBLIC // 1
                | 64    // SUPPORTS_TOUCH
                | 256   // DESTROY_CONTENT_ON_REMOVAL
                | 1024  // TRUSTED
                | 2048  // OWN_DISPLAY_GROUP
                | 4096; // ALWAYS_UNLOCKED
        try {
            if (isShizukuReady()) {
                IBinder displayBinder = new ShizukuBinderWrapper(
                        SystemServiceHelper.getSystemService(Context.DISPLAY_SERVICE));
                IDisplayManager idm = IDisplayManager.Stub.asInterface(displayBinder);
                IVirtualDisplayCallback callback = new IVirtualDisplayCallback.Stub() {
                    @Override public void onPaused() {}
                    @Override public void onResumed() {}
                    @Override public void onStopped() {}
                };
                int dId = -1;
                try {
                    VirtualDisplayConfig config = new VirtualDisplayConfig.Builder(name, width, height, dpi)
                            .setSurface(surface)
                            .setFlags(flags)
                            .build();
                    dId = idm.createVirtualDisplay(config, callback, null, "com.android.shell");
                } catch (Throwable t1) {
                    Log.w(TAG, "应用屏 Config 接口不可用，回退 10-args: " + t1.getMessage());
                }
                if (dId <= 0) {
                    dId = idm.createVirtualDisplay(callback, null, "com.android.shell",
                            name, width, height, dpi, surface, flags, null);
                }
                if (dId > 0) {
                    displayCallbacks.put(dId, callback);
                    Log.i(TAG, "应用壳虚拟屏创建成功 ID=" + dId
                            + " " + width + "x" + height + " @" + dpi + "dpi (无系统装饰)");
                    return dId;
                }
            }
        } catch (Throwable t) {
            Log.w(TAG, "创建应用壳虚拟屏失败: " + t.getMessage(), t);
        }

        // 2. 纯 Root 通道：root UID 建屏（不带 512，应用壳不要系统装饰）。
        //    旧版这里只有 Shizuku 一条路，纯 Root 下直接返回 -1 —— 这就是
        //    「沧海系统 里手机软件都打不开、一直显示正在打开」的根因。
        if (PrivilegeRoute.mode() == PrivilegeRoute.Mode.ROOT) {
            int rootId = RootInputBridge.createVirtualDisplay(
                    context, name, width, height, dpi, surface, false);
            if (rootId > 0) {
                Log.i(TAG, "root UID 创建应用壳虚拟屏成功 ID=" + rootId
                        + " " + width + "x" + height + " @" + dpi + "dpi (无系统装饰)");
                return rootId;
            }
        }

        // 3. 本地 DisplayManager 兜底：应用 UID 不能带 TRUSTED / OWN_DISPLAY_GROUP，
        //    退回基础标志也要保证窗口有画面，绝不返回 -1 把用户卡在「正在打开」。
        if (context != null && surface != null && surface.isValid()) {
            try {
                DisplayManager dm = (DisplayManager) context.getSystemService(Context.DISPLAY_SERVICE);
                if (dm != null) {
                    int[] attempts = {
                            DisplayManager.VIRTUAL_DISPLAY_FLAG_PUBLIC | 64 | 256,
                            DisplayManager.VIRTUAL_DISPLAY_FLAG_PUBLIC
                    };
                    for (int f : attempts) {
                        try {
                            VirtualDisplay vd = dm.createVirtualDisplay(
                                    name, width, height, dpi, surface, f);
                            if (vd != null && vd.getDisplay() != null) {
                                int id = vd.getDisplay().getDisplayId();
                                localDisplays.put(id, vd);
                                Log.i(TAG, "本地创建应用壳虚拟屏 ID=" + id + " flags=" + f);
                                return id;
                            }
                        } catch (Throwable t3) {
                            Log.w(TAG, "本地创建应用壳虚拟屏失败 flags=" + f + ": " + t3.getMessage());
                        }
                    }
                }
            } catch (Throwable t4) {
                Log.e(TAG, "本地创建应用壳虚拟屏异常: " + t4.getMessage(), t4);
            }
        }
        return -1;
    }

    /**
     * 把目标屏的 IME 策略改成 ON_SCREEN(0)。
     *
     * AOSP 给非主屏的默认策略是 DISPLAY_IME_POLICY_FALLBACK_DISPLAY(1)：输入法窗口被送回
     * 手机主屏，于是候选框显示在手机上；主屏此时没有焦点输入框时又会被立即隐藏，
     * 表现为「有时候两边都不显示」。旧代码只在 Shizuku.pingBinder() 为真时才设置，
     * 纯 Root 下这一步从未执行。
     *
     * 两条通道都覆盖：Shizuku binder 优先，其次常驻 root 桥（root UID 调 IWindowManager）。
     */
    public static boolean applyOnScreenImePolicy(int displayId) {
        if (displayId <= 0) return false;
        // Moonlight 把按键以「硬件键盘」形式注入，系统默认会因此压制软键盘；
        // 固定为可弹出，中文输入法才能附着到该屏的输入框。
        try {
            execute("settings put secure show_ime_with_hard_keyboard 1");
        } catch (Throwable ignored) {}
        try {
            if (isShizukuReady()) {
                IBinder wmBinder = new ShizukuBinderWrapper(
                        SystemServiceHelper.getSystemService(Context.WINDOW_SERVICE));
                // 必须反射：编译期 IWindowManager 的声明与 One UI 8.5 (Android 16) 运行时
                // 的 framework.jar 不是同一份，直接 invokeinterface 会抛
                // NoSuchMethodError: setDisplayImePolicy(II)V（真机实测）。
                Object iwm = Class.forName("android.view.IWindowManager$Stub")
                        .getMethod("asInterface", IBinder.class)
                        .invoke(null, wmBinder);
                if (iwm != null) {
                    java.lang.reflect.Method setPolicy = null;
                    for (java.lang.reflect.Method cand : iwm.getClass().getMethods()) {
                        if ("setDisplayImePolicy".equals(cand.getName())
                                && cand.getParameterTypes().length == 2) {
                            setPolicy = cand;
                            break;
                        }
                    }
                    if (setPolicy != null) {
                        setPolicy.invoke(iwm, displayId, 0); // ON_SCREEN
                        Log.i(TAG, "Shizuku 设置虚拟屏 IME 策略 ON_SCREEN: ID " + displayId);
                        return true;
                    }
                }
            }
        } catch (Throwable t) {
            Log.w(TAG, "Shizuku 设置 IME 策略失败: " + t.getMessage());
        }
        if (RootInputBridge.setDisplayImePolicy(displayId, 0)) {
            Log.i(TAG, "root UID 设置虚拟屏 IME 策略 ON_SCREEN: ID " + displayId);
            return true;
        }
        // 建屏可能抢在 root 桥句柄送达之前；不在这里 sleep 重试，避免串流建屏被拖慢。
        Log.w(TAG, "IME 策略设置失败（Root 桥不可用）: ID " + displayId);
        return false;
    }

    /**
     * 【已废弃 · 历史实现】应用屏环境：全屏窗口模式 + 输入法可弹出。
     * 新模型下系统应用跑在桌面屏，窗口模式由自由窗口机制决定，无需再配置。
     *
     * @deprecated 仅与 {@link #createAppVirtualDisplay} 配套使用
     */
    @Deprecated
    public static void configureAppDisplay(int displayId) {
        if (displayId <= 0) return;
        /* 必须同步执行：调用方在「配置完成」后才 am start。
         * 若这里另起线程，wm / IME 配置可能落在应用已起之后；
         * 在线切换已有任务的显示窗口模式会触发 SystemUI 崩溃/重建。 */
        try {
            execute("wm set-display-windowing-mode -d " + displayId + " 1");
            applyOnScreenImePolicy(displayId);
            Log.i(TAG, "应用壳虚拟屏环境已配置 ID=" + displayId + " (全屏 / IME 可用)");
        } catch (Throwable t) {
            Log.w(TAG, "配置应用屏环境异常: " + t.getMessage());
        }
    }

    public static void resizeVirtualDisplay(int displayId, int width, int height, int dpi) {
        if (displayId < 0) return;

        if (RootInputBridge.ownsDisplay(displayId)) {
            RootInputBridge.resizeVirtualDisplay(displayId, width, height, dpi);
            return;
        }

        IVirtualDisplayCallback cb = displayCallbacks.get(displayId);
        if (cb != null) {
            try {
                if (isShizukuReady()) {
                    IBinder displayBinder = new ShizukuBinderWrapper(SystemServiceHelper.getSystemService(Context.DISPLAY_SERVICE));
                    IDisplayManager idm = IDisplayManager.Stub.asInterface(displayBinder);
                    idm.resizeVirtualDisplay(cb, width, height, dpi);
                    Log.i(TAG, "通过 ShizukuBinderWrapper 原生调整虚拟屏物理尺寸: ID " + displayId + " (" + width + "x" + height + " @" + dpi + "dpi)");
                    return;
                }
            } catch (Throwable t) {
                Log.w(TAG, "ShizukuBinderWrapper resizeVirtualDisplay 提示: " + t.getMessage());
            }
        }

        VirtualDisplay localVd = localDisplays.get(displayId);
        if (localVd != null) {
            try {
                localVd.resize(width, height, dpi);
            } catch (Throwable ignored) {}
        }
    }

    public static void releaseVirtualDisplay(int displayId) {
        if (displayId < 0) return;

        // root UID 创建的虚拟屏，callback binder 在 root 常驻进程里，必须让它自己释放；
        // 否则应用侧拿不到 VirtualDisplay 对象，会留下幽灵屏。
        if (RootInputBridge.ownsDisplay(displayId)) {
            RootInputBridge.releaseVirtualDisplay(displayId);
            if (displayCallbacks.isEmpty() && localDisplays.isEmpty()
                    && !RootInputBridge.hasTrackedDisplays()) {
                restoreForceResizable();
            }
            return;
        }

        IVirtualDisplayCallback cb = displayCallbacks.remove(displayId);
        if (cb != null) {
            try {
                if (isShizukuReady()) {
                    IBinder displayBinder = new ShizukuBinderWrapper(SystemServiceHelper.getSystemService(Context.DISPLAY_SERVICE));
                    IDisplayManager idm = IDisplayManager.Stub.asInterface(displayBinder);
                    idm.releaseVirtualDisplay(cb);
                    Log.i(TAG, "通过 ShizukuBinderWrapper 释放特权虚拟屏: ID " + displayId);
                }
            } catch (Throwable t) {
                Log.w(TAG, "ShizukuBinderWrapper releaseVirtualDisplay 提示: " + t.getMessage());
            }
        }

        VirtualDisplay localVd = localDisplays.remove(displayId);
        if (localVd != null) {
            try {
                localVd.release();
            } catch (Throwable ignored) {}
        }

        // 所有 DeX 虚拟屏都已释放 → 恢复用户原来的 force_resizable_activities 设置
        if (displayCallbacks.isEmpty() && localDisplays.isEmpty()) {
            restoreForceResizable();
        }
    }

    /** 释放当前进程跟踪的所有虚拟屏（划掉任务卡时由 Service.onTaskRemoved 调用）。
     *  虚拟屏以 shell 身份创建，进程活着屏就活着——不清掉会把鼠标指针"挟持"在幽灵屏上。 */
    public static void releaseAllTrackedDisplays() {
        for (Integer id : RootInputBridge.trackedDisplayIds()) {
            try {
                releaseVirtualDisplay(id);
            } catch (Throwable t) {
                Log.w(TAG, "releaseAllTrackedDisplays 释放 root 屏 " + id + " 失败: " + t.getMessage());
            }
        }
        for (Integer id : new java.util.ArrayList<>(displayCallbacks.keySet())) {
            try {
                releaseVirtualDisplay(id);
            } catch (Throwable t) {
                Log.w(TAG, "releaseAllTrackedDisplays 释放 " + id + " 失败: " + t.getMessage());
            }
        }
        for (Integer id : new java.util.ArrayList<>(localDisplays.keySet())) {
            try {
                releaseVirtualDisplay(id);
            } catch (Throwable ignored) {}
        }
        Log.i(TAG, "已释放全部虚拟屏（任务被移除）");
    }

    // ===== force_resizable_activities 全局开关管理（DeX 期间开启，退出时恢复） =====
    private static final Object sForceResizableLock = new Object();
    private static String sPrevForceResizable = null;
    private static boolean sForceResizableChanged = false;

    /** 开启强制可调整大小（只在首次调用时保存原值，幂等） */
    public static void enableForceResizableForDeX() {
        synchronized (sForceResizableLock) {
            if (sForceResizableChanged) return;
            String cur = execute("settings get global force_resizable_activities");
            sPrevForceResizable = (cur == null || cur.isEmpty()) ? "0" : cur.trim();
            if (!"1".equals(sPrevForceResizable)) {
                execute("settings put global force_resizable_activities 1");
                sForceResizableChanged = true;
                Log.i(TAG, "已开启 force_resizable_activities（DeX 期间生效），原值=" + sPrevForceResizable);
            }
        }
    }

    /** 恢复用户原来的 force_resizable_activities 值 */
    public static void restoreForceResizable() {
        synchronized (sForceResizableLock) {
            if (!sForceResizableChanged) return;
            execute("settings put global force_resizable_activities " + sPrevForceResizable);
            Log.i(TAG, "已恢复 force_resizable_activities=" + sPrevForceResizable);
            sForceResizableChanged = false;
        }
    }

    public static void setVirtualDisplaySurface(int displayId, Surface surface) {
        if (displayId < 0) return;
        if (RootInputBridge.ownsDisplay(displayId)) {
            RootInputBridge.setVirtualDisplaySurface(displayId, surface);
            return;
        }
        IVirtualDisplayCallback cb = displayCallbacks.get(displayId);
        if (cb != null) {
            try {
                if (isShizukuReady()) {
                    IBinder displayBinder = new ShizukuBinderWrapper(SystemServiceHelper.getSystemService(Context.DISPLAY_SERVICE));
                    IDisplayManager idm = IDisplayManager.Stub.asInterface(displayBinder);
                    idm.setVirtualDisplaySurface(cb, surface);
                    Log.i(TAG, "通过 ShizukuBinderWrapper 切换虚拟屏 Surface: ID " + displayId + " -> " + surface);
                    return;
                }
            } catch (Throwable t) {
                Log.w(TAG, "ShizukuBinderWrapper setVirtualDisplaySurface 提示: " + t.getMessage());
            }
        }

        VirtualDisplay localVd = localDisplays.get(displayId);
        if (localVd != null) {
            try {
                localVd.setSurface(surface);
            } catch (Throwable ignored) {}
        }
    }

    private static volatile IInputManager iInputManager = null;

    public static IInputManager getInputManager() {
        if (iInputManager == null) {
            try {
                if (isShizukuReady()) {
                    IBinder inputBinder = new ShizukuBinderWrapper(SystemServiceHelper.getSystemService(Context.INPUT_SERVICE));
                    iInputManager = IInputManager.Stub.asInterface(inputBinder);
                    Log.i(TAG, "已成功初始化 IInputManager Binder 通道");
                }
            } catch (Throwable t) {
                Log.w(TAG, "获取 IInputManager 失败: " + t.getMessage());
            }
        }
        return iInputManager;
    }

    private static volatile boolean sPointerSpriteHideAttempted = false;

    /** 本次会话需要隐藏系统指针精灵的屏幕（绝不登记 0 = 手机主屏）。 */
    private static final java.util.Set<Integer> sHiddenPointerDisplays =
            java.util.Collections.synchronizedSet(new java.util.HashSet<>());
    private static final Map<Integer, Long> sLastSpriteHideTime = new ConcurrentHashMap<>();
    private static final long SPRITE_HIDE_THROTTLE_MS = 1500L;

    /** 登记一块目标屏并立即隐藏其系统精灵：虚拟屏（外设绑定目标）+ 物理外接屏都要登记。 */
    public static void registerPointerSpriteTarget(int displayId) {
        if (true) return; // 统一由外设大脑接管调度，屏蔽特权操作
        if (displayId <= 0) return;
        sHiddenPointerDisplays.add(displayId);
        final int target = displayId;
        new Thread(() -> forceHideSystemPointerSprite(target), "pointer-sprite").start();
    }

    /** USB 鼠标移动时节流重喷：焦点窗口切换会把全局精灵重置回默认黑箭头，必须反复压住。 */
    public static void keepSystemPointerHidden() {
        if (true) return; // 统一由外设大脑接管调度，屏蔽特权操作
        long now = android.os.SystemClock.elapsedRealtime();
        java.util.List<Integer> targets;
        synchronized (sHiddenPointerDisplays) { targets = new java.util.ArrayList<>(sHiddenPointerDisplays); }
        for (int id : targets) {
            Long last = sLastSpriteHideTime.get(id);
            if (last != null && now - last < SPRITE_HIDE_THROTTLE_MS) continue;
            final int target = id;
            new Thread(() -> forceHideSystemPointerSprite(target), "pointer-sprite").start();
        }
    }

    /** 无节流重喷：切换黑屏控制 / 窗口焦点变化后调用。
     *  旧实现内部调 hideSystemPointerSprite(0)，而该方法首行对 <=0 直接 return，等于恒为空操作。 */
    public static void rehideSystemPointerSprite() {
        if (true) return; // 统一由外设大脑接管调度，屏蔽特权操作
        sPointerSpriteHideAttempted = false;
        java.util.List<Integer> targets;
        synchronized (sHiddenPointerDisplays) { targets = new java.util.ArrayList<>(sHiddenPointerDisplays); }
        for (int id : targets) {
            final int target = id;
            new Thread(() -> forceHideSystemPointerSprite(target), "pointer-sprite").start();
        }
    }

    /** 兼容旧调用：对已登记的全部目标屏重喷。 */
    public static void hideSystemPointerSprite() { rehideSystemPointerSprite(); }

    private static void forceHideSystemPointerSprite(int targetDisplayId) {
        if (targetDisplayId <= 0) return;
        sLastSpriteHideTime.put(targetDisplayId, android.os.SystemClock.elapsedRealtime());
        hideSystemPointerSprite(targetDisplayId);
    }

    /** 仅反映"本次会话是否登记过该屏"，不再作为是否绘制软件指针的开关。 */
    public static boolean isSystemPointerHidden(int displayId) {
        return displayId > 0 && sHiddenPointerDisplays.contains(displayId);
    }

    /** 外屏真正断开时清理：虚拟屏 displayId 每次重建都会递增，避免残留旧 ID 越积越多。 */
    public static void clearPointerSpriteTargets() {
        if (true) return; // 统一由外设大脑接管调度，屏蔽特权操作
        sHiddenPointerDisplays.clear();
        sLastSpriteHideTime.clear();
    }

    /**
     * 隐藏特定外接屏幕的系统指针精灵。
     * 注意：绝不向 displayId = 0 (手机主屏) 注入透明空指针，确保手机自身鼠标永远正常可见。
     */
    public static boolean hideSystemPointerSprite(int targetDisplayId) {
        if (true) return true; // 统一由外设大脑接管调度，屏蔽特权操作
        if (targetDisplayId <= 0) return false;
        try {
            IInputManager im = getInputManager();
            if (im == null) return false;
            android.view.PointerIcon icon = null;
            if (appContext != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                icon = EmptyPointerFrameLayout.getEmptyPointerIcon(appContext);
            }
            if (icon == null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                android.graphics.Bitmap empty =
                        android.graphics.Bitmap.createBitmap(1, 1, android.graphics.Bitmap.Config.ARGB_8888);
                icon = android.view.PointerIcon.create(empty, 0f, 0f);
            }
            Class<?> cls = im.getClass();
            for (java.lang.reflect.Method m : cls.getMethods()) {
                String name = m.getName().toLowerCase(java.util.Locale.ROOT);
                if (!name.contains("pointericon")) continue;
                Class<?>[] params = m.getParameterTypes();
                try {
                    if (params.length == 2 && params[0] == android.view.PointerIcon.class && (params[1] == int.class || params[1] == Integer.class)) {
                        m.invoke(im, icon, targetDisplayId);
                        sHiddenPointerDisplays.add(targetDisplayId);
                        Log.i(TAG, "外接屏(" + targetDisplayId + ")系统指针已设置为自绘主题图标 (2 params)");
                        return true;
                    } else if (params.length == 5 && params[0] == android.view.PointerIcon.class) {
                        m.invoke(im, icon, targetDisplayId, 0, 0, null);
                        sHiddenPointerDisplays.add(targetDisplayId);
                        Log.i(TAG, "外接屏(" + targetDisplayId + ")系统指针精灵已设置为自绘主题图标 (5 params)");
                        return true;
                    }
                } catch (Throwable ignored) {}
            }
        } catch (Throwable t) {
            Log.w(TAG, "hideSystemPointerSprite 异常: " + t.getMessage());
        }
        return false;
    }



    /**
     * 恢复系统默认鼠标指针（还原为原生箭头）：
     * 在 App 退出、断开外屏或启动时调用，确保手机主屏幕鼠标立即恢复正常显示。
     */
    public static void restoreSystemPointerSprite(Context context) {
        if (true) return; // 统一由外设大脑接管调度，屏蔽特权操作
        // 旧实现会 clear 目标屏集合，而启动时这个方法是异步执行的，可能与虚拟屏
        // surfaceCreated 的登记竞态，把刚登记的外接屏清掉。改为只清节流时间戳：
        // 保留目标屏，USB 鼠标一动就会立刻重喷隐藏（隐藏只作用于 >0 的屏，不碰手机主屏）。
        sLastSpriteHideTime.clear();
        if (!isShizukuReady()) return;
        try {
            IInputManager im = getInputManager();
            if (im == null) return;
            android.view.PointerIcon arrowIcon = null;
            if (context != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                try {
                    arrowIcon = android.view.PointerIcon.getSystemIcon(context, android.view.PointerIcon.TYPE_ARROW);
                } catch (Throwable ignored) {}
            }
            Class<?> cls = im.getClass();
            for (java.lang.reflect.Method m : cls.getMethods()) {
                String name = m.getName().toLowerCase(java.util.Locale.ROOT);
                if (!name.contains("pointericon")) continue;
                Class<?>[] params = m.getParameterTypes();
                try {
                    if (params.length == 2 && params[0] == android.view.PointerIcon.class && arrowIcon != null && (params[1] == int.class || params[1] == Integer.class)) {
                        m.invoke(im, arrowIcon, 0);
                    } else if (params.length == 2 && (params[0] == int.class || params[0] == Integer.class) && (params[1] == int.class || params[1] == Integer.class)) {
                        m.invoke(im, 1000, 0); // 1000 = TYPE_ARROW
                    } else if (params.length == 1 && params[0] == android.view.PointerIcon.class && arrowIcon != null) {
                        m.invoke(im, arrowIcon);
                    } else if (params.length == 1 && (params[0] == int.class || params[0] == Integer.class)) {
                        m.invoke(im, 1000); // 1000 = TYPE_ARROW
                    } else if (params.length == 5 && params[0] == android.view.PointerIcon.class && arrowIcon != null) {
                        m.invoke(im, arrowIcon, 0, 0, 0, null);
                    }
                } catch (Throwable ignored) {}
            }
            Log.i(TAG, "已恢复系统默认鼠标指针 (TYPE_ARROW)");
        } catch (Throwable t) {
            Log.w(TAG, "restoreSystemPointerSprite 异常: " + t.getMessage());
        }
    }

    /**
     * 把输入事件注入到「指定虚拟屏」。
     *
     * 关键坑：IInputManager.injectInputEvent 读的是 InputEvent 内部的 displayId，
     * motion event 未指定时系统按 display 0（手机主屏）派发 —— 这正是 WiFi 投屏
     * 「点了没反应，反而误点手机界面」的根因。所以这里必须确认 displayId 真的
     * 写进去了；写不进去宁可返回 false 交给上层走 `input -d` 兜底，绝不注入默认屏。
     */
    public static boolean injectMotionEvent(MotionEvent event, int targetDisplayId) {
        if (event == null || targetDisplayId <= 0) return false;
        MotionEvent copy = MotionEvent.obtain(event);
        try {
            int originalSource = event.getSource();
            if (originalSource != 0 && originalSource != InputDevice.SOURCE_UNKNOWN) {
                copy.setSource(originalSource);
            } else {
                copy.setSource(InputDevice.SOURCE_TOUCHSCREEN);
            }

            if (!setInputEventDisplayId(copy, targetDisplayId)) {
                Log.w(TAG, "无法写入 displayId=" + targetDisplayId + "，跳过 binder 注入（避免误点主屏）");
                return false;
            }

            IInputManager im = getInputManager();
            if (im != null) {
                boolean ok = im.injectInputEvent(copy, 0);
                if (!ok) Log.w(TAG, "injectInputEvent 返回 false, display=" + targetDisplayId);
                return ok;
            }
            Log.w(TAG, "IInputManager 不可用");
        } catch (Throwable t) {
            Log.w(TAG, "injectMotionEvent 异常: " + t.getMessage());
        } finally {
            try {
                copy.recycle();
            } catch (Throwable ignored) {}
        }
        return false;
    }

    /** 多路径写 displayId：public @hide 方法优先，其次父类字段直写。 */
    private static boolean setInputEventDisplayId(InputEvent ev, int displayId) {
        try {
            java.lang.reflect.Method m = InputEvent.class.getMethod("setDisplayId", int.class);
            m.setAccessible(true);
            m.invoke(ev, displayId);
            int actual = readInputEventDisplayId(ev);
            // 读不到（getDisplayId 也是 hidden）时，invoke 未抛异常就认为写入成功
            if (actual < 0 || actual == displayId) return true;
            Log.w(TAG, "setDisplayId 后读回 " + actual + " != " + displayId);
        } catch (Throwable t) {
            Log.w(TAG, "setDisplayId 方法反射失败: " + t);
        }
        try {
            Class<?> c = ev.getClass();
            java.lang.reflect.Field f = null;
            while (c != null && f == null) {
                try { f = c.getDeclaredField("mDisplayId"); }
                catch (NoSuchFieldException ignored) { c = c.getSuperclass(); }
            }
            if (f != null) {
                f.setAccessible(true);
                f.setInt(ev, displayId);
                int actual = readInputEventDisplayId(ev);
                if (actual < 0 || actual == displayId) return true;
                Log.w(TAG, "mDisplayId 写入后读回 " + actual + " != " + displayId);
            } else {
                Log.w(TAG, "InputEvent 及其父类没有 mDisplayId 字段");
            }
        } catch (Throwable t) {
            Log.w(TAG, "mDisplayId 字段反射失败: " + t);
        }
        return false;
    }

    private static int readInputEventDisplayId(InputEvent ev) {
        try {
            java.lang.reflect.Method g = InputEvent.class.getMethod("getDisplayId");
            g.setAccessible(true);
            Object v = g.invoke(ev);
            return v instanceof Integer ? (Integer) v : -1;
        } catch (Throwable t) {
            return -1;
        }
    }

    /**
     * 注入虚拟左键点击（ACTION_DOWN + ACTION_UP）到指定显示屏，秒级生效且不占用命令行子进程
     */
    public static boolean injectVirtualLeftClick(int targetDisplayId, float x, float y) {
        if (targetDisplayId <= 0) return false;
        long now = android.os.SystemClock.uptimeMillis();
        MotionEvent.PointerProperties[] pp = new MotionEvent.PointerProperties[1];
        pp[0] = new MotionEvent.PointerProperties();
        pp[0].id = 0;
        pp[0].toolType = MotionEvent.TOOL_TYPE_FINGER;

        MotionEvent.PointerCoords[] pc = new MotionEvent.PointerCoords[1];
        pc[0] = new MotionEvent.PointerCoords();
        pc[0].x = x;
        pc[0].y = y;
        pc[0].pressure = 1.0f;
        pc[0].size = 1.0f;

        try {
            MotionEvent down = MotionEvent.obtain(
                    now, now, MotionEvent.ACTION_DOWN, 1, pp, pc,
                    0, 0, 1.0f, 1.0f,
                    0, 0, InputDevice.SOURCE_TOUCHSCREEN, 0
            );
            boolean r1 = injectMotionEvent(down, targetDisplayId);

            MotionEvent up = MotionEvent.obtain(
                    now, now + 35, MotionEvent.ACTION_UP, 1, pp, pc,
                    0, 0, 1.0f, 1.0f,
                    0, 0, InputDevice.SOURCE_TOUCHSCREEN, 0
            );
            boolean r2 = injectMotionEvent(up, targetDisplayId);
            return r1 && r2;
        } catch (Throwable t) {
            Log.w(TAG, "injectVirtualLeftClick 异常: " + t.getMessage());
            return false;
        }
    }

    /**
     * 注入鼠标右键 (BUTTON_SECONDARY) 点击事件到指定虚拟屏幕
     */
    public static boolean injectVirtualRightClick(int targetDisplayId, float x, float y) {
        if (targetDisplayId <= 0) return false;
        long now = android.os.SystemClock.uptimeMillis();
        MotionEvent.PointerProperties[] pp = new MotionEvent.PointerProperties[1];
        pp[0] = new MotionEvent.PointerProperties();
        pp[0].id = 0;
        pp[0].toolType = MotionEvent.TOOL_TYPE_MOUSE;

        MotionEvent.PointerCoords[] pc = new MotionEvent.PointerCoords[1];
        pc[0] = new MotionEvent.PointerCoords();
        pc[0].x = x;
        pc[0].y = y;
        pc[0].pressure = 1.0f;
        pc[0].size = 1.0f;

        try {
            MotionEvent down = MotionEvent.obtain(
                    now, now, MotionEvent.ACTION_DOWN, 1, pp, pc,
                    0, MotionEvent.BUTTON_SECONDARY, 1.0f, 1.0f,
                    0, 0, InputDevice.SOURCE_MOUSE, 0
            );
            MotionEvent up = MotionEvent.obtain(
                    now, now + 50, MotionEvent.ACTION_UP, 1, pp, pc,
                    0, 0, 1.0f, 1.0f,
                    0, 0, InputDevice.SOURCE_MOUSE, 0
            );

            boolean r1 = injectMotionEvent(down, targetDisplayId);
            boolean r2 = injectMotionEvent(up, targetDisplayId);
            down.recycle();
            up.recycle();
            return r1 && r2;
        } catch (Throwable t) {
            Log.w(TAG, "injectVirtualRightClick 异常: " + t.getMessage());
            return false;
        }
    }

    /**
     * 注入鼠标中键 (BUTTON_TERTIARY) 点击事件到指定虚拟屏幕
     */
    public static boolean injectVirtualMiddleClick(int targetDisplayId, float x, float y) {
        if (targetDisplayId <= 0) return false;
        long now = android.os.SystemClock.uptimeMillis();
        MotionEvent.PointerProperties[] pp = new MotionEvent.PointerProperties[1];
        pp[0] = new MotionEvent.PointerProperties();
        pp[0].id = 0;
        pp[0].toolType = MotionEvent.TOOL_TYPE_MOUSE;

        MotionEvent.PointerCoords[] pc = new MotionEvent.PointerCoords[1];
        pc[0] = new MotionEvent.PointerCoords();
        pc[0].x = x;
        pc[0].y = y;
        pc[0].pressure = 1.0f;
        pc[0].size = 1.0f;

        try {
            MotionEvent down = MotionEvent.obtain(
                    now, now, MotionEvent.ACTION_DOWN, 1, pp, pc,
                    0, MotionEvent.BUTTON_TERTIARY, 1.0f, 1.0f,
                    0, 0, InputDevice.SOURCE_MOUSE, 0
            );
            MotionEvent up = MotionEvent.obtain(
                    now, now + 50, MotionEvent.ACTION_UP, 1, pp, pc,
                    0, 0, 1.0f, 1.0f,
                    0, 0, InputDevice.SOURCE_MOUSE, 0
            );

            boolean r1 = injectMotionEvent(down, targetDisplayId);
            boolean r2 = injectMotionEvent(up, targetDisplayId);
            down.recycle();
            up.recycle();
            return r1 && r2;
        } catch (Throwable t) {
            Log.w(TAG, "injectVirtualMiddleClick 异常: " + t.getMessage());
            return false;
        }
    }

    /**
     * 关键修复：无损注入物理键盘按键 (KeyEvent) 到指定虚拟显示屏
     * 让外接 USB 物理键盘/蓝牙键盘敲击的字符直接打入当前虚拟桌面所聚焦的应用中
     */
    public static boolean injectKeyEvent(KeyEvent event, int targetDisplayId) {
        if (event == null || targetDisplayId <= 0) return false;
        try {
            KeyEvent copy = new KeyEvent(event);
            if (!setInputEventDisplayId(copy, targetDisplayId)) {
                Log.w(TAG, "按键无法写入 displayId=" + targetDisplayId + "，跳过注入");
                return false;
            }
            IInputManager im = getInputManager();
            if (im != null) {
                return im.injectInputEvent(copy, 0);
            }
        } catch (Throwable t) {
            Log.w(TAG, "injectKeyEvent 异常: " + t.getMessage());
        }
        return false;
    }

    public static Map<String, String> getInputDeviceDescriptorToPortMap() {
        // dumpsys input 耗时 2-3 秒且经 Shizuku 转发，蓝牙设备频繁插拔事件会打满 Shizuku 服务
        // 导致后续注入调用排队 → 主线程 ANR。此处缓存 30 秒。
        long now = android.os.SystemClock.elapsedRealtime();
        Map<String, String> cached = sCachedPortMap;
        if (cached != null && now - sPortMapCacheTime < 30000L) {
            return cached;
        }
        Map<String, String> map = new HashMap<>();
        try {
            String inputDump = execute("dumpsys input");
            if (inputDump == null) return map;
            String[] lines = inputDump.split("\n");
            String lastDescriptor = "";
            for (String line : lines) {
                line = line.trim();
                if (line.startsWith("Descriptor:")) {
                    lastDescriptor = line.substring("Descriptor:".length()).trim();
                }
                if (line.startsWith("Location:")) {
                    String inputPort = line.substring("Location:".length()).trim();
                    if (!lastDescriptor.isEmpty() && !inputPort.isEmpty()) {
                        map.put(lastDescriptor, inputPort);
                    }
                }
            }
            sCachedPortMap = map;
            sPortMapCacheTime = now;
        } catch (Throwable t) {
            Log.w(TAG, "获取 dumpsys input 映射失败: " + t.getMessage());
        }
        return map;
    }

    private static volatile Map<String, String> sCachedPortMap = null;
    private static volatile long sPortMapCacheTime = 0L;

    // 绑定操作互斥与节流：蓝牙设备会频繁触发 onInputDeviceChanged，绝不并发重复执行
    private static final java.util.concurrent.atomic.AtomicBoolean sBindInProgress =
            new java.util.concurrent.atomic.AtomicBoolean(false);
    private static volatile long sLastBindTime = 0L;

    /** 最近一次成功绑定的目标屏。外设热插拔（手柄重连/新插鼠标）时按它自动补绑。 */
    private static volatile int sActiveDisplayId = -1;
    /** 当前屏之前绑过的那块屏，关屏时回退用。 */
    private static volatile int sFallbackDisplayId = -1;
    private static volatile long sLastHotplugBind = 0L;
    private static android.hardware.input.InputManager.InputDeviceListener sDeviceListener;
    private static final java.util.List<Runnable> sDeviceChangeListeners = new java.util.concurrent.CopyOnWriteArrayList<>();

    public static void addInputDeviceListener(Runnable r) {
        if (r != null && !sDeviceChangeListeners.contains(r)) {
            sDeviceChangeListeners.add(r);
        }
    }

    public static void removeInputDeviceListener(Runnable r) {
        if (r != null) {
            sDeviceChangeListeners.remove(r);
        }
    }

    private static void notifyDeviceChangeListeners() {
        for (Runnable r : sDeviceChangeListeners) {
            try { r.run(); } catch (Throwable ignored) {}
        }
    }

    /**
     * 有线外屏的「指针宿主屏」= DeXPresentation 所在的物理外接屏 id（未接有线外屏时为 -1）。
     *
     * 为什么鼠标不能跟键盘/手柄一起绑进桌面虚拟屏：
     * 鼠标一旦关联到虚拟屏，InputFlinger 会直接在虚拟屏里画系统黑箭头，
     * 事件也绕过了 DeXPresentation —— 自绘指针收不到坐标，指针大小/样式设置全部失效。
     * 以前能看到自绘指针，恰恰是因为 Root 绑定当时是失败的，鼠标留在了物理外屏上。
     *
     * 正确分工：
     *  - 纯指针设备（鼠标 / 触控板 / 轨迹球）→ 物理外屏：Presentation 收事件、画自绘指针、
     *    再把点击/滚轮注入虚拟屏；系统箭头由 EmptyPointerFrameLayout 的空 PointerIcon 隐藏（公开 API）。
     *  - 键盘 / 手柄 → 桌面虚拟屏（需要那里的焦点窗口）。
     *  - 键鼠一体设备（单个 descriptor 同时是鼠标和全键盘）→ 解除关联，走系统默认路由：
     *    指针默认落在外屏、按键跟随焦点屏，两边都不丢。
     */
    private static volatile int sPointerHostDisplayId = -1;

    /** DeXPresentation 显示/销毁时调用；会按当前目标屏立即重绑一次。 */
    public static void setPointerHostDisplay(int displayId) {
        if (true) return; // 统一由外设大脑接管调度，屏蔽特权操作
        if (sPointerHostDisplayId == displayId) return;
        sPointerHostDisplayId = displayId;
        Log.i(TAG, "指针宿主屏 -> " + displayId);
        int active = sActiveDisplayId;
        if (active > 0) bindAllExternalInputToDisplay(active, true);
    }

    private static boolean isPointerDevice(InputDevice dev) {
        return dev.supportsSource(InputDevice.SOURCE_MOUSE)
                || dev.supportsSource(InputDevice.SOURCE_TOUCHPAD)
                || dev.supportsSource(InputDevice.SOURCE_TRACKBALL);
    }

    /**
     * 计算某个外设实际该关联的屏：返回 >0 为目标 display id；返回 0 表示解除关联（走系统默认路由到手机自身）。
     * 规则：优先遵循用户在首页下方的外设配置页的手动切换；
     * 外设只有未手动配置过、或者手动配置的目标大屏断开不存在时，才自动找一个当前可用的大屏去连接。
     */
    public static int routeDisplayFor(InputDevice dev, int target) {
        if (dev == null) return target;
        String desc = dev.getDescriptor();
        if (desc == null || desc.isEmpty()) return target;

        if (appContext != null) {
            int manual = CanghaiStore.getPeripheralDisplay(appContext, desc, -999);
            if (manual != -999) {
                // 用户显式设置过：设为 0 (手机自身) 则必须严格尊重，返回 0 解除外接绑定回到手机
                if (manual <= 0) return 0;
                // 用户指定了外屏或 Moonlight，检查该屏幕是否依然有效
                if (StreamTarget.get().isUiAlias(manual) || isDisplayAlive(manual)) {
                    return manual;
                }
                // 原目标屏幕已断开失效，进入自动 fallback
            }
        }

        // 未手动配置或原配置屏已断开：自动找当前可用的大屏
        if (target > 0 && isDisplayAlive(target)) {
            return target;
        }
        return 0;
    }

    /**
     * 判断是否为外接输入设备（鼠标、键盘、游戏手柄、遥控器等）。
     * 使用 Android 原生 InputDevice.supportsSource 与 getKeyboardType，
     * 避免对 SOURCE_* 常量直接做按位与（低位 SOURCE_CLASS_POINTER=0x2 会把手机内置触摸屏误判为鼠标）。
     */
    /**
     * InputDevice#isExternal() 是 API 29 才有的方法，Android 9 及以下调用会
     * NoSuchMethodError（Error，不是 RuntimeException）→ 设备热插拔时直接闪退。
     * 低版本按 vendorId / 设备名兜底判断：内置设备 vendorId 为 0，且名字里带
     * 常见内置字样（触摸屏 / 传感器 / gpio）。
     */
    private static boolean isExternalSafe(InputDevice dev) {
        if (dev == null) return false;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            try {
                return dev.isExternal();
            } catch (Throwable ignored) {
            }
        }
        try {
            int vid = dev.getVendorId();
            String n = dev.getName() == null ? "" : dev.getName().toLowerCase(Locale.ROOT);
            if (vid > 0 && !n.contains("gpio") && !n.contains("sensor")) return true;
            return n.contains("usb") || n.contains("bluetooth") || n.contains("bt-");
        } catch (Throwable ignored) {
        }
        return false;
    }

    public static boolean isExternalInputDevice(InputDevice dev) {
        if (dev == null || dev.isVirtual()) return false;
        String name = dev.getName() != null ? dev.getName().toLowerCase(Locale.ROOT) : "";

        // 手机内置触摸屏、触控芯片、触控笔绝不视为外设绑定至外屏
        if (dev.supportsSource(InputDevice.SOURCE_TOUCHSCREEN)
                || name.contains("touchscreen") || name.contains("tsp") || name.contains("sec_e-pen")
                || name.contains("goodix") || name.contains("synaptics")) {
            return false;
        }

        // 蓝牙/USB 手柄与摇杆
        if (dev.supportsSource(InputDevice.SOURCE_GAMEPAD) || dev.supportsSource(InputDevice.SOURCE_JOYSTICK)) {
            return true;
        }

        // 外部鼠标 / 轨迹球（排除三星内置触控板）
        if (dev.supportsSource(InputDevice.SOURCE_MOUSE) || dev.supportsSource(InputDevice.SOURCE_TRACKBALL)) {
            return !name.contains("sec_touchpad");
        }

        // 外接全配列物理键盘
        if (dev.supportsSource(InputDevice.SOURCE_KEYBOARD)
                && dev.getKeyboardType() == InputDevice.KEYBOARD_TYPE_ALPHABETIC) {
            return !name.contains("sec_") && !name.contains("gpio");
        }

        return false;
    }

    public static void bindAllExternalInputToDisplay(int displayId) {
        if (true) return; // 统一由外设大脑接管调度，屏蔽特权操作
        bindAllExternalInputToDisplay(displayId, false);
    }

    /**
     * 监听外设热插拔：蓝牙手柄休眠后重连、新插鼠标、外接键盘上下线都会触发。
     *
     * <p>为什么必须监听：手柄走蓝牙，闲置几分钟会自己休眠断开，重连时是<b>新的 deviceId</b>，
     * 原来那条 uniqueId 关联已经失效。不补绑的话表现就是「刚还能用，过一会儿又不认识了」。
     * 只在设备增删时回调，静止时零开销。
     */
    public static void watchInputDevices(Context context) {
        if (sDeviceListener != null) return;
        Context ctx = context != null ? context : appContext;
        if (ctx == null) return;
        try {
            Object svc = ctx.getSystemService(Context.INPUT_SERVICE);
            if (!(svc instanceof android.hardware.input.InputManager)) return;
            android.hardware.input.InputManager im = (android.hardware.input.InputManager) svc;
            sDeviceListener = new android.hardware.input.InputManager.InputDeviceListener() {
                @Override
                public void onInputDeviceAdded(int deviceId) {
                    rebindActiveAsync();
                    notifyDeviceChangeListeners();
                }

                @Override
                public void onInputDeviceRemoved(int deviceId) {
                    notifyDeviceChangeListeners();
                }

                @Override
                public void onInputDeviceChanged(int deviceId) {
                    rebindActiveAsync();
                    notifyDeviceChangeListeners();
                }
            };
            im.registerInputDeviceListener(sDeviceListener, null);
            Log.i(TAG, "已监听外设热插拔");
        } catch (Throwable t) {
            Log.w(TAG, "注册输入设备监听失败: " + t.getMessage());
        }
    }

    /** 外设刚上线时 InputFlinger 还没准备好 descriptor，稍等一小会儿再绑。 */
    private static void rebindActiveAsync() {
        final int target = sActiveDisplayId;
        if (target <= 0) return;
        long now = android.os.SystemClock.elapsedRealtime();
        if (now - sLastHotplugBind < 1500L) return;
        sLastHotplugBind = now;
        new Thread(() -> {
            try {
                Thread.sleep(900);
                Log.i(TAG, "外设热插拔，自动重绑 -> display " + target);
                bindAllExternalInputToDisplay(target, true);
            } catch (Throwable t) {
                Log.w(TAG, "热插拔重绑失败: " + t.getMessage());
            }
        }, "aosen-hotplug-rebind").start();
    }

    /**
     * 某块屏被销毁时调用（关掉应用壳窗口、断开串流）：把外设交还给上一块还在的屏，
     * 没有可回退的屏就解绑、交回系统默认路由。
     *
     * <p>没有这一步的话，手柄关联会一直指着已销毁的 display id，事件投递无处可去，
     * 表现就是手机和串流两边都收不到输入。
     */
    public static void onDisplayReleased(int displayId) {
        if (displayId <= 0) return;
        int next = -1;
        synchronized (ShizukuCmd.class) {
            if (sActiveDisplayId != displayId) {
                if (sFallbackDisplayId == displayId) sFallbackDisplayId = -1;
                return;
            }
            sActiveDisplayId = -1;
            int fb = sFallbackDisplayId;
            sFallbackDisplayId = -1;
            if (fb > 0 && fb != displayId && isDisplayAlive(fb)) next = fb;
        }
        if (next > 0) {
            Log.i(TAG, "屏幕 " + displayId + " 已释放，外设回退绑到 display " + next);
            bindAllExternalInputToDisplay(next, true);
        } else {
            Log.i(TAG, "屏幕 " + displayId + " 已释放，外设交还系统默认路由");
            unbindAllExternalInputFromDisplaySync();
        }
    }

    /** 目标屏是否还活着（往已销毁的 display id 投递是无效的）。 */
    private static boolean isDisplayAlive(int displayId) {
        try {
            if (appContext == null) return false;
            android.hardware.display.DisplayManager dm =
                    (android.hardware.display.DisplayManager)
                            appContext.getSystemService(Context.DISPLAY_SERVICE);
            if (dm == null) return false;
            android.view.Display d = dm.getDisplay(displayId);
            return d != null && d.isValid();
        } catch (Throwable t) {
            return false;
        }
    }

    /**
     * 将所有外接鼠标、触控板、指针设备、物理键盘、蓝牙手柄路由绑定至指定屏幕。
     * 从 Linux 内核 / InputFlinger 层面直接向目标屏幕派发输入，彻底消灭中间层！
     */
    public static void bindAllExternalInputToDisplay(int displayId, boolean force) {
        if (true) return; // 统一由外设大脑接管调度，屏蔽特权操作
        if (displayId <= 0) return;
        // 记住目标屏，供外设热插拔时自动补绑（手柄蓝牙断开会重新枚举，拿到新的 deviceId，
        // 原来那条关联就失效了 —— 不补绑的话表现为"刚还能用，过一会儿又不认识了"）。
        // 同时记下上一次绑的屏，作为关闭当前屏后的回退目标
        // （比如关掉应用壳窗口，手柄要回到 Moonlight 的虚拟屏，而不是掉回手机屏）。
        synchronized (ShizukuCmd.class) {
            if (sActiveDisplayId != displayId) sFallbackDisplayId = sActiveDisplayId;
            sActiveDisplayId = displayId;
        }
        // Root 优先：只要 root 可用就走 root UID 的 service call input，完全不碰 Shizuku。
        // 这里绝不能用 Shizuku.pingBinder() 判断 —— 设备上装了 Shizuku 但没授权时
        // pingBinder 仍返回 true，于是走进 Shizuku 分支，拿到的是裸 binder，
        // 调用直接抛 SecurityException: transactRemote requires permission，绑定静默失败。
        // 结果就是手柄/鼠标全留在真 HDMI 外接屏上，虚拟屏一点输入都收不到。
        if (PrivilegeRoute.mode() == PrivilegeRoute.Mode.ROOT) {
            Log.i(TAG, "绑定外接输入 -> display " + displayId + ", 方式=ROOT");
            new Thread(() -> RootInputBinder.bind(appContext, displayId)).start();
            return;
        }
        if (!isShizukuReady()) return;
        long now = android.os.SystemClock.elapsedRealtime();
        if (!force && now - sLastBindTime < 2000L) return;
        if (!sBindInProgress.compareAndSet(false, true)) {
            if (!force) return;
        }
        sLastBindTime = now;
        new Thread(() -> {
            try {
                IBinder displayBinder = new ShizukuBinderWrapper(SystemServiceHelper.getSystemService(Context.DISPLAY_SERVICE));
                IDisplayManager idm = IDisplayManager.Stub.asInterface(displayBinder);
                DisplayInfo displayInfo = idm.getDisplayInfo(displayId);
                if (displayInfo == null) {
                    Log.w(TAG, "无法获取外接屏幕 DisplayInfo (ID: " + displayId + ")");
                    return;
                }

                IInputManager iim = getInputManager();
                if (iim == null) return;

                Map<String, String> portMap = getInputDeviceDescriptorToPortMap();
                int[] deviceIds = iim.getInputDeviceIds();
                if (deviceIds == null) return;

                for (int devId : deviceIds) {
                    InputDevice dev = iim.getInputDevice(devId);
                    if (dev == null) continue;
                    if (!isExternalInputDevice(dev)) {
                        // 自愈：若内置触摸屏/按键曾被旧版误绑到外屏，立即解除关联恢复手机触控
                        try {
                            iim.removeUniqueIdAssociationByDescriptor(dev.getDescriptor());
                        } catch (Throwable ignored) {}
                        continue;
                    }

                    String desc = dev.getDescriptor();
                    int route = routeDisplayFor(dev, displayId);
                    if (route == 0) {
                        // 键鼠一体设备：解除关联，走系统默认路由
                        try { iim.removeUniqueIdAssociationByDescriptor(desc); } catch (Throwable ignored) {}
                        Log.i(TAG, "键鼠一体设备 [" + dev.getName() + "] 解除关联，走系统默认路由");
                        continue;
                    }
                    DisplayInfo info = displayInfo;
                    if (route != displayId) {
                        DisplayInfo hostInfo = idm.getDisplayInfo(route);
                        if (hostInfo != null) info = hostInfo;
                    }
                    Log.i(TAG, "准备将外接输入设备 [" + dev.getName() + " (ID: " + devId + ")] 绑定至 display "
                            + route + " (UID: " + info.uniqueId + ")");

                    boolean bound = false;
                    try {
                        iim.removeUniqueIdAssociationByDescriptor(desc);
                        iim.addUniqueIdAssociationByDescriptor(desc, String.valueOf(info.uniqueId));
                        Log.i(TAG, "成功通过 Descriptor 绑定输入设备: " + dev.getName() + " -> " + info.uniqueId);
                        bound = true;
                    } catch (Throwable t1) {
                        Log.d(TAG, "通过 Descriptor 绑定异常: " + t1.getMessage());
                    }

                    if (!bound) {
                        String inputPort = portMap.get(desc);
                        if (inputPort != null && !inputPort.isEmpty()) {
                            try {
                                iim.removeUniqueIdAssociation(inputPort);
                                iim.addUniqueIdAssociation(inputPort, String.valueOf(info.uniqueId));
                                Log.i(TAG, "成功通过 InputPort 绑定输入设备: " + dev.getName() + " -> " + info.uniqueId);
                                bound = true;
                            } catch (Throwable t2) {
                                try {
                                    iim.removePortAssociation(inputPort);
                                    if (info.address instanceof DisplayAddress.Physical) {
                                        int displayPort = ((DisplayAddress.Physical) info.address).getPort();
                                        iim.addPortAssociation(inputPort, displayPort);
                                        Log.i(TAG, "成功通过 PhysicalPort 绑定输入设备: " + dev.getName() + " -> " + displayPort);
                                        bound = true;
                                    }
                                } catch (Throwable t3) {
                                    Log.w(TAG, "通过 Port 绑定失败: " + t3.getMessage());
                                }
                            }
                        }
                    }
                }
            } catch (Throwable t) {
                Log.e(TAG, "绑定输入设备到外接屏幕异常: " + t.getMessage(), t);
            } finally {
                sBindInProgress.set(false);
            }
        }).start();
    }

    private static volatile long sLastMouseBindTime = 0L;
    private static final java.util.concurrent.atomic.AtomicBoolean sMouseBindInProgress = new java.util.concurrent.atomic.AtomicBoolean(false);

    /**
     * 将外接鼠标设备显式关联绑定到指定显示屏 (displayId)。
     * 统一路由至 bindAllExternalInputToDisplay，确保所有外设同步绑定。
     */
    public static void bindMouseDeviceToDisplay(int displayId) {
        if (true) return; // 统一由外设大脑接管调度，屏蔽特权操作
        bindAllExternalInputToDisplay(displayId, true);
    }

    /**
     * 将单个外接输入设备显式绑定到目标显示屏 (displayId)，displayId <= 0 时解绑回手机自身
     */
    public static void bindSingleInputDevice(String descriptor, int targetDisplayId) {
        if (true) return; // 统一由外设大脑接管调度，屏蔽特权操作
        if (descriptor == null || descriptor.isEmpty()) return;
        new Thread(() -> {
            try {
                if (PrivilegeRoute.mode() == PrivilegeRoute.Mode.ROOT) {
                    RootInputBinder.bindSingle(appContext, descriptor, targetDisplayId);
                    return;
                }
                if (!isShizukuReady()) return;
                IInputManager iim = getInputManager();
                if (iim == null) return;
                try {
                    iim.removeUniqueIdAssociationByDescriptor(descriptor);
                } catch (Throwable ignored) {}

                if (targetDisplayId <= 0) {
                    Log.i(TAG, "外设 [" + descriptor + "] 已解绑回手机自身 (Display 0)");
                    return;
                }

                int actualDisplayId = StreamTarget.get().resolveDisplayId(targetDisplayId);
                if (actualDisplayId < 0) {
                    Log.i(TAG, "串流屏尚未就绪，跳过外设绑定: " + descriptor);
                    return;
                }

                IBinder displayBinder = new ShizukuBinderWrapper(SystemServiceHelper.getSystemService(Context.DISPLAY_SERVICE));
                IDisplayManager idm = IDisplayManager.Stub.asInterface(displayBinder);
                DisplayInfo displayInfo = idm.getDisplayInfo(actualDisplayId);
                if (displayInfo != null && displayInfo.uniqueId != null) {
                    iim.addUniqueIdAssociationByDescriptor(descriptor, String.valueOf(displayInfo.uniqueId));
                    Log.i(TAG, "外设 [" + descriptor + "] 成功绑定至 display " + actualDisplayId + " (" + displayInfo.uniqueId + ")");
                }
            } catch (Throwable t) {
                Log.w(TAG, "绑定单个外设异常: " + t.getMessage());
            }
        }, "bind-single-device").start();
    }

    /**
     * 解绑鼠标设备，使其回归手机主屏 (Display 0)
     */
    public static void unbindMouseDevice() {
        // 同样 Root 优先，不能靠 pingBinder 判断（未授权的 Shizuku 也会是 true）。
        if (PrivilegeRoute.mode() == PrivilegeRoute.Mode.ROOT) {
            new Thread(() -> RootInputBinder.unbind(appContext)).start();
            return;
        }
        if (!isShizukuReady()) return;
        new Thread(() -> {
            try {
                IInputManager iim = getInputManager();
                if (iim == null) return;
                int[] deviceIds = iim.getInputDeviceIds();
                if (deviceIds == null) return;

                for (int devId : deviceIds) {
                    InputDevice dev = iim.getInputDevice(devId);
                    if (dev == null) continue;
                    boolean isMouse = dev.supportsSource(InputDevice.SOURCE_MOUSE)
                            || dev.supportsSource(InputDevice.SOURCE_TOUCHPAD)
                            || dev.supportsSource(InputDevice.SOURCE_TRACKBALL);
                    if (!isMouse) continue;

                    String desc = dev.getDescriptor();
                    try {
                        iim.removeUniqueIdAssociationByDescriptor(desc);
                        Log.i(TAG, "已解绑鼠标设备回到手机: " + dev.getName());
                    } catch (Throwable t) {
                        Log.w(TAG, "解绑鼠标设备异常: " + t.getMessage());
                    }
                }
            } catch (Throwable t) {
                Log.w(TAG, "unbindMouseDevice 异常: " + t.getMessage());
            }
        }).start();
    }

    /**
     * 当拔掉外接屏幕或退出应用时：解除输入设备绑定，使鼠标重新恢复控制手机
     */
    public static void unbindAllExternalInputFromDisplay() {
        if (true) return; // 统一由外设大脑接管调度，屏蔽特权操作
        new Thread(ShizukuCmd::unbindAllExternalInputFromDisplaySync).start();
    }

    /**
     * 同步解绑所有外接输入设备绑定，并彻底清除 dumpsys input 中残留的全部 Unique Id Associations
     */
    public static void unbindAllExternalInputFromDisplaySync() {
        if (true) return; // 统一由外设大脑接管调度，屏蔽特权操作
        // Root 优先：用 root UID 清掉关联，鼠标/手柄才会回到系统默认路由。
        // 不能用 pingBinder 判断，否则未授权的 Shizuku 会让解绑也静默失败，
        // 设备就永久卡在一块已销毁的屏幕 id 上（手机和串流两边都收不到输入）。
        if (PrivilegeRoute.mode() == PrivilegeRoute.Mode.ROOT) {
            Log.i(TAG, "解绑外接输入, 方式=ROOT");
            RootInputBinder.unbind(appContext);
            return;
        }
        if (!isShizukuReady()) return;
        try {
            IInputManager iim = getInputManager();
            if (iim == null) return;

            // 1. 通过 dumpsys input 彻底抓取并清理当前系统残留的所有 Unique Id Associations
            try {
                String dump = execute("dumpsys input");
                if (dump != null && dump.contains("Unique Id Associations:")) {
                    String[] lines = dump.split("\n");
                    boolean inAssoc = false;
                    for (String line : lines) {
                        if (line.contains("Unique Id Associations:")) {
                            inAssoc = true;
                            continue;
                        }
                        if (inAssoc) {
                            if (line.contains("descriptor:")) {
                                String trimmed = line.trim();
                                int dIdx = trimmed.indexOf("descriptor:");
                                int uIdx = trimmed.indexOf("uniqueId:");
                                if (dIdx >= 0 && uIdx > dIdx) {
                                    String desc = trimmed.substring(dIdx + "descriptor:".length(), uIdx).trim();
                                    if (!desc.isEmpty()) {
                                        try {
                                            iim.removeUniqueIdAssociationByDescriptor(desc);
                                            Log.i(TAG, "已成功解绑残留输入设备 Descriptor: " + desc);
                                        } catch (Throwable ignored) {}
                                    }
                                }
                            } else if (!line.trim().startsWith("descriptor") && line.trim().contains(":")) {
                                break;
                            }
                        }
                    }
                }
            } catch (Throwable t) {
                Log.w(TAG, "dumpsys input 解析解绑异常: " + t.getMessage());
            }

            // 2. 遍历当前已连接的所有外设解绑
            Map<String, String> portMap = getInputDeviceDescriptorToPortMap();
            int[] deviceIds = iim.getInputDeviceIds();
            if (deviceIds != null) {
                for (int devId : deviceIds) {
                    InputDevice dev = iim.getInputDevice(devId);
                    if (dev == null) continue;

                    String desc = dev.getDescriptor();
                    try {
                        iim.removeUniqueIdAssociationByDescriptor(desc);
                    } catch (Throwable ignored) {}

                    String inputPort = portMap.get(desc);
                    if (inputPort != null && !inputPort.isEmpty()) {
                        try { iim.removeUniqueIdAssociation(inputPort); } catch (Throwable ignored) {}
                        try { iim.removePortAssociation(inputPort); } catch (Throwable ignored) {}
                    }
                    Log.i(TAG, "已解绑输入设备 [" + dev.getName() + "]，外接设备恢复控制手机");
                }
            }
        } catch (Throwable t) {
            Log.w(TAG, "解绑外部输入设备异常: " + t.getMessage());
        }
    }

    public static int extractDisplayId(String s) {
        int i = s.indexOf("displayId=");
        if (i < 0) return -1;
        int start = i + "displayId=".length();
        int end = start;
        while (end < s.length() && Character.isDigit(s.charAt(end))) end++;
        try {
            return Integer.parseInt(s.substring(start, end));
        } catch (Exception e) {
            return -1;
        }
    }

    /**
     * 获取指定显示屏上当前栈顶(聚焦)应用的组件名，如 "com.pkg/com.pkg.MainActivity"；无则返回 null。
     * 通过 am stack list 解析（每个 stack 块含 displayId 与 topActivity）。
     */
    /** DeX 屏栈顶任务信息（taskId + 组件名） */
    public static class TopTask {
        public final int taskId;
        public final String component;
        TopTask(int taskId, String component) {
            this.taskId = taskId;
            this.component = component;
        }
    }

    /**
     * 解析 "am stack list"，取指定显示屏上栈顶（焦点）的应用任务。
     * 跳过 SystemUI 与 Samsung Launcher（DeX 桌面本身不是"焦点应用"）。
     */
    public static TopTask getTopTaskOnDisplay(int displayId) {
        try {
            String dump = execute("am stack list");
            if (dump == null || dump.isEmpty()) return null;
            String[] lines = dump.split("\n");
            int currentDisplay = -1;
            for (String raw : lines) {
                String line = raw.trim();
                if (line.startsWith("RootTask") && line.contains("displayId=")) {
                    currentDisplay = extractDisplayId(line);
                    continue;
                }
                if (currentDisplay == displayId && line.startsWith("taskId=")) {
                    int colon = line.indexOf(':');
                    if (colon <= "taskId=".length()) continue;
                    int taskId;
                    try {
                        taskId = Integer.parseInt(line.substring("taskId=".length(), colon));
                    } catch (NumberFormatException e) {
                        continue;
                    }
                    int idx = line.indexOf("topActivity=ComponentInfo{");
                    if (idx >= 0) {
                        String seg = line.substring(idx + "topActivity=ComponentInfo{".length());
                        int close = seg.indexOf('}');
                        if (close >= 0) seg = seg.substring(0, close);
                        // seg 形如 "com.pkg/com.pkg.MainActivity"（带 u0 等前缀时剔除）
                        int space = seg.lastIndexOf(' ');
                        if (space >= 0) seg = seg.substring(space + 1);
                        int slash = seg.indexOf('/');
                        boolean isSystemUi = seg.startsWith("com.android.systemui")
                                || seg.startsWith("com.sec.android.app.launcher");
                        if (slash > 0 && !isSystemUi) {
                            return new TopTask(taskId, seg);
                        }
                    }
                }
            }
        } catch (Throwable t) {
            Log.w(TAG, "获取栈顶任务失败: " + t.getMessage());
        }
        return null;
    }

    public static String getTopActivityOnDisplay(int displayId) {
        TopTask t = getTopTaskOnDisplay(displayId);
        return t == null ? null : t.component;
    }

    /**
     * 将指定显示屏的当前焦点应用切换窗口模式。
     * 实测 One UI 8.5 (Android 16) desktopmode 命令通道：
     * - mode 1 = 真全屏：moveTaskOutOfDesk（标题栏消失、铺满整屏，任务栏保留）——真机验证安全。
     * - mode 5 = 恢复窗口：走 am start 降级路径。
     * ⚠️ 严禁使用 moveTaskToDesk：传无效 deskId 会让 Samsung SystemUI 抛
     * "Display for desk=xxx not found" 直接崩溃（11-04 真机实测 3 连崩），永不再用。
     */
    public static boolean relaunchTopAppWithWindowingMode(int displayId, int mode) {
        TopTask top = getTopTaskOnDisplay(displayId);
        if (top == null) {
            Log.w(TAG, "未检测到显示屏 " + displayId + " 上的焦点应用");
            return false;
        }
        Log.i(TAG, "焦点应用: taskId=" + top.taskId + " " + top.component + " 切换模式 " + mode);
        boolean ok;
        if (mode == 1) {
            String r = execute("wm shell desktopmode moveTaskOutOfDesk " + top.taskId);
            Log.i(TAG, "moveTaskOutOfDesk " + top.taskId + ": " + r);
            ok = isShellCmdOk(r);
        } else {
            ok = false; // 恢复窗口无安全通道，直接走降级
        }
        if (!ok) {
            // 降级：am start 方式（对已存在任务通常只拉前台，尽力而为）
            Log.w(TAG, "desktopmode 通道不可用/失败，降级 am start 方式");
            execute("am start -n " + top.component + " --windowingMode " + mode + " --display " + displayId);
        }
        return ok;
    }

    /** 判断 wm shell 输出是否为正常执行（无 Invalid / Not implemented / Exception / Error） */
    private static boolean isShellCmdOk(String result) {
        if (result == null) return true; // 无输出通常代表静默成功
        return !(result.contains("Invalid") || result.contains("Not implemented")
                || result.contains("Exception") || result.contains("Error") || result.contains("error"));
    }

    public static void setDisplayWindowingMode(int displayId, int mode) {
        if (displayId <= 0) return;
        new Thread(() -> {
            try {
                execute("wm set-display-windowing-mode -d " + displayId + " " + mode);
                Log.i(TAG, "已成功设置 Display ID=" + displayId + " 窗口模式为 " + (mode == 5 ? "自由窗口(5)" : "全屏模式(1)"));
            } catch (Throwable t) {
                Log.w(TAG, "setDisplayWindowingMode 异常: " + t.getMessage());
            }
        }).start();
    }

    public static void setScreenRotation(int displayId, int rotation) {
        if (displayId <= 0) return;
        new Thread(() -> {
            try {
                execute("wm set-ignore-orientation-request -d " + displayId + " true");
                execute("wm fixed-to-user-rotation -d " + displayId + " enabled");
                execute("wm user-rotation -d " + displayId + " lock " + rotation);
                Log.i(TAG, "已成功设置 Display ID=" + displayId + " 屏幕旋转为 " + (rotation * 90) + "° (lock " + rotation + ")");
            } catch (Throwable t) {
                Log.w(TAG, "setScreenRotation 异常: " + t.getMessage());
            }
        }).start();
    }

    /**
     * 配置显示环境：
     * 根据用户配置设置窗口模式(5或1)与屏幕旋转(0/1/2/3)；
     * 允许软键盘在有外接设备连接时正常响应唤出，确保手机端正常输入。
     */
    public static void configureDesktopDisplayEnvironment(int displayId) {
        configureDesktopDisplayEnvironment(displayId, 5, 0);
    }

    public static void configureDesktopDisplayEnvironment(int displayId, int windowMode, int rotation) {
        if (displayId <= 0) return;
        new Thread(() -> {
            try {
                if (windowMode > 0) {
                    execute("wm set-display-windowing-mode -d " + displayId + " " + windowMode);
                }
                // 强制所有应用可调整大小（DeX 期间）：声明 resizeableActivity=false 的应用（多数游戏）
                // 否则会被系统强制全屏，无视 freeform 模式与 --windowingMode 5。
                // 注意：One UI 没有 wm set-force-resizeable 命令（实测 Unknown command），
                // 有效的是开发者选项同款全局开关 force_resizable_activities（实测 Balatro 立即开成窗口）。
                enableForceResizableForDeX();
                if (rotation >= 0) {
                    execute("wm set-ignore-orientation-request -d " + displayId + " true");
                    execute("wm fixed-to-user-rotation -d " + displayId + " enabled");
                    execute("wm user-rotation -d " + displayId + " lock " + rotation);
                }

                // 允许软键盘在有外接设备连接时正常响应唤出（确保手机端输入正常）
                execute("settings put secure show_ime_with_hard_keyboard 1");
                execute("settings put system dex_keyboard_on_screen 0");
                execute("settings put system dex_touchpad_show_keyboard 1");
                execute("settings put system dex_show_keyboard 1");

                // 关键修复：IME 策略必须为 ON_SCREEN (0)。原 HIDE (2) 会完全压制虚拟屏上的输入法——
                // 物理键盘只剩英文硬映射，中文输入法无法附着到 DeX 屏的焦点输入框（切什么都没用），
                // 且三星输入法反复尝试附着失败会造成输入管线卡死（DeX 窗口冻住但本 App 不 ANR）。
                // 非主屏默认是 FALLBACK_DISPLAY，输入法会跑到手机上，必须显式改成 0。
                applyOnScreenImePolicy(displayId);
                enableFloatingKeyboardSync();

                Log.i(TAG, "已成功配置 Display ID=" + displayId + " 环境 (Mode=" + windowMode + ", Rotation=" + (rotation * 90) + "°)");
            } catch (Throwable t) {
                Log.w(TAG, "configureDesktopDisplayEnvironment 异常: " + t.getMessage());
            }
        }).start();
    }

    /**
     * 外屏一般为横向宽屏，底部通栏输入法会遮挡约一半可视区域；
     * 唤醒输入法时开启主流输入法（三星 HoneyBoard / Gboard / 百度 / 搜狗 / 讯飞 / 系统通用）的悬浮键盘模式。
     */
    public static void enableFloatingKeyboard() {
        new Thread(ShizukuCmd::enableFloatingKeyboardSync, "floating-ime").start();
    }

    public static void enableFloatingKeyboardSync() {
        try {
            // 三星 HoneyBoard / One UI 悬浮键盘与横屏悬浮模式（2 = 悬浮键盘）
            execute("settings put system honeyboard_keyboard_mode 2");
            execute("settings put system honeyboard_landscape_keyboard_mode 2");
            execute("settings put system honeyboard_floating_keyboard 1");
            execute("settings put secure honeyboard_floating_keyboard 1");
            execute("settings put system samsung_keyboard_floating_mode 1");
            execute("settings put system dex_keyboard_floating 1");
            // Gboard / Android 通用及主流国产 ROM 悬浮键盘开关
            execute("settings put secure gboard_floating_keyboard 1");
            execute("settings put secure floating_keyboard_enabled 1");
            execute("settings put system floating_keyboard_enabled 1");
            execute("settings put secure oplus_floating_keyboard 1");
            execute("settings put secure miui_keyboard_floating 1");
        } catch (Throwable t) {
            Log.w(TAG, "enableFloatingKeyboardSync: " + t.getMessage());
        }
    }

    private static volatile Boolean sLastDisplayZeroLandscapeLock = null;

    /**
     * 为什么手机横屏时外屏/Moonlight 的输入法会自动变成悬浮键盘，而手机竖屏时输入法几乎占满整个外屏？
     * 原因：主流系统输入法（三星 HoneyBoard / 小米搜狗百度 / ColorOS / vivo 等）的「横屏自动悬浮键盘」
     * 读取的是主屏（Display 0 / ApplicationContext）的旋转方向（ROTATION_90 vs ROTATION_0）。
     * 当手机处于竖屏（ROTATION_0）时，输入法误以为当前是竖屏，把竖屏通栏键盘按外屏 1920px 宽度等比放大，
     * 导致高度接近 900px 占满外屏；而一旦主屏（Display 0）为横屏（ROTATION_90），输入法立即切换为精巧的横屏悬浮键盘。
     *
     * 因此当用户正在操控外屏/Moonlight 或唤醒外屏输入法、处于黑屏触控板时，将 Display 0 设为横屏旋转（ROTATION_90）；
     * 当用户回到手机屏幕操作时立即恢复自由旋转。
     */
    public static void setDisplayZeroLandscapeForExternalIme(final boolean landscape) {
        if (sLastDisplayZeroLandscapeLock != null && sLastDisplayZeroLandscapeLock == landscape) {
            return;
        }
        sLastDisplayZeroLandscapeLock = landscape;
        new Thread(() -> {
            try {
                if (isShizukuReady()) {
                    try {
                        IBinder wmBinder = new ShizukuBinderWrapper(
                                SystemServiceHelper.getSystemService(Context.WINDOW_SERVICE));
                        Class<?> stubCls = Class.forName("android.view.IWindowManager$Stub");
                        Object iwm = stubCls.getMethod("asInterface", IBinder.class).invoke(null, wmBinder);
                        if (landscape) {
                            try {
                                iwm.getClass().getMethod("freezeDisplayRotation", int.class, int.class)
                                        .invoke(iwm, Display.DEFAULT_DISPLAY, Surface.ROTATION_90);
                                return;
                            } catch (NoSuchMethodException ignored) {
                                iwm.getClass().getMethod("freezeRotation", int.class)
                                        .invoke(iwm, Surface.ROTATION_90);
                                return;
                            }
                        } else {
                            try {
                                iwm.getClass().getMethod("thawDisplayRotation", int.class)
                                        .invoke(iwm, Display.DEFAULT_DISPLAY);
                                return;
                            } catch (NoSuchMethodException ignored) {
                                iwm.getClass().getMethod("thawRotation").invoke(iwm);
                                return;
                            }
                        }
                    } catch (Throwable ignored) {}
                }
                if (landscape) {
                    execute("wm user-rotation -d 0 lock 1");
                } else {
                    execute("wm user-rotation -d 0 free");
                }
            } catch (Throwable t) {
                Log.w(TAG, "setDisplayZeroLandscapeForExternalIme: " + t.getMessage());
            }
        }, "ime-landscape-rot").start();
    }
}
