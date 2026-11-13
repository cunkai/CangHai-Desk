package com.aosen.shizukudex;

import android.content.Context;
import android.hardware.input.InputManager;
import android.os.SystemClock;
import android.util.Log;
import android.view.InputDevice;

/**
 * 特权环境下的「真硬件鼠标」唯一出口。
 *
 * <pre>
 * Root    → RootInputBridge 守护进程（app_process 在 root 域建 /dev/uinput 节点）
 * Shizuku → ShizukuCmd → UserService（Shizuku 拉起的独立进程里建节点）
 * 零权限  → 什么都不做，isActive() 恒 false，调用方自动回落到内存级虚拟管道
 * </pre>
 *
 * 为什么非要真设备：游戏只认 InputDevice，注入出来的 MotionEvent 在它们眼里是
 * 「不可信事件」；而 uinput 节点被系统当成物理硬件读走，谁都没法拒绝。
 *
 * <p>分成两步，因为它们的时机根本不同：
 * <ol>
 *   <li><b>建节点</b>：鼠标一插上就能做，和设备挂在哪块屏无关；</li>
 *   <li><b>绑屏</b>：必须等虚拟屏建好、拿到真实 displayId。设备没绑屏就写事件，
 *       指针会落在手机主屏（默认屏），等于在用户手机上乱点。</li>
 * </ol>
 * 所以 isActive() 只在<b>绑屏成功之后</b>才为 true，之前一个事件都不许写出去。
 */
public final class VirtualMouse {

    private static final String TAG = "CanghaiVirtualMouse";

    /** 与 RootInputDaemon / UserService 里创建设备用的名字必须一致 */
    public static final String DEVICE_NAME = "Aosen Virtual Mouse";

    public static final int BACKEND_NONE = 0;
    public static final int BACKEND_ROOT = 1;
    public static final int BACKEND_SHIZUKU = 2;

    /** 已绑定到外屏，可以写事件 */
    private static volatile int sBackend = BACKEND_NONE;
    /** 节点已建成（还没绑屏），只做记录，不写事件 */
    private static volatile int sDeviceBackend = BACKEND_NONE;

    private static volatile int sDisplayId = -1;
    private static volatile int sDeviceId = -1;
    /** 绑屏用过的 descriptor，以及那块「内部投递画布」的 id：用户改绑时只动关联，不动身份。 */
    private static volatile String sDescriptor = "";
    private static volatile int sCanvasDisplayId = -1;
    private static volatile boolean sStarting = false;
    private static volatile boolean sBinding = false;
    private static volatile String sLastError = "";

    /** 真设备通道一旦启动，就不许任何人再抢 PointerCapture（见 captureBanned()）。 */
    private static volatile boolean sCaptureBanned = false;

    private static volatile Context appCtx;

    private VirtualMouse() {}

    public static int backend() { return sBackend; }

    public static boolean isActive() { return sBackend != BACKEND_NONE; }

    /**
     * 节点已经建成（不管有没有绑屏）。
     *
     * 上层用它来「停手」：只要这颗真设备存在，就绝不能再向系统申请 PointerCapture。
     * 原因是 AOSP 的 SetPointerCaptureController 一收到捕获请求，会把<b>所有</b>
     * 鼠标设备切到 MODE_POINTER_RELATIVE（sources 变成 0x20004）：光标被 fade 掉、
     * 系统不做命中测试、位移只送给捕获窗口。日志里我们这台设备一直是 0x20004、
     * 屏幕上永远只有一个不动的位置，根子就在这里 —— 不是设备没被判成鼠标，
     * 是我们自己的窗口把它降级了。
     */
    public static boolean hasDevice() { return sDeviceBackend != BACKEND_NONE; }

    /**
     * 一旦决定走真设备通道，PointerCapture 就必须永久禁足 —— 包括「节点已经建成」
     * 之前的那一瞬间。
     *
     * <p>没有这条会死循环：建节点前我们主动放掉捕获 → 系统回调 hasCapture=false →
     * 上层看到 hasDevice() 还是 false，立刻判定「捕获丢了」并开始每 60ms 重抢 →
     * 设备正好生在重抢的空档里，又被配置成 0x20004 相对模式。
     * 所以禁足标记必须在「放手」之前就立起来，且只能由真设备通道自己解除。
     */
    public static boolean captureBanned() { return sCaptureBanned; }

