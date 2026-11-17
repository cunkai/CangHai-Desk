package com.aosen.shizukudex;

import android.app.Presentation;
import android.content.Context;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.graphics.drawable.ColorDrawable;
import android.hardware.display.DisplayManager;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.Display;
import android.view.Gravity;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.webkit.WebView;
import android.webkit.WebViewClient;

/**
 * 指针层：整块虚拟屏最上方画一根鼠标的唯一图层。
 *
 * <p>为什么只用 Presentation 而不再用悬浮窗（TYPE_APPLICATION_OVERLAY）：
 * Android 12+ 的「阻断不可信触摸」机制专门盯着 OVERLAY 类窗口——只要一块 OVERLAY
 * 盖在外部应用之上（哪怕 FLAG_NOT_TOUCHABLE），系统照样把落在外应用窗口上的触摸丢掉。
 * 这正是之前「屏蔽悬浮层后点击就正常」的根因。
 *
 * <p>Presentation 的窗口类型是 TYPE_APPLICATION_ATTACHED_DIALOG，属于同一应用的普通窗口，
 * 根本不在「不可信触摸」的拦截名单里。它仍然浮在应用之上、被 SurfaceFlinger 合成进
 * 同一块虚拟屏的画面（Moonlight 照常把「应用 + UI」一起串流），但不再触发系统触摸拦截。
 * 所以这个类从悬浮窗改为绑定到 Moonlight 虚拟屏的 Presentation——画面合成效果不变，
 * 但根上消除了遮挡丢点击。
 *
 * <p>位置仍只有一个主人 —— {@link VirtualMouse}（真设备的绝对坐标）。这一层只负责把那个
 * 点画出来，不接收事件、不推算、不换算（FLAG_NOT_TOUCHABLE）。
 */
public final class AosenPointerOverlay {

    private static final String TAG = "沧海指针";

    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    private static Presentation sPresentation;
    private static WebView sView;
    private static int sDisplayId = -1;
    private static boolean sLogged = false;
    private static volatile boolean sPageReady = false;

    /* 位置合并：外设原始事件一秒上百次，每次 evaluateJavascript 都要过一遍主线程。
     * 只保留「最新那一个点」，一次派发就把中间所有点跳过 —— 看得见的是最新位置，
     * 也没必要把中间每一帧都画出来。 */
    private static final Object LOCK = new Object();
    private static float sPendingX = Float.NaN;
    private static float sPendingY = Float.NaN;
    private static boolean sDispatchScheduled = false;

    private static final String HTML =
            "<!DOCTYPE html><html><head><meta charset='utf-8'>"
            + "<meta name='viewport' content='width=device-width,initial-scale=1,user-scalable=no'>"
            + "<style>"
            + "html,body{margin:0;padding:0;width:100%;height:100%;background:transparent;"
            + "overflow:hidden;-webkit-user-select:none;}"
            + "#c{position:fixed;left:0;top:0;width:48px;height:48px;pointer-events:none;"
            + "will-change:transform;transform:translate3d(-200px,-200px,0);}"
            + "#c.down{filter:brightness(0.7);}"
            + "</style></head><body>"
            + "<div id='c'><svg width='48' height='48' viewBox='0 0 48 48'>"
            + "<rect x='2' y='2' width='44' height='44' rx='12' "
            + "fill='#1D9E75' stroke='#ffffff' stroke-width='2'/>"
            + "<text x='24' y='32' text-anchor='middle' font-family='sans-serif' "
            + "font-size='27' font-weight='bold' fill='#ffffff'>A</text>"
            + "<path d='M3 3 L3 13 L9 9 Z' fill='#ffffff'/>"
            + "</svg></div>"
            + "<script>"
            + "var el=document.getElementById('c'),dpr=window.devicePixelRatio||1;"
            + "window.__aosenMove=function(x,y){"
            + "el.style.transform='translate3d('+(x/dpr)+'px,'+(y/dpr)+'px,0)';};"
            + "window.__aosenDown=function(d){el.className=d?'down':'';};"
            + "window.__aosenReady=1;"
            + "</script></body></html>";

    private AosenPointerOverlay() {}

    /* 调试用「屏蔽这一层」：屏蔽期间连后续 show() 也不许重新挂回来。 */
    private static volatile boolean sUserBlocked = false;
    private static Context sLastBase;
    private static int sLastDisplayId = -1, sLastW = 0, sLastH = 0;

