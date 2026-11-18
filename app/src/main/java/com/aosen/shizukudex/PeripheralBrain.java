package com.aosen.shizukudex;

import android.content.Context;
import android.hardware.input.InputManager;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;
import android.view.Display;
import android.view.InputDevice;
import android.view.KeyEvent;
import android.view.MotionEvent;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 沧海外设大脑 (Canghai Peripheral Brain)
 *
 * 核心架构原则：
 * 1. 真实硬件 100% 隔离在手机中枢，绝不盲目让渡底层控制权；
 * 2. 统一虚拟外设插槽 (Virtual Device Slot)：每块卡片代表一个虚拟设备；
 * 3. 动态热插拔路由：卡片选择哪个屏幕，虚拟设备就挂载到哪个屏幕；
 * 4. 无论是免 Root 直显还是有 Root 特权，均走统一的虚拟输入总线与 Z 轴图层派发；
 * 5. 单一信源 (Single Source of Truth)，彻底解决多处重复注入与日志重复刷屏。
 */
public final class PeripheralBrain {

    private static final String TAG = "PeripheralBrain";
    private static final PeripheralBrain INSTANCE = new PeripheralBrain();

    public static PeripheralBrain getInstance() {
        return INSTANCE;
    }

    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private Context appContext;

    // 虚拟外设插槽注册表 (Device Descriptor / Slot ID -> Slot)
        // 外设槽位注册表 (Canonical Slot ID -> Slot)
    private final Map<String, VirtualSlot> slots = new ConcurrentHashMap<>();
    // 物理 Descriptor 到标准槽位 ID 的映射（合并同一 USB 键盘/外设的多个 HID 子接口）
    private final Map<String, String> descriptorToCanonicalId = new ConcurrentHashMap<>();
    /** 由 InputManager 枚举出来的真实硬件插槽；只有它们会随拔插被注销。 */
    private final java.util.Set<String> hardwareSlotIds = ConcurrentHashMap.newKeySet();

    private String resolveCanonicalSlotId(InputDevice dev, String type) {
        if (dev == null) return "unknown";
        String desc = dev.getDescriptor();
        int vid = dev.getVendorId();
        int pid = dev.getProductId();
        String name = (dev.getName() != null) ? dev.getName().trim() : "";
        String canonical;
        if (vid != 0 && pid != 0) {
            canonical = "hw_" + vid + "_" + pid + "_" + type;
        } else if (!name.isEmpty()) {
            canonical = "hw_" + name + "_" + type;
        } else if (desc != null && !desc.isEmpty()) {
            canonical = desc;
        } else {
            canonical = "dev_" + dev.getId();
        }
        if (desc != null && !desc.isEmpty()) {
            descriptorToCanonicalId.put(desc, canonical);
        }
        return canonical;
    }

    // 宿主引用
    private MainActivity mainActivity;
    private DeXPresentation dexPresentation;
    /** 零权限 Moonlight 本地虚拟屏上的沧海桌面，与有线外屏桌面相互独立。 */
    private DeXPresentation moonlightPresentation;

    private boolean initialized = false;

    private PeripheralBrain() {}

    /**
     * 虚拟设备插槽
     */
    public static class VirtualSlot {
        public final String id;
        public String name;
        public String type; // "touchpad", "mouse", "gamepad", "keyboard", "moon_input"
        public int targetDisplayId;
        public float cursorX;
        public float cursorY;
        public long lastActiveTime;
        /** 该虚拟外设当前落地的宿主；换屏时指针归位到新屏中央。 */
        public Host lastHost;
        /** 该虚拟外设当前按住的鼠标键；DOWN 与 BUTTON_PRESS 双报时只认一次。 */
        public int pressedButton = 0;

        /**
         * 投递端：挂载时绑一次，之后移动 / 按键 / 滚轮一律交给它。
         * 这一层存在的意义就是「虚拟外设自己会投」——大脑不再逐个动作判断目标屏。
         */
        Surface surface;

        /** 当前投递端对应的落点类别；变了才重绑，不参与任何投递判断。 */
        Host mountHost;

        public VirtualSlot(String id, String name, String type, int targetDisplayId) {
            this.id = id;
            this.name = name;
            this.type = type;
            this.targetDisplayId = targetDisplayId;
            this.lastActiveTime = SystemClock.uptimeMillis();
            // -1 = 还没在目标屏上定位过。不能拿 0 当哨兵：0 是合法的左上角。
            this.cursorX = -1f;
            this.cursorY = -1f;
        }

        public JSONObject toJson() {
            JSONObject o = new JSONObject();
            try {
                o.put("id", id);
                o.put("name", name);
                o.put("type", type);
                o.put("targetDisplayId", targetDisplayId);
                o.put("lastActive", lastActiveTime);
            } catch (Throwable ignored) {}
            return o;
        }
    }

    /**
     * 虚拟外设的投递端。
     *
     * 一个虚拟外设插在哪块屏，就只绑那一种承载方式；之后所有动作问它即可，
     * 大脑不再写「这是手机还是外屏」。三块屏三种承载，所以只有三个实现：
     *   PhoneSurface  —— 手机主屏（WebView，本地合成事件）
     *   WiredSurface  —— 物理外接屏（Presentation 承载，内部按权限决定本地绘制或注入）
     *   StreamSurface —— Moonlight 串流屏（本地屏走 Presentation，系统屏走串流注入）
     *
     * 三条约束写死在结构里，防止再长出第二套路由：
     *   1. 不持有光标位置，位置只在大脑 slot 那一份；
     *   2. 不判断目标屏，绑哪个就是哪个；
     *   3. 换绑前必须 cancelAll(x, y)，屏幕上不能留悬空的按下。
     */
    public interface Surface {
        /** 相对位移落地，返回落地后的绝对位置；null 表示这一帧没接住。 */
        float[] moveBy(float dx, float dy, float curX, float curY, float sensitivity);

        /** 绝对定位（不产生输入事件，只对齐光标），返回对齐后的位置。 */
        float[] moveTo(float x, float y);

        /** 按键只按语义转交，怎么实现由各屏自己决定。返回 false 表示没接住。 */
        boolean button(int btn, boolean down, float x, float y);

        /** 按下状态下的移动（拖拽）。 */
        void dragTo(float x, float y);

        boolean scroll(float v, float h, float x, float y);

        /** 取消一切悬空按下，换屏 / 失焦时调用。 */
        void cancelAll(float x, float y);

        /** 本屏光标该出现的默认位置（首次挂载 / 换屏时对齐一次），返回对齐后的位置。 */
        float[] center();
    }

    private final Surface phoneSurface = new Surface() {
        @Override
        public float[] moveBy(float dx, float dy, float curX, float curY, float sensitivity) {
            MainActivity a = mainActivity;
            if (a == null) return null;
            a.applyPhoneCursorDelta(dx, dy);
            return new float[]{a.getPhoneCursorX(), a.getPhoneCursorY()};
        }

        @Override
        public float[] moveTo(float x, float y) {
            MainActivity a = mainActivity;
            if (a == null) return null;
            a.movePhoneCursorTo(x, y);
            return new float[]{a.getPhoneCursorX(), a.getPhoneCursorY()};
        }

        @Override
        public boolean button(int btn, boolean down, float x, float y) {
            MainActivity a = mainActivity;
            if (a == null) return false;
            if (btn == MotionEvent.BUTTON_PRIMARY) {
                if (down) a.phoneMouseDown(x, y);
                else a.phoneMouseUp(x, y);
                return true;
            }
            // 手机首页只有一个「点击」语义：右键就是长按菜单，中键没有对应物。
            // 抬起不再做动作，但要算处理过，否则每次右键都会多记一条「丢弃」。
            if (btn == MotionEvent.BUTTON_SECONDARY) {
                if (down) a.triggerPhoneClickAtCursor(true);
                return true;
            }
            return true;
        }

        @Override
        public void dragTo(float x, float y) {
            MainActivity a = mainActivity;
            if (a != null) a.phoneMouseMove(x, y);
        }

        @Override
        public boolean scroll(float v, float h, float x, float y) {
            MainActivity a = mainActivity;
            if (a == null) return false;
            // 手机页面是自定义滚动容器，window.scrollBy 无效；必须作为真实
            // ACTION_SCROLL 交给光标下的元素，由 Chromium 自己滚动。
            a.phoneMouseScroll(x, y, v, h);
            return true;
        }

        @Override
        public void cancelAll(float x, float y) {
            MainActivity a = mainActivity;
            if (a != null) a.cancelPhoneMousePress();
        }

        @Override
        public float[] center() {
            MainActivity a = mainActivity;
            if (a == null) return null;
            android.util.DisplayMetrics m = a.getResources().getDisplayMetrics();
            return moveTo(m.widthPixels / 2f, m.heightPixels / 2f);
        }
    };

    private final Surface wiredSurface = new Surface() {
        @Override
        public float[] moveBy(float dx, float dy, float curX, float curY, float sensitivity) {
            DeXPresentation d = wiredOrNull();
            if (d == null) return null;
            d.applyPointerDelta(dx, dy, sensitivity);
            return new float[]{d.getLastMouseX(), d.getLastMouseY()};
        }

        @Override
        public float[] moveTo(float x, float y) {
            DeXPresentation d = wiredOrNull();
            if (d == null) return null;
            d.updateCursor(x, y);
            return new float[]{d.getLastMouseX(), d.getLastMouseY()};
        }

        @Override
        public boolean button(int btn, boolean down, float x, float y) {
            DeXPresentation d = wiredOrNull();
            if (d == null) return false;
            if (btn == MotionEvent.BUTTON_PRIMARY) {
                if (down) d.onMousePrimaryDown(x, y);
                else d.onMousePrimaryUp(x, y);
            } else if (down) {
                if (btn == MotionEvent.BUTTON_SECONDARY) d.performRightClickAt(x, y);
                else if (btn == MotionEvent.BUTTON_TERTIARY) d.performMiddleClickAt(x, y);
                else return false;
            }
            return true;
        }

        @Override
        public void dragTo(float x, float y) {
            DeXPresentation d = wiredOrNull();
            if (d != null) d.onMousePrimaryMove(x, y);
        }

        @Override
        public boolean scroll(float v, float h, float x, float y) {
            DeXPresentation d = wiredOrNull();
            if (d == null) return false;
            d.performScrollAt(x, y, v, h);
            return true;
        }

        @Override
        public void cancelAll(float x, float y) {
            DeXPresentation d = wiredOrNull();
            if (d != null) d.onMousePrimaryUp(x, y);
        }

        @Override
        public float[] center() {
            DeXPresentation d = wiredOrNull();
            if (d == null) return null;
            return moveTo(d.getLastMouseX(), d.getLastMouseY());
        }
    };

