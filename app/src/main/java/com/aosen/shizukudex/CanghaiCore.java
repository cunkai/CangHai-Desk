package com.aosen.shizukudex;

import android.content.ContentValues;
import android.content.Context;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.provider.MediaStore;
import android.util.Base64;
import android.util.Log;
import android.webkit.JavascriptInterface;
import android.webkit.WebView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.ref.WeakReference;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * 前端 ↔ 安卓唯一通道。
 *
 * Native 侧以 "__CanghaiNative" 注入；assets/canghai/shared/core.js 据此生成
 * window.CanghaiCore，desktop/js/canghai-core.js 再包成 window.CHCore。
 * 契约：invoke(ns, method, payloadJson) -> "{\"ok\":true,\"data\":...}" / "{\"ok\":false,\"error\":\"...\"}"。
 * 反向事件：window.__canghaiCoreEmit(type, "{type,data,ts}")。
 *
 * 身份规则（这是沙箱的关键）：
 *   · boundAppId == null → 宿主页面（桌面 / 手机主页，L1）。桌面按 iframe 的
 *     e.source 认应用身份后代理调用，所以允许 payload 里带 appId。
 *   · boundAppId != null → 应用独立窗口（L0）。fs 调用的 appId 一律由 Native
 *     覆盖成该实例绑定的值，前端传什么都没用，无法读写别人的目录。
 * 于是前端契约一个字都不用改，身份下沉到实例。
 */
public final class CanghaiCore {

    private static final String TAG = "CanghaiCore";
    private static final String PREF = "canghai_core";
    private static final String KEY_UUID = "device_uuid";
    private static final String KEY_RENDER_SCALE = "render_scale";
    private static final AtomicBoolean PERF_ON = new AtomicBoolean(false);
    private static final CopyOnWriteArrayList<WeakReference<WebView>> WEBVIEWS =
            new CopyOnWriteArrayList<>();

    private final Context ctx;
    private final WeakReference<WebView> webRef;
    private final String boundAppId;
    private final CoreFs fs;

    private CanghaiCore(Context context, WebView web, String boundAppId) {
        this.ctx = context.getApplicationContext();
        this.webRef = new WeakReference<>(web);
        this.boundAppId = boundAppId;
        this.fs = new CoreFs(this.ctx);
        WEBVIEWS.add(new WeakReference<>(web));
    }

    /**
     * 给一个 WebView 挂上统一桥。
     *
     * @param boundAppId L0 应用窗口传该应用的 appId；宿主页面传 null。
     */
    public static CanghaiCore attach(Context context, WebView web, String boundAppId) {
        CanghaiCore core = new CanghaiCore(context, web, boundAppId);
        web.addJavascriptInterface(core, "__CanghaiNative");
        /* 局域网大厅随第一个 WebView 宿主异步自启，只起一次、不阻塞 UI。 */
        MiniChatBridge.ensureStartedAsync(context);
        return core;
    }

    /**
     * 把局域网短地址和配对码推到每个宿主页面的全局，供应用 iframe 兜底读取。
     * 有些 WebView / sandbox iframe 拿不到 addJavascriptInterface 注入的对象，
     * 但 allow-same-origin 的 iframe 能读 window.parent 的普通全局变量。
     */
    static void publishLanUrl(String url, String pin) {
        if (url == null || url.isEmpty()) return;
        final String js = "window.__CH_LAN_URL__=" + JSONObject.quote(url)
                + ";window.__CH_LAN_PIN__=" + JSONObject.quote(pin == null ? "" : pin) + ";";
        for (WeakReference<WebView> ref : WEBVIEWS) {
            final WebView w = ref.get();
            if (w == null) continue;
            try {
                w.post(() -> {
                    try { w.evaluateJavascript(js, null); } catch (Throwable ignored) {}
                });
            } catch (Throwable ignored) {}
        }
    }

