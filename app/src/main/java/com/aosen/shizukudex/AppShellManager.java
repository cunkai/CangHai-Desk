package com.aosen.shizukudex;

import android.app.Activity;
import android.os.Handler;
import android.os.Looper;
import android.util.DisplayMetrics;
import android.util.Log;
import android.view.Display;
import android.widget.FrameLayout;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 系统应用窗口管理（自由窗口版）。
 *
 * 设计变更（旧版：一窗口一虚拟屏 + TextureView 采集 + 自绘标题栏）：
 *  - 旧模型每个窗口都要建一块独立 VirtualDisplay，而一台物理键鼠/手柄同一时刻
 *    只能关联一块屏，于是"谁最后建屏谁抢走输入"，壳里打开的应用经常收不到手柄，
 *    桌面反而被抢走输入而无法点击 —— 这是多屏绑定模型的天花板。
 *  - 新模型：系统应用以 freeform 窗口直接跑在「桌面所在的那块虚拟屏」上，
 *    标题栏、拖动、缩放、层叠全部交给系统。桌面与所有应用同屏，只有一块屏、
 *    一个焦点、一个输入源，输入争抢问题从根上消失。
 *
 * 分工：
 *  - 沧海自己的 HTML 应用走 WebView（dexhome/index.html），标题栏由网页绘制，与本类无关；
 *  - 系统 APK 走本类：系统窗口 + 系统标题栏。
 *
 * 对外接口保持与旧版一致，仅调用方 DexHomeActivity 使用：
 *   open / close / close / closeActive / minimize / minimizeActive /
 *   toggleMaximize / toggleMaximizeActive / hideAllForLaunchpad /
 *   restoreFromLaunchpad / releaseAll。
 */
public final class AppShellManager {

    private static final String TAG = "ShizukuDeX_SysWin";

    /**
     * 沧海自己的 HTML 应用在窗口表里的 comp 形如 "html:piano"。
     *
     * 它们以前只是桌面页面里的一个 div，永远被系统应用的 Task 压住；
     * 现在每个 HTML 应用也开一个 freeform Task（{@link HtmlAppActivity}），
     * 与系统应用共用同一套窗口层级。
     */
    public static final String HTML_PREFIX = "html:";

    public static String htmlComp(String appId) {
        return HTML_PREFIX + appId;
    }

    public static boolean isHtmlComp(String comp) {
        return comp != null && comp.startsWith(HTML_PREFIX);
    }

    /** 全屏状态变化回调：桌面据此隐藏 / 恢复底部 Dock。 */
    public interface FullscreenCallback {
        void onFullscreenChanged(boolean fullscreen);
    }

    /**
     * 旧接口：返回「当前应用所在虚拟屏 id」。
     * 新模型下应用与桌面同屏，键盘/输入法直接作用在同一块屏上，
     * 不再需要按窗口切换注入目标，因此统一返回 -1，
     * 调用方会自动回退到桌面自己的 virtualDisplayId（语义正好正确）。
     */
    public static int getActiveAppDisplayId() {
        return -1;
    }

    /** 一个系统应用窗口 = 一个 Activity task。 */
    private static final class Win {
        final String comp;
        final String label;
        int taskId = -1;
        int x, y, w, h;       // 常规位置
        int rx, ry, rw, rh;   // 最大化前的位置
        boolean maximized;
        boolean minimized;

        Win(String comp, String label) {
            this.comp = comp;
            this.label = label;
        }
    }

    private final Activity host;
    private final FullscreenCallback fsCallback;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private final float density;
    private final int screenW, screenH;
    private final int menubarPx, dockPx;
    /** 底部 Dock 预留高度：窗口最大化也停在这条线上面，Dock 才不会被 Task 盖住。 */
    private final int dockReservePx;
    /** 桌面所在的显示 id：所有系统应用都启动在这块屏上。 */
    private final int displayId;

    private final Map<String, Win> wins = new LinkedHashMap<>();
    private final List<String> launchpadHidden = new ArrayList<>();
    private Win active;
    private int seq = 0;
    private boolean fullscreen = false;
    private boolean releasing = false;
    private volatile boolean inputBound = false;