    private final Surface streamSurface = new Surface() {
        @Override
        public float[] moveBy(float dx, float dy, float curX, float curY, float sensitivity) {
            DeXPresentation d = moonlightOrNull();
            if (d != null) {
                d.applyPointerDelta(dx, dy, sensitivity);
                return new float[]{d.getLastMouseX(), d.getLastMouseY()};
            }
            MainActivity a = mainActivity;
            if (a == null) return null;
            // 没有本地画布（Root 系统串流屏）：位置只能由大脑这一份持有，
            // 客户端只负责把这一帧送出去。两边各存一份就会「移动看着对，点击落旧位置」。
            int[] sz = a.streamScreenSize();
            float speed = com.alkaidlab.sunshine.service.MouseCursorPrefs.getSpeed();
            float nx = Math.max(0f, Math.min(sz[0] - 1f, curX + dx * speed));
            float ny = Math.max(0f, Math.min(sz[1] - 1f, curY + dy * speed));
            a.deliverStreamPointerAt(nx, ny);
            return new float[]{nx, ny};
        }

        @Override
        public float[] moveTo(float x, float y) {
            DeXPresentation d = moonlightOrNull();
            if (d != null) {
                d.updateCursor(x, y);
                return new float[]{d.getLastMouseX(), d.getLastMouseY()};
            }
            int[] sz = (mainActivity != null) ? mainActivity.streamScreenSize() : new int[]{1920, 1080};
            return new float[]{Math.max(0f, Math.min(sz[0] - 1f, x)),
                    Math.max(0f, Math.min(sz[1] - 1f, y))};
        }

        @Override
        public boolean button(int btn, boolean down, float x, float y) {
            DeXPresentation d = moonlightOrNull();
            if (d != null) {
                if (btn == MotionEvent.BUTTON_PRIMARY) {
                    if (down) d.onMousePrimaryDown(x, y);
                    else d.onMousePrimaryUp(x, y);
                } else if (down) {
                    if (btn == MotionEvent.BUTTON_SECONDARY) d.performRightClickAt(x, y);
                    else if (btn == MotionEvent.BUTTON_TERTIARY) d.performMiddleClickAt(x, y);
                    else return false;
                }
                return true;
            }
            MainActivity a = mainActivity;
            if (a == null) return false;
            a.deliverStreamButton(x, y, btn, down);
            return true;
        }

        @Override
        public void dragTo(float x, float y) {
            DeXPresentation d = moonlightOrNull();
            if (d != null) d.onMousePrimaryMove(x, y);
        }

        @Override
        public boolean scroll(float v, float h, float x, float y) {
            DeXPresentation d = moonlightOrNull();
            if (d != null) {
                d.performScrollAt(x, y, v, h);
                return true;
            }
            MainActivity a = mainActivity;
            if (a == null) return false;
            a.deliverStreamScroll(x, y, v, h);
            return true;
        }

        @Override
        public void cancelAll(float x, float y) {
            DeXPresentation d = moonlightOrNull();
            if (d != null) d.onMousePrimaryUp(x, y);
        }

        @Override
        public float[] center() {
            DeXPresentation d = moonlightOrNull();
            if (d != null) return moveTo(d.getLastMouseX(), d.getLastMouseY());
            int[] sz = (mainActivity != null) ? mainActivity.streamScreenSize() : new int[]{1920, 1080};
            return new float[]{sz[0] / 2f, sz[1] / 2f};
        }
    };

    private Surface surfaceFor(Host host) {
        if (host == Host.STREAM) return streamSurface;
        if (host == Host.WIRED) return wiredSurface;
        return phoneSurface;
    }

    /**
     * 挂载点变了才重绑。重绑前先把上一个端上的悬空按下收干净，
     * 否则换屏那一刻屏幕上会留下一个「按着不放」的手势。
     */
    private synchronized void syncMount(VirtualSlot slot) {
        Host host = liveHostOf(slot);
        if (slot.surface != null && slot.mountHost == host) return;
        if (slot.surface != null) slot.surface.cancelAll(slot.cursorX, slot.cursorY);
        slot.mountHost = host;
        slot.surface = surfaceFor(host);
        // 从没定位过就给新屏中心；换过屏则保持原位，由新端自己夹到边界内。
        float[] p = (slot.cursorX < 0 || slot.cursorY < 0)
                ? slot.surface.center()
                : slot.surface.moveTo(slot.cursorX, slot.cursorY);
        if (p != null) {
            slot.cursorX = p[0];
            slot.cursorY = p[1];
        }
    }

    public synchronized Collection<VirtualSlot> getAllSlots() {
        return new ArrayList<>(slots.values());
    }

    /**
     * 一块屏没了（外接屏拔掉 / 串流结束），挂在它上面的虚拟外设绝不能变成无主：
     * 先找还在的外接屏，再找串流屏，最后落回手机。卡片保留、挂载点改写、状态持久化。
     */
    public synchronized void onDisplayDisconnected(int disconnectedDisplayId) {
        final int goneKey = ScreenRegistry.get().normalize(disconnectedDisplayId);
        final int fallback = liveFallbackTarget(goneKey);
        boolean changed = false;
        for (VirtualSlot slot : slots.values()) {
            if (ScreenRegistry.get().normalize(slot.targetDisplayId) == goneKey) {
                slot.targetDisplayId = fallback;
                if (appContext != null) {
                    CanghaiStore.setPeripheralDisplay(appContext, slot.id, fallback);
                }
                changed = true;
            }
        }
        if (changed) {
            Log.i(TAG, "屏幕 " + goneKey + " 已移除，外设挂载点回退到 " + fallback);
            CanghaiErrorCollector.logExt("屏幕移除，外设已回退到 " + targetName(fallback));
            notifyPeripheralUi();
        }
    }

    /** 还能落地的屏：现存物理屏 > 串流屏 > 手机，绝不选刚拔掉的那块。 */
    private int liveFallbackTarget(int excluded) {
        return ScreenRegistry.get().fallbackFor(excluded);
    }

    private String targetName(int target) {
        return ScreenRegistry.get().nameOf(target);
    }

    /**
     * 诊断面板上的「落点」：大脑这一帧到底投给了哪块屏。
     * 只读 ScreenRegistry，不自己猜；屏刚没时会显示回退后的真实落点。
     */
    /**
     * 一次性诊断：左键按下那一刻，系统眼里「焦点窗口」是谁。
     *
     * <p>点击落到哪，取决于 WindowManager 的焦点/命中，而不是我们的 cursorX/Y。
     * 面板上出现「窗口壳 ... bounds=[...]」是应用窗口自己的落点，
     * 这里给的是系统正在把输入派给谁 —— 两者对不上就说明点打偏了。
     * 3s 节流、后台线程、不占注入通道；解析在 Java 侧做，绝不用 shell 管道
     * （管道会让 root 常驻 shell 的结束标记丢失，整条命令链卡死）。
     */
    private void probeFocusAsync() {
        long now = SystemClock.uptimeMillis();
        if (now - lastFocusProbeAt < 3000L) return;
        lastFocusProbeAt = now;
        new Thread(() -> {
            try {
                if (!RootCmd.isAvailable()) return;
                String out = RootCmd.execute("dumpsys window displays");
                if (out == null || out.isEmpty()) return;
                StringBuilder sb = new StringBuilder();
                String curDisplay = "";
                int lines = 0;
                for (String raw : out.split("\n")) {
                    String line = raw.trim();
                    int di = line.indexOf("Display: mDisplayId=");
                    if (di >= 0) {
                        int eq = line.indexOf('=', di);
                        String rest = (eq > 0) ? line.substring(eq + 1).trim() : "";
                        curDisplay = rest.isEmpty() ? "?" : rest.split("[ ,]")[0];
                        continue;
                    }
                    if (line.startsWith("mCurrentFocus=")) {
                        String w = line.substring("mCurrentFocus=".length()).trim()
                                .replace("Window{", "").replace("}", "");
                        int slash = w.indexOf('/');
                        if (slash > 0) {
                            int sp = w.lastIndexOf(' ', slash);
                            if (sp > 0) w = w.substring(sp + 1);
                        }
                        if (w.isEmpty() || "null".equals(w)) w = "无";
                        if (sb.length() > 0) sb.append(" | ");
                        sb.append("屏").append(curDisplay).append(" → ").append(w);
                        if (++lines >= 4) break;
                    }
                }
                if (sb.length() > 0) InputTrace.info("系统焦点", sb.toString());
            } catch (Throwable ignored) {
            }
        }, "aosen-focus-probe").start();
    }

    private long lastFocusProbeAt = 0L;

    // ---------------- 真设备鼠标 → 触摸（外设大脑翻译层） ----------------

    /** 一次触摸手势是否已经按下；用来把「按着左键移动」补成 ACTION_MOVE。 */
    private volatile boolean fingerDown = false;
    private volatile long fingerDownTime = 0L;
    /** 当前左键手势整段交给谁：true=直派 Dock，false=注入外部应用（按下那一刻定）。 */
    private volatile boolean fingerOnDock = false;
    /** 上一帧指针是否在 Dock 悬停区，用来发 HOVER_ENTER/EXIT 配对。 */
    private volatile boolean lastHoverOnDock = false;
    /** 这次右键是否已被 Dock 直派吃掉（按下时决定），抬起必须跟着它走，否则系统会收到孤立的 BTN_RIGHT up。 */
    private volatile boolean rightOnDock = false;

    /**
     * 只认真设备当前绑到的串流屏，拿不到就返回 -1。
     * 绝不退回 0（手机主屏）——那会把点击打到手机界面上。
     */
    private int fingerDisplayId() {
        /* 真实外设已被用户拖回手机：大脑层摘掉了屏关联，输入由系统派到默认屏。
         * 这里必须返回 -1，否则左/右键还会继续往虚拟画布注入，外接屏照样能控制。 */
        if (RealPeripheralBinding.get().isPhoneBound()) return -1;
        int id = VirtualMouse.displayId();
        if (id <= 0) id = StreamTarget.get().getDisplayId();
        return id;
    }

    /**
     * 外接触摸屏绑屏：把数字化仪的 descriptor 关联到目标显示。
     * 用户在设置里给这台设备指定过目标屏（routeDisplayFor）一律尊重；指定回
     * 手机则摘掉关联。后台线程跑，binder 往返不占调用线程。
     */
    private static void bindTouchscreenToDisplay(final Context ctx, final InputDevice dev, final int targetDisplayId) {
        if (ctx == null || dev == null || targetDisplayId <= 0) return;
        final String desc = dev.getDescriptor();
        final String devName = dev.getName();
        if (desc == null || desc.isEmpty()) return;
        new Thread(() -> {
            try {
                int route = ShizukuCmd.routeDisplayFor(dev, targetDisplayId);
                if (route <= 0) {
                    try { ShizukuCmd.releaseDescriptor(desc); } catch (Throwable ignored) {}
                    return;
                }
                boolean bound = false;
                for (int i = 0; i < 3 && !bound; i++) {
                    try { bound = ShizukuCmd.bindDescriptorToDisplay(desc, route); } catch (Throwable ignored) {}
                    if (!bound) SystemClock.sleep(150);
                }
                if (bound) {
                    Log.i(TAG, "外接触摸屏已绑定: [" + devName + "] -> display " + route);
                    CanghaiErrorCollector.logExt("[大脑后] 外接触摸屏已绑定 display " + route + " [" + devName + "]");
                } else {
                    Log.w(TAG, "外接触摸屏绑定失败: [" + devName + "] display " + route);
                }
            } catch (Throwable t) {
                Log.w(TAG, "外接触摸屏绑定异常: " + t.getMessage());
            }
        }, "touchscreen-bind").start();
    }

    /**
     * 屏就绪钩子：建屏方（DeXPresentation / ScreenRegistry）在虚拟屏/串流屏
     * 就绪、真实 displayId 已知时调用，把启动时已插着的外接触摸屏补绑到这块屏。
     * 热插拔的由 handleInputDeviceAdded 认领，这里只补「屏比设备晚到」的那一半。
     */
    public static void rebindExternalTouchscreens(final Context ctx, final int displayId) {
        if (ctx == null || displayId <= 0) return;
        new Thread(() -> {
            try {
                InputManager im = (InputManager) ctx.getApplicationContext().getSystemService(Context.INPUT_SERVICE);
                if (im == null) return;
                for (int id : im.getInputDeviceIds()) {
                    try {
                        InputDevice dev = im.getInputDevice(id);
                        if (dev == null || dev.isVirtual()) continue;
                        if (!dev.supportsSource(InputDevice.SOURCE_TOUCHSCREEN)) continue;
                        if (!ShizukuCmd.isExternalSafe(dev)) continue;
                        bindTouchscreenToDisplay(ctx, dev, displayId);
                    } catch (Throwable ignored) {}
                }
            } catch (Throwable ignored) {}
        }, "touchscreen-rebind").start();
    }