    public static boolean isUserBlocked() { return sUserBlocked; }

    public static void setUserBlocked(final boolean blocked) {
        sUserBlocked = blocked;
        MAIN.post(() -> {
            if (blocked) {
                hideLocked();
            } else if (sLastBase != null && sLastDisplayId > 0) {
                show(sLastBase, sLastDisplayId, sLastW, sLastH);
            }
        });
    }

    /** 沿 ContextWrapper 链找 Activity：Presentation.show 只要 Activity token，
     *  拿 ContextThemeWrapper 这类包过一层的 base 必抛 BadToken。链上没有 Activity
     *  就返回 null，调用方只记日志不挂载，绝不把异常抛到外层。 */
    private static android.app.Activity resolveActivityBase(Context ctx) {
        Context c = ctx;
        while (c != null) {
            if (c instanceof android.app.Activity) return (android.app.Activity) c;
            if (c instanceof android.content.ContextWrapper) {
                c = ((android.content.ContextWrapper) c).getBaseContext();
            } else {
                break;
            }
        }
        return null;
    }

    /** 在这块虚拟屏的最高层用 Presentation 挂载唯一那一层指针。重复调用只换屏，不会挂出第二层。 */
    public static void show(Context base, int displayId, int width, int height) {
        if (base == null || displayId <= 0) return;
        CanghaiErrorCollector.logExt("[指针层辨识] AosenPointerOverlay.show 被调用 base="
                + base.getClass().getSimpleName() + " display=" + displayId
                + "；本层图标 = 绿色圆角方块 + 白色大写 A");
        MAIN.post(() -> {
            if (sUserBlocked) return;
            try {
                if (sPresentation != null && sDisplayId == displayId) return;

                hideLocked();

                DisplayManager dm = (DisplayManager) base.getSystemService(Context.DISPLAY_SERVICE);
                Display display = dm == null ? null : dm.getDisplay(displayId);
                if (display == null) {
                    Log.w(TAG, "取不到 display " + displayId + "，指针层无法挂载");
                    return;
                }
                /* Presentation 的 token 必须来自 Activity：先沿 base 链解包，找不到
                 * Activity 就只记日志、不尝试挂载（否则必抛 BadToken）。 */
                android.app.Activity actBase = resolveActivityBase(base);
                if (actBase == null) {
                    Log.w(TAG, "指针层 base 链上没有 Activity（base=" + base.getClass().getName() + "），不挂载");
                    CanghaiErrorCollector.logExt("[指针层辨识] AosenPointerOverlay 未挂载：base 链上没有 Activity token");
                    return;
                }
                StringBuilder ds = new StringBuilder();
                try {
                    for (android.view.Display d : dm.getDisplays()) {
                        ds.append(d.getDisplayId()).append(':').append(d.getName()).append(' ');
                    }
                } catch (Throwable ignored) {}
                CanghaiErrorCollector.pointerAudit("E-绿色A层",
                        "base=" + base.getClass().getName()
                                + " isActivity=" + (base instanceof android.app.Activity)
                                + " actBase=" + actBase.getClass().getName()
                                + " 目标display=" + displayId
                                + " 取到display=true 全部display=[" + ds.toString().trim() + "]"
                                + " ｜Presentation.show 需要 Activity token，非 Activity base 必抛 BadToken",
                        true);

                // 关键：用这块屏自己的 Context 创建 WebView，确保它渲染到这块屏。
                Context dc = base.createDisplayContext(display);
                WebView wv = new WebView(dc);
                wv.setBackgroundColor(Color.TRANSPARENT);
                wv.setVerticalScrollBarEnabled(false);
                wv.setHorizontalScrollBarEnabled(false);
                wv.setOverScrollMode(View.OVER_SCROLL_NEVER);
                wv.getSettings().setJavaScriptEnabled(true);
                wv.setWebViewClient(new WebViewClient() {
                    @Override
                    public void onPageFinished(WebView view, String url) {
                        sPageReady = true;
                        /* 页面就绪先摆一个可见位置。
                         * HTML 里箭头初始是 translate(-200px,-200px)，也就是屏幕外 ——
                         * 设备那一刻要是还没绑上（position() 返回 null），屏幕上就一个
                         * 鼠标都没有，用户看到的是「完全没有指针」。先摆到正中，
                         * 后面有真实坐标再跟着走。 */
                        float[] p = VirtualMouse.position();
                        if (p != null) {
                            move(p[0], p[1]);
                        } else {
                            move(sLastW / 2f, sLastH / 2f);
                        }
                        CanghaiErrorCollector.logExt("[指针层辨识] AosenPointerOverlay HTML 就绪（绿色方块白 A 应可见）");
                    }
                });
                wv.loadDataWithBaseURL(null, HTML, "text/html", "utf-8", null);

                // 用 Presentation 把指针画到这块虚拟屏上：窗口类型 TYPE_APPLICATION_ATTACHED_DIALOG，
                // 同一应用的普通窗口，不进「不可信触摸」拦截名单，从根上消除遮挡丢点击。
                // base 必须是解包出来的 Activity（actBase），传包过的 Context 必抛 BadToken。
                Presentation pres = new Presentation(actBase, display) {};
                Window w = pres.getWindow();
                w.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
                w.requestFeature(Window.FEATURE_NO_TITLE);
                pres.setContentView(wv);
                WindowManager.LayoutParams lp = w.getAttributes();
                lp.type = WindowManager.LayoutParams.TYPE_APPLICATION_ATTACHED_DIALOG;
                lp.flags |= WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                        | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS;
                lp.width = Math.max(width, 1);
                lp.height = Math.max(height, 1);
                lp.gravity = Gravity.FILL;
                w.setAttributes(lp);

                sView = wv;
                sPresentation = pres;
                sDisplayId = displayId;
                sPageReady = false;
                sLastBase = base.getApplicationContext();
                sLastDisplayId = displayId;
                sLastW = width;
                sLastH = height;

                pres.show();

                CanghaiErrorCollector.logExt("[指针层辨识] AosenPointerOverlay 已在 display "
                        + displayId + " 挂载成功（Presentation，" + width + "×" + height
                        + "，图标=绿色方块白 A）");
            } catch (Throwable t) {
                Log.w(TAG, "指针层挂载失败: " + t);
                CanghaiErrorCollector.logExt("[指针层辨识] AosenPointerOverlay 挂载失败: " + t);
                CanghaiErrorCollector.pointerAudit("E-绿色A层",
                        "挂载失败 display=" + displayId + " 原因=" + t
                                + " ｜这一层本次没画出来，屏幕上看到的其它箭头不是它", true);
                hideLocked();
            }
        });
    }

