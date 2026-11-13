package com.aosen.shizukudex;

import android.os.SystemClock;
import android.view.InputDevice;
import android.view.KeyEvent;
import android.view.MotionEvent;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayDeque;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * 输入诊断记录器 —— 桌面「输入监视」小工具的数据源。
 *
 * 设计目标（对应用户要求：能直观看到右键到底是什么、任何外设都能显示按了什么）：
 *  - 记录所有低频输入：按下 / 抬起 / 滚轮 / 手柄按键 / 摇杆方向；
 *  - 高频 MOVE / HOVER_MOVE 不写日志，只更新最后位置，绝不把 CPU 拖垮；
 *  - 默认关闭，只有桌面里打开「输入监视」时才打开，关闭窗口立刻清空并停止记录；
 *  - 两种来源分别标记：「桌面」= 事件真的到达了桌面看板；「Moonlight」= 客户端原始注入帧，
 *    两者对比就能判断右键是丢在注入阶段还是丢在 WebView 阶段。
 */
public final class InputTrace {

    private static final int MAX_LOG = 80;

    private static volatile boolean enabled = true;

    private static final ArrayDeque<Rec> log = new ArrayDeque<>();
    private static final Set<Integer> keysDown = new LinkedHashSet<>();
    private static final Set<Integer> buttonsDown = new LinkedHashSet<>();

    private static Rec lastRec = null;

    /** 当前活跃设备（最近一次产生事件的外设名），面板顶部直接显示"谁在按"。 */
    private static volatile String activeDevice = "";
    private static volatile long activeDeviceAt = 0;

    /**
     * 摇杆轴实时值：0=X 1=Y 2=RX 3=RY 4=HAT_X 5=HAT_Y。
     * 高频 ACTION_MOVE 只更新这里、绝不写日志，所以面板能实时看到摇杆位移而不会把 CPU 拖垮。
     */
    private static final float[] axes = new float[6];

    /** 最近一次滚轮方向与累计次数（滚轮是离散事件，只记最近一次即可）。 */
    private static volatile float wheelV = 0f;
    private static volatile float wheelH = 0f;
    private static volatile int wheelCount = 0;
    private static volatile long wheelAt = 0;

    private InputTrace() {
    }

    // ======================= 开关 / 清空 =======================

    public static void setEnabled(boolean on) {
        enabled = on;
        if (!on) clear();
    }

    public static boolean isEnabled() {
        return enabled;
    }

    public static void clear() {
        synchronized (log) {
            log.clear();
            lastRec = null;
        }
        synchronized (keysDown) {
            keysDown.clear();
        }
        synchronized (buttonsDown) {
            buttonsDown.clear();
        }
        synchronized (axes) {
            for (int i = 0; i < axes.length; i++) axes[i] = 0f;
        }
        activeDevice = "";
        activeDeviceAt = 0;
        lastMoveAt = 0L;
        wheelV = 0f;
        wheelH = 0f;
        wheelCount = 0;
        wheelAt = 0;
    }

    // ======================= 黑屏触控专用通道 =======================

    /**
     * 黑屏触控板手指接触 / 离开
     */
    public static void touchpadTouch(boolean down) {
        activeDevice = "手机黑屏触控板";
        activeDeviceAt = SystemClock.uptimeMillis();
        if (!down) {
            synchronized (axes) {
                axes[0] = 0f;
                axes[1] = 0f;
            }
        }
    }

    /**
     * 黑屏触控板滑动 / 移动
     */
    public static void touchpadMotion(float screenX, float screenY, float dx, float dy) {
        activeDevice = "手机黑屏触控板";
        activeDeviceAt = SystemClock.uptimeMillis();
        synchronized (axes) {
            // 将相对滑动矢量映射到摇杆轴（-1.0 ~ 1.0），直接驱动「调试外设」小工具里的虚拟摇杆圆点！
            axes[0] = Math.max(-1.0f, Math.min(1.0f, dx / 15.0f));
            axes[1] = Math.max(-1.0f, Math.min(1.0f, dy / 15.0f));
        }
    }