    /**
     * 把鼠标的一帧翻成 SOURCE_TOUCHSCREEN 事件注入串流屏。
     *
     * Root 走常驻守护进程的定长帧（RootInputDaemon.tryInject 已经支持
     * TOOL_TYPE_FINGER，不用改 dex），Shizuku 走带 displayId 的 binder 注入。
     * 两条路都显式带 displayId，绝不让事件落到默认屏 0。
     */
    private void injectFinger(int action, float x, float y) {
        int dId = fingerDisplayId();
        if (dId <= 0) return;
        if (PrivilegeRoute.mode() == PrivilegeRoute.Mode.ROOT) {
            RootInputBridge.inject(appContext, dId, action, 0, 0, x, y, 0f, 0f,
                    InputDevice.SOURCE_TOUCHSCREEN);
            return;
        }
        if (PrivilegeRoute.mode() != PrivilegeRoute.Mode.SHIZUKU) return;
        long now = SystemClock.uptimeMillis();
        if (action == MotionEvent.ACTION_DOWN) fingerDownTime = now;
        long down = fingerDownTime > 0 ? fingerDownTime : now;
        MotionEvent ev = MotionEvent.obtain(down, now, action, x, y, 0);
        try {
            ev.setSource(InputDevice.SOURCE_TOUCHSCREEN);
            ShizukuCmd.injectMotionEvent(ev, dId);
        } catch (Throwable ignored) {
        } finally {
            ev.recycle();
        }
    }

    private String destLabel(VirtualSlot slot, Host host) {
        switch (host) {
            case STREAM:
                return ScreenRegistry.get().hasStream()
                        ? ScreenRegistry.get().nameOf(StreamTarget.UI_ID) : "Moonlight 串流屏";
            case WIRED:
                return targetName(effectiveTarget(slot));
            default:
                return "手机屏";
        }
    }

    /**
     * 光标/虚拟外设离开手机时，把手机上可能残留的那次「按住」立刻取消。
     *
     * 手机首页是 WebView：只要有一个 ACTION_DOWN 没等到 UP，Chromium 就会自己
     * 判长按（选文字 / 唤起识屏 AI）。所以只要这一刻事件不该投手机，就必须取消，
     * 而不是等它自己超时。
     */
    private void cancelPhonePressIfLeft(Host host) {
        if (host == Host.PHONE || mainActivity == null) return;
        try { mainActivity.cancelPhoneMousePress(); } catch (Throwable ignored) {}
    }

    /**
     * 初始化外设大脑
     */
    public synchronized void init(MainActivity activity) {
        this.mainActivity = activity;
        this.appContext = activity.getApplicationContext();

        if (initialized) return;
        initialized = true;

        // 默认预置手机黑屏触控板插槽
        ensureSlot("virtual_touchpad", "触控 (黑屏触控板)", "touchpad", -1);

        // 监听物理外设热插拔 (蓝牙/USB 键盘鼠标手柄)
        registerHardwareDeviceListener();

        // 屏幕没了只有一个通知源：注册表。挂在上面的虚拟外设由这里统一回流。
        ScreenRegistry.get().init(appContext);
        ScreenRegistry.get().addListener(screenListener);

        Log.i(TAG, "沧海外设大脑已启动并就绪");
        CanghaiErrorCollector.logExt("沧海外设大脑已启动，统一虚拟总线已就绪");

        // 主线程一旦被同步等待卡住就是 10 秒 ANR，卡在哪一行必须能取证
        try { UiWatchdog.start(); } catch (Throwable ignored) {}

        // 物理鼠标被独占后，事件只从 daemon 上来，这里转成「写虚拟设备 + 挪自绘光标」。
        // 这条路不需要 PointerCapture：手机屏上没有第二根指针。
        try {
            RootInputBridge.setRawMouseSink(new RootInputBridge.RawMouseSink() {
                @Override
                public void onRawMotion(float dx, float dy) {
                    if (!VirtualMouse.isActive()) {
                        /* 【手机态：没有虚拟画布，也没有第二根指针】
                         *
                         * 没有外屏时 VirtualMouse 永远不会 active，于是这颗物理鼠标
                         * 一直是「系统自己读」的 —— 之前给自绘箭头留安全区、以为能挡住
                         * 系统手势，其实一点用都没有：停住的只是我们画的那根，系统指针
                         * 照旧一路往上，走到屏幕顶端就把通知栏拉下来了。
                         *
                         * 现在手机态也独占这颗鼠标（ensurePhoneMouseGrabbed），系统再也
                         * 收不到它的位移；位置只由下面这一行推进，屏幕上只有一根箭头。 */
                        if (mainActivity != null) {
                            float sp = pointerSpeed();
                            mainActivity.applyPhoneCursorDelta(dx * sp, dy * sp);
                        }
                        return;
                    }
                    /* 位置仍然只由 VirtualMouse 一个人维护：一次推进自绘箭头，
                     * 一次写 uinput 让系统指针落在同一处。屏幕上只允许有一层指针。 */
                    float sp = pointerSpeed();
                    VirtualMouse.moveBy(dx * sp, dy * sp);
                    markRealMouseOnExternalDisplay(dx, dy);
                    float[] p = VirtualMouse.position();
                    /* 真实外设 ↔ 真实外屏：这一帧按大脑层的绑定关系把指针画到用户真正在看的屏。
                     * 以前这里什么都不做，光标只跟着 Moonlight 网络事件画，外接屏上就永远没有鼠标。 */
                    if (p != null) RealPeripheralBinding.get().showRealCursor(appContext, p[0], p[1]);
                    int dId = fingerDisplayId();
                    if (p == null) return;
                    if (dId <= 0) {
                        /* 拖回手机后系统原生落点与自绘光标不同源，按住移动要补一发
                         * 带按钮状态的 MOVE 给首页 WebView，否则拖不动。 */
                        if (fingerDown) RealPeripheralBinding.get().deliverPhoneDrag(p[0], p[1]);
                        return;
                    }
                    /* Dock 是唯一需要大脑补投递的窗口，而且这个判断必须**运行期做**。
                     *
                     * Dock 有两副形态，取决于它挂在哪种载体上：
                     *  - CastHomeActivity（Root 虚拟屏）：底部一条**可触摸**窗口，系统命中得到，
                     *    这时 dispatchToDockXxx 内部会被 isDockDirectTouchActive() 挡掉，不会重发；
                     *  - DexHomeActivity（默认屏/外屏/DeX 载体）：整屏 FLAG_NOT_TOUCHABLE，
                     *    系统命中测试会直接跳过它，点击全部穿透到桌面 —— 必须由大脑直派。
                     *
                     * 所以这里恢复直派，但不自己判断"该不该"：把决定权交给窗口自己的
                     * isTouchable()。这样两种形态各自只有一份事件，不会再出现
                     * 「点开就自动关」（双份）或「点 Dock 穿透到桌面」（零份）。 */
                    boolean onDock = DexHomeActivity.isOnDockRegion(dId, p[0], p[1]);
                    if (onDock) {
                        int act = lastHoverOnDock
                                ? MotionEvent.ACTION_HOVER_MOVE : MotionEvent.ACTION_HOVER_ENTER;
                        DexHomeActivity.dispatchToDockHover(dId, act, p[0], p[1]);
                        lastHoverOnDock = true;
                    } else if (lastHoverOnDock) {
                        DexHomeActivity.dispatchToDockHover(dId, MotionEvent.ACTION_HOVER_EXIT, p[0], p[1]);
                        lastHoverOnDock = false;
                    }
                    if (fingerDown && fingerOnDock) {
                        DexHomeActivity.dispatchToDockTouch(dId, MotionEvent.ACTION_MOVE, p[0], p[1]);
                    }
                }

                @Override
                public void onRawButton(int androidButton, boolean down) {
                    if (!VirtualMouse.isActive()) {
                        /* 手机态：系统收不到这颗鼠标，点击只能由我们按自绘箭头的位置补。
                         * 落点口径和手机 surface 那一套完全一致（phoneMouseDown/Up）。 */
                        RealPeripheralBinding.get().deliverPhoneClick(androidButton, down);
                        return;
                    }
                    int dId = fingerDisplayId();
                    float[] p = VirtualMouse.position();
                    final float bx = (p != null) ? p[0] : 960f;
                    final float by = (p != null) ? p[1] : 540f;
                    /* 先记真事：这颗键是**真实 USB 鼠标**按的，设备名必须是它自己。
                     * 以前这条通道一条记录都不写，面板上看到的就只剩系统把我们注入的
                     * 触摸帧派回窗口时那一条「Aosen Virtual Mouse」——把回声当成了来源。 */
                    traceRealButton(androidButton, down, bx, by, dId);
                    /* 左键：外屏走**真鼠标按键**，不再翻成触摸注入。
                     *
                     * 以前外屏这边把左键翻译成 SOURCE_TOUCHSCREEN 的 DOWN/UP 注入，理由是
                     * 「原生应用窗口只认触摸」。能点，但拖不动：Chromium 收到的是手指，
                     * 只有 pointerdown/click，没有 mousemove —— 桌面图标的拖拽库靠的是
                     * 鼠标按住移动，于是「右键好了、图标还是拖不动」。
                     *
                     * 现在位置这条链已经收干净了（系统落点 = 箭头坐标，偏差 0~8px），
                     * 系统指针就在箭头底下，完全可以按真鼠标的路子走：
                     * 写 BTN_LEFT，系统自己按落点做命中测试，按住期间由设备维护按钮状态，
                     * 移动时系统自动补带 BUTTON_PRIMARY 的 ACTION_MOVE —— 拖拽、框选、
                     * 文本选择全是原生行为，我们一帧都不用补。
                     *
                     * Dock 是唯一例外：它是 NOT_TOUCHABLE 的悬浮窗，系统命中不到，
                     * 仍由大脑直派触摸。手机态没有画布，交给首页 surface 那套。 */
                    if (androidButton == MotionEvent.BUTTON_PRIMARY) {
                        if (down && !fingerDown) {
                            fingerDown = true;
                            /* 只有真正接住这次按下的 Dock 才算数：接不住就当场回落成真鼠标按键，
                             * 手势不能断。isDockDirectTouchActive 为真（窗口可触摸）时
                             * dispatchToDockTouch 返回 false，于是自然走系统那条路。 */
                            boolean onDock = dId > 0 && DexHomeActivity.isOnDockRegion(dId, bx, by)
                                    && DexHomeActivity.dispatchToDockTouch(dId, MotionEvent.ACTION_DOWN, bx, by);
                            fingerOnDock = onDock;
                            AosenPointerOverlay.setPressed(true);
                            if (dId > 0) {
                                if (!onDock) VirtualMouse.button(MotionEvent.BUTTON_PRIMARY, true);
                            } else {
                                /* 拖回手机：系统那根指针带系统加速，位置和自绘光标早已分叉，
                                 * 写 uinput 的 BTN_LEFT 会点在我们看不见的地方。 */
                                RealPeripheralBinding.get().deliverPhoneClick(
                                        MotionEvent.BUTTON_PRIMARY, true);
                            }
                        } else if (!down && fingerDown) {
                            fingerDown = false;
                            AosenPointerOverlay.setPressed(false);
                            if (dId > 0) {
                                if (fingerOnDock) {
                                    DexHomeActivity.dispatchToDockTouch(dId, MotionEvent.ACTION_UP, bx, by);
                                } else {
                                    VirtualMouse.button(MotionEvent.BUTTON_PRIMARY, false);
                                }
                            } else {
                                RealPeripheralBinding.get().deliverPhoneClick(
                                        MotionEvent.BUTTON_PRIMARY, false);
                            }
                            fingerOnDock = false;
                        }
                        return;
                    }
                    /* 右键：桌面/外部应用走系统真实按键（BTN_RIGHT 成对写，
                     * Chromium 自己发 contextmenu）；只有 Dock 例外 —— 它是 NOT_TOUCHABLE 窗口，
                     * 系统右键根本到不了它，只能直派。两者互斥，靠 rightOnDock 记住这一下归谁。 */
                    if (androidButton == MotionEvent.BUTTON_SECONDARY) {
                        if (dId <= 0) {
                            /* 手机端同理：按光标坐标准确落到首页 WebView，不再交给系统那根分叉的指针。 */
                            RealPeripheralBinding.get().deliverPhoneClick(
                                    MotionEvent.BUTTON_SECONDARY, down);
                            return;
                        }
                        if (down) {
                            rightOnDock = DexHomeActivity.onDockRightClick(dId, bx, by);
                            if (!rightOnDock) VirtualMouse.button(MotionEvent.BUTTON_SECONDARY, true);
                        } else {
                            if (!rightOnDock) VirtualMouse.button(MotionEvent.BUTTON_SECONDARY, false);
                            rightOnDock = false;
                        }
                        return;
                    }
                    VirtualMouse.button(androidButton, down);
                }

                @Override
                public void onRawScroll(float v, float h) {
                    if (!VirtualMouse.isActive()) {
                        /* 手机态：滚轮也交给首页 WebView 自己滚，系统那边没有指针可滚。 */
                        if (mainActivity != null) {
                            mainActivity.triggerPhoneScroll(v, h);
                        }
                        return;
                    }
                    /* 滚轮同样只有一条路：写 uinput 的 REL_WHEEL，InputDispatcher 把它
                     * 变成 ACTION_SCROLL 派给落在光标下的那个窗口。Chromium 天然处理 wheel，
                     * 再往页面里补一帧 dispatchWebScroll 就是滚两倍 —— 那正是「滚一格跳两格」
                     * 这类怪手感的来源。 */
                    VirtualMouse.scroll(v, h);
                    float[] p = VirtualMouse.position();
                    if (p == null) return;
                    int dId = fingerDisplayId();
                    /* 滚轮也是真实外设发的，先按真名记一条，面板才看得到「滚了几个格」。 */
                    traceRealScroll(v, h, p[0], p[1], dId);
                    if (dId <= 0) RealPeripheralBinding.get().deliverPhoneScroll(v, h);
                }
            });
        } catch (Throwable ignored) {}
    }