    /**
     * 这台设备是不是大脑自己造出来的虚拟外设。
     *
     * 必须有这道判断：uinput 节点在系统眼里不是 isVirtual()，它长得和真鼠标一模一样，
     * 于是外设大脑会把「自己造的设备」当成「又一枚插进来的真实外设」再建一个槽位、
     * 再写一遍事件 —— 事件在自己造的设备之间来回循环，输入队列被灌满，界面无响应。
     * 一一对应的前提是：一台真实外设对应一台虚拟外设，虚拟外设绝不能再被当成输入源。
     */
    public static boolean isOwnDevice(String name) {
        return name != null && name.startsWith(DEVICE_NAME);
    }

    public static int displayId() { return sDisplayId; }

    /**
     * 大脑层改绑：把那台虚拟鼠标插回原来的内部投递画布，或摘掉关联让它回手机默认屏。
     * 只动输入投递关联，不动 sDisplayId —— 画布身份在大脑里是固定的，
     * 用户看到的外屏是软件端另一条展示链（RealPeripheralBinding）。
     */
    public static boolean rebindToCanvas(boolean external) {
        String desc = sDescriptor;
        if (desc == null || desc.isEmpty()) {
            try { desc = descriptorOf(appCtx, sDeviceId); } catch (Throwable ignored) {}
        }
        if (desc == null || desc.isEmpty()) return false;
        if (!external) return ShizukuCmd.releaseDescriptor(desc);
        int id = sCanvasDisplayId > 0 ? sCanvasDisplayId : sDisplayId;
        if (id <= 0) return false;
        return ShizukuCmd.bindDescriptorToDisplay(desc, id);
    }

    public static String lastError() { return sLastError; }

    public static String backendName() {
        switch (sBackend != BACKEND_NONE ? sBackend : sDeviceBackend) {
            case BACKEND_ROOT: return "Root";
            case BACKEND_SHIZUKU: return "Shizuku";
            default: return "无（内存管道）";
        }
    }

    public static void attachContext(Context ctx) {
        if (ctx != null) appCtx = ctx.getApplicationContext();
    }

    /** 鼠标一插上就调：只建节点，不绑屏。非阻塞。 */
    public static void startAsync(final Context ctx) {
        attachContext(ctx);
        if (sDeviceBackend != BACKEND_NONE || sStarting) return;
        new Thread(() -> {
            try {
                createDevice(appCtx);
            } catch (Throwable t) {
                Log.w(TAG, "start 异常: " + t);
            }
        }, "virtual-mouse-start").start();
    }

    /**
     * 外屏（虚拟屏）建好或换屏时由建屏方调用；屏幕 id 变了就重绑。
     *
     * @param anchorX/anchorY 自绘光标当前所在位置。绑屏成功后会把真设备的指针
     *                        「归位」到这个点，否则系统指针位置和用户看到的箭头对不上，
     *                        点击会落在偏掉的地方。
     */
    public static void onDisplayReady(final Context ctx, final int displayId,
                                      final int anchorX, final int anchorY) {
        if (displayId <= 0) return;
        attachContext(ctx);
        new Thread(() -> {
            try {
                bindToDisplay(appCtx, displayId, anchorX, anchorY);
            } catch (Throwable t) {
                sLastError = "bind: " + t;
                Log.w(TAG, sLastError);
            }
        }, "virtual-mouse-bind").start();
    }

    /** 会话结束：解绑，指针交回系统（下次建屏会重新绑）。 */
    public static void onDisplayGone() {
        sBackend = BACKEND_NONE;
        sDisplayId = -1;
        try { RootInputBridge.releasePhysicalMouse(appCtx); } catch (Throwable ignored) {}
    }

    // ---------------- 第一步：建节点 ----------------