    /**
     * 黑屏触控板点击（左键 / 右键）
     */
    public static void touchpadClick(float x, float y, boolean rightClick) {
        activeDevice = "手机黑屏触控板";
        activeDeviceAt = SystemClock.uptimeMillis();
        int btn = rightClick ? MotionEvent.BUTTON_SECONDARY : MotionEvent.BUTTON_PRIMARY;

        synchronized (buttonsDown) {
            buttonsDown.add(btn);
        }

        Rec r = new Rec();
        r.t = SystemClock.uptimeMillis();
        r.origin = "黑屏触控";
        r.kind = "mouse";
        r.action = rightClick ? "右键点击" : "左键点击";
        r.name = rightClick ? "鼠标右键" : "鼠标左键";
        r.device = "手机黑屏触控板";
        r.x = x;
        r.y = y;
        r.actionButton = btn;
        r.buttonState = btn;
        r.down = true;
        push(r);

        // 120ms 后自动清除按下高亮，让 UI 上的按钮亮起后恢复
        new android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(() -> {
            synchronized (buttonsDown) {
                buttonsDown.remove(btn);
            }
        }, 120);
    }

    /**
     * 黑屏触控板双指滚动
     */
    public static void touchpadScroll(float x, float y, float vScroll, float hScroll) {
        activeDevice = "手机黑屏触控板";
        activeDeviceAt = SystemClock.uptimeMillis();
        wheelV = vScroll;
        wheelH = hScroll;
        wheelCount++;
        wheelAt = SystemClock.uptimeMillis();

        Rec r = new Rec();
        r.t = SystemClock.uptimeMillis();
        r.origin = "黑屏触控";
        r.kind = "scroll";
        r.action = "滚轮滚动";
        r.name = "双指滑轮";
        r.device = "手机黑屏触控板";
        r.x = x;
        r.y = y;
        r.v = vScroll;
        r.h = hScroll;
        push(r);
    }

    // ======================= 物理外接鼠标专用通道 =======================

    /**
     * 物理鼠标移动
     *
     * <p>以前这里只更新 axes 和 activeDevice，一条记录都不进 log。结果是：面板顶部
     * 已经能显示真鼠标的名字了，下面的事件列表却永远是空的 —— 用户看到的就是
     * 「设备名有了，数据没有」。移动本身是高频事件，不能每帧都写，所以按 400ms
     * 节流留一条，既能证明链路在动，又不会把按键记录挤出去。
     */
    public static void mouseMotion(String deviceName, float screenX, float screenY, float dx, float dy) {
        activeDevice = (deviceName != null && !deviceName.isEmpty()) ? deviceName : "外接鼠标";
        activeDeviceAt = SystemClock.uptimeMillis();
        synchronized (axes) {
            axes[0] = Math.max(-1.0f, Math.min(1.0f, dx / 15.0f));
            axes[1] = Math.max(-1.0f, Math.min(1.0f, dy / 15.0f));
        }
        if (!enabled) return;
        long now = activeDeviceAt;
        if (now - lastMoveAt < 400L) return;
        lastMoveAt = now;
        Rec r = new Rec();
        r.t = now;
        r.origin = "物理外设";
        r.kind = "mouse";
        r.action = "MOVE";
        r.name = "指针移动";
        r.device = activeDevice;
        r.source = InputDevice.SOURCE_MOUSE;
        r.x = screenX;
        r.y = screenY;
        r.down = false;
        push(r);
    }

    private static volatile long lastMoveAt = 0L;

    /**
     * 物理鼠标点击（左键、中键、右键）
     */
    /**
     * 物理鼠标按键按下
     */
    public static void mousePress(String deviceName, float x, float y, int btn) {
        mousePress(deviceName, x, y, btn, "");
    }

    public static void mousePress(String deviceName, float x, float y, int btn, String dest) {
        activeDevice = (deviceName != null && !deviceName.isEmpty()) ? deviceName : "外接鼠标";
        activeDeviceAt = SystemClock.uptimeMillis();
        String btnName = (btn == MotionEvent.BUTTON_SECONDARY) ? "鼠标右键"
                : (btn == MotionEvent.BUTTON_TERTIARY ? "鼠标中键" : "鼠标左键");

        synchronized (buttonsDown) {
            buttonsDown.add(btn);
        }

        Rec r = new Rec();
        r.t = SystemClock.uptimeMillis();
        r.origin = "物理外设";
        r.kind = "mouse";
        r.action = btnName + "按下";
        r.name = btnName;
        r.device = activeDevice;
        r.x = x;
        r.y = y;
        r.actionButton = btn;
        r.buttonState = btn;
        r.dest = dest;
        r.down = true;
        push(r);
    }