    /**
     * 真实鼠标被独占、虚拟设备绑到某块屏之后，「它就在那块屏上」是既成事实。
     *
     * <p>以前这里什么都不做，槽位还停在默认目标屏（手机屏 0），于是出现最荒诞的一幕：
     * 鼠标明明在外屏上动，手机端 UI 的外设卡却挂在手机屏；调试面板也因为
     * 从来没走过 InputTrace，显示「等待输入设备」。
     *
     * <p>所以真设备通道每帧顺手把两件事对齐：槽位的目标屏改成真实绑定的屏（事实），
     * 以及把这一帧记进 InputTrace（面板的唯一信源）。
     */
    /** 真设备通道唯一的灵敏度来源。系统对绝对坐标设备不做指针加速，
     *  所以用户设置的鼠标速度必须在这里乘一次，而且只能乘这一次。 */
    private static float pointerSpeed() {
        try {
            float s = com.alkaidlab.sunshine.service.MouseCursorPrefs.getSpeed();
            if (s > 0.05f && s <= 4f) return s;
        } catch (Throwable ignored) {}
        return 1f;
    }

    /* ================= 手机态的物理鼠标独占 =================
     *
     * 没有外屏、没有虚拟画布时，以前这颗物理鼠标是「系统自己读」的：系统指针一路
     * 自由移动，走到屏幕顶端就自己把通知栏拉下来，走到边上就自己触发返回手势。
     * 我们画的那根箭头怎么挡都没用 —— 挡的只是影子，本体还在走。
     *
     * 唯一真正管用的做法是把它从系统手里抢过来（EVIOCGRAB）：系统从此收不到这颗
     * 鼠标的一个字节，屏幕上只剩我们画的那根指针，位置完全由我们自己算；
     * 点击 / 滚轮由 raw 通道按箭头坐标补发。
     *
     * 代价：被独占期间这颗鼠标在系统眼里「不存在」，所以离开沧海时必须放手，
     * 否则用户切到别的应用会觉得鼠标坏了（见 releasePhoneMouseGrabIfIdle）。
     */
    private volatile long lastPhoneGrabAt = 0L;

    private void ensurePhoneMouseGrabbed() {
        if (PrivilegeRoute.mode() != PrivilegeRoute.Mode.ROOT) return;
        if (VirtualMouse.isActive()) return;     // 外屏通道自己在管，别抢
        if (RootInputBridge.isRawGrabbed()) return;
        long now = SystemClock.uptimeMillis();
        if (now - lastPhoneGrabAt < 3000L) return;   // 抢不到别死循环
        lastPhoneGrabAt = now;
        try { RootInputBridge.grabPhysicalMouse(appContext); } catch (Throwable ignored) {}
    }

    /** 离开沧海 / 回后台：鼠标必须还给系统，否则整台设备上这颗鼠标都是死的。 */
    public static void releasePhoneMouseGrabIfIdle() {
        try {
            if (VirtualMouse.isActive()) return;
            if (RootInputBridge.isRawGrabbed()) RootInputBridge.releasePhysicalMouse(null);
        } catch (Throwable ignored) {}
    }

    private void markRealMouseOnExternalDisplay(float dx, float dy) {
        final String held = RootInputBridge.grabbedName();
        if (held == null || held.isEmpty()) return;
        boolean hitSlot = false;
        synchronized (this) {
            for (VirtualSlot s : slots.values()) {
                if (!"mouse".equals(s.type) || !held.equals(s.name)) continue;
                s.cursorX += dx;
                s.cursorY += dy;
                s.lastActiveTime = SystemClock.uptimeMillis();
                /* 槽位里的 targetDisplayId 是「虚拟外设插到哪块画布」的内部路由值，
                 * 不动它；软件端展示的真实关系一律只读 RealPeripheralBinding。 */
                InputTrace.mouseMotion(s.name, s.cursorX, s.cursorY, dx, dy);
                hitSlot = true;
            }
        }
        /* 槽位还没建出来（刚独占、或用户把卡片拖回了手机）也要记得上。
         * 以前这里靠 VirtualMouse.displayId() > 0 才记录，鼠标一回到手机就一条不写，
         * 面板顶部永远停在「等待输入设备」。 */
        if (!hitSlot) {
            float[] p = VirtualMouse.position();
            InputTrace.mouseMotion(held, p != null ? p[0] : 0f, p != null ? p[1] : 0f, dx, dy);
        }
    }

    /** 真设备的名字：面板上必须显示那颗插在机器上的 USB 鼠标，而不是我们造的虚拟设备。 */
    private static String realMouseName() {
        String held = RootInputBridge.grabbedName();
        return (held == null || held.isEmpty()) ? "外接鼠标" : held;
    }

    /** 这一帧实际投给了哪块屏（面板每行末尾的 → 目标）。 */
    private static String rawDestLabel(int dId) {
        if (dId <= 0) return "手机";
        try {
            String n = RealPeripheralBinding.get().realDisplayName();
            if (n != null && !n.isEmpty()) return n;
        } catch (Throwable ignored) {}
        return "display " + dId;
    }

    /** 真实鼠标按键：按下 / 抬起成对记，设备名用真名。 */
    private static void traceRealButton(int button, boolean down, float x, float y, int dId) {
        try {
            String name = realMouseName();
            String dest = rawDestLabel(dId);
            if (down) InputTrace.mousePress(name, x, y, button, dest);
            else InputTrace.mouseRelease(name, x, y, button, dest);
        } catch (Throwable ignored) {}
    }

    private static void traceRealScroll(float v, float h, float x, float y, int dId) {
        try {
            InputTrace.mouseScroll(realMouseName(), x, y, v, h, rawDestLabel(dId));
        } catch (Throwable ignored) {}
    }

    /** 屏幕事实的唯一订阅：屏消失 → 外设回流，别的模块不再各自调 onDisplayDisconnected。 */
    private final ScreenRegistry.Listener screenListener = new ScreenRegistry.Listener() {
        @Override
        public void onScreensChanged() {
            RealPeripheralBinding.get().invalidate();
        }

        @Override
        public void onScreenGone(int key) {
            RealPeripheralBinding.get().invalidate();
            onDisplayDisconnected(key);
        }
    };

    public synchronized void setDexPresentation(DeXPresentation presentation) {
        // 只登记「有线外屏桌面」这个实体，绝不擅自改动任何虚拟外设的挂载点：
        // 虚拟外设插在哪块屏只能由用户在卡片上决定，屏幕接入/断开由
        // onDisplayDisconnected() 单独做回退，避免 ID 漂移悄悄覆盖用户选择。
        this.dexPresentation = presentation;
    }

    /** 由 LocalMoonlightDisplay 挂载 / 释放时调用，绝不覆盖有线外屏的 dexPresentation。 */
    public synchronized void setMoonlightPresentation(DeXPresentation presentation) {
        this.moonlightPresentation = presentation;
    }

    /** 只有「无 Root、无 Shizuku」的本地虚拟屏在跑时才会返回 true。 */
    public synchronized boolean hasMoonlightPresentation() {
        return moonlightPresentation != null && moonlightPresentation.isShowing();
    }

    private synchronized DeXPresentation moonlightOrNull() {
        return hasMoonlightPresentation() ? moonlightPresentation : null;
    }

    // ==============================================================
    // Moonlight 客户端外设：一个客户端连接 = 一个虚拟外设
    // ==============================================================

    /** 外设大脑卡片里的 Moonlight 客户端虚拟插槽 ID（整个客户端只此一个）。 */
    public static final String MOONLIGHT_REMOTE_SLOT = "moonlight_remote";
    private long lastRemoteTraceAt = 0L;

    /**
     * 一个 Moonlight 客户端就是一个整体：键鼠、触控板、手柄都跑在同一条输入流上，
     * 共用一个挂载点，绝不拆成多个虚拟外设（否则会出现「鼠标在手机屏、键盘在串流屏」
     * 这种客户端根本不存在的状态）。连入登记、断开只标记离线，用户设好的挂载点保留。
     * 客户端发来的每个输入包由统一帧队列合帧后，按这个虚拟外设当前的
     * targetDisplayId 落地（deliverRemoteTo / deliverRemoteKeyTo），不做二次坐标解算。
     */
    public synchronized void onMoonlightRemoteAttached() {
        VirtualSlot slot = ensureSlot(MOONLIGHT_REMOTE_SLOT,
                "Moonlight 外设", "mouse", StreamTarget.UI_ID);
        if (slot.targetDisplayId < 0) slot.targetDisplayId = StreamTarget.UI_ID;
        slot.lastActiveTime = SystemClock.uptimeMillis();
        Log.i(TAG, "外设大脑: Moonlight 远端键鼠已接入，目标=Moonlight 串流桌面");
        CanghaiErrorCollector.logExt("外设大脑: Moonlight 远端键鼠/触控板已接入");
        notifyPeripheralUi();
        // 客户端刚连入也算「鼠标刚出现」：等桌面把光标 View 挂好后发一次到达波纹。
        final int arrivalTarget = slot.targetDisplayId;
        mainHandler.postDelayed(() -> pulseArrivalOn(hostForTarget(arrivalTarget)), 600L);
    }

