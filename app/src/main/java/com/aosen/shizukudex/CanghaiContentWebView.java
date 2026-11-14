package com.aosen.shizukudex;

import android.content.Context;
import android.graphics.PixelFormat;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.Display;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import org.json.JSONObject;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 浏览器验证：单个原生内容 WebView，挂在桌面所在的屏上、盖在桌面窗口的内容区。
 *
 * <p>挂载只用 {@code TYPE_PRIVATE_PRESENTATION}（Presentation 类窗口，不是悬浮窗，
 * 不需要悬浮窗权限；与 CanghaiVirtualWindow 首选类型相同）。挂不上就报 UNSUPPORTED，
 * 绝不降级到悬浮窗——用户铁律：悬浮窗与无障碍两个权限不许开，机器做不到就报
 * 「本机不支持」。
 *
 * <p>验证版只有这一个 WebView，没有标签池、没有下载、没有文件选择器；只验证
 * 层级（内容窗盖在桌面之上、藏起后不挡点击）与矩形跟随（窗口拖动时内容窗跟上）。
 */
final class CanghaiContentWebView {

    private static final String TAG = "沧海内容窗";
    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    private static WebView sWeb;
    private static WindowManager sWm;
    private static WindowManager.LayoutParams sLp;
    private static boolean sAdded = false;
    private static String sTitle = "";
    private static String sUrl = "";
    private static int sProgress = 0;

    private CanghaiContentWebView() {
    }

    /** 桥调用在 JavaBridge 线程，WebView 操作必须在主线程：同步等结果，5 秒超时。 */
    private static <T> T runOnMain(java.util.concurrent.Callable<T> c) throws Exception {
        if (Looper.myLooper() == Looper.getMainLooper()) return c.call();
        AtomicReference<T> out = new AtomicReference<>();
        AtomicReference<Throwable> err = new AtomicReference<>();
        CountDownLatch latch = new CountDownLatch(1);
        MAIN.post(() -> {
            try {
                out.set(c.call());
            } catch (Throwable t) {
                err.set(t);
            }
            latch.countDown();
        });
        if (!latch.await(5, TimeUnit.SECONDS)) throw new CoreFs.Err("TIMEOUT", "主线程超时");
        if (err.get() != null) throw new Exception(err.get());
        return out.get();
    }

    static JSONObject show(Context ctx, Display display, int x, int y, int w, int h, String url) throws Exception {
        if (display == null) throw new CoreFs.Err("UNSUPPORTED", "本机不支持");
        if (w <= 0 || h <= 0) throw new CoreFs.Err("INVALID_ARGS", "矩形无效");
        return runOnMain(() -> {
            if (sWeb == null) {
                Context dc = ctx.getApplicationContext().createDisplayContext(display);
                sWm = (WindowManager) dc.getSystemService(Context.WINDOW_SERVICE);
                sWeb = new WebView(dc);
                WebSettings ws = sWeb.getSettings();
                ws.setJavaScriptEnabled(true);
                ws.setDomStorageEnabled(true);
                ws.setUseWideViewPort(true);
                ws.setLoadWithOverviewMode(true);
                ws.setBuiltInZoomControls(false);
                sWeb.setWebViewClient(new WebViewClient() {
                    @Override
                    public void onPageStarted(WebView view, String url, android.graphics.Bitmap favicon) {
                        sUrl = url == null ? "" : url;
                        sProgress = 0;
                    }

                    @Override
                    public void onPageFinished(WebView view, String url) {
                        sUrl = url == null ? sUrl : url;
                        sProgress = 100;
                    }
                });
                sWeb.setWebChromeClient(new WebChromeClient() {
                    @Override
                    public void onReceivedTitle(WebView view, String title) {
                        sTitle = title == null ? "" : title;
                    }

                    @Override
                    public void onProgressChanged(WebView view, int progress) {
                        sProgress = progress;
                    }
                });
                sLp = new WindowManager.LayoutParams(
                        w, h, x, y,
                        WindowManager.LayoutParams.TYPE_PRIVATE_PRESENTATION,
                        WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
                        PixelFormat.OPAQUE);
                sLp.gravity = Gravity.TOP | Gravity.START;
                try {
                    sWm.addView(sWeb, sLp);
                } catch (Throwable t) {
                    Log.w(TAG, "内容窗挂载失败: " + t);
                    try {
                        sWeb.destroy();
                    } catch (Throwable ignored) {
                    }
                    sWeb = null;
                    sWm = null;
                    sLp = null;
                    throw new CoreFs.Err("UNSUPPORTED", "本机不支持");
                }
                sAdded = true;
            } else {
                sLp.x = x;
                sLp.y = y;
                sLp.width = w;
                sLp.height = h;
                if (!sAdded) {
                    try {
                        sWm.addView(sWeb, sLp);
                    } catch (Throwable t) {
                        throw new CoreFs.Err("UNSUPPORTED", "本机不支持");
                    }
                    sAdded = true;
                    sWeb.onResume();
                } else {
                    sWm.updateViewLayout(sWeb, sLp);
                }
            }
            if (url != null && !url.isEmpty() && !url.equals(sUrl)) sWeb.loadUrl(url);
            return new JSONObject().put("shown", true);
        });
    }