    private static synchronized boolean createDevice(Context ctx) {
        if (sDeviceBackend != BACKEND_NONE) return true;
        if (ctx == null) return false;
        if (sStarting) return false;
        sStarting = true;
        try {
            /* 先禁足，再放手：顺序反了就会被上层的「捕获丢了」重抢逻辑立刻抢回来。 */
            sCaptureBanned = true;
            /* 【必须发生在建节点之前，一个字节都不能晚】
             *
             * 设备的 source 是 InputReader 在 configure() 里按当时的 pointerCapture 定的：
             * 捕获期建出来的设备会被直接判成 MODE_POINTER_RELATIVE（sources=0x20004），
             * 光标被 fade、系统不做命中测试，位移只送给捕获窗口 —— 于是屏幕上我们画的
             * 箭头和系统真正点击的点是两套值，点哪儿都落到自家桌面。
             *
             * 20:46:37 的日志就是这么写的，顺序明显反了：
             *   [40.150] 虚拟外设已创建 ... sources=0x20004（不是鼠标！）
             *   [40.157] 鼠标硬件捕获状态: 已释放      ← 晚了 7 毫秒
             * 设备那一刻已经配置成相对设备，之后再释放捕获也换不回真鼠标模式。
             */
            try {
                MainActivity act = MainActivity.getInstance();
                if (act != null) act.releasePointerCaptureIfHeld();
            } catch (Throwable ignored) {}
            SystemClock.sleep(150);   // 等 InputReader 真正把捕获状态切回来

            final PrivilegeRoute.Mode mode = PrivilegeRoute.mode();
            if (mode == PrivilegeRoute.Mode.ROOT) {
                RootInputBridge.warmUp(ctx);
                for (int i = 0; i < 40 && !RootInputBridge.sUinputReady; i++) SystemClock.sleep(50);
                if (!RootInputBridge.sUinputReady) {
                    sLastError = "root: uinput 节点没建起来";
                    Log.w(TAG, sLastError);
                    return false;
                }
                sDeviceBackend = BACKEND_ROOT;
            } else if (mode == PrivilegeRoute.Mode.SHIZUKU) {
                int rc = ShizukuCmd.uinputOpen(ctx);
                if (rc != 0) {
                    sLastError = "shizuku: uinput open rc=" + rc
                            + " (-13=SELinux 拒绝) " + ShizukuCmd.uinputStatus();
                    Log.w(TAG, sLastError);
                    return false;
                }
                sDeviceBackend = BACKEND_SHIZUKU;
            } else {
                sLastError = "没有 Root/Shizuku";
                return false;
            }

            // 等 EventHub 把这个节点收进来，拿到 deviceId
            int devId = -1;
            for (int i = 0; i < 40 && devId <= 0; i++) {
                SystemClock.sleep(50);
                devId = findDeviceId(ctx);
            }
            if (devId <= 0) {
                sLastError = "设备节点没出现在 InputDevice 列表";
                Log.w(TAG, sLastError);
                /* 这条必须进面板日志：节点建成了但系统没认，是「屏幕上完全没有鼠标」
                 * 最直接的死法，而它以前只写 logcat，我们在日志里什么都看不到，
                 * 只能一直在上层瞎猜。 */
                try {
                    CanghaiErrorCollector.logExt("[大脑后] 虚拟外设没被系统收编："
                            + DEVICE_NAME + " 不在 InputDevice 列表（" + backendName() + "）");
                } catch (Throwable ignored) {}
                resetDevice();
                return false;
            }
            sDeviceId = devId;
            sLastError = "";
            /* 「大脑之后」的第一段证据：虚拟外设到底有没有造出来、系统认没认。
             * 和「大脑前」那行成对看，才知道输入链是哪一段断的。
             * sources 是决定性的一项：游戏（Unity/SDL）只按 InputDevice 的能力判断
             * 「这是不是鼠标」，不看内核里有没有这个节点。SOURCE_MOUSE = 0x2002，
             * 认成 0x1000010（joystick）或 0x1002（touchscreen）时，系统照样派事件，
             * 但游戏那边一律当它不是鼠标 —— 这就是「插上了但游戏不认」。 */
            CanghaiErrorCollector.logExt("[大脑后] 虚拟外设已创建: " + DEVICE_NAME
                    + " deviceId=" + devId + " desc=" + descriptorOf(ctx, devId)
                    + " sources=" + sourcesOf(ctx, devId)
                    + "（" + backendName() + "）");

            /* 复核一次：捕获必须真的交回去了，设备必须是 0x2002。
             * 这一行是「点不中安卓窗口」唯一的判据 —— 只要它还是 0x20004，
             * 后面绑屏、归位、画箭头做得再对也都是白做。 */
            SystemClock.sleep(200);
            final String src2 = sourcesOf(ctx, devId);
            if (src2.contains("0x20004")) {
                CanghaiErrorCollector.logExt("[大脑后] 设备仍是相对模式（sources=" + src2
                        + "）：PointerCapture 没放掉，系统不会做命中测试，"
                        + "点击一定落到自家桌面而不是安卓窗口");
            } else {
                CanghaiErrorCollector.logExt("[大脑后] 捕获已归还，设备模式 " + src2
                        + "（0x2002 = 真鼠标，由系统做命中测试）");
            }
            return true;
        } catch (Throwable t) {
            sLastError = "createDevice: " + t;
            Log.w(TAG, sLastError);
            resetDevice();
            return false;
        } finally {
            sStarting = false;
            /* 真设备没建成（没 Root/Shizuku、节点被拒、系统没收编）时要把禁足解除掉，
             * 否则零权限通道那条「手机当触控板」的老路就再也拿不回捕获了。 */
            if (sDeviceBackend == BACKEND_NONE) sCaptureBanned = false;
        }
    }