    public synchronized void onMoonlightRemoteDetached() {
        VirtualSlot slot = slots.get(MOONLIGHT_REMOTE_SLOT);
        if (slot != null) slot.lastActiveTime = 0L;
        notifyPeripheralUi();
    }

    private void notifyPeripheralUi() {
        if (mainActivity != null) {
            mainActivity.runOnUiThread(() -> {
                try { mainActivity.syncToPhoneWeb(); } catch (Throwable ignored) {}
            });
        }
    }

    /** Moonlight 网络 MotionEvent 的统一登记口：写 InputTrace + 刷新插槽活跃时间。 */
    public synchronized boolean onMoonlightRemoteMotion(MotionEvent event) {
        if (event == null) return true;
        VirtualSlot slot = ensureSlot(MOONLIGHT_REMOTE_SLOT,
                "Moonlight 外设", "mouse", StreamTarget.UI_ID);
        slot.lastActiveTime = SystemClock.uptimeMillis();
        if (InputTrace.isEnabled()) {
            int action = event.getActionMasked();
            boolean continuous = action == MotionEvent.ACTION_MOVE
                    || action == MotionEvent.ACTION_HOVER_MOVE;
            long now = SystemClock.uptimeMillis();
            // 鼠标移动帧可达 100+Hz，诊断面板按 80ms 采样，避免刷爆日志。
            if (!continuous || now - lastRemoteTraceAt >= 80L) {
                lastRemoteTraceAt = now;
                InputTrace.motion("Moonlight(远端)", event);
            }
        }
        return true;
    }

    /** Moonlight 网络键盘 / 手柄按键的统一登记口。 */
    public synchronized void onMoonlightRemoteKey(int keyCode, boolean down, int modifiers) {
        VirtualSlot slot = ensureSlot(MOONLIGHT_REMOTE_SLOT,
                "Moonlight 外设", "mouse", StreamTarget.UI_ID);
        slot.lastActiveTime = SystemClock.uptimeMillis();
        if (InputTrace.isEnabled()) {
            InputTrace.key("Moonlight(远端)", keyCode, down, modifiers);
        }
    }

    /**
     * 确保插槽存在，并从持久化存储恢复绑定的目标屏幕
     */
    public VirtualSlot ensureSlot(String slotId, String name, String type, int fallbackDisplay) {
        VirtualSlot slot = slots.get(slotId);
        if (slot == null) {
            int savedTarget = (appContext != null)
                    ? CanghaiStore.getPeripheralDisplay(appContext, slotId, fallbackDisplay)
                    : fallbackDisplay;
            slot = new VirtualSlot(slotId, name, type, savedTarget);
            slots.put(slotId, slot);
        } else {
            if (name != null) slot.name = name;
            if (type != null) slot.type = type;
        }
        return slot;
    }

    /**
     * 用户在卡片上切换外设绑定的目标屏幕（热插拔路由核心）
     */
    public void setDeviceTargetDisplay(String slotId, int targetDisplayId) {
        VirtualSlot slot = slots.get(slotId);
        if (slot == null) {
            slot = ensureSlot(slotId, slotId, "peripheral", targetDisplayId);
        }
        int oldTarget = slot.targetDisplayId;
        slot.targetDisplayId = targetDisplayId;
        slot.lastActiveTime = SystemClock.uptimeMillis();

        if (appContext != null) {
            CanghaiStore.setPeripheralDisplay(appContext, slotId, targetDisplayId);
        }

        String displayName;
        if (targetDisplayId <= 0) {
            displayName = "手机屏幕";
        } else if (StreamTarget.get().isStreamTarget(targetDisplayId)) {
            displayName = StreamTarget.get().getName();
        } else {
            String friendly = (mainActivity != null) ? mainActivity.getDisplayNameForId(targetDisplayId) : null;
            displayName = (friendly == null || friendly.isEmpty()) ? "外接显示器" : friendly;
        }

        Log.i(TAG, "外设热插拔: [" + slot.name + "] 目标屏幕从 " + oldTarget + " 切换为 -> " + targetDisplayId + " (" + displayName + ")");
        CanghaiErrorCollector.logExt("外设热插拔: [" + slot.name + "] -> " + displayName);

        // 切换成功立刻在新屏上发一圈到达波纹；只做提示，不参与事件派发。
        if (oldTarget != targetDisplayId) pulseArrivalOn(hostForTarget(targetDisplayId));

        // 广播给手机前端卡片刷新连线与状态
        if (mainActivity != null) {
            mainActivity.runOnUiThread(() -> {
                try {
                    mainActivity.syncToPhoneWeb();
                } catch (Throwable ignored) {}
            });
        }
    }

    // ==============================================================
    // 热插拔唯一判据
    //
    // 大脑只负责「真实外设发生了什么」，把事件交给它对应的那个虚拟外设；
    // 虚拟外设只负责「我现在插在哪块屏」，按 Host 落地。
    // 所有输入入口（位移 / 点击 / 滚轮 / 按键 / 手柄 / Moonlight 远端包）
    // 一律先问 hostOf()，不允许各处再写一套 if/else 猜目标屏。
    // ==============================================================

    public enum Host { PHONE, STREAM, WIRED }

    /** 目标屏 id → 落点类别。屏幕身份只问 ScreenRegistry，绝不查屏名。 */
    public static Host hostForTarget(int targetDisplayId) {
        switch (ScreenRegistry.get().kindOf(targetDisplayId)) {
            case STREAM:
                return Host.STREAM;
            case WIRED:
                return Host.WIRED;
            default:
                return Host.PHONE;
        }
    }

    /** 虚拟外设落到某块屏时，只通知那块屏发一次到达波纹；不参与输入派发。 */
    private void pulseArrivalOn(Host host) {
        if (host == null) return;
        mainHandler.post(() -> {
            try {
                if (host == Host.PHONE) {
                    if (mainActivity != null) mainActivity.pulsePhoneCursorArrival();
                } else if (host == Host.STREAM) {
                    DeXPresentation moon = moonlightOrNull();
                    if (moon != null) moon.pulseCursorArrival();
                } else {
                    DeXPresentation wired = wiredOrNull();
                    if (wired != null) wired.pulseCursorArrival();
                }
            } catch (Throwable ignored) {}
        });
    }

    /**
     * 外设实际落点：
     * - 仅手机（无有线外屏）且当前有 Moonlight 串流 → 所有外设自动转交串流屏，
     *   插在手机上的键鼠直接喂给对端，不再留在手机屏。
     * - 否则：用户显式绑定（targetDisplayId>=0）一律尊重；未绑定的跟随当前串流屏，
     *   没有串流时回退手机屏。
     */
    private synchronized int effectiveTarget(VirtualSlot slot) {
        if (wiredOrNull() == null && ScreenRegistry.get().hasStream()) {
            return StreamTarget.UI_ID;
        }
        if (slot.targetDisplayId >= 0) return slot.targetDisplayId;
        return ScreenRegistry.get().hasStream() ? StreamTarget.UI_ID : 0;
    }

    /** 真实外设 id / descriptor → 它唯一对应的虚拟外设；未登记时返回 null。 */
    public synchronized VirtualSlot slotOf(String realDeviceId) {
        if (realDeviceId == null) return null;
        VirtualSlot slot = slots.get(realDeviceId);
        if (slot == null) {
            String canonical = descriptorToCanonicalId.get(realDeviceId);
            if (canonical != null) slot = slots.get(canonical);
        }
        return slot;
    }

    /** 大脑把真实外设的事件交给虚拟外设之前，唯一一次问「你现在插在哪」。 */
    public synchronized Host hostOf(String realDeviceId) {
        VirtualSlot slot = slotOf(realDeviceId);
        if (slot == null) return null;
        /* 特权通道的事实优先：这颗鼠标已经被独占，它的虚拟设备绑在哪块屏，
         * 事件就落在哪块屏，和槽位里那个「用户选的目标屏」无关。
         * 不这么判就会出现「鼠标在外屏动、卡片挂在手机屏」的怪事：
         * 槽位默认目标是 0（手机），而设备实际绑在外屏上。 */
        if (VirtualMouse.isActive() && "mouse".equals(slot.type) && VirtualMouse.displayId() > 0) {
            DeXPresentation moon = moonlightOrNull();
            if (moon != null && moon.getVirtualDisplayId() == VirtualMouse.displayId()) {
                return Host.STREAM;
            }
            return Host.WIRED;
        }
        return hostForTarget(effectiveTarget(slot));
    }

    /** 前端展示用：把「未绑定(-1)」解析成当前实际会落地的目标屏，不显示假 id。 */
    public synchronized int effectiveTargetOf(String slotId) {
        VirtualSlot slot = slots.get(slotId);
        return slot == null ? 0 : effectiveTarget(slot);
    }

    /** 当前有线外屏桌面；零权限本地虚拟屏不算有线屏。 */
    private synchronized DeXPresentation wiredOrNull() {
        return (dexPresentation != null && dexPresentation.isShowing()) ? dexPresentation : null;
    }

    /**
     * 真设备鼠标该把「看得见的箭头」画在哪块桌面。
     * 设备绑在哪块屏由 {@link VirtualMouse} 决定，与外设槽位的目标屏无关，
     * 所以先按槽位偏好挑，挑不到就用当前活着的那块桌面。
     */
    private synchronized DeXPresentation virtualMousePresentation(Host host) {
        if (host == Host.STREAM && moonlightOrNull() != null) return moonlightOrNull();
        if (host == Host.WIRED && wiredOrNull() != null) return wiredOrNull();
        if (wiredOrNull() != null) return wiredOrNull();
        return moonlightOrNull();
    }

    /**
     * 零权限链路的唯一跨屏落地口。Moonlight 远端帧合帧之后，把这一帧交给
     * 那台客户端对应的虚拟外设，由虚拟外设按当前挂载点投递。
     * 返回 false 表示没接住（例如外屏已拔掉），调用方应回退到串流桌面。
     */
    public boolean deliverRemoteTo(Host host, MotionEvent event) {
        if (event == null || host == null || host == Host.STREAM) return false;
        if (host == Host.PHONE) {
            return mainActivity != null && mainActivity.deliverPhoneRemoteMotion(event);
        }
        final DeXPresentation ext = wiredOrNull();
        if (ext == null) return false;
        final MotionEvent copy = MotionEvent.obtain(event);
        final int action = copy.getActionMasked();
        final boolean generic = action == MotionEvent.ACTION_HOVER_MOVE
                || action == MotionEvent.ACTION_SCROLL
                || action == MotionEvent.ACTION_BUTTON_PRESS
                || action == MotionEvent.ACTION_BUTTON_RELEASE;
        if (!generic && copy.isFromSource(InputDevice.SOURCE_MOUSE)) {
            copy.setSource(InputDevice.SOURCE_TOUCHSCREEN);
        }
        mainHandler.post(() -> {
            try {
                if (generic) ext.dispatchGenericMotionEvent(copy);
                else ext.dispatchTouchEvent(copy);
            } catch (Throwable ignored) {
            } finally {
                copy.recycle();
            }
        });
        return true;
    }

    /** 同上，键盘 / 手柄按键的跨屏落地。KeyEvent 是自建对象，不需要 recycle。 */
    public boolean deliverRemoteKeyTo(Host host, KeyEvent event) {
        if (event == null || host == null || host == Host.STREAM) return false;
        if (host == Host.PHONE) {
            return mainActivity != null && mainActivity.deliverPhoneRemoteKey(event);
        }
        final DeXPresentation ext = wiredOrNull();
        if (ext == null) return false;
        mainHandler.post(() -> {
            try { ext.dispatchExternalKeyEvent(event); } catch (Throwable ignored) {}
        });
        return true;
    }