    /**
     * 物理鼠标按键抬起。
     *
     * <p>按下有 [mousePress]，抬起以前完全没有记录口 —— 面板上就变成「只有按下没有抬起」，
     * 看着像按键丢了。这里补上对称的一条，按下 / 抬起成对出现才是真实链路。
     */
    public static void mouseRelease(String deviceName, float x, float y, int btn, String dest) {
        activeDevice = (deviceName != null && !deviceName.isEmpty()) ? deviceName : "外接鼠标";
        activeDeviceAt = SystemClock.uptimeMillis();
        String btnName = (btn == MotionEvent.BUTTON_SECONDARY) ? "鼠标右键"
                : (btn == MotionEvent.BUTTON_TERTIARY ? "鼠标中键" : "鼠标左键");
        synchronized (buttonsDown) {
            buttonsDown.remove(btn);
        }
        Rec r = new Rec();
        r.t = SystemClock.uptimeMillis();
        r.origin = "物理外设";
        r.kind = "mouse";
        r.action = btnName + "抬起";
        r.name = btnName;
        r.device = activeDevice;
        r.x = x;
        r.y = y;
        r.actionButton = btn;
        r.buttonState = 0;
        r.dest = dest;
        r.down = false;
        push(r);
    }

    public static void mouseClick(String deviceName, float x, float y, int btn) {
        mouseClick(deviceName, x, y, btn, "");
    }

    public static void mouseClick(String deviceName, float x, float y, int btn, String dest) {
        activeDevice = (deviceName != null && !deviceName.isEmpty()) ? deviceName : "外接鼠标";
        activeDeviceAt = SystemClock.uptimeMillis();
        String actionName;
        String btnName;
        if (btn == MotionEvent.BUTTON_SECONDARY) {
            actionName = "右键点击";
            btnName = "鼠标右键";
        } else if (btn == MotionEvent.BUTTON_TERTIARY) {
            actionName = "中键点击";
            btnName = "鼠标中键";
        } else {
            actionName = "左键点击";
            btnName = "鼠标左键";
        }

        synchronized (buttonsDown) {
            buttonsDown.add(btn);
        }

        Rec r = new Rec();
        r.t = SystemClock.uptimeMillis();
        r.origin = "物理外设";
        r.kind = "mouse";
        r.action = actionName;
        r.name = btnName;
        r.device = activeDevice;
        r.x = x;
        r.y = y;
        r.actionButton = btn;
        r.buttonState = btn;
        r.dest = dest;
        r.down = true;
        push(r);

        new android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(() -> {
            synchronized (buttonsDown) {
                buttonsDown.remove(btn);
            }
        }, 120);
    }

    /**
     * 物理鼠标滚轮滚动
     */
    public static void mouseScroll(String deviceName, float x, float y, float vScroll, float hScroll) {
        mouseScroll(deviceName, x, y, vScroll, hScroll, "");
    }

    public static void mouseScroll(String deviceName, float x, float y, float vScroll, float hScroll, String dest) {
        activeDevice = (deviceName != null && !deviceName.isEmpty()) ? deviceName : "外接鼠标";
        activeDeviceAt = SystemClock.uptimeMillis();
        wheelV = vScroll;
        wheelH = hScroll;
        wheelCount++;
        wheelAt = SystemClock.uptimeMillis();

        Rec r = new Rec();
        r.t = SystemClock.uptimeMillis();
        r.origin = "物理外设";
        r.kind = "scroll";
        r.action = "滚轮滚动";
        r.name = (vScroll < 0) ? "滚轮向下" : "滚轮向上";
        r.device = activeDevice;
        r.x = x;
        r.y = y;
        r.v = vScroll;
        r.h = hScroll;
        r.dest = dest;
        push(r);
    }

    // ======================= 大脑前面的唯一记录口 =======================

    /**
     * 系统层原始事件（大脑前面的唯一记录点）。
     *
     * 所有输入入口（PointerCapture 独占 / dispatchGenericMotionEvent / dispatchTouchEvent /
     * dispatchKeyEvent）在把事件交给外设大脑之前只调这一个方法：记的是「系统给了什么」，
     * 不是「我们想让它是什么」。落点由大脑在决策记录里补（mousePress/mouseClick/... 的 dest）。
     *
     * entry：捕获 / 通用 / 触摸 / 按键 —— 一眼看出事件从哪条入口进来。
     */
    public static void system(String entry, MotionEvent e) {
        motion("系统·" + entry, e);
    }