    @JavascriptInterface
    public String invoke(String ns, String method, String payloadJson) {
        try {
            JSONObject p;
            if (payloadJson == null || payloadJson.isEmpty()) {
                p = new JSONObject();
            } else {
                p = new JSONObject(payloadJson);
            }
            if ("fs".equals(ns) && boundAppId != null) {
                p.put("appId", boundAppId);
            }
            Object data = dispatch(ns == null ? "" : ns, method == null ? "" : method, p);
            return ok(data);
        } catch (CoreFs.Err e) {
            return err(e.code, e.getMessage());
        } catch (org.json.JSONException e) {
            return err("INVALID_ARGS", "payload 不是合法 JSON");
        } catch (Throwable t) {
            Log.w(TAG, "invoke 失败 ns=" + ns + " method=" + method, t);
            return err("INTERNAL_ERROR", "内部错误");
        }
    }

    private Object dispatch(String ns, String method, JSONObject p) throws Exception {
        switch (ns) {
            case "fs":
                return fs.handle(method, p);
            case "device":
                return device(method, p);
            case "perf":
                return perf(method, p);
            case "audio":
                return audio(method, p);
            case "screen":
                return new JSONArray();
            case "peripheral":
                return new JSONArray();
            case "lan":
                return lan(method, p);
            case "github":
                return github(method, p);
            default:
                throw new CoreFs.Err("UNKNOWN_NAMESPACE", "未知命名空间: " + ns);
        }
    }

    /* ---------------- lan（MiniChat 局域网大厅，邀请口令鉴权） ---------------- */
    private Object lan(String method, JSONObject p) throws Exception {
        switch (method) {
            case "start":
                return new JSONObject()
                        .put("port", MiniChatBridge.start(ctx))
                        .put("url", MiniChatBridge.url())
                        .put("ready", MiniChatBridge.ready());
            case "url":
            case "getUrl":
                return new JSONObject().put("url", MiniChatBridge.url());
            case "status": {
                String lan = MiniChatBridge.lanUrl(ctx);
                return new JSONObject()
                        .put("ready", MiniChatBridge.ready())
                        .put("port", MiniChatBridge.port())
                        .put("url", MiniChatBridge.url())
                        .put("lanUrl", lan == null ? "" : lan)
                        .put("pairCode", MiniChatBridge.pairCode(ctx))
                        .put("bound", "0.0.0.0")
                        .put("note", "本机免配对；其它设备输配对码即可");
            }
            case "open":
                return new JSONObject().put("opened", MiniChatBridge.open(ctx));
            default:
                throw new CoreFs.Err("UNKNOWN_METHOD", "未知方法: lan." + method);
        }
    }

    /* ---------------- github（仓海应用：GitHub API + 产物下载） ----------------
     * 只允许 github.com 与 api.github.com 两个 host；token 由调用方每次传入，
     * Java 不保存、不记日志。request 走 JSON API；download 流式落盘到下载
     * 目录，是 zip 就解包（防路径穿越、限条目数与总大小）。 */
    private Object github(String method, JSONObject p) throws Exception {
        switch (method) {
            case "request":
                return githubRequest(p);
            case "download":
                return githubDownload(p);
            default:
                throw new CoreFs.Err("UNKNOWN_METHOD", "未知方法: github." + method);
        }
    }

    private static void checkGithubHost(String urlText) throws Exception {
        String host;
        try {
            host = new URL(urlText).getHost();
        } catch (Exception e) {
            throw new CoreFs.Err("INVALID_ARGS", "URL 不合法");
        }
        if (!"github.com".equals(host) && !"api.github.com".equals(host)) {
            throw new CoreFs.Err("PERMISSION_DENIED", "只允许访问 github.com 与 api.github.com");
        }
    }

    private static String sanitizeDownloadName(String filename) {
        if (filename == null || filename.trim().isEmpty()) return "download";
        return filename.replaceAll("[\\\\/:*?\"<>|]", "_");
    }

    private static String mimeForName(String filename) {
        String n = filename == null ? "" : filename.toLowerCase();
        if (n.endsWith(".apk")) return "application/vnd.android.package-archive";
        if (n.endsWith(".zip")) return "application/zip";
        if (n.endsWith(".json")) return "application/json";
        if (n.endsWith(".txt") || n.endsWith(".md")) return "text/plain";
        return "application/octet-stream";
    }

