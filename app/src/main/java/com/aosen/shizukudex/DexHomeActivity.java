package com.aosen.shizukudex;

import android.app.Activity;
import android.app.ActivityManager;
import android.app.AlertDialog;
import android.app.KeyguardManager;
import android.app.WallpaperManager;
import android.appwidget.AppWidgetHost;
import android.appwidget.AppWidgetHostView;
import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProviderInfo;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.os.BatteryManager;
import android.hardware.display.DisplayManager;
import android.view.Display;
import android.content.pm.ActivityInfo;
import android.content.pm.LauncherActivityInfo;
import android.content.pm.LauncherApps;
import android.content.pm.PackageManager;
import android.content.pm.PackageInfo;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Outline;
import android.graphics.Rect;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.ParcelFileDescriptor;
import android.os.Process;
import android.os.SystemClock;
import android.util.Log;
import android.view.Gravity;
import android.view.InputDevice;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewOutlineProvider;
import android.view.ViewTreeObserver;
import android.view.WindowManager;
import android.webkit.JavascriptInterface;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 沧海桌面 v17：WebView(HTML) 渲染整套 UI（玻璃拟态 CSS），原生层只负责：
 *  - Shizuku 命令（启动应用/窗口校正/贴边/清理三星桌面）
 *  - 小组件宿主（AppWidgetHostView 叠在 WebView 上方，位置由 JS 上报）
 *  - 壁纸/图标资源生成
 * UI 全部在 assets/dexhome/index.html。
 */
public class DexHomeActivity extends Activity {
    public void notifyActiveWinChanged(final String name) {
        if (dockWeb != null) {
            runOnUiThread(() -> {
                try {
                    dockWeb.evaluateJavascript("window.updateTopActiveWin && window.updateTopActiveWin("
                            + org.json.JSONObject.quote(name == null || name.isEmpty() ? "桌面" : name) + ");", null);
                } catch (Throwable ignored) {}
            });
        }
    }

    public static void notifyResolutionChanged(final int targetDisplayId, final String resKey) {
        if (resKey == null || resKey.isEmpty()) return;
        for (DexHomeActivity inst : sInstancesByDisplay.values()) {
            if (inst != null && !inst.isFinishing() && !inst.isDestroyed() && inst.web != null) {
                inst.applyResolution(resKey);
            }
        }
        if (sInstance != null && sInstance.web != null) {
            sInstance.applyResolution(resKey);
        }
    }

    public void applyResolution(final String resKey) {
        runOnUiThread(() -> {
            try {
                if (web != null) {
                    web.evaluateJavascript(
                            "window.chApplyResolution && window.chApplyResolution("
                                    + JSONObject.quote(resKey) + ");", null);
                }
            } catch (Throwable ignored) {}
        });
    }

    public static void notifyDockOffsetsChanged(final int x, final int y) {
        for (DexHomeActivity inst : sInstancesByDisplay.values()) {
            if (inst != null && inst.dockWeb != null) {
                inst.dockWeb.post(() -> {
                    try {
                        String js = "window.chApplyDockOffsets && window.chApplyDockOffsets(" + x + ", " + y + ");";
                        inst.dockWeb.evaluateJavascript(js, null);
                    } catch (Throwable ignored) {}
                });
            }
        }
    }

    public static volatile DexHomeActivity sInstance;
    private static final java.util.Map<Integer, DexHomeActivity> sInstancesByDisplay =
            new java.util.concurrent.ConcurrentHashMap<>();

    /**
     * 按屏找桌面实例。
     *
     * <p>精确 key 是首选，但**不能是唯一出路**：虚拟画布的 displayId 与桌面 Activity
     * 注册时用的 id 会因屏重建、桌面被单独拉起而差一轮。一失手，右键 / Dock / 拖拽 /
     * 滚轮全部静默失效，而左键是纯注入、照样能用 —— 用户看到的就是「只剩左键能用」。
     * 所以精确 key 落空时，退一步挑这块屏上**唯一或距离最近**的活实例。
     */
    public static DexHomeActivity findForDisplay(int targetDisplayId) {
        if (targetDisplayId > 0) {
            DexHomeActivity inst = sInstancesByDisplay.get(targetDisplayId);
            if (inst != null && !inst.isFinishing() && !inst.isDestroyed()) return inst;
        }
        DexHomeActivity fallback = sInstance;
        if (fallback != null && !fallback.isFinishing() && !fallback.isDestroyed()) return fallback;
        if (targetDisplayId <= 0) return null;
        DexHomeActivity best = null;
        int bestDist = Integer.MAX_VALUE;
        for (java.util.Map.Entry<Integer, DexHomeActivity> e : sInstancesByDisplay.entrySet()) {
            DexHomeActivity c = e.getValue();
            if (c == null || c.isFinishing() || c.isDestroyed()) continue;
            int k = e.getKey() == null ? -1 : e.getKey();
            int dist = Math.abs(k - targetDisplayId);
            if (dist < bestDist) {
                bestDist = dist;
                best = c;
            }
        }
        return best;
    }


    /** 切换到官方原生桌面时，主动结束所有自绘沧海桌面实例，避免残留抢占虚拟屏栈顶。 */
    public static void finishAllForOfficialDesktop() {
        java.util.Set<DexHomeActivity> seen = new java.util.HashSet<>(sInstancesByDisplay.values());
        if (sInstance != null) seen.add(sInstance);
        sInstancesByDisplay.clear();
        sInstance = null;
        for (final DexHomeActivity inst : seen) {
            if (inst == null) continue;
            inst.runOnUiThread(() -> {
                try {
                    if (!inst.isFinishing() && !inst.isDestroyed()) {
                        inst.finish();
                    }
                } catch (Throwable ignored) {}
            });
        }
    }

    /**
     * 手机端改了光标样式 / 大小 / 速度（或样式库）后，把最新一份推给外屏桌面，
     * 桌面无需刷新页面就能跟着换。自定义桌面没有这个钩子，调用是安全的空操作。
     */
    public static void notifyCursorPrefsChanged() {
        EmptyPointerFrameLayout.clearThemedPointerCache();
        java.util.Set<DexHomeActivity> seen = new java.util.HashSet<>(sInstancesByDisplay.values());
        if (sInstance != null) seen.add(sInstance);
        for (DexHomeActivity inst : seen) {
            if (inst != null && !inst.isFinishing() && !inst.isDestroyed() && inst.web != null) {
                notifyCursorPrefsChanged(inst);
            }
        }
    }

    /**
     * 手机端（或另一块外屏）改了应用数据：把最新的 stateJson 推给所有活着的
     * 沧海桌面，页面用 __canghaiSyncApps 增量同步，不用刷新页面、不打断已开窗口。
     */
    public static void notifyDesktopAppsChanged() {
        java.util.Set<DexHomeActivity> seen = new java.util.HashSet<>();
        for (DexHomeActivity inst : sInstancesByDisplay.values()) {
            if (inst != null && !inst.isFinishing() && !inst.isDestroyed()) seen.add(inst);
        }
        DexHomeActivity fallback = sInstance;
        if (fallback != null && !fallback.isFinishing() && !fallback.isDestroyed()) seen.add(fallback);
        for (final DexHomeActivity inst : seen) {
            inst.runOnUiThread(() -> {
                try {
                    if (inst.web != null) {
                        String state = CanghaiStore.stateJson(inst);
                        inst.web.evaluateJavascript(
                                "window.__canghaiSyncApps && window.__canghaiSyncApps(" + state + ");", null);
                    }
                    if (inst.dockWeb != null) {
                        inst.pushDock();
                    }
                } catch (Throwable ignored) {}
            });
        }
    }

    private static volatile String sLastBroadcastDeskState = "";

    /** 跨外屏实时同步桌面布局（网格、图标位置、文件夹、小组件等），带全网防自环与防死循环抖动 */
    public static void notifyDesktopStateChanged(int senderDisplayId, final String json) {
        if (json == null || json.isEmpty()) return;
        synchronized (DexHomeActivity.class) {
            if (json.equals(sLastBroadcastDeskState)) return;
            sLastBroadcastDeskState = json;
        }
        java.util.Set<DexHomeActivity> seen = new java.util.HashSet<>();
        for (DexHomeActivity inst : sInstancesByDisplay.values()) {
            if (inst != null && !inst.isFinishing() && !inst.isDestroyed()) seen.add(inst);
        }
        DexHomeActivity fallback = sInstance;
        if (fallback != null && !fallback.isFinishing() && !fallback.isDestroyed()) seen.add(fallback);
        for (final DexHomeActivity inst : seen) {
            if (inst.displayId == senderDisplayId) continue;
            inst.runOnUiThread(() -> {
                try {
                    if (inst.web == null) return;
                    inst.web.evaluateJavascript(
                            "window.__canghaiSyncDesktop && window.__canghaiSyncDesktop("
                                    + org.json.JSONObject.quote(json) + ");", null);
                } catch (Throwable ignored) {}
            });
        }
    }

    /** 跨端同步语言切换 */
    public static void notifyLanguageChanged(final String lang) {
        if (lang == null || lang.isEmpty()) return;
        java.util.Set<DexHomeActivity> seen = new java.util.HashSet<>();
        for (DexHomeActivity inst : sInstancesByDisplay.values()) {
            if (inst != null && !inst.isFinishing() && !inst.isDestroyed()) seen.add(inst);
        }
        DexHomeActivity fallback = sInstance;
        if (fallback != null && !fallback.isFinishing() && !fallback.isDestroyed()) seen.add(fallback);
        for (final DexHomeActivity inst : seen) {
            inst.runOnUiThread(() -> {
                try {
                    String script = "window.CHI18n && window.CHI18n.setLang && window.CHI18n.setLang("
                            + org.json.JSONObject.quote(lang) + ");";
                    if (inst.web != null) inst.web.evaluateJavascript(script, null);
                    if (inst.dockWeb != null) inst.dockWeb.evaluateJavascript(script, null);
                } catch (Throwable ignored) {}
            });
        }
    }

    private static void notifyCursorPrefsChanged(final DexHomeActivity inst) {
        if (inst == null || inst.web == null) return;
        inst.runOnUiThread(() -> {
            try {
                org.json.JSONObject o = new org.json.JSONObject();
                o.put("cursorCurrent", CanghaiStore.cursorCurrentForDisplay(inst, inst.displayId));
                o.put("cursorSize", CanghaiStore.cursorSizeForDisplay(inst, inst.displayId));
                o.put("cursorSpeed", (double) CanghaiStore.cursorSpeed(inst));
                o.put("cursorLib", new org.json.JSONArray(CanghaiStore.cursorLibJson(inst)));
                inst.web.evaluateJavascript(
                        "window.__canghaiSyncCursor && window.__canghaiSyncCursor("
                                + o.toString() + ");", null);
            } catch (Throwable ignored) {
            }
        });
    }

    /**
     * 手机端把某个应用「投到屏幕」时调用：如果外屏桌面还活着，直接把打开动作推过去；
     * 没活着就只留 pending，由页面开机时 takePendingOpen() 自行取走。
     */
    public static void postOpenApp(String appId) {
        if (appId == null || appId.isEmpty()) return;
        final DexHomeActivity inst = findForDisplay(0);
        if (inst == null) return;
        inst.runOnUiThread(() -> {
            try {
                if (inst.web == null) return;
                inst.web.evaluateJavascript(
                        "window.__canghaiOpenApp && window.__canghaiOpenApp("
                                + JSONObject.quote(appId) + ");", null);
            } catch (Throwable ignored) {
            }
        });
    }

    /** 手机端「系统桌面设置 → 隐藏 Dock」改了：所有在跑的外屏桌面立刻跟着变。 */
    public static void onDockAutoHideChanged(boolean on) {
        for (DexHomeActivity inst : sInstancesByDisplay.values()) {
            if (inst == null || inst.isFinishing() || inst.isDestroyed()) continue;
            inst.runOnUiThread(() -> inst.saveDockAutoHide(on));
        }
        DexHomeActivity fallback = sInstance;
        if (fallback != null && !fallback.isFinishing() && !fallback.isDestroyed()) {
            fallback.runOnUiThread(() -> fallback.saveDockAutoHide(on));
        }
    }

    /**
     * 外屏物理鼠标或黑屏触控板移动时，不向虚拟屏注入 SOURCE_MOUSE（否则 InputFlinger
     * 会在虚拟屏点亮系统默认硬件箭头），而是在进程内直接驱动 Dock 底部探针唤醒与加高。
     */
    public static void onExternalPointerHover(int targetDisplayId, float x, float y, int screenH) {
        if (screenH <= 0) return;
        DexHomeActivity inst = findForDisplay(targetDisplayId);
        if (inst == null || inst.isFinishing() || inst.isDestroyed()) return;
        float distBottom = screenH - y;
        int probeH = inst.dockProbeHpx > 0 ? inst.dockProbeHpx : 24;
        int expandH = inst.dockFullHpx > 0 ? inst.dockFullHpx : 140;
        if (distBottom <= probeH) {
            if (!inst.dockHoverOn) {
                inst.runOnUiThread(() -> {
                    inst.chDockNativeHover = true;
                    inst.cancelCanghaiDockCollapse();
                    inst.dockHoverOn = true;
                    inst.refreshCanghaiDock();
                });
            }
        } else if (distBottom > expandH && inst.dockHoverOn) {
            inst.runOnUiThread(() -> {
                inst.chDockNativeHover = false;
                inst.dockHoverOn = false;
                inst.scheduleCanghaiDockCollapse(300L);
            });
        }
    }

    /** 外屏物理鼠标或通用注入右键点击时，直接通知桌面 WebView 弹出上下文菜单 */
    /** 中键 = 应用台。底座 Activity 模式下列表在 Dock 悬浮窗里，只能让它自己翻。 */
    public static boolean onExternalToggleLaunchpad(int targetDisplayId) {
        DexHomeActivity inst = findForDisplay(targetDisplayId);
        if (inst == null || inst.isFinishing() || inst.isDestroyed()) return false;
        inst.runOnUiThread(() -> inst.setDockLaunchpadOpen(!inst.dockLaunchpadOpen));
        return true;
    }

    /**
     * 通知桌面 WebView 弹上下文菜单。
     *
     * 返回 true 表示这一屏确实有沧海桌面、菜单已在它自己的 WebView 里弹出来了。
     * 调用方拿到 true 就**不能再往虚拟屏补注入右键**：那个 WebView 就是桌面本身，
     * 补一发 mouseup 会立刻把刚弹的菜单关掉（用户看到的「右键一闪而过」）。
     */
    public static boolean onExternalRightClick(int targetDisplayId, float x, float y) {
        DexHomeActivity inst = findForDisplay(targetDisplayId);
        if (inst == null || inst.isFinishing() || inst.isDestroyed()) return false;
        if (!inst.webReady || inst.web == null) return false;
        inst.runOnUiThread(() -> inst.triggerContextMenu(x, y, inst.web,
                code -> rightClickFallback(targetDisplayId, x, y, "桌面", code)));
        return true;
    }

    /**
     * JS 派发的结果必须核对：>0 才算真的送到。
     *
     * <p>送到 0 / 负数（没见过元素、evaluateJavascript 抛错、WebView 还没就绪）就说明
     * 这条路这一次走不通，立刻用**系统原生右键**补一发：命中测试交给系统，它一定知道
     * 光标底下是哪块窗口 —— 桌面、Dock、还是第三方的播放器。补之前先把系统指针挪到
     * 同一个点上，否则它会打在我们累加器漂到的位置（差几百像素，看着就是没反应）。
     */
    private static void rightClickFallback(int targetDisplayId, float x, float y,
                                           String who, int code) {
        try {
            CanghaiErrorCollector.logExt("[右键] " + who + "派发 display=" + targetDisplayId
                    + " @" + Math.round(x) + "," + Math.round(y)
                    + " → " + (code > 0 ? "送达(" + code + ")" : "没送达(" + code + ")，改用系统右键"));
        } catch (Throwable ignored) {}
        if (code > 0) return;
        new Thread(() -> {
            try {
                VirtualMouse.moveTo(x, y);
                VirtualMouse.click(android.view.MotionEvent.BUTTON_SECONDARY);
            } catch (Throwable ignored) {}
        }, "right-click-fallback").start();
    }

    /**
     * Dock 栏右键：Root 底部条带窗口可触摸后 {@link #isOnDockRegion} 恒返回 false，
     * 桌面那条右键直派只认桌面 document，Dock 图标永远吃不到右键。
     * 这里按 dockWeb 在屏幕上的真实矩形判断，命中就派给 Dock 自己的 document。
     */
    public static boolean onDockRightClick(int targetDisplayId, float x, float y) {
        DexHomeActivity inst = findForDisplay(targetDisplayId);
        if (inst == null || inst.isFinishing() || inst.isDestroyed()) return false;
        WebView dw = inst.dockWeb;
        if (dw == null || !inst.dockReady) return false;
        /* Dock 的 WebView 是**整屏**的，只是内容画在底部条带（应用台/菜单摊开时才是全屏）。
         * 以前这里用 hitWebView 判矩形，等于「整块屏都属于 Dock」，于是桌面的右键
         * 一路被 Dock 吃光，桌面永远收不到 contextmenu —— 症状就是
         * 「Dock 和应用台能右键，桌面不能」。所以只有两种情况 Dock 才接管：
         *   1. 应用台 / 菜单已经摊开（这时它本来就该吃整屏）；
         *   2. 点真的落在它此刻**可见**的那条带里（展开 140px，收起只剩探针高度）。
         * 其余一律不接，交给桌面那条路。 */
        boolean owns = inst.dockLaunchpadOpen || inst.dockMenuOpen || inst.mAllTouchable;
        if (!owns) {
            int band = (inst.canghaiDockExpanded || inst.dockHoverOn)
                    ? inst.dockFullHpx : inst.dockProbeHpx;
            int h = (inst.dockHolder != null) ? inst.dockHolder.getHeight() : inst.getWindowH();
            if (h > 0 && band > 0 && y >= h - band) owns = true;
        }
        if (!owns) return false;
        inst.runOnUiThread(() -> inst.triggerContextMenu(x, y, dw,
                code -> rightClickFallback(targetDisplayId, x, y, "Dock", code)));
        return true;
    }

    /** 当前窗口高度（虚拟屏像素）；拿不到返回 0。 */
    private int getWindowH() {
        try {
            android.graphics.Rect r = new android.graphics.Rect();
            getWindowManager().getDefaultDisplay().getRectSize(r);
            return r.height();
        } catch (Throwable ignored) {
            return 0;
        }
    }

    /** 指针（虚拟屏像素）是否落在这块 WebView 的真实屏幕矩形里；顺带回填窗口左上角。 */
    private boolean hitWebView(WebView v, float x, float y, int[] loc) {
        if (v == null || v.getWidth() <= 0 || v.getHeight() <= 0) return false;
        try { v.getLocationOnScreen(loc); } catch (Throwable t) { return false; }
        return x >= loc[0] && x <= loc[0] + v.getWidth()
                && y >= loc[1] && y <= loc[1] + v.getHeight();
    }

    /** 滚轮 / 按住拖动：按真实窗口几何挑一块 WebView（Dock 条优先，否则桌面页）。 */
    private static WebView webAt(DexHomeActivity a, float x, float y, int[] loc) {
        if (a == null) return null;
        if (a.dockWeb != null && a.dockReady && a.hitWebView(a.dockWeb, x, y, loc)) return a.dockWeb;
        if (a.webReady && a.web != null) {
            try { a.web.getLocationOnScreen(loc); } catch (Throwable ignored) {}
            return a.web;
        }
        return null;
    }

    /** 滚轮：除了写回 uinput 让外部原生应用能滚，也给命中的沧海 WebView 补一发带坐标的 SCROLL。 */
    public static boolean dispatchWebScroll(int targetDisplayId, float x, float y, float v, float h) {
        DexHomeActivity a = findForDisplay(targetDisplayId);
        if (a == null || a.isFinishing() || a.isDestroyed()) return false;
        int[] loc = new int[2];
        WebView target = webAt(a, x, y, loc);
        if (target == null) return false;
        long now = android.os.SystemClock.uptimeMillis();
        android.view.MotionEvent.PointerProperties pp = new android.view.MotionEvent.PointerProperties();
        pp.id = 0;
        pp.toolType = android.view.MotionEvent.TOOL_TYPE_MOUSE;
        android.view.MotionEvent.PointerCoords pc = new android.view.MotionEvent.PointerCoords();
        pc.x = x - loc[0];
        pc.y = y - loc[1];
        pc.setAxisValue(android.view.MotionEvent.AXIS_VSCROLL, v);
        pc.setAxisValue(android.view.MotionEvent.AXIS_HSCROLL, h);
        android.view.MotionEvent ev = android.view.MotionEvent.obtain(now, now,
                android.view.MotionEvent.ACTION_SCROLL, 1,
                new android.view.MotionEvent.PointerProperties[]{pp},
                new android.view.MotionEvent.PointerCoords[]{pc},
                0, 0, 1f, 1f, 0, 0, android.view.InputDevice.SOURCE_MOUSE, 0);
        a.runOnUiThread(() -> {
            try { target.dispatchGenericMotionEvent(ev); } catch (Throwable ignored) {}
            finally { ev.recycle(); }
        });
        return true;
    }

    /** 左键按住移动：触摸注入之外再补一发带按钮状态的鼠标 MOVE，拖动才有「按住」。 */
    public static boolean dispatchWebDrag(int targetDisplayId, float x, float y) {
        DexHomeActivity a = findForDisplay(targetDisplayId);
        if (a == null || a.isFinishing() || a.isDestroyed()) return false;
        int[] loc = new int[2];
        WebView target = webAt(a, x, y, loc);
        if (target == null) return false;
        long now = android.os.SystemClock.uptimeMillis();
        android.view.MotionEvent.PointerProperties pp = new android.view.MotionEvent.PointerProperties();
        pp.id = 0;
        pp.toolType = android.view.MotionEvent.TOOL_TYPE_MOUSE;
        android.view.MotionEvent.PointerCoords pc = new android.view.MotionEvent.PointerCoords();
        pc.x = x - loc[0];
        pc.y = y - loc[1];
        pc.pressure = 1f;
        android.view.MotionEvent ev = android.view.MotionEvent.obtain(now, now,
                android.view.MotionEvent.ACTION_MOVE, 1,
                new android.view.MotionEvent.PointerProperties[]{pp},
                new android.view.MotionEvent.PointerCoords[]{pc},
                0, android.view.MotionEvent.BUTTON_PRIMARY, 1f, 1f, 0, 0,
                android.view.InputDevice.SOURCE_MOUSE, 0);
        a.runOnUiThread(() -> {
            try { target.dispatchGenericMotionEvent(ev); } catch (Throwable ignored) {}
            finally { ev.recycle(); }
        });
        return true;
    }

    /** 右键派发结果：>0 表示 JS 确实在某个元素上派发了 contextmenu，=0 表示什么都没命中。 */
    public interface CtxResult { void onResult(int code); }

    public void triggerContextMenu(float x, float y) {
        triggerContextMenu(x, y, (dockLaunchpadOpen && dockWeb != null && dockReady) ? dockWeb : web, null);
    }

    private void triggerContextMenu(float x, float y, final WebView target) {
        triggerContextMenu(x, y, target, null);
    }

    /**
     * 在 {@code target} 上派发一次 contextmenu，并**回传到底有没有送达**。
     *
     * <p>以前这里是 fire-and-forget，调用方只要「实例在、页面就绪」就当成功返回 true：
     * 屏幕上一个菜单都没有，上层却以为处理完了，连系统兜底都被挡掉 ——
     * 用户看到的就是「右键彻底没反应，日志里还什么都没有」。现在 JS 必须回一个数：
     * 1=命中了具体元素，2=只落在桌面容器上，0=连容器都没找到。
     */
    private void triggerContextMenu(float x, float y, final WebView target, final CtxResult cb) {
        if (target == null) {
            if (cb != null) cb.onResult(-1);
            return;
        }
        if (target == web && !webReady) {
            if (cb != null) cb.onResult(-2);
            return;
        }
        int[] loc = new int[2];
        try {
            target.getLocationOnScreen(loc);
        } catch (Throwable ignored) {
        }
        final float relX = Math.max(0, x - loc[0]);
        final float relY = Math.max(0, y - loc[1]);
        String js = String.format(java.util.Locale.US,
                "(function(x, y) {" +
                "  var dpr = window.devicePixelRatio || 1;" +
                "  var cx = x / dpr;" +
                "  var cy = y / dpr;" +
                "  var el = document.elementFromPoint(cx, cy);" +
                "  var code = 1;" +
                "  if (!el) { el = document.getElementById('desktop'); code = 2; }" +
                "  if (!el) el = document.body;" +
                "  if (!el) return -1;" +
                "  var ev = new MouseEvent('contextmenu', {" +
                "    bubbles: true, cancelable: true, view: window," +
                "    button: 2, buttons: 2, clientX: cx, clientY: cy" +
                "  });" +
                "  el.dispatchEvent(ev);" +
                "  return code;" +
                "})(%.1f, %.1f);", relX, relY);
        try {
            target.evaluateJavascript(js, cb == null ? null : value -> {
                int code = 0;
                try {
                    code = Integer.parseInt(value.replace("\"", "").replace(".0", "").trim());
                } catch (Throwable ignored) {
                }
                cb.onResult(code);
            });
        } catch (Throwable t) {
            if (cb != null) cb.onResult(-3);
        }
    }