    static JSONObject move(int x, int y, int w, int h) throws Exception {
        return runOnMain(() -> {
            if (sWeb == null || !sAdded) throw new CoreFs.Err("NO_WEBVIEW", "内容窗没开");
            sLp.x = x;
            sLp.y = y;
            sLp.width = w;
            sLp.height = h;
            sWm.updateViewLayout(sWeb, sLp);
            return new JSONObject().put("moved", true);
        });
    }

    static JSONObject hide() throws Exception {
        return runOnMain(() -> {
            if (sWeb != null && sAdded) {
                try {
                    sWeb.onPause();
                } catch (Throwable ignored) {
                }
                try {
                    sWm.removeView(sWeb);
                } catch (Throwable ignored) {
                }
                sAdded = false;
            }
            return new JSONObject().put("hidden", true);
        });
    }

    static JSONObject navigate(String url) throws Exception {
        return runOnMain(() -> {
            if (sWeb == null) throw new CoreFs.Err("NO_WEBVIEW", "内容窗没开");
            if (url != null && !url.isEmpty()) sWeb.loadUrl(url);
            return new JSONObject().put("navigated", true);
        });
    }

    static JSONObject back() throws Exception {
        return runOnMain(() -> {
            if (sWeb == null) throw new CoreFs.Err("NO_WEBVIEW", "内容窗没开");
            boolean ok = false;
            if (sWeb.canGoBack()) {
                sWeb.goBack();
                ok = true;
            }
            return new JSONObject().put("wentBack", ok);
        });
    }

    static JSONObject forward() throws Exception {
        return runOnMain(() -> {
            if (sWeb == null) throw new CoreFs.Err("NO_WEBVIEW", "内容窗没开");
            boolean ok = false;
            if (sWeb.canGoForward()) {
                sWeb.goForward();
                ok = true;
            }
            return new JSONObject().put("wentForward", ok);
        });
    }

    static JSONObject reload() throws Exception {
        return runOnMain(() -> {
            if (sWeb == null) throw new CoreFs.Err("NO_WEBVIEW", "内容窗没开");
            sWeb.reload();
            return new JSONObject().put("reloaded", true);
        });
    }

    static JSONObject stop() throws Exception {
        return runOnMain(() -> {
            if (sWeb == null) throw new CoreFs.Err("NO_WEBVIEW", "内容窗没开");
            sWeb.stopLoading();
            return new JSONObject().put("stopped", true);
        });
    }

    static JSONObject close() throws Exception {
        return runOnMain(() -> {
            if (sWeb != null) {
                if (sAdded) {
                    try {
                        sWm.removeView(sWeb);
                    } catch (Throwable ignored) {
                    }
                    sAdded = false;
                }
                try {
                    sWeb.destroy();
                } catch (Throwable ignored) {
                }
                sWeb = null;
                sWm = null;
                sLp = null;
                sTitle = "";
                sUrl = "";
                sProgress = 0;
            }
            return new JSONObject().put("closed", true);
        });
    }

    static JSONObject status() throws Exception {
        return runOnMain(() -> {
            JSONObject o = new JSONObject();
            o.put("showing", sAdded);
            o.put("title", sTitle);
            o.put("url", sUrl);
            o.put("progress", sProgress);
            o.put("canGoBack", sWeb != null && sWeb.canGoBack());
            o.put("canGoForward", sWeb != null && sWeb.canGoForward());
            return o;
        });
    }

    /** 宿主 WebView 被销毁时顺手收掉内容窗，免得它孤零零浮在屏上。 */
    static void onHostGone() {
        MAIN.post(() -> {
            try {
                if (sWeb != null) {
                    if (sAdded) {
                        try {
                            sWm.removeView(sWeb);
                        } catch (Throwable ignored) {
                        }
                        sAdded = false;
                    }
                    try {
                        sWeb.destroy();
                    } catch (Throwable ignored) {
                    }
                    sWeb = null;
                    sWm = null;
                    sLp = null;
                }
            } catch (Throwable ignored) {
            }
        });
    }
}