    // ---------------- 第二步：绑屏 ----------------

    private static synchronized boolean bindToDisplay(Context ctx, int displayId,
                                                      int anchorX, int anchorY) {
        if (ctx == null) return false;
        if (sBinding) return false;
        /* 不能因为 id 没变就跳过重绑：虚拟屏每次重建，uniqueId 末尾的序号都会变
         * （Aosen_VD_Moonlight,9 → ,10），上一条关联指向已销毁的屏，系统解析不到
         * viewport 就回落手机内屏（DisplayId=-1 / 480dpi）。每次屏就绪都重绑一次，
         * bindSingle 内部本来就是「先 remove 再 add」，顺手把悬空关联清掉。 */
        sBinding = true;
        try {
            if (sDeviceBackend == BACKEND_NONE && !createDevice(ctx)) return false;

            /* 分工是「大脑握着真设备 + 一对一造出对应的虚拟设备 + 插到目标窗口」：
             *
             *   真实鼠标 --(EVIOCGRAB，系统看不见)--> 大脑 --> 虚拟鼠标 --(绑屏)--> 目标显示
             *
             * 绑屏（addUniqueIdAssociationByDescriptor）就是「插进这个窗口」这一步，
             * 少了它，虚拟设备只是躺在系统里，指针和点击都落在手机主屏。 */
            int devId = findDeviceId(ctx);
            if (devId > 0) sDeviceId = devId;
            if (sDeviceId <= 0) {
                sLastError = "绑屏时找不到虚拟设备节点";
                Log.w(TAG, sLastError);
                return false;
            }
            String desc = descriptorOf(ctx, sDeviceId);
            if (desc == null || desc.isEmpty()) {
                sLastError = "虚拟设备 descriptor 为空，无法绑屏";
                Log.w(TAG, sLastError);
                return false;
            }
            boolean bound = false;
            CanghaiErrorCollector.logExt("[大脑后] 开始插入虚拟外设到 display " + displayId
                    + "（deviceId=" + sDeviceId + " desc=" + desc + "）");
            for (int i = 0; i < 5 && !bound; i++) {
                bound = ShizukuCmd.bindDescriptorToDisplay(desc, displayId);
                if (!bound) SystemClock.sleep(120);
            }
            if (!bound) {
                sLastError = "插入 display " + displayId + " 失败（desc=" + desc + "）";
                Log.w(TAG, sLastError);
                CanghaiErrorCollector.logExt("[大脑后] 虚拟外设插入失败 display " + displayId
                        + "（desc=" + desc + "，5 次重试都没成）");
                sBackend = BACKEND_NONE;
                sDisplayId = -1;
                return false;
            }

            sBackend = sDeviceBackend;
            sDisplayId = displayId;
            sDescriptor = desc;
            if (sCanvasDisplayId <= 0) sCanvasDisplayId = displayId;
            sLastError = "";
            CanghaiErrorCollector.logExt("[大脑后] 虚拟外设已插入 display " + displayId
                    + "（" + backendName() + "），系统从此按这个设备派发指针");

            // 相对坐标设备做一次确定性归位：先推到 (0,0)，再推回用户看得见的位置
            homeTo(anchorX, anchorY);

            // 大脑握着真设备：抢过来之后系统看不见它，指针只由我们这台虚拟设备产生，
            // 一对一，不会出现「系统箭头 + 我们箭头」两根指针互相打架。
            boolean requested = false;
            try { requested = RootInputBridge.grabPhysicalMouse(ctx); } catch (Throwable ignored) {}
            // 独占结果是从守护进程异步回来的（RBG 行），等一小会儿才能对账
            for (int i = 0; i < 20 && requested && !RootInputBridge.isRawGrabbed(); i++) {
                SystemClock.sleep(50);
            }
            final String heldName = RootInputBridge.grabbedName();
            CanghaiErrorCollector.logExt("[链路对账] "
                    + (heldName.isEmpty() ? "真实鼠标(大脑未握住)" : "真实鼠标「" + heldName + "」")
                    + " --[独占 " + (RootInputBridge.isRawGrabbed() ? "ok" : "失败") + "]--> 大脑"
                    + " --[虚拟设备 " + DEVICE_NAME + " deviceId=" + sDeviceId + "]--> display "
                    + displayId + " [插入 ok]");
            return true;
        } catch (Throwable t) {
            sLastError = "bindToDisplay: " + t;
            Log.w(TAG, sLastError);
            sBackend = BACKEND_NONE;
            sDisplayId = -1;
            return false;
        } finally {
            sBinding = false;
        }
    }

