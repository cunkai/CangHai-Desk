package com.aosen.shizukudex;

import android.content.Context;
import android.hardware.display.DisplayManager;
import android.os.Handler;
import android.os.Looper;
import android.util.DisplayMetrics;
import android.util.Log;
import android.view.Display;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 沧海屏幕注册表 —— 全应用唯一的「有哪些屏」来源。
 *
 * 三类屏各自只有一个登记口：
 *  1. 物理屏：DisplayManager 枚举 + DisplayListener，接/断/改分辨率都只在这里 diff；
 *  2. Moonlight 屏：由串流会话（连入 / 建屏 / 结束）登记，id 固定用 StreamTarget.UI_ID；
 *  3. 手机屏：恒存在的 key=0。
 *
 * 其它任何地方（外设大脑、首页、桌面桥、状态 JSON）都不许再自己
 * getDisplays()、自己按名字猜、自己判断「这块屏还在不在」，只准问这里。
 * 屏幕消失时由这里统一发事件，外设回流也只有一条路径。
 */
public final class ScreenRegistry {

    private static final String TAG = "ScreenRegistry";
    private static final ScreenRegistry INSTANCE = new ScreenRegistry();

    public static ScreenRegistry get() {
        return INSTANCE;
    }

    /** 落点类别；外设大脑的 Host 与之一一对应，避免两套枚举各自演化。 */
    public enum Kind { PHONE, STREAM, WIRED }

    public static final class Screen {
        /** 归一 key：0=手机，StreamTarget.UI_ID=串流，>0=物理屏真实 displayId。 */
        public final int key;
        public final Kind kind;
        public String name;
        /** 系统 displayId；串流屏建好之前是 -1。 */
        public int realDisplayId;
        public int width;
        public int height;
        public String route = "";

        Screen(int key, Kind kind, String name) {
            this.key = key;
            this.kind = kind;
            this.name = name;
            this.realDisplayId = (kind == Kind.WIRED) ? key : (kind == Kind.PHONE ? 0 : -1);
        }

        public boolean isPhone() { return kind == Kind.PHONE; }
        public boolean isStream() { return kind == Kind.STREAM; }
        public boolean isWired() { return kind == Kind.WIRED; }

        /** 这块屏现在能不能真的接收输入。 */
        public boolean isLive() {
            return kind != Kind.STREAM || realDisplayId > 0;
        }

        public String resolution() {
            if (width <= 0 || height <= 0) return "";
            return width + "×" + height;
        }

        public JSONObject toJson() {
            JSONObject o = new JSONObject();
            try {
                o.put("id", key);
                o.put("name", name);
                o.put("kind", kind == Kind.PHONE ? "phone" : (kind == Kind.STREAM ? "stream" : "wired"));
                o.put("displayId", realDisplayId);
                o.put("res", resolution());
            } catch (Throwable ignored) {}
            return o;
        }
    }

    /** 屏幕变化监听：只发「变了 / 没了」两种事实，谁要做什么由监听者自己决定。 */
    public interface Listener {
        default void onScreensChanged() {}
        /** 某块屏真实消失了（物理拔出 / 串流结束），挂在它上面的东西需要自行回退。 */
        default void onScreenGone(int key) {}
    }

    private final List<Listener> listeners = new CopyOnWriteArrayList<>();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    /** 物理屏：key 就是真实 displayId。 */
    private final Map<Integer, Screen> wired = new LinkedHashMap<>();
    /** Moonlight 串流屏：始终只有一个（当前会话）。 */
    private Screen stream;
    /** 手机屏：恒存在。 */
    private final Screen phone = new Screen(0, Kind.PHONE, "手机自身屏幕");

    private DisplayManager displayManager;
    private DisplayManager.DisplayListener displayListener;
    private boolean initialized = false;

    private ScreenRegistry() {}

    // ==============================================================
    // 初始化与物理屏管理
    // ==============================================================

    public synchronized void init(Context context) {
        if (context == null) return;
        final Context app = context.getApplicationContext();
        if (initialized) return;
        initialized = true;

        phone.name = "手机自身屏幕";

        displayManager = (DisplayManager) app.getSystemService(Context.DISPLAY_SERVICE);
        if (displayManager == null) return;

        displayListener = new DisplayManager.DisplayListener() {
            @Override
            public void onDisplayAdded(int displayId) {
                refreshWired();
                mainHandler.post(() -> detectAndMountExternal(displayId));
            }

            @Override
            public void onDisplayRemoved(int displayId) {
                refreshWired();
            }

            @Override
            public void onDisplayChanged(int displayId) {
                refreshWired();
            }
        };
        try {
            displayManager.registerDisplayListener(displayListener, mainHandler);
        } catch (Throwable t) {
            Log.w(TAG, "registerDisplayListener failed: " + t);
        }
        refreshWired();
    }

    /** 新接入一块物理屏时，让主界面去挂桌面；注册表只管登记，不管业务。 */
    private void detectAndMountExternal(int displayId) {
        try {
            MainActivity act = MainActivity.getInstance();
            if (act != null) act.onWiredDisplayAdded(displayId);
        } catch (Throwable ignored) {}
    }