    /** 系统层按键，带大脑判定的落点。 */
    public static void systemKey(String entry, KeyEvent e, String dest) {
        key("系统·" + entry, e, dest);
    }

    /**
     * 这条事件没被拦住、直接落到手机上了。
     *
     * 这是唯一需要人工排查的一类：出现它说明「鼠标已经挂到别的屏，但事件还在手机」，
     * 面板上单列一行红字，不再混在正常记录里。
     */
    public static void leak(String entry, MotionEvent e, String reason) {
        miss(entry, e, "漏到手机", reason);
    }

    /** 没人接（虚拟外设认不出来 / 目标屏刚没了）：事件被丢掉，同样要看得见。 */
    public static void drop(String entry, MotionEvent e, String reason) {
        miss(entry, e, "已丢弃", reason);
    }

    /** 没有 MotionEvent 的丢弃（例如目标屏不可用，按下没投出去）。 */
    public static void note(String origin, String reason) {
        if (!enabled) return;
        Rec r = new Rec();
        r.t = SystemClock.uptimeMillis();
        r.origin = origin;
        r.kind = "leak";
        r.action = "已丢弃";
        r.name = reason == null ? "" : reason;
        push(r);
    }

    /** 中立信息（窗口落地在哪个屏、哪个 bounds）：不是异常，别用红字误导。 */
    public static void info(String origin, String text) {
        if (!enabled) return;
        Rec r = new Rec();
        r.t = SystemClock.uptimeMillis();
        r.origin = origin;
        r.kind = "note";
        r.action = "";
        r.name = text == null ? "" : text;
        push(r);
    }

    private static void miss(String entry, MotionEvent e, String action, String reason) {
        if (!enabled) return;
        Rec r = new Rec();
        r.t = SystemClock.uptimeMillis();
        r.origin = "系统·" + entry;
        r.kind = "leak";
        r.action = action;
        r.name = (reason == null || reason.isEmpty()) ? "未拦截" : reason;
        if (e != null) {
            r.source = e.getSource();
            r.deviceId = e.getDeviceId();
            try { r.device = deviceName(e.getDevice()); } catch (Throwable ignored) {}
            r.x = e.getX();
            r.y = e.getY();
            r.buttonState = e.getButtonState();
            r.actionButton = e.getActionButton();
            r.name = r.name + " · " + motionAction(e.getActionMasked());
        }
        push(r);
    }

    /** 按键没落地（注入通道不可用 / 目标屏未就绪）：同样单列一行，绝不静默丢。 */
    public static void leakKey(String entry, int keyCode, boolean down, int metaState) {
        if (!enabled) return;
        Rec r = new Rec();
        r.t = SystemClock.uptimeMillis();
        r.origin = "系统·" + entry;
        r.kind = "leak";
        r.action = "已丢弃";
        r.name = keyName(keyCode) + (down ? " 按下" : " 抬起");
        r.keyCode = keyCode;
        r.metaState = metaState;
        r.down = down;
        push(r);
    }

    // ======================= 记录入口 =======================

    /** 键盘 / 手柄按键（KeyEvent 完整信息）。调用方无需判断开关，这里首行就返回。 */
    public static void key(String origin, KeyEvent e) {
        key(origin, e, "");
    }

    public static void key(String origin, KeyEvent e, String dest) {
        if (!enabled || e == null) return;
        int action = e.getAction();
        int code = e.getKeyCode();
        boolean down = action == KeyEvent.ACTION_DOWN;

        synchronized (keysDown) {
            if (down) keysDown.add(code);
            else keysDown.remove(code);
        }

        Rec r = new Rec();
        r.t = SystemClock.uptimeMillis();
        r.origin = origin;
        r.kind = isPadKey(code) ? "pad" : "key";
        r.action = down ? "按下" : (action == KeyEvent.ACTION_MULTIPLE ? "重复" : "抬起");
        r.keyCode = code;
        r.name = keyName(code);
        r.metaState = e.getMetaState();
        r.deviceId = e.getDeviceId();
        r.source = e.getSource();
        r.device = deviceName(e.getDevice());
        r.dest = dest;
        r.down = down;
        markDevice(r.device);
        push(r);
    }