    /**
     * @param layer 旧模型用于承载 TextureView 的容器；新模型不再采集画面，
     *              该参数仅为保持调用方签名兼容而保留，内部不使用。
     */
    public AppShellManager(Activity host, FrameLayout layer, FullscreenCallback cb) {
        this.host = host;
        this.fsCallback = cb;

        /* 取「Activity 所在屏幕」的完整物理尺寸：getRealMetrics 不扣 system decor，
         * 自由窗口的 bounds 用的就是这块屏的绝对像素坐标。 */
        DisplayMetrics dm = new DisplayMetrics();
        int did = 0;
        boolean got = false;
        try {
            Display d = host.getDisplay();
            if (d != null) {
                d.getRealMetrics(dm);
                did = d.getDisplayId();
                got = true;
            }
        } catch (Throwable ignored) {}
        if (!got) {
            try {
                Display d = host.getDisplay();
                if (d != null) {
                    d.getMetrics(dm);
                    did = d.getDisplayId();
                    got = true;
                }
            } catch (Throwable ignored) {}
        }
        if (!got) {
            dm.setTo(host.getResources().getDisplayMetrics());
        }
        this.displayId = did;
        this.density = dm.density > 0 ? dm.density : 1f;
        this.screenW = dm.widthPixels;
        this.screenH = dm.heightPixels;
        this.menubarPx = Math.round(30 * density);
        this.dockPx = Math.round(78 * density);
        this.dockReservePx = Math.round(88 * density);
        Log.i(TAG, "系统窗口管理就绪 display=" + displayId
                + " " + screenW + "x" + screenH + " @" + dm.densityDpi + "dpi");
    }

    // ==================== 打开 / 关闭 ====================

    public void open(String comp, String label) {
        if (comp == null || comp.isEmpty()) return;
        Win w = wins.get(comp);
        if (w != null) {
            // 已存在：置顶（重新 am start 会把它带到前台）并恢复最小化。
            if (w.minimized) restore(w);
            else bringToFront(w);
            setActive(w);
            return;
        }
        w = new Win(comp, (label == null || label.isEmpty()) ? comp : label);
        layout(w);
        wins.put(comp, w);
        setActive(w);
        final Win target = w;
        new Thread(() -> start(target), "aosen-syswin-open").start();
    }


    public boolean hasFullscreenWin() {
        for (Win w : wins.values()) {
            if (w.maximized && !w.minimized) return true;
        }
        return false;
    }

    public void exitFullscreenActive() {
        for (Win w : wins.values()) {
            if (w.maximized && !w.minimized) {
                toggleMaximize(w.comp);
                return;
            }
        }
    }
    public void close(String comp) {
        Win w = wins.remove(comp);
        if (w == null) return;
        if (active == w) active = lastVisible();
        final Win target = w;
        new Thread(() -> killTask(target), "aosen-syswin-close").start();
        syncFullscreen();
    }

    public void closeActive() {
        if (active != null) close(active.comp);
    }

    public void minimize(String comp) {
        Win w = wins.get(comp);
        if (w == null || w.minimized) return;
        w.minimized = true;
        if (active == w) active = lastVisible();
        moveOut(w);
        syncFullscreen();
    }

    public void minimizeActive() {
        if (active != null) minimize(active.comp);
    }

    public void toggleMaximize(String comp) {
        Win w = wins.get(comp);
        if (w == null) return;
        if (!w.maximized) {
            w.rx = w.x;
            w.ry = w.y;
            w.rw = w.w;
            w.rh = w.h;
            w.maximized = true;
            /* 最大化也留出底部 Dock：窗口是 freeform Task，会盖在桌面 Activity 上面，
             * 铺满整屏的话 Dock 就被永久遮住（用户原话：dock 栏层级太低）。 */
            resize(w, 0, 0, screenW, Math.max(1, screenH - dockReservePx));
        } else {
            w.maximized = false;
            resize(w, w.rx, w.ry, w.rw, w.rh);
        }
        syncFullscreen();
    }

    public void toggleMaximizeActive() {
        if (active != null) toggleMaximize(active.comp);
    }

    public boolean isFullscreen() {
        return fullscreen;
    }

    // ==================== 启动台 ====================

    public void hideAllForLaunchpad() {
        launchpadHidden.clear();
        for (Win w : wins.values()) {
            if (!w.minimized) {
                launchpadHidden.add(w.comp);
                minimize(w.comp);
            }
        }
    }

    public void restoreFromLaunchpad() {
        for (String comp : launchpadHidden) {
            Win w = wins.get(comp);
            if (w != null && w.minimized) restore(w);
        }
        launchpadHidden.clear();
    }

