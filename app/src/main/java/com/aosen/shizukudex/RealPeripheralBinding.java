package com.aosen.shizukudex;

import android.content.Context;

import org.json.JSONObject;

/**
 * Root / Shizuku 下「真实外设 ↔ 真实外屏」的绑定关系，大脑层唯一信源。
 *
 * <p>大脑层做的事：EVIOCGRAB 独占那颗真实鼠标，造一颗 uinput 虚拟鼠标当投递代理，
 * 再用 addUniqueIdAssociationByDescriptor 把这条输入流插到承载内容的虚拟屏。
 * 于是：
 * <ul>
 *   <li>虚拟屏（Aosen_VD_*）里的虚拟外设只是大脑的投递手段，不是外设本身；</li>
 *   <li>自建虚拟屏只是画布，不是用户看到的「外屏」；</li>
 *   <li>软件端要显示、要跟着走的，是「那颗真实鼠标 → 那块真实外屏」。</li>
 * </ul>
 *
 * <p>这条关系是可写的唯一信源：用户在手机端把鼠标拖到别的屏，改的是这里；
 * 软件端卡片、外接屏光标、大脑层的输入投递都读同一个值，不再各存一份。
 * 非特权环境一律返回「无真实外屏」，一行都不参与，避免把非 Root 链路带乱。
 */
public final class RealPeripheralBinding {

    private static final String TAG = "RealPeripheralBinding";
    private static final RealPeripheralBinding INSTANCE = new RealPeripheralBinding();

    public static RealPeripheralBinding get() { return INSTANCE; }

    /** MIN_VALUE = 用户没改过（按物理事实推断）；0 = 用户拖回手机；>0 = 用户指定的屏幕归一 key。 */
    private volatile int userTarget = Integer.MIN_VALUE;
    /** 展示出去的那张真实鼠标卡片的 id，拖拽回来时靠它对号入座。 */
    private volatile String boundSlotId = "";
    private volatile int cachedDisplayId = -1;
    private volatile String cachedDisplayName = "手机自身屏幕";
    private volatile long resolvedAt = 0L;
    private volatile String lastSignature = "";
    private volatile Context appContext;
    /** 手机端那支自绘光标是不是我们点亮的：只在离开手机状态时收一次。 */
    private volatile boolean phoneCursorShown = false;

    private RealPeripheralBinding() {}

    /** 只有 Root / Shizuku 才存在「大脑层绑屏」这件事。 */
    public boolean isPrivileged() {
        return PrivilegeRoute.mode() != PrivilegeRoute.Mode.NONE;
    }

    /** 大脑当前握着的真实外设；没握住返回空串（此时没有真实绑定可显示）。 */
    public String realDeviceName() {
        try {
            String n = RootInputBridge.grabbedName();
            return n == null ? "" : n;
        } catch (Throwable ignored) {
            return "";
        }
    }

    /**
     * 真实外屏：用户改过就按用户改的来；否则优先物理外接屏（显式排掉自建虚拟屏），
     * 没有物理屏时，用户正在看的是 Moonlight 串流桌面。
     * 绝不返回 Aosen_VD_Desk_* / Aosen_VD_Moonlight_* 这类自建虚拟屏。
     */
    private void resolve(boolean force) {
        long now = android.os.SystemClock.uptimeMillis();
        if (!force && now - resolvedAt < 1000L) return;
        resolvedAt = now;
        if (userTarget == 0) {
            cachedDisplayId = -1;
            cachedDisplayName = "手机自身屏幕";
            return;
        }
        int id = -1;
        String name = "手机自身屏幕";
        if (userTarget > 0) {
            try {
                int real = ScreenRegistry.get().realIdOf(userTarget);
                if (real > 0) {
                    id = real;
                    name = ScreenRegistry.get().nameOf(real);
                }
            } catch (Throwable ignored) {}
        }
        if (id <= 0) {
            try {
                for (ScreenRegistry.Screen s : ScreenRegistry.get().all()) {
                    if (!s.isWired()) continue;
                    String n = (s.name == null) ? "" : s.name;
                    if (n.contains("Aosen_VD")) continue;
                    int real = (s.realDisplayId > 0) ? s.realDisplayId : s.key;
                    if (real <= 0) continue;
                    id = real;
                    name = n.isEmpty() ? "外接显示器" : n;
                    break;
                }
            } catch (Throwable ignored) {}
        }
        if (id <= 0) {
            try {
                int stream = ScreenRegistry.get().realIdOf(StreamTarget.UI_ID);
                if (stream > 0 && ScreenRegistry.get().isStreamLive()) {
                    id = stream;
                    name = StreamTarget.get().getName();
                }
            } catch (Throwable ignored) {}
        }
        cachedDisplayId = id;
        cachedDisplayName = name;
    }

    /** 真实外屏的系统 displayId；已绑到手机或没有屏时返回 -1。 */
    public int realDisplayId() {
        if (!isPrivileged()) return -1;
        resolve(false);
        return cachedDisplayId;
    }