    private static void homeTo(int x, int y) {
        try {
            // 绝对坐标设备不需要「推到角落再推回来」这种相对归位：直接把指针
            // 摆到用户当前看得见的位置即可，系统照这个点点击。
            moveTo(x, y);
            SystemClock.sleep(30);
        } catch (Throwable ignored) {}
    }

    private static void resetDevice() {
        if (sDeviceBackend == BACKEND_SHIZUKU) {
            try { ShizukuCmd.uinputClose(); } catch (Throwable ignored) {}
        }
        sDeviceBackend = BACKEND_NONE;
        sBackend = BACKEND_NONE;
        sDisplayId = -1;
        sDeviceId = -1;
    }

    // ---------------- 事件写入（全部在一条专用写线程上） ----------------

    /* 为什么必须另起写线程：
     * 写 uinput 实际是「往守护进程的管道里写字节」。守护进程一旦卡住（ioctl 阻塞、
     * 被杀但管道没断），write 会一直阻塞；而这些调用全发生在 UI 线程的按键/移动
     * 回调里。17:45:41 按下、51.98 才抬手，中间整整 10 秒，正好是 ANR 阈值 ——
     * 这个形状只有「UI 线程卡在某次写」能解释。写线程一卡，最多丢输入，界面照转。 */
    private static final java.util.concurrent.atomic.AtomicInteger sPending =
            new java.util.concurrent.atomic.AtomicInteger();
    private static volatile java.util.concurrent.ExecutorService sWriter;

    /** 按键状态：同一颗键已经是按下状态就不再重复按下（两套事件源的必要去重）。 */
    private static final boolean[] sDown = new boolean[3];

    private static java.util.concurrent.ExecutorService writer() {
        java.util.concurrent.ExecutorService w = sWriter;
        if (w != null) return w;
        synchronized (VirtualMouse.class) {
            if (sWriter == null) {
                sWriter = java.util.concurrent.Executors.newSingleThreadExecutor(r -> {
                    Thread t = new Thread(r, "virtual-mouse-writer");
                    t.setPriority(Thread.MAX_PRIORITY);
                    return t;
                });
            }
            return sWriter;
        }
    }

    private static void post(Runnable task, boolean droppable) {
        // 位移可以丢（丢了下一帧就补上），按键绝不能丢：丢一次抬起就永久粘住。
        if (droppable && sPending.get() > 256) return;
        sPending.incrementAndGet();
        try {
            writer().execute(() -> {
                try {
                    task.run();
                } catch (Throwable ignored) {
                } finally {
                    sPending.decrementAndGet();
                }
            });
        } catch (Throwable t) {
            sPending.decrementAndGet();
        }
    }

    /** 相对位移，像素为单位（和注入路径同一个量纲） */
    public static void move(float dx, float dy) {
        moveBy(dx, dy);
    }

    /* ================= 指针位置 =================
     *
     * 设备是相对位移设备（REL_X/REL_Y），这是 Android 唯一支持的形式：
     * CursorInputMapper 只读 EV_REL，绝对轴连一行处理都没有。所以「写到设备上」的
     * 只能是增量，位置是双方各自累加出来的：
     *
     *   真实鼠标（大脑独占）→ 增量 → ① 写进虚拟设备（系统累加，决定点哪儿）
     *                                ② 我们自己累加（决定箭头画哪儿）
     *
     * 两边能对上只有一个前提：起点相同、步长 1:1。所以绑屏时先做一次「归位」——
     * 发一个足够大的负增量让系统的 PointerController 钳到 (0,0)，再发 (x,y)，
     * 系统指针就精确落在 (x,y)，我们的累加器也从 (x,y) 起算。之后每帧同一个增量，
     * 两边同步走。 */
    private static final Object sPosLock = new Object();
    private static float sPosX;
    private static float sPosY;
    private static volatile int sSpaceW;
    private static volatile int sSpaceH;

    /** 当前虚拟投递画布的像素尺寸；软件端把指针换算到真实外屏时用它。 */
    public static int[] space() { return new int[]{sSpaceW, sSpaceH}; }

    /** 目标屏的像素尺寸：边界钳制要用它，绑屏时由建屏方告知。 */
    public static void setSpace(int width, int height) {
        if (width <= 1 || height <= 1) return;
        boolean first = sSpaceW <= 1 || sSpaceH <= 1;
        sSpaceW = width;
        sSpaceH = height;
        if (first) {
            synchronized (sPosLock) {
                sPosX = width / 2f;
                sPosY = height / 2f;
            }
        }
    }

