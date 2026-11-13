package com.aosen.shizukudex;

/**
 * 当前 Moonlight 串流目标的唯一状态源。
 *
 * 由串流会话回调驱动（连入 / 建屏 / 断开），Root、Shizuku 与零权限三种权限路径
 * 只负责「把画面送进哪块屏」，不各自维护一套目标判断。外设路由、前端设备列表、
 * 名称显示、坐标映射一律只读这里，不再靠屏幕名字反查，也不把 UI 别名当系统 displayId。
 */
public final class StreamTarget {
    private static final StreamTarget INSTANCE = new StreamTarget();

    /**
     * UI 下拉框 / 持久化里代表「当前串流目标」的内部别名。
     * 真实 displayId 每次连接都会变，没法存；这个值只在本应用内部流转，
     * 传给系统 API 前必须经 resolveDisplayId() 换成真实 id。
     */
    public static final int UI_ID = 1_000_000;

    public static StreamTarget get() {
        return INSTANCE;
    }

    private boolean active;
    private boolean displayReady;
    private int displayId = -1;
    private int width;
    private int height;
    private String clientName = "";
    private String route = "";

    private StreamTarget() {}

    /** 客户端连入：此时虚拟屏可能还没建好，先记录会话身份。 */
    public synchronized void onSessionStarted(String name, int w, int h) {
        active = true;
        clientName = (name == null || name.trim().isEmpty()) ? "Moonlight 客户端" : name.trim();
        // 客户端协商分辨率只是临时值；屏一旦建好，真实像素由 onDisplayReady 说了算。
        if (!displayReady) {
            width = w;
            height = h;
        }
    }

    /** 虚拟屏就绪：真实 displayId 由建屏方（本地 / Root / Shizuku）上报。 */
    public synchronized void onDisplayReady(int id, int w, int h, String routeName) {
        displayReady = id > 0;
        displayId = id;
        if (w > 0 && h > 0) {
            width = w;
            height = h;
        }
        route = routeName == null ? "" : routeName;
    }

    /** 会话全部结束：状态归零，旧 displayId 立刻失效。 */
    public synchronized void clear() {
        active = false;
        displayReady = false;
        displayId = -1;
        width = 0;
        height = 0;
        clientName = "";
        route = "";
    }

    public synchronized boolean isActive() {
        return active;
    }

    /** 当前串流目标的真实 displayId；屏未就绪时返回 -1。 */
    public synchronized int getDisplayId() {
        return displayReady ? displayId : -1;
    }

    public synchronized int getWidth() {
        return width;
    }

    public synchronized int getHeight() {
        return height;
    }

    public synchronized String getClientName() {
        return clientName;
    }

    public synchronized String getRoute() {
        return route;
    }

    /** 前端设备卡、外设目标下拉框用的名字：串流客户端名字优先。 */
    public synchronized String getName() {
        if (clientName.isEmpty()) return "Moonlight 串流桌面";
        return "Moonlight 串流桌面 · " + clientName;
    }

    /** 内部别名或当前真实 displayId 都算串流目标，不查屏幕名字。 */
    public synchronized boolean isStreamTarget(int id) {
        if (id == UI_ID) return true;
        return displayReady && displayId > 0 && id == displayId;
    }

    /** 命中串流目标时返回它的名字，否则返回 null 交给外接屏 / 手机屏解析。 */
    public synchronized String displayNameFor(int id) {
        return isStreamTarget(id) ? getName() : null;
    }

    /** 这个 id 是否只是 UI 别名，不代表任何系统屏幕。 */
    public boolean isUiAlias(int id) {
        return id == UI_ID;
    }

    /** 把 UI 别名换成真实 displayId；屏未就绪返回 -1，绝不把假 id 交给系统 API。 */
    public synchronized int resolveDisplayId(int id) {
        if (!isUiAlias(id)) return id;
        return displayReady ? displayId : -1;
    }
}