    public String realDisplayName() {
        int id = realDisplayId();
        return id > 0 ? cachedDisplayName : "手机自身屏幕";
    }

    /** 给前端设备卡用的归一 key：串流屏折成 UI_ID，物理屏就是真实 id，手机是 0。 */
    public int uiKey() {
        int id = realDisplayId();
        if (id <= 0) return 0;
        return ScreenRegistry.get().normalize(id);
    }

    public String kind() {
        int id = realDisplayId();
        if (id <= 0) return "phone";
        return StreamTarget.get().isStreamTarget(id) ? "stream" : "wired";
    }

    public String describe() {
        String dev = realDeviceName();
        if (dev.isEmpty()) dev = "真实鼠标";
        int id = realDisplayId();
        if (id <= 0) return dev + " → 手机自身屏幕";
        return dev + " → " + realDisplayName() + "（display " + id + "）";
    }

    public JSONObject toJson() {
        JSONObject o = new JSONObject();
        try {
            String dev = realDeviceName();
            int id = realDisplayId();
            o.put("deviceName", dev.isEmpty() ? "真实鼠标" : dev);
            o.put("displayId", id);
            o.put("displayKey", uiKey());
            o.put("displayName", id > 0 ? realDisplayName() : "手机自身屏幕");
            o.put("kind", id > 0 ? kind() : "phone");
            o.put("bound", id > 0 && !dev.isEmpty());
            o.put("describe", describe());
        } catch (Throwable ignored) {}
        return o;
    }

    /** 屏幕接/断、串流起停后让关系重算一次。 */
    public void invalidate() {
        resolvedAt = 0L;
        lastSignature = "";
    }

    /** 真实鼠标已被用户拖回手机：输入必须真的离开外屏，不能再往画布注入。 */
    public boolean isPhoneBound() {
        return isPrivileged() && userTarget == 0;
    }

    /** 展示时记住这张卡就是真实绑定那颗鼠标，拖拽回来才能对上号。 */
    public void setBoundSlotId(String slotId) {
        if (slotId != null) boundSlotId = slotId;
    }

    public boolean ownsSlot(String slotId) {
        if (slotId == null || slotId.isEmpty() || !isPrivileged()) return false;
        return slotId.equals(boundSlotId) || "grabbed_real_mouse".equals(slotId);
    }

    /**
     * 用户在手机端改绑：显示、光标、大脑层的输入投递一起换，不再有第二套状态。
     * displayKey 是归一 key，0 = 手机自身屏幕。内部投递画布仍是原来那块虚拟屏，
     * 它只是大脑的投递手段，不是用户看到的外屏。
     */
    public void setUserTarget(int displayKey) {
        if (!isPrivileged()) return;
        int oldReal = realDisplayId();
        boolean wasPhone = isPhoneBound();
        userTarget = displayKey;
        invalidate();
        boolean external = displayKey > 0;
        int newReal = realDisplayId();
        if (oldReal > 0 && (!external || oldReal != newReal)) {
            try {
                com.alkaidlab.sunshine.service.RemoteCursorOverlay.INSTANCE.hideOnly(oldReal);
            } catch (Throwable ignored) {}
        }
        if (wasPhone && external) hidePhoneCursor();
        if (!external) {
            /* 拖回手机就立刻把图标点出来，不必等下一帧鼠标移动。 */
            float[] p = VirtualMouse.position();
            showPhoneCursor(p != null ? p[0] : 960f, p != null ? p[1] : 540f);
        }
        try {
            CanghaiErrorCollector.logExt("[真实绑定] 用户改绑 → " + describe());
        } catch (Throwable ignored) {}
        /* 显示状态已经同步改完，卡片立刻就能动；真正改输入关联要跑 root / shizuku
         * shell，放到子线程，别把 UI 线程压住。 */
        new Thread(() -> {
            boolean ok;
            try {
                ok = VirtualMouse.rebindToCanvas(external);
            } catch (Throwable t) {
                ok = false;
            }
            if (!ok) {
                try {
                    CanghaiErrorCollector.logExt("[真实绑定] 输入关联未生效，鼠标可能仍在原屏");
                } catch (Throwable ignored) {}
            }
        }, "real-binding-rebind").start();
    }

    /**
     * 真实鼠标每动一帧，就按这条真实关系把光标画到真实外屏上。
     * 这是「软件端显示真实关系」的落地点，不走 Moonlight 网络事件那条链
     * （外接屏模式下那条链根本不会被调用，所以以前外接屏一直没有鼠标）。
     */
    public void showRealCursor(Context ctx, float x, float y) {
        if (!isPrivileged()) return;
        if (ctx != null) appContext = ctx.getApplicationContext();
        /* 用户把真实鼠标拖回手机：这块屏上没有自建画布，也不该挂 overlay 窗口。
         * 手机端本来就有自己的自绘光标层（MainActivity.phoneCursorView，非 Root
         * 走的就是它），让它跟着同一个位置信源走，图标才会出现。 */
        if (isPhoneBound()) {
            showPhoneCursor(x, y);
            return;
        }
        if (phoneCursorShown) hidePhoneCursor();
        int id = realDisplayId();
        if (id <= 0) return;
        Context app = appContext;
        if (app == null) return;
        float[] p = scaleTo(id, x, y);
        try {
            com.alkaidlab.sunshine.service.RemoteCursorOverlay.INSTANCE
                    .show(app, id, p[0], p[1]);
        } catch (Throwable ignored) {}
        noteIfChanged();
    }