    /** 当前指针位置（虚拟屏像素），没进入真设备通道时返回 null。 */
    public static float[] position() {
        if (sBackend == BACKEND_NONE) return null;
        synchronized (sPosLock) {
            return new float[]{sPosX, sPosY};
        }
    }

    /** 按位移推进指针：两个消费者吃同一个整数增量。 */
    /* 小数残差：硬件给的是浮点 REL 值，乘完灵敏度（1.5 之类）几乎每帧都带小数，
     * 而写进 uinput 的只能是整数。以前是「两边各自 Math.round」——
     * 设备走 round(1.5)=2，我们自己累加 1.5，一帧差 0.5 像素，几百帧下来就是
     * 日志里那 100~400 像素的偏差，用户看到的就是「图标画在这儿、点击落在那儿」。
     * 现在小数部分留到下一帧，设备走多少，我们的位置就加多少，永远严格相等。 */
    private static float sFracX;
    private static float sFracY;

    public static void moveBy(float dx, float dy) {
        if (sBackend == BACKEND_NONE) return;
        if (dx == 0f && dy == 0f) return;
        int w = sSpaceW > 1 ? sSpaceW : 1920;
        int h = sSpaceH > 1 ? sSpaceH : 1080;
        int ix, iy;
        float nx, ny;
        synchronized (sPosLock) {
            float tx = dx + sFracX;
            float ty = dy + sFracY;
            ix = Math.round(tx);
            iy = Math.round(ty);
            sFracX = tx - ix;
            sFracY = ty - iy;
            nx = sPosX + ix;
            ny = sPosY + iy;
            /* 系统那边 PointerController 会钳在屏边界，钳掉的那部分增量谁都拿不到；
             * 我们同样钳掉并把残差清零，否则贴边滑动时会一直攒着一笔账。 */
            if (nx < 0f || nx > w - 1f) {
                nx = Math.max(0f, Math.min(w - 1f, nx));
                sFracX = 0f;
            }
            if (ny < 0f || ny > h - 1f) {
                ny = Math.max(0f, Math.min(h - 1f, ny));
                sFracY = 0f;
            }
            sPosX = nx;
            sPosY = ny;
        }
        if (ix != 0 || iy != 0) writeRel(ix, iy);
        AosenPointerOverlay.move(nx, ny);
        traceMove(Math.round(nx), Math.round(ny), ix, iy, "moveBy(自己积分)");
    }

    /**
     * 用系统算出来的落点校正我们自己的位置：只搬箭头，不写设备。
     *
     * <p>这是「看见哪就点哪」的兜底：增量推算再准也会因为丢帧、钳边界慢慢偏，
     * 而目标屏上窗口收到的 x/y 是系统自己算好的落点，是唯一权威。
     * 偏差小就直接对齐；偏差大（说明那一帧不是真落点，比如捕获期残留的旧坐标）
     * 一律不动 —— 宁可慢一帧，也不能把箭头甩到屏角。
     */
    public static void resync(float x, float y) {
        if (sBackend == BACKEND_NONE) return;
        int w = sSpaceW > 1 ? sSpaceW : 1920;
        int h = sSpaceH > 1 ? sSpaceH : 1080;
        float nx = Math.max(0f, Math.min(w - 1f, x));
        float ny = Math.max(0f, Math.min(h - 1f, y));
        synchronized (sPosLock) {
            sPosX = nx;
            sPosY = ny;
            sFracX = 0f;
            sFracY = 0f;
        }
        AosenPointerOverlay.move(nx, ny);
    }

    /** 直接摆到某个点：先发一个超大负增量把系统指针钳到角落，再走 (x,y)。 */
    public static void moveTo(float x, float y) {
        if (sBackend == BACKEND_NONE) return;
        int w = sSpaceW > 1 ? sSpaceW : 1920;
        int h = sSpaceH > 1 ? sSpaceH : 1080;
        float nx = Math.max(0f, Math.min(w - 1f, x));
        float ny = Math.max(0f, Math.min(h - 1f, y));
        synchronized (sPosLock) {
            sPosX = nx;
            sPosY = ny;
            sFracX = 0f;   // 归位是绝对定位，残差必须清零，否则下一帧又带出去半个像素
            sFracY = 0f;
        }
        /* PointerController 会把超界增量钳在屏边界，所以这两步是确定性的：
         * 先到 (0,0)，再走 (x,y)。中间那个 SYN 必须发，否则两步会在同一批里
         * 被合并成一次 delta，归位就白做了。 */
        writeRel(-32767, -32767);
        SystemClock.sleep(2);
        writeRel(Math.round(nx), Math.round(ny));
        AosenPointerOverlay.move(nx, ny);
        traceMove(Math.round(nx), Math.round(ny), Math.round(nx), Math.round(ny),
                "resync(系统落点回灌，唯一能拉回漂移的入口)");
    }

