package com.aosen.shizukudex;

import android.app.Activity;
import android.app.ActivityManager;
import android.app.AlertDialog;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.ActivityInfo;
import android.content.res.Configuration;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.util.Base64;
import android.util.Log;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.view.WindowManager;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputConnection;
import android.view.inputmethod.InputMethodManager;
import android.widget.TextView;
import android.webkit.JavascriptInterface;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;

import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 沧海软件（HTML 应用）的独立窗口宿主：一个窗口 = 一个 Task，可以多开。
 *
 * 手机上点「打开」、外屏投屏、桌面快捷方式最终都走到这里，窗口表现完全一致：
 * <ul>
 *   <li><b>全屏沉浸</b>：状态栏 / 导航栏全部收起，<b>连屏幕开孔（挖孔/刘海）那一条也
 *       交给内容</b>（LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS），WebView 铺满整块屏，
 *       渲染分辨率就是所在设备的真实分辨率；</li>
 *   <li><b>旋转通知</b>：转屏后 WebView 会自动收到 resize，另外再补一个
 *       aosen:resize 事件（带像素尺寸），应用想监听就监听；</li>
 *   <li><b>返回键</b>：页面接管（{@code Aosen.claimBack(true)}，游戏内菜单）优先；
 *       其次页面自己的浏览历史（点开外链回来先退历史）；退无可退时第一下弹
 *       一条「再按一次返回键，回到沧海」，2 秒内第二下才关窗口回到沧海；</li>
 *   <li><b>外链</b>：http(s) 且不是应用自己的 canghai.local，一律交系统浏览器打开，
 *       不许劫持应用窗口；</li>
 *   <li><b>安全区域</b>：系统栏 + 挖孔/刘海 + 手势条的位置会注入给页面
 *       （{@code window.CanghaiSafeArea}、{@code --ch-safe-*} CSS 变量、
 *       {@code canghai:safearea} 事件），UI 避开、背景照样铺满；</li>
 *   <li><b>后台可见、可关</b>：按 Home 后仍出现在最近任务里（可划掉关闭），
 *       另有通知栏的「关闭」入口；退到后台自动静音，回来自动恢复；</li>
 *   <li><b>横竖屏锁定</b>：手机端「设置」里锁定后，这个窗口就不随设备转了
 *       （已开着的窗口通过 {@link #applyOrientationTo} 立即生效）。</li>
 * </ul>
 */
public class HtmlAppActivity extends Activity {

    private static final String TAG = "ShizukuDeX_HtmlApp";

    public static final String EXTRA_APP_ID = "app_id";
    public static final String EXTRA_APP_TITLE = "app_title";
    public static final String EXTRA_OPEN_FILE_URI = "open_file_uri";
    public static final String EXTRA_OPEN_FILE_NAME = "open_file_name";

    /** 手机端打包源码工程时留的运行时占位标记，两侧必须一致。 */

    /** 连按返回关窗口的窗口期：第一下提示、第二下才退出。2 秒足够看清提示。 */
    private static final long BACK_QUIT_GAP_MS = 2000L;

    private static final String NOTIF_CHANNEL = "canghai_html_app";
    private static final String ACTION_FOCUS = "com.cunkai.canghai.HTML_APP_FOCUS";
    private static final String ACTION_CLOSE = "com.cunkai.canghai.HTML_APP_CLOSE";
    private static final String EXTRA_TASK_ID = "html_task_id";

    /**
     * 页面加载前注入的音频守护脚本：把 AudioContext 实例和 <audio>/<video> 记下来，
     * 退到后台时统一静音 / 回来时恢复。第三方 HTML 游戏不会自己写生命周期，
     * 不这么做按 Home 之后 BGM 会在后台一直响（用户实测 cf 运输船）。
     * 只用 ES5 语法，Android 9 的 WebView 72 也能跑。
     */
    private static final String AUDIO_GUARD_JS =
            "var __chCtx=[],__chMedia=[];"
                    + "function __chWrap(k){var O=window[k];if(!O)return;function W(){"
                    + "var a=Array.prototype.slice.call(arguments);"
                    + "var B=Function.prototype.bind.apply(O,[null].concat(a));"
                    + "var c=new B();try{__chCtx.push(c);}catch(e){}return c;}"
                    + "W.prototype=O.prototype;"
                    + "try{Object.defineProperty(window,k,{value:W,writable:true,configurable:true});}"
                    + "catch(e){try{window[k]=W;}catch(e2){}}}"
                    + "__chWrap('AudioContext');__chWrap('webkitAudioContext');"
                    + "window.__chAudioPause=function(){"
                    + "__chMedia=[];try{var m=document.querySelectorAll('audio,video');"
                    + "for(var i=0;i<m.length;i++){try{if(!m[i].paused){m[i].pause();__chMedia.push(m[i]);}}catch(e){}}}catch(e){}"
                    + "try{for(var j=0;j<__chCtx.length;j++){var c=__chCtx[j];"
                    + "if(c.state==='running'){try{c.suspend();}catch(e){}}}}catch(e){}};"
                    + "window.__chAudioResume=function(){"
                    + "try{for(var j=0;j<__chCtx.length;j++){var c=__chCtx[j];"
                    + "if(c.state==='suspended'){try{c.resume();}catch(e){}}}}catch(e){}"
                    + "try{for(var k=0;k<__chMedia.length;k++){try{__chMedia[k].play();}catch(e){}}}catch(e){}"
                    + "__chMedia=[];};";

    /** appId → 活着的窗口，设置里的横竖屏锁定要立即作用于已开的窗口。 */
    private static final Map<String, List<WeakReference<HtmlAppActivity>>> sInstances =
            new ConcurrentHashMap<>();

    private WebView web;
    private String appId;
    private String appTitle;
    /** 内置应用（assets/canghai/apps/&lt;id&gt;/）：代码在 APK 里，数据仍在 Data/&lt;id&gt;/。 */
    private boolean builtin;
    private int notifId;
    private BroadcastReceiver notifReceiver;

    private ValueCallback<Uri[]> mUploadCallback;
    private static final int REQ_PICK_FILE = 8801;
    private static final int REQ_PICK_FOLDER = 8802;

    /** 返回键提示条：页面盖满整块屏，系统 Toast 在沉浸窗口里不一定看得见，自己画一条。 */
    private TextView backHint;
    private Runnable hintHideRunnable;

    /** 页面声明「返回键我自己管」（游戏内菜单等），原生就不再做连按退出。 */
    private volatile boolean backClaimedByPage = false;


    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Intent it = getIntent();
        appId = it == null ? null : it.getStringExtra(EXTRA_APP_ID);
        if (appId == null && it != null) appId = it.getStringExtra("id");
        String title = it == null ? null : it.getStringExtra(EXTRA_APP_TITLE);
        if (title == null && it != null) title = it.getStringExtra("title");
        if (appId == null || appId.isEmpty()) {
            Log.w(TAG, "缺少 app_id，直接关闭");
            finish();
            return;
        }
        /* 内置应用和「装入的 HTML 应用」走同一个独立窗口，只是一个从 assets 读、一个从 app 目录读。
           之前内置应用只能塞在主页的 iframe 里跑，窗口、返回键、任务栏表现都和独立窗口不一致。 */
        builtin = CanghaiStore.isBuiltinApp(this, appId);
        if (title == null || title.isEmpty()) {
            title = builtin ? CanghaiStore.builtinAppName(this, appId)
                            : CanghaiStore.htmlName(this, appId);
        }
        if (title == null || title.isEmpty()) title = CanghaiStore.htmlName(this, appId);
        appTitle = title;
        Log.i(TAG, "打开 HTML 应用 " + appId + (builtin ? "（内置）" : "") + " display=" + getDisplayId());

        applyImmersive();
        applyOrientation(CanghaiStore.htmlOrientation(this, appId));        registerInstance(this);
        /* 最近任务里必须叫它自己的名字，不然按 Home 之后只能看到「沧海」，
           用户找不到这个窗口，也划不掉 —— 之前 excludeFromRecents=true 更彻底看不见。 */
        applyTaskDescription(title);
        setupRunningNotification(title);

        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(0xFF0F1216);
        web = new WebView(this);
        WebSettings ws = web.getSettings();
        ws.setJavaScriptEnabled(true);
        ws.setAllowFileAccess(true);
        ws.setAllowFileAccessFromFileURLs(true);
        ws.setAllowUniversalAccessFromFileURLs(true);
        ws.setDomStorageEnabled(true);
        ws.setMediaPlaybackRequiresUserGesture(false);
        ws.setTextZoom(100);
        if (builtin) {
            /* 内置应用是我们自己的页面：局域网大厅要内嵌 http://127.0.0.1 的大厅页，
               file:// 页面加载 http 子框架会被当混合内容拦掉，这里放开。
               第三方装入的应用不开这个口子。 */
            ws.setMixedContentMode(WebSettings.MIXED_CONTENT_ALWAYS_ALLOW);
        }
        web.setBackgroundColor(0xFF0F1216);
        web.addJavascriptInterface(new Bridge(), "Aosen");
        web.addJavascriptInterface(new Bridge(), "Canghai");
        /* L0：身份由宿主绑定，fs 调用的 appId 一律被 Native 覆盖，前端无法读别人的目录 */
        CanghaiCore.attach(this, web, appId);
        web.setWebChromeClient(new WebChromeClient() {
            @Override
            public boolean onConsoleMessage(android.webkit.ConsoleMessage m) {
                Log.i(TAG, "[web] " + m.message() + " @" + m.lineNumber());
                return true;
            }

            @Override
            public boolean onShowFileChooser(WebView webView, ValueCallback<Uri[]> filePathCallback, FileChooserParams fileChooserParams) {
                return handleFileChooser(filePathCallback, fileChooserParams);
            }
        });
        web.setDownloadListener((url, userAgent, contentDisposition, mimetype, contentLength) -> {
            try {
                String filename = android.webkit.URLUtil.guessFileName(url, contentDisposition, mimetype);
                CanghaiCore.saveDownloadDirect(HtmlAppActivity.this, filename, url);
                runOnUiThread(() -> Toast("已保存至下载目录: " + filename));
            } catch (Throwable t) {
                Log.w(TAG, "下载失败: " + t.getMessage(), t);
                runOnUiThread(() -> Toast("保存失败: " + t.getMessage()));
            }
        });

        /* 旋转 / 尺寸变化通知：resize 系统本来就发，这里补一个带像素尺寸的 aosen:resize；
           顺便装上音频守护，按 Home 之后不会在后台继续放声音。 */
        final String boot = "<script>(function(){"
                + "function n(){try{var e=new Event('aosen:resize');"
                + "e.width=window.innerWidth;e.height=window.innerHeight;"
                + "e.dpr=window.devicePixelRatio;window.dispatchEvent(e);}catch(_){}}"
                + "window.addEventListener('resize',n);"
                + AUDIO_GUARD_JS
                + "})();</script>";

        web.setWebViewClient(new WebViewClient() {
            @Override
            public void onPageFinished(WebView view, String url) {
                super.onPageFinished(view, url);
                deliverIncomingFileIfNeeded();
                /* 页面重载后注入的安全区域就没了，拿当前 insets 重新注一遍。 */
                try {
                    WindowInsets ri = view.getRootWindowInsets();
                    if (ri != null) injectSafeArea(ri);
                } catch (Throwable ignored) {}
            }

            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                /* 外链不许劫持应用窗口：http(s) 且不是应用自己的 canghai.local，
                   一律交系统浏览器；应用自己的 file:// 与 canghai.local 原样放行。 */
                try {
                    android.net.Uri uri = request != null ? request.getUrl() : null;
                    if (uri != null) {
                        String scheme = uri.getScheme();
                        if (("http".equals(scheme) || "https".equals(scheme))
                                && !"canghai.local".equals(uri.getHost())) {
                            Intent intent = new Intent(Intent.ACTION_VIEW, uri);
                            intent.addCategory(Intent.CATEGORY_BROWSABLE);
                            HtmlAppActivity.this.startActivity(intent);
                            return true;
                        }
                    }
                } catch (Throwable t) {
                    Log.w(TAG, "外链打开失败: " + t.getMessage(), t);
                    Toast("打不开这个链接");
                    return true;
                }
                return false;
            }

            @Override
            public boolean onRenderProcessGone(WebView view, android.webkit.RenderProcessGoneDetail detail) {
                Log.w(TAG, "HtmlAppActivity onRenderProcessGone: didCrash=" + (detail != null && detail.didCrash()));
                finish();
                return true;
            }

            @Override
            public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) {
                if (request != null && request.getUrl() != null) {
                    android.net.Uri uri = request.getUrl();
                    if ("canghai.local".equals(uri.getHost())) {
                        String path = uri.getPath();
                        if (path == null || path.isEmpty() || path.equals("/")
                                || path.equals("/" + appId) || path.equals("/" + appId + "/")
                                || path.endsWith("/index.html")) {
                            InputStream in = CanghaiStore.openHtmlAppStreamForRun(HtmlAppActivity.this, appId, boot);
                            if (in != null) {
                                Map<String, String> headers = new HashMap<>();
                                headers.put("Access-Control-Allow-Origin", "*");
                                return new WebResourceResponse("text/html", "utf-8", 200, "OK", headers, in);
                            }
                        } else {
                            String name = uri.getLastPathSegment();
                            if (name != null) {
                                File assetFile = new File(CanghaiStore.appsDir(HtmlAppActivity.this), name);
                                if (assetFile.exists() && assetFile.isFile()) {
                                    try {
                                        String mime = android.webkit.MimeTypeMap.getSingleton().getMimeTypeFromExtension(
                                                android.webkit.MimeTypeMap.getFileExtensionFromUrl(name));
                                        if (mime == null) mime = "application/octet-stream";
                                        Map<String, String> headers = new HashMap<>();
                                        headers.put("Access-Control-Allow-Origin", "*");
                                        return new WebResourceResponse(mime, "utf-8", 200, "OK", headers, new FileInputStream(assetFile));
                                    } catch (Throwable ignored) {}
                                }
                            }
                        }
                    }
                }
                return super.shouldInterceptRequest(view, request);
            }
        });

        if (!builtin && CanghaiStore.getHtmlAppFile(this, appId) == null) {
            Log.w(TAG, "应用已被移除，直接关闭 " + appId);
            Toast("这个应用已经被移除了");
            finish();
            return;
        }

        /* 采用流式 URL 加载替代 loadDataWithBaseURL，避免大型 3D 应用（40MB+）在 Java 层触发 Base64 内存分配导致 OOM 崩溃。
           内置应用直接读 APK 内 assets，相对路径资源照常解析。 */
        web.loadUrl(builtin ? CanghaiStore.builtinEntryUrl(appId)
                            : "https://canghai.local/" + appId + "/index.html");
        root.addView(web, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        setupBackHint(root);
        setupSafeAreaReporting(web);
        setContentView(root);
        setTitle(title);

    }

    // ==========================================================
    // 自绘键盘（替代系统输入法）
    // ==========================================================

    /**
     * 找插入启动脚本的位置：<head> 开标签之后；没有 head 就放 <html> 之后；
     * 连 html 都没有（纯片段）就放最前面。
     *
     * 注意：Matcher.start()/end() 必须先 find() 成功，否则会抛
     * IllegalStateException: No successful match so far —— 之前就是这么崩的
     * （adb logcat: HtmlAppActivity.indexIgnoreCase:129）。
     */
    private static int headInsertPos(String html) {
        if (html == null) return 0;
        java.util.regex.Matcher head = java.util.regex.Pattern
                .compile("<head[^>]*>", java.util.regex.Pattern.CASE_INSENSITIVE)
                .matcher(html);
        if (head.find()) return head.end();
        java.util.regex.Matcher doc = java.util.regex.Pattern
                .compile("<html[^>]*>", java.util.regex.Pattern.CASE_INSENSITIVE)
                .matcher(html);
        if (doc.find()) return doc.end();
        return 0;
    }

    private void Toast(String msg) {
        try {
            android.widget.Toast.makeText(this, msg, android.widget.Toast.LENGTH_SHORT).show();
        } catch (Throwable ignored) {
        }
    }

    // ==========================================================
    // 后台可见 / 可关 / 静音：按 Home 之后窗口还能找到、还能关掉
    // ==========================================================

    /** 最近任务卡片显示应用自己的名字和用户导入的图标。 */
    private void applyTaskDescription(String label) {
        try {
            Bitmap icon = decodeIcon(CanghaiStore.htmlIconPng(this, appId));
            setTaskDescription(new ActivityManager.TaskDescription(label, icon));
        } catch (Throwable t) {
            Log.w(TAG, "applyTaskDescription: " + t);
        }
    }

    private static Bitmap decodeIcon(String dataUrl) {
        try {
            if (dataUrl == null) return null;
            int comma = dataUrl.indexOf(',');
            byte[] raw = Base64.decode(comma >= 0 ? dataUrl.substring(comma + 1) : dataUrl,
                    Base64.DEFAULT);
            return BitmapFactory.decodeByteArray(raw, 0, raw.length);
        } catch (Throwable t) {
            return null;
        }
    }

    /**
     * 窗口活着就挂一条通知：点通知回到窗口，点「关闭」直接结束它。
     * 多开时用 taskId 区分，避免一个通知把全部窗口都关掉。
     */
    private void setupRunningNotification(String label) {
        try {
            final int taskId = getTaskId();
            notifId = 0x5100 + (taskId & 0x0fff);
            IntentFilter filter = new IntentFilter();
            filter.addAction(ACTION_FOCUS);
            filter.addAction(ACTION_CLOSE);
            notifReceiver = new BroadcastReceiver() {
                @Override
                public void onReceive(Context c, Intent i) {
                    if (i == null || i.getIntExtra(EXTRA_TASK_ID, -1) != taskId) return;
                    String act = i.getAction();
                    if (ACTION_CLOSE.equals(act)) {
                        Log.i(TAG, "通知栏关闭窗口 " + appId);
                        finish();
                    } else if (ACTION_FOCUS.equals(act)) {
                        bringToFront(taskId);
                    }
                }
            };
            if (Build.VERSION.SDK_INT >= 33) {
                registerReceiver(notifReceiver, filter, Context.RECEIVER_NOT_EXPORTED);
            } else {
                registerReceiver(notifReceiver, filter);
            }
            showRunningNotification(label, taskId, notifId);
        } catch (Throwable t) {
            Log.w(TAG, "setupRunningNotification: " + t);
        }
    }

    private void bringToFront(int taskId) {
        try {
            ActivityManager am = (ActivityManager) getSystemService(ACTIVITY_SERVICE);
            if (am != null) am.moveTaskToFront(taskId, 0);
        } catch (Throwable t) {
            try {
                Intent bring = new Intent(this, HtmlAppActivity.class)
                        .putExtra(EXTRA_APP_ID, appId)
                        .putExtra(EXTRA_APP_TITLE, appTitle)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_REORDER_TO_FRONT);
                startActivity(bring);
            } catch (Throwable ignored) { }
        }
    }

    private void showRunningNotification(String label, int taskId, int id) {
        try {
            NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
            if (nm == null) return;
            if (Build.VERSION.SDK_INT >= 26) {
                NotificationChannel ch = new NotificationChannel(NOTIF_CHANNEL, "沧海软件",
                        NotificationManager.IMPORTANCE_LOW);
                ch.setDescription("正在运行的沧海软件，可在这里关闭");
                nm.createNotificationChannel(ch);
            }
            int flags = PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE;
            PendingIntent focus = PendingIntent.getBroadcast(this, id,
                    new Intent(ACTION_FOCUS).setPackage(getPackageName())
                            .putExtra(EXTRA_TASK_ID, taskId), flags);
            PendingIntent close = PendingIntent.getBroadcast(this, id + 0x10000,
                    new Intent(ACTION_CLOSE).setPackage(getPackageName())
                            .putExtra(EXTRA_TASK_ID, taskId), flags);
            Notification.Builder b = Build.VERSION.SDK_INT >= 26
                    ? new Notification.Builder(this, NOTIF_CHANNEL)
                    : new Notification.Builder(this);
            b.setSmallIcon(R.mipmap.ic_launcher)
                    .setContentTitle(label)
                    .setContentText("正在运行 · 点此回到应用")
                    .setContentIntent(focus)
                    .setOngoing(true)
                    .setCategory(Notification.CATEGORY_SERVICE)
                    .addAction(android.R.drawable.ic_menu_close_clear_cancel, "关闭应用", close);
            Notification n = b.build();
            n.flags |= Notification.FLAG_NO_CLEAR;
            nm.notify(id, n);
        } catch (Throwable t) {
            Log.w(TAG, "showRunningNotification: " + t);
        }
    }

    /** 退到后台：先让页面里的 <audio>/<video>/AudioContext 全部静音，再暂停 WebView。 */
    private void pausePage() {
        WebView w = web;
        if (w == null) return;
        try {
            w.evaluateJavascript("try{window.__chAudioPause&&window.__chAudioPause()}catch(_){}", null);
            w.onPause();
        } catch (Throwable t) {
            Log.w(TAG, "pausePage: " + t);
        }
    }

    /** 回到前台：恢复 WebView，并让之前被暂停的媒体继续播。 */
    private void resumePage() {
        WebView w = web;
        if (w == null) return;
        try {
            w.onResume();
            w.evaluateJavascript("try{window.__chAudioResume&&window.__chAudioResume()}catch(_){}", null);
        } catch (Throwable t) {
            Log.w(TAG, "resumePage: " + t);
        }
    }

    private boolean fileDelivered = false;

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        fileDelivered = false;
        deliverIncomingFileIfNeeded();
    }

    private void deliverIncomingFileIfNeeded() {
        Intent it = getIntent();
        if (it == null) return;
        String uriStr = it.getStringExtra(EXTRA_OPEN_FILE_URI);
        if (uriStr == null && it.getData() != null) {
            uriStr = it.getData().toString();
        }
        if (uriStr == null || uriStr.isEmpty()) return;
        if (fileDelivered) return;
        fileDelivered = true;

        String fName = it.getStringExtra(EXTRA_OPEN_FILE_NAME);
        if (fName == null || fName.isEmpty()) {
            try {
                Uri u = Uri.parse(uriStr);
                if ("content".equals(u.getScheme())) {
                    try (android.database.Cursor c = getContentResolver().query(
                            u, new String[]{android.provider.OpenableColumns.DISPLAY_NAME}, null, null, null)) {
                        if (c != null && c.moveToFirst()) fName = c.getString(0);
                    }
                }
                if (fName == null || fName.isEmpty()) fName = u.getLastPathSegment();
            } catch (Throwable ignored) {}
        }
        if (fName == null || fName.isEmpty()) fName = "file";
        final String finalName = fName;
        final String finalUri = uriStr;

        new Thread(() -> {
            try {
                Uri u = Uri.parse(finalUri);
                byte[] data;
                try (InputStream in = getContentResolver().openInputStream(u);
                     java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream()) {
                    if (in == null) return;
                    byte[] buf = new byte[32768];
                    int n;
                    while ((n = in.read(buf)) > 0) bos.write(buf, 0, n);
                    data = bos.toByteArray();
                }
                final String b64 = Base64.encodeToString(data, Base64.NO_WRAP);
                String iface = "";
                if (builtin) {
                    org.json.JSONObject meta = CanghaiStore.builtinAppMeta(this, appId);
                    if (meta != null) iface = meta.optString("iface", "");
                }
                final String finalIface = iface;
                runOnUiThread(() -> {
                    if (web == null) return;
                    String script = "(function(){\n"
                            + "  try {\n"
                            + "    var bin = atob('" + b64 + "');\n"
                            + "    var len = bin.length;\n"
                            + "    var u8 = new Uint8Array(len);\n"
                            + "    for (var i = 0; i < len; i++) u8[i] = bin.charCodeAt(i);\n"
                            + "    var file = new File([u8], " + org.json.JSONObject.quote(finalName) + ");\n"
                            + "    var iface = " + org.json.JSONObject.quote(finalIface) + ";\n"
                            + "    var api = iface ? window[iface] : null;\n"
                            + "    if (!api) {\n"
                            + "      var cand = ['CanghaiMarkdown', 'CanghaiDocument', 'CanghaiPhotoEditor', 'CanghaiOffice', 'CanghaiMusic'];\n"
                            + "      for (var j = 0; j < cand.length; j++) {\n"
                            + "        if (window[cand[j]] && typeof window[cand[j]].openFile === 'function') { api = window[cand[j]]; break; }\n"
                            + "      }\n"
                            + "    }\n"
                            + "    if (api && typeof api.openFile === 'function') {\n"
                            + "      api.openFile(file);\n"
                            + "    }\n"
                            + "  } catch(e) { console.warn('deliver file error', e); }\n"
                            + "})();";
                    web.evaluateJavascript(script, null);
                });
            } catch (Throwable t) {
                Log.w(TAG, "deliverIncomingFileIfNeeded error", t);
            }
        }, "ch-deliver-file").start();
    }

    @Override
    protected void onPause() {
        super.onPause();
        pausePage();
    }

    @Override
    protected void onResume() {
        super.onResume();
        resumePage();
    }

    @Override
    protected void onStop() {
        super.onStop();
        /* onPause 的 JS 可能还没落地（页面在后台被系统冻结），这里补一刀静音。 */
        WebView w = web;
        if (w != null) {
            try {
                w.evaluateJavascript("try{window.__chAudioPause&&window.__chAudioPause()}catch(_){}", null);
            } catch (Throwable ignored) { }
        }
    }

    // ==========================================================
    // 沉浸式：全屏收起系统栏， regained focus 时重新压一遍
    // ==========================================================

    private void applyImmersive() {
        try {
            Window w = getWindow();
            View d = w.getDecorView();
            /* 顶部那条黑边不是状态栏，是「开孔（挖孔/刘海）安全区」：
               layoutInDisplayCutoutMode 默认是 DEFAULT，窗口不进挖孔区，系统就在
               屏幕顶端留一整条黑带，页面怎么拉满都伸不进去。ALWAYS 之后内容才真正
               铺到物理屏最上面，摄像头区域由页面自己决定画什么。 */
            WindowManager.LayoutParams lp = w.getAttributes();
            if (Build.VERSION.SDK_INT >= 30) {
                lp.layoutInDisplayCutoutMode =
                        WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS;
            } else if (Build.VERSION.SDK_INT >= 28) {
                lp.layoutInDisplayCutoutMode =
                        WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES;
            }
            w.setAttributes(lp);

            if (Build.VERSION.SDK_INT >= 30) {
                /* API 30+ 用 insets 控制器：decorFitsSystemWindows=false 让内容区
                   直接吃满整块屏（含状态栏/导航栏/挖孔），再把两条系统栏收起来。 */
                w.setDecorFitsSystemWindows(false);
                WindowInsetsController c = w.getInsetsController();
                if (c != null) {
                    c.hide(WindowInsets.Type.statusBars() | WindowInsets.Type.navigationBars());
                    c.setSystemBarsBehavior(
                            WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
                }
            }
            d.setSystemUiVisibility(View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                    | View.SYSTEM_UI_FLAG_FULLSCREEN
                    | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                    | View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                    | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                    | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION);
            w.setStatusBarColor(0x00000000);
            w.setNavigationBarColor(0x00000000);
            if (Build.VERSION.SDK_INT >= 29) {
                w.setStatusBarContrastEnforced(false);
                w.setNavigationBarContrastEnforced(false);
            }
        } catch (Throwable t) {
            Log.w(TAG, "applyImmersive: " + t);
        }
    }

    // ---------------- 安全区域：告诉页面哪里不能放 UI ----------------
    /* 窗口是沉浸式 + ALWAYS 进挖孔区，内容铺满整块屏。页面自己决定 UI 避开哪、
       背景照样铺满——但页面得先知道挖孔/系统栏/手势条在哪。这里把 insets 换算
       成 CSS 像素（dp，与 window.innerWidth 同一单位）注给页面，三样东西：
       window.CanghaiSafeArea 对象、:root 上的 --ch-safe-top/right/bottom/left
       CSS 变量、canghai:safearea 事件（detail 就是同一个对象）。
       约定：UI 容器用变量留边距，背景/画布不要用变量、继续铺满。 */

    private void setupSafeAreaReporting(WebView w) {
        if (w == null) return;
        try {
            w.setOnApplyWindowInsetsListener((v, insets) -> {
                injectSafeArea(insets);
                return insets;
            });
            w.requestApplyInsets();
        } catch (Throwable t) {
            Log.w(TAG, "setupSafeAreaReporting: " + t);
        }
    }

    private void injectSafeArea(WindowInsets insets) {
        WebView w = web;
        if (w == null || insets == null) return;
        try {
            float density = getResources().getDisplayMetrics().density;
            if (density <= 0) density = 1f;
            int sbTop, sbRight, sbBottom, sbLeft;
            if (Build.VERSION.SDK_INT >= 30) {
                android.graphics.Insets sb = insets.getInsets(WindowInsets.Type.systemBars());
                sbTop = sb.top;
                sbRight = sb.right;
                sbBottom = sb.bottom;
                sbLeft = sb.left;
                android.graphics.Insets ge = insets.getInsets(WindowInsets.Type.mandatorySystemGestures());
                sbBottom = Math.max(sbBottom, ge.bottom);
            } else {
                sbTop = insets.getSystemWindowInsetTop();
                sbRight = insets.getSystemWindowInsetRight();
                sbBottom = insets.getSystemWindowInsetBottom();
                sbLeft = insets.getSystemWindowInsetLeft();
            }
            int top = sbTop, right = sbRight, bottom = sbBottom, left = sbLeft;
            org.json.JSONArray cutouts = new org.json.JSONArray();
            if (Build.VERSION.SDK_INT >= 28) {
                android.view.DisplayCutout cutout = insets.getDisplayCutout();
                if (cutout != null) {
                    int sw = w.getWidth(), sh = w.getHeight();
                    for (android.graphics.Rect r : cutout.getBoundingRects()) {
                        org.json.JSONObject o = new org.json.JSONObject();
                        o.put("left", Math.round(r.left / density));
                        o.put("top", Math.round(r.top / density));
                        o.put("right", Math.round(r.right / density));
                        o.put("bottom", Math.round(r.bottom / density));
                        cutouts.put(o);
                        if (r.top <= 0) top = Math.max(top, r.bottom);
                        if (r.left <= 0) left = Math.max(left, r.right);
                        if (sw > 0 && r.right >= sw) right = Math.max(right, sw - r.left);
                        if (sh > 0 && r.bottom >= sh) bottom = Math.max(bottom, sh - r.top);
                    }
                }
            }
            final String js = "try{"
                    + "var sa={top:" + Math.round(top / density)
                    + ",right:" + Math.round(right / density)
                    + ",bottom:" + Math.round(bottom / density)
                    + ",left:" + Math.round(left / density)
                    + ",cutouts:" + cutouts.toString()
                    + ",density:" + density + "};"
                    + "window.CanghaiSafeArea=sa;"
                    + "var st=document.documentElement.style;"
                    + "st.setProperty('--ch-safe-top',sa.top+'px');"
                    + "st.setProperty('--ch-safe-right',sa.right+'px');"
                    + "st.setProperty('--ch-safe-bottom',sa.bottom+'px');"
                    + "st.setProperty('--ch-safe-left',sa.left+'px');"
                    + "window.dispatchEvent(new CustomEvent('canghai:safearea',{detail:sa}));"
                    + "}catch(_){}";
            w.post(() -> {
                try {
                    w.evaluateJavascript(js, null);
                } catch (Throwable ignored) {
                }
            });
        } catch (Throwable t) {
            Log.w(TAG, "injectSafeArea: " + t);
        }
    }

    // ---------------- 返回键提示条 ----------------

    private int dp(float v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }

    private void setupBackHint(FrameLayout root) {
        try {
            TextView tv = new TextView(this);
            tv.setText("再按一次返回键，回到沧海");
            tv.setTextColor(0xFFFFFFFF);
            tv.setTextSize(13f);
            tv.setPadding(dp(16), dp(9), dp(16), dp(9));
            android.graphics.drawable.GradientDrawable bg =
                    new android.graphics.drawable.GradientDrawable();
            bg.setColor(0xD9000000);
            bg.setCornerRadius(dp(20));
            tv.setBackground(bg);
            tv.setAlpha(0f);
            tv.setVisibility(View.GONE);
            tv.setClickable(false);
            tv.setFocusable(false);
            FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.gravity = Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL;
            lp.bottomMargin = dp(64);
            root.addView(tv, lp);
            backHint = tv;
        } catch (Throwable t) {
            Log.w(TAG, "setupBackHint: " + t);
        }
    }

    private void showBackHint() {
        final TextView tv = backHint;
        if (tv == null) {
            Toast("再按一次返回键，回到沧海");
            return;
        }
        if (hintHideRunnable != null) tv.removeCallbacks(hintHideRunnable);
        tv.animate().cancel();
        tv.setVisibility(View.VISIBLE);
        tv.setAlpha(0f);
        tv.animate().alpha(1f).setDuration(120).start();
        hintHideRunnable = () -> {
            tv.animate().alpha(0f).setDuration(200)
                    .withEndAction(() -> tv.setVisibility(View.GONE)).start();
            hintHideRunnable = null;
        };
        tv.postDelayed(hintHideRunnable, 1400L);
    }

    private void hideBackHintNow() {
        TextView tv = backHint;
        if (tv == null) return;
        if (hintHideRunnable != null) {
            tv.removeCallbacks(hintHideRunnable);
            hintHideRunnable = null;
        }
        tv.animate().cancel();
        tv.setAlpha(0f);
        tv.setVisibility(View.GONE);
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) applyImmersive();
    }

    // ==========================================================
    // 旋转：配置变化不打断页面（manifest 已声明），补发一次尺寸事件
    // ==========================================================

    @Override
    public void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        Log.i(TAG, "配置变化 ori=" + newConfig.orientation + " app=" + appId);
        notifyResize();
    }

    private void notifyResize() {
        WebView w = web;
        if (w == null) return;
        w.post(() -> {
            try {
                w.evaluateJavascript(
                        "try{var e=new Event('aosen:resize');e.width=window.innerWidth;"
                                + "e.height=window.innerHeight;e.dpr=window.devicePixelRatio;"
                                + "window.dispatchEvent(e);}catch(_){}", null);
            } catch (Throwable ignored) {
            }
        });
    }

    // ==========================================================
    // 横竖屏锁定
    // ==========================================================

    private void applyOrientation(String mode) {
        try {
            if ("portrait".equals(mode)) {
                setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_PORTRAIT);
            } else if ("landscape".equals(mode)) {
                setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE);
            } else {
                setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED);
            }
        } catch (Throwable t) {
            Log.w(TAG, "applyOrientation: " + t);
        }
    }

    /** 设置里改了锁定方向：只作用于某个应用的已开窗口，别的应用不受影响。 */
    public static void applyOrientationTo(Context c, String appId, String mode) {
        List<WeakReference<HtmlAppActivity>> list = sInstances.get(appId);
        if (list == null) return;
        List<WeakReference<HtmlAppActivity>> dead = null;
        for (WeakReference<HtmlAppActivity> ref : list) {
            HtmlAppActivity act = ref.get();
            if (act == null || act.isFinishing()) {
                if (dead == null) dead = new ArrayList<>();
                dead.add(ref);
                continue;
            }
            act.runOnUiThread(() -> act.applyOrientation(mode));
        }
        if (dead != null) list.removeAll(dead);
    }

    private static void registerInstance(HtmlAppActivity act) {
        if (act == null || act.appId == null) return;
        List<WeakReference<HtmlAppActivity>> list =
                sInstances.computeIfAbsent(act.appId, k -> new ArrayList<>());
        list.add(new WeakReference<>(act));
    }

    private static void unregisterInstance(HtmlAppActivity act) {
        if (act == null || act.appId == null) return;
        List<WeakReference<HtmlAppActivity>> list = sInstances.get(act.appId);
        if (list == null) return;
        list.removeIf(ref -> ref.get() == act || ref.get() == null);
    }

    private int getDisplayId() {
        try {
            return getDisplay() == null ? -1 : getDisplay().getDisplayId();
        } catch (Throwable t) {
            return -1;
        }
    }

    // ==========================================================
    // 返回键：先传给应用；没有弹层要吃时，第一下提示、1 秒内第二下关窗口
    // ==========================================================

    private long lastBackAt = 0L;

    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        /* 手柄 B：收起这个应用窗口（相当于系统窗口的关闭），页面保留，下次点开接着玩 */
        if (event.getAction() == KeyEvent.ACTION_DOWN
                && event.getKeyCode() == KeyEvent.KEYCODE_BUTTON_B) {
            dismissKeepingPage("手柄 B");
            return true;
        }
            if (event.getKeyCode() == KeyEvent.KEYCODE_BACK) {
                if (event.getAction() == KeyEvent.ACTION_UP) {
                if (web != null) web.dispatchKeyEvent(event);
                return true;
            }
            /* 长按不叠加：按住不放只算一次，否则一路弹提示 */
            if (event.getRepeatCount() > 0) return true;
            if (web != null) web.dispatchKeyEvent(event);
            /* 以前这里靠 evaluateJavascript 回调判断「页面吃没吃」，回调是异步的，
               页面一忙就永远等不到，表现就是「按了没反应 / 提示不来」。改成同步判据：
               页面加载后自己调 Aosen.claimBack(true) 声明接管返回键，原生立刻就知道。 */
            if (backClaimedByPage) {
                lastBackAt = 0L;
                return true;
            }
            /* 页面自己有浏览历史时先退历史（点开的页面按返回回到应用自己的页），
               退无可退才走提示退出；退历史后清掉上一次提示的计时，免得回
               到应用页再按一下就直接退出。 */
            if (web != null && web.canGoBack()) {
                lastBackAt = 0L;
                web.goBack();
                return true;
            }
            handleBackToExit();
            return true;
        }
        return super.dispatchKeyEvent(event);
    }

    /** 第一下只提示，2 秒内第二下才真的收起窗口回到沧海。 */
    private void handleBackToExit() {
        long now = android.os.SystemClock.uptimeMillis();
        if (lastBackAt > 0 && now - lastBackAt <= BACK_QUIT_GAP_MS) {
            lastBackAt = 0L;
            hideBackHintNow();
            Log.i(TAG, "连按两次返回，收起 " + appId);
            dismissKeepingPage("返回键");
            return;
        }
        lastBackAt = now;
        showBackHint();
        Log.i(TAG, "返回键：已提示，再按一次收起 " + appId);
    }

    /**
     * 收起窗口但<b>不销毁页面</b>：把沧海主页叫回前台，自己退到后台。
     *
     * 两个老问题都出在这里：
     * 1. 以前直接 finish()，壳是独立 Task，任务一没系统就回到桌面 —— 主页明明还在后台却醒不过来；
     * 2. finish() 会销毁 WebView，再次打开只能重新加载，游戏从头开始（按 Home 反而不会）。
     * 现在改成「退后台 + 主动唤醒主页」：页面活着，下次点开用 {@link #focusExistingWindow} 直接提回来。
     * 真正销毁只有三条路：通知栏「关闭应用」、最近任务划掉、系统回收内存。
     */
    private void dismissKeepingPage(String why) {
        Log.i(TAG, "收起窗口（保留页面） " + appId + " ← " + why);
        try {
            Intent home = new Intent(this, MainActivity.class);
            /* singleTask 的主界面：REORDER_TO_FRONT 把它所在任务整体提到前台，
               主页在后台放着、被别的窗口压着都能叫醒。 */
            home.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                    | Intent.FLAG_ACTIVITY_REORDER_TO_FRONT
                    | Intent.FLAG_ACTIVITY_SINGLE_TOP);
            startActivity(home);
        } catch (Throwable t) {
            Log.w(TAG, "唤醒主页失败: " + t);
        }
        try {
            moveTaskToBack(true);
        } catch (Throwable ignored) {
        }
    }

    /**
     * 这个应用已经有开着的窗口就把它提到前台（页面状态原样保留），返回 true 表示不用再新建。
     * 只在「窗口还活着」时成立；被系统回收或被划掉之后自然返回 false，走新建。
     */
    public static boolean focusExistingWindow(Context c, String appId) {
        if (appId == null || appId.isEmpty()) return false;
        List<WeakReference<HtmlAppActivity>> list = sInstances.get(appId);
        if (list == null || list.isEmpty()) return false;
        List<WeakReference<HtmlAppActivity>> dead = null;
        boolean focused = false;
        for (WeakReference<HtmlAppActivity> ref : list) {
            HtmlAppActivity act = ref.get();
            if (act == null || act.isFinishing() || act.isDestroyed()) {
                if (dead == null) dead = new ArrayList<>();
                dead.add(ref);
                continue;
            }
            /* 已经开在外接屏上的窗口别抢回手机屏：那会把它从大屏搬走。
               这种时候老老实实给手机再开一个窗口。 */
            if (act.getDisplayId() > 0) continue;
            try {
                ActivityManager am = (ActivityManager) act.getSystemService(ACTIVITY_SERVICE);
                if (am != null) am.moveTaskToFront(act.getTaskId(), 0);
                focused = true;
            } catch (Throwable t) {
                Log.w(TAG, "focusExistingWindow 失败: " + t);
            }
            break;
        }
        if (dead != null) list.removeAll(dead);
        return focused;
    }

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

    private boolean handleFileChooser(ValueCallback<Uri[]> callback, WebChromeClient.FileChooserParams params) {
        if (mUploadCallback != null) {
            try { mUploadCallback.onReceiveValue(null); } catch (Throwable ignored) {}
            mUploadCallback = null;
        }
        mUploadCallback = callback;

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
                        if (mUploadCallback != null) {
                            try { mUploadCallback.onReceiveValue(null); } catch (Throwable ignored) {}
                            mUploadCallback = null;
                        }
                    })
                    .show();
            return true;
        } catch (Throwable t) {
            launchFilePicker(params);
            return true;
        }
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
                intent.putExtra("android.provider.extra.INITIAL_URI", Uri.parse(
                        "content://com.android.externalstorage.documents/document/primary%3ADownload"));
            } catch (Throwable ignored) {}
            startActivityForResult(Intent.createChooser(intent, "选择文件"), REQ_PICK_FILE);
        } catch (Throwable t) {
            Log.w(TAG, "打开文件选择器失败: " + t.getMessage());
            if (mUploadCallback != null) {
                try { mUploadCallback.onReceiveValue(null); } catch (Throwable ignored) {}
                mUploadCallback = null;
            }
        }
    }

    private void launchFolderPicker() {
        try {
            Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION
                    | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION
                    | Intent.FLAG_GRANT_PREFIX_URI_PERMISSION);
            startActivityForResult(intent, REQ_PICK_FOLDER);
        } catch (Throwable t) {
            Log.w(TAG, "打开文件夹选择器失败: " + t.getMessage());
            if (mUploadCallback != null) {
                try { mUploadCallback.onReceiveValue(null); } catch (Throwable ignored) {}
                mUploadCallback = null;
            }
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQ_PICK_FILE) {
            Uri[] picked = null;
            if (resultCode == RESULT_OK && data != null) {
                if (data.getClipData() != null) {
                    int count = data.getClipData().getItemCount();
                    picked = new Uri[count];
                    for (int i = 0; i < count; i++) {
                        picked[i] = data.getClipData().getItemAt(i).getUri();
                    }
                } else if (data.getData() != null) {
                    picked = new Uri[]{ data.getData() };
                }
            }
            if (mUploadCallback != null) {
                try { mUploadCallback.onReceiveValue(picked); } catch (Throwable ignored) {}
                mUploadCallback = null;
            }
            return;
        }
        if (requestCode == REQ_PICK_FOLDER) {
            Uri[] picked = null;
            if (resultCode == RESULT_OK && data != null && data.getData() != null) {
                Uri treeUri = data.getData();
                try {
                    final int takeFlags = data.getFlags()
                            & (Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
                    getContentResolver().takePersistableUriPermission(treeUri, takeFlags);
                } catch (Throwable ignored) {}
                picked = new Uri[]{ treeUri };
            }
            if (mUploadCallback != null) {
                try { mUploadCallback.onReceiveValue(picked); } catch (Throwable ignored) {}
                mUploadCallback = null;
            }
            return;
        }
    }

    @Override
    protected void onDestroy() {
        if (mUploadCallback != null) {
            try { mUploadCallback.onReceiveValue(null); } catch (Throwable ignored) {}
            mUploadCallback = null;
        }
        unregisterInstance(this);
        try {
            NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
            if (nm != null && notifId != 0) nm.cancel(notifId);
        } catch (Throwable ignored) { }
        if (notifReceiver != null) {
            try { unregisterReceiver(notifReceiver); } catch (Throwable ignored) { }
            notifReceiver = null;
        }
        if (web != null) {
            try {
                web.destroy();
            } catch (Throwable ignored) {}
            web = null;
        }
        super.onDestroy();
    }

    /** HTML 应用可用的桥：输入诊断 + 主动关窗，其余能力按需再加。 */
    private class Bridge {

        @JavascriptInterface
        public String inputTrace() {
            return InputTrace.snapshotJson();
        }

        @JavascriptInterface
        public void inputTraceEnable(boolean on) {
            InputTrace.setEnabled(on);
        }

        @JavascriptInterface
        public void inputTraceClear() {
            InputTrace.clear();
        }

        @JavascriptInterface
        public String appId() {
            return appId;
        }

        /** 页面接管返回键（游戏内菜单等）：传 true 后原生不再做「再按一次退出」。 */
        @JavascriptInterface
        public void claimBack(boolean on) {
            backClaimedByPage = on;
            Log.i(TAG, "页面接管返回键=" + on);
        }

        /** 页面自己要求关闭窗口。 */
        @JavascriptInterface
        public void closeWindow() {
            runOnUiThread(HtmlAppActivity.this::finish);
        }

        /** 页面保存下载文件：写到系统公共下载目录并提示 */
        @JavascriptInterface
        public String saveDownload(final String filename, final String dataUrl) {
            try {
                org.json.JSONObject res = CanghaiCore.saveDownloadDirect(HtmlAppActivity.this, filename, dataUrl);
                runOnUiThread(() -> Toast("已保存至下载目录: " + res.optString("name")));
                return res.toString();
            } catch (Throwable t) {
                Log.w(TAG, "saveDownload 失败: " + t.getMessage(), t);
                runOnUiThread(() -> Toast("保存失败: " + t.getMessage()));
                return "{\"saved\":false,\"error\":\"" + t.getMessage() + "\"}";
            }
        }

        /**
         * 页面里的输入框被点了。现在键盘由注册进系统的「沧海键盘」
         * （系统原生输入法）负责，这边不需要做任何事；
         * 两个方法保留成空实现，是为了老页面调过来不至于抛异常。
         */
        @JavascriptInterface
        public void imeWanted(String mode) {
        }

        @JavascriptInterface
        public void imeHide() {
        }

        @JavascriptInterface
        public void log(String msg) {
            Log.i(TAG, "[app] " + msg);
        }
    }

}