    /** 实时广播网络与电池状态给大屏桌面前端 */
    public void broadcastSystemStatusToWeb() {
        if (web == null) return;
        try {
            JSONObject info = new JSONObject();
            int level = -1, status = -1, plugged = 0;
            try {
                Intent b = registerReceiver(null, new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
                if (b != null) {
                    level = b.getIntExtra("level", -1);
                    int scale = b.getIntExtra("scale", 100);
                    status = b.getIntExtra("status", -1);
                    plugged = b.getIntExtra("plugged", 0);
                    if (scale > 0 && level >= 0) level = Math.round(level * 100f / scale);
                }
            } catch (Throwable ignored) {}
            JSONObject bat = new JSONObject();
            bat.put("level", level >= 0 ? level : 100);
            bat.put("charging", status == BatteryManager.BATTERY_STATUS_CHARGING
                    || status == BatteryManager.BATTERY_STATUS_FULL);
            bat.put("plugged", plugged);
            info.put("battery", bat);

            String net = "none";
            try {
                android.net.ConnectivityManager cm = (android.net.ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);
                if (cm != null) {
                    android.net.Network activeNet = cm.getActiveNetwork();
                    if (activeNet != null) {
                        android.net.NetworkCapabilities caps = cm.getNetworkCapabilities(activeNet);
                        if (caps != null) {
                            if (caps.hasTransport(android.net.NetworkCapabilities.TRANSPORT_ETHERNET)) {
                                net = "ethernet";
                            } else if (caps.hasTransport(android.net.NetworkCapabilities.TRANSPORT_WIFI)) {
                                net = "wifi";
                            } else if (caps.hasTransport(android.net.NetworkCapabilities.TRANSPORT_CELLULAR)) {
                                net = "cellular";
                            }
                        }
                    }
                }
            } catch (Throwable ignored) {}
            info.put("net", net);

            final String js = "window.updateSystemStatus && window.updateSystemStatus(" + info.toString() + ");";
            web.post(() -> {
                try { web.evaluateJavascript(js, null); } catch (Throwable ignored) {}
            });
        } catch (Throwable ignored) {}
    }


    private static final String TAG = "ShizukuDeX_DexHome";    public static final String EXTRA_WINDOWING_MODE = "windowing_mode";

    /**
     * 启动页面参数：由串流输入层通过 am start 下发。
     *
     * HOME 键在系统层就被 PhoneWindowManager 拦截（直接启动 home activity），
     * Activity 窗口永远收不到 dispatchKeyEvent。注入层因此把 HOME 翻译成
     * 「带 desktop_page=launchpad 启动桌面」，桌面收到后前置自己并打开启动台，
     * 既保住「HOME = 启动台」的手感，也不会让沧海系统 被系统 launcher 顶掉。
     */
    public static final String EXTRA_DESKTOP_PAGE = "desktop_page";
    public static final String PAGE_LAUNCHPAD = "launchpad";
    /** HOME 键语义：启动台开着就关、关着就开。 */
    public static final String PAGE_HOME = "home";
    private static final int APPWIDGET_HOST_ID = 4213;
    /** 启动应用时把窗口拉成全屏：让「桌面 + 全屏应用」看起来就是一个软件，
     *  屏幕最外层只留我们画的伪装标题栏。设为 false 则保持 DeX 自由窗口（伪标题栏仍显示）。 */
    private static final boolean CAPTION_MAXIMIZE_ON_LAUNCH = true;
    private static final int REQ_BIND_WIDGET = 9011;
    private static final int REQ_DESKTOP_ZIP = 9012;
    /** 外屏页面上 <input type=file>（装沧海软件 / 导入桌面 zip）的文件选择请求码。 */
    private static final int REQ_WEB_FILE = 9013;
    /** 外屏右键「导出沧海软件」时系统「另存为」的请求码。 */
    private static final int REQ_EXPORT = 9014;
    private static final int REQ_WEB_FOLDER = 9015;
    private static final String PREFS = "dexhome_prefs";
    private static final String KEY_BUILTIN_WIDGETS = "builtin_widgets";

    /** 注入到自定义桌面页面的「拉取助手」。
     *  宿主不做任何定时广播、不主动 emit：页面想拿数据，随时调 Aosen.* 同步桥
     *  或 AosenBus.snapshot() 自己取；内置桌面小组件也走同一个通道取电量。 */
    private static final String BUS_JS =
            "(function(){if(window.AosenBus)return;"
            + "var h={};"
            + "window.AosenBus={"
            + "on:function(ev,fn){(h[ev]||(h[ev]=[])).push(fn)},"
            + "off:function(ev,fn){if(!h[ev])return;h[ev]=h[ev].filter(function(f){return f!==fn})},"
            + "emit:function(ev,data){(h[ev]||[]).forEach(function(f){try{f(data)}catch(e){}})},"
            + "pull:function(name){try{var m='get'+name+'Json';"
            + "return typeof Aosen[m]==='function'?JSON.parse(Aosen[m]()||'[]'):null}"
            + "catch(e){return null}},"
            + "snapshot:function(){try{return{apps:JSON.parse(Aosen.getAppsJson()||'[]'),"
            + "dock:JSON.parse(Aosen.getDockJson()||'[]'),"
            + "items:JSON.parse(Aosen.getItemsJson()||'[]'),"
            + "system:JSON.parse(Aosen.getSystemInfoJson()||'{}'),"
            + "time:Date.now()}}catch(e){return{apps:[],dock:[],items:[],system:{},time:Date.now()}}}"
            + "};})();";
    private static final String KEY_LAYOUT = "desktop_layout";   // JSON: ["w:<id>","s:<comp>"]
    private static final String KEY_WIDGET_IDS = "widget_ids";   // 旧版遗留
    private static final String KEY_SHORTCUTS = "desktop_shortcuts"; // 旧版遗留

    private int displayId = -1;
    /**
     * 显示归属边界：true = 纯 Root / Moonlight 虚拟屏（RootDisplayController 启动的
     * CastHomeActivity，displayId>0）；false = 非 Root（默认屏、真实外屏、
     * Shizuku / DeXPresentation 路径的 DexHomeActivity 本体）。
     *
     * <p>DeXPresentation 那条链路的 Dock 是 Presentation 内部的 View 层，完全不经过
     * CanghaiVirtualWindow；这里只隔离「本类及其子类」自己的五层窗口，保证本次
     * Root 修复不会改到非 Root 路径的任何挂载与交互行为。
     */
    private boolean rootVirtualDisplay = false;
    private int windowingMode = 5;
    private boolean webReady = false;
    private BroadcastReceiver lockReceiver;
    private BroadcastReceiver systemStatusReceiver;
    private android.net.ConnectivityManager.NetworkCallback networkCallback;
    /** 待处理的「打开启动台」请求：页面还没加载完时先记下来，onPageFinished 后执行。 */
    private volatile boolean pendingLaunchpad = false;
    /** 网页 <input type=file> 的回调（同一时刻只会有一个，老的先回 null 释放）。 */
    private android.webkit.ValueCallback<android.net.Uri[]> webFileCallback;
    /** 最近一次选中的文件 uri：外屏装软件包时交给 Native 解压（页面解不了 deflate）。 */
    private volatile String lastPickedUri;
    /** 桌面此刻是否在前台：在前台就不要再 am start 拉一遍（会把它变成自由窗口）。 */
    private volatile boolean deskForeground = false;
    /** 待处理请求是否是 HOME 的「切换」语义（否则是强制打开启动台）。 */
    private volatile boolean pendingHomeToggle = false;

    private WebView web;
    private FrameLayout widgetOverlay;
    private boolean customDesktopMode = false;

    /** 任务栏：挂在虚拟屏上的 Presentation 应用窗口，永远在应用窗口之上（官方 DeX 任务栏同架构）。
     *  用 Presentation（TYPE_APPLICATION_ATTACHED_DIALOG）而非 OVERLAY 悬浮窗，
     *  从根上消除 Android 12+ 的「不可信触摸」遮挡丢点击。
     *  双窗口：probe=底部 12dp 常驻悬停探针（隐藏时鼠标压底边缘唤回）；
     *  bar=任务栏+开始菜单容器，隐藏时整个摘除，触摸不吞应用点击。 */
    private WebView tbWeb;
    private boolean tbReady = false;
    private CanghaiVirtualWindow tbWin;
    private FrameLayout tbHolder;
    private FrameLayout probeHolder;
    private CanghaiVirtualWindow probeWin;
    private float tbDensity = 1f;
    private int barHpx;        // 任务栏窗口常态高（含底边距）：CSS 66px
    private int dockExpandHpx; // 鼠标悬停 Dock 时临时加高：容纳图标放大 + 应用名标签
    private int openHpx;       // 菜单展开时窗口高：CSS 716px
    private boolean dockExpanded = false;  // 仅 hover 期间为 true，避免平时吞掉应用底部触摸
    private long dockExpandAt = 0L;        // 最近一次加高时刻，防止高度快速来回切换
    private long dockCollapseAt = 0L;      // 最近一次缩回时刻：缩回后短暂禁止立刻再加高
    private boolean dockDragActive = false;// Dock 图标拖拽期间禁止任何缩回
    private Runnable pendingDockCollapse = null;
    private Runnable pendingDockExpand = null;
    private final Handler dockHandler = new Handler(Looper.getMainLooper());
    private boolean tbVisible = false;
    private boolean tbMenuOpen = false;

    /** 顶部伪装标题栏：第三方应用盖住桌面时，浮在最上层画 macOS 风标题栏，
     *  遮住 DeX 原生窗口装饰；返回桌面即收起。
     *  同样用 Presentation 承载（非 OVERLAY 悬浮窗）；绿点隐藏后由顶部 8dp 探针悬停唤回。 */
    private WebView capWeb;
    private boolean capReady = false;
    private FrameLayout capHolder;
    private CanghaiVirtualWindow capWin;
    private FrameLayout capProbeHolder;
    private CanghaiVirtualWindow capProbeWin;
    private boolean capVisible = false;      // 窗口当前是否挂在虚拟屏上
    private boolean capProbeVisible = false;// 顶部探针当前是否挂载（避免重复 show）
    private boolean capAutoHidden = false;   // 用户点绿点隐藏（探针可唤回）
    private int capBarHpx;                  // 标题栏窗口高（px），跟随窗口移动时复用
    private int capProbeHpx;                // 顶部探针高（px）
    private String capPkg = "";              // 当前前景应用包名
    private String capLabel = "";            // 当前前景应用名
    private volatile boolean capMaximized = false;  // 前景窗口是否处于全屏（决定 Dock 显隐）
    private volatile int[] capDragBounds = null;    // 拖动开始时的窗口 bounds，避免每帧查 am stack

    private final Map<Integer, AppWidgetHostView> widgetViews = new HashMap<>();
    private AppWidgetHost widgetHost;
    private int pendingBindWidgetId = -1;
    private int pendingMoveWid = -1;

    /** 桌面内容顺序：小组件/快捷方式混排 */
    private static class Item {
        boolean widget;
        int wid;
        String comp;
    }
    private final List<Item> items = new ArrayList<>();

    /** 开始菜单应用：comp -> {label, iconPath} */
    private final List<String> appComps = new ArrayList<>();
    private final Map<String, String> appLabels = new HashMap<>();
    private final Map<String, String> iconPaths = new HashMap<>();
    /** 应用扫描防重入 + 空结果（首次系统授权弹窗会挡住读取）自动重扫 */
    private volatile boolean appsLoading = false;
    private int appsRetry = 0;

    /** 真·窗口壳：应用窗口挂这一层（桌面 WebView 之上、悬浮 Dock 之下）。 */
    private FrameLayout shellLayer;
    private AppShellManager shell;

    /** Dock 固定 8 个应用（第一项启动台按钮由前端固定渲染），comp 列表持久化。 */
    private static final int DOCK_MAX = 8;
    private final List<String> dockComps = new ArrayList<>();
    private boolean dockLoaded = false;
    private boolean dockUserCustomized = false;

    /**
     * 取本 Activity 所在屏幕的 id。
     *
     * Context#getDisplay() 是 API 30 才加的方法，Android 9/10 上直接调会抛
     * NoSuchMethodError（Error，catch RuntimeException 兜不住）→ 一开外屏就闪退。
     * 低版本退回 WindowManager#getDefaultDisplay()，它跟着 Activity 所在的屏走。
     */
    private int resolveDisplayId() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                Display d = getDisplay();
                if (d != null) return d.getDisplayId();
            }
        } catch (Throwable ignored) {
        }
        try {
            android.view.WindowManager wm = getWindowManager();
            Display d = (wm == null) ? null : wm.getDefaultDisplay();
            if (d != null) return d.getDisplayId();
        } catch (Throwable ignored) {
        }
        return -1;
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        sInstance = this;

        super.onCreate(savedInstanceState);
        displayId = resolveDisplayId();
        // Root 虚拟屏由 RootDisplayController 拉起 CastHomeActivity（独立 task、独立 display）；
        // 非 Root 一律是 DexHomeActivity 本体。这是本次修复唯一允许生效的分支。
        rootVirtualDisplay = (this instanceof CastHomeActivity) && displayId > 0;
        CanghaiErrorCollector.logExt("【重要】DexHomeActivity.onCreate 被启动！所在 displayId=" + displayId + ", taskId=" + getTaskId());
        CanghaiErrorCollector.logExt("[显示归属] 组件=" + getClass().getSimpleName()
                + " display=" + displayId
                + " 路径=" + (rootVirtualDisplay ? "Root/Moonlight 虚拟屏" : "非 Root（默认屏/外屏/DeX）")
                + "；透明探针 / 任务栏 / Dock / 标题栏探针 / 标题栏 五层均只挂 display " + displayId
                + "；" + (rootVirtualDisplay
                        ? "Dock 改为底部可触摸条带（应用区无覆盖窗），点击走系统命中"
                        : "保留原有可触摸区域声明与交互，不做任何 Root 侧改动"));
        // 桌面可能是被单独拉起的（不经过 MainActivity）。纯 Root 下虚拟屏创建、
        // IME 策略、应用壳都依赖 root 桥句柄，这里预热一次，后台线程、幂等。
        try {
            com.aosen.shizukudex.ShizukuCmd.setAppContext(getApplicationContext());
            CanghaiStore.syncCursorPrefsToNative(getApplicationContext());
            RootInputBridge.warmUp(getApplicationContext());
            // 桌面单独拉起时也要能感知手柄重连，否则补绑逻辑不会生效。
            com.aosen.shizukudex.ShizukuCmd.watchInputDevices(getApplicationContext());
        } catch (Throwable ignored) {}
        if (displayId > 0) {
            sInstancesByDisplay.put(displayId, this);
            // 自愈：桌面所在的这块屏如果不是手机主屏，就把外接鼠标/键盘/蓝牙手柄强制绑过来。
            // 不绑的话系统一律把它们留在「真 HDMI 外接屏」或上一次会话遗留的旧屏上，
            // 本屏收不到任何输入（串流里手柄没反应、输入监视抓不到）。
            // 延迟 1.2s 等虚拟屏稳定；一次性后台事务，不在输入热路径。
            final int selfDisplayId = displayId;
            new Thread(() -> {
                try {
                    Thread.sleep(1200);
                    ShizukuCmd.bindAllExternalInputToDisplay(selfDisplayId, true);
                    Log.i(TAG, "桌面自愈：外接输入已绑定 display=" + selfDisplayId);
                } catch (Throwable t) {
                    Log.w(TAG, "桌面自愈绑定失败: " + t.getMessage());
                }
            }, "aosen-self-bind").start();
        }
        windowingMode = getIntent().getIntExtra(EXTRA_WINDOWING_MODE, 5);
        handleDesktopPage(getIntent());
        Log.i(TAG, "DexHome(HTML) 启动: displayId=" + displayId + " windowingMode=" + windowingMode);

        // 直接隐藏导航栏与状态栏，全屏沉浸
        getWindow().setNavigationBarColor(0x00000000);
        getWindow().setStatusBarColor(0x00000000);
        // 外屏横向宽屏下：输入法弹出时保持悬浮，禁止挤压或整体顶起桌面视口
        getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_NOTHING
                | WindowManager.LayoutParams.SOFT_INPUT_STATE_UNCHANGED);
        if (Build.VERSION.SDK_INT >= 29) {
            getWindow().setNavigationBarContrastEnforced(false);
            getWindow().setStatusBarContrastEnforced(false);
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            try {
                WindowManager.LayoutParams attrs = getWindow().getAttributes();
                attrs.layoutInDisplayCutoutMode =
                        WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES;
                getWindow().setAttributes(attrs);
            } catch (Throwable ignored) {}
        }
        // 隐藏系统指针精灵：桌面虚拟屏上的系统箭头（含左上角 (0,0) 不动的初始位置）
        // 与我们的软件指针重影，改用 1x1 透明位图图标（公开 API，三星 TYPE_NULL 不可靠）
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            try {
                android.view.PointerIcon themed = EmptyPointerFrameLayout.getThemedPointerIcon(this);
                if (themed != null) getWindow().getDecorView().setPointerIcon(themed);
            } catch (Throwable t) {
                Log.w(TAG, "设置系统指针失败: " + t.getMessage());
            }
        }

        FrameLayout root = new EmptyPointerFrameLayout(this);
        root.setBackgroundColor(0xFF0E1014);
        root.setOnApplyWindowInsetsListener((v, insets) -> {
            int safeTop = 0, safeBottom = 0, safeLeft = 0, safeRight = 0;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                android.view.DisplayCutout cutout = insets.getDisplayCutout();
                if (cutout != null) {
                    safeTop = cutout.getSafeInsetTop();
                    safeBottom = cutout.getSafeInsetBottom();
                    safeLeft = cutout.getSafeInsetLeft();
                    safeRight = cutout.getSafeInsetRight();
                }
            }
            float density = getResources().getDisplayMetrics().density;
            if (density <= 0) density = 1.0f;
            final int st = Math.round(safeTop / density);
            final int sb = Math.round(safeBottom / density);
            final int sl = Math.round(safeLeft / density);
            final int sr = Math.round(safeRight / density);
            if (web != null) {
                web.post(() -> {
                    try {
                        String js = String.format(java.util.Locale.ROOT,
                                "document.documentElement.style.setProperty('--safe-top','%dpx');" +
                                "document.documentElement.style.setProperty('--safe-bottom','%dpx');" +
                                "document.documentElement.style.setProperty('--safe-left','%dpx');" +
                                "document.documentElement.style.setProperty('--safe-right','%dpx');" +
                                "window.computeGrid && window.computeGrid();", st, sb, sl, sr);
                        web.evaluateJavascript(js, null);
                    } catch (Throwable ignored) {}
                });
            }
            return insets;
        });

        web = new WebView(this) {
            @Override
            public android.view.PointerIcon onResolvePointerIcon(MotionEvent event, int pointerIndex) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                    android.view.PointerIcon icon = super.onResolvePointerIcon(event, pointerIndex);
                    if (icon == null) {
                        return EmptyPointerFrameLayout.getThemedPointerIcon(getContext());
                    }
                    int type = EmptyPointerFrameLayout.getPointerIconType(icon);
                    if (type == android.view.PointerIcon.TYPE_ARROW || type == android.view.PointerIcon.TYPE_DEFAULT || type == -1) {
                        return EmptyPointerFrameLayout.getThemedPointerIcon(getContext());
                    }
                    return icon;
                }
                return super.onResolvePointerIcon(event, pointerIndex);
            }

            @Override
            public android.view.inputmethod.InputConnection onCreateInputConnection(
                    android.view.inputmethod.EditorInfo outAttrs) {
                MainActivity.onExternalInteractionOrIme();
                ShizukuCmd.enableFloatingKeyboard();
                android.view.inputmethod.InputConnection ic = super.onCreateInputConnection(outAttrs);
                if (outAttrs != null) {
                    // 禁止横向全屏提取编辑框，请求输入法以小巧悬浮窗模式展示
                    outAttrs.imeOptions |= android.view.inputmethod.EditorInfo.IME_FLAG_NO_EXTRACT_UI
                            | android.view.inputmethod.EditorInfo.IME_FLAG_NO_FULLSCREEN;
                    String priv = outAttrs.privateImeOptions == null ? "" : (outAttrs.privateImeOptions + ";");
                    outAttrs.privateImeOptions = priv
                            + "inputType=floating;default_keyboard_mode=floating;"
                            + "com.samsung.android.honeyboard.floating=1;"
                            + "com.google.android.inputmethod.latin.floatingKeyboard=true";
                }
                return ic;
            }
        };
        WebSettings ws = web.getSettings();
        ws.setJavaScriptEnabled(true);
        ws.setAllowFileAccess(true);
        ws.setAllowFileAccessFromFileURLs(true);
        ws.setAllowUniversalAccessFromFileURLs(true);
        ws.setAllowContentAccess(true);
        ws.setDomStorageEnabled(true);   // 自定义桌面可用 localStorage 存自己的布局
        ws.setMediaPlaybackRequiresUserGesture(false);
        ws.setCacheMode(WebSettings.LOAD_NO_CACHE);
        web.setBackgroundColor(0xFF0E1014);
        web.setWebViewClient(new WebViewClient() {
            @Override
            public void onPageFinished(WebView v, String url) {
                webReady = true;
                CanghaiErrorCollector.logExt("【重要】DexHomeActivity 桌面 WebView onPageFinished 成功！url=" + url + ", 尺寸=" + (web != null ? (web.getWidth() + "x" + web.getHeight()) : "null"));
                // 只注入一个「拉取助手」，宿主不主动推送任何数据、不做定时广播；
                // 内置桌面小组件与自定义桌面都按需调 Aosen.* / AosenBus.snapshot()。
                v.evaluateJavascript(BUS_JS
                        + "\ntry{window.onAosenReady&&window.onAosenReady()}catch(e){}"
                        + "try{document.dispatchEvent(new Event('aosen:ready'))}catch(e){}", null);
                pushWallpaper();
                pushApps();
                pushItems();
                pushDock();
                // HOME 键在页面还没加载完时就按了：等首屏就绪再补处理。
                if (pendingLaunchpad) {
                    v.postDelayed(DexHomeActivity.this::deliverPendingPage, 260);
                }
                try {
                    KeyguardManager km = (KeyguardManager) getSystemService(Context.KEYGUARD_SERVICE);
                    boolean locked = (km != null && km.isKeyguardLocked());
                    v.evaluateJavascript("window.onDeviceLockChanged && window.onDeviceLockChanged(" + locked + ");", null);
                    int actId = MainActivity.getActiveDisplayIdStatic();
                    v.evaluateJavascript("window.updateTopBarActiveScreen && window.updateTopBarActiveScreen(" + actId + ");", null);
                    com.alkaidlab.sunshine.service.MouseCursorPrefs.load(DexHomeActivity.this);
                    int curStyle = com.alkaidlab.sunshine.service.MouseCursorPrefs.getStyle();
                    v.evaluateJavascript("window.applyCursorTheme && window.applyCursorTheme(" + curStyle + ");", null);
                } catch (Throwable ignored) {}
            }
        });
        web.setWebChromeClient(new WebChromeClient() {
            @Override
            public boolean onShowFileChooser(android.webkit.WebView v,
                                             android.webkit.ValueCallback<android.net.Uri[]> cb,
                                             FileChooserParams params) {
                return pickWebFile(cb, params);
            }

            @Override
            public boolean onConsoleMessage(android.webkit.ConsoleMessage m) {
                /* 桌面页面的 console 一律转到 logcat：层叠的 WebView + evaluateJavascript
                 * 出问题时，这里捕获并存入内存错误收集器。 */
                if (m == null) return false;
                String msg = m.message();
                String loc = m.sourceId() + ":" + m.lineNumber();
                Log.i(TAG, "[web] " + msg + " @" + loc);
                if (m.messageLevel() == android.webkit.ConsoleMessage.MessageLevel.ERROR
                        || (msg != null && (msg.toLowerCase().contains("uncaught") || msg.toLowerCase().contains("error")))) {
                    CanghaiErrorCollector.getInstance().log("大屏桌面", "ERROR", msg, loc);
                }
                return true;
            }
        });
        web.addJavascriptInterface(new Bridge(), "Aosen");
        web.addJavascriptInterface(new Bridge(), "Canghai");
        /* 统一通道：宿主页面是 L1，桌面按 iframe 的 e.source 认应用身份后代理调用 */
        CanghaiCore.attach(this, web, null);
        loadDesktopPage();
        root.addView(web, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        // 原生小组件覆盖层（在 WebView 之上，位置由 JS 上报对齐占位框）
        widgetOverlay = new FrameLayout(this);
        root.addView(widgetOverlay, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        // 应用窗口壳层：最上层（但 y >= 菜单栏高度，顶部菜单栏永远露出来）。
        // 透明 FrameLayout 在无子 View 命中时不消费触摸，桌面点击仍能落到下面的 WebView。
        shellLayer = new FrameLayout(this);
        root.addView(shellLayer, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        // 确保窗口及根视图可聚焦以接收手柄与键盘硬件按键，防止 InputDispatcher 抛出 ANR
        getWindow().getDecorView().setFocusable(true);
        getWindow().getDecorView().setFocusableInTouchMode(true);
        root.setFocusable(true);
        root.setFocusableInTouchMode(true);
        web.setFocusable(true);
        web.setFocusableInTouchMode(true);

        setContentView(root);
        hideNavBar();
        shell = new AppShellManager(this, shellLayer, this::onShellFullscreenChanged);
        loadDock();
        /* Dock 独立悬浮层：让 Dock 永远压在原生应用窗口之上 */
        setupCanghaiDock(0);

        widgetHost = new AppWidgetHost(this, APPWIDGET_HOST_ID);
        loadLayout();           // 含旧数据迁移
        restoreWidgets();       // 为每个 widget id 建原生视图
        loadAppsAsync();        // 应用列表 + 图标缓存
        loadWallpaperAsync();   // 壁纸缓存文件

        // 清掉三星自动拉起的 DeX 桌面（SecondaryLauncher），防它的任务栏藏底边
        cleanupSamsungDexLauncher(true);

        // 监听系统锁屏与解锁广播
        lockReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                String action = intent.getAction();
                if (Intent.ACTION_SCREEN_OFF.equals(action)) {
                    jsEval("window.onDeviceLockChanged && window.onDeviceLockChanged(true);");
                } else if (Intent.ACTION_USER_PRESENT.equals(action)) {
                    jsEval("window.onDeviceLockChanged && window.onDeviceLockChanged(false);");
                } else if (Intent.ACTION_SCREEN_ON.equals(action)) {
                    try {
                        KeyguardManager km = (KeyguardManager) getSystemService(Context.KEYGUARD_SERVICE);
                        if (km != null && !km.isKeyguardLocked()) {
                            jsEval("window.onDeviceLockChanged && window.onDeviceLockChanged(false);");
                        }
                    } catch (Throwable ignored) {}
                }
            }
        };
        IntentFilter lockFilter = new IntentFilter();
        lockFilter.addAction(Intent.ACTION_SCREEN_OFF);
        lockFilter.addAction(Intent.ACTION_SCREEN_ON);
        lockFilter.addAction(Intent.ACTION_USER_PRESENT);
        registerReceiver(lockReceiver, lockFilter);

        // 实时网络与电池状态监听：WiFi / 有线以太网 / 断网 与电量实时推送到大屏状态栏
        systemStatusReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                broadcastSystemStatusToWeb();
            }
        };
        IntentFilter statusFilter = new IntentFilter();
        statusFilter.addAction(Intent.ACTION_BATTERY_CHANGED);
        statusFilter.addAction(Intent.ACTION_POWER_CONNECTED);
        statusFilter.addAction(Intent.ACTION_POWER_DISCONNECTED);
        registerReceiver(systemStatusReceiver, statusFilter);

        try {
            android.net.ConnectivityManager cm = (android.net.ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);
            if (cm != null) {
                networkCallback = new android.net.ConnectivityManager.NetworkCallback() {
                    @Override
                    public void onAvailable(android.net.Network network) {
                        broadcastSystemStatusToWeb();
                    }
                    @Override
                    public void onLost(android.net.Network network) {
                        broadcastSystemStatusToWeb();
                    }
                    @Override
                    public void onCapabilitiesChanged(android.net.Network network, android.net.NetworkCapabilities networkCapabilities) {
                        broadcastSystemStatusToWeb();
                    }
                };
                cm.registerDefaultNetworkCallback(networkCallback);
            }
        } catch (Throwable ignored) {}
    }

    /**
     * 解析启动参数。
     *  - desktop_page=launchpad：强制打开启动台（外部「全部应用」入口）
     *  - desktop_page=home：HOME 键语义，启动台开着就关掉、关着就打开
     */
    private void handleDesktopPage(Intent intent) {
        if (intent == null) return;
        String page = intent.getStringExtra(EXTRA_DESKTOP_PAGE);
        if (page == null) return;
        if (!PAGE_LAUNCHPAD.equals(page) && !PAGE_HOME.equals(page)) return;
        pendingLaunchpad = true;
        pendingHomeToggle = PAGE_HOME.equals(page);
        Log.i(TAG, "收到 desktop_page=" + page + "，webReady=" + webReady);
        if (webReady) {
            web.postDelayed(this::deliverPendingPage, 120);
        }
    }

    /** 把待处理的 HOME / 启动台请求投递给页面（只执行一次）。 */
    private void deliverPendingPage() {
        if (!pendingLaunchpad) return;
        pendingLaunchpad = false;
        pendingHomeToggle = false;
        /* HOME 一律「打开启动台」，不做 toggle。
         * 物理 HOME 在 policy 层就被系统消费了，我们收不到，只能靠失焦自愈拉回；
         * 而系统的 HOME 处理与我们拉回存在竞态，一次按键可能投递两次 ——
         * 用 toggle 就会看到「启动台自己开开关关」。关闭走手柄 B / 点空白。 */
        if (dockReady && dockWeb != null) {
            runOnUiThread(() -> setDockLaunchpadOpen(true));
            return;
        }
        openLaunchpadByJs("HOME");
    }

    /** 启动台总开关：Dock 悬浮层在 → 开关 Dock 页启动台；不在 → 退回桌面页启动台。 */
    public void toggleLaunchpadFromController() {
        toggleLaunchpadAny();
    }

    private void toggleLaunchpadAny() {
        runOnUiThread(() -> {
            if (web != null) {
                web.evaluateJavascript("window.toggleLaunchpad ? window.toggleLaunchpad() : (typeof toggleLaunchpad === 'function' ? toggleLaunchpad() : null);", null);
            }
        });
    }

    /**
     * 让桌面页面打开启动台。
     *
     * 页面里 openLaunchpad 是顶层函数（不一定会挂到 window），window.toggleLaunchpad
     * 是显式挂载的，两个都试一遍并回读结果，避免「调了但没反应」查不出原因。
     */
    private void openLaunchpadByJs(String reason) {
        if (!webReady || web == null) {
            Log.w(TAG, "打开启动台失败(" + reason + "): 页面未就绪");
            return;
        }
        String script = "(function(){try{"
                + "if(typeof window.openLaunchpad==='function'){window.openLaunchpad();return 'openLaunchpad';}"
                + "if(typeof openLaunchpad==='function'){openLaunchpad();return 'scope-openLaunchpad';}"
                + "if(typeof window.toggleLaunchpad==='function'){window.toggleLaunchpad(true);return 'toggleLaunchpad';}"
                + "return 'none';}catch(e){return 'err:'+e.message;}})()";
        runOnUiThread(() -> {
            try {
                web.evaluateJavascript(script, r -> Log.i(TAG, "打开启动台(" + reason + ") 结果=" + r));
            } catch (Throwable t) {
                Log.w(TAG, "打开启动台异常(" + reason + "): " + t.getMessage());
            }
        });
    }

    /**
     * 载入桌面页面：若已导入自定义桌面 zip，则加载其根目录下的 index.html；
     * 否则加载内置 assets 桌面。
     * 自定义页面完全离线跑在 file:// 下，宿主只提供 window.Aosen 同步桥，
     * 不注入任何内置 CSS/JS、不主动推送数据、不轮询、不广播。
     */
    private void loadDesktopPage() {
        if (web == null) return;
        customDesktopMode = CustomDesktopManager.has(this);
        String url = customDesktopMode
                ? CustomDesktopManager.entryUrl(this)
                : "file:///android_asset/canghai/desktop/index.html";
        Log.i(TAG, "载入桌面页面: " + url + " (custom=" + customDesktopMode + ")");
        webReady = false;
        web.loadUrl(url);
    }

    /**
     * 取 UI 层的 base Context：只需要能取到 applicationContext / DisplayManager，
     * CanghaiVirtualWindow 会用 {@code applicationContext.createDisplayContext(本屏 Display)}
     * 把窗口精确挂到 {@link #displayId}，不要求 base 带 window token。
     *
     * <p><b>按显示归属分流</b>：Root 虚拟屏一律用本进程的 applicationContext，
     * 绝不借用 {@code DeXPresentation.getActiveInstance()} —— 那是非 Root 链路的
     * ContextThemeWrapper，残留实例会把 Root 侧的 Dock/任务栏串到别人的主题与屏上。
     * 非 Root 路径保持原优先级（DeXPresentation → MainActivity → applicationContext）不变。
     */
    private Context hostBaseContext() {
        if (rootVirtualDisplay) return getApplicationContext();
        DeXPresentation p = DeXPresentation.getActiveInstance();
        if (p != null) return p.getContext();
        MainActivity m = MainActivity.getInstance();
        if (m != null) return m;
        return getApplicationContext();
    }

    /** 任务栏悬浮窗：挂在虚拟屏上的 overlay WebView，永远在应用窗口之上（官方 DeX 任务栏同架构）。
     *  probe = 底部 12dp 常驻悬停探针（任务栏隐藏时鼠标压底边缘唤回）；
     *  bar = 任务栏/开始菜单容器窗口，隐藏时整体摘除（触摸不吞应用窗口）。 */
    private void setupTaskbarOverlay(final int retry) {
        if (isFinishing() || isDestroyed()) return;
        Display d = (displayId >= 0)
                ? getSystemService(DisplayManager.class).getDisplay(displayId) : null;
        if (d == null) {
            if (retry < 10) {
                runOnUiThread(() -> new Handler(Looper.getMainLooper())
                        .postDelayed(() -> setupTaskbarOverlay(retry + 1), 400));
            } else {
                Log.w(TAG, "虚拟屏未就绪，任务栏悬浮窗创建失败");
            }
            return;
        }
        try {
            Context dc = hostBaseContext().createDisplayContext(d);
            tbDensity = dc.getResources().getDisplayMetrics().density;
            barHpx = Math.round(96 * tbDensity);   // 任务栏窗口固定高，留足上部余量，彻底消除 hover 抖动
            dockExpandHpx = barHpx;
            openHpx = Math.round(716 * tbDensity); // 菜单展开: 640 + 76

            // ---- 探针窗口：底部 12dp，悬停唤回任务栏 ----
            View probe = new View(dc);
            probe.setOnHoverListener((v, ev) -> {
                if (ev.getAction() == MotionEvent.ACTION_HOVER_ENTER
                        || ev.getAction() == MotionEvent.ACTION_HOVER_MOVE) {
                    jsEvalTb("if (taskbarHidden) showTaskbar();");
                }
                return true;
            });
            // 探针改用 Presentation（TYPE_APPLICATION_ATTACHED_DIALOG）承载，规避 OVERLAY 不可信触摸拦截
            probeHolder = new FrameLayout(dc);
            probeHolder.addView(probe, new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
            probeWin = new CanghaiVirtualWindow(this::hostBaseContext, displayId, probeHolder,
                    ViewGroup.LayoutParams.MATCH_PARENT, Math.round(12 * tbDensity),
                    0, 0, android.view.Gravity.BOTTOM,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                            | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                    android.graphics.PixelFormat.TRANSLUCENT, false);
            probeWin.show();

            // ---- 任务栏窗口 ----
            tbWeb = new WebView(dc);
            WebSettings ws = tbWeb.getSettings();
            ws.setJavaScriptEnabled(true);
            ws.setAllowFileAccess(true);
            ws.setAllowFileAccessFromFileURLs(true);
            ws.setAllowUniversalAccessFromFileURLs(true);
            ws.setCacheMode(WebSettings.LOAD_NO_CACHE);
            tbWeb.setBackgroundColor(0x00000000);
            tbWeb.setWebViewClient(new WebViewClient() {
                @Override
                public void onPageFinished(WebView v, String url) {
                    tbReady = true;
                    pushDockData();
                    // 悬浮窗首帧尺寸可能尚未稳定，稍后再补一次，保证 Dock 按最终宽度排布
                    if (tbWeb != null) {
                        tbWeb.postDelayed(DexHomeActivity.this::pushDockData, 700);
                    }
                }
            });
            tbWeb.setWebChromeClient(new WebChromeClient());
            tbWeb.addJavascriptInterface(new Bridge(), "Aosen");
            tbWeb.addJavascriptInterface(new Bridge(), "Canghai");

            tbHolder = new FrameLayout(dc);
            tbHolder.addView(tbWeb, new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

            // 任务栏改用 Presentation 承载：窗口类型 ATTACHED_DIALOG（非 OVERLAY），从根消除遮挡丢点击
            tbWin = new CanghaiVirtualWindow(this::hostBaseContext, displayId, tbHolder,
                    ViewGroup.LayoutParams.MATCH_PARENT, barHpx,
                    0, 0, android.view.Gravity.BOTTOM,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                            | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                    android.graphics.PixelFormat.TRANSLUCENT, false);
            tbWin.show();
            tbVisible = true;
            // 先挂载窗口再加载页面：WebView 首次布局就能拿到真实宽度，避免 Dock 被压成小方块
            tbWeb.loadUrl("file:///android_asset/dexhome/index.html?role=taskbar");
            Log.i(TAG, "任务栏悬浮窗已创建 display=" + displayId);

            // 真·窗口壳：应用跑在独立虚拟屏（无系统装饰），标题栏由每个窗格自绘，
            // 不再需要全局伪装标题栏浮层。setupCaptionOverlay() 保留以兼容旧桥方法，但不再创建。
        } catch (Throwable t) {
            Log.w(TAG, "任务栏悬浮窗创建失败: " + t.getMessage(), t);
        }
    }

    // ==================== 沧海 Dock 独立层（Presentation 应用窗口） ====================
    // 桌面 Activity 永远在系统应用窗口之下，原生应用一起来就把页面里的 Dock 压没了。
    // 所以 Dock 单独用 Presentation（TYPE_APPLICATION_ATTACHED_DIALOG）挂到这块屏上，
    // 内容还是咱们自己的网页（assets/canghai/dock/index.html），
    // 样式与图标跟桌面页共用 shared/dock.css + shared/apps.js + shared/appcolor.js。
    // 改用 Presentation 而非 OVERLAY 悬浮窗：从根消除 Android 12+ 不可信触摸遮挡丢点击。
    private WebView dockWeb;
    private EmptyPointerFrameLayout dockHolder;
    private CanghaiVirtualWindow dockWin;
    private boolean dockReady = false;
    /** 直派触摸给 Dock 时，把 DOWN 的时间记下来，MOVE/UP 才能和 DOWN 配对成同一手势。 */
    private long lastDockDownTime = 0L;

    /* ==================== 调试：整层摘掉 / 挂回 Dock 悬浮窗 ====================
     * 只用于「管理渲染层」逐层二分遮挡来源：View.GONE 不会让窗口退出
     * InputDispatcher 的遮挡栈，必须真的 removeView 才摘得掉。 */
    private static volatile boolean sDockUserBlocked = false;

    public static void setDockLayerEnabled(boolean on) {
        sDockUserBlocked = !on;
        java.util.Set<DexHomeActivity> all = new java.util.HashSet<>();
        try {
            synchronized (sInstancesByDisplay) { all.addAll(sInstancesByDisplay.values()); }
        } catch (Throwable ignored) {}
        if (sInstance != null) all.add(sInstance);
        for (DexHomeActivity a : all) {
            try { a.runOnUiThread(a::applyDockLayerBlocked); } catch (Throwable ignored) {}
        }
    }

    private void applyDockLayerBlocked() {
        if (dockHolder == null || dockWin == null) return;
        if (rootVirtualDisplay) { applyDockWindowVisibility(); return; }
        if (sDockUserBlocked) {
            dockWin.dismiss();
        } else {
            dockWin.show();
        }
    }

    /** Root 虚拟屏：Dock 隐藏时真的把条带窗口摘掉，避免一条透明 NOT_TOUCHABLE 窗口仍挡住应用底部。 */
    private void applyDockWindowVisibility() {
        if (dockWin == null) return;
        if (!sDockUserBlocked && !dockHidden) {
            dockWin.show();
        } else {
            dockWin.dismiss();
        }
    }

    // ==================== 沧海 Dock：设置 / 自动隐藏 / 悬停加高 / 运行态 ====================
    private static final String PREF_DOCK_AUTO_HIDE = "dock_auto_hide";
    /** 默认常驻显示：开机/重连默认不自动隐藏，Dock 栏始终可见且可点。 */
    private boolean dockAutoHide = false;
    private boolean dockHidden = false;
    private boolean dockHoverOn = false;
    private boolean dockFullscreenNative = false;   // 系统应用窗口最大化
    private boolean dockFullscreenWeb = false;      // 桌面页里的沧海软件最大化
    private boolean dockLaunchpadOpen = false;      // Dock 页启动台打开：Dock 悬浮窗拉成全屏
    private boolean deskLaunchpadOpen = false;      // 桌面页自己的启动台（悬浮层不在时的 fallback）打开
    private boolean canghaiDockExpanded = false;   // hover 时窗口加高，提示不被裁
    /** 鼠标是否真的悬在 Dock 悬浮窗里（原生 hover 事件，窗口加高不会重派，抖动免疫）。 */
    private boolean chDockNativeHover = false;
    private int dockFullHpx = 0, dockExpandedHpx = 0, dockProbeHpx = 0;
    private FrameLayout dockProbeHolder;
    private WindowManager.LayoutParams dockProbeLp;
    private String dockRunStateJson = "{}";
    private String lastDockPushSig = "";
    private final Handler chDockHandler = new Handler(Looper.getMainLooper());
    private Runnable chDockCollapse;

    private boolean readDockAutoHide() {
        return CanghaiStore.getDockAutoHide(this);
    }

    private void saveDockAutoHide(boolean on) {
        dockAutoHide = on;
        CanghaiStore.setDockAutoHide(this, on);
        getSharedPreferences(PREFS, MODE_PRIVATE).edit()
                .putBoolean("canghai_dock_auto_hide", on)
                .putBoolean(displayKey() + "_dockAutoHide", on).apply();
        Log.i(TAG, "Dock 自动隐藏设置 = " + on);
        refreshCanghaiDock();
        pushDock();
    }

    /**
     * 每块外屏的唯一持久化 key：优先显示名 + 分辨率，取不到就用 displayId。
     * 设置和桌面布局都按这个 key 分开存，换一块屏就是一套自己的桌面。
     */
    private String displayKey() {
        Display d = (displayId >= 0)
                ? getSystemService(DisplayManager.class).getDisplay(displayId) : null;
        String uid = null;
        if (d != null) {
            String name = null;
            try { name = d.getName(); } catch (Throwable ignored) { }
            if (name == null || name.isEmpty()) name = "display";
            try {
                java.lang.reflect.Method m = Display.class.getMethod("getUniqueId");
                Object r = m.invoke(d);
                if (r != null && !r.toString().isEmpty()) name = name + "|" + r;
            } catch (Throwable ignored) { }
            int w = 0, h = 0;
            try {
                android.util.DisplayMetrics dm = new android.util.DisplayMetrics();
                d.getRealMetrics(dm);
                w = dm.widthPixels; h = dm.heightPixels;
            } catch (Throwable ignored) { }
            uid = name + "|" + w + "x" + h;
        } else {
            uid = "display" + displayId;
        }
        String safe = uid.replaceAll("[^A-Za-z0-9]", "_");
        if (safe.length() > 40) safe = safe.substring(0, 40);
        return "deskstate_" + Integer.toHexString(uid.hashCode()) + "_" + safe;
    }

    private void cancelCanghaiDockCollapse() {
        if (chDockCollapse != null) {
            chDockHandler.removeCallbacks(chDockCollapse);
            chDockCollapse = null;
        }
    }

    private final android.graphics.Rect dockBoundsPx = new android.graphics.Rect();
    private final java.util.List<Rect> mTouchableRectList = new java.util.concurrent.CopyOnWriteArrayList<>();
    private volatile boolean mAllTouchable = false;
    private float dockLeftFrac = 0.05f;
    private float dockTopFrac = 0.35f;
    private float dockRightFrac = 0.95f;
    private float dockBottomFrac = 1.0f;
    private boolean dockBoundsReceived = false;
    private boolean dockMenuOpen = false;

    private void updateDockWindowSize() {
        /* Root 虚拟屏：常态只占底部 Dock 条（应用区头顶无窗口 → 不可能被判遮挡）；
         * 启动台 / 菜单 / 前台 HTML 窗口等模态态临时拉回全屏，让原有全屏 UI 照常工作。 */
        if (rootVirtualDisplay && dockWin != null && dockWin.isTouchable()) {
            boolean full = mAllTouchable || dockLaunchpadOpen || dockMenuOpen;
            int stripH = dockFullHpx > 0 ? dockFullHpx
                    : Math.round(104 * getResources().getDisplayMetrics().density);
            dockWin.updateLayout(ViewGroup.LayoutParams.MATCH_PARENT,
                    full ? ViewGroup.LayoutParams.MATCH_PARENT : Math.max(1, stripH));
        }
        if (dockHolder != null) {
            dockHolder.requestLayout();
            dockHolder.invalidate();
        }
    }

    private void scheduleCanghaiDockCollapse(long delay) {
        cancelCanghaiDockCollapse();
        chDockCollapse = () -> {
            chDockCollapse = null;
            if (chDockNativeHover) { scheduleCanghaiDockCollapse(1200L); return; }
            dockHoverOn = false;
            refreshCanghaiDock();
        };
        chDockHandler.postDelayed(chDockCollapse, Math.max(0L, delay));
    }

    /** 软件全屏 / 桌面页启动台打开时，无论设置里有没有勾「隐藏 Dock」都必须藏起来。
     *  Dock 页自己的启动台不算：启动台属于 Dock 页，打开时 Dock 条照样可见。 */
    private boolean canghaiDockForceHidden() {
        return deskLaunchpadOpen || dockFullscreenNative || dockFullscreenWeb;
    }

    private void refreshCanghaiDock() {
        if (dockWin == null || dockHolder == null) return;
        boolean force = canghaiDockForceHidden();
        boolean hidden = force || (dockAutoHide && !dockHoverOn);
        dockHidden = hidden;
        if (rootVirtualDisplay) applyDockWindowVisibility();
        pushCanghaiDockState();
    }

    private void setCanghaiDockExpanded(boolean open) {
        canghaiDockExpanded = open;
    }

    private void setDockLaunchpadOpen(boolean open) {
        dockLaunchpadOpen = open;
        runOnUiThread(() -> {
            updateDockWindowSize();
            if (dockWeb != null && dockReady) {
                dockWeb.evaluateJavascript(
                        "window.__lpSetOpen && window.__lpSetOpen(" + open + ");", null);
            }
        });
    }

    /** 把设置 / 显隐 / 运行倒影一次性推给悬浮 Dock 网页。 */
    private void pushCanghaiDockState() {
        if (dockWeb == null || !dockReady) return;
        final String script;
        try {
            org.json.JSONObject o = new org.json.JSONObject();
            o.put("hidden", dockHidden);
            o.put("autoHide", dockAutoHide);
            o.put("expanded", canghaiDockExpanded);
            o.put("forceHidden", canghaiDockForceHidden());
            org.json.JSONObject runObj;
            try { runObj = new org.json.JSONObject(dockRunStateJson); }
            catch (Throwable ignored) { runObj = new org.json.JSONObject(); }
            if (runObj.has("runState")) {
                o.put("runState", runObj.optJSONObject("runState"));
                if (runObj.has("dockPins")) o.put("dockPins", runObj.optJSONArray("dockPins"));
                if (runObj.has("recentApps")) o.put("recentApps", runObj.optJSONArray("recentApps"));
            } else {
                o.put("runState", runObj);
            }
            script = "window.__dockApplyState && window.__dockApplyState(" + o.toString() + ");";
        } catch (Throwable t) { return; }
        /* 内容没变就不 evaluate：悬浮 WebView 里的 JS 调用很重，
           每次 hover 都推一遍会让整个 Dock 跟着一顿一顿。 */
        if (script.equals(lastDockPushSig)) return;
        lastDockPushSig = script;
        runOnUiThread(() -> {
            try { dockWeb.evaluateJavascript(script, null); } catch (Throwable ignored) { }
        });
    }

    /** 桌面页问「Dock 是不是已经被独立层接管了」——是的话页面里那条就别画了。 */
    public boolean isDockOverlayOn() {
        return dockReady && dockWeb != null && dockWin != null;
    }

    /* ==================== 大脑直派：把 Dock 事件交给外设大脑分流 ====================
     * Dock 现在是 NOT_TOUCHABLE，系统不再把鼠标事件路由给它，改由大脑判断指针是否
     * 落在 Dock 可交互区，命中就手动把事件派进 Dock 的 WebView，没命中才注入外部应用。
     * 这样 Dock 全屏常驻却不再是遮挡源，点击/悬停/图标跳动一个不少。 */

    public static DexHomeActivity instanceForDisplay(int displayId) {
        if (displayId <= 0) return null;
        synchronized (sInstancesByDisplay) {
            DexHomeActivity a = sInstancesByDisplay.get(displayId);
            if (a != null) return a;
        }
        return sInstance;
    }

    /** 坐标（虚拟屏像素）是否落在 Dock 的可交互区（顶部条 / 底部应用台）。 */
    public static boolean isOnDockRegion(int displayId, float x, float y) {
        DexHomeActivity a = instanceForDisplay(displayId);
        return a != null && a.onDockRegionInternal(x, y);
    }

    private boolean onDockRegionInternal(float x, float y) {
        if (dockHolder == null || dockWeb == null) return false;
        /* Root 条带窗口已可触摸：所有点击都交给系统命中测试，大脑不得再分流/直派，
         * 否则「按在 Dock 区」会被判成直派、直派闸又跳过，点击凭空丢掉。 */
        if (rootVirtualDisplay && dockWin != null && dockWin.isTouchable()) return false;
        if (mAllTouchable || dockLaunchpadOpen || dockMenuOpen) return true;
        java.util.List<android.graphics.Rect> rects = new java.util.ArrayList<>(mTouchableRectList);
        if (rects.isEmpty()) {
            android.graphics.Rect fb = touchableRectFallback();
            if (fb != null && !fb.isEmpty()) rects.add(fb);
        }
        int ix = (int) x, iy = (int) y;
        for (android.graphics.Rect r : rects) {
            if (r.contains(ix, iy)) return true;
        }
        return false;
    }

    private android.graphics.Rect touchableRectFallback() {
        int h = (dockHolder != null) ? dockHolder.getHeight() : 0;
        int w = (dockHolder != null) ? dockHolder.getWidth() : 0;
        return new android.graphics.Rect(0, Math.max(0, h - dockFullHpx), Math.max(1, w), Math.max(1, h));
    }

    /** Root 条带窗口可触摸时真实触摸直接命中，大脑直派必须跳过以免双击；
     * 非 Root 窗口恒 NOT_TOUCHABLE，直派保持生效。 */
    public static boolean isDockDirectTouchActive(int displayId) {
        DexHomeActivity a = instanceForDisplay(displayId);
        return a != null && a.dockWin != null && a.dockWin.isShowing() && a.dockWin.isTouchable();
    }

    /** 直派一次悬停事件给 Dock（HOVER_ENTER/MOVE/EXIT/SCROLL），主线程执行。 */
    public static boolean dispatchToDockHover(int displayId, int action, float x, float y) {
        if (isDockDirectTouchActive(displayId)) return false;
        DexHomeActivity a = instanceForDisplay(displayId);
        if (a == null || a.dockWeb == null || !a.dockReady) return false;
        long now = android.os.SystemClock.uptimeMillis();
        android.view.MotionEvent ev = android.view.MotionEvent.obtain(now, now, action, x, y, 0);
        ev.setSource(android.view.InputDevice.SOURCE_MOUSE);
        a.runOnUiThread(() -> {
            try { a.dockWeb.dispatchGenericMotionEvent(ev); } catch (Throwable ignored) {}
            finally { ev.recycle(); }
        });
        return true;
    }

    /** 直派一次触摸事件给 Dock（DOWN/MOVE/UP），主线程执行，保持 DOWN/UP 配对。 */
    public static boolean dispatchToDockTouch(int displayId, int action, float x, float y) {
        if (isDockDirectTouchActive(displayId)) return false;
        DexHomeActivity a = instanceForDisplay(displayId);
        if (a == null || a.dockWeb == null || !a.dockReady) return false;
        long now = android.os.SystemClock.uptimeMillis();
        long down = (action == android.view.MotionEvent.ACTION_DOWN)
                ? now : (a.lastDockDownTime > 0 ? a.lastDockDownTime : now);
        if (action == android.view.MotionEvent.ACTION_DOWN) a.lastDockDownTime = now;
        android.view.MotionEvent ev = android.view.MotionEvent.obtain(down, now, action, x, y, 0);
        ev.setSource(android.view.InputDevice.SOURCE_TOUCHSCREEN);
        a.runOnUiThread(() -> {
            try { a.dockWeb.dispatchTouchEvent(ev); } catch (Throwable ignored) {}
            finally { ev.recycle(); }
        });
        return true;
    }

    private void setupCanghaiDock(final int retry) {
        if (isFinishing() || isDestroyed()) return;
        Display d = (displayId >= 0)
                ? getSystemService(DisplayManager.class).getDisplay(displayId) : null;
        if (d == null) {
            if (retry < 12) {
                new Handler(Looper.getMainLooper())
                        .postDelayed(() -> setupCanghaiDock(retry + 1), 400);
            } else {
                Log.w(TAG, "虚拟屏未就绪，Dock 悬浮层建不出来（页面内 Dock 继续用）");
            }
            return;
        }
        try {
            Context dc = hostBaseContext().createDisplayContext(d);
            float density = dc.getResources().getDisplayMetrics().density;
            /* Dock 改用 Presentation（TYPE_APPLICATION_ATTACHED_DIALOG）挂到这块屏：
               窗口类型不在 Android 12+「不可信触摸」拦截名单，从根消除遮挡丢点击。 */
            /* 画布高度 104dp：留足顶部弹跳动画(26px)与光晕阴影，永不发生任何裁切 */
            dockFullHpx = Math.round(104 * density);
            dockExpandedHpx = dockFullHpx;
            dockProbeHpx = Math.round(10 * density);
            dockAutoHide = readDockAutoHide();

            dockWeb = new WebView(dc) {
                @Override
                public android.view.PointerIcon onResolvePointerIcon(MotionEvent event, int pointerIndex) {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                        android.view.PointerIcon icon = super.onResolvePointerIcon(event, pointerIndex);
                        if (icon == null) {
                            return EmptyPointerFrameLayout.getThemedPointerIcon(getContext());
                        }
                        int type = EmptyPointerFrameLayout.getPointerIconType(icon);
                        if (type == android.view.PointerIcon.TYPE_ARROW || type == android.view.PointerIcon.TYPE_DEFAULT || type == -1) {
                            return EmptyPointerFrameLayout.getThemedPointerIcon(getContext());
                        }
                        return icon;
                    }
                    return super.onResolvePointerIcon(event, pointerIndex);
                }
                @Override
                public boolean onHoverEvent(MotionEvent ev) {
                    int a = ev.getActionMasked();
                    if (a == MotionEvent.ACTION_HOVER_ENTER || a == MotionEvent.ACTION_HOVER_MOVE) {
                        if (!dockHoverOn) {
                            runOnUiThread(() -> {
                                chDockNativeHover = true;
                                cancelCanghaiDockCollapse();
                                dockHoverOn = true;
                                refreshCanghaiDock();
                            });
                        }
                    } else if (a == MotionEvent.ACTION_HOVER_EXIT) {
                        runOnUiThread(() -> {
                            chDockNativeHover = false;
                            scheduleCanghaiDockCollapse(300L);
                        });
                    }
                    return super.onHoverEvent(ev);
                }
            };
            WebSettings ws = dockWeb.getSettings();
            ws.setJavaScriptEnabled(true);
            ws.setAllowFileAccess(true);
            ws.setAllowFileAccessFromFileURLs(true);
            ws.setAllowUniversalAccessFromFileURLs(true);
            ws.setCacheMode(WebSettings.LOAD_NO_CACHE);
            dockWeb.setBackgroundColor(0x00000000);
            dockWeb.setVerticalScrollBarEnabled(false);
            dockWeb.setHorizontalScrollBarEnabled(false);
            dockWeb.setWebViewClient(new WebViewClient() {
                @Override
                public void onPageFinished(WebView v, String url) {
                    dockReady = true;
                    lastDockPushSig = "";
                    pushDock();
                    refreshCanghaiDock();
                    /* 桌面页可能比 Dock 先加载完（那时它已经画了自己的那条 Dock），
                       这里补一句话让它撤掉，避免同屏两条 Dock */
                    runOnUiThread(() -> {
                        try {
                            if (web != null) web.evaluateJavascript(
                                    "window.__dockOverlayOn && window.__dockOverlayOn();", null);
                        } catch (Throwable ignored) {
                        }
                    });
                    Log.i(TAG, "Dock 悬浮层就绪 display=" + displayId);
                }
            });
            dockWeb.setWebChromeClient(new WebChromeClient() {
                @Override
                public boolean onConsoleMessage(android.webkit.ConsoleMessage m) {
                    if (m == null) return false;
                    String msg = m.message();
                    String loc = m.sourceId() + ":" + m.lineNumber();
                    Log.i(TAG, "[dockweb] " + msg + " @" + loc);
                    if (m.messageLevel() == android.webkit.ConsoleMessage.MessageLevel.ERROR
                            || (msg != null && (msg.toLowerCase().contains("uncaught") || msg.toLowerCase().contains("error")))) {
                        CanghaiErrorCollector.getInstance().log("悬浮Dock", "ERROR", msg, loc);
                    }
                    return true;
                }
            });
            dockWeb.addJavascriptInterface(new DockBridge(), "AosenDock");
            dockWeb.addJavascriptInterface(new DockBridge(), "CanghaiDock");

            dockHolder = new EmptyPointerFrameLayout(dc);
            dockHolder.addView(dockWeb, new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

            /* 只按显示归属分流，非 Root 路径一行不动：
             *  - 非 Root（默认屏 / 外屏 / DeXPresentation 底座 Activity）：保留原来的
             *    OnComputeInternalInsetsListener + 可触摸区域声明，交互与层序完全照旧；
             *  - Root 虚拟屏：不注册该监听。Dock 已改底部可触摸条带，不需要靠隐藏 API
             *    声明可触摸区域；整屏监听反而会在全模态态把窗口重新判成整屏遮挡。 */
            if (!rootVirtualDisplay) {
                final float finalDensity = density;
                dockHolder.setTouchableInsetsProvider(new EmptyPointerFrameLayout.TouchableInsetsProvider() {
                    @Override
                    public boolean isWholeWindowTouchable() {
                        return mAllTouchable || dockLaunchpadOpen || dockMenuOpen;
                    }

                    @Override
                    public Rect getTouchableRect() {
                        if (dockBoundsReceived && dockBoundsPx != null && !dockBoundsPx.isEmpty()) {
                            int pad = Math.round(18 * finalDensity);
                            int l = Math.max(0, dockBoundsPx.left - pad);
                            int t = Math.max(0, dockBoundsPx.top - pad);
                            int r = dockBoundsPx.right + pad;
                            int b = dockBoundsPx.bottom + pad;
                            return new Rect(l, t, r, b);
                        }
                        int h = (dockHolder != null) ? dockHolder.getHeight() : 0;
                        int w = (dockHolder != null) ? dockHolder.getWidth() : 0;
                        return new Rect(0, Math.max(0, h - dockFullHpx), Math.max(1, w), Math.max(1, h));
                    }

                    @Override
                    public java.util.List<Rect> getTouchableRects() {
                        if (mAllTouchable || dockLaunchpadOpen) return null;
                        java.util.List<Rect> res = new java.util.ArrayList<>(mTouchableRectList);
                        if (res.isEmpty()) {
                            Rect d = getTouchableRect();
                            if (d != null && !d.isEmpty()) res.add(d);
                        }
                        return res;
                    }

                    @Override
                    public int getFallbackDockHeight() {
                        return dockFullHpx;
                    }
                });
            } else {
                CanghaiErrorCollector.logExt("[显示归属] display " + displayId
                        + " 的 Dock 层不注册可触摸区域声明（仅 Root 虚拟屏分支生效）");
            }

            /* 只按显示归属分流，非 Root 一行不动：
             *  - Root 虚拟屏：整屏 2038 窗口在三星上必被判成遮挡源（NOT_TOUCHABLE 不是豁免），
             *    且 NOT_TOUCHABLE 让 Dock 收不到点击、大脑直派又拿不到原始鼠标事件。
             *    因此改成「底部 dockFullHpx 一条 + 可触摸」：应用区头顶没有本层窗口，
             *    Dock 按钮直接吃系统真实触摸。全屏模态态由 updateDockWindowSize 临时拉回整屏。
             *  - 非 Root：维持原来的整屏 NOT_TOUCHABLE + insets 可触摸区域声明与大脑直派。 */
            int dockW = ViewGroup.LayoutParams.MATCH_PARENT;
            int dockH = rootVirtualDisplay ? Math.max(1, dockFullHpx)
                    : ViewGroup.LayoutParams.MATCH_PARENT;
            int dockGravity = rootVirtualDisplay ? Gravity.BOTTOM : Gravity.FILL;
            int dockFlags = rootVirtualDisplay
                    ? (WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                        | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS)
                    : (WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                        | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS);
            dockWin = new CanghaiVirtualWindow(this::hostBaseContext, displayId, dockHolder,
                    dockW, dockH, 0, 0, dockGravity, dockFlags,
                    android.graphics.PixelFormat.TRANSLUCENT, rootVirtualDisplay);
            if (rootVirtualDisplay) {
                CanghaiErrorCollector.logExt("[显示归属] display " + displayId
                        + " 的 Dock 改为底部 " + dockFullHpx + "px 可触摸条带（应用区无覆盖窗）");
            }
            if (!sDockUserBlocked) dockWin.show();
            refreshCanghaiDock();

            // 先挂窗口再加载页面：首帧就能拿到真实宽度，Dock 不会被压成一小块
            dockWeb.loadUrl("file:///android_asset/canghai/dock/index.html"
                    + (rootVirtualDisplay ? "?strip=1" : ""));
            // 页面那边可能要等桥就绪，补推一次
            new Handler(Looper.getMainLooper()).postDelayed(this::pushDock, 800);
        } catch (Throwable t) {
            Log.w(TAG, "Dock 悬浮层创建失败: " + t.getMessage(), t);
        }
    }

    /** 把最新注册表推给悬浮 Dock（装/卸软件、插拔屏之后都要调）。 */
    public void pushDock() {
        if (dockWeb == null || !dockReady) return;
        runOnUiThread(() -> {
            try {
                dockWeb.evaluateJavascript("window.__dockRefresh && window.__dockRefresh();", null);
            } catch (Throwable t) {
                Log.w(TAG, "pushDock 失败: " + t.getMessage());
            }
        });
    }

    /**
     * 把桌面拉回前台。
     *
     * 必须走 HomeGuard 同一条路：`am start --windowingMode 1 -f 0x30000000`。
     * 直接 startActivity 会让三星 DeX 副屏按 freeform 起一个**小窗口**桌面
     * （用户反馈过「桌面变成了窗口」），只有 shell 这条命令能强制全屏。
     */
    private void bringDeskToFront(boolean launchpad) {
        /* 首选：把桌面自己的 task 前置（REORDER_TASKS 是普通权限，不需要 Root）。
           这条不会新建窗口，桌面保持全屏。
           千万不能用 am start：三星 DeX 副屏默认自由窗口，am start 会把桌面
           重新拉成一个带标题栏的小窗口（实测）。 */
        try {
            ActivityManager amgr = (ActivityManager) getSystemService(Context.ACTIVITY_SERVICE);
            int taskId = getTaskId();
            if (amgr != null && taskId > 0) {
                amgr.moveTaskToFront(taskId, 0);
                Log.i(TAG, "桌面 task 前置: taskId=" + taskId);
            }
        } catch (Throwable t) {
            Log.w(TAG, "moveTaskToFront 失败: " + t.getMessage());
        }
        if (launchpad) {
            runOnUiThread(() -> {
                if (web == null) return;
                web.postDelayed(() -> {
                    try {
                        web.evaluateJavascript(
                                "window.__canghaiLaunchpad && window.__canghaiLaunchpad();", null);
                    } catch (Throwable ignored) {
                    }
                }, 180);
            });
        }
    }

    /** 悬浮 Dock 的 JS 通道。 */
    private class DockBridge {
        @JavascriptInterface
        public void reportTouchableRegions(final String json) {
            runOnUiThread(() -> {
                try {
                    if ("ALL".equals(json)) {
                        mAllTouchable = true;
                        mTouchableRectList.clear();
                    } else {
                        mAllTouchable = false;
                        mTouchableRectList.clear();
                        if (json != null && json.startsWith("[")) {
                            org.json.JSONArray arr = new org.json.JSONArray(json);
                            for (int i = 0; i < arr.length(); i++) {
                                org.json.JSONObject o = arr.getJSONObject(i);
                                int l = o.optInt("l", 0);
                                int t = o.optInt("t", 0);
                                int r = o.optInt("r", 0);
                                int b = o.optInt("b", 0);
                                if (r > l && b > t) {
                                    mTouchableRectList.add(new Rect(l, t, r, b));
                                }
                            }
                        }
                    }
                    if (dockHolder != null) dockHolder.requestLayout();
                    if (rootVirtualDisplay) updateDockWindowSize();
                } catch (Throwable ignored) {}
            });
        }

        @JavascriptInterface
        public void syncWindowsToDesktop() {
            runOnUiThread(() -> {
                try {
                    if (web != null) {
                        web.evaluateJavascript("window.onSyncWindowsFromFloating && window.onSyncWindowsFromFloating();", null);
                    }
                } catch (Throwable ignored) {}
            });
        }

        @JavascriptInterface
        public boolean isPerfMonitorEnabled() {
            try {
                android.content.SharedPreferences sp = getSharedPreferences(PREFS, Context.MODE_PRIVATE);
                return sp.getBoolean("perf_monitor_enabled", BuildConfig.DEBUG);
            } catch (Throwable t) {
                return BuildConfig.DEBUG;
            }
        }

        @JavascriptInterface
        public void setPerfMonitorEnabled(final boolean on) {
            try {
                android.content.SharedPreferences sp = getSharedPreferences(PREFS, Context.MODE_PRIVATE);
                sp.edit().putBoolean("perf_monitor_enabled", on).apply();
                if (dockWeb != null) {
                    runOnUiThread(() -> dockWeb.evaluateJavascript("window.setPerfMonitorVisible && window.setPerfMonitorVisible(" + on + ");", null));
                }
            } catch (Throwable ignored) {}
        }

        @JavascriptInterface
        public String getDockOffsets() {
            try {
                org.json.JSONObject obj = new org.json.JSONObject();
                obj.put("x", CanghaiStore.getDockOffsetX(DexHomeActivity.this));
                obj.put("y", CanghaiStore.getDockOffsetY(DexHomeActivity.this));
                return obj.toString();
            } catch (Throwable t) {
                return "{\"x\":0,\"y\":0}";
            }
        }

        @JavascriptInterface
        public void reportJsError(String source, String message, String stack) {
            CanghaiErrorCollector.getInstance().log(source != null ? source : "悬浮Dock", "ERROR", message, stack != null ? stack : "");
        }

        @JavascriptInterface
        public String getLanguage() {
            return CanghaiStore.getLanguage(DexHomeActivity.this);
        }

        @JavascriptInterface
        public String getState() {
            try {
                /* 内置软件（设置/输入监视/便签）的定义与图标在网页侧 shared/apps.js，
                   这里只给真机应用、沧海软件、显示 id，别在原生再抄一份。 */
                org.json.JSONObject o = new org.json.JSONObject(CanghaiStore.stateJsonForDisplay(DexHomeActivity.this, displayId));
                o.put("displayId", displayId);
                return o.toString();
            } catch (Throwable t) {
                return "{}";
            }
        }

        @JavascriptInterface
        public void launch(final String comp) {
            runOnUiThread(() -> {
                if (dockLaunchpadOpen) setDockLaunchpadOpen(false);
                if (comp == null || comp.isEmpty()) return;
                launchByComponent(comp, comp);
            });
        }

        @JavascriptInterface
        public void openApp(final String id) {
            if (id == null || id.isEmpty()) return;
            /* 从启动台点应用：先把启动台收回去（窗口缩回 Dock 条），再开应用 */
            runOnUiThread(() -> { if (dockLaunchpadOpen) setDockLaunchpadOpen(false); });
            if (deskForeground) {
                openAppInPage(id, 0);
                return;
            }
            new Thread(() -> {
                bringDeskToFront(false);
                /* 桌面被拉到前台之后再让它开窗，否则窗口会开在看不见的底下 */
                openAppInPage(id, 520);
            }).start();
        }

        private void openAppInPage(final String id, long delay) {
            runOnUiThread(() -> {
                if (web == null) return;
                web.postDelayed(() -> {
                    try {
                        web.evaluateJavascript("window.__canghaiOpenApp && window.__canghaiOpenApp("
                                + JSONObject.quote(id) + ");", null);
                    } catch (Throwable ignored) {
                    }
                }, Math.max(delay, 0));
            });
        }

        @JavascriptInterface
        public void setDockBoundsFraction(final double lf, final double tf, final double rf, final double bf) {
            runOnUiThread(() -> {
                dockLeftFrac = (float) Math.max(0.0, Math.min(1.0, lf));
                dockTopFrac = (float) Math.max(0.0, Math.min(1.0, tf));
                dockRightFrac = (float) Math.max(0.0, Math.min(1.0, rf));
                dockBottomFrac = 1.0f;
                dockBoundsReceived = true;
            });
        }

        @JavascriptInterface
        public void setDockMenuOpen(final boolean open) {
            runOnUiThread(() -> {
                dockMenuOpen = open;
                updateDockWindowSize();
            });
        }

        @JavascriptInterface
        public void setDockBounds(final int left, final int top, final int right, final int bottom) {
            runOnUiThread(() -> {
                dockBoundsPx.set(left, top, right, bottom);
                dockBoundsReceived = true;
                updateDockWindowSize();
            });
        }

        @JavascriptInterface
        public void setDockPins(final String json) {
            runOnUiThread(() -> {
                try {
                    CanghaiStore.setDockPinsJson(DexHomeActivity.this, json);
                    if (web != null) {
                        web.evaluateJavascript(
                                "window.__canghaiSyncDockPins && window.__canghaiSyncDockPins("
                                        + JSONObject.quote(json) + ");", null);
                    }
                    pushDock();
                } catch (Throwable ignored) {}
            });
        }

        @JavascriptInterface
        public void closeAppById(final String id, final String comp) {
            runOnUiThread(() -> {
                try {
                    if (web != null) {
                        web.evaluateJavascript(
                                "window.__canghaiCloseApp && window.__canghaiCloseApp("
                                        + JSONObject.quote(id) + ");", null);
                    }
                    if (comp != null && !comp.isEmpty() && shell != null) {
                        shell.close(comp);
                    }
                } catch (Throwable ignored) {}
            });
        }

        /** Dock 条上的启动台图标：直接在最顶层悬浮覆盖层展开应用抽屉，绝不动底层应用窗口 */
        @JavascriptInterface
        public void setActiveWinTitle(final String title) {
            notifyActiveWinChanged(title);
        }

        @JavascriptInterface
        public void openLaunchpad() {
            runOnUiThread(() -> setDockLaunchpadOpen(!dockLaunchpadOpen));
        }

        /** 启动台页内点空白关闭 / 点应用后由原生顺手关闭 */
        @JavascriptInterface
        public void setDockLaunchpad(final boolean open) {
            runOnUiThread(() -> setDockLaunchpadOpen(open));
        }

        @JavascriptInterface
        public boolean getDockAutoHide() {
            return readDockAutoHide();
        }

        @JavascriptInterface
        public void setDockAutoHide(final boolean on) {
            runOnUiThread(() -> saveDockAutoHide(on));
        }

        /** 鼠标进入 Dock：画布高度固定 116dp 足够容纳，不再动态修改窗口大小 */
        @JavascriptInterface
        public void setDockExpanded(final boolean open) {
            // no-op：保持固定尺寸，防止窗口反复 relayout 抖动
        }

        /** 网页侧 pointerenter / pointerleave：维持「鼠标还在 Dock 上」这个状态。 */
        @JavascriptInterface
        public void setDockHover(final boolean on) {
            runOnUiThread(() -> {
                if (on) {
                    cancelCanghaiDockCollapse();
                    dockHoverOn = true;
                    refreshCanghaiDock();
                } else {
                    scheduleCanghaiDockCollapse(280L);
                }
            });
        }

        @JavascriptInterface
        public void dbg(String msg) {
            Log.i(TAG, "[dock] " + (msg == null ? "null" : msg));
        }
    }

    // ==================== 顶部伪装标题栏浮层 ====================

    /** 8dp 顶部悬停探针 + 30dp 标题栏窗口。初始不挂载，仅在第三方应用前台时显示。 */
    private void setupCaptionOverlay(Context dc) {
        try {
            // ---- 顶部探针：绿点隐藏后，鼠标压顶边缘唤回 ----
            View probe = new View(dc);
            probe.setOnHoverListener((v, ev) -> {
                if (capAutoHidden && (ev.getAction() == MotionEvent.ACTION_HOVER_ENTER
                        || ev.getAction() == MotionEvent.ACTION_HOVER_MOVE)) {
                    runOnUiThread(() -> {
                        capAutoHidden = false;
                        setCaptionProbe(false);   // 唤回后立刻摘掉探针，不吞顶层触摸
                        setCaptionVisible(true);
                    });
                }
                return true;
            });
            capProbeHpx = Math.round(8 * tbDensity);
            capProbeHolder = new FrameLayout(dc);
            capProbeHolder.addView(probe, new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
            // 探针改用 Presentation 承载（非 OVERLAY 悬浮窗）；不常驻，仅在绿点隐藏后挂载
            capProbeWin = new CanghaiVirtualWindow(this::hostBaseContext, displayId, capProbeHolder,
                    ViewGroup.LayoutParams.MATCH_PARENT, Math.round(8 * tbDensity),
                    0, 0, android.view.Gravity.TOP,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                            | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                    android.graphics.PixelFormat.TRANSLUCENT, false);
            capProbeVisible = false;

            // ---- 标题栏窗口 ----
            capWeb = new WebView(dc);
            WebSettings ws = capWeb.getSettings();
            ws.setJavaScriptEnabled(true);
            ws.setAllowFileAccess(true);
            ws.setAllowFileAccessFromFileURLs(true);
            ws.setAllowUniversalAccessFromFileURLs(true);
            ws.setCacheMode(WebSettings.LOAD_NO_CACHE);
            capWeb.setBackgroundColor(0x00000000);
            capWeb.setWebViewClient(new WebViewClient() {
                @Override
                public void onPageFinished(WebView v, String url) {
                    capReady = true;
                    pushCaptionInfo();
                }
            });
            capWeb.setWebChromeClient(new WebChromeClient());
            capWeb.addJavascriptInterface(new Bridge(), "Aosen");
            capWeb.addJavascriptInterface(new Bridge(), "Canghai");
            capWeb.loadUrl("file:///android_asset/dexhome/index.html?role=titlebar");

            capHolder = new FrameLayout(dc);
            capHolder.addView(capWeb, new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

            // 标题栏改用 Presentation 承载（非 OVERLAY 悬浮窗）；初始不挂载
            capBarHpx = Math.round(30 * tbDensity);
            capWin = new CanghaiVirtualWindow(this::hostBaseContext, displayId, capHolder,
                    ViewGroup.LayoutParams.MATCH_PARENT, capBarHpx,
                    0, 0, android.view.Gravity.TOP,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                            | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                    android.graphics.PixelFormat.TRANSLUCENT, false);
            Log.i(TAG, "伪装标题栏已就绪 display=" + displayId);
        } catch (Throwable t) {
            Log.w(TAG, "伪装标题栏创建失败: " + t.getMessage(), t);
        }
    }

    /** 标题栏窗口的 JS 通道 */
    private void jsEvalCap(final String script) {
        if (!capReady || capWeb == null) return;
        runOnUiThread(() -> {
            try {
                capWeb.evaluateJavascript(script, null);
            } catch (Throwable t) {
                Log.w(TAG, "jsEvalCap 失败: " + t.getMessage());
            }
        });
    }

    /** 把当前前景应用信息推给标题栏页（页面可能比 launch 晚加载完） */
    private void pushCaptionInfo() {
        String label = capLabel == null ? "" : capLabel.replace("\\", "\\\\").replace("'", "\\'");
        String pkg = capPkg == null ? "" : capPkg.replace("'", "\\'");
        jsEvalCap("window.AosenUI && window.AosenUI.setCaption"
                + " && window.AosenUI.setCaption('" + label + "','" + pkg + "')");
    }

    /** 启动应用时：绑定前景应用并挂上标题栏 */
    private void showCaptionFor(final String comp, final String label) {
        final String pkg = comp.contains("/") ? comp.substring(0, comp.indexOf('/')) : comp;
        runOnUiThread(() -> {
            capPkg = pkg;
            capLabel = (label == null || label.isEmpty() || label.equals(pkg)) ? pkg : label;
            capAutoHidden = false;
            capMaximized = false;
            setCaptionProbe(false);
            setCaptionVisible(true);
            pushCaptionInfo();
        });
    }

    /** 标题栏窗口显/隐：隐藏 = 整个摘除（探针按需挂载，不常驻吞触摸） */
    private void setCaptionVisible(final boolean visible) {
        try {
            if (capWin == null || capHolder == null || visible == capVisible) return;
            capVisible = visible;
            if (visible) {
                capWin.show();
                // 绿点隐藏时 JS 加了淡出类，重新挂载必须清掉，否则标题栏透明看不见
                jsEvalCap("window.showCaptionBar && window.showCaptionBar()");
            } else {
                capWin.dismiss();
            }
        } catch (Throwable t) {
            Log.w(TAG, "伪装标题栏显隐失败: " + t.getMessage());
        }
    }

    /** 顶部悬停探针显隐：仅绿点隐藏后挂载，唤回即摘除 */
    private void setCaptionProbe(final boolean visible) {
        try {
            if (capProbeWin == null || capProbeHolder == null || visible == capProbeVisible) return;
            capProbeVisible = visible;
            if (visible) capProbeWin.show();
            else capProbeWin.dismiss();
        } catch (Throwable t) {
            Log.w(TAG, "顶部探针显隐失败: " + t.getMessage());
        }
    }

    /** 返回桌面 / 用户主动关闭：标题栏与探针一并收起 */
    private void hideCaption() {
        capAutoHidden = false;
        capMaximized = false;
        setCaptionProbe(false);
        setCaptionVisible(false);
    }

    /** 统一计算任务栏窗口高度：菜单 > Dock hover 展开 > 常态 66dp。
     *  Dock 只在鼠标进入时临时加高，离开立即缩回，避免平时吞掉应用底部区域的触摸。 */
    private void applyTaskbarHeight() {
        if (tbWin == null || tbHolder == null) return;
        int h = tbMenuOpen ? openHpx : (dockExpanded ? dockExpandHpx : barHpx);
        if (tbWin.getLayoutHeight() == h) return;
        try {
            if (tbVisible) tbWin.updateLayout(ViewGroup.LayoutParams.MATCH_PARENT, h);
        } catch (Throwable t) {
            Log.w(TAG, "任务栏高度切换失败: " + t.getMessage());
        }
    }

    /** 任务栏窗口显/隐：隐藏 = 整个窗口摘除（探针仍悬停可唤回） */
    private void setTbBarVisible(final boolean visible) {
        try {
            if (tbWin == null || tbHolder == null || visible == tbVisible) return;
            tbVisible = visible;
            if (visible) {
                tbWin.updateLayout(ViewGroup.LayoutParams.MATCH_PARENT,
                        tbMenuOpen ? openHpx : (dockExpanded ? dockExpandHpx : barHpx));
                tbWin.show();
                // 重新挂载后 viewport 会重置，补一次数据并让 Dock 重排
                pushDockData();
            } else {
                tbWin.dismiss();
            }
        } catch (Throwable t) {
            Log.w(TAG, "任务栏窗口显隐失败: " + t.getMessage());
        }
    }

    /** 窗口壳全屏联动：应用铺满整屏时摘掉任务栏与 Dock 悬浮窗，复原成窗口时挂回。 */
    private void onShellFullscreenChanged(boolean fullscreen) {
        setTbBarVisible(!fullscreen);
        dockFullscreenNative = fullscreen;
        refreshCanghaiDock();
    }

    /** 开始菜单开/关：窗口长高让菜单露出来；开 = 临时可聚焦（否则 USB 键盘进不了搜索框） */
    private void setTbMenuOpen(final boolean open) {
        try {
            if (tbWin == null || tbHolder == null || open == tbMenuOpen) return;
            tbMenuOpen = open;
            if (!tbVisible) return;
            tbWin.updateLayout(ViewGroup.LayoutParams.MATCH_PARENT,
                    open ? openHpx : (dockExpanded ? dockExpandHpx : barHpx));
            if (open) {
                tbWin.setFlags(WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN);
            } else {
                tbWin.setFlags(WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN);
            }
        } catch (Throwable t) {
            Log.w(TAG, "菜单窗口切换失败: " + t.getMessage());
        }
    }

    private void hideNavBar() {
        if (Build.VERSION.SDK_INT >= 30) {
            try {
                android.view.WindowInsetsController ic = getWindow().getInsetsController();
                if (ic != null) {
                    ic.hide(android.view.WindowInsets.Type.systemBars());
                    ic.setSystemBarsBehavior(
                            android.view.WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
                }
            } catch (Throwable t) {
                Log.w(TAG, "hideNavBar 失败: " + t.getMessage());
            }
        } else {
            getWindow().getDecorView().setSystemUiVisibility(
                    View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                            | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                            | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                            | View.SYSTEM_UI_FLAG_FULLSCREEN
                            | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                            | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);
        }
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) hideNavBar();
    }

    private void updateTopBarScreenStatus() {
        int actId = MainActivity.getActiveDisplayIdStatic();
        jsEval("window.updateTopBarActiveScreen && window.updateTopBarActiveScreen(" + actId + ");");
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        int action = event.getAction();
        int keyCode = event.getKeyCode();

        // 若用户在手机端配置弹层操作后按系统返回键，因外屏刚启动抢了焦点而派发到这里，优先交还手机端处理
        if (keyCode == KeyEvent.KEYCODE_BACK && !DeXPresentation.isFromGameController(event)) {
            if (MainActivity.tryConsumeBackFromExternal(event)) {
                return true;
            }
        }

        if (action == KeyEvent.ACTION_DOWN) {
            switch (keyCode) {
                case KeyEvent.KEYCODE_BUTTON_MODE: // 110 (手柄 HOME 键)
                case KeyEvent.KEYCODE_HOME:        // 3
                case KeyEvent.KEYCODE_BUTTON_START:// 108
                    toggleLaunchpadAny();
                    return true;

                case KeyEvent.KEYCODE_BUTTON_B:    // 97 (手柄 B 键)
                    if (dockLaunchpadOpen) {
                        setDockLaunchpadOpen(false);
                        return true;
                    }
                    if (dockWeb != null) {
                        dockWeb.evaluateJavascript("if(window.dockGamepadBack&&window.dockGamepadBack()){return;}else{window.gamepadBack&&window.gamepadBack();}", null);
                    }
                    jsEval("window.gamepadBack && window.gamepadBack();");
                    return true;

                case KeyEvent.KEYCODE_BACK:        // 4 (系统返回键/手势)
                    if (dockLaunchpadOpen) {
                        setDockLaunchpadOpen(false);
                        return true;
                    }
                    if (shell != null && shell.hasFullscreenWin()) {
                        shell.exitFullscreenActive();
                        Toast.makeText(DexHomeActivity.this, "已退出全屏模式", Toast.LENGTH_SHORT).show();
                        return true;
                    }
                    long nowBack = System.currentTimeMillis();
                    if (nowBack - lastBackPressTime < 2000) {
                        finish();
                    } else {
                        lastBackPressTime = nowBack;
                        Toast.makeText(DexHomeActivity.this, "再按一次返回键退出系统桌面", Toast.LENGTH_SHORT).show();
                    }
                    return true;

                case KeyEvent.KEYCODE_BUTTON_A:    // 96 (手柄 A 键)
                case KeyEvent.KEYCODE_DPAD_CENTER: // 23
                case KeyEvent.KEYCODE_ENTER:       // 66
                    jsEval("window.gamepadSelect && window.gamepadSelect();");
                    if (dockWeb != null) dockWeb.evaluateJavascript("window.dockGamepadSelect && window.dockGamepadSelect();", null);
                    return true;

                case KeyEvent.KEYCODE_DPAD_UP:
                    jsEval("window.gamepadNav && window.gamepadNav('up');");
                    if (dockWeb != null) dockWeb.evaluateJavascript("window.dockGamepadNav && window.dockGamepadNav('up');", null);
                    return true;
                case KeyEvent.KEYCODE_DPAD_DOWN:
                    jsEval("window.gamepadNav && window.gamepadNav('down');");
                    if (dockWeb != null) dockWeb.evaluateJavascript("window.dockGamepadNav && window.dockGamepadNav('down');", null);
                    return true;
                case KeyEvent.KEYCODE_DPAD_LEFT:
                    jsEval("window.gamepadNav && window.gamepadNav('left');");
                    if (dockWeb != null) dockWeb.evaluateJavascript("window.dockGamepadNav && window.dockGamepadNav('left');", null);
                    return true;
                case KeyEvent.KEYCODE_DPAD_RIGHT:
                    jsEval("window.gamepadNav && window.gamepadNav('right');");
                    if (dockWeb != null) dockWeb.evaluateJavascript("window.dockGamepadNav && window.dockGamepadNav('right');", null);
                    return true;

                case KeyEvent.KEYCODE_BUTTON_SELECT: // 109 (手柄 Select 键)
                case KeyEvent.KEYCODE_BUTTON_X:
                case KeyEvent.KEYCODE_BUTTON_Y:
                case KeyEvent.KEYCODE_BUTTON_L1:
                case KeyEvent.KEYCODE_BUTTON_R1:
                case KeyEvent.KEYCODE_BUTTON_L2:
                case KeyEvent.KEYCODE_BUTTON_R2:
                case KeyEvent.KEYCODE_BUTTON_THUMBL:
                case KeyEvent.KEYCODE_BUTTON_THUMBR:
                    return true; // 拦截消费，防止未处理超时触发 ANR
            }
        } else if (action == KeyEvent.ACTION_UP) {
            if (keyCode == KeyEvent.KEYCODE_BUTTON_MODE
                    || keyCode == KeyEvent.KEYCODE_HOME
                    || keyCode == KeyEvent.KEYCODE_BUTTON_START
                    || keyCode == KeyEvent.KEYCODE_BUTTON_B
                    || keyCode == KeyEvent.KEYCODE_BACK
                    || keyCode == KeyEvent.KEYCODE_BUTTON_A
                    || keyCode == KeyEvent.KEYCODE_DPAD_CENTER
                    || keyCode == KeyEvent.KEYCODE_ENTER
                    || keyCode == KeyEvent.KEYCODE_DPAD_UP
                    || keyCode == KeyEvent.KEYCODE_DPAD_DOWN
                    || keyCode == KeyEvent.KEYCODE_DPAD_LEFT
                    || keyCode == KeyEvent.KEYCODE_DPAD_RIGHT
                    || keyCode == KeyEvent.KEYCODE_BUTTON_SELECT
                    || keyCode == KeyEvent.KEYCODE_BUTTON_X
                    || keyCode == KeyEvent.KEYCODE_BUTTON_Y
                    || keyCode == KeyEvent.KEYCODE_BUTTON_L1
                    || keyCode == KeyEvent.KEYCODE_BUTTON_R1
                    || keyCode == KeyEvent.KEYCODE_BUTTON_L2
                    || keyCode == KeyEvent.KEYCODE_BUTTON_R2
                    || keyCode == KeyEvent.KEYCODE_BUTTON_THUMBL
                    || keyCode == KeyEvent.KEYCODE_BUTTON_THUMBR) {
                return true;
            }
        }

        return super.dispatchKeyEvent(event);
    }

    private long lastStickNavTime = 0L;

    /**
     * 目标屏上的窗口收到自家虚拟设备的指针事件时，把「系统算好的落点」回传给自绘层。
     *
     * <p>虚拟屏上的系统指针精灵不绘制，图标只能用沧海自己的，所以箭头必须我们自己画；
     * 但位置不能自己算 —— 系统的落点带指针加速、带屏边界钳制，起点还受绑屏归位影响，
     * 我们按增量累加必然和它错开。错开之后用户瞄的是箭头、点的是落点，
     * 表现就是「位置不对、安卓窗口一个都点不中」。
     *
     * <p>这条回传把两者焊死：箭头永远画在系统真正要点击的地方。
     */
    @Override
    public boolean dispatchGenericMotionEvent(MotionEvent event) {
        if (event != null) {
            try {
                /* 不按设备名/来源过滤：窗口派发过来的 MotionEvent，有的机型 getDevice() 为空，
                 * 有的把鼠标 hover 的 source 改写成 TOUCHSCREEN（DeX 桌面就是这么干的），
                 * 按名字或 source 判断会整条被跳过 —— 表现就是箭头完全不跟手。
                 * 142 上不会有真实触摸（它是虚拟屏），所以只要「指针类动作」就上报。 */
                if (VirtualMouse.isActive()) {
                    final int a = event.getActionMasked();
                    boolean pointerish = a == MotionEvent.ACTION_HOVER_MOVE
                            || a == MotionEvent.ACTION_HOVER_ENTER
                            || a == MotionEvent.ACTION_HOVER_EXIT
                            || a == MotionEvent.ACTION_BUTTON_PRESS
                            || a == MotionEvent.ACTION_BUTTON_RELEASE
                            || a == MotionEvent.ACTION_SCROLL
                            || event.isFromSource(InputDevice.SOURCE_MOUSE);
                    if (pointerish) {
                        float[] vm = VirtualMouse.position();
                        int hostDisplay = -1;
                        try {
                            hostDisplay = getWindowManager().getDefaultDisplay().getDisplayId();
                        } catch (Throwable ignored) {}
                        String diff = (vm == null) ? "VirtualMouse未就绪"
                                : ("自算=(" + vm[0] + "," + vm[1] + ") Δ=("
                                        + (event.getX() - vm[0]) + "," + (event.getY() - vm[1]) + ")");
                        CanghaiErrorCollector.pointerAudit("C-系统落点",
                                "event=(" + event.getX() + "," + event.getY() + ")"
                                        + " raw=(" + event.getRawX() + "," + event.getRawY() + ")"
                                        + " 窗口display=" + hostDisplay + " " + diff
                                        + " ｜这是系统真正要点击的位置；Δ 就是双指针漂移量。"
                                        + " 进原生应用/DeX桌面后本回调断供，漂移会累积", false);
                        DeXPresentation pres = DeXPresentation.getActiveInstance();
                        if (pres != null) pres.onNativePointerAt(event.getX(), event.getY());
                    }
                }
            } catch (Throwable ignored) {}
        }
        return super.dispatchGenericMotionEvent(event);
    }

    @Override
    public boolean onGenericMotionEvent(MotionEvent event) {
        if (event.isFromSource(InputDevice.SOURCE_JOYSTICK)
                || event.isFromSource(InputDevice.SOURCE_GAMEPAD)) {
            float hatX = event.getAxisValue(MotionEvent.AXIS_HAT_X);
            float hatY = event.getAxisValue(MotionEvent.AXIS_HAT_Y);
            float axisX = event.getAxisValue(MotionEvent.AXIS_X);
            float axisY = event.getAxisValue(MotionEvent.AXIS_Y);

            long now = SystemClock.uptimeMillis();
            if (now - lastStickNavTime > 180L) {
                String dir = null;
                if (hatX < -0.5f || axisX < -0.5f) dir = "left";
                else if (hatX > 0.5f || axisX > 0.5f) dir = "right";
                else if (hatY < -0.5f || axisY < -0.5f) dir = "up";
                else if (hatY > 0.5f || axisY > 0.5f) dir = "down";

                if (dir != null) {
                    lastStickNavTime = now;
                    jsEval("window.launchpadGamepadNav && window.launchpadGamepadNav('" + dir + "');");
                    jsEval("window.gamepadNav && window.gamepadNav('" + dir + "');");
                    if (dockWeb != null) dockWeb.evaluateJavascript("window.dockGamepadNav && window.dockGamepadNav('" + dir + "');", null);
                    return true;
                }
            }
            return true;
        }
        return super.onGenericMotionEvent(event);
    }

    private void jsEval(final String script) {
        if (!webReady || web == null) return;
        runOnUiThread(() -> {
            try {
                web.evaluateJavascript(script, null);
            } catch (Throwable t) {
                Log.w(TAG, "jsEval 失败: " + t.getMessage());
            }
        });
    }

    /** 任务栏悬浮窗的 JS 通道（显隐/菜单等桌面壳层指令） */
    private void jsEvalTb(final String script) {
        if (!tbReady || tbWeb == null) return;
        runOnUiThread(() -> {
            try {
                tbWeb.evaluateJavascript(script, null);
            } catch (Throwable t) {
                Log.w(TAG, "jsEvalTb 失败: " + t.getMessage());
            }
        });
    }

    // ==================== JS 桥（HTML → 原生） ====================

    private class Bridge {
        @JavascriptInterface
        public void exitDesktop() {
            runOnUiThread(() -> finish());
        }

        @JavascriptInterface
        public String getConnectedScreens() {
            try {
                org.json.JSONArray arr = new org.json.JSONArray();
                // 1. 手机屏幕
                org.json.JSONObject phone = new org.json.JSONObject();
                phone.put("id", 0);
                phone.put("name", "手机屏幕 (本机)");
                phone.put("current", (displayId == 0));
                arr.put(phone);

                // 2. 有线外接大屏
                MainActivity main = MainActivity.getInstance();
                if (main != null && main.getDexPresentation() != null && main.getDexPresentation().isShowing()) {
                    int extId = main.getDexPresentation().getDisplay().getDisplayId();
                    org.json.JSONObject ext = new org.json.JSONObject();
                    ext.put("id", extId);
                    ext.put("name", "外接大屏 (" + main.getDexPresentation().getRawWidth() + "×" + main.getDexPresentation().getRawHeight() + ")");
                    int vdId = main.getDexPresentation().getVirtualDisplayId();
                    ext.put("current", (displayId == extId || (vdId > 0 && displayId == vdId)));
                    arr.put(ext);
                }

                // 3. Moonlight 串流屏
                boolean hasMoon = false;
                try {
                    com.alkaidlab.sunshine.service.SunshineRuntimeManager rt =
                            com.alkaidlab.sunshine.service.SunshineRuntimeManager.getInstance();
                    java.util.List<com.alkaidlab.sunshine.data.RuntimeSession> sessions = rt.getActiveSessionsFlow().getValue();
                    if (sessions != null && !sessions.isEmpty()) {
                        hasMoon = true;
                    }
                } catch (Throwable ignored) {}
                if (hasMoon) {
                    org.json.JSONObject moon = new org.json.JSONObject();
                    moon.put("id", StreamTarget.UI_ID);
                    moon.put("name", StreamTarget.get().getName());
                    moon.put("current", (displayId == StreamTarget.UI_ID));
                    arr.put(moon);
                }
                return arr.toString();
            } catch (Throwable t) {
                return "[]";
            }
        }

        @JavascriptInterface
        public String saveDownload(final String filename, final String dataUrl) {
            try {
                org.json.JSONObject res = CanghaiCore.saveDownloadDirect(DexHomeActivity.this, filename, dataUrl);
                runOnUiThread(() -> Toast.makeText(DexHomeActivity.this, "已保存至下载目录: " + res.optString("name"), Toast.LENGTH_SHORT).show());
                return res.toString();
            } catch (Throwable t) {
                Log.w(TAG, "DexHome.saveDownload 失败: " + t.getMessage(), t);
                runOnUiThread(() -> Toast.makeText(DexHomeActivity.this, "保存失败: " + t.getMessage(), Toast.LENGTH_SHORT).show());
                return "{\"saved\":false,\"error\":\"" + t.getMessage() + "\"}";
            }
        }

        @JavascriptInterface
        public void switchMouseToDisplay(final int targetDisplayId) {
            runOnUiThread(() -> {
                try {
                    MainActivity main = MainActivity.getInstance();
                    if (main != null) {
                        main.runOnUiThread(() -> {
                            try {
                                if (targetDisplayId == StreamTarget.UI_ID) {
                                    main.setActiveDisplayId(StreamTarget.UI_ID);
                                    if (main.getDexPresentation() != null) {
                                        main.getDexPresentation().hideMouseCursor();
                                    }
                                    Toast.makeText(main, "光标已切换至 Moonlight 串流屏", Toast.LENGTH_SHORT).show();
                                } else if (targetDisplayId == 0) {
                                    main.setActiveDisplayId(0);
                                    if (main.getDexPresentation() != null) {
                                        main.getDexPresentation().hideMouseCursor();
                                    }
                                    Toast.makeText(main, "光标已切换至手机屏幕", Toast.LENGTH_SHORT).show();
                                } else {
                                    main.activateDisplay(targetDisplayId);
                                    if (main.getDexPresentation() != null && main.getDexPresentation().isShowing()) {
                                        main.getDexPresentation().setMousePointerVisibility(true);
                                        main.getDexPresentation().resetCursorToCenter();
                                    }
                                    Toast.makeText(main, "光标已切换至外接显示器", Toast.LENGTH_SHORT).show();
                                }
                            } catch (Throwable t) {
                                Log.w(TAG, "switchMouseToDisplay error: " + t);
                            }
                        });
                    }
                } catch (Throwable ignored) {}
            });
        }


        @JavascriptInterface
        public void reportJsError(String source, String message, String stack) {
            CanghaiErrorCollector.getInstance().log(source != null ? source : "大屏桌面", "ERROR", message, stack != null ? stack : "");
        }

        @JavascriptInterface
        public String getLanguage() {
            return CanghaiStore.getLanguage(DexHomeActivity.this);
        }

        @JavascriptInterface
        public void setLanguage(final String lang) {
            runOnUiThread(() -> {
                CanghaiStore.setLanguage(DexHomeActivity.this, lang);
                notifyLanguageChanged(lang);
                MainActivity.notifyLanguageChangedFromExt(lang);
            });
        }

        /** 手机端点「投到屏幕」留下的意图：桌面开机时取一次，取走即清。 */
        @JavascriptInterface
        public String takePendingOpen() {
            return CanghaiStore.takePendingOpen(DexHomeActivity.this);
        }

        /** Dock 是不是已经由独立悬浮层接管：是的话页面里那条 Dock 不要再画。 */
        @JavascriptInterface
        public boolean isDockOverlayOn() {
            return DexHomeActivity.this.isDockOverlayOn();
        }

        /** 装/卸软件之后让悬浮 Dock 立刻刷新。 */
        @JavascriptInterface
        public void refreshDock() {
            pushDock();
        }

        /** 调试日志通道：网页侧关键事件（dock 展开/收起、坐标）打到 logcat，配合 tools/hover-test.js 复现交互 bug。 */
        @JavascriptInterface
        public void dbg(String msg) {
            Log.i("DexJsDbg", msg == null ? "null" : msg);
        }

        @JavascriptInterface
        public String l10n() {
            try {
                JSONObject o = new JSONObject();
                o.put("start", getString(R.string.taskbar_start));
                o.put("addWidget", getString(R.string.taskbar_widgets));
                o.put("searchHint", getString(R.string.menu_search_hint));
                o.put("emptyHint", getString(R.string.desktop_empty_hint));
                o.put("addShortcut", getString(R.string.ctx_add_shortcut));
                o.put("uninstall", getString(R.string.ctx_uninstall));
                o.put("appSettings", getString(R.string.ctx_app_settings));
                o.put("removeShortcut", getString(R.string.ctx_remove_shortcut));
                o.put("dockRemove", "从 Dock 移除");
                o.put("launchpad", "启动台");
                return o.toString();
            } catch (Throwable t) {
                return "{}";
            }
        }

        @JavascriptInterface
        public String wallpaperPath() {
            File f = new File(getCacheDir(), "dexwallpaper.jpg");
            return f.exists() ? f.getAbsolutePath() : "";
        }

        @JavascriptInterface
        public String getItemsJson() {
            return itemsJson();
        }

        /* ---------- 输入诊断小工具（桌面「输入监视」） ---------- */

        /** 当前按下了什么 + 最近事件日志，JSON 字符串。 */
        @JavascriptInterface
        public String inputTrace() {
            return InputTrace.snapshotJson();
        }

        /** 只有工具窗口打开时才开启记录，关窗立刻关闭，平时零开销。 */
        @JavascriptInterface
        public void inputTraceEnable(boolean on) {
            InputTrace.setEnabled(on);
        }

        @JavascriptInterface
        public void inputTraceClear() {
            InputTrace.clear();
        }

        @JavascriptInterface
        public String getAppsJson() {
            return appsJson();
        }

        // ================= 沧海：唯一数据源（手机端与外屏共用） =================

        /** 首屏一次性拿全：真机应用、沧海软件、鼠标样式库、壁纸与网格。 */
        @JavascriptInterface
        public String getState() {
            return CanghaiStore.stateJsonForDisplay(DexHomeActivity.this, displayId);
        }

        /** 沧海软件的源码，窗口打开时才取（避免首屏传几 MB）。
            源码工程包在此统一注入内置 three 运行时，否则 iframe 里 CH_MODULES
            一直 undefined，游戏只会黑屏（手机上由 HtmlAppActivity 注入所以能开）。 */
        @JavascriptInterface
        public String getHtmlApp(final String id) {
            String s = CanghaiStore.readHtmlAppForRun(DexHomeActivity.this, id);
            return s == null ? "" : s;
        }

        /** 导软件包用原始源码，不带临时注入的 three 运行时。 */
        @JavascriptInterface
        public String getHtmlAppSource(final String id) {
            String s = CanghaiStore.readHtmlApp(DexHomeActivity.this, id);
            return s == null ? "" : s;
        }

        @JavascriptInterface
        public String installHtml(final String name, final String html) {
            return installHtml(name, html, null);
        }

        @JavascriptInterface
        public String installHtml(final String name, final String html, final String iconSvg) {
            String id = CanghaiStore.installHtml(DexHomeActivity.this, name, html, iconSvg);
            if (id != null) CanghaiStore.notifyDataChanged(DexHomeActivity.this);
            return id == null ? "" : id;
        }

        @JavascriptInterface
        public void removeHtml(final String id) {
            CanghaiStore.removeHtml(DexHomeActivity.this, id);
            CanghaiStore.notifyDataChanged(DexHomeActivity.this);
        }

        /** 外屏右键「修改名字和图标」也要写回唯一数据源，手机端才会跟着变。 */
        @JavascriptInterface
        public String renameHtml(final String id, final String name) {
            String n = CanghaiStore.renameHtml(DexHomeActivity.this, id, name);
            if (n != null) CanghaiStore.notifyDataChanged(DexHomeActivity.this);
            return n == null ? "" : n;
        }

        @JavascriptInterface
        public boolean setHtmlIcon(final String id, final String iconSvg) {
            boolean ok = CanghaiStore.setHtmlIcon(DexHomeActivity.this, id, iconSvg, null);
            if (ok) CanghaiStore.notifyDataChanged(DexHomeActivity.this);
            return ok;
        }

        /** 外屏装软件包：WebView 没有 DecompressionStream，交给 java.util.zip 解。 */
        @JavascriptInterface
        public String getPickedUri() {
            return lastPickedUri == null ? "" : lastPickedUri;
        }

        @JavascriptInterface
        public String unzipUri(final String uriStr) {
            return CanghaiStore.unzipUriJson(DexHomeActivity.this, uriStr);
        }

        @JavascriptInterface
        public String unzipBase64(final String base64) {
            return CanghaiStore.unzipBase64Json(base64);
        }

        @JavascriptInterface
        public boolean disconnectDisplay(final int targetDisplayId) {
            try {
                if (targetDisplayId == displayId || targetDisplayId <= 0) {
                    runOnUiThread(() -> finish());
                } else {
                    ShizukuCmd.releaseVirtualDisplay(targetDisplayId);
                    ShizukuCmd.onDisplayReleased(targetDisplayId);
                }
                return true;
            } catch (Throwable t) {
                Log.w(TAG, "disconnectDisplay: " + t.getMessage());
                return false;
            }
        }

        /** 外屏右键「导出沧海软件」：系统「另存为」，让用户选目录和文件名。 */
        @JavascriptInterface
        public boolean exportPackage(final String name, final String base64) {
            try {
                return CanghaiStore.beginExport(DexHomeActivity.this, REQ_EXPORT, name, base64, web);
            } catch (Throwable t) {
                Log.w(TAG, "exportPackage: " + t.getMessage());
                return false;
            }
        }

        @JavascriptInterface
        public String getCursorLib() {
            return CanghaiStore.cursorLibJson(DexHomeActivity.this);
        }

        @JavascriptInterface
        public void selectCursor(final String id) {
            CanghaiStore.selectCursorForDisplay(DexHomeActivity.this, displayId, id);
            notifyCursorPrefsChanged(DexHomeActivity.this);
        }

        @JavascriptInterface
        public void setCursorSize(final int px) {
            CanghaiStore.setCursorSizeForDisplay(DexHomeActivity.this, displayId, px);
            notifyCursorPrefsChanged(DexHomeActivity.this);
        }

        @JavascriptInterface
        public void setUserProfile(final String name, final String avatar) {
            if (name != null) CanghaiStore.setUserName(DexHomeActivity.this, name);
            if (avatar != null) CanghaiStore.setUserAvatar(DexHomeActivity.this, avatar);
            CanghaiStore.notifyDataChanged(DexHomeActivity.this);
            MainActivity.notifyCursorPrefsChanged();
        }

        @JavascriptInterface
        public String getUserProfile() {
            try {
                JSONObject o = new JSONObject();
                o.put("userName", CanghaiStore.userName(DexHomeActivity.this));
                o.put("userAvatar", CanghaiStore.userAvatar(DexHomeActivity.this));
                return o.toString();
            } catch (Throwable t) {
                return "{}";
            }
        }

        @JavascriptInterface
        public void setCursorSpeed(final float v) {
            CanghaiStore.setCursorSpeed(DexHomeActivity.this, v);
            MainActivity.notifyCursorPrefsChanged();
        }

        /** 「关于沧海系统」窗口的数据。 */
        @JavascriptInterface
        public String getAboutInfo() {
            return CanghaiStore.aboutInfoJson(DexHomeActivity.this);
        }

        /** 在线检查更新：Native 发 HTTPS 请求，避开 file:// 页面的同源限制。 */
        @JavascriptInterface
        public String checkUpdate() {
            return CanghaiStore.checkUpdateJson(DexHomeActivity.this);
        }

        @JavascriptInterface
        public void openUpdateUrl(final String url) {
            runOnUiThread(() -> {
                try {
                    if (url == null || !url.startsWith("https://")) return;
                    android.content.Intent it = new android.content.Intent(
                            android.content.Intent.ACTION_VIEW, android.net.Uri.parse(url));
                    it.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK);
                    startActivity(it);
                } catch (Throwable ignored) {
                }
            });
        }

        @JavascriptInterface
        public String saveCursor(final String id, final String name, final String svg,
                                  final int hx, final int hy) {
            String real = CanghaiStore.upsertCursor(DexHomeActivity.this, id, name, svg, hx, hy);
            MainActivity.notifyCursorPrefsChanged();
            return real;
        }

        @JavascriptInterface
        public void removeCursor(final String id) {
            CanghaiStore.removeCursor(DexHomeActivity.this, id);
            MainActivity.notifyCursorPrefsChanged();
        }

        @JavascriptInterface
        public void saveWallpaper(final String wp) {
            CanghaiStore.setWallpaper(DexHomeActivity.this, wp);
        }

        @JavascriptInterface
        public void saveGrid(final int cols, final int rows) {
            CanghaiStore.setGrid(DexHomeActivity.this, cols, rows);
        }

        @JavascriptInterface
        public void saveDesktopItems(final String json) {
            CanghaiStore.setDesktopItems(DexHomeActivity.this, json);
        }

        @JavascriptInterface
        public String getDesktopItems() {
            return CanghaiStore.desktopItems(DexHomeActivity.this);
        }

        /** 统一桌面数据：保存整份桌面至统一数据库（条目/Dock 固定/设置/壁纸/网格），换显示器/重连仍是同一个桌面，并实时同步给其它外屏 */
        @JavascriptInterface
        public void saveDeskState(final String json) {
            try {
                if (json != null && !json.isEmpty()) {
                    CanghaiStore.setDesktopItems(DexHomeActivity.this, json);
                    notifyDesktopStateChanged(displayId, json);
                }
                getSharedPreferences(PREFS, MODE_PRIVATE).edit()
                        .putString(displayKey(), json == null ? "" : json).apply();
            } catch (Throwable ignored) {
            }
        }

        @JavascriptInterface
        public String getDeskState() {
            try {
                String s = CanghaiStore.desktopItems(DexHomeActivity.this);
                if (s != null && !s.isEmpty()) return s;
                return getSharedPreferences(PREFS, MODE_PRIVATE).getString(displayKey(), "");
            } catch (Throwable t) {
                return "";
            }
        }

        @JavascriptInterface
        public void launch(final String comp) {
            runOnUiThread(() -> launchByComponent(comp,
                    comp.contains("/") ? comp.substring(0, comp.indexOf('/')) : comp));
        }

        /** 沧海自己的 HTML 应用：开独立 freeform 窗口。 */
        @JavascriptInterface
        public void launchHtml(final String appId, final String label) {
            runOnUiThread(() -> launchHtmlApp(appId, label));
        }

        @JavascriptInterface
        public void removeShortcut(final String comp) {
            runOnUiThread(() -> {
                removeItemByComp(comp);
                saveLayout();
                pushItems();
            });
        }

        @JavascriptInterface
        public void addShortcut(final String comp) {
            runOnUiThread(() -> {
                synchronized (items) {
                    for (Item it : items) {
                        if (!it.widget && comp.equals(it.comp)) return; // 已在桌面
                    }
                    Item it = new Item();
                    it.widget = false;
                    it.comp = comp;
                    items.add(it);
                }
                saveLayout();
                pushItems();
                runOnUiThread(() -> Toast.makeText(DexHomeActivity.this,
                        R.string.toast_added_to_desktop, Toast.LENGTH_SHORT).show());
            });
        }

        @JavascriptInterface
        public void uninstall(final String comp) {
            final String pkg = comp.contains("/") ? comp.substring(0, comp.indexOf('/')) : comp;
            try {
                startActivity(new Intent(Intent.ACTION_DELETE,
                        Uri.parse("package:" + pkg)));
            } catch (Throwable t) {
                Log.w(TAG, "卸载跳转失败: " + t.getMessage());
            }
        }

        @JavascriptInterface
        public void appSettings(final String comp) {
            final String pkg = comp.contains("/") ? comp.substring(0, comp.indexOf('/')) : comp;
            try {
                startActivity(new Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                        Uri.parse("package:" + pkg)));
            } catch (Throwable t) {
                Log.w(TAG, "应用设置跳转失败: " + t.getMessage());
            }
        }

        @JavascriptInterface
        public void moveItem(final int from, final int to) {
            runOnUiThread(() -> {
                synchronized (items) {
                    if (from < 0 || from >= items.size()) return;
                    Item it = items.remove(from);
                    int t = to;
                    if (t > from) t--;
                    t = Math.max(0, Math.min(items.size(), t));
                    items.add(t, it);
                }
                saveLayout();
                pushItems();
            });
        }

        @JavascriptInterface
        public void reportWidgetRects(final String json) {
            runOnUiThread(() -> positionWidgets(json));
        }

        @JavascriptInterface
        public void pickWidget() {
            runOnUiThread(() -> showWidgetPicker());
        }

        @JavascriptInterface
        public void removeWidget(final int wid) {
            runOnUiThread(() -> {
                removeItemByWidgetId(wid);
                saveLayout();
                deleteWidgetNative(wid);
                pushItems();
            });
        }

        /** 任务栏悬浮窗显隐（隐藏后探针仍可悬停唤回） */
        @JavascriptInterface
        public void setTaskbarVisible(final boolean visible) {
            runOnUiThread(() -> setTbBarVisible(visible));
        }

        // ---- 系统桌面设置：隐藏 Dock / 全屏强制隐藏 / 运行倒影 ----

        @JavascriptInterface
        public boolean getDockAutoHide() {
            return readDockAutoHide();
        }

        @JavascriptInterface
        public void setDockAutoHide(final boolean on) {
            runOnUiThread(() -> saveDockAutoHide(on));
        }

        /** 桌面页里的沧海软件最大化/还原：此时不管设置怎样都要把 Dock 藏起来。 */
        @JavascriptInterface
        public void setDockFullscreen(final boolean on) {
            runOnUiThread(() -> {
                dockFullscreenWeb = on;
                refreshCanghaiDock();
            });
        }

        /** 桌面页上报每个软件的运行态：{"appId":"running"|"minned"}，没有条目 = 已关闭。 */
        @JavascriptInterface
        public void setDockRunState(final String json) {
            runOnUiThread(() -> {
                dockRunStateJson = (json == null || json.isEmpty()) ? "{}" : json;
                pushCanghaiDockState();
            });
        }

        @JavascriptInterface
        public void setDockPins(final String json) {
            runOnUiThread(() -> {
                try {
                    CanghaiStore.setDockPinsJson(DexHomeActivity.this, json);
                    if (dockWeb != null) {
                        dockWeb.evaluateJavascript(
                                "window.__dockApplyPins && window.__dockApplyPins("
                                        + JSONObject.quote(json) + ");", null);
                    }
                    pushDock();
                } catch (Throwable ignored) {}
            });
        }

        @JavascriptInterface
        public String getResolutions() {
            try {
                DeXPresentation pres = DeXPresentation.getActiveInstance();
                int rw = (pres != null) ? pres.getRawWidth() : getResources().getDisplayMetrics().widthPixels;
                int rh = (pres != null) ? pres.getRawHeight() : getResources().getDisplayMetrics().heightPixels;
                return CanghaiStore.getResolutionOptions(rw, rh, displayKey(), DexHomeActivity.this).toString();
            } catch (Throwable t) {
                return "[]";
            }
        }

        @JavascriptInterface
        public void setResolution(final String resKey) {
            runOnUiThread(() -> {
                try {
                    CanghaiStore.setDisplayResolutionKey(DexHomeActivity.this, displayKey(), resKey);
                    DeXPresentation pres = DeXPresentation.getActiveInstance();
                    if (pres != null) {
                        int[] target = CanghaiStore.getTargetResolution(pres.getRawWidth(), pres.getRawHeight(), resKey);
                        pres.recreateVirtualDisplayWithSize(target[0], target[1], -1);
                    }
                } catch (Throwable ignored) {}
            });
        }

        /** 开始菜单开/关：窗口长高 + 临时可聚焦（否则 USB 键盘输入进不了搜索框） */
        @JavascriptInterface
        public void setMenuOpen(final boolean open) {
            runOnUiThread(() -> setTbMenuOpen(open));
        }

        private void cancelDockCollapse() {
            if (pendingDockCollapse != null) {
                dockHandler.removeCallbacks(pendingDockCollapse);
                pendingDockCollapse = null;
            }
        }

        private void scheduleDockCollapse(long delay) {
            cancelDockCollapse();
            pendingDockCollapse = () -> {
                pendingDockCollapse = null;
                // 拖拽期间绝不缩回；等拖拽结束再收起
                if (dockDragActive) { scheduleDockCollapse(140L); return; }
                dockExpanded = false;
                dockCollapseAt = android.os.SystemClock.uptimeMillis();
                applyTaskbarHeight();
            };
            dockHandler.postDelayed(pendingDockCollapse, Math.max(0L, delay));
        }

        /** Dock 图标开始/结束拖拽：原生侧锁定加高状态，杜绝拖拽中高度来回切换。 */
        @JavascriptInterface
        public void setDockDragging(final boolean dragging) {
            runOnUiThread(() -> {
                dockDragActive = dragging;
                if (!dragging) return;
                cancelDockCollapse();
                if (pendingDockExpand != null) {
                    dockHandler.removeCallbacks(pendingDockExpand);
                    pendingDockExpand = null;
                }
                if (!dockExpanded) {
                    dockExpanded = true;
                    dockExpandAt = android.os.SystemClock.uptimeMillis();
                    applyTaskbarHeight();
                }
            });
        }

        /** 鼠标进入/离开 Dock：临时加高悬浮窗口，让图标放大与应用名标签不被裁切。
         *  防抖策略（彻底钉死 66↔124dp 上下鬼畜）：
         *   1) 状态幂等：已经展开就不再改高度，重复 pointerenter 只刷新时间；
         *   2) 最短展开 900ms：打断「缩回→假 enter→加高→假 leave→缩回」的循环；
         *   3) 缩回后 260ms 内不允许立刻再加高，假 enter 会被延后合并；
         *   4) 拖拽期间锁定展开，任何缩回请求排队等待。 */
        @JavascriptInterface
        public void setDockExpanded(final boolean open) {
            // 固定常态高度，无需再频繁变动 WindowManager 布局，彻底消除 Dock 抖动
        }

        @JavascriptInterface
        public float getCursorScale() {
            return com.alkaidlab.sunshine.service.MouseCursorPrefs.getScale();
        }

        @JavascriptInterface
        public void setCursorScale(final float scale) {
            runOnUiThread(() -> {
                com.alkaidlab.sunshine.service.MouseCursorPrefs.setContinuousScale(DexHomeActivity.this, scale);
                MainActivity.notifyCursorPrefsChanged();
            });
        }

        @JavascriptInterface
        public int getCursorStyle() {
            return com.alkaidlab.sunshine.service.MouseCursorPrefs.getStyle();
        }

        @JavascriptInterface
        public void setCursorStyle(final int style) {
            runOnUiThread(() -> {
                com.alkaidlab.sunshine.service.MouseCursorPrefs.setStyleIndex(DexHomeActivity.this, style);
                MainActivity.notifyCursorPrefsChanged();
            });
        }

        @JavascriptInterface
        public void switchActiveScreen() {
            runOnUiThread(() -> {
                MainActivity.toggleActiveDisplayFromDesktop();
                updateTopBarScreenStatus();
            });
        }

        @JavascriptInterface
        public boolean isDeviceLocked() {
            try {
                KeyguardManager km = (KeyguardManager) getSystemService(Context.KEYGUARD_SERVICE);
                return km != null && km.isKeyguardLocked();
            } catch (Throwable t) {
                return false;
            }
        }

        @JavascriptInterface
        public void lockDevice() {
            new Thread(() -> {
                try {
                    android.os.PowerManager pm = (android.os.PowerManager) getSystemService(Context.POWER_SERVICE);
                    if (pm == null || pm.isInteractive()) {
                        // 223 = KEYCODE_SLEEP（单向休眠锁屏，不会像 26 POWER 那样在已息屏时反过来点亮屏幕）
                        ShizukuCmd.execute("input keyevent 223");
                        Thread.sleep(150);
                        if (pm != null && pm.isInteractive()) {
                            ShizukuCmd.execute("input keyevent 26");
                        }
                    }
                } catch (Throwable ignored) {}
            }).start();
        }

        @JavascriptInterface
        public int getActiveScreenId() {
            return MainActivity.getActiveDisplayIdStatic();
        }

        // ---- 窗口壳：红 / 黄 / 绿（由每个窗格的自绘标题栏直接触发，保留桥名兼容旧页） ----

        /** 红点：关闭当前应用窗口 = 释放专属虚拟屏，不再 force-stop 整个包 */
        @JavascriptInterface
        public void closeApp() {
            runOnUiThread(() -> { if (shell != null) shell.closeActive(); });
        }

        /** 黄点：最小化（隐藏 View，虚拟屏保留，再点 Dock 立即恢复） */
        @JavascriptInterface
        public void minimizeApp() {
            runOnUiThread(() -> { if (shell != null) shell.minimizeActive(); });
        }

        /** 绿点：最大化 / 还原 */
        @JavascriptInterface
        public void toggleMaximize() {
            runOnUiThread(() -> { if (shell != null) shell.toggleMaximizeActive(); });
        }

        /** 真·壳标题栏由原生 View 自绘，以下旧浮层桥保留为空实现，避免 JS 调用报错 */
        @JavascriptInterface
        public void hideCaptionBar() { }

        @JavascriptInterface
        public void beginCaptionDrag() { }

        @JavascriptInterface
        public void moveCaptionWindow(final int dx, final int dy) { }

        @JavascriptInterface
        public void endCaptionDrag() { }

        // ---- Dock：固定 8 个应用 + 启动台 ----

        @JavascriptInterface
        public String getDockJson() { return dockJson(); }

        /** 右键「从 Dock 移除」：持久化，不改动桌面快捷方式 */
        @JavascriptInterface
        public void removeFromDock(final String comp) {
            synchronized (dockComps) {
                dockComps.remove(comp);
                dockUserCustomized = true;
                saveDock();
            }
            pushDockApps();
        }

        /** 从桌面 / 应用抽屉拖到 Dock 区域：加入 Dock（不超 8 个） */
        @JavascriptInterface
        public void addToDock(final String comp) {
            if (comp == null || comp.isEmpty()) return;
            synchronized (dockComps) {
                if (dockComps.contains(comp)) return;
                if (dockComps.size() >= DOCK_MAX) return;
                dockComps.add(comp);
                dockUserCustomized = true;
                saveDock();
            }
            pushDockApps();
        }

        /** Dock 拖拽换序：按前端给出的 comp 顺序重建固定项列表 */
        @JavascriptInterface
        public void reorderDock(final String json) {
            try {
                JSONArray a = new JSONArray(json);
                List<String> next = new ArrayList<>();
                for (int i = 0; i < a.length() && next.size() < DOCK_MAX; i++) {
                    String c = a.optString(i);
                    if (c != null && !c.isEmpty() && !next.contains(c)) next.add(c);
                }
                synchronized (dockComps) {
                    for (String c : dockComps) {
                        if (next.size() < DOCK_MAX && !next.contains(c)) next.add(c);
                    }
                    dockComps.clear();
                    dockComps.addAll(next);
                    dockUserCustomized = true;
                    saveDock();
                }
                pushDockApps();
            } catch (Throwable t) {
                Log.w(TAG, "reorderDock 失败: " + t.getMessage());
            }
        }

        /** 内置小组件（纯 HTML 渲染，位置持久化，不占用系统 AppWidget id） */
        @JavascriptInterface
        public String getBuiltinWidgetsJson() {
            return getSharedPreferences(PREFS, MODE_PRIVATE)
                    .getString(KEY_BUILTIN_WIDGETS, "[]");
        }

        @JavascriptInterface
        public void saveBuiltinWidgets(final String json) {
            getSharedPreferences(PREFS, MODE_PRIVATE)
                    .edit().putString(KEY_BUILTIN_WIDGETS, json == null ? "[]" : json).apply();
        }

        /** 系统基本信息 + 电量 + 真实网络：自定义桌面按需拉取，宿主主动广播 */
        @JavascriptInterface
        public String getSystemInfoJson() {
            try {
                JSONObject o = new JSONObject();
                o.put("model", Build.MODEL);
                o.put("brand", Build.BRAND);
                o.put("android", Build.VERSION.RELEASE);
                o.put("sdk", Build.VERSION.SDK_INT);
                o.put("locale", java.util.Locale.getDefault().toString());
                o.put("width", web != null ? web.getWidth() : 0);
                o.put("height", web != null ? web.getHeight() : 0);
                o.put("dpi", getResources().getDisplayMetrics().densityDpi);
                o.put("time", System.currentTimeMillis());
                int level = -1, status = -1, plugged = 0;
                try {
                    Intent b = registerReceiver(null, new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
                    if (b != null) {
                        level = b.getIntExtra("level", -1);
                        int scale = b.getIntExtra("scale", 100);
                        status = b.getIntExtra("status", -1);
                        plugged = b.getIntExtra("plugged", 0);
                        if (scale > 0 && level >= 0) level = Math.round(level * 100f / scale);
                    }
                } catch (Throwable ignored) {}
                JSONObject bat = new JSONObject();
                bat.put("level", level >= 0 ? level : 100);
                bat.put("charging", status == BatteryManager.BATTERY_STATUS_CHARGING
                        || status == BatteryManager.BATTERY_STATUS_FULL);
                bat.put("plugged", plugged);
                o.put("battery", bat);

                String net = "none";
                try {
                    android.net.ConnectivityManager cm = (android.net.ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);
                    if (cm != null) {
                        android.net.Network activeNet = cm.getActiveNetwork();
                        if (activeNet != null) {
                            android.net.NetworkCapabilities caps = cm.getNetworkCapabilities(activeNet);
                            if (caps != null) {
                                if (caps.hasTransport(android.net.NetworkCapabilities.TRANSPORT_ETHERNET)) {
                                    net = "ethernet";
                                } else if (caps.hasTransport(android.net.NetworkCapabilities.TRANSPORT_WIFI)) {
                                    net = "wifi";
                                } else if (caps.hasTransport(android.net.NetworkCapabilities.TRANSPORT_CELLULAR)) {
                                    net = "cellular";
                                }
                            }
                        }
                    }
                } catch (Throwable ignored) {}
                o.put("net", net);

                return o.toString();
            } catch (Throwable t) {
                return "{}";
            }
        }

        /** 打开系统文件选择器导入自定义桌面 zip */
        @JavascriptInterface
        public void pickDesktopZip() {
            runOnUiThread(() -> {
                try {
                    Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
                    i.addCategory(Intent.CATEGORY_OPENABLE);
                    i.setType("*/*");
                    i.putExtra(Intent.EXTRA_MIME_TYPES, new String[]{
                            "application/zip", "application/x-zip-compressed",
                            "application/octet-stream"});
                    i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION
                            | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
                    startActivityForResult(i, REQ_DESKTOP_ZIP);
                } catch (Throwable t) {
                    Toast.makeText(DexHomeActivity.this, "无法打开文件选择器", Toast.LENGTH_SHORT).show();
                }
            });
        }

        /** 恢复内置桌面 */
        @JavascriptInterface
        public void resetCustomDesktop() {
            runOnUiThread(() -> {
                CustomDesktopManager.reset(DexHomeActivity.this);
                customDesktopMode = false;
                loadDesktopPage();
            });
        }

        /** HTML 应用锁鼠（Pointer Lock API）支持：射击/3D 游戏等锁住鼠标时，隐藏软件光标并开启硬件捕获 */
        @JavascriptInterface
        public void setPointerLocked(final boolean locked) {
            runOnUiThread(() -> {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    if (locked && web != null) {
                        try { web.requestPointerCapture(); } catch (Throwable ignored) {}
                    } else if (!locked && web != null) {
                        try { web.releasePointerCapture(); } catch (Throwable ignored) {}
                    }
                }
                DeXPresentation.setPointerLocked(locked);
                com.alkaidlab.sunshine.service.RemoteCursorOverlay.INSTANCE.setSuppressed(locked);
            });
        }

        /** Dock 第一项「应用抽屉」：让桌面页打开应用抽屉 */
        @JavascriptInterface
        public void setActiveWinTitle(final String title) {
            notifyActiveWinChanged(title);
        }

        @JavascriptInterface
        public void openLaunchpad() {
            runOnUiThread(() -> jsEval("window.openLaunchpad && window.openLaunchpad();"));
        }

        /** 启动台开/关（桌面页自己的启动台，悬浮层不在时的 fallback）：
         *  与 Dock 同一层级 —— 打开时把应用窗口收起来，启动台成为最上层；关闭时全部恢复。 */
        @JavascriptInterface
        public void setLaunchpadOpen(final boolean open) {
            runOnUiThread(() -> {
                deskLaunchpadOpen = open;
                if (shell != null) {
                    if (open) shell.hideAllForLaunchpad();
                    else shell.restoreFromLaunchpad();
                }
                refreshCanghaiDock();
            });
        }

        /** 桌面被点击时兜底：确认已回到桌面则收起标题栏（自由窗口不触发 onResume 的场景） */
        @JavascriptInterface
        public void dismissCaption() {
            if (capVisible) hideCaption();
        }

        /** 标题栏页主动取前景应用信息（与 Java 侧 pushCaptionInfo 双路兼容，避免加载时序问题） */
        @JavascriptInterface
        public String captionInfo() {
            try {
                JSONObject o = new JSONObject();
                o.put("pkg", capPkg == null ? "" : capPkg);
                o.put("label", capLabel == null ? "" : capLabel);
                return o.toString();
            } catch (Throwable t) {
                return "{}";
            }
        }
    }

    // ==================== 桌面条目（布局持久化） ====================

    private void loadLayout() {
        SharedPreferences sp = getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        String saved = sp.getString(KEY_LAYOUT, "");
        boolean migrated = false;
        synchronized (items) {
            items.clear();
            if (saved != null && !saved.isEmpty()) {
                try {
                    JSONArray arr = new JSONArray(saved);
                    for (int i = 0; i < arr.length(); i++) {
                        String s = arr.optString(i);
                        if (s.startsWith("w:")) {
                            Item it = new Item();
                            it.widget = true;
                            it.wid = Integer.parseInt(s.substring(2));
                            items.add(it);
                        } else if (s.startsWith("s:")) {
                            Item it = new Item();
                            it.widget = false;
                            it.comp = s.substring(2);
                            items.add(it);
                        }
                    }
                } catch (Throwable t) {
                    Log.w(TAG, "布局解析失败，重新迁移: " + t.getMessage());
                }
            }
            if (items.isEmpty()) { // 从旧版两个 key 迁移
                try {
                    for (String s : sp.getString(KEY_WIDGET_IDS, "").split(",")) {
                        if (s.trim().isEmpty()) continue;
                        Item it = new Item();
                        it.widget = true;
                        it.wid = Integer.parseInt(s.trim());
                        items.add(it);
                        migrated = true;
                    }
                    for (String s : sp.getString(KEY_SHORTCUTS, "").split(",")) {
                        if (s.trim().isEmpty()) continue;
                        Item it = new Item();
                        it.widget = false;
                        it.comp = s;
                        items.add(it);
                        migrated = true;
                    }
                } catch (Throwable ignored) {
                }
            }
        }
        if (migrated) saveLayout();
    }

    private void saveLayout() {
        try {
            JSONArray arr = new JSONArray();
            synchronized (items) {
                for (Item it : items) {
                    arr.put(it.widget ? ("w:" + it.wid) : ("s:" + it.comp));
                }
            }
            getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                    .putString(KEY_LAYOUT, arr.toString()).apply();
        } catch (Throwable t) {
            Log.w(TAG, "保存布局失败: " + t.getMessage());
        }
    }

    private String itemsJson() {
        try {
            JSONArray arr = new JSONArray();
            PackageManager pm = getPackageManager();
            synchronized (items) {
                for (Item it : items) {
                    JSONObject o = new JSONObject();
                    if (it.widget) {
                        o.put("t", "w");
                        o.put("wid", it.wid);
                        String label = "";
                        AppWidgetManager mgr = AppWidgetManager.getInstance(this);
                        AppWidgetProviderInfo info = mgr.getAppWidgetInfo(it.wid);
                        if (info != null) label = String.valueOf(info.loadLabel(pm));
                        o.put("label", label);
                    } else {
                        o.put("t", "s");
                        o.put("comp", it.comp);
                        String label = it.comp;
                        try {
                            ComponentName cn = ComponentName.unflattenFromString(it.comp);
                            if (cn != null) label = String.valueOf(
                                    pm.getActivityInfo(cn, 0).loadLabel(pm));
                        } catch (Throwable ignored) {
                        }
                        o.put("label", label);
                    }
                    arr.put(o);
                }
            }
            return arr.toString();
        } catch (Throwable t) {
            return "[]";
        }
    }

    private String appsJson() {
        try {
            JSONArray arr = new JSONArray();
            synchronized (appComps) {
                for (String comp : appComps) {
                    JSONObject o = new JSONObject();
                    o.put("comp", comp);
                    o.put("label", appLabels.get(comp));
                    String icon = iconPaths.get(comp);
                    o.put("icon", icon == null ? "" : ("file://" + icon));
                    arr.put(o);
                }
            }
            return arr.toString();
        } catch (Throwable t) {
            return "[]";
        }
    }

    // ==================== Dock 固定应用列表 ====================

    private void loadDock() {
        synchronized (dockComps) {
            dockComps.clear();
            SharedPreferences sp = getSharedPreferences("aosen_settings", MODE_PRIVATE);
            String raw = sp.getString("dock_apps", null);
            dockUserCustomized = (raw != null);
            if (raw != null) {
                try {
                    JSONArray a = new JSONArray(raw);
                    for (int i = 0; i < a.length() && dockComps.size() < DOCK_MAX; i++) {
                        String c = a.optString(i);
                        if (c != null && !c.isEmpty()) dockComps.add(c);
                    }
                } catch (Throwable ignored) {}
            }
            dockLoaded = true;
        }
    }

    /** 首次使用且从未定制：按「系统应用优先」自动取满 8 个官方应用。
     *  用户主动移除过（含全部移除）后不再自动补位。 */
    private void ensureDockApps() {
        synchronized (dockComps) {
            if (!dockLoaded || dockUserCustomized || !dockComps.isEmpty()) return;
            List<String> sys = new ArrayList<>();
            List<String> usr = new ArrayList<>();
            synchronized (appComps) {
                for (String comp : appComps) {
                    String pkg = comp.contains("/") ? comp.substring(0, comp.indexOf('/')) : comp;
                    boolean isSys = false;
                    try {
                        isSys = (getPackageManager().getApplicationInfo(pkg, 0).flags
                                & android.content.pm.ApplicationInfo.FLAG_SYSTEM) != 0;
                    } catch (Throwable ignored) {}
                    (isSys ? sys : usr).add(comp);
                }
            }
            for (String c : sys) {
                if (dockComps.size() >= DOCK_MAX) break;
                dockComps.add(c);
            }
            for (String c : usr) {
                if (dockComps.size() >= DOCK_MAX) break;
                dockComps.add(c);
            }
            if (!dockComps.isEmpty()) saveDock();
        }
    }

    private void saveDock() {
        try {
            JSONArray a = new JSONArray();
            synchronized (dockComps) {
                for (String c : dockComps) a.put(c);
            }
            getSharedPreferences("aosen_settings", MODE_PRIVATE)
                    .edit().putString("dock_apps", a.toString()).apply();
        } catch (Throwable t) {
            Log.w(TAG, "保存 Dock 失败: " + t.getMessage());
        }
    }

    private String dockJson() {
        try {
            JSONArray arr = new JSONArray();
            synchronized (dockComps) {
                for (String comp : dockComps) {
                    String icon, label;
                    synchronized (appComps) {
                        icon = iconPaths.get(comp);
                        label = appLabels.get(comp);
                    }
                    if (label == null || label.isEmpty()) label = comp;
                    JSONObject o = new JSONObject();
                    o.put("comp", comp);
                    o.put("label", label);
                    o.put("icon", icon == null ? "" : ("file://" + icon));
                    arr.put(o);
                }
            }
            return arr.toString();
        } catch (Throwable t) {
            return "[]";
        }
    }

    private void pushDockApps() {
        jsEvalTb("window.AosenUI && AosenUI.setDockApps("
                + JSONObject.quote(dockJson()) + ");");
        jsEvalTb("window.reflowDock && window.reflowDock();");
    }

    private void removeItemByComp(String comp) {
        synchronized (items) {
            items.removeIf(it -> !it.widget && comp.equals(it.comp));
        }
    }

    private void removeItemByWidgetId(int wid) {
        synchronized (items) {
            items.removeIf(it -> it.widget && it.wid == wid);
        }
    }

    private void addItemWidget(int wid) {
        synchronized (items) {
            for (Item it : items) {
                if (it.widget && it.wid == wid) return; // 已存在
            }
            Item it = new Item();
            it.widget = true;
            it.wid = wid;
            items.add(0, it); // 新小组件放最前（桌面顶部）
        }
    }

    private void pushItems() {
        // 自定义桌面可能没有 AosenUI（规范不强求），必须防御式调用
        jsEval("window.AosenUI && AosenUI.setItems(" + JSONObject.quote(itemsJson()) + ");");
    }

    private void pushApps() {
        String script = "window.AosenUI && AosenUI.setApps(" + JSONObject.quote(appsJson()) + ");";
        jsEval(script);
        jsEval("window.onAppsUpdated && window.onAppsUpdated();");
        jsEvalTb(script);
        ensureDockApps();
        pushDockApps();
    }

    /** 把 Dock 需要的全部数据（文案 + 应用列表）重新推给悬浮 WebView，并触发一次重排。 */
    private void pushDockData() {
        jsEvalTb("window.AosenUI && AosenUI.setL10n(" + JSONObject.quote(new Bridge().l10n()) + ");");
        jsEvalTb("window.AosenUI && AosenUI.setApps(" + JSONObject.quote(appsJson()) + ");");
        ensureDockApps();
        pushDockApps();
    }

    private void pushWallpaper() {
        File f = new File(getCacheDir(), "dexwallpaper.jpg");
        if (f.exists()) {
            jsEval("applyWallpaper && applyWallpaper();");
            jsEval("window.AosenUI && AosenUI.setWallpaper("
                    + JSONObject.quote(f.getAbsolutePath()) + ");");
        }
    }

    // ==================== 小组件宿主 ====================

    @Override
    protected void onStart() {
        super.onStart();
        try {
            widgetHost.startListening();
        } catch (Throwable t) {
            Log.w(TAG, "widgetHost.startListening 失败: " + t.getMessage());
        }
    }

    @Override
    protected void onStop() {
        try {
            widgetHost.stopListening();
        } catch (Throwable t) {
            Log.w(TAG, "widgetHost.stopListening 失败: " + t.getMessage());
        }
        super.onStop();
    }

    private void restoreWidgets() {
        synchronized (items) {
            for (Item it : items) {
                if (it.widget) attachWidgetNative(it.wid);
            }
        }
    }

    /** 创建原生 AppWidgetHostView（位置等 JS 上报后对齐占位框）。 */
    private void attachWidgetNative(int appWidgetId) {
        if (widgetViews.containsKey(appWidgetId)) return;
        try {
            AppWidgetManager mgr = AppWidgetManager.getInstance(this);
            AppWidgetProviderInfo info = mgr.getAppWidgetInfo(appWidgetId);
            if (info == null || info.provider == null) {
                widgetHost.deleteAppWidgetId(appWidgetId);
                removeItemByWidgetId(appWidgetId);
                saveLayout();
                return;
            }
            AppWidgetHostView hv = widgetHost.createView(this, appWidgetId, info);
            hv.setVisibility(View.GONE); // 等 JS 上报矩形再显示
            hv.setOnLongClickListener(v -> {
                showWidgetMenu(appWidgetId);
                return true;
            });
            widgetViews.put(appWidgetId, hv);
            widgetOverlay.addView(hv, new FrameLayout.LayoutParams(1, 1));
            Log.i(TAG, "小组件已挂载: " + info.provider.flattenToString());
        } catch (Throwable t) {
            Log.w(TAG, "挂载小组件失败: " + t.getMessage());
            widgetHost.deleteAppWidgetId(appWidgetId);
        }
    }

    private void deleteWidgetNative(int wid) {
        AppWidgetHostView v = widgetViews.remove(wid);
        if (v != null) widgetOverlay.removeView(v);
        try {
            widgetHost.deleteAppWidgetId(wid);
        } catch (Throwable ignored) {
        }
    }

    /** 按 JS 上报的 CSS 像素矩形摆放原生小组件。 */
    private void positionWidgets(String json) {
        try {
            JSONArray arr = new JSONArray(json);
            float den = getResources().getDisplayMetrics().density;
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.getJSONObject(i);
                int wid = o.optInt("wid", -1);
                AppWidgetHostView v = widgetViews.get(wid);
                if (v == null || wid == pendingMoveWid) continue;
                FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) v.getLayoutParams();
                lp.leftMargin = Math.round((float) o.optDouble("x", 0) * den);
                lp.topMargin = Math.round((float) o.optDouble("y", 0) * den);
                lp.width = Math.round((float) o.optDouble("w", 120) * den);
                lp.height = Math.round((float) o.optDouble("h", 120) * den);
                lp.gravity = Gravity.TOP | Gravity.LEFT;
                v.setLayoutParams(lp);
                if (v.getVisibility() != View.VISIBLE) v.setVisibility(View.VISIBLE);
                int wdp = Math.max(1, lp.width / (int) den);
                int hdp = Math.max(1, lp.height / (int) den);
                v.updateAppWidgetSize(null, wdp, hdp, wdp, hdp);
            }
        } catch (Throwable t) {
            Log.w(TAG, "摆放小组件失败: " + t.getMessage());
        }
    }

    /** 长按小组件：移动位置 / 移除 */
    private void showWidgetMenu(final int wid) {
        new AlertDialog.Builder(this)
                .setItems(new CharSequence[]{
                        getString(R.string.widget_menu_move),
                        getString(R.string.widget_menu_remove)},
                        (d, w) -> {
                            if (w == 0) armWidgetMove(wid);
                            else {
                                removeItemByWidgetId(wid);
                                saveLayout();
                                deleteWidgetNative(wid);
                                pushItems();
                            }
                        })
                .show();
    }

    /** 进入小组件移动模式：下一次触摸拖动该小组件，松手落到目标格子。 */
    private void armWidgetMove(final int wid) {
        final AppWidgetHostView v = widgetViews.get(wid);
        if (v == null) return;
        pendingMoveWid = wid;
        jsEval("AosenUI.showGrid();");
        Toast.makeText(this, R.string.widget_move_hint, Toast.LENGTH_SHORT).show();
        v.setOnTouchListener((view, ev) -> {
            float den = getResources().getDisplayMetrics().density;
            switch (ev.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    v.setTag(new float[]{ev.getRawX(), ev.getRawY(),
                            v.getTranslationX(), v.getTranslationY()});
                    return true;
                case MotionEvent.ACTION_MOVE: {
                    float[] s = (float[]) v.getTag();
                    if (s == null) return false;
                    float dx = ev.getRawX() - s[0];
                    float dy = ev.getRawY() - s[1];
                    v.setTranslationX(s[2] + dx);
                    v.setTranslationY(s[3] + dy);
                    float cssX = ev.getRawX() / den;
                    float cssY = ev.getRawY() / den;
                    jsEval("AosenUI.moveHint(" + cssX + "," + cssY + ");");
                    return true;
                }
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL: {
                    v.setOnTouchListener(null);
                    float cssX = ev.getRawX() / den;
                    float cssY = ev.getRawY() / den;
                    pendingMoveWid = -1;
                    v.setTranslationX(0);
                    v.setTranslationY(0);
                    jsEval("AosenUI.widgetDropped(" + cssX + "," + cssY + "," + wid + ");");
                    return true;
                }
                default:
                    return true;
            }
        });
    }

    /**
     * 外屏页面里点「选择软件包」时弹系统文件选择器。
     *
     * 沧海软件包（.ch / .zip / 自定义扩展名）没有标准 MIME，若按页面 accept 拼过滤条件，
     * 系统选择器里会一个文件都点不亮。统一按任意类型打开，扩展名由页面校验。
     */
    private boolean isFolderMode(WebChromeClient.FileChooserParams params) {
        if (params == null) return false;
        if (params.getMode() == 2) return true;
        String[] types = params.getAcceptTypes();
        if (types != null) {
            for (String t : types) {
                if (t != null) {
                    String lower = t.toLowerCase().trim();
                    if (lower.contains("folder") || lower.contains("directory") || lower.contains("dir")) {
                        return true;
                    }
                }
            }
        }
        if (params.getTitle() != null) {
            String titleLower = params.getTitle().toString().toLowerCase().trim();
            if (titleLower.contains("folder") || titleLower.contains("目录") || titleLower.contains("文件夹")) {
                return true;
            }
        }
        return false;
    }

    private boolean isSpecificFileMode(WebChromeClient.FileChooserParams params) {
        if (params == null) return false;
        String[] types = params.getAcceptTypes();
        if (types == null || types.length == 0) return false;
        for (String t : types) {
            if (t != null) {
                String s = t.trim();
                if (!s.isEmpty() && !s.equals("*/*")) {
                    return true;
                }
            }
        }
        return false;
    }

    private void launchFilePicker(WebChromeClient.FileChooserParams params) {
        try {
            Intent intent = new Intent(Intent.ACTION_GET_CONTENT);
            intent.addCategory(Intent.CATEGORY_OPENABLE);
            if (params != null && params.getMode() == WebChromeClient.FileChooserParams.MODE_OPEN_MULTIPLE) {
                intent.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
            }
            String[] types = (params != null) ? params.getAcceptTypes() : null;
            if (types != null && types.length > 0 && types[0] != null && !types[0].isEmpty() && !types[0].equals("*/*")) {
                if (types.length == 1) {
                    intent.setType(types[0]);
                } else {
                    intent.setType("*/*");
                    intent.putExtra(Intent.EXTRA_MIME_TYPES, types);
                }
            } else {
                intent.setType("*/*");
            }
            try {
                intent.putExtra("android.provider.extra.INITIAL_URI", android.net.Uri.parse(
                        "content://com.android.externalstorage.documents/document/primary%3ADownload"));
            } catch (Throwable ignored) {}
            startActivityForResult(Intent.createChooser(intent, "选择文件"), REQ_WEB_FILE);
        } catch (Throwable t) {
            Log.w(TAG, "打开文件选择器失败: " + t.getMessage());
            if (webFileCallback != null) {
                try { webFileCallback.onReceiveValue(null); } catch (Throwable ignored) {}
                webFileCallback = null;
            }
        }
    }

    private void launchFolderPicker() {
        try {
            Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION
                    | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION
                    | Intent.FLAG_GRANT_PREFIX_URI_PERMISSION);
            startActivityForResult(intent, REQ_WEB_FOLDER);
        } catch (Throwable t) {
            Log.w(TAG, "打开文件夹选择器失败: " + t.getMessage());
            if (webFileCallback != null) {
                try { webFileCallback.onReceiveValue(null); } catch (Throwable ignored) {}
                webFileCallback = null;
            }
        }
    }

    private boolean pickWebFile(android.webkit.ValueCallback<android.net.Uri[]> callback, WebChromeClient.FileChooserParams params) {
        if (webFileCallback != null) {
            try { webFileCallback.onReceiveValue(null); } catch (Throwable ignored) {}
        }
        webFileCallback = callback;

        if (isFolderMode(params)) {
            launchFolderPicker();
            return true;
        }
        if (isSpecificFileMode(params)) {
            launchFilePicker(params);
            return true;
        }

        try {
            new AlertDialog.Builder(this)
                    .setTitle("请选择操作")
                    .setItems(new CharSequence[]{"📄 选择文件", "📁 选择文件夹"}, (dialog, which) -> {
                        if (which == 0) {
                            launchFilePicker(params);
                        } else {
                            launchFolderPicker();
                        }
                    })
                    .setOnCancelListener(dialog -> {
                        if (webFileCallback != null) {
                            try { webFileCallback.onReceiveValue(null); } catch (Throwable ignored) {}
                            webFileCallback = null;
                        }
                    })
                    .show();
            return true;
        } catch (Throwable t) {
            launchFilePicker(params);
            return true;
        }
    }


    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQ_EXPORT) {
            CanghaiStore.finishExport(this, requestCode, REQ_EXPORT, resultCode, data);
            return;
        }
        if (requestCode == REQ_WEB_FILE) {
            android.net.Uri[] picked = null;
            if (resultCode == RESULT_OK && data != null) {
                if (data.getClipData() != null) {
                    int count = data.getClipData().getItemCount();
                    picked = new android.net.Uri[count];
                    for (int i = 0; i < count; i++) {
                        picked[i] = data.getClipData().getItemAt(i).getUri();
                    }
                } else if (data.getData() != null) {
                    picked = new android.net.Uri[]{ data.getData() };
                }
                if (picked != null && picked.length > 0) {
                    lastPickedUri = picked[0].toString();
                } else {
                    lastPickedUri = null;
                }
            } else {
                lastPickedUri = null;
            }
            if (webFileCallback != null) {
                try { webFileCallback.onReceiveValue(picked); } catch (Throwable ignored) {}
                webFileCallback = null;
            }
            return;
        }
        if (requestCode == REQ_WEB_FOLDER) {
            android.net.Uri[] picked = null;
            if (resultCode == RESULT_OK && data != null && data.getData() != null) {
                android.net.Uri treeUri = data.getData();
                try {
                    final int takeFlags = data.getFlags()
                            & (Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
                    getContentResolver().takePersistableUriPermission(treeUri, takeFlags);
                } catch (Throwable ignored) {}
                picked = new android.net.Uri[]{ treeUri };
                lastPickedUri = treeUri.toString();
            } else {
                lastPickedUri = null;
            }
            if (webFileCallback != null) {
                try { webFileCallback.onReceiveValue(picked); } catch (Throwable ignored) {}
                webFileCallback = null;
            }
            return;
        }
        if (requestCode == REQ_DESKTOP_ZIP) {
            if (resultCode != RESULT_OK || data == null || data.getData() == null) return;
            final Uri uri = data.getData();
            // 解压在后台线程做（zip 可能十几 MB），完事回主线程换页
            new Thread(() -> {
                boolean ok = CustomDesktopManager.install(DexHomeActivity.this, uri);
                runOnUiThread(() -> {
                    Toast.makeText(DexHomeActivity.this,
                            ok ? "自定义桌面已导入，正在加载"
                               : "导入失败：zip 根目录必须有 index.html（≤20MB / ≤600 个文件）",
                            Toast.LENGTH_LONG).show();
                    if (ok) loadDesktopPage();
                });
            }, "desktop-zip-import").start();
            return;
        }
        if (requestCode == REQ_BIND_WIDGET) {
            int id = pendingBindWidgetId;
            pendingBindWidgetId = -1;
            if (resultCode == RESULT_OK && id >= 0) {
                attachWidgetNative(id);
                addItemWidget(id);
                saveLayout();
                pushItems();
            } else if (id >= 0) {
                try {
                    widgetHost.deleteAppWidgetId(id);
                } catch (Throwable ignored) {
                }
            }
        }
    }

    // ==================== 小组件选择（保留系统授权流） ====================

    private void showWidgetPicker() {
        try {
            widgetHost.startListening();
            AppWidgetManager mgr = AppWidgetManager.getInstance(this);
            List<AppWidgetProviderInfo> all = mgr.getInstalledProviders();
            List<AppWidgetProviderInfo> candidates = new ArrayList<>();
            List<String> labels = new ArrayList<>();
            PackageManager pm = getPackageManager();
            for (AppWidgetProviderInfo info : all) {
                // 跳过带配置向导的小组件（配置页会弹到手机屏）以及自身
                if (info == null || info.provider == null || info.configure != null) continue;
                if (getPackageName().equals(info.provider.getPackageName())) continue;
                CharSequence label = info.loadLabel(pm);
                String appLabel;
                try {
                    appLabel = String.valueOf(pm.getApplicationLabel(
                            pm.getApplicationInfo(info.provider.getPackageName(), 0)));
                } catch (Throwable t) {
                    appLabel = info.provider.getPackageName();
                }
                candidates.add(info);
                labels.add(String.valueOf(label) + " · " + appLabel);
            }
            if (candidates.isEmpty()) {
                Toast.makeText(this, R.string.widget_none, Toast.LENGTH_SHORT).show();
                return;
            }
            List<Integer> order = new ArrayList<>();
            for (int i = 0; i < candidates.size(); i++) order.add(i);
            Collections.sort(order, (a, b) -> labels.get(a).compareToIgnoreCase(labels.get(b)));

            CharSequence[] cs = new CharSequence[candidates.size()];
            for (int i = 0; i < order.size(); i++) cs[i] = labels.get(order.get(i));
            new AlertDialog.Builder(this)
                    .setTitle(R.string.dialog_pick_widget)
                    .setItems(cs, (dialog, which) ->
                            bindWidget(candidates.get(order.get(which))))
                    .setNegativeButton(R.string.action_cancel, null)
                    .show();
        } catch (Throwable t) {
            Log.w(TAG, "小组件列表失败: " + t.getMessage());
            Toast.makeText(this, R.string.widget_none, Toast.LENGTH_SHORT).show();
        }
    }

    /** 绑定：先试 bindAppWidgetIdIfAllowed，失败走系统授权弹窗（弹在本虚拟屏）。 */
    private void bindWidget(AppWidgetProviderInfo info) {
        final int id = widgetHost.allocateAppWidgetId();
        AppWidgetManager mgr = AppWidgetManager.getInstance(this);
        boolean ok = false;
        try {
            ok = mgr.bindAppWidgetIdIfAllowed(id, info.provider);
        } catch (Throwable t) {
            Log.w(TAG, "bindAppWidgetIdIfAllowed 异常: " + t.getMessage());
        }
        if (ok) {
            attachWidgetNative(id);
            addItemWidget(id);
            saveLayout();
            pushItems();
            return;
        }
        pendingBindWidgetId = id;
        try {
            Intent i = new Intent(AppWidgetManager.ACTION_APPWIDGET_BIND);
            i.putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, id);
            startActivityForResult(i, REQ_BIND_WIDGET);
        } catch (Throwable t) {
            Log.w(TAG, "拉起授权失败: " + t.getMessage());
            widgetHost.deleteAppWidgetId(id);
            pendingBindWidgetId = -1;
        }
    }

    // ==================== 壁纸（缓存成 JPEG 文件给 WebView） ====================

    /**
     * 壁纸读取链：① WallpaperManager.getDrawable()（读取手机当前桌面壁纸）；
     * ② getWallpaperFile()；③ Shizuku/root 兜底提取 /data/system/users/0/wallpaper；
     * 成功后压缩缓存为 JPEG，通知前端渲染。
     */
    private void loadWallpaperAsync() {
        new Thread(() -> {
            Bitmap full = null;
            try {
                try {
                    String g = ShizukuCmd.execute("pm grant " + getPackageName()
                            + " android.permission.READ_EXTERNAL_STORAGE");
                    Log.i(TAG, "pm grant 壁纸权限: " + (g == null || g.isEmpty() ? "ok" : g.trim()));
                } catch (Throwable t) {
                    Log.w(TAG, "pm grant 失败: " + t.getMessage());
                }

                WallpaperManager wm = WallpaperManager.getInstance(this);

                // 1. 优先尝试系统公开 API: wm.getDrawable() 直接获取手机当前壁纸
                try {
                    Drawable d = wm.getDrawable();
                    if (d instanceof BitmapDrawable) {
                        full = ((BitmapDrawable) d).getBitmap();
                    } else if (d != null) {
                        int w = Math.max(1, d.getIntrinsicWidth());
                        int h = Math.max(1, d.getIntrinsicHeight());
                        Bitmap b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
                        Canvas c = new Canvas(b);
                        d.setBounds(0, 0, w, h);
                        d.draw(c);
                        full = b;
                    }
                    if (full != null) {
                        Log.i(TAG, "从 wm.getDrawable() 成功提取手机壁纸: " + full.getWidth() + "x" + full.getHeight());
                    }
                } catch (Throwable t) {
                    Log.w(TAG, "wm.getDrawable 提取壁纸失败: " + t.getMessage());
                }

                // 2. 尝试 wm.getWallpaperFile(FLAG_SYSTEM)
                if (full == null) {
                    try {
                        ParcelFileDescriptor pfd = wm.getWallpaperFile(WallpaperManager.FLAG_SYSTEM);
                        if (pfd != null) {
                            full = BitmapFactory.decodeFileDescriptor(pfd.getFileDescriptor());
                            try {
                                pfd.close();
                            } catch (Throwable ignored) {
                            }
                        }
                    } catch (Throwable t) {
                        Log.w(TAG, "getWallpaperFile 失败: " + t.getMessage());
                    }
                }

                // 3. Shizuku/Root 兜底拷贝
                if (full == null) {
                    try {
                        String tmp = getCacheDir().getAbsolutePath() + "/tmp_sys_wp";
                        ShizukuCmd.execute("cp /data/system/users/0/wallpaper " + tmp + " && chmod 644 " + tmp);
                        File tmpF = new File(tmp);
                        if (tmpF.exists() && tmpF.length() > 0) {
                            full = BitmapFactory.decodeFile(tmp);
                            tmpF.delete();
                            Log.i(TAG, "Shizuku 提取系统壁纸成功");
                        }
                    } catch (Throwable t) {
                        Log.w(TAG, "Shizuku 提取壁纸失败: " + t.getMessage());
                    }
                }

                if (full == null && RootCmd.isAvailable()) {
                    try {
                        final String tmp = "/data/local/tmp/aosen_wp_bg";
                        RootCmd.execute("cp /data/system/users/0/wallpaper " + tmp);
                        RootCmd.execute("chmod 644 " + tmp);
                        full = BitmapFactory.decodeFile(tmp);
                        RootCmd.execute("rm -f " + tmp);
                        Log.i(TAG, "root 壁纸兜底: " + (full != null ? "成功" : "解码失败"));
                    } catch (Throwable t) {
                        Log.w(TAG, "root 壁纸兜底失败: " + t.getMessage());
                    }
                }
                if (full == null) {
                    Log.w(TAG, "所有壁纸通道均失败（保持深色底）");
                    return;
                }
                File out = new File(getCacheDir(), "dexwallpaper.jpg");
                FileOutputStream fos = new FileOutputStream(out);
                full.compress(Bitmap.CompressFormat.JPEG, 90, fos);
                fos.close();
                Log.i(TAG, "壁纸已缓存: " + out.getAbsolutePath()
                        + " " + full.getWidth() + "x" + full.getHeight());
                pushWallpaper();
            } catch (Throwable t) {
                Log.w(TAG, "读取壁纸异常: " + t.getMessage());
            }
        }).start();
    }

    // ==================== 应用列表 + 图标缓存 ====================

    private void loadAppsAsync() {
        synchronized (this) {
            if (appsLoading) return;
            appsLoading = true;
        }
        new Thread(() -> {
            boolean needRetry = false;
            try {
                LauncherApps launcherApps = (LauncherApps)
                        getSystemService(Context.LAUNCHER_APPS_SERVICE);
                List<LauncherActivityInfo> raw =
                        launcherApps.getActivityList(null, Process.myUserHandle());
                List<LauncherActivityInfo> sorted = new ArrayList<>(raw);
                Collections.sort(sorted, (a, b) -> String.valueOf(a.getLabel())
                        .compareToIgnoreCase(String.valueOf(b.getLabel())));

                File iconDir = new File(getCacheDir(), "dexicons");
                if (!iconDir.exists()) iconDir.mkdirs();

                // 先在局部变量里构建完整快照，再一次性替换；
                // 避免扫描途中 appsJson() 返回半份列表（图标还没生成），Dock 画成一个小空壳。
                List<String> comps = new ArrayList<>();
                Map<String, String> labels = new HashMap<>();
                Map<String, String> icons = new HashMap<>();
                int i = 0;
                for (LauncherActivityInfo lai : sorted) {
                    String comp = lai.getComponentName().flattenToString();
                    comps.add(comp);
                    labels.put(comp, String.valueOf(lai.getLabel()));
                    // 图标渲染到缓存 PNG（56dp 显示，96px 足够）
                    try {
                        Drawable d = lai.getBadgedIcon(0);
                        if (d != null) {
                            Bitmap bm = Bitmap.createBitmap(96, 96, Bitmap.Config.ARGB_8888);
                            Canvas c = new Canvas(bm);
                            d.setBounds(0, 0, 96, 96);
                            d.draw(c);
                            File f = new File(iconDir, "app_" + (i++) + ".png");
                            FileOutputStream fos = new FileOutputStream(f);
                            bm.compress(Bitmap.CompressFormat.PNG, 100, fos);
                            fos.close();
                            icons.put(comp, f.getAbsolutePath());
                        }
                    } catch (Throwable t) {
                        Log.w(TAG, "图标缓存失败: " + comp + " " + t.getMessage());
                    }
                }
                synchronized (appComps) {
                    appComps.clear();
                    appComps.addAll(comps);
                    appLabels.clear();
                    appLabels.putAll(labels);
                    iconPaths.clear();
                    iconPaths.putAll(icons);
                }
                Log.i(TAG, "应用列表加载完成: " + comps.size() + " 个");
                pushApps();
                pushItems(); // 图标就绪后重推桌面（补 icon）
                // 首次读取可能被系统「是否允许」弹窗挡住而返回空，自动重扫，不必重启桌面
                needRetry = comps.isEmpty() && appsRetry < 6;
            } catch (Throwable t) {
                Log.w(TAG, "加载应用列表失败: " + t.getMessage());
                needRetry = appsRetry < 6;
            } finally {
                appsLoading = false;
            }
            if (needRetry) {
                appsRetry++;
                Log.i(TAG, "应用列表为空，自动重扫 (" + appsRetry + ")");
                new Handler(Looper.getMainLooper()).postDelayed(
                        DexHomeActivity.this::loadAppsAsync, 1500);
            } else {
                appsRetry = 0;
            }
        }).start();
    }

    // ==================== 应用启动（含窗口模式校正 + 边界归一化） ====================

    /**
     * 真·窗口壳启动：不再把应用丢到桌面虚拟屏上（那样必然是带原生标题栏的 DeX 自由窗口），
     * 而是由 AppShellManager 为它建一块无系统装饰的专属虚拟屏，全屏跑在壳里。
     */
    public void launchByComponent(final String comp, final String label) {
        String real = appLabels.get(comp);
        if (real == null || real.isEmpty()) real = label;
        final String title = (real == null || real.isEmpty()) ? comp : real;
        Log.i(TAG, "启动应用（窗口壳）: " + comp + " 标题=" + title);
        // 用户主动开应用引起的失焦，不要被 HOME 自愈顶回桌面。
        HomeGuard.noteUserOpenedApp();
        if (shell == null) return;
        shell.restoreFromLaunchpad();   // 从启动台点图标时，恢复被临时隐藏的其它窗口
        shell.open(comp, title);
        notifyActiveWinChanged(title);
    }

    /**
     * 打开沧海自己的 HTML 软件。
     *
     * 走页面内窗口（#winLayer / .win），不再开系统 freeform Task：用系统外壳时会
     * 被系统窗口压住、还会连带挡住 Dock，实测还会黑屏。HTML 软件就是咱们自己的
     * 软件，层级自己管。
     */
    public void launchHtmlApp(final String appId, final String label) {
        if (appId == null || appId.isEmpty()) return;
        Log.i(TAG, "在页面内打开沧海软件: " + appId + " 标题=" + label);
        HomeGuard.noteUserOpenedApp();
        runOnUiThread(() -> {
            try {
                if (web == null) return;
                web.evaluateJavascript(
                        "window.__canghaiOpenApp && window.__canghaiOpenApp("
                                + JSONObject.quote(appId) + ");", null);
            } catch (Throwable t) {
                Log.w(TAG, "页面内打开失败: " + t.getMessage());
            }
        });
    }

    /** 扫描包内 Activity 找"真实主界面"，绕开三星别名桩。 */
    private String findRealMainActivity(String pkg, String stubFlatComp) {
        try {
            String stubCls = stubFlatComp.substring(stubFlatComp.indexOf('/') + 1);
            if (stubCls.startsWith(".")) stubCls = pkg + stubCls;
            PackageInfo pi = getPackageManager().getPackageInfo(pkg,
                    PackageManager.GET_ACTIVITIES);
            if (pi.activities == null) return null;
            String secondBest = null;
            for (ActivityInfo ai : pi.activities) {
                if (!ai.isEnabled()) continue;
                String name = ai.name;
                if (name.equals(stubCls)) continue;
                String simple = name.substring(name.lastIndexOf('.') + 1);
                if (simple.equals("MainActivity")) return name;
                if (secondBest == null && simple.contains("Main") && simple.endsWith("Activity")) {
                    secondBest = name;
                }
            }
            return secondBest;
        } catch (Throwable t) {
            Log.w(TAG, "扫描真实主界面失败: " + t.getMessage());
            return null;
        }
    }

    /** 解析 am stack list，返回 int[]{taskId, windowingMode, l, t, r, b}。 */
    private static int[] getTaskInfoOnDisplay(String pkg, int displayId) {
        try {
            String dump = ShizukuCmd.execute("am stack list");
            if (dump == null || dump.isEmpty()) return new int[]{-1, -1, 0, 0, 0, 0};
            int currentDisplay = -1;
            int lastMode = -1;
            int lastTaskId = -1;
            for (String raw : dump.split("\n")) {
                String line = raw.trim();
                if (line.startsWith("RootTask") && line.contains("displayId=")) {
                    currentDisplay = ShizukuCmd.extractDisplayId(line);
                    continue;
                }
                int m = line.indexOf("mWindowingMode=");
                if (m >= 0) {
                    String seg = line.substring(m + "mWindowingMode=".length());
                    int space = seg.indexOf(' ');
                    if (space >= 0) seg = seg.substring(0, space);
                    if (seg.contains("freeform")) lastMode = 5;
                    else if (seg.contains("fullscreen")) lastMode = 1;
                    else lastMode = -1;
                }
                if (currentDisplay == displayId && line.startsWith("taskId=")
                        && line.contains(pkg)) {
                    try {
                        int colon = line.indexOf(':');
                        if (colon > "taskId=".length()) {
                            lastTaskId = Integer.parseInt(
                                    line.substring("taskId=".length(), colon).trim());
                        }
                    } catch (NumberFormatException ignored) {
                    }
                    int[] b = parseBounds(line);
                    return new int[]{lastTaskId, lastMode, b[0], b[1], b[2], b[3]};
                }
            }
        } catch (Throwable t) {
            Log.w(TAG, "解析任务模式失败: " + t.getMessage());
        }
        return new int[]{-1, -1, 0, 0, 0, 0};
    }

    private static int[] parseBounds(String line) {
        try {
            int i = line.indexOf("bounds=[");
            if (i < 0) return new int[]{0, 0, 0, 0};
            String s = line.substring(i + "bounds=[".length());
            int comma = s.indexOf(',');
            int close = s.indexOf(']');
            int l = Integer.parseInt(s.substring(0, comma).trim());
            int t = Integer.parseInt(s.substring(comma + 1, close).trim());
            s = s.substring(close + 1);
            int comma2 = s.indexOf(',');
            int close2 = s.indexOf(']');
            int r = Integer.parseInt(s.substring(1, comma2).trim());
            int b = Integer.parseInt(s.substring(comma2 + 1, close2).trim());
            return new int[]{l, t, r, b};
        } catch (Throwable t) {
            return new int[]{0, 0, 0, 0};
        }
    }

    /** 越界或宽高比 > 3:1 的异常窗口 → 居中重置为屏幕 2/3。 */
    private void normalizeTaskBounds(String pkg) {
        int[] info = getTaskInfoOnDisplay(pkg, displayId);
        if (info[0] == -1 || info[1] != 5) return;
        int l = info[2], t = info[3], r = info[4], b = info[5];
        int w = r - l, h = b - t;
        android.util.DisplayMetrics dm = getResources().getDisplayMetrics();
        int dispW = dm.widthPixels, dispH = dm.heightPixels;
        boolean broken = w <= 0 || h <= 0
                || l < 0 || t < 0 || r > dispW || b > dispH
                || (h > 0 && w / (float) h > 3f)
                || (w > 0 && h / (float) w > 3f);
        if (!broken) return;
        int nw = Math.round(dispW * 0.66f);
        int nh = Math.round(dispH * 0.66f);
        int nl = Math.max(0, (dispW - nw) / 2);
        int nt = Math.max(0, (dispH - nh) / 2);
        Log.w(TAG, "窗口边界异常 " + pkg + " [" + l + "," + t + "][" + r + "," + b
                + "] (屏 " + dispW + "x" + dispH + ") → 归一化 [" + nl + "," + nt
                + "][" + (nl + nw) + "," + (nt + nh) + "]");
        ShizukuCmd.execute("am task resize " + info[0]
                + " " + nl + " " + nt + " " + (nl + nw) + " " + (nt + nh));
    }

    /** 把应用窗口拉满整块虚拟屏：配合顶部伪装标题栏，视觉上就是「一个 macOS 窗口里的软件」。
     *  Activity 本身运行在该虚拟屏上，getResources().getDisplayMetrics() 即该屏尺寸。 */
    private void maximizeTask(String pkg) {
        try {
            int[] info = getTaskInfoOnDisplay(pkg, displayId);
            if (info[0] == -1) {
                Log.w(TAG, "窗口全屏失败: 未在 display " + displayId + " 找到 " + pkg + " 的 task");
                return;
            }
            int w = getResources().getDisplayMetrics().widthPixels;
            int h = getResources().getDisplayMetrics().heightPixels;
            String r = ShizukuCmd.execute("am task resize " + info[0] + " 0 0 " + w + " " + h);
            Log.i(TAG, "伪装标题栏: 窗口全屏 " + pkg + " [" + w + "x" + h + "] task="
                    + info[0] + " 结果=" + r);
            // 只有真正全屏才隐藏 Dock；自由窗口 / 启动过程中 Dock 常驻
            runOnUiThread(() -> {
                capMaximized = true;
                jsEvalTb("hideTaskbar();");
                syncCaptionPosition();
            });
        } catch (Throwable t) {
            Log.w(TAG, "窗口全屏失败: " + t.getMessage());
        }
    }

    /** 让伪装标题栏跟随应用窗口左上角，而不是永远贴在屏幕顶端（自由窗口时两者会错位）。 */
    private void syncCaptionPosition() {
        if (capWin == null || capHolder == null || !capVisible || capPkg == null
                || capPkg.isEmpty()) return;
        try {
            int[] info = getTaskInfoOnDisplay(capPkg, displayId);
            if (info[0] == -1) return;
            int l = info[2], t = info[3], r = info[4];
            capWin.updatePosition(l, t);
            capWin.updateLayout(Math.max(1, r - l), capBarHpx);
        } catch (Throwable t) {
            Log.w(TAG, "标题栏跟随窗口失败: " + t.getMessage());
        }
    }

    /** 绿点：在「全屏 / 还原成自由窗口」之间切换，并同步 Dock 显隐。 */
    private void toggleMaximize() {
        final String pkg = capPkg;
        if (pkg == null || pkg.isEmpty()) return;
        new Thread(() -> {
            int[] info = getTaskInfoOnDisplay(pkg, displayId);
            if (info[0] == -1) {
                Log.w(TAG, "绿点切换失败: 找不到 " + pkg + " 的 task");
                return;
            }
            int w = getResources().getDisplayMetrics().widthPixels;
            int h = getResources().getDisplayMetrics().heightPixels;
            boolean isMax = info[2] <= 4 && info[3] <= 4
                    && (info[4] - info[2]) >= w - 8
                    && (info[5] - info[3]) >= h - 8;
            String cmd;
            if (isMax) {
                int nw = Math.round(w * 0.72f), nh = Math.round(h * 0.78f);
                int nl = (w - nw) / 2, nt = (h - nh) / 2;
                cmd = "am task resize " + info[0] + " " + nl + " " + nt
                        + " " + (nl + nw) + " " + (nt + nh);
            } else {
                cmd = "am task resize " + info[0] + " 0 0 " + w + " " + h;
            }
            String r = ShizukuCmd.execute(cmd);
            final boolean nowMax = !isMax;
            Log.i(TAG, "绿点切换最大化: " + pkg + " nowMax=" + nowMax
                    + " cmd=" + cmd + " 结果=" + r);
            runOnUiThread(() -> {
                capMaximized = nowMax;
                jsEvalTb(nowMax ? "hideTaskbar();" : "showTaskbar();");
                syncCaptionPosition();
            });
        }).start();
    }

    /** 标题栏开始拖动：缓存一次窗口 bounds，拖动过程中不再逐帧查 am stack list。 */
    private void beginCaptionDrag() {
        new Thread(() -> {
            capDragBounds = getTaskInfoOnDisplay(capPkg, displayId);
            Log.i(TAG, "标题栏拖动开始 taskId=" + capDragBounds[0]);
        }).start();
    }

    /** 标题栏拖动：按累计位移平移窗口（dx/dy 为相对拖动起点的 CSS 像素，需乘 density）。 */
    private void moveCaptionWindow(final int dx, final int dy) {
        int[] b = capDragBounds;
        if (b == null || b[0] == -1) return;
        final float den = getResources().getDisplayMetrics().density;
        final int ddx = Math.round(dx * den), ddy = Math.round(dy * den);
        int w = b[4] - b[2], h = b[5] - b[3];
        int nl = b[2] + ddx, nt = b[3] + ddy;
        new Thread(() -> {
            ShizukuCmd.execute("am task resize " + b[0] + " " + nl + " " + nt
                    + " " + (nl + w) + " " + (nt + h));
        }).start();
        // 浮层立即跟随，不等 shell 命令返回，保证跟手
        runOnUiThread(() -> {
            try {
                if (capWin == null || capHolder == null || !capVisible) return;
                capWin.updatePosition(nl, nt);
                capWin.updateLayout(Math.max(1, w), capBarHpx);
            } catch (Throwable ignored) {}
        });
    }

    private void endCaptionDrag() {
        capDragBounds = null;
        runOnUiThread(this::syncCaptionPosition);
    }

    // ==================== 四向贴边 ====================

    private void showSnapMenu() {
        new AlertDialog.Builder(this)
                .setTitle(R.string.snap_title)
                .setItems(new CharSequence[]{
                        getString(R.string.snap_left), getString(R.string.snap_right),
                        getString(R.string.snap_top), getString(R.string.snap_bottom)
                }, (dialog, which) -> snapFocusedWindow(which))
                .setNegativeButton(R.string.action_cancel, null)
                .show();
    }

    private void snapFocusedWindow(final int which) {
        new Thread(() -> {
            int tid = findTopAppTaskOnDisplay();
            if (tid <= 0) {
                runOnUiThread(() -> Toast.makeText(DexHomeActivity.this,
                        R.string.toast_snap_no_window, Toast.LENGTH_SHORT).show());
                return;
            }
            int w = getResources().getDisplayMetrics().widthPixels;
            int h = getResources().getDisplayMetrics().heightPixels;
            String bounds;
            switch (which) {
                case 1: bounds = w / 2 + " 0 " + w + " " + h; break;
                case 2: bounds = "0 0 " + w + " " + h / 2; break;
                case 3: bounds = "0 " + h / 2 + " " + w + " " + h; break;
                default: bounds = "0 0 " + w / 2 + " " + h; break;
            }
            String r = ShizukuCmd.execute("am task resize " + tid + " " + bounds);
            Log.i(TAG, "贴边: task=" + tid + " bounds=[" + bounds + "] 结果=" + r);
        }).start();
    }

    private int findTopAppTaskOnDisplay() {
        try {
            String dump = ShizukuCmd.execute("am stack list");
            if (dump == null || dump.isEmpty()) return -1;
            int currentDisplay = -1;
            for (String raw : dump.split("\n")) {
                String line = raw.trim();
                if (line.startsWith("RootTask") && line.contains("displayId=")) {
                    currentDisplay = ShizukuCmd.extractDisplayId(line);
                    continue;
                }
                if (currentDisplay == displayId && line.startsWith("taskId=")
                        && !line.contains(BuildConfig.APPLICATION_ID)
                        && !line.contains("com.aosen.shizukudex")
                        && !line.contains("com.sec.android.app.launcher")) {
                    int colon = line.indexOf(':');
                    return Integer.parseInt(line.substring("taskId=".length(), colon).trim());
                }
            }
        } catch (Throwable t) {
            Log.w(TAG, "查找贴边任务失败: " + t.getMessage());
        }
        return -1;
    }

    // ==================== 三星 DeX 桌面清理 ====================

    private void cleanupSamsungDexLauncher(final boolean retry) {
        new Thread(() -> {
            int misses = 0;
            for (int attempt = 0; attempt < (retry ? 5 : 1); attempt++) {
                if (attempt > 0) {
                    try {
                        Thread.sleep(1000);
                    } catch (InterruptedException ignored) {
                        return;
                    }
                }
                boolean found = removeSamsungDexLauncherOnce();
                if (found) {
                    misses = 0;
                } else if (++misses >= 2) {
                    break;
                }
            }
        }).start();
    }

    private boolean removeSamsungDexLauncherOnce() {
        try {
            String dump = ShizukuCmd.execute("am stack list");
            if (dump == null || dump.isEmpty()) return false;
            boolean found = false;
            int currentDisplay = -1;
            for (String raw : dump.split("\n")) {
                String line = raw.trim();
                if (line.startsWith("RootTask") && line.contains("displayId=")) {
                    currentDisplay = ShizukuCmd.extractDisplayId(line);
                    continue;
                }
                if (currentDisplay == displayId && line.startsWith("taskId=")
                        && line.contains("com.sec.android.app.launcher")
                        && line.contains("SecondaryLauncher")) {
                    int colon = line.indexOf(':');
                    int tid = Integer.parseInt(
                            line.substring("taskId=".length(), colon).trim());
                    Log.i(TAG, "移除三星 DeX 桌面任务: taskId=" + tid);
                    ShizukuCmd.execute("am stack remove " + tid);
                    found = true;
                }
            }
            return found;
        } catch (Throwable t) {
            Log.w(TAG, "清理三星桌面失败: " + t.getMessage());
            return false;
        }
    }

    // ==================== 生命周期 ====================

    /** 前台实例所在 displayId（Sunshine 导航服务用它避免在桌面上重复注入全局动作） */
    public static volatile int foregroundDisplayId = -1;

    public static final String EXTRA_LAUNCH_COMPONENT = "launch_component";

    /** Single entry for "open this app on this screen" coming from outside the desktop (phone UI, Moonlight controller). */
    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        String comp = intent == null ? null : intent.getStringExtra(EXTRA_LAUNCH_COMPONENT);
        if (comp != null && !comp.isEmpty()) launchByComponent(comp, comp);
        handleDesktopPage(intent);
    }

    @Override
    protected void onResume() {
        super.onResume();
        deskForeground = true;
        if (getWindow() != null && getWindow().getDecorView() != null) {
            getWindow().getDecorView().requestFocus();
        }
        updateTopBarScreenStatus();
        try {
            foregroundDisplayId = getWindowManager().getDefaultDisplay().getDisplayId();
        } catch (Throwable ignored) {}
        cleanupSamsungDexLauncher(false);
        // 从应用返回桌面：任务栏滑回（悬浮窗角色），伪装标题栏收起
        jsEvalTb("showTaskbar();");
        hideCaption();
    }

    @Override
    protected void onPause() {
        deskForeground = false;
        if (displayId <= 0) {
            if (dockHolder != null) dockHolder.setVisibility(View.GONE);
            if (tbHolder != null) tbHolder.setVisibility(View.GONE);
            if (capHolder != null) capHolder.setVisibility(View.GONE);
            if (probeHolder != null) probeHolder.setVisibility(View.GONE);
        }
        try {
            int id = foregroundDisplayId;
            if (id >= 0 && getWindowManager().getDefaultDisplay().getDisplayId() == id) foregroundDisplayId = -1;
        } catch (Throwable ignored) {}
        // HOME 键被系统在输入分发阶段吃掉，应用侧拦不住（详见 HomeGuard 注释）。
        // 桌面一失焦就后台确认一次该屏前台，确认是系统副屏桌面才把自己拉回来。
        Log.i(TAG, "onPause displayId=" + displayId);
        try {
            HomeGuard.onDesktopPaused(this, displayId, getClass().getName());
        } catch (Throwable t) {
            Log.w(TAG, "HomeGuard 调用失败: " + t.getMessage());
        }
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        if (displayId > 0) sInstancesByDisplay.remove(displayId, this);
        if (sInstance == this) sInstance = null;

        super.onDestroy();
        if (lockReceiver != null) {
            try {
                unregisterReceiver(lockReceiver);
            } catch (Throwable ignored) {}
            lockReceiver = null;
        }
        if (systemStatusReceiver != null) {
            try {
                unregisterReceiver(systemStatusReceiver);
            } catch (Throwable ignored) {}
            systemStatusReceiver = null;
        }
        if (networkCallback != null) {
            try {
                android.net.ConnectivityManager cm = (android.net.ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);
                if (cm != null) cm.unregisterNetworkCallback(networkCallback);
            } catch (Throwable ignored) {}
            networkCallback = null;
        }
        // 释放所有应用专属虚拟屏（DESTROY_CONTENT_ON_REMOVAL 会一并清掉其上任务）
        if (shell != null) {
            shell.releaseAll();
            shell = null;
        }
        // 任务栏/探针/标题栏/Dock 虚拟窗随虚拟屏销毁必须显式摘除
        try {
            if (dockWin != null) dockWin.dismiss();
        } catch (Throwable ignored) {}
        dockWeb = null;
        dockHolder = null;
        dockWin = null;
        dockReady = false;
        try {
            if (tbWin != null) tbWin.dismiss();
            if (probeWin != null) probeWin.dismiss();
            if (capWin != null) capWin.dismiss();
            if (capProbeWin != null) capProbeWin.dismiss();
        } catch (Throwable ignored) {}
        tbWeb = null;
        tbHolder = null;
        probeHolder = null;
        tbWin = null;
        probeWin = null;
        capWeb = null;
        capHolder = null;
        capProbeHolder = null;
        capWin = null;
        capProbeWin = null;
        capVisible = false;
        capProbeVisible = false;
        capAutoHidden = false;
        capReady = false;
    }

    private long lastBackPressTime = 0;

    @Override
    public void onBackPressed() {
        jsEvalTb("closeMenus();");
        jsEval("closeMenus();");
        if (web != null) {
            web.evaluateJavascript("(function(){ return (typeof window.handleDesktopBack === 'function') ? window.handleDesktopBack() : false; })()", value -> {
                if ("true".equalsIgnoreCase(String.valueOf(value).replace("\"", ""))) {
                    return;
                }
                if (shell != null && shell.hasFullscreenWin()) {
                    shell.exitFullscreenActive();
                    Toast.makeText(DexHomeActivity.this, "已退出全屏模式", Toast.LENGTH_SHORT).show();
                    return;
                }
                long now = System.currentTimeMillis();
                if (now - lastBackPressTime < 2000) {
                    finish();
                } else {
                    lastBackPressTime = now;
                    Toast.makeText(DexHomeActivity.this, "再按一次返回键退出系统桌面", Toast.LENGTH_SHORT).show();
                }
            });
            return;
        }
        long now = System.currentTimeMillis();
        if (now - lastBackPressTime < 2000) {
            finish();
        } else {
            lastBackPressTime = now;
            Toast.makeText(this, "再按一次返回键退出系统桌面", Toast.LENGTH_SHORT).show();
        }
    }
}