    private static void writeRel(final int dx, final int dy) {
        if (dx == 0 && dy == 0) return;
        final int backend = sBackend;
        post(() -> {
            try {
                if (backend == BACKEND_ROOT) RootInputBridge.uinputMove(appCtx, dx, dy);
                else ShizukuCmd.uinputMove(dx, dy);
            } catch (Throwable t) {
                Log.w(TAG, "move 失败: " + t);
            }
        }, true);
    }

    /* 位移每秒几十次，只节流抽样记录：日志里能不能看到「设备真在动」是排查第一现场，
     * 全量打会把队列冲掉。 */
    private static long sLastMoveTrace;
    private static void traceMove(int x, int y, int relX, int relY, String src) {
        long now = SystemClock.uptimeMillis();
        if (now - sLastMoveTrace < 600) return;
        sLastMoveTrace = now;
        try {
            CanghaiErrorCollector.logExt("[真设备] 指针 " + x + "," + y
                    + " @display " + sDisplayId + "（" + backendName() + "）");
            CanghaiErrorCollector.pointerAudit("B-uinput自算",
                    "来源=" + src + " 写出REL=(" + relX + "," + relY + ") 落点=(" + x + "," + y + ")"
                            + " sDisplayId=" + sDisplayId + " canvasDisplayId=" + sCanvasDisplayId
                            + " 空间=" + sSpaceW + "x" + sSpaceH
                            + " ｜这是 VirtualMouse 自己累加的数，不是系统 PointerController 落点；"
                            + "AosenPointerOverlay.move 也喂这个数", true);
        } catch (Throwable ignored) {}
    }

    public static void button(int buttonCode, boolean down) {
        if (sBackend == BACKEND_NONE) return;
        /* MotionEvent.BUTTON_PRIMARY=1 / SECONDARY=2 / TERTIARY=4 是 Android 的
         * 「按键掩码」，不是内核键码。直接把它们写进 uinput 的 EV_KEY，1 是 KEY_ESC、
         * 2 是 KEY_1、4 是 KEY_3 —— 系统收到的是一串键盘按键，根本不是鼠标点击。
         * 必须在写之前换成 BTN_LEFT/RIGHT/MIDDLE。 */
        final int keyCode = toKeyCode(buttonCode);
        if (keyCode <= 0) return;
        if (!markButton(keyCode, down)) return;
        traceButton(buttonCode, down);
        if (keyCode == UinputNative.BTN_LEFT) AosenPointerOverlay.setPressed(down);
        final int backend = sBackend;
        post(() -> writeButton(backend, keyCode, down), false);
    }

    /** 一次完整点击（按下 + 抬起）。整段放在写线程里，12ms 的间隔绝不能睡在 UI 线程。 */
    public static void click(int buttonCode) {
        if (sBackend == BACKEND_NONE) return;
        final int keyCode = toKeyCode(buttonCode);
        if (keyCode <= 0) return;
        if (!markButton(keyCode, true)) return;
        traceButton(buttonCode, true);
        traceButton(buttonCode, false);
        final int backend = sBackend;
        post(() -> {
            writeButton(backend, keyCode, true);
            SystemClock.sleep(12);
            writeButton(backend, keyCode, false);
            markButton(keyCode, false);
        }, false);
    }

    private static final long[] sDownAt = new long[3];

    /** 返回 true 表示这条事件需要真的写出去（重复的按下/抬起被吃掉）。 */
    private static boolean markButton(int keyCode, boolean down) {
        int idx = keyIndex(keyCode);
        if (idx < 0) return true;
        synchronized (sDown) {
            long now = SystemClock.uptimeMillis();
            if (down) {
                // 卡住超过 2 秒当成脏状态放行，避免一次丢抬起把后续按键全废掉
                if (sDown[idx] && now - sDownAt[idx] < 2000) return false;
                sDown[idx] = true;
                sDownAt[idx] = now;
                return true;
            }
            if (!sDown[idx]) return false;
            sDown[idx] = false;
            return true;
        }
    }

    private static int keyIndex(int keyCode) {
        if (keyCode == UinputNative.BTN_LEFT) return 0;
        if (keyCode == UinputNative.BTN_RIGHT) return 1;
        if (keyCode == UinputNative.BTN_MIDDLE) return 2;
        return -1;
    }