    /** 画布像素 → 手机自身屏幕像素：拖回手机后鼠标仍要能走遍整块手机屏。 */
    private float[] phonePoint(float x, float y) {
        float sx = 1f, sy = 1f;
        try {
            MainActivity a = MainActivity.getInstance();
            if (a != null) {
                int[] space = VirtualMouse.space();
                android.util.DisplayMetrics dm = a.getResources().getDisplayMetrics();
                if (space[0] > 1 && space[1] > 1 && dm.widthPixels > 1 && dm.heightPixels > 1) {
                    sx = (float) dm.widthPixels / space[0];
                    sy = (float) dm.heightPixels / space[1];
                }
            }
        } catch (Throwable ignored) {}
        return new float[]{x * sx, y * sy};
    }

    /** 点亮手机端原有的自绘光标层；那个 View 属于 MainActivity，必须回主线程。 */
    private void showPhoneCursor(float x, float y) {
        MainActivity a = MainActivity.getInstance();
        if (a == null) return;
        float[] p = phonePoint(x, y);
        final float px = p[0], py = p[1];
        phoneCursorShown = true;
        try {
            a.runOnUiThread(() -> a.updatePhoneCursor(px, py));
        } catch (Throwable ignored) {}
    }

    private void hidePhoneCursor() {
        phoneCursorShown = false;
        try {
            MainActivity a = MainActivity.getInstance();
            if (a != null) a.runOnUiThread(a::hidePhoneCursor);
        } catch (Throwable ignored) {}
    }

    /** 真实鼠标拖回手机时按住移动：把「按住」这一帧补给首页 WebView。 */
    public void deliverPhoneDrag(float canvasX, float canvasY) {
        MainActivity a = MainActivity.getInstance();
        if (a == null) return;
        float[] p = phonePoint(canvasX, canvasY);
        a.phoneMouseMove(p[0], p[1]);
    }

    /** 真实鼠标拖回手机时滚轮：同样补给首页 WebView。 */
    public void deliverPhoneScroll(float v, float h) {
        MainActivity a = MainActivity.getInstance();
        if (a == null) return;
        a.phoneMouseScroll(a.getPhoneCursorX(), a.getPhoneCursorY(), v, h);
    }

    /**
     * 真实鼠标拖回手机时按下 / 抬起。
     *
     * <p>手机屏没有虚拟画布，系统那根指针又带系统加速、和自绘光标早就分叉，
     * 写 uinput 的 BTN_LEFT 会点在我们看不见的位置上（表现就是「按钮点不动」）。
     *
     * <p>这里必须复用手机 surface 已有的那套落点（phoneMouseDown / phoneMouseUp /
     * triggerPhoneClickAtCursor）：坐标口径、downTime 配对、「按下未抬起就长按」的清理
     * 都在那边，自己再造一套只会有两份不一致的行为。
     */
    public void deliverPhoneClick(int androidButton, boolean down) {
        MainActivity a = MainActivity.getInstance();
        if (a == null) return;
        final float x = a.getPhoneCursorX();
        final float y = a.getPhoneCursorY();
        if (androidButton == android.view.MotionEvent.BUTTON_PRIMARY) {
            if (down) a.phoneMouseDown(x, y);
            else a.phoneMouseUp(x, y);
        } else if (androidButton == android.view.MotionEvent.BUTTON_SECONDARY) {
            /* 手机首页只有一个「点击」语义：右键就是长按菜单。 */
            if (down) a.triggerPhoneClickAtCursor(true);
        }
    }

    /** 虚拟屏像素 → 真实外屏像素；两者一致时系数就是 1。 */
    private float[] scaleTo(int id, float x, float y) {
        float sx = 1f, sy = 1f;
        try {
            int[] space = VirtualMouse.space();
            ScreenRegistry.Screen s = ScreenRegistry.get().find(id);
            if (s != null && space[0] > 1 && space[1] > 1 && s.width > 1 && s.height > 1) {
                float rx = (float) s.width / space[0];
                float ry = (float) s.height / space[1];
                if (Math.abs(rx - 1f) > 0.02f) sx = rx;
                if (Math.abs(ry - 1f) > 0.02f) sy = ry;
            }
        } catch (Throwable ignored) {}
        return new float[]{x * sx, y * sy};
    }

    /** 关系变了才在调试面板记一行，避免每帧刷屏。 */
    private void noteIfChanged() {
        String sig = describe();
        if (sig.equals(lastSignature)) return;
        lastSignature = sig;
        try {
            CanghaiErrorCollector.logExt("[真实绑定] " + sig);
        } catch (Throwable ignored) {}
    }
}