    // ==============================================================
    // 物理外设唯一入口
    //
    // 真实设备 → 虚拟外设 → 挂载屏，只有这一条链。系统层、Activity 层
    // 都不允许自己算目标屏，也不允许自己画光标；它们只把「设备发生了什么」
    // 原样交到这里。
    // ==============================================================

    /** 当前光标落在哪类屏；只用于切换时隐藏旧屏，不参与任何路由判断。 */
    private Host cursorHost = null;

    /**
     * 手机这一刻是不是「没有指针外设在服务它」。
     * 手机首页据此关闭长按识屏 / 询问 AI：鼠标已经挂到 Moonlight，手机上不该有长按语义。
     */
    public boolean isPointerAwayFromPhone() {
        Host h = cursorHost;
        return h != null && h != Host.PHONE;
    }

    /**
     * 真实指针设备的唯一身份。
     *
     * PointerCapture 拿到的 MotionEvent 常常不带 device（descriptor 为空），
     * 如果此时按「名字」现造一个插槽，事件就落到另一张卡上：用户拖的是
     * AuditedDevice 卡，动的是新造的假卡，表现就是「怎么拖都没用」。
     *
     * 所以身份只认两条：有 device 时用系统给的身份（与热插拔监听完全同一个
     * resolveCanonicalSlotId）；没 device 时沿用这台机器上当前那一个真实指针。
     * 这是身份续接，不是猜屏幕。
     */
    private volatile String pointerSlotId;

    /** 记住当前这台真实指针设备（热插拔监听里登记，捕获通道丢 device 时续接）。 */
    private void rememberPointerSlot(String slotId) {
        if (slotId != null) pointerSlotId = slotId;
    }

    private synchronized VirtualSlot pointerSlotFor(InputDevice dev, String devName) {
        if (dev != null && dev.isExternal()) {
            String id = resolveCanonicalSlotId(dev, "mouse");
            pointerSlotId = id;
            String name = (dev.getName() != null && !dev.getName().isEmpty()) ? dev.getName()
                    : ((devName != null && !devName.isEmpty()) ? devName : "外接鼠标");
            VirtualSlot slot = ensureSlot(id, name, "mouse", -1);
            slot.name = name;
            slot.type = "mouse";
            return slot;
        }
        if (pointerSlotId != null) {
            VirtualSlot slot = slots.get(pointerSlotId);
            if (slot != null) return slot;
        }
        // 还没收到过任何带 device 的指针事件：全机只有一个鼠标型虚拟外设时，
        // 它就是这台指针，直接认，绝不新造一个（Moonlight 客户端是一整体，不参与）。
        VirtualSlot only = null;
        for (VirtualSlot s : slots.values()) {
            if (!"mouse".equals(s.type) || MOONLIGHT_REMOTE_SLOT.equals(s.id)) continue;
            if (only != null) return null;
            only = s;
        }
        return only;
    }

    /**
     * 物理设备 → 它对应的虚拟外设。没登记过就当场登记成第一类公民，
     * 绝不根据「当前有没有串流」临时猜一个目标屏。
     */
    private synchronized VirtualSlot physicalSlot(String desc, String devName, String type) {
        if ("mouse".equals(type)) {
            // 指针设备只认 pointerSlotFor 的身份；认不出来就明确「不是我们的外设」，
            // 绝不按名字现造一张假卡，否则事件与卡片会各自落到不同插槽上。
            return pointerSlotFor(null, devName);
        }
        VirtualSlot slot = slotOf(desc);
        if (slot != null) {
            if (devName != null && !devName.isEmpty()) slot.name = devName;
            if (type != null) slot.type = type;
            return slot;
        }
        // PointerCapture 偶尔会抹掉 device：此时按类型归到唯一的同类外设，
        // 仍然只认「虚拟外设自己的挂载点」，不是猜屏幕。
        if ((desc == null || desc.isEmpty()) && type != null) {
            VirtualSlot only = null;
            for (VirtualSlot s : slots.values()) {
                if (!type.equals(s.type)) continue;
                if (only != null) { only = null; break; }
                only = s;
            }
            if (only != null) return only;
        }
        String canonical = (devName != null && !devName.isEmpty())
                ? "hw_" + devName + "_" + type
                : "dev_" + type;
        if (desc != null && !desc.isEmpty()) descriptorToCanonicalId.put(desc, canonical);
        VirtualSlot created = ensureSlot(canonical,
                (devName != null && !devName.isEmpty()) ? devName : "外接外设", type, -1);
        created.lastActiveTime = SystemClock.uptimeMillis();
        notifyPeripheralUi();
        return created;
    }

    /** 挂载屏没了就回落手机，事件绝不丢；有屏时一律以插槽绑定为准。 */
    private Host liveHostOf(VirtualSlot slot) {
        int target = effectiveTarget(slot);
        if (!ScreenRegistry.get().isAlive(target)) return Host.PHONE;
        return hostForTarget(target);
    }

    /**
     * 光标只由挂载屏画一次。切屏时把其它屏的光标收掉，
     * 之后所有移动都只更新这一块屏上的那一个。
     */
    private synchronized void focusCursor(Host host) {
        // 每次落地前都确认一次：上家屏幕上不能留下「按着不放」的悬空手势。
        cancelPhonePressIfLeft(host);
        if (cursorHost == host) return;
        cursorHost = host;
        if (host != Host.PHONE && mainActivity != null) mainActivity.hidePhoneCursor();
        if (host != Host.STREAM) {
            DeXPresentation p = moonlightOrNull();
            if (p != null) p.hideMouseCursor();
        }
        if (host != Host.WIRED) {
            DeXPresentation p = wiredOrNull();
            if (p != null) p.hideMouseCursor();
        }
    }

    /** 物理鼠标相对位移：交给虚拟外设，由它按自己的挂载点落地。 */
    public boolean dispatchPhysicalMotion(InputDevice dev, String desc, String devName,
                                          float dx, float dy,
                                          float absX, float absY, boolean absolute) {
        // 自家虚拟外设的事件被系统派回来了：系统已经把它送到目标窗口，这里再收一遍
        // 就是自激（写设备 → 派回窗口 → 又写设备），位移翻倍、队列灌满即 ANR。
        if (VirtualMouse.isOwnDevice(devName)) return true;
        VirtualSlot slot = (dev != null && dev.isExternal())
                ? pointerSlotFor(dev, devName) : physicalSlot(desc, devName, "mouse");
        if (slot == null) return false;
        // 失去 PointerCapture 的那一瞬间，系统会拿手机屏的绝对坐标重算一次相对量，
        // 一帧就能上千像素。这种帧必须丢，否则光标会先瞬移到边角再被拉回来
        // —— 用户看到的「鼠标一贴屏幕边就跳回中间」有一半是它造成的。
        if (Math.abs(dx) > 240f || Math.abs(dy) > 240f) {
            InputTrace.note("大脑", "位移突变已丢弃 dx=" + Math.round(dx) + " dy=" + Math.round(dy));
            return true;
        }
        slot.lastActiveTime = SystemClock.uptimeMillis();
        syncMount(slot);
        focusCursor(slot.mountHost);

        /* 手机屏 + 没有外屏在接管：这颗物理鼠标必须从系统手里抢过来。
         *
         * 以前只在「虚拟设备绑上外屏」时才独占，手机态一直是系统自己读 ——
         * 于是自绘箭头停住了、系统指针还在往上跑，到顶端自己拉开通知栏，
         * 给箭头留安全区只能挡住影子，挡不住本体。
         * 独占之后系统收不到这颗鼠标，位置只由下面 moveBy/moveTo 推进。 */
        if (slot.mountHost == Host.PHONE
                && !VirtualMouse.isActive()
                && MainActivity.getActiveDisplayIdStatic() <= 0) {
            ensurePhoneMouseGrabbed();
        }

        float[] pos;
        if (slot.mountHost == Host.PHONE && absolute) {
            // 没被 PointerCapture 独占时，系统指针位置就是真实位置：直接对齐，
            // 不用相对量累加，也就不会因为丢一帧而永远差一截。
            pos = slot.surface.moveTo(absX, absY);
        } else {
            pos = slot.surface.moveBy(dx, dy, slot.cursorX, slot.cursorY, 1.0f);
        }
        if (pos == null) {
            InputTrace.note("大脑", "目标屏未接住位移，这一帧已丢弃");
            return true;
        }
        slot.cursorX = pos[0];
        slot.cursorY = pos[1];
        // 按着左键移动 = 拖拽，必须原样送给目标面，否则永远只能点不能拖。
        if (slot.pressedButton == MotionEvent.BUTTON_PRIMARY) {
            slot.surface.dragTo(slot.cursorX, slot.cursorY);
        }
        InputTrace.mouseMotion(slot.name, slot.cursorX, slot.cursorY, dx, dy);
        return true;
    }

    /** 物理鼠标按键：交给虚拟外设，按下与抬起走同一个端。 */
    /**
     * 最近一次真正按下所落在的外设槽。
     * 补发抬起时必须用它：那时候事件已经被系统翻译成「无设备的触摸」，
     * 再用事件里的 device 去算槽位会算到另一个槽，抬起就补到了空处。
     */
    private volatile VirtualSlot lastPressedSlot;

    /** 把上一次按下补一个抬起（捕获丢失导致 UP 帧丢失时用），返回是否真的补了。 */
    public boolean releaseLastPress() {
        VirtualSlot slot = lastPressedSlot;
        if (slot == null || slot.pressedButton == 0) return false;
        final int btn = slot.pressedButton;
        slot.pressedButton = 0;
        InputTrace.mouseClick(slot.name, slot.cursorX, slot.cursorY, btn, destLabel(slot, slot.mountHost));
        if (!slot.surface.button(btn, false, slot.cursorX, slot.cursorY)) {
            InputTrace.note("大脑", "补发抬起时目标屏不可用");
        }
        return true;
    }

    public boolean dispatchPhysicalButton(InputDevice dev, String desc, String devName, int btn, boolean down) {
        if (VirtualMouse.isOwnDevice(devName)) return true;
        VirtualSlot slot = (dev != null && dev.isExternal())
                ? pointerSlotFor(dev, devName) : physicalSlot(desc, devName, "mouse");
        if (slot == null) return false;
        slot.lastActiveTime = SystemClock.uptimeMillis();        syncMount(slot);
        focusCursor(slot.mountHost);
        final String dest = destLabel(slot, slot.mountHost);

        if (down) {
            if (btn == 0 || btn == slot.pressedButton) return true;
            slot.pressedButton = btn;
            lastPressedSlot = slot;
            InputTrace.mousePress(slot.name, slot.cursorX, slot.cursorY, btn, dest);
            // 左键按下时看一眼系统焦点，判断点击究竟派给了谁（只在调试面板出现）。
            if (btn == MotionEvent.BUTTON_PRIMARY && slot.mountHost != Host.PHONE) probeFocusAsync();
            if (!slot.surface.button(btn, true, slot.cursorX, slot.cursorY)) {
                InputTrace.note("大脑", "目标屏不可用，按下已丢弃");
            }
            return true;
        }

        if (btn == 0) btn = slot.pressedButton;
        if (btn == 0) return true;
        slot.pressedButton = 0;
        InputTrace.mouseClick(slot.name, slot.cursorX, slot.cursorY, btn, dest);
        if (!slot.surface.button(btn, false, slot.cursorX, slot.cursorY)) {
            InputTrace.note("大脑", "目标屏不可用，按键已丢弃");
        }
        return true;
    }