    public void releaseAll() {
        releasing = true;
        List<Win> all = new ArrayList<>(wins.values());
        wins.clear();
        active = null;
        launchpadHidden.clear();
        for (Win w : all) {
            new Thread(() -> killTask(w), "aosen-syswin-release").start();
        }
        syncFullscreen();
        releasing = false;
    }

    // ==================== 内部实现 ====================

    /**
     * 所有外设固定绑到桌面这块屏。应用窗口现在就在同一块屏上，
     * 绑定只做一次，不再随窗口数量变化 —— 这是与旧模型最本质的区别。
     */
    private void ensureInputBound() {
        if (inputBound) return;
        inputBound = true;
        try {
            ShizukuCmd.bindAllExternalInputToDisplay(displayId, true);
            Log.i(TAG, "外设已绑定到桌面屏 display=" + displayId);
        } catch (Throwable t) {
            Log.w(TAG, "绑定外设失败: " + t.getMessage());
        }
    }

    private void start(Win w) {
        try {
            ensureInputBound();
            String cmd = buildStartCmd(w.comp, w.label, true);
            String r = ShizukuCmd.execute(cmd);
            Log.i(TAG, "启动自由窗口 " + w.comp + " display=" + displayId + " 结果=" + brief(r));
            sleep(700);
            int id = findTask(w.comp);
            if (id <= 0) {
                /* freeform 被系统拒绝时退回全屏启动：至少保证应用能打开，
                 * 全屏时窗口没有系统标题栏，但输入链路仍然正确。 */
                Log.w(TAG, "自由窗口未生效，回退全屏启动: " + w.comp);
                ShizukuCmd.execute(buildStartCmd(w.comp, w.label, false));
                sleep(600);
                id = findTask(w.comp);
            }
            if (id > 0) {
                w.taskId = id;
                if (!w.maximized && !w.minimized) {
                    /* resizeable=2 是 resizeable：部分应用默认不可缩放，
                     * 不改这个标志 am task resize 会被静默忽略。 */
                    ShizukuCmd.execute("am task resizeable " + id + " 2");
                    sleep(120);
                    resizeSync(w);
                }
                Log.i(TAG, "系统窗口就绪 " + w.comp + " task=" + id
                        + " bounds=" + w.x + "," + w.y + " " + w.w + "x" + w.h);
                // 窗口落在哪块屏、什么 bounds：一键复测时直接看调试面板，不用抓 logcat。
                InputTrace.info("窗口壳", w.label + " " + lastTaskInfo
                        + " 目标=" + w.x + "," + w.y + " " + w.w + "x" + w.h);
            } else {
                Log.w(TAG, "未找到 task，无法定位窗口: " + w.comp);
                InputTrace.info("窗口壳", w.label + " 未找到 task（display=" + displayId + "）");
            }
            ui.post(this::syncFullscreen);
        } catch (Throwable t) {
            Log.w(TAG, "启动系统窗口异常: " + t.getMessage());
        }
    }

    private void resizeSync(Win w) {
        if (w.taskId <= 0) return;
        ShizukuCmd.execute("am task resize " + w.taskId + " " + w.x + " " + w.y
                + " " + (w.x + w.w) + " " + (w.y + w.h));
    }

    private void resize(Win w, int x, int y, int ww, int hh) {
        if (w.taskId <= 0) return;
        final int id = w.taskId;
        final String cmd = "am task resize " + id + " " + x + " " + y
                + " " + (x + ww) + " " + (y + hh);
        new Thread(() -> ShizukuCmd.execute(cmd), "aosen-syswin-resize").start();
    }

    /** 最小化：窗口挪到屏幕可视区域之外，task 保留，恢复时只改坐标。 */
    private void moveOut(Win w) {
        if (w.taskId <= 0) return;
        final int id = w.taskId;
        final int far = Math.max(screenW, screenH) + 4000;
        new Thread(() -> ShizukuCmd.execute("am task resize " + id
                + " " + far + " " + far + " " + (far + 400) + " " + (far + 300)),
                "aosen-syswin-hide").start();
    }

    private void restore(Win w) {
        w.minimized = false;
        resize(w, w.x, w.y, w.w, w.h);
        bringToFront(w);
        setActive(w);
        syncFullscreen();
    }