    /** 控制器注入侧的按键（只有 keyCode，没有 KeyEvent 对象）。 */
    public static void key(String origin, int keyCode, boolean down, int metaState) {
        if (!enabled) return;
        synchronized (keysDown) {
            if (down) keysDown.add(keyCode);
            else keysDown.remove(keyCode);
        }
        Rec r = new Rec();
        r.t = SystemClock.uptimeMillis();
        r.origin = origin;
        r.kind = isPadKey(keyCode) ? "pad" : "key";
        r.action = down ? "按下" : "抬起";
        r.keyCode = keyCode;
        r.name = keyName(keyCode);
        r.metaState = metaState;
        r.source = InputDevice.SOURCE_KEYBOARD;
        r.device = "控制器注入";
        r.down = down;
        markDevice(r.device);
        push(r);
    }

    private static int dupDev = Integer.MIN_VALUE;
    private static int dupSem = 0;
    private static float dupX = 0f, dupY = 0f;
    private static long dupAt = 0L;

    /** DOWN/BUTTON_PRESS 归成同一个语义，UP/BUTTON_RELEASE 同理。 */
    private static int semOf(int action) {
        switch (action) {
            case MotionEvent.ACTION_DOWN:
            case MotionEvent.ACTION_BUTTON_PRESS:
                return 1;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_BUTTON_RELEASE:
                return 2;
            default:
                return action + 1000;
        }
    }

    /** 同一帧被多个入口重复交上来：同设备、同语义动作、同坐标、12ms 内。 */
    private static boolean sameFrameCopy(MotionEvent e, int action) {
        int sem = semOf(action);
        int dev = e.getDeviceId();
        long now = SystemClock.uptimeMillis();
        boolean dup = (sem == dupSem && dev == dupDev
                && Math.abs(e.getX() - dupX) < 1.5f && Math.abs(e.getY() - dupY) < 1.5f
                && now - dupAt < 12L);
        dupSem = sem;
        dupDev = dev;
        dupX = e.getX();
        dupY = e.getY();
        dupAt = now;
        return dup;
    }

    /**
     * 鼠标 / 触摸 / 滚轮 / 摇杆（MotionEvent）。高频移动直接丢弃，只保留「按了什么」。
     */
    public static void motion(String origin, MotionEvent e) {
        if (!enabled || e == null) return;
        /* 大脑自己造的那台 "Aosen Virtual Mouse" 会把我们刚写进去的事件再送回系统，
         * 再由系统派回我们的窗口 —— 那是回声，不是「谁在按」。以前这里无条件
         * markDevice()，于是点一下键面板顶部的设备名就从真鼠标跳成 Aosen Virtual Mouse，
         * 看着像“虚拟设备点了键”。回声单独标出来，且不许抢 activeDevice。 */
        final boolean echo = VirtualMouse.isOwnDevice(deviceName(e.getDevice()));
        if (!echo) markDevice(deviceName(e.getDevice()));

        int action = e.getActionMasked();
        boolean joystick = e.isFromSource(InputDevice.SOURCE_JOYSTICK)
                || e.isFromSource(InputDevice.SOURCE_GAMEPAD);

        // HOVER_ENTER / HOVER_EXIT 是 View 层因指针进出而派生的，不是设备发出来的原始帧。
        // 一次点击刷出这两条，纯属噪音。
        if (action == MotionEvent.ACTION_HOVER_ENTER || action == MotionEvent.ACTION_HOVER_EXIT) return;
        /* 同一次点击会被三条入口各收一份：PointerCapture、dispatchGenericMotionEvent、
         * dispatchTouchEvent。它们在系统看来只是同一帧的副本（坐标都一样），
         * 各记一行就会变成「一次点击七八条」。按「语义动作 + 坐标 + 12ms」归一，
         * 面板上只留最先到的那一条 —— 这是记录去重，不影响任何投递。 */
        if (sameFrameCopy(e, action)) return;

        // 摇杆轴：每次采样都刷新实时值（不写日志），面板才能看到摇杆推了多少。
        if (joystick) updateAxes(e);

        // 纯移动：手柄摇杆无按键时不记录（否则一秒钟几十条），鼠标移动完全不记录。
        if (action == MotionEvent.ACTION_MOVE || action == MotionEvent.ACTION_HOVER_MOVE) {
            if (!joystick) return;
            float hatX, hatY, ax, ay;
            synchronized (axes) {
                ax = axes[0];
                ay = axes[1];
                hatX = axes[4];
                hatY = axes[5];
            }
            boolean pushed = Math.abs(hatX) > 0.5f || Math.abs(hatY) > 0.5f
                    || Math.abs(ax) > 0.5f || Math.abs(ay) > 0.5f;
            if (!pushed) return;
        }

        int buttonState = e.getButtonState();
        int actionButton = e.getActionButton();
        float vScroll = e.getAxisValue(MotionEvent.AXIS_VSCROLL);
        float hScroll = e.getAxisValue(MotionEvent.AXIS_HSCROLL);

        // 滚轮单独留一份"最近一次 + 累计次数"，面板不用翻日志就能看到刚滚了什么。
        if (action == MotionEvent.ACTION_SCROLL) {
            wheelV = vScroll;
            wheelH = hScroll;
            wheelCount++;
            wheelAt = SystemClock.uptimeMillis();
        }

        // 动作结束后同步按下集合，供面板实时高亮。
        if (e.isFromSource(InputDevice.SOURCE_MOUSE) || buttonState != 0 || actionButton != 0) {
            synchronized (buttonsDown) {
                if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
                    buttonsDown.clear();
                } else {
                    buttonsDown.clear();
                    for (int bit : BUTTON_BITS) {
                        if ((buttonState & bit) != 0) buttonsDown.add(bit);
                    }
                    if (actionButton != 0 && (action == MotionEvent.ACTION_BUTTON_PRESS
                            || action == MotionEvent.ACTION_BUTTON_RELEASE)) {
                        if (action == MotionEvent.ACTION_BUTTON_PRESS) buttonsDown.add(actionButton);
                        else buttonsDown.remove(actionButton);
                    }
                }
            }
        }