    /**
     * 重新枚举物理屏并 diff 出接入 / 拔出。
     * 所有「外屏还在不在」「外屏叫什么」的问题都由这一次枚举回答。
     */
    public synchronized void refreshWired() {
        if (displayManager == null) return;

        Map<Integer, Screen> next = new LinkedHashMap<>();
        boolean changed = false;
        try {
            for (Display d : displayManager.getDisplays()) {
                if (!isConnectableWired(d)) continue;
                int id = d.getDisplayId();
                Screen s = wired.get(id);
                if (s == null) {
                    s = new Screen(id, Kind.WIRED, friendlyName(d));
                    changed = true;
                }
                s.name = friendlyName(d);
                DisplayMetrics m = new DisplayMetrics();
                try {
                    d.getRealMetrics(m);
                    if (m.widthPixels > 0) {
                        s.width = m.widthPixels;
                        s.height = m.heightPixels;
                    }
                } catch (Throwable ignored) {}
                next.put(id, s);
            }
        } catch (Throwable t) {
            Log.w(TAG, "refreshWired err: " + t);
        }

        List<Integer> gone = new ArrayList<>();
        for (Integer id : wired.keySet()) {
            if (!next.containsKey(id)) gone.add(id);
        }
        wired.clear();
        wired.putAll(next);

        for (Integer id : gone) {
            Log.i(TAG, "物理屏已移除: " + id);
            changed = true;
        }
        if (changed) notifyScreensChanged();
        for (Integer id : gone) notifyScreenGone(id);
    }

    /** 只认真正的物理/投屏外接屏：排除手机主屏、我们自建的虚拟屏。 */
    public boolean isConnectableWired(Display d) {
        if (d == null) return false;
        int id = d.getDisplayId();
        if (id == Display.DEFAULT_DISPLAY || id == 1) return false;
        if (id == StreamTarget.UI_ID) return false;
        if (stream != null && id == stream.realDisplayId) return false;
        String name = d.getName();
        // 我们自建的串流/虚拟屏都带 Aosen_VD 前缀，绝不进物理屏清单。
        return name == null || !name.contains("Aosen_VD");
    }

    private String friendlyName(Display d) {
        try {
            String n = (d == null) ? null : d.getName();
            if (n != null && !n.trim().isEmpty()) return n.trim();
        } catch (Throwable ignored) {}
        return "外接显示器";
    }

    // ==============================================================
    // Moonlight 屏管理
    // ==============================================================

    /** 客户端连入：会话身份先登记，屏还没建好（realDisplayId=-1）。 */
    public synchronized void onStreamSessionStarted(String clientName, int w, int h) {
        if (stream == null) stream = new Screen(StreamTarget.UI_ID, Kind.STREAM, "Moonlight 串流桌面");
        String n = (clientName == null || clientName.trim().isEmpty()) ? "" : clientName.trim();
        stream.name = n.isEmpty() ? "Moonlight 串流桌面" : ("Moonlight 串流桌面 · " + n);
        if (w > 0) stream.width = w;
        if (h > 0) stream.height = h;
        StreamTarget.get().onSessionStarted(clientName, w, h);
        notifyScreensChanged();
    }

    /** 虚拟屏就绪：真实 displayId 由建屏方（本地 / Root / Shizuku）上报。 */
    public synchronized void onStreamDisplayReady(int realDisplayId, int w, int h, String routeName) {
        if (stream == null) stream = new Screen(StreamTarget.UI_ID, Kind.STREAM, "Moonlight 串流桌面");
        stream.realDisplayId = realDisplayId;
        stream.route = routeName == null ? "" : routeName;
        if (w > 0) stream.width = w;
        if (h > 0) stream.height = h;
        StreamTarget.get().onDisplayReady(realDisplayId, w, h, routeName);
        Log.i(TAG, "串流屏就绪: displayId=" + realDisplayId + " " + w + "x" + h);
        /* 真设备鼠标必须绑到这块串流屏本身。
         * 以前唯一的绑屏时机是 DeXPresentation 建 Aosen_VD_Desk_<n> 的时候，
         * 纯 Moonlight 会话不建那块屏，于是 InputManager 里永远留着上次的
         * Aosen_VD_Desk_1 关联（屏早已销毁）→ DisplayId=-1 → 指针按手机内屏
         * 480dpi 积分，和 240dpi 的串流屏坐标对不上、点不到应用窗口。
         * 零权限路径不走 uinput，不能在这里建节点。 */
        if (realDisplayId > 0 && PrivilegeRoute.mode() != PrivilegeRoute.Mode.NONE) {
            try {
                VirtualMouse.onDisplayReady(ShizukuCmd.appContext(), realDisplayId,
                        w > 0 ? w / 2 : 960, h > 0 ? h / 2 : 540);
                PeripheralBrain.rebindExternalTouchscreens(ShizukuCmd.appContext(), realDisplayId);
            } catch (Throwable t) {
                Log.w(TAG, "串流屏绑定虚拟鼠标失败: " + t.getMessage());
            }
        }
        notifyScreensChanged();
    }