    private static String readCapped(InputStream in, int maxBytes) throws Exception {
        if (in == null) return "";
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int total = 0, n;
        while ((n = in.read(buf)) != -1) {
            if (total + n > maxBytes) {
                int rest = maxBytes - total;
                if (rest > 0) out.write(buf, 0, rest);
                break;
            }
            out.write(buf, 0, n);
            total += n;
        }
        return new String(out.toByteArray(), "UTF-8");
    }

    private static void copyStream(InputStream in, OutputStream out) throws Exception {
        byte[] buf = new byte[32768];
        int n;
        while ((n = in.read(buf)) != -1) out.write(buf, 0, n);
        out.flush();
    }

    private JSONObject githubRequest(JSONObject p) throws Exception {
        String urlText = p.optString("url", "");
        checkGithubHost(urlText);
        String httpMethod = p.optString("httpMethod", "GET");
        if (!"GET".equals(httpMethod) && !"POST".equals(httpMethod)) {
            throw new CoreFs.Err("INVALID_ARGS", "只支持 GET / POST");
        }
        HttpURLConnection conn = null;
        try {
            conn = (HttpURLConnection) new URL(urlText).openConnection();
            conn.setRequestMethod(httpMethod);
            conn.setConnectTimeout(15000);
            conn.setReadTimeout(60000);
            conn.setRequestProperty("Accept", "application/vnd.github+json");
            conn.setRequestProperty("X-GitHub-Api-Version", "2022-11-28");
            conn.setRequestProperty("User-Agent", "Canghai-Git");
            String token = p.optString("token", "");
            if (token != null && !token.isEmpty()) {
                conn.setRequestProperty("Authorization", "Bearer " + token);
            }
            String body = p.optString("body", null);
            if ("POST".equals(httpMethod) && body != null) {
                byte[] bytes = body.getBytes("UTF-8");
                conn.setDoOutput(true);
                conn.setRequestProperty("Content-Type", p.optString("contentType", "application/json"));
                conn.setRequestProperty("Content-Length", String.valueOf(bytes.length));
                try (OutputStream os = conn.getOutputStream()) {
                    os.write(bytes);
                }
            }
            int status = conn.getResponseCode();
            InputStream in = status >= 400 ? conn.getErrorStream() : conn.getInputStream();
            String text = readCapped(in, 2 * 1024 * 1024);
            return new JSONObject().put("status", status).put("text", text == null ? "" : text);
        } catch (CoreFs.Err e) {
            throw e;
        } catch (Exception e) {
            throw new CoreFs.Err("NETWORK_ERROR", String.valueOf(e.getMessage()));
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    private JSONObject githubDownload(JSONObject p) throws Exception {
        String urlText = p.optString("url", "");
        checkGithubHost(urlText);
        String filename = sanitizeDownloadName(p.optString("filename", "download"));
        String token = p.optString("token", "");
        File tmp = File.createTempFile("github-dl-", ".tmp", ctx.getCacheDir());
        HttpURLConnection conn = null;
        try {
            conn = (HttpURLConnection) new URL(urlText).openConnection();
            conn.setRequestMethod("GET");
            conn.setConnectTimeout(15000);
            conn.setReadTimeout(120000);
            conn.setRequestProperty("Accept", "application/octet-stream");
            conn.setRequestProperty("User-Agent", "Canghai-Git");
            if (token != null && !token.isEmpty()) {
                conn.setRequestProperty("Authorization", "Bearer " + token);
            }
            int status = conn.getResponseCode();
            if (status < 200 || status >= 300) {
                throw new CoreFs.Err("DOWNLOAD_FAILED", "HTTP " + status);
            }
            long total = 0;
            try (InputStream in = conn.getInputStream();
                 FileOutputStream fos = new FileOutputStream(tmp)) {
                byte[] buf = new byte[32768];
                int n;
                while ((n = in.read(buf)) != -1) {
                    total += n;
                    if (total > 512L * 1024 * 1024) throw new CoreFs.Err("DOWNLOAD_FAILED", "文件过大");
                    fos.write(buf, 0, n);
                }
            }
            JSONArray saved;
            if (isZipFile(tmp)) {
                saved = unzipToDownloads(tmp);
            } else {
                saved = new JSONArray();
                saved.put(writeToDownloads(tmp, filename, mimeForName(filename)));
            }
            return new JSONObject().put("saved", true).put("files", saved).put("dir", "Downloads/Canghai");
        } catch (CoreFs.Err e) {
            throw e;
        } catch (Exception e) {
            throw new CoreFs.Err("DOWNLOAD_FAILED", String.valueOf(e.getMessage()));
        } finally {
            tmp.delete();
            if (conn != null) conn.disconnect();
        }
    }

    private static boolean isZipFile(File f) throws Exception {
        try (FileInputStream fis = new FileInputStream(f)) {
            byte[] magic = new byte[4];
            if (fis.read(magic) < 4) return false;
            return magic[0] == 'P' && magic[1] == 'K' && magic[2] == 3 && magic[3] == 4;
        }
    }

    private JSONArray unzipToDownloads(File zipFile) throws Exception {
        JSONArray saved = new JSONArray();
        int entries = 0;
        long totalOut = 0;
        try (ZipInputStream zin = new ZipInputStream(new FileInputStream(zipFile))) {
            ZipEntry e;
            while ((e = zin.getNextEntry()) != null) {
                if (e.isDirectory()) continue;
                String name = e.getName().replace('\\', '/');
                if (name.startsWith("/") || name.contains("..")) continue;
                String base = name.substring(name.lastIndexOf('/') + 1);
                if (base.isEmpty()) continue;
                if (++entries > 100) throw new CoreFs.Err("DOWNLOAD_FAILED", "压缩包条目过多");
                File tmpEntry = File.createTempFile("github-entry-", ".tmp", ctx.getCacheDir());
                try {
                    long wrote = 0;
                    try (FileOutputStream fos = new FileOutputStream(tmpEntry)) {
                        byte[] buf = new byte[32768];
                        int n;
                        while ((n = zin.read(buf)) != -1) {
                            wrote += n;
                            totalOut += n;
                            if (totalOut > 512L * 1024 * 1024) throw new CoreFs.Err("DOWNLOAD_FAILED", "解压后过大");
                            fos.write(buf, 0, n);
                        }
                    }
                    JSONObject one = writeToDownloads(tmpEntry, sanitizeDownloadName(base), mimeForName(base));
                    one.put("size", wrote);
                    saved.put(one);
                } finally {
                    tmpEntry.delete();
                }
            }
        }
        if (saved.length() == 0) throw new CoreFs.Err("DOWNLOAD_FAILED", "压缩包里没有文件");
        return saved;
    }

    private JSONObject writeToDownloads(File src, String filename, String mimeType) throws Exception {
        boolean written = false;
        String finalName = filename;
        long size = src.length();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            try {
                ContentValues values = new ContentValues();
                values.put(MediaStore.Downloads.DISPLAY_NAME, filename);
                values.put(MediaStore.Downloads.MIME_TYPE, mimeType);
                values.put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/Canghai");
                Uri uri = ctx.getContentResolver().insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values);
                if (uri != null) {
                    try (InputStream in = new FileInputStream(src);
                         OutputStream os = ctx.getContentResolver().openOutputStream(uri)) {
                        if (os != null) {
                            copyStream(in, os);
                            written = true;
                        }
                    }
                }
            } catch (Throwable t) {
                Log.w(TAG, "MediaStore 写入失败，尝试文件兜底", t);
            }
        }
        if (!written) {
            File dlDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS);
            File targetDir = new File(dlDir, "Canghai");
            if (!targetDir.exists()) targetDir.mkdirs();
            File dest = new File(targetDir, filename);
            if (dest.exists()) {
                String base = filename;
                String ext = "";
                int dot = filename.lastIndexOf('.');
                if (dot > 0) {
                    base = filename.substring(0, dot);
                    ext = filename.substring(dot);
                }
                dest = new File(targetDir, base + "_" + System.currentTimeMillis() + ext);
            }
            try (InputStream in = new FileInputStream(src);
                 FileOutputStream fos = new FileOutputStream(dest)) {
                copyStream(in, fos);
                written = true;
                finalName = dest.getName();
            } catch (Throwable t) {
                File appDl = ctx.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS);
                if (appDl != null) {
                    if (!appDl.exists()) appDl.mkdirs();
                    try (InputStream in = new FileInputStream(src);
                         FileOutputStream fos = new FileOutputStream(new File(appDl, filename))) {
                        copyStream(in, fos);
                        written = true;
                    }
                }
                if (!written) throw t;
            }
        }
        return new JSONObject().put("saved", true).put("name", finalName).put("size", size);
    }

    /* ---------------- device ---------------- */
    private Object device(String method, JSONObject p) throws Exception {
        switch (method) {
            case "renderScale":
                return new JSONObject().put("value", shared().getInt(KEY_RENDER_SCALE, 100));
            case "setRenderScale": {
                int v = p.optInt("value", 100);
                if (v < 20) v = 20;
                if (v > 100) v = 100;
                shared().edit().putInt(KEY_RENDER_SCALE, v).apply();
                return new JSONObject().put("value", v);
            }
            case "streamStats":
                return new JSONObject()
                        .put("width", 0).put("height", 0)
                        .put("targetBitrate", 0).put("actualBitrate", 0).put("bitrateLimit", 0);
            case "webFps":
                return true;
            case "saveDownload":
                return saveDownload(p);
            case "info":
            case "getInfo":
            default:
                return deviceInfo();
        }
    }

    public static JSONObject saveDownloadDirect(Context ctx, String filename, String dataUrl) throws Exception {
        JSONObject p = new JSONObject();
        p.put("filename", filename);
        p.put("dataUrl", dataUrl);
        return doSaveDownload(ctx, p);
    }

    private JSONObject saveDownload(JSONObject p) throws Exception {
        return doSaveDownload(this.ctx, p);
    }

    private static JSONObject doSaveDownload(Context ctx, JSONObject p) throws Exception {
        if (p == null) throw new CoreFs.Err("INVALID_ARGS", "缺少参数");
        String filename = p.optString("filename", "download");
        if (filename == null || filename.trim().isEmpty()) filename = "download";
        filename = filename.replaceAll("[\\\\/:*?\"<>|]", "_");

        String dataUrl = p.optString("dataUrl", "");
        byte[] bytes;
        String mimeType = "application/octet-stream";
        if (dataUrl.startsWith("data:")) {
            int comma = dataUrl.indexOf(',');
            if (comma >= 0) {
                String meta = dataUrl.substring(5, comma);
                int semi = meta.indexOf(';');
                if (semi >= 0) {
                    mimeType = meta.substring(0, semi);
                } else if (!meta.isEmpty()) {
                    mimeType = meta;
                }
                String b64 = dataUrl.substring(comma + 1);
                bytes = Base64.decode(b64, Base64.DEFAULT);
            } else {
                bytes = dataUrl.getBytes("UTF-8");
            }
        } else {
            bytes = dataUrl.getBytes("UTF-8");
        }

        // 优先尝试写到公共 Downloads 目录
        boolean written = false;
        long writtenSize = bytes.length;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            try {
                ContentValues values = new ContentValues();
                values.put(MediaStore.Downloads.DISPLAY_NAME, filename);
                values.put(MediaStore.Downloads.MIME_TYPE, mimeType);
                values.put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/Canghai");
                Uri uri = ctx.getContentResolver().insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values);
                if (uri != null) {
                    try (OutputStream os = ctx.getContentResolver().openOutputStream(uri)) {
                        if (os != null) {
                            os.write(bytes);
                            os.flush();
                            written = true;
                        }
                    }
                }
            } catch (Throwable t) {
                Log.w(TAG, "MediaStore 下载落盘失败，尝试文件兜底", t);
            }
        }

        if (!written) {
            File dlDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS);
            File targetDir = new File(dlDir, "Canghai");
            if (!targetDir.exists()) targetDir.mkdirs();
            File dest = new File(targetDir, filename);
            // 避免覆盖重名
            if (dest.exists()) {
                String base = filename;
                String ext = "";
                int dot = filename.lastIndexOf('.');
                if (dot > 0) {
                    base = filename.substring(0, dot);
                    ext = filename.substring(dot);
                }
                dest = new File(targetDir, base + "_" + System.currentTimeMillis() + ext);
            }
            try (FileOutputStream fos = new FileOutputStream(dest)) {
                fos.write(bytes);
                fos.flush();
                written = true;
                filename = dest.getName();
            } catch (Throwable t) {
                // 如果公共目录由于权限无法写入，写到应用专属的外部下载目录
                File appDl = ctx.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS);
                if (appDl != null) {
                    if (!appDl.exists()) appDl.mkdirs();
                    File destApp = new File(appDl, filename);
                    try (FileOutputStream fos = new FileOutputStream(destApp)) {
                        fos.write(bytes);
                        fos.flush();
                        written = true;
                        filename = destApp.getName();
                    }
                }
                if (!written) throw t;
            }
        }

        JSONObject res = new JSONObject();
        res.put("saved", true);
        res.put("name", filename);
        res.put("size", writtenSize);
        return res;
    }

    private JSONObject deviceInfo() throws Exception {
        String uuid = shared().getString(KEY_UUID, "");
        if (uuid == null || uuid.isEmpty()) {
            uuid = UUID.randomUUID().toString();
            shared().edit().putString(KEY_UUID, uuid).apply();
        }
        return new JSONObject()
                .put("uuid", uuid)
                .put("model", Build.MODEL == null ? "" : Build.MODEL)
                .put("brand", Build.BRAND == null ? "" : Build.BRAND);
    }

    /* ---------------- perf ---------------- */
    private Object perf(String method, JSONObject p) throws Exception {
        switch (method) {
            case "enable":
                PERF_ON.set(p.optBoolean("on", false));
                return new JSONObject().put("enabled", PERF_ON.get());
            case "state":
                return new JSONObject().put("enabled", PERF_ON.get());
            case "snapshot":
                return new JSONObject()
                        .put("pages", new JSONArray())
                        .put("windows", new JSONArray());
            case "report":
                return true;
            default:
                return new JSONObject().put("enabled", PERF_ON.get());
        }
    }

    /* ---------------- audio ---------------- */
    private Object audio(String method, JSONObject p) throws Exception {
        if ("request".equals(method)) {
            return new JSONObject().put("started", false).put("reason", "NOT_WIRED");
        }
        return new JSONObject()
                .put("permission", false)
                .put("projection", false)
                .put("capturing", false)
                .put("streaming", false)
                .put("audible", false)
                .put("reason", "NOT_WIRED");
    }

    private SharedPreferences shared() {
        return ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE);
    }

    /* ---------------- 统一返回 ---------------- */
    private static String ok(Object data) {
        try {
            JSONObject o = new JSONObject();
            o.put("ok", true);
            o.put("data", data == null ? JSONObject.NULL : data);
            return o.toString();
        } catch (Exception e) {
            return "{\"ok\":true,\"data\":null}";
        }
    }

    private static String err(String code, String message) {
        try {
            JSONObject o = new JSONObject();
            o.put("ok", false);
            o.put("error", code == null ? "INTERNAL_ERROR" : code);
            if (message != null && !message.equals(code)) o.put("message", message);
            return o.toString();
        } catch (Exception e) {
            return "{\"ok\":false,\"error\":\"INTERNAL_ERROR\"}";
        }
    }

    /* ---------------- 反向事件 ---------------- */
    /** 原生推事件给所有已挂桥的页面，信封为 {type,data,ts}，前端会自己拆。 */
    public static void emit(String type, Object data) {
        try {
            JSONObject env = new JSONObject();
            env.put("type", type);
            env.put("data", data == null ? JSONObject.NULL : data);
            env.put("ts", System.currentTimeMillis());
            final String js = "window.__canghaiCoreEmit&&window.__canghaiCoreEmit("
                    + JSONObject.quote(type) + "," + JSONObject.quote(env.toString()) + ")";
            for (WeakReference<WebView> ref : WEBVIEWS) {
                WebView w = ref.get();
                if (w == null) {
                    WEBVIEWS.remove(ref);
                    continue;
                }
                try {
                    w.post(() -> w.evaluateJavascript(js, null));
                } catch (Throwable ignored) {}
            }
        } catch (Throwable t) {
            Log.w(TAG, "emit 失败", t);
        }
    }
}