    private static void writeButton(int backend, int keyCode, boolean down) {
        try {
            if (backend == BACKEND_ROOT) RootInputBridge.uinputButton(appCtx, keyCode, down);
            else ShizukuCmd.uinputButton(keyCode, down);
        } catch (Throwable t) {
            Log.w(TAG, "button 失败: " + t);
        }
    }

    private static void traceButton(int buttonCode, boolean down) {
        try {
            CanghaiErrorCollector.logExt("[真设备] " + buttonName(buttonCode)
                    + (down ? " 按下" : " 抬起") + " @display " + sDisplayId
                    + "（" + backendName() + "）");
        } catch (Throwable ignored) {}
    }

    /** Android 按键掩码 → Linux 内核 BTN_* 键码 */
    private static int toKeyCode(int buttonCode) {
        if ((buttonCode & android.view.MotionEvent.BUTTON_PRIMARY) != 0) return UinputNative.BTN_LEFT;
        if ((buttonCode & android.view.MotionEvent.BUTTON_SECONDARY) != 0) return UinputNative.BTN_RIGHT;
        if ((buttonCode & android.view.MotionEvent.BUTTON_TERTIARY) != 0) return UinputNative.BTN_MIDDLE;
        if ((buttonCode & android.view.MotionEvent.BUTTON_BACK) != 0) return UinputNative.BTN_SIDE;
        if ((buttonCode & android.view.MotionEvent.BUTTON_FORWARD) != 0) return UinputNative.BTN_EXTRA;
        return -1;
    }

    private static String buttonName(int code) {
        if ((code & android.view.MotionEvent.BUTTON_PRIMARY) != 0) return "左键";
        if ((code & android.view.MotionEvent.BUTTON_SECONDARY) != 0) return "右键";
        if ((code & android.view.MotionEvent.BUTTON_TERTIARY) != 0) return "中键";
        return "按键" + code;
    }

    public static void scroll(float vScroll, float hScroll) {
        if (sBackend == BACKEND_NONE) return;
        final int v = Math.round(vScroll), h = Math.round(hScroll);
        if (v == 0 && h == 0) return;
        final int backend = sBackend;
        post(() -> {
            try {
                if (backend == BACKEND_ROOT) RootInputBridge.uinputScroll(appCtx, v, h);
                else ShizukuCmd.uinputScroll(v, h);
            } catch (Throwable t) {
                Log.w(TAG, "scroll 失败: " + t);
            }
        }, true);
    }

    // ---------------- 内部工具 ----------------

    private static int findDeviceId(Context ctx) {
        try {
            InputManager im = (InputManager) ctx.getSystemService(Context.INPUT_SERVICE);
            if (im == null) return -1;
            for (int id : im.getInputDeviceIds()) {
                InputDevice d = im.getInputDevice(id);
                if (d != null && DEVICE_NAME.equals(d.getName())) return id;
            }
        } catch (Throwable ignored) {}
        return -1;
    }

    private static String descriptorOf(Context ctx, int deviceId) {
        try {
            InputManager im = (InputManager) ctx.getSystemService(Context.INPUT_SERVICE);
            if (im == null) return "";
            InputDevice d = im.getInputDevice(deviceId);
            if (d == null) return "";
            String desc = d.getDescriptor();
            if (desc != null && !desc.isEmpty()) return desc;
            // uinput 设备常常没有 uniq（descriptor 为空），退而用「名字」当关联键：
            // InputManager 允许用 device 的 descriptor 字符串做关联，部分机型也吃名字。
            return d.getName() == null ? "" : d.getName();
        } catch (Throwable t) {
            return "";
        }
    }

    /**
     * 系统给这台虚拟设备判定的能力位。判断「游戏为什么不认」只能看它：
     * SOURCE_MOUSE = 0x2002；若只出现 0x1000010（joystick）或 0x1002（touchscreen），
     * 说明内核节点是通的、系统也在派事件，但游戏按 InputDevice 能力判定「这不是鼠标」。
     */
    private static String sourcesOf(Context ctx, int deviceId) {
        try {
            InputManager im = (InputManager) ctx.getSystemService(Context.INPUT_SERVICE);
            if (im == null) return "?";
            InputDevice d = im.getInputDevice(deviceId);
            if (d == null) return "?";
            int src = d.getSources();
            boolean mouse = (src & InputDevice.SOURCE_MOUSE) == InputDevice.SOURCE_MOUSE;
            return "0x" + Integer.toHexString(src) + (mouse ? "（鼠标）" : "（不是鼠标！）");
        } catch (Throwable t) {
            return "?";
        }
    }
}