    /** 会话结束：串流屏注销，挂在它上面的外设必须回流。 */
    public synchronized void onStreamGone() {
        boolean had = stream != null && (stream.realDisplayId > 0 || StreamTarget.get().isActive());
        stream = null;
        StreamTarget.get().clear();
        if (had) {
            Log.i(TAG, "串流屏已注销");
            notifyScreensChanged();
            notifyScreenGone(StreamTarget.UI_ID);
        }
    }

    public synchronized Screen streamScreen() {
        return stream;
    }

    public synchronized boolean hasStream() {
        return stream != null && (stream.realDisplayId > 0 || StreamTarget.get().isActive());
    }

    /** 串流屏是否已经真的可以接输入（画面已落地）。 */
    public synchronized boolean isStreamLive() {
        return stream != null && stream.realDisplayId > 0;
    }

    // ==============================================================
    // 总屏幕查询
    // ==============================================================

    /** 所有现存屏：手机 + 物理屏 + 串流屏。 */
    public synchronized List<Screen> all() {
        List<Screen> out = new ArrayList<>();
        out.add(phone);
        out.addAll(wired.values());
        if (hasStream()) out.add(stream);
        return out;
    }

    /** 真实 displayId → 归一 key（串流屏统一折成 UI_ID）。 */
    public synchronized int normalize(int rawId) {
        if (rawId <= 0) return 0;
        if (rawId == StreamTarget.UI_ID) return StreamTarget.UI_ID;
        if (stream != null && rawId == stream.realDisplayId) return StreamTarget.UI_ID;
        return rawId;
    }

    public synchronized Screen find(int rawId) {
        int key = normalize(rawId);
        if (key == 0) return phone;
        if (key == StreamTarget.UI_ID) return hasStream() ? stream : null;
        return wired.get(key);
    }

    public synchronized Kind kindOf(int rawId) {
        Screen s = find(rawId);
        if (s != null) return s.kind;
        // 找不到就说明这块屏已经不在了；绝不把陌生 id 当成有线外屏留在路由里。
        return Kind.PHONE;
    }

    public synchronized String nameOf(int rawId) {
        Screen s = find(rawId);
        if (s != null) return s.name;
        try {
            Display d = (displayManager != null) ? displayManager.getDisplay(rawId) : null;
            if (d != null) return friendlyName(d);
        } catch (Throwable ignored) {}
        return "已断开的屏幕";
    }

    /** 交给系统 API 的真实 id；串流屏未就绪返回 -1，绝不把别名当 displayId。 */
    public synchronized int realIdOf(int rawId) {
        Screen s = find(rawId);
        if (s == null) return rawId <= 0 ? 0 : -1;
        return s.realDisplayId;
    }

    public synchronized boolean isAlive(int rawId) {
        Screen s = find(rawId);
        return s != null && s.isLive();
    }

    public synchronized boolean hasWired() {
        return !wired.isEmpty();
    }

    /**
     * 一块屏没了以后，挂在上面的东西该去哪：现存物理屏 > 串流屏 > 手机。
     * 这是全应用唯一的回退规则。
     */
    public synchronized int fallbackFor(int excludedKey) {
        int ex = normalize(excludedKey);
        for (Screen s : wired.values()) {
            if (s.key != ex) return s.key;
        }
        if (hasStream() && StreamTarget.UI_ID != ex) return StreamTarget.UI_ID;
        return 0;
    }

    // ==============================================================
    // 对外 JSON（首页 / 状态推送唯一出口）
    // ==============================================================

    public synchronized JSONArray toJsonArray() {
        JSONArray arr = new JSONArray();
        for (Screen s : all()) arr.put(s.toJson());
        return arr;
    }

    public synchronized JSONArray devicesJson(int activeKey) {
        JSONArray arr = new JSONArray();
        try {
            for (Screen s : all()) {
                if (s.isPhone()) continue; // 手机屏单独用 device 卡描述，这里只发外接屏
                JSONObject o = s.toJson();
                o.put("active", normalize(activeKey) == s.key);
                arr.put(o);
            }
        } catch (Throwable ignored) {}
        return arr;
    }

    public void addListener(Listener l) {
        if (l != null && !listeners.contains(l)) listeners.add(l);
    }

    public void removeListener(Listener l) {
        listeners.remove(l);
    }

    private void notifyScreensChanged() {
        final List<Listener> copy = new ArrayList<>(listeners);
        mainHandler.post(() -> {
            for (Listener l : copy) {
                try { l.onScreensChanged(); } catch (Throwable ignored) {}
            }
        });
    }

    private void notifyScreenGone(int key) {
        final List<Listener> copy = new ArrayList<>(listeners);
        mainHandler.post(() -> {
            for (Listener l : copy) {
                try { l.onScreenGone(key); } catch (Throwable ignored) {}
            }
        });
    }
}