    /** 物理鼠标滚轮：同样交给虚拟外设，它自己知道该滚哪块屏。 */
    public boolean dispatchPhysicalScroll(InputDevice dev, String desc, String devName, float vScroll, float hScroll) {
        if (VirtualMouse.isOwnDevice(devName)) return true;
        VirtualSlot slot = (dev != null && dev.isExternal())
                ? pointerSlotFor(dev, devName) : physicalSlot(desc, devName, "mouse");
        if (slot == null) return false;
        slot.lastActiveTime = SystemClock.uptimeMillis();
        syncMount(slot);
        focusCursor(slot.mountHost);
        final String dest = destLabel(slot, slot.mountHost);

        InputTrace.mouseScroll(slot.name, slot.cursorX, slot.cursorY, vScroll, hScroll, dest);
        if (!slot.surface.scroll(vScroll, hScroll, slot.cursorX, slot.cursorY)) {
            InputTrace.note("大脑", "目标屏不可用，滚轮已丢弃");
        }
        return true;
    }

    // ==============================================================
    // 统一虚拟事件流输入入口（由黑屏触控、蓝牙手柄等统一调用）
    // ==============================================================

    /**
     * 1. 移动光标 (相对位移 dx, dy，由外设大脑在目标画布解算绝对坐标)
     */
    public void dispatchMotion(String slotId, float rawDx, float rawDy, float baseSensitivity) {
        VirtualSlot slot = slots.get(slotId);
        if (slot == null) slot = ensureSlot(slotId, "未知外设", "mouse", -1);
        slot.lastActiveTime = SystemClock.uptimeMillis();
        final Host host = hostOf(slotId);

        // 大脑报告真实外设发生了什么（单一信源，不重复记录）
        if ("mouse".equals(slot.type)) {
            InputTrace.mouseMotion(slot.name, slot.cursorX, slot.cursorY, rawDx, rawDy);
        } else {
            InputTrace.touchpadMotion(slot.cursorX, slot.cursorY, rawDx, rawDy);
        }

        // 特权环境：真设备接管。相对位移直接写给 /dev/uinput 造出来的鼠标节点，
        // 系统把它当物理硬件读走 —— 不经过 injectInputEvent，也就不会再抢手机屏的
        // PointerCapture（左键一注入就掉捕获、抬手漏到手机，根因就在这里）。
        //
        // 这里**不再看 host**：设备一旦绑到外屏，它的指针在物理上就只存在于那块屏，
        // 「这块鼠标现在挂在哪」由绑屏决定，不再由外设大脑的槽位目标决定。
        // 之前要求 host != PHONE，而鼠标槽位默认目标是手机屏，于是位移一个都没写进
        // 设备（日志里只有归位那一条 -32000）、按键却在 injectMousePrimaryFrame 里
        // 写进去了 —— 指针不动、点全砸在同一个点上，右键表现就是「卡住」。
        if (VirtualMouse.isActive()) {
            /* 绝对坐标设备：位置由 VirtualMouse 独占维护，写设备与画指针是同一个数。
             * 速度只在这里乘一次（系统那边不再做指针加速），所以不会再出现
             * 「两套速度互相拉扯、越走越偏」。 */
            float sp = pointerSpeed();
            VirtualMouse.moveBy(rawDx * sp, rawDy * sp);
            return;
        }

        // 虚拟外设按自己当前挂载点落地
        if (host == Host.STREAM) {
            DeXPresentation moon = moonlightOrNull();
            if (moon != null) {
                moon.applyPointerDelta(rawDx, rawDy, baseSensitivity);
            } else if (mainActivity != null) {
                mainActivity.dispatchMoonlightPointerDelta(rawDx, rawDy);
            }
        } else if (host == Host.WIRED) {
            DeXPresentation ext = wiredOrNull();
            if (ext != null) ext.applyPointerDelta(rawDx, rawDy, baseSensitivity);
            else if (mainActivity != null) mainActivity.applyPhoneCursorDelta(rawDx, rawDy);
        } else if (mainActivity != null) {
            // PHONE 或尚未登记：手机主屏是自绘光标 + hover
            /* 第一帧位移就把物理鼠标从系统手里抢过来：不抢，系统指针会继续自己走，
               走到屏幕顶端拉开通知栏、走到边上触发返回 —— 只挡住自绘箭头毫无意义。 */
            ensurePhoneMouseGrabbed();
            mainActivity.applyPhoneCursorDelta(rawDx, rawDy);
        }
    }

    /**
     * 2. 单指左键点击 / 双指右键点击
     */
    public void dispatchClick(String slotId, boolean rightClick) {
        VirtualSlot slot = slots.get(slotId);
        if (slot == null) slot = ensureSlot(slotId, "未知外设", "mouse", -1);
        slot.lastActiveTime = SystemClock.uptimeMillis();
        final Host host = hostOf(slotId);
        final DeXPresentation desktop = (host == Host.STREAM) ? moonlightOrNull() : wiredOrNull();
        final float x = (desktop != null) ? desktop.getLastMouseX() : Math.max(0f, slot.cursorX);
        final float y = (desktop != null) ? desktop.getLastMouseY() : Math.max(0f, slot.cursorY);

        int btn = rightClick ? MotionEvent.BUTTON_SECONDARY : MotionEvent.BUTTON_PRIMARY;
        if ("mouse".equals(slot.type)) {
            InputTrace.mouseClick(slot.name, x, y, btn);
        } else {
            InputTrace.touchpadClick(x, y, rightClick);
        }

        // 真设备接管：点哪儿由系统按可见指针位置决定，这里只补一次点击。
        // 同样不再看 host —— 设备绑在外屏，点击就落在外屏。
        if (VirtualMouse.isActive()) {
            VirtualMouse.click(btn);
            return;
        }

        if (host == Host.STREAM && desktop == null) {
            // 零权限本地屏还没建好：走帧队列的既有入口，别误落到手机主屏
            if (mainActivity != null) {
                if (rightClick) mainActivity.triggerMoonlightRightClick();
                else mainActivity.triggerMoonlightClick();
            }
        } else if (host == Host.PHONE || desktop == null) {
            if (mainActivity != null) mainActivity.triggerPhoneClickAtCursor(rightClick);
        } else if (rightClick) {
            desktop.performRightClickAt(x, y);
        } else {
            desktop.performClickAt(x, y);
        }
    }

    /**
     * 2.1 鼠标中键点击
     */
    public void dispatchMiddleClick(String slotId) {
        VirtualSlot slot = slots.get(slotId);
        if (slot == null) slot = ensureSlot(slotId, "未知外设", "mouse", -1);
        slot.lastActiveTime = SystemClock.uptimeMillis();
        final Host host = hostOf(slotId);
        final DeXPresentation desktop = (host == Host.STREAM) ? moonlightOrNull() : wiredOrNull();
        final float x = (desktop != null) ? desktop.getLastMouseX() : Math.max(0f, slot.cursorX);
        final float y = (desktop != null) ? desktop.getLastMouseY() : Math.max(0f, slot.cursorY);

        InputTrace.mouseClick(slot.name, x, y, MotionEvent.BUTTON_TERTIARY);
        // 手机主屏没有中键语义，虚拟外设插在手机上时这个信号自然落空。
        if (desktop != null) desktop.performMiddleClickAt(x, y);
    }

    /**
     * 3. 滚轮滚动 (区分物理鼠标标准滚轮 vs 触控板自然滚动)
     */
    public void dispatchScroll(String slotId, float vScroll, float hScroll) {
        VirtualSlot slot = slots.get(slotId);
        if (slot == null) slot = ensureSlot(slotId, "未知外设", "mouse", -1);
        slot.lastActiveTime = SystemClock.uptimeMillis();
        final Host host = hostOf(slotId);
        final DeXPresentation desktop = (host == Host.STREAM) ? moonlightOrNull() : wiredOrNull();
        final float x = (desktop != null) ? desktop.getLastMouseX() : Math.max(0f, slot.cursorX);
        final float y = (desktop != null) ? desktop.getLastMouseY() : Math.max(0f, slot.cursorY);

        // 物理鼠标滚轮：保持与标准 PC 鼠标一致（滚轮向上滚动则内容向上，向下滚动则内容向下）
        float effectiveV = vScroll;

        if ("mouse".equals(slot.type)) {
            InputTrace.mouseScroll(slot.name, x, y, effectiveV, hScroll);
        } else {
            InputTrace.touchpadScroll(x, y, effectiveV, hScroll);
        }

        if (VirtualMouse.isActive()) {
            VirtualMouse.scroll(effectiveV, hScroll);
            return;
        }

        if (host == Host.STREAM && desktop == null) {
            if (mainActivity != null) mainActivity.triggerMoonlightScroll(effectiveV, hScroll);
        } else if (host == Host.PHONE || desktop == null) {
            if (mainActivity != null) mainActivity.triggerPhoneScroll(effectiveV, hScroll);
        } else {
            desktop.performScrollAt(x, y, effectiveV, hScroll);
        }
    }

    // ==============================================================
    // 物理外设监听与热插拔识别 (InputManager.InputDeviceListener)
    // ==============================================================

    private void registerHardwareDeviceListener() {
        if (appContext == null) return;
        try {
            InputManager im = (InputManager) appContext.getSystemService(Context.INPUT_SERVICE);
            if (im != null) {
                im.registerInputDeviceListener(new InputManager.InputDeviceListener() {
                    @Override
                    public void onInputDeviceAdded(int deviceId) {
                        handleInputDeviceAdded(deviceId);
                        // 鼠标重新插上（或蓝牙重连）后原来的独占 fd 已经失效，
                        // 这里补抢一次；没抢到也不影响，MotionEvent 那条老路还在。
                        if (VirtualMouse.isActive()) {
                            try { RootInputBridge.grabPhysicalMouse(appContext); } catch (Throwable ignored) {}
                        }
                    }

                    @Override
                    public void onInputDeviceRemoved(int deviceId) {
                        handleInputDeviceRemoved(deviceId);
                    }

                    @Override
                    public void onInputDeviceChanged(int deviceId) {
                        handleInputDeviceAdded(deviceId);
                    }
                }, mainHandler);

                // 启动时同步并清理非在线物理设备
                cleanupDisconnectedHardware(im);
                int[] ids = im.getInputDeviceIds();
                if (ids != null) {
                    for (int id : ids) handleInputDeviceAdded(id);
                }
            }
        } catch (Throwable t) {
            Log.w(TAG, "registerHardwareDeviceListener err: " + t);
        }
    }

    private String classifyDeviceType(InputDevice dev) {
        String name = dev.getName();
        String lowerName = (name != null) ? name.toLowerCase(Locale.ROOT) : "";
        if (dev.supportsSource(InputDevice.SOURCE_GAMEPAD) || dev.supportsSource(InputDevice.SOURCE_JOYSTICK)
                || lowerName.contains("gamepad") || lowerName.contains("controller") || lowerName.contains("joystick")) {
            return "gamepad";
        } else if (dev.supportsSource(InputDevice.SOURCE_TOUCHSCREEN)
                && !dev.supportsSource(InputDevice.SOURCE_MOUSE)
                && !dev.supportsSource(InputDevice.SOURCE_TOUCHPAD)
                && !dev.supportsSource(InputDevice.SOURCE_TRACKBALL)
                && !lowerName.contains("mouse")) {
            // 纯外接触摸屏（到这里的已由调用方滤掉内置面板）：以前会被下面
            // SOURCE_CLASS_POINTER 那条兜底误认成鼠标，槽位与路由全错。
            // 键鼠触摸一体设备仍归鼠标，触摸部分由绑屏逻辑另行认领。
            return "touch";
        } else if (dev.supportsSource(InputDevice.SOURCE_MOUSE) || dev.supportsSource(InputDevice.SOURCE_TOUCHPAD)
                || dev.supportsSource(InputDevice.SOURCE_TRACKBALL)
                || (dev.getSources() & InputDevice.SOURCE_CLASS_POINTER) != 0
                || lowerName.contains("mouse") || lowerName.contains("touchpad")) {
            return "mouse";
        } else if (dev.supportsSource(InputDevice.SOURCE_KEYBOARD) || lowerName.contains("keyboard")) {
            return "keyboard";
        }
        return "peripheral";
    }