    private void bringToFront(Win w) {
        if (w.comp == null) return;
        final String comp = w.comp;
        final String label = w.label;
        new Thread(() -> {
            // 再次 am start 是最稳的置顶方式（不依赖 am task 的子命令支持情况）。
            ShizukuCmd.execute(buildStartCmd(comp, label, true));
        }, "aosen-syswin-front").start();
    }

    /** 拼 am start 命令：系统应用直接启动组件，HTML 应用启动自己的窗口宿主。 */
    private String buildStartCmd(String comp, String label, boolean freeform) {
        StringBuilder sb = new StringBuilder("am start -n ");
        if (isHtmlComp(comp)) {
            String appId = comp.substring(HTML_PREFIX.length());
            sb.append(host.getPackageName()).append('/').append(HtmlAppActivity.class.getName());
            sb.append(" --display ").append(displayId);
            if (freeform) sb.append(" --windowingMode 5");
            sb.append(" --es ").append(HtmlAppActivity.EXTRA_APP_ID).append(' ').append(appId);
            sb.append(" --es ").append(HtmlAppActivity.EXTRA_APP_TITLE).append(' ').append(quote(label));
        } else {
            sb.append(comp).append(" --display ").append(displayId);
            if (freeform) sb.append(" --windowingMode 5");
        }
        return sb.toString();
    }

    /** shell 参数带空格时用单引号包起来。 */
    private static String quote(String s) {
        if (s == null || s.isEmpty()) return "''";
        return "'" + s.replace("'", "'\\''") + "'";
    }

    private void killTask(Win w) {
        if (w.taskId > 0) {
            String r = ShizukuCmd.execute("am task remove " + w.taskId);
            if (r != null && r.toLowerCase().contains("error") && !isHtmlComp(w.comp)) {
                ShizukuCmd.execute("am force-stop " + pkgOf(w.comp));
            }
        } else if (!isHtmlComp(w.comp)) {
            /* HTML 应用的包名就是我们自己，绝不能 force-stop（会把桌面一起杀掉）。 */
            ShizukuCmd.execute("am force-stop " + pkgOf(w.comp));
        }
        if (isHtmlComp(w.comp)) htmlTasks.remove(w.comp.substring(HTML_PREFIX.length()));
    }

    /**
     * 从 dumpsys 里定位该包在当前屏上的 taskId。
     *
     * <p>必须按 display 过滤：同一个包可能在手机主屏还留着上一次的 task，
     * 旧实现取「全局最后一个匹配」，于是 am task resize 改的是手机那块屏的窗口，
     * 虚拟屏上的窗口尺寸/位置一直没动过 —— 表现就是"窗口看得见，点不进去"。
     */
    private int findTask(String comp) {
        if (isHtmlComp(comp)) return findHtmlTask(comp.substring(HTML_PREFIX.length()));
        String pkg = pkgOf(comp);
        if (pkg.isEmpty()) return -1;
        String out = ShizukuCmd.execute("dumpsys activity activities");
        if (out == null || out.isEmpty()) return -1;
        try {
            Pattern p = Pattern.compile("#(\\d+)\\s+type=\\S+\\s+A=\\d+:" + Pattern.quote(pkg) + "(?=[\\s}])");
            Matcher m = p.matcher(out);
            int best = -1;
            int fallback = -1;
            String bestInfo = "";
            String fallbackInfo = "";
            boolean displayParsed = false;
            while (m.find()) {
                int id;
                try {
                    id = Integer.parseInt(m.group(1));
                } catch (Throwable ignored) {
                    continue;
                }
                String block = out.substring(m.end(), taskBlockEnd(out, m.end()));
                String info = describeTask(id, block);
                // 同屏多个 task 时取最后出现的那个（刚启动的最新）
                fallback = id;
                fallbackInfo = info;
                Matcher dm = DISPLAY_ID_RE.matcher(block);
                if (dm.find()) {
                    displayParsed = true;
                    try {
                        if (Integer.parseInt(dm.group(1)) == displayId) {
                            best = id;
                            bestInfo = info;
                        }
                    } catch (Throwable ignored) {}
                }
            }
            lastTaskInfo = (best > 0) ? bestInfo : fallbackInfo;
            if (best > 0) return best;
            // dumpsys 里压根没有 mDisplayId 字段（老系统差异）时退回旧行为，别把功能判死。
            return displayParsed ? -1 : fallback;
        } catch (Throwable t) {
            return -1;
        }
    }