        Rec r = new Rec();
        r.t = SystemClock.uptimeMillis();
        r.origin = echo ? (origin + "·虚拟回声") : origin;
        r.kind = joystick ? "axis" : (action == MotionEvent.ACTION_SCROLL ? "scroll" : "mouse");
        r.action = motionAction(action);
        r.buttonState = buttonState;
        r.actionButton = actionButton;
        r.source = e.getSource();
        r.deviceId = e.getDeviceId();
        r.device = deviceName(e.getDevice());
        r.x = e.getX();
        r.y = e.getY();
        r.v = vScroll;
        r.h = hScroll;
        r.name = describeMotion(r, e);
        r.down = action == MotionEvent.ACTION_DOWN || action == MotionEvent.ACTION_BUTTON_PRESS;
        if (!echo) markDevice(r.device);
        push(r);
    }

    // ======================= 快照（供 JS 轮询） =======================

    public static String snapshotJson() {
        JSONObject o = new JSONObject();
        try {
            o.put("enabled", enabled);
            // uptime 时间戳换算成墙上时钟用，网页侧用 Date.now()-now 得到偏移。
            o.put("now", SystemClock.uptimeMillis());

            JSONArray keys = new JSONArray();
            synchronized (keysDown) {
                for (Integer c : keysDown) {
                    JSONObject k = new JSONObject();
                    k.put("code", (int) c);
                    k.put("name", keyName(c));
                    k.put("pad", isPadKey(c));
                    keys.put(k);
                }
            }
            o.put("keys", keys);

            JSONArray btns = new JSONArray();
            synchronized (buttonsDown) {
                for (Integer b : buttonsDown) {
                    JSONObject k = new JSONObject();
                    k.put("bit", (int) b);
                    k.put("name", buttonName(b));
                    btns.put(k);
                }
            }
            o.put("buttons", btns);

            JSONArray arr = new JSONArray();
            synchronized (log) {
                for (Rec r : log) arr.put(r.toJson());
            }
            o.put("log", arr);

            synchronized (log) {
                o.put("last", lastRec == null ? JSONObject.NULL : lastRec.toJson());
            }

            // 顶部「当前设备」：谁最后产生了事件，面板直接照着它显示按键图。
            o.put("activeDevice", activeDevice);
            o.put("activeDeviceAt", activeDeviceAt);

            JSONObject ax = new JSONObject();
            synchronized (axes) {
                ax.put("x", round3(axes[0]));
                ax.put("y", round3(axes[1]));
                ax.put("rx", round3(axes[2]));
                ax.put("ry", round3(axes[3]));
                ax.put("hatX", round3(axes[4]));
                ax.put("hatY", round3(axes[5]));
            }
            o.put("axes", ax);

            JSONObject wh = new JSONObject();
            wh.put("v", round3(wheelV));
            wh.put("h", round3(wheelH));
            wh.put("n", wheelCount);
            wh.put("t", wheelAt);
            o.put("wheel", wh);
        } catch (Throwable ignored) {
        }
        return o.toString();
    }

    private static double round3(float v) {
        return Math.round(v * 1000) / 1000.0;
    }

    /** 记录当前活跃设备，忽略"未知设备"这种无意义值。 */
    private static void markDevice(String name) {
        if (name == null || name.isEmpty() || "未知设备".equals(name)) return;
        activeDevice = name;
        activeDeviceAt = SystemClock.uptimeMillis();
    }

    /** 采样摇杆 / 十字键轴值，只更新实时状态，不写日志。 */
    private static void updateAxes(MotionEvent e) {
        synchronized (axes) {
            axes[0] = e.getAxisValue(MotionEvent.AXIS_X);
            axes[1] = e.getAxisValue(MotionEvent.AXIS_Y);
            axes[2] = e.getAxisValue(MotionEvent.AXIS_RX);
            axes[3] = e.getAxisValue(MotionEvent.AXIS_RY);
            axes[4] = e.getAxisValue(MotionEvent.AXIS_HAT_X);
            axes[5] = e.getAxisValue(MotionEvent.AXIS_HAT_Y);
        }
    }

    // ======================= 内部实现 =======================

    private static void push(Rec r) {
        synchronized (log) {
            lastRec = r;
            log.addFirst(r);
            while (log.size() > MAX_LOG) log.removeLast();
        }
    }

    private static final int[] BUTTON_BITS = {1, 2, 4, 8, 16};

    private static boolean isPadKey(int code) {
        return code >= KeyEvent.KEYCODE_BUTTON_A && code <= KeyEvent.KEYCODE_BUTTON_16;
    }

    private static String keyName(int code) {
        try {
            String s = KeyEvent.keyCodeToString(code);
            if (s != null && s.length() > 0) return s;
        } catch (Throwable ignored) {
        }
        return "KEYCODE_" + code;
    }

    private static String buttonName(int bit) {
        switch (bit) {
            case 1: return "左键";
            case 2: return "右键";
            case 4: return "中键";
            case 8: return "后退键";
            case 16: return "前进键";
            default: return "按钮" + bit;
        }
    }

    private static String buttonsText(int state) {
        if (state == 0) return "无";
        StringBuilder sb = new StringBuilder();
        for (int bit : BUTTON_BITS) {
            if ((state & bit) != 0) {
                if (sb.length() > 0) sb.append(" + ");
                sb.append(buttonName(bit));
            }
        }
        return sb.length() == 0 ? ("0x" + Integer.toHexString(state)) : sb.toString();
    }

    private static String motionAction(int action) {
        switch (action) {
            case MotionEvent.ACTION_DOWN: return "DOWN";
            case MotionEvent.ACTION_UP: return "UP";
            case MotionEvent.ACTION_MOVE: return "MOVE";
            case MotionEvent.ACTION_CANCEL: return "CANCEL";
            case MotionEvent.ACTION_HOVER_MOVE: return "HOVER_MOVE";
            case MotionEvent.ACTION_HOVER_ENTER: return "HOVER_ENTER";
            case MotionEvent.ACTION_HOVER_EXIT: return "HOVER_EXIT";
            case MotionEvent.ACTION_SCROLL: return "SCROLL";
            case MotionEvent.ACTION_BUTTON_PRESS: return "BUTTON_PRESS";
            case MotionEvent.ACTION_BUTTON_RELEASE: return "BUTTON_RELEASE";
            case MotionEvent.ACTION_OUTSIDE: return "OUTSIDE";
            case MotionEvent.ACTION_POINTER_DOWN: return "POINTER_DOWN";
            case MotionEvent.ACTION_POINTER_UP: return "POINTER_UP";
        }
        try {
            return MotionEvent.actionToString(action);
        } catch (Throwable t) {
            return "ACTION_" + action;
        }
    }

    /** 面板上要显示的一行摘要，把最关键的信息压在前面。 */
    private static String describeMotion(Rec r, MotionEvent e) {
        int action = e.getActionMasked();
        if (action == MotionEvent.ACTION_SCROLL) {
            return String.format(java.util.Locale.US, "滚轮 v=%.3f h=%.3f", r.v, r.h);
        }
        if (action == MotionEvent.ACTION_BUTTON_PRESS || action == MotionEvent.ACTION_BUTTON_RELEASE) {
            // actionButton 为 0 说明这一帧根本没标是哪个键（注入链或系统翻译丢的），
            // 直说「按键未标」，不要显示成不存在的「按钮0」。
            if (r.actionButton != 0) return buttonName(r.actionButton);
            String bs = buttonsText(r.buttonState);
            return "无".equals(bs) ? "按键未标" : bs;
        }
        if (e.isFromSource(InputDevice.SOURCE_JOYSTICK)
                || e.isFromSource(InputDevice.SOURCE_GAMEPAD)) {
            return String.format(java.util.Locale.US, "摇杆 X=%.2f Y=%.2f HAT=%.1f/%.1f",
                    e.getAxisValue(MotionEvent.AXIS_X), e.getAxisValue(MotionEvent.AXIS_Y),
                    e.getAxisValue(MotionEvent.AXIS_HAT_X), e.getAxisValue(MotionEvent.AXIS_HAT_Y));
        }
        return buttonsText(r.buttonState);
    }

    private static String deviceName(InputDevice dev) {
        if (dev == null) return "未知设备";
        try {
            String n = dev.getName();
            return n == null ? "未知设备" : n;
        } catch (Throwable t) {
            return "未知设备";
        }
    }

    private static String sourceText(int source) {
        if ((source & InputDevice.SOURCE_MOUSE) == InputDevice.SOURCE_MOUSE) return "鼠标";
        if ((source & InputDevice.SOURCE_TOUCHSCREEN) == InputDevice.SOURCE_TOUCHSCREEN) return "触摸屏";
        if ((source & InputDevice.SOURCE_JOYSTICK) == InputDevice.SOURCE_JOYSTICK) return "摇杆";
        if ((source & InputDevice.SOURCE_GAMEPAD) == InputDevice.SOURCE_GAMEPAD) return "手柄";
        if ((source & InputDevice.SOURCE_KEYBOARD) == InputDevice.SOURCE_KEYBOARD) return "键盘";
        return "0x" + Integer.toHexString(source);
    }

    private static final class Rec {
        long t;
        String origin = "";
        String kind = "";
        String action = "";
        String name = "";
        String device = "";
        /** 大脑判定这块虚拟外设此刻插在哪块屏；系统层记录为空。 */
        String dest = "";
        int keyCode = -1;
        int buttonState = 0;
        int actionButton = 0;
        int source = 0;
        int deviceId = 0;
        int metaState = 0;
        float x, y, v, h;
        boolean down;

        JSONObject toJson() {
            JSONObject o = new JSONObject();
            try {
                o.put("t", t);
                o.put("o", origin);
                o.put("k", kind);
                o.put("a", action);
                o.put("n", name);
                o.put("dev", device);
                if (!dest.isEmpty()) o.put("to", dest);
                o.put("devId", deviceId);
                o.put("src", sourceText(source));
                o.put("srcRaw", source);
                if (keyCode >= 0) o.put("code", keyCode);
                if (kind.equals("mouse") || kind.equals("scroll") || kind.equals("axis")) {
                    o.put("bs", buttonState);
                    o.put("bsText", buttonsText(buttonState));
                    o.put("ab", actionButton);
                    o.put("abText", actionButton == 0 ? "无" : buttonName(actionButton));
                    o.put("x", Math.round(x * 10) / 10.0);
                    o.put("y", Math.round(y * 10) / 10.0);
                    o.put("v", Math.round(v * 1000) / 1000.0);
                    o.put("h", Math.round(h * 1000) / 1000.0);
                }
                if (metaState != 0) o.put("meta", metaState);
                o.put("d", down);
            } catch (Throwable ignored) {
            }
            return o;
        }
    }
}