    private void cleanupDisconnectedHardware(InputManager im) {
        if (im == null) return;
        int[] ids = im.getInputDeviceIds();
        java.util.Set<String> activeCanonicalIds = new java.util.HashSet<>();
        if (ids != null) {
            for (int id : ids) {
                InputDevice dev = im.getInputDevice(id);
                if (dev != null && !dev.isVirtual() && dev.isExternal()) {
                    String type = classifyDeviceType(dev);
                    activeCanonicalIds.add(resolveCanonicalSlotId(dev, type));
                }
            }
        }
        // 只清理「由硬件监听登记过」的插槽。Moonlight 客户端、黑屏触控这类
        // 不是硬件枚举出来的卡片，绝不能被一次热插拔扫描顺手删掉。
        for (String slotId : new ArrayList<>(hardwareSlotIds)) {
            if (activeCanonicalIds.contains(slotId)) continue;
            hardwareSlotIds.remove(slotId);
            if (slotId.equals(pointerSlotId)) pointerSlotId = null;
            if (slots.remove(slotId) != null) {
                Log.i(TAG, "外设已拔出并注销: " + slotId);
                notifyPeripheralUi();
            }
        }
    }

    private void handleInputDeviceAdded(int deviceId) {
        if (appContext == null) return;
        try {
            InputManager im = (InputManager) appContext.getSystemService(Context.INPUT_SERVICE);
            if (im == null) return;
            InputDevice dev = im.getInputDevice(deviceId);
            if (dev == null || dev.isVirtual()) return;

            if (!dev.isExternal()) {
                return;
            }

            String name = dev.getName();
            // 大脑自己造出来的虚拟外设，绝不能反过来当成「又一枚真实外设」接入。
            // 少了这一行就是自激：写事件 → 自家设备 → 又被接入 → 再写一遍，队列灌满即 ANR。
            if (VirtualMouse.isOwnDevice(name)) {
                Log.i(TAG, "跳过自家虚拟外设: [" + name + "] deviceId=" + deviceId);
                return;
            }
            String type = classifyDeviceType(dev);
            String canonicalId = resolveCanonicalSlotId(dev, type);

            int defaultTarget = (dexPresentation != null && dexPresentation.isShowing())
                    ? dexPresentation.getDisplay().getDisplayId() : 0;

            boolean alreadyExisted = slots.containsKey(canonicalId);
            VirtualSlot slot = ensureSlot(canonicalId, name, type, defaultTarget);
            slot.name = name;
            slot.type = type;
            hardwareSlotIds.add(canonicalId);
            // 指针设备一插上就记住身份：PointerCapture 通道丢 device 时靠它续接。
            if ("mouse".equals(type)) {
                // 有特权就让外设大脑造真设备（root: app_process→/dev/uinput，
                // Shizuku: UserService→/dev/uinput），并绑到外屏；没特权时
                // start() 直接失败，下面继续用 PointerCapture 的内存级虚拟管道。
                VirtualMouse.attachContext(appContext);
                VirtualMouse.startAsync(appContext);
                rememberPointerSlot(canonicalId);
                // 鼠标在场就独占捕获：系统指针不画，输入只有大脑一个消费者，
                // 落地由大脑按挂载点合成给目标 WebView（点击/拖拽/滚轮都走这一条）。
                if (mainActivity != null) {
                    mainActivity.runOnUiThread(() -> mainActivity.ensurePointerCapture());
                }
            }

            // 外接触摸屏：把它的 descriptor 绑到内容所在的那块屏，系统直接派发
            // 触摸（多指、手势、原生应用全通）。不分类型认领：一体设备归了鼠标
            // 也要绑，鼠标部分反正已被大脑独占，绑了不影响。
            // 屏还没建好时 target<=0，先跳过，屏就绪钩子会补绑。
            if (dev.supportsSource(InputDevice.SOURCE_TOUCHSCREEN)) {
                int target = fingerDisplayId();
                if (target <= 0) target = defaultTarget;
                if (target > 0) bindTouchscreenToDisplay(appContext, dev, target);
            }

            if (!alreadyExisted) {
                Log.i(TAG, "外设大脑识别到硬件接入: [" + name + "] 类型=" + type + " 槽位=" + canonicalId);
                CanghaiErrorCollector.logExt("外设大脑识别设备接入: [" + name + "] (" + type + ")");
                if (mainActivity != null) {
                    mainActivity.runOnUiThread(() -> mainActivity.syncToPhoneWeb());
                }
            }
        } catch (Throwable ignored) {}
    }

    private void handleInputDeviceRemoved(int deviceId) {
        Log.i(TAG, "外设热插拔移除: deviceId=" + deviceId);
        CanghaiErrorCollector.logExt("外设已拔出: deviceId=" + deviceId);
        try {
            if (appContext != null) {
                InputManager im = (InputManager) appContext.getSystemService(Context.INPUT_SERVICE);
                cleanupDisconnectedHardware(im);
            }
        } catch (Throwable ignored) {}
        if (mainActivity != null) {
            mainActivity.runOnUiThread(() -> mainActivity.syncToPhoneWeb());
        }
    }

    /**
     * 这条 BACK 到底是不是「物理鼠标右键被系统翻译出来的」。
     *
     * 三星 One UI 在系统层把鼠标右键翻成 KEYCODE_BACK，事件到 Activity 时已经没有
     * 可归属的设备。真正的外接键盘按 BACK 会带 device（且有 SOURCE_KEYBOARD），
     * 必须放行；只有「无 device 的 BACK」与「纯指针设备」才认成右键。
     */
    private boolean isSystemMouseBack(KeyEvent event) {
        if (event == null) return false;
        InputDevice dev = event.getDevice();
        if (dev == null) {
            // 系统翻译产生的 BACK：只有这台机器上确实有指针外设时才接管，
            // 否则会把普通返回键吞掉。
            return pointerSlotId != null && slots.containsKey(pointerSlotId);
        }
        int sources = dev.getSources();
        boolean isMouse = (sources & InputDevice.SOURCE_MOUSE) == InputDevice.SOURCE_MOUSE;
        boolean isKeyboard = (sources & InputDevice.SOURCE_KEYBOARD) == InputDevice.SOURCE_KEYBOARD;
        return isMouse && !isKeyboard;
    }

    /** 右键按当前挂载点落地：和 dispatchPhysicalButton 走同一个端，不重复一套。 */
    private boolean dispatchPointerRightClick() {
        VirtualSlot slot = (pointerSlotId != null) ? slots.get(pointerSlotId) : null;
        if (slot == null) {
            for (VirtualSlot s : slots.values()) {
                if ("mouse".equals(s.type) && !MOONLIGHT_REMOTE_SLOT.equals(s.id)) {
                    slot = s;
                    break;
                }
            }
        }
        if (slot == null) return false;

        slot.lastActiveTime = SystemClock.uptimeMillis();
        syncMount(slot);
        focusCursor(slot.mountHost);
        InputTrace.mouseClick(slot.name, slot.cursorX, slot.cursorY,
                MotionEvent.BUTTON_SECONDARY, destLabel(slot, slot.mountHost));
        // 按下与抬起都走同一个端：串流屏需要成对注入，本地屏按下即弹菜单。
        slot.surface.button(MotionEvent.BUTTON_SECONDARY, true, slot.cursorX, slot.cursorY);
        slot.surface.button(MotionEvent.BUTTON_SECONDARY, false, slot.cursorX, slot.cursorY);
        return true;
    }

    public boolean interceptKeyEvent(KeyEvent event) {
        if (event == null) return false;
        int kc = event.getKeyCode();

        // 三星 One UI 在系统层把「物理鼠标右键」翻译成 BACK：到 Activity 时已经
        // 没有可归属的外接设备，放行就会变成手机返回、甚至弹「再按一次退出」。
        // 指针外设存在时它只能是右键，按挂载点还原，绝不落到手机 Activity 上。
        if (kc == KeyEvent.KEYCODE_BACK && isSystemMouseBack(event)) {
            return dispatchPointerRightClick();
        }

        // 1. 最高优先级全局拦截：键盘 Windows 键 / Mac Command 键 (META_LEFT/RIGHT)、手柄 Home 键 (BUTTON_MODE)
        // 统一拦截并映射为大屏弹出/关闭应用台，严禁系统退回手机桌面！
        if (kc == KeyEvent.KEYCODE_META_LEFT || kc == KeyEvent.KEYCODE_META_RIGHT
                || kc == KeyEvent.KEYCODE_BUTTON_MODE) {
            if (dexPresentation != null && dexPresentation.isShowing()) {
                InputTrace.systemKey("按键", event, "桌面启动台");
                if (event.getAction() == KeyEvent.ACTION_UP && !event.isCanceled()) {
                    dexPresentation.toggleLaunchpad();
                }
                return true;
            }
        }

        InputDevice dev = event.getDevice();
        if (dev == null || !dev.isExternal()) {
            return false;
        }

        // 外接设备发出的 HOME 键同样拦截用于切换应用台
        if (kc == KeyEvent.KEYCODE_HOME) {
            if (dexPresentation != null && dexPresentation.isShowing()) {
                InputTrace.systemKey("按键", event, "桌面启动台");
                if (event.getAction() == KeyEvent.ACTION_UP && !event.isCanceled()) {
                    dexPresentation.toggleLaunchpad();
                }
                return true;
            }
        }

        String desc = dev.getDescriptor();
        String devName = (dev.getName() != null) ? dev.getName() : "外接设备";
        VirtualSlot slot = physicalSlot(desc, devName, "keyboard");
        final Host host = liveHostOf(slot);
        // 系统给的按键原样记一份，落点写清楚；面板上不再各处各记一条。
        InputTrace.systemKey("按键", event, destLabel(slot, host));
        // 插在手机主屏：硬件键本来就会送到手机 Activity，大脑放行系统即可。
        if (host == Host.PHONE) return false;

        if (host == Host.STREAM) {
            DeXPresentation moon = moonlightOrNull();
            if (moon != null) moon.dispatchExternalKeyEvent(event);
            else if (mainActivity != null) mainActivity.injectMoonlightKey(event);
        } else {
            DeXPresentation ext = wiredOrNull();
            if (ext != null) ext.dispatchExternalKeyEvent(event);
        }
        return true;
    }

    public boolean interceptGenericMotionEvent(MotionEvent event) {
        if (event == null) return false;
        InputDevice dev = event.getDevice();
        // 手机机身硬件绝不拦截
        if (dev == null || !dev.isExternal()) {
            return false;
        }

        boolean isGamepad = (event.getSource() & InputDevice.SOURCE_JOYSTICK) != 0
                || (event.getSource() & InputDevice.SOURCE_GAMEPAD) != 0;
        // 鼠标位移/滚轮由 dispatchPhysicalMotion / Scroll 处理，这里只接手柄。
        if (!isGamepad) return false;

        String desc = dev.getDescriptor();
        String devName = (dev.getName() != null) ? dev.getName() : "外接设备";
        VirtualSlot slot = physicalSlot(desc, devName, "gamepad");
        final Host host = liveHostOf(slot);
        // 插在手机主屏：放行给手机系统自己处理
        if (host == Host.PHONE) return false;

        InputTrace.motion("外设手柄(" + devName + ")", event);
        DeXPresentation desktop = (host == Host.STREAM) ? moonlightOrNull() : wiredOrNull();
        if (desktop != null) desktop.dispatchExternalMotionEvent(event);
        return true;
    }
}