    /** 外设大脑算好的绝对坐标（虚拟屏像素）—— 同一帧也会写进虚拟设备，两边是同一个数。 */
    public static void move(float x, float y) {
        if (sView == null) return;
        synchronized (LOCK) {
            sPendingX = x;
            sPendingY = y;
            if (sDispatchScheduled) return;
            sDispatchScheduled = true;
        }
        MAIN.post(AosenPointerOverlay::flush);
    }

    private static void flush() {
        float x, y;
        synchronized (LOCK) {
            sDispatchScheduled = false;
            x = sPendingX;
            y = sPendingY;
        }
        if (sView == null || !sPageReady || Float.isNaN(x) || Float.isNaN(y)) return;
        try {
            sView.evaluateJavascript("__aosenMove(" + x + "," + y + ")", null);
        } catch (Throwable ignored) {}
    }

    /** 左键按下时把图标压暗一点，给一个「按到了」的反馈。 */
    public static void setPressed(final boolean down) {
        if (sView == null) return;
        MAIN.post(() -> {
            if (sView == null || !sPageReady) return;
            try {
                sView.evaluateJavascript("__aosenDown(" + (down ? "true" : "false") + ")", null);
            } catch (Throwable ignored) {}
        });
    }

    public static boolean isShowing() {
        return sPresentation != null && sView != null;
    }

    public static void hide() {
        MAIN.post(AosenPointerOverlay::hideLocked);
    }

    private static void hideLocked() {
        if (sPresentation == null) return;
        try {
            sPresentation.dismiss();
        } catch (Throwable ignored) {}
        try {
            if (sView != null) sView.destroy();
        } catch (Throwable ignored) {}
        sView = null;
        sPresentation = null;
        sDisplayId = -1;
        sPageReady = false;
    }
}