    /** 当前 dumpsys 输出里，task 块的结束位置（下一个 "* Task{" 之前）。 */
    private static int taskBlockEnd(String out, int from) {
        int next = out.indexOf("* Task{", from);
        int limit = Math.min(out.length(), from + 2000);
        return (next > from && next < limit) ? next : limit;
    }

    private static String describeTask(int id, String block) {
        StringBuilder sb = new StringBuilder("#").append(id);
        Matcher dm = DISPLAY_ID_RE.matcher(block);
        if (dm.find()) sb.append(" display=").append(dm.group(1));
        Matcher bm = BOUNDS_RE.matcher(block);
        if (bm.find()) sb.append(" bounds=[").append(bm.group(1)).append("]");
        return sb.toString();
    }

    private static final Pattern DISPLAY_ID_RE = Pattern.compile("mDisplayId=(-?\\d+)");
    private static final Pattern BOUNDS_RE = Pattern.compile("(?:bounds|mBounds)=Rect\\(([^)]*)\\)");
    /** 最近一次解析到的 task 归属，供调试面板显示。 */
    private String lastTaskInfo = "";


    /** appId -> 它的窗口 taskId（同一个 Activity 组件开多个应用，只能自己记账）。 */
    private final Map<String, Integer> htmlTasks = new LinkedHashMap<>();

    private int findHtmlTask(String appId) {
        Integer cached = htmlTasks.get(appId);
        if (cached != null && cached > 0 && isTaskPresent(cached)) return cached;
        String out = ShizukuCmd.execute("dumpsys activity activities");
        if (out == null || out.isEmpty()) return -1;
        java.util.Set<Integer> all = new java.util.HashSet<>();
        try {
            Matcher m = Pattern.compile("HtmlAppActivity\\s+t(\\d+)").matcher(out);
            while (m.find()) {
                try {
                    all.add(Integer.parseInt(m.group(1)));
                } catch (Throwable ignored) {}
            }
        } catch (Throwable ignored) {}
        java.util.Set<Integer> used = new java.util.HashSet<>(htmlTasks.values());
        for (Integer id : all) {
            if (!used.contains(id)) {
                htmlTasks.put(appId, id);
                return id;
            }
        }
        return -1;
    }

    private boolean isTaskPresent(int taskId) {
        String out = ShizukuCmd.execute("dumpsys activity activities | grep \"#" + taskId + " \"");
        return out != null && out.contains("#" + taskId);
    }

    private static String pkgOf(String comp) {
        if (comp == null) return "";
        int i = comp.indexOf('/');
        return i > 0 ? comp.substring(0, i) : comp;
    }

    private static String brief(String s) {
        if (s == null) return "null";
        int nl = s.indexOf('\n');
        String one = nl > 0 ? s.substring(0, nl) : s;
        return one.length() > 120 ? one.substring(0, 120) + "…" : one;
    }

    private void setActive(Win w) {
        active = w;
    }

    private Win lastVisible() {
        Win last = null;
        for (Win w : wins.values()) {
            if (!w.minimized) last = w;
        }
        return last;
    }

    private void syncFullscreen() {
        boolean fs = false;
        for (Win w : wins.values()) {
            if (w.maximized && !w.minimized) {
                fs = true;
                break;
            }
        }
        if (fs == fullscreen) return;
        fullscreen = fs;
        try {
            fsCallback.onFullscreenChanged(fs);
        } catch (Throwable ignored) {}
    }

    /** 级联布局：避开顶部菜单栏与底部 Dock，保证桌面导航始终可点。 */
    private void layout(Win w) {
        int idx = seq++ % 6;
        int ww = Math.round(screenW * 0.74f);
        int hh = Math.round(screenH * 0.70f);
        int maxX = Math.max(0, screenW - ww);
        int maxY = Math.max(menubarPx, screenH - dockPx - hh);
        int x = Math.min(Math.round(screenW * 0.05f) + idx * Math.round(30 * density), maxX);
        int y = Math.min(menubarPx + Math.round(14 * density) + idx * Math.round(26 * density), maxY);
        w.x = Math.max(0, x);
        w.y = Math.max(0, y);
        w.w = ww;
        w.h = hh;
        w.rx = w.x;
        w.ry = w.y;
        w.rw = ww;
        w.rh = hh;
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
        }
    }
}
