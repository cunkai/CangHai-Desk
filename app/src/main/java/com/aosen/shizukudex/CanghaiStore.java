package com.aosen.shizukudex;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.webkit.WebView;
import android.content.pm.ApplicationInfo;
import android.content.pm.LauncherApps;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.os.Build;
import android.util.Base64;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/**
 * 沧海系统的唯一数据源。
 *
 * <p>手机端（连接管理 / 应用 / 设置）与外屏桌面必须看到同一份数据，所以全部落在 Native：
 * <ul>
 *   <li><b>鼠标样式库</b>：内置 + 用户自定义（SVG + 热点），两端共用一份，改哪边另一边立刻跟着变。</li>
 *   <li><b>沧海软件</b>：用户装进来的 HTML，源码存内部存储，索引存 SharedPreferences。</li>
 *   <li><b>桌面偏好</b>：壁纸 / 网格格数，外屏改完刷新页面也还在。</li>
 * </ul>
 *
 * <p>JSON 结构两端一致，样式渲染逻辑只写在页面里（见 shared 说明），Native 不重复实现，
 * 避免"手机上一个样、外屏另一个样"。
 */
public final class CanghaiStore {

    private static final String TAG = "CanghaiStore";
    private static final String PREFS = "canghai_settings";

    private static final String KEY_CURSOR_LIB = "cursor_lib";
    private static final String KEY_CURSOR_CUR = "cursor_current";
    private static final String KEY_CURSOR_SIZE = "cursor_size";
    private static final String KEY_CURSOR_SPEED = "cursor_speed";
    private static final String KEY_USER_NAME = "user_name";
    private static final String KEY_USER_AVATAR = "user_avatar";
    
    private static final String KEY_DOCK_OFFSET_X = "dock_offset_x";
    private static final String KEY_DOCK_OFFSET_Y = "dock_offset_y";

    public static int getDockOffsetX(Context c) {
        if (c == null) return 0;
        return c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getInt(KEY_DOCK_OFFSET_X, 0);
    }

    public static int getDockOffsetY(Context c) {
        if (c == null) return 0;
        return c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getInt(KEY_DOCK_OFFSET_Y, 0);
    }

    public static void setDockOffsets(Context c, int x, int y) {
        if (c == null) return;
        c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putInt(KEY_DOCK_OFFSET_X, x)
                .putInt(KEY_DOCK_OFFSET_Y, y)
                .apply();
        notifyDataChanged(c);
    }

    public static final String KEY_LANGUAGE = "canghai_language";

    public static String getLanguage(Context c) {
        String lang = prefs(c).getString(KEY_LANGUAGE, null);
        if (lang != null && !lang.isEmpty()) return lang;
        java.util.Locale loc = java.util.Locale.getDefault();
        String l = loc.getLanguage();
        String country = loc.getCountry();
        String script = "";
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.LOLLIPOP) {
            script = loc.getScript();
        }
        if ("zh".equalsIgnoreCase(l)) {
            if ("Hant".equalsIgnoreCase(script) || "TW".equalsIgnoreCase(country) || "HK".equalsIgnoreCase(country) || "MO".equalsIgnoreCase(country)) {
                return "zh-Hant";
            }
            return "zh-Hans";
        }
        return "en";
    }

    public static void setLanguage(Context c, String lang) {
        if (!"zh-Hant".equals(lang) && !"en".equals(lang)) {
            lang = "zh-Hans";
        }
        prefs(c).edit().putString(KEY_LANGUAGE, lang).apply();
    }

    /** 沧海系统自身版本号，与 Gitee 上 canghai.json 的 versionCode 同一尺度。 */
    public static final double CANGHAI_VERSION = 1.0;
    public static final String UPDATE_URL =
            "https://raw.giteeusercontent.com/cunkai/tips/raw/master/canghai.json";
    private static final String KEY_HTML_INDEX = "html_apps";
    private static final String KEY_WALLPAPER = "wallpaper";
    private static final String KEY_GRID_COLS = "grid_cols";
    private static final String KEY_GRID_ROWS = "grid_rows";
    private static final String KEY_DESKTOP_ITEMS = "desktop_items";
    private static final String KEY_DOCK_PINS = "dock_pins";
    private static final String KEY_DOCK_AUTO_HIDE = "dock_auto_hide";
    /** 手机端「投到屏幕」时留一次意图，外屏桌面开机/热启时取走并打开。 */
    private static final String KEY_PENDING_OPEN = "pending_open";
    /** 更新检查落盘：每 7 天自动检查一次，结果只点亮绿点，绝不自动打开浏览器。 */
    private static final String KEY_UPD_LAST = "upd_last_check";
    private static final String KEY_UPD_HAS = "upd_has_update";
    private static final String KEY_UPD_VER = "upd_ver_name";
    public static final long UPDATE_CHECK_INTERVAL_MS = 7L * 24L * 3600L * 1000L;

    private static final String APPS_DIR = "canghai_apps";

    /** 内置样式：id 与 Native 指针绘制的 STYLE_XXX 一一对应。 */
    private static final String[][] BUILTIN_CURSORS = {
            // id, 名字, 对应 MouseCursorPrefs 样式索引
            {"arrow", "箭头", "0"},
            {"dot", "圆点", "1"},
            {"block", "方块", "2"},
    };

    private CanghaiStore() {
    }

    private static SharedPreferences prefs(Context c) {
        return c.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    // ==================================================================
    // 鼠标样式库
    // ==================================================================

    /** 样式库：内置三项 + 用户自定义项（{id,name,svg,hx,hy}）。 */
    public static String cursorLibJson(Context c) {
        JSONArray arr = new JSONArray();
        try {
            for (String[] b : BUILTIN_CURSORS) {
                JSONObject o = new JSONObject();
                o.put("id", b[0]);
                o.put("name", b[1]);
                o.put("builtin", true);
                o.put("style", Integer.parseInt(b[2]));
                arr.put(o);
            }
            String raw = prefs(c).getString(KEY_CURSOR_LIB, "[]");
            JSONArray custom = new JSONArray(raw == null ? "[]" : raw);
            for (int i = 0; i < custom.length(); i++) {
                JSONObject o = custom.optJSONObject(i);
                if (o != null) arr.put(o);
            }
        } catch (Throwable t) {
            Log.w(TAG, "cursorLibJson: " + t.getMessage());
        }
        return arr.toString();
    }

    /** 当前选中的样式 id（内置 id 或自定义 id）。 */
    public static String cursorCurrent(Context c) {
        return cursorCurrentForDisplay(c, 0);
    }

    public static String cursorCurrentForDisplay(Context c, int displayId) {
        if (c == null) return "arrow";
        SharedPreferences sp = prefs(c);
        if (displayId > 0) {
            String key = KEY_CURSOR_CUR + "_" + displayId;
            if (sp.contains(key)) return sp.getString(key, "arrow");
        }
        return sp.getString(KEY_CURSOR_CUR, "arrow");
    }

    public static int cursorSize(Context c) {
        return cursorSizeForDisplay(c, 0);
    }

    /** 按每个外屏单独读取指针大小（1 ~ 9999）。 */
    public static int cursorSizeForDisplay(Context c, int displayId) {
        if (c == null) return 28;
        SharedPreferences sp = prefs(c);
        if (displayId > 0) {
            String key = KEY_CURSOR_SIZE + "_" + displayId;
            if (sp.contains(key)) {
                return Math.max(1, Math.min(9999, sp.getInt(key, 28)));
            }
        }
        return Math.max(1, Math.min(9999, sp.getInt(KEY_CURSOR_SIZE, 28)));
    }

    /**
     * 选中一个样式：内置项 → 让 Native 指针切到对应预置；自定义项 → 走自定义 SVG 通道。
     * 这样外屏上的系统级指针（MouseCursorView）与页面里画的光标永远同一个样子。
     */
    public static void selectCursor(Context c, String id) {
        selectCursorForDisplay(c, 0, id);
    }

    /** 按外屏单独选中光标样式，互不影响。 */
    public static void selectCursorForDisplay(Context c, int displayId, String id) {
        id = id == null ? "arrow" : id.trim();
        SharedPreferences.Editor ed = prefs(c).edit().putString(KEY_CURSOR_CUR, id);
        if (displayId > 0) {
            ed.putString(KEY_CURSOR_CUR + "_" + displayId, id);
        }
        ed.apply();
        try {
            for (String[] b : BUILTIN_CURSORS) {
                if (b[0].equals(id)) {
                    com.alkaidlab.sunshine.service.MouseCursorPrefs
                            .setStyleForDisplay(c, displayId, Integer.parseInt(b[2]));
                    return;
                }
            }
            String cleanId = id.startsWith("lib:") ? id.substring(4) : id;
            JSONObject o = findCustom(c, cleanId);
            if (o != null) {
                String svg = o.optString("svg", "");
                String path = svgToPathData(svg);
                int hx = o.optInt("hx", 50);
                int hy = o.optInt("hy", 50);
                com.alkaidlab.sunshine.service.MouseCursorPrefs
                        .setCustomSvgForDisplay(c, displayId, path, hx, hy);
                com.alkaidlab.sunshine.service.MouseCursorPrefs
                        .setStyleForDisplay(c, displayId, com.alkaidlab.sunshine.service.MouseCursorPrefs.STYLE_CUSTOM_SVG);
            }
        } catch (Throwable t) {
            Log.w(TAG, "selectCursorForDisplay: " + t.getMessage());
        }
    }

    /**
     * 指针大小：px 换算成绘制倍率写进 MouseCursorPrefs，
     * 系统级自绘光标（物理鼠标 / 黑屏触控 / Moonlight）立刻跟着变大变小。
     * 28 px = 1.0 倍，与页面 CSS 光标的默认尺寸一致。
     */
    public static void setCursorSize(Context c, int px) {
        setCursorSizeForDisplay(c, 0, px);
    }

    /** 按指定外屏单独保存指针大小（1 ~ 9999），并同步激活缩放。 */
    public static void setCursorSizeForDisplay(Context c, int displayId, int px) {
        int size = Math.max(1, Math.min(9999, px));
        // KEY_CURSOR_SIZE 是「手机自身屏幕（displayId=0）」的全局值。
        // 外屏单独设置时绝不能顺手覆盖它，否则进程重启后 syncCursorPrefsToNative
        // 会把外屏的小尺寸灌给手机主屏，手机光标就缩成一个小点。
        SharedPreferences.Editor ed = prefs(c).edit();
        if (displayId > 0) {
            ed.putInt(KEY_CURSOR_SIZE + "_" + displayId, size);
        } else {
            ed.putInt(KEY_CURSOR_SIZE, size);
        }
        ed.apply();
        try {
            com.alkaidlab.sunshine.service.MouseCursorPrefs
                    .setContinuousScaleForDisplay(c, displayId, size / 28f);
        } catch (Throwable t) {
            Log.w(TAG, "setCursorSizeForDisplay/applyScale: " + t.getMessage());
        }
    }

    // ==================================================================
    // 用户头像与名字（手机端与外屏桌面共用）
    // ==================================================================

    public static String userName(Context c) {
        if (c == null) return "沧海用户";
        String n = prefs(c).getString(KEY_USER_NAME, "沧海用户");
        return (n == null || n.trim().isEmpty()) ? "沧海用户" : n.trim();
    }

    public static void setUserName(Context c, String name) {
        if (c == null) return;
        String n = (name == null || name.trim().isEmpty()) ? "沧海用户" : name.trim();
        prefs(c).edit().putString(KEY_USER_NAME, n).apply();
    }

    public static String userAvatar(Context c) {
        if (c == null) return "";
        String a = prefs(c).getString(KEY_USER_AVATAR, "");
        return a == null ? "" : a;
    }

    public static void setUserAvatar(Context c, String avatar) {
        if (c == null) return;
        prefs(c).edit().putString(KEY_USER_AVATAR, avatar == null ? "" : avatar).apply();
    }

    /** 指针速度（0.4 ~ 2.6），与手机端、外屏桌面共用一份。 */
    public static float cursorSpeed(Context c) {
        try {
            return prefs(c).getFloat(KEY_CURSOR_SPEED, 1.0f);
        } catch (Throwable t) {
            return 1.0f;
        }
    }

    public static void setCursorSpeed(Context c, float v) {
        float speed = Math.max(0.4f, Math.min(2.6f, v));
        prefs(c).edit().putFloat(KEY_CURSOR_SPEED, speed).apply();
        try {
            com.alkaidlab.sunshine.service.MouseCursorPrefs.setPointerSpeed(c, speed);
        } catch (Throwable t) {
            Log.w(TAG, "setCursorSpeed: " + t.getMessage());
        }
    }

    /**
     * 启动时将 canghai_settings 中保存的光标样式、大小、速度同步到 MouseCursorPrefs，
     * 避免进程重启或外屏刚连接时两套 SharedPreferences 不一致。
     */
    public static void syncCursorPrefsToNative(Context c) {
        if (c == null) return;
        try {
            com.alkaidlab.sunshine.service.MouseCursorPrefs.load(c);
            android.content.SharedPreferences sp = prefs(c);
            if (sp.contains(KEY_CURSOR_SIZE)) {
                int size = sp.getInt(KEY_CURSOR_SIZE, 28);
                com.alkaidlab.sunshine.service.MouseCursorPrefs.setContinuousScale(c, size / 28f);
            }
            if (sp.contains(KEY_CURSOR_SPEED)) {
                float speed = sp.getFloat(KEY_CURSOR_SPEED, 1.0f);
                com.alkaidlab.sunshine.service.MouseCursorPrefs.setPointerSpeed(c, speed);
            }
            if (sp.contains(KEY_CURSOR_CUR)) {
                selectCursor(c, sp.getString(KEY_CURSOR_CUR, "arrow"));
            }
        } catch (Throwable t) {
            Log.w(TAG, "syncCursorPrefsToNative: " + t.getMessage());
        }
    }

    /** 新增 / 覆盖一条自定义样式，返回它的 id。 */
    public static String upsertCursor(Context c, String id, String name, String svg, int hx, int hy) {
        try {
            JSONArray arr = new JSONArray(prefs(c).getString(KEY_CURSOR_LIB, "[]"));
            if (id == null || id.isEmpty()) id = "c" + System.currentTimeMillis();
            boolean found = false;
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.optJSONObject(i);
                if (o != null && id.equals(o.optString("id"))) {
                    o.put("name", name);
                    o.put("svg", svg);
                    o.put("hx", hx);
                    o.put("hy", hy);
                    found = true;
                    break;
                }
            }
            if (!found) {
                JSONObject o = new JSONObject();
                o.put("id", id);
                o.put("name", name);
                o.put("svg", svg);
                o.put("hx", hx);
                o.put("hy", hy);
                arr.put(o);
            }
            prefs(c).edit().putString(KEY_CURSOR_LIB, arr.toString()).apply();
            String cur = cursorCurrent(c);
            if (id.equals(cur) || ("lib:" + id).equals(cur)) {
                com.alkaidlab.sunshine.service.MouseCursorPrefs.setCustomHotspot(c, hx, hy);
            }
            return id;
        } catch (Throwable t) {
            Log.w(TAG, "upsertCursor: " + t.getMessage());
            return null;
        }
    }

    public static void removeCursor(Context c, String id) {
        try {
            JSONArray arr = new JSONArray(prefs(c).getString(KEY_CURSOR_LIB, "[]"));
            JSONArray out = new JSONArray();
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.optJSONObject(i);
                if (o == null) continue;
                if (id != null && id.equals(o.optString("id"))) continue;
                out.put(o);
            }
            prefs(c).edit().putString(KEY_CURSOR_LIB, out.toString()).apply();
            if (id != null && id.equals(cursorCurrent(c))) selectCursor(c, "arrow");
        } catch (Throwable t) {
            Log.w(TAG, "removeCursor: " + t.getMessage());
        }
    }

    private static JSONObject findCustom(Context c, String id) {
        if (id == null || id.isEmpty()) return null;
        if (id.startsWith("lib:")) id = id.substring(4);
        try {
            JSONArray arr = new JSONArray(prefs(c).getString(KEY_CURSOR_LIB, "[]"));
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.optJSONObject(i);
                if (o != null && id.equals(o.optString("id"))) return o;
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    /**
     * 把一段自定义 SVG 变成 Native 指针能画的 path 数据。
     * 只取第一条 &lt;path d="..."&gt;：指针是单色描边图形，取主轮廓已经够认。
     */
    private static String svgToPathData(String svg) {
        if (svg == null) return null;
        String trimmed = svg.trim();
        if (trimmed.startsWith("M") || trimmed.startsWith("m")) {
            return trimmed;
        }
        try {
            java.util.regex.Matcher m = java.util.regex.Pattern
                    .compile("<path[^>]*\\bd\\s*=\\s*['\"]([^'\"]+)['\"]", java.util.regex.Pattern.CASE_INSENSITIVE)
                    .matcher(trimmed);
            if (m.find()) return m.group(1);
        } catch (Throwable ignored) {
        }
        return null;
    }

    // ==================================================================
    // 沧海软件（HTML 应用）
    // ==================================================================

    public static File appsDir(Context c) {
        File d = new File(c.getFilesDir(), APPS_DIR);
        if (!d.exists()) //noinspection ResultOfMethodCallIgnored
            d.mkdirs();
        return d;
    }

    /**
     * 已安装软件索引：[{id,name,size,png,ori,at}]，不含源码（源码单独取，避免每次传几 MB）。
     * size 是安装时记录的实际字节数；老条目没有时读文件补上。
     * png 是页面渲染好的 128×128 图标 PNG data URL（建桌面快捷方式直接解码就用，不必原生解 SVG）。
     * ori 是横竖屏锁定：auto / portrait / landscape。
     */
    public static String htmlAppsJson(Context c) {
        String raw = prefs(c).getString(KEY_HTML_INDEX, "[]");
        if (raw == null) return "[]";
        /* 老条目补 size：懒填充一次就写回，之后不再走文件系统 */
        try {
            JSONArray arr = new JSONArray(raw);
            boolean dirty = false;
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.optJSONObject(i);
                if (o == null || o.has("size")) continue;
                File f = new File(appsDir(c), String.valueOf(o.optString("id"))
                        .replaceAll("[^A-Za-z0-9_\\-]", "_") + ".html");
                o.put("size", f.exists() ? f.length() : 0);
                dirty = true;
            }
            if (dirty) {
                raw = arr.toString();
                prefs(c).edit().putString(KEY_HTML_INDEX, raw).apply();
            }
        } catch (Throwable ignored) {
        }
        return raw;
    }

    public static File getHtmlAppFile(Context c, String id) {
        if (id == null || id.isEmpty()) return null;
        File f = new File(appsDir(c), id.replaceAll("[^A-Za-z0-9_\\-]", "_") + ".html");
        return f.exists() ? f : null;
    }

    public static InputStream openHtmlAppStreamForRun(Context c, String id, String bootScript) {
        File f = getHtmlAppFile(c, id);
        if (f == null) return null;
        try {
            if (f.length() < 8L * 1024L * 1024L) {
                String html = readHtmlApp(c, id);
                if (html != null && html.contains(RUNTIME_TAG)) {
                    String rt = readRuntimeAsset(c);
                    String body = (rt == null)
                            ? "console.warn('内置运行时读取失败，这个应用可能跑不起来');"
                            : rt.replace("</script", "<\\/script");
                    html = html.replace(RUNTIME_TAG, body);
                    if (bootScript != null && !bootScript.isEmpty()) {
                        html = bootScript + html;
                    }
                    return new java.io.ByteArrayInputStream(html.getBytes(StandardCharsets.UTF_8));
                }
            }
            InputStream fileIn = new java.io.FileInputStream(f);
            if (bootScript != null && !bootScript.isEmpty()) {
                byte[] bootBytes = bootScript.getBytes(StandardCharsets.UTF_8);
                return new java.io.SequenceInputStream(new java.io.ByteArrayInputStream(bootBytes), fileIn);
            }
            return fileIn;
        } catch (Throwable t) {
            Log.e(TAG, "openHtmlAppStreamForRun: " + t.getMessage(), t);
            return null;
        }
    }

    public static String readHtmlApp(Context c, String id) {
        if (id == null || id.isEmpty()) return null;
        File f = new File(appsDir(c), id.replaceAll("[^A-Za-z0-9_\\-]", "_") + ".html");
        if (!f.exists()) return null;
        try (InputStream in = new java.io.FileInputStream(f)) {
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) bos.write(buf, 0, n);
            return new String(bos.toByteArray(), StandardCharsets.UTF_8);
        } catch (Throwable t) {
            Log.w(TAG, "readHtmlApp: " + t.getMessage());
            return null;
        }
    }

    /** 源码工程包在手机端组装时只留一个运行时标记，由这里统一把 three 运行时拼进去。 */
    public static final String RUNTIME_TAG = "/*__CH_RUNTIME__*/";
    private static final String RUNTIME_ASSET = "canghai/shared/three_runtime.js";

    /**
     * 真正能跑起来的 HTML：把内置三方运行时注入进去。
     * 手机端独立窗口（HtmlAppActivity）、外屏桌面 iframe（DexHomeActivity）、
     * 手机端安装预览都走这一个方法，避免「手机能开、大屏黑屏」。
     */
    public static String readHtmlAppForRun(Context c, String id) {
        String html = readHtmlApp(c, id);
        if (html == null || !html.contains(RUNTIME_TAG)) return html;
        String rt = readRuntimeAsset(c);
        String body = (rt == null)
                ? "console.warn('内置运行时读取失败，这个应用可能跑不起来');"
                : rt.replace("</script", "<\\/script");
        // String.replace 是字面量替换，不会把运行时里的 $& / $' 当替换组
        String merged = html.replace(RUNTIME_TAG, body);
        Log.i(TAG, "注入内置运行时 " + (rt == null ? 0 : rt.length()) + " 字节 -> " + id);
        return merged;
    }

    private static String readRuntimeAsset(Context c) {
        try (InputStream in = c.getAssets().open(RUNTIME_ASSET)) {
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            byte[] buf = new byte[16384];
            int n;
            while ((n = in.read(buf)) > 0) bos.write(buf, 0, n);
            return new String(bos.toByteArray(), StandardCharsets.UTF_8);
        } catch (Throwable t) {
            Log.w(TAG, "readRuntimeAsset: " + t);
            return null;
        }
    }

    /** 内置软件在 assets 里的根目录（随 APK 一起发，不经过安装流程）。 */
    private static final String BUILTIN_ROOT = "canghai/apps/";

    /** 内置应用的 id 只允许 [A-Za-z0-9_-]；带 / 或 .. 直接判非法，防止读到别的资产目录。 */
    private static String safeBuiltinId(String id) {
        if (id == null || id.isEmpty()) return null;
        String s = id.replaceAll("[^A-Za-z0-9_\\-]", "");
        return s.equals(id) ? s : null;
    }

    /** 是不是内置应用（assets/canghai/apps/&lt;id&gt;/index.html 存在）。 */
    public static boolean isBuiltinApp(Context c, String id) {
        String sid = safeBuiltinId(id);
        if (sid == null) return false;
        try (InputStream in = c.getAssets().open(BUILTIN_ROOT + sid + "/index.html")) {
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    /** 内置应用入口 URL：file:// 直读 assets，包内相对路径资源天然可解析。 */
    public static String builtinEntryUrl(String id) {
        String sid = safeBuiltinId(id);
        return sid == null ? null : "file:///android_asset/" + BUILTIN_ROOT + sid + "/index.html";
    }

    /** 获取内置应用 app.json 解析结果对象。 */
    public static JSONObject builtinAppMeta(Context c, String id) {
        String sid = safeBuiltinId(id);
        if (sid == null) return null;
        try (InputStream in = c.getAssets().open(BUILTIN_ROOT + sid + "/app.json")) {
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            byte[] buf = new byte[4096];
            int n;
            while ((n = in.read(buf)) > 0) bos.write(buf, 0, n);
            return new JSONObject(new String(bos.toByteArray(), StandardCharsets.UTF_8));
        } catch (Throwable ignored) {
            return null;
        }
    }

    /** 内置应用显示名：app.json 的 name，取不到就退回 id。 */
    public static String builtinAppName(Context c, String id) {
        JSONObject o = builtinAppMeta(c, id);
        if (o != null) {
            String nm = o.optString("name", "");
            if (!nm.isEmpty()) return nm;
        }
        String sid = safeBuiltinId(id);
        return sid != null ? sid : (id == null ? "" : id);
    }

    /** 兼容旧调用：不带渲染 PNG。 */
    public static String installHtml(Context c, String name, String html, String iconSvg) {
        return installHtml(c, name, html, iconSvg, null);
    }

    /** 安装一份 HTML 软件，返回 id；名字为空时从 &lt;title&gt; 取。png 为页面渲染好的图标 data URL。 */
    public static String installHtml(Context c, String name, String html, String iconSvg, String pngDataUrl) {
        if (html == null || html.trim().isEmpty()) return null;
        try {
            String id = "h" + System.currentTimeMillis();
            String title = (name == null ? "" : name.trim());
            if (title.isEmpty()) title = titleOf(html);
            if (title.isEmpty()) title = "未命名软件";
            title = title.length() > 16 ? title.substring(0, 16) : title;

            File f = new File(appsDir(c), id + ".html");
            try (FileOutputStream out = new FileOutputStream(f)) {
                out.write(html.getBytes(StandardCharsets.UTF_8));
            }

            JSONArray arr = new JSONArray(htmlAppsJson(c));
            JSONObject o = new JSONObject();
            o.put("id", id);
            o.put("name", title);
            o.put("at", System.currentTimeMillis());
            o.put("size", f.length());
            o.put("ori", "auto");
            if (iconSvg != null && !iconSvg.isEmpty()) o.put("icon", iconSvg);
            if (pngDataUrl != null && pngDataUrl.startsWith("data:image/")) o.put("png", pngDataUrl);
            arr.put(o);
            prefs(c).edit().putString(KEY_HTML_INDEX, arr.toString()).apply();
            return id;
        } catch (Throwable t) {
            Log.w(TAG, "installHtml: " + t.getMessage());
            return null;
        }
    }

    /** 在索引里找某个沧海软件，改它的 JSON 时用。 */
    private static JSONObject findHtml(Context c, String id) {
        try {
            JSONArray arr = new JSONArray(htmlAppsJson(c));
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.optJSONObject(i);
                if (o != null && id != null && id.equals(o.optString("id"))) return o;
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    private static boolean saveHtmlEntry(Context c, String id, JSONObject updated) {
        try {
            JSONArray arr = new JSONArray(htmlAppsJson(c));
            JSONArray out = new JSONArray();
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.optJSONObject(i);
                if (o != null && id != null && id.equals(o.optString("id"))) out.put(updated);
                else if (o != null) out.put(o);
            }
            prefs(c).edit().putString(KEY_HTML_INDEX, out.toString()).apply();
            return true;
        } catch (Throwable t) {
            Log.w(TAG, "saveHtmlEntry: " + t.getMessage());
            return false;
        }
    }

    /** 改名。返回新名字，失败返回 null。 */
    public static String renameHtml(Context c, String id, String name) {
        String t = name == null ? "" : name.trim();
        if (t.isEmpty()) return null;
        JSONObject o = findHtml(c, id);
        if (o == null) return null;
        t = t.length() > 16 ? t.substring(0, 16) : t;
        try {
            o.put("name", t);
            return saveHtmlEntry(c, id, o) ? t : null;
        } catch (Throwable e) {
            return null;
        }
    }

    /** 换图标：svg 源码 + 页面渲染好的 PNG data URL（快捷方式用）。 */
    public static boolean setHtmlIcon(Context c, String id, String iconSvg, String pngDataUrl) {
        JSONObject o = findHtml(c, id);
        if (o == null) return false;
        try {
            if (iconSvg != null && !iconSvg.isEmpty()) o.put("icon", iconSvg);
            else o.remove("icon");
            if (pngDataUrl != null && pngDataUrl.startsWith("data:image/")) o.put("png", pngDataUrl);
            return saveHtmlEntry(c, id, o);
        } catch (Throwable e) {
            return false;
        }
    }

    /** 横竖屏锁定：auto / portrait / landscape。 */
    public static boolean setHtmlOrientation(Context c, String id, String mode) {
        JSONObject o = findHtml(c, id);
        if (o == null) return false;
        String m = "landscape".equals(mode) ? "landscape"
                : ("portrait".equals(mode) ? "portrait" : "auto");
        try {
            o.put("ori", m);
            return saveHtmlEntry(c, id, o);
        } catch (Throwable e) {
            return false;
        }
    }

    /** 读某个沧海软件的横竖屏锁定，默认 auto。 */
    public static String htmlOrientation(Context c, String id) {
        JSONObject o = findHtml(c, id);
        return o == null ? "auto" : o.optString("ori", "auto");
    }

    /** 读某个沧海软件的名字。 */
    public static String htmlName(Context c, String id) {
        JSONObject o = findHtml(c, id);
        return o == null ? id : o.optString("name", id);
    }

    /** 读某个沧海软件渲染好的图标 PNG（data:image/png;base64,...），没有返回 null。 */
    public static String htmlIconPng(Context c, String id) {
        JSONObject o = findHtml(c, id);
        if (o == null) return null;
        String png = o.optString("png", "");
        return png.startsWith("data:image/") ? png : null;
    }

    public static void removeHtml(Context c, String id) {
        try {
            JSONArray arr = new JSONArray(htmlAppsJson(c));
            JSONArray out = new JSONArray();
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.optJSONObject(i);
                if (o == null) continue;
                if (id != null && id.equals(o.optString("id"))) continue;
                out.put(o);
            }
            prefs(c).edit().putString(KEY_HTML_INDEX, out.toString()).apply();
            File f = new File(appsDir(c), String.valueOf(id).replaceAll("[^A-Za-z0-9_\\-]", "_") + ".html");
            if (f.exists()) //noinspection ResultOfMethodCallIgnored
                f.delete();
        } catch (Throwable t) {
            Log.w(TAG, "removeHtml: " + t.getMessage());
        }
    }

    private static String titleOf(String html) {
        try {
            java.util.regex.Matcher m = java.util.regex.Pattern
                    .compile("<title[^>]*>([\\s\\S]*?)</title>", java.util.regex.Pattern.CASE_INSENSITIVE)
                    .matcher(html);
            if (m.find()) {
                String s = m.group(1).replaceAll("<[^>]+>", "").replaceAll("\\s+", " ").trim();
                if (!s.isEmpty()) return s;
            }
        } catch (Throwable ignored) {
        }
        return "";
    }

    // ==================================================================
    // 桌面偏好
    // ==================================================================

    public static String wallpaper(Context c) {
        return prefs(c).getString(KEY_WALLPAPER, "wp-dusk");
    }

    public static void setWallpaper(Context c, String wp) {
        prefs(c).edit().putString(KEY_WALLPAPER, wp == null ? "wp-dusk" : wp).apply();
    }

    public static int gridCols(Context c) {
        return prefs(c).getInt(KEY_GRID_COLS, 15);
    }

    public static int gridRows(Context c) {
        return prefs(c).getInt(KEY_GRID_ROWS, 7);
    }

    public static void setGrid(Context c, int cols, int rows) {
        prefs(c).edit().putInt(KEY_GRID_COLS, Math.max(4, Math.min(24, cols)))
                .putInt(KEY_GRID_ROWS, Math.max(3, Math.min(12, rows))).apply();
    }

    /** 外屏图标的摆放（格坐标），外屏自己序列化，这里只负责存取。 */
    public static String desktopItems(Context c) {
        String s = prefs(c).getString(KEY_DESKTOP_ITEMS, "");
        return s == null ? "" : s;
    }

    public static void setDesktopItems(Context c, String json) {
        prefs(c).edit().putString(KEY_DESKTOP_ITEMS, json == null ? "" : json).apply();
    }

    // ==================================================================
    // 真机应用列表
    // ==================================================================

    /**
     * 手机上能启动的应用（带 LAUNCHER 入口），按名字排序。
     * 每个都带一张 48dp 的图标 PNG（base64），外屏直接用，不必自己画。
     */
    public static String nativeAppsJson(Context c) {
        JSONArray arr = new JSONArray();
        PackageManager pm = c.getPackageManager();
        try {
            List<ApplicationInfo> list = new ArrayList<>();
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                LauncherApps la = (LauncherApps) c.getSystemService(Context.LAUNCHER_APPS_SERVICE);
                if (la != null) {
                    for (android.content.pm.LauncherActivityInfo info : la.getActivityList(null,
                            android.os.Process.myUserHandle())) {
                        if (info.getApplicationInfo() != null) list.add(info.getApplicationInfo());
                    }
                }
            }
            if (list.isEmpty()) {
                // 兜底：没有 LauncherApps 权限时退回去查 LAUNCHER intent
                android.content.Intent main = new android.content.Intent(android.content.Intent.ACTION_MAIN)
                        .addCategory(android.content.Intent.CATEGORY_LAUNCHER);
                for (android.content.pm.ResolveInfo ri : pm.queryIntentActivities(main, 0)) {
                    if (ri.activityInfo != null) list.add(ri.activityInfo.applicationInfo);
                }
            }

            java.util.LinkedHashMap<String, JSONObject> uniq = new java.util.LinkedHashMap<>();
            for (ApplicationInfo ai : list) {
                if (ai == null) continue;
                String pkg = ai.packageName;
                if (pkg == null || pkg.equals(c.getPackageName())) continue;
                if (uniq.containsKey(pkg)) continue;
                String label = String.valueOf(pm.getApplicationLabel(ai));
                JSONObject o = new JSONObject();
                o.put("id", "app:" + pkg);
                o.put("kind", "native");
                o.put("pkg", pkg);
                o.put("name", label);
                o.put("comp", launchComponent(pm, pkg));
                String icon = iconPng(pm, ai);
                if (icon != null) o.put("icon", icon);
                uniq.put(pkg, o);
            }
            List<JSONObject> values = new ArrayList<>(uniq.values());
            Collections.sort(values, new Comparator<JSONObject>() {
                @Override
                public int compare(JSONObject a, JSONObject b) {
                    return a.optString("name").compareToIgnoreCase(b.optString("name"));
                }
            });
            for (JSONObject o : values) arr.put(o);
        } catch (Throwable t) {
            Log.w(TAG, "nativeAppsJson: " + t.getMessage());
        }
        return arr.toString();
    }

    private static String launchComponent(PackageManager pm, String pkg) {
        try {
            android.content.Intent i = pm.getLaunchIntentForPackage(pkg);
            if (i != null && i.getComponent() != null) {
                return i.getComponent().getPackageName() + "/" + i.getComponent().getClassName();
            }
        } catch (Throwable ignored) {
        }
        return pkg + "/";
    }

    /** 应用图标 → 48dp PNG → data URI 片段。外屏直接塞进图标框，和真机图标一模一样。 */
    private static String iconPng(PackageManager pm, ApplicationInfo ai) {
        try {
            Drawable d = pm.getApplicationIcon(ai);
            int size = 96;
            Bitmap bmp;
            if (d instanceof BitmapDrawable && ((BitmapDrawable) d).getBitmap() != null) {
                bmp = Bitmap.createScaledBitmap(((BitmapDrawable) d).getBitmap(), size, size, true);
            } else {
                bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
                Canvas canvas = new Canvas(bmp);
                d.setBounds(0, 0, size, size);
                d.draw(canvas);
            }
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            bmp.compress(Bitmap.CompressFormat.PNG, 92, bos);
            String b64 = Base64.encodeToString(bos.toByteArray(), Base64.NO_WRAP);
            return "<img src=\"data:image/png;base64," + b64 + "\" style=\"width:100%;height:100%;object-fit:contain\">";
        } catch (Throwable t) {
            return null;
        }
    }

    public static String dockPinsJson(Context c) {
        String s = prefs(c).getString(KEY_DOCK_PINS, null);
        if (s != null && !s.isEmpty()) return s;
        try {
            String desk = desktopItems(c);
            if (desk != null && !desk.isEmpty()) {
                JSONObject o = new JSONObject(desk);
                if (o.has("dockPins")) return o.getJSONArray("dockPins").toString();
            }
        } catch (Throwable ignored) {}
        return "[]";
    }

    public static void setDockPinsJson(Context c, String json) {
        prefs(c).edit().putString(KEY_DOCK_PINS, (json == null || json.isEmpty()) ? "[]" : json).apply();
    }

    public static boolean getDockAutoHide(Context c) {
        return prefs(c).getBoolean(KEY_DOCK_AUTO_HIDE, false);
    }

    public static void setDockAutoHide(Context c, boolean on) {
        prefs(c).edit().putBoolean(KEY_DOCK_AUTO_HIDE, on).apply();
    }

    // ==================================================================
    // 一次性状态（外屏 / 手机首屏都靠它）
    // ==================================================================

    public static String stateJson(Context c) {
        return stateJsonForDisplay(c, 0);
    }

    public static String stateJsonForDisplay(Context c, int displayId) {
        JSONObject o = new JSONObject();
        try {
            o.put("apps", new JSONArray(nativeAppsJson(c)));
            o.put("htmlApps", new JSONArray(htmlAppsJson(c)));
            /* 大屏前端只看这一位：没有特权就不列安卓应用、应用台里提示一句。
               每次都现读（RootCmd 由 Activity 在前台重探），不落任何本地缓存。 */
            boolean privRoot = RootCmd.isAvailable();
            boolean privShizuku = ShizukuCmd.isShizukuReady();
            o.put("privilege", privRoot || privShizuku);
            o.put("privMode", privRoot ? "root" : (privShizuku ? "shizuku" : "none"));
            o.put("dockPins", new JSONArray(dockPinsJson(c)));
            o.put("dockAutoHide", getDockAutoHide(c));
            o.put("cursorLib", new JSONArray(cursorLibJson(c)));
            o.put("cursorCurrent", cursorCurrentForDisplay(c, displayId));
            o.put("cursorSize", cursorSizeForDisplay(c, displayId));
            o.put("cursorSpeed", (double) cursorSpeed(c));
            o.put("wallpaper", wallpaper(c));
            o.put("gridCols", gridCols(c));
            o.put("gridRows", gridRows(c));
            o.put("desktopItems", desktopItems(c));
            o.put("userName", userName(c));
            o.put("userAvatar", userAvatar(c));
            o.put("language", getLanguage(c));
            o.put("version", CANGHAI_VERSION);
        } catch (Throwable t) {
            Log.w(TAG, "stateJson: " + t.getMessage());
        }
        return o.toString();
    }

    /** 已安装、带启动入口的第三方应用数量（按包名去重，不生成图标）。 */
    public static int nativeAppCount(Context c) {
        try {
            java.util.HashSet<String> pkgs = new java.util.HashSet<>();
            LauncherApps la = (LauncherApps) c.getSystemService(Context.LAUNCHER_APPS_SERVICE);
            if (la != null) {
                for (android.content.pm.LauncherActivityInfo info :
                        la.getActivityList(null, android.os.Process.myUserHandle())) {
                    if (info.getApplicationInfo() != null) {
                        pkgs.add(info.getApplicationInfo().packageName);
                    }
                }
            }
            if (pkgs.isEmpty()) {
                android.content.Intent main = new android.content.Intent(android.content.Intent.ACTION_MAIN)
                        .addCategory(android.content.Intent.CATEGORY_LAUNCHER);
                for (android.content.pm.ResolveInfo ri :
                        c.getPackageManager().queryIntentActivities(main, 0)) {
                    if (ri.activityInfo != null) pkgs.add(ri.activityInfo.packageName);
                }
            }
            pkgs.remove(c.getPackageName());
            return pkgs.size();
        } catch (Throwable t) {
            return 0;
        }
    }

    /** 「关于沧海系统」窗口的数据：版本、机型、两个软件计数。 */
    public static String aboutInfoJson(Context c) {
        JSONObject o = new JSONObject();
        try {
            o.put("version", CANGHAI_VERSION);
            String apkVer = "";
            int apkCode = 0;
            try {
                android.content.pm.PackageInfo pi = c.getPackageManager()
                        .getPackageInfo(c.getPackageName(), 0);
                apkVer = pi.versionName == null ? "" : pi.versionName;
                apkCode = pi.versionCode;
            } catch (Throwable ignored) {
            }
            o.put("apkVersion", apkVer);
            o.put("apkCode", apkCode);
            String brand = Build.MANUFACTURER == null ? "" : Build.MANUFACTURER.trim();
            String model = Build.MODEL == null ? "" : Build.MODEL.trim();
            o.put("model", brand.equalsIgnoreCase(model) ? model : (brand + " " + model).trim());
            o.put("android", Build.VERSION.RELEASE == null ? "" : Build.VERSION.RELEASE);
            o.put("htmlApps", new JSONArray(htmlAppsJson(c)).length());
            // 只数个数：nativeAppsJson 会为每个应用生成图标 base64，打开小窗没必要
            o.put("installedApps", nativeAppCount(c));
        } catch (Throwable t) {
            Log.w(TAG, "aboutInfoJson: " + t.getMessage());
        }
        return o.toString();
    }

    /**
     * 检查更新：去 Gitee 拉 canghai.json，比较 versionCode。
     * 放在 Native 是因为桌面跑在 file:// 下，页面 fetch 会被同源策略挡住。
     * 返回 {ok, hasUpdate, local, remote, versionName, notes, url, message}
     */
    public static String checkUpdateJson(Context c) {
        JSONObject r = new JSONObject();
        HttpURLConnection conn = null;
        try {
            r.put("ok", false);
            r.put("local", CANGHAI_VERSION);
            r.put("message", "无法获取更新信息，请检测网络");
            URL u = new URL(UPDATE_URL);
            conn = (HttpURLConnection) u.openConnection();
            conn.setConnectTimeout(6000);
            conn.setReadTimeout(6000);
            conn.setRequestMethod("GET");
            conn.setUseCaches(false);
            conn.setRequestProperty("Cache-Control", "no-cache");
            conn.setRequestProperty("Accept", "application/json, text/plain, */*");
            conn.setRequestProperty("User-Agent", "CanghaiSystem/" + CANGHAI_VERSION);
            int code = conn.getResponseCode();
            if (code < 200 || code >= 300) throw new IOException("HTTP " + code);
            InputStream in = conn.getInputStream();
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            byte[] buf = new byte[4096];
            int n;
            while ((n = in.read(buf)) > 0) {
                bos.write(buf, 0, n);
                if (bos.size() > 256 * 1024) break;
            }
            in.close();
            String txt = new String(bos.toByteArray(), StandardCharsets.UTF_8).trim();
            JSONObject j = new JSONObject(txt);
            double remote = j.optDouble("versionCode", -1);
            if (remote < 0) throw new IOException("versionCode missing");
            r.put("ok", true);
            r.put("remote", remote);
            r.put("hasUpdate", remote > CANGHAI_VERSION + 1e-6);
            r.put("versionName", j.optString("versionName", String.valueOf(remote)));
            r.put("notes", j.optString("notes", ""));
            r.put("date", j.optString("date", ""));
            r.put("url", j.optString("url", ""));
            r.put("message", "");
        } catch (Throwable t) {
            Log.w(TAG, "checkUpdate: " + t.getMessage());
            try {
                r.put("ok", false);
                r.put("hasUpdate", false);
                r.put("message", "无法获取更新信息，请检测网络");
            } catch (Throwable ignored) {
            }
        } finally {
            if (conn != null) conn.disconnect();
        }
        return r.toString();
    }

    /**
     * 检查结果落盘：重置 7 天计时并记录「有没有新版本」供绿点显示。
     * 只有检查成功（ok=true）才落盘——网络失败不计时，下次启动会再试。
     * 手动检查和 7 天自动检查都走这里，行为完全一致。
     */
    public static void recordUpdateResult(Context c, String json) {
        try {
            JSONObject r = new JSONObject(json == null ? "{}" : json);
            if (!r.optBoolean("ok", false)) return;
            prefs(c).edit()
                    .putLong(KEY_UPD_LAST, System.currentTimeMillis())
                    .putBoolean(KEY_UPD_HAS, r.optBoolean("hasUpdate", false))
                    .putString(KEY_UPD_VER, r.optString("versionName", ""))
                    .apply();
        } catch (Throwable ignored) {
        }
    }

    /** 距上次成功检查是否已到 7 天。 */
    public static boolean updateCheckDue(Context c) {
        return System.currentTimeMillis() - prefs(c).getLong(KEY_UPD_LAST, 0L) >= UPDATE_CHECK_INTERVAL_MS;
    }

    /** 页面启动时拉取一次：只给「有没有新版 + 版本号」，用于点亮绿点。 */
    public static String updateHintJson(Context c) {
        JSONObject o = new JSONObject();
        try {
            o.put("hasUpdate", prefs(c).getBoolean(KEY_UPD_HAS, false));
            o.put("versionName", prefs(c).getString(KEY_UPD_VER, ""));
        } catch (Throwable ignored) {
        }
        return o.toString();
    }

    // ==================================================================
    // 跨端一次意图：手机点「投到屏幕」，外屏桌面取走后自己打开
    // ==================================================================

    public static void setPendingOpen(Context c, String appId) {
        try {
            prefs(c).edit().putString(KEY_PENDING_OPEN, appId == null ? "" : appId).apply();
        } catch (Throwable ignored) {
        }
    }

    /** 读走即清空，保证同一次意图只消费一次。 */
    public static String takePendingOpen(Context c) {
        try {
            String v = prefs(c).getString(KEY_PENDING_OPEN, "");
            if (v != null && !v.isEmpty()) prefs(c).edit().remove(KEY_PENDING_OPEN).apply();
            return v == null ? "" : v;
        } catch (Throwable t) {
            return "";
        }
    }

    private static void ensureDirs(Context c) {
        File d = appsDir(c);
        if (!d.exists()) //noinspection ResultOfMethodCallIgnored
            d.mkdirs();
    }

    /** 供调试：把当前数据概要打成一行。 */
    public static String summary(Context c) {
        try {
            return "apps=" + new JSONArray(nativeAppsJson(c)).length()
                    + " html=" + new JSONArray(htmlAppsJson(c)).length()
                    + " cursor=" + cursorCurrent(c)
                    + " grid=" + gridCols(c) + "x" + gridRows(c);
        } catch (Throwable t) {
            return "summary error: " + t.getMessage();
        }
    }

    // ==================================================================
    // 双端联动：任何一端改了应用 / 光标 / 桌面数据，另一端立刻重读唯一数据源
    // ==================================================================

    /**
     * 所有写操作（安装 / 卸载 / 改名 / 换图标 / 横竖屏 / 光标库）统一收口到这里。
     * 手机页与所有活着的沧海桌面都会重读 stateJson，不再各自存一份内存副本。
     */
    public static void notifyDataChanged(Context c) {
        try { MainActivity.notifyAppsChanged(); } catch (Throwable ignored) {}
        try { DexHomeActivity.notifyDesktopAppsChanged(); } catch (Throwable ignored) {}
    }

    // ==================================================================
    // 导出 .沧海软件：SAF 让用户选目录 / 文件名，Native 写文件
    // ==================================================================

    private static final Object EXPORT_LOCK = new Object();
    private static byte[] sExportBytes;
    private static String sExportName;
    private static java.lang.ref.WeakReference<WebView> sExportWeb;

    /** 页面把 zip 打包好以后调这里；拉起系统「另存为」，让用户自己选目录和文件名。 */
    public static boolean beginExport(final Activity a, final int reqCode,
                                      String rawName, String base64, final WebView web) {
        byte[] bytes;
        try {
            bytes = Base64.decode(base64, Base64.DEFAULT);
        } catch (Throwable t) {
            Log.w(TAG, "beginExport: base64 解不开");
            return false;
        }
        if (bytes == null || bytes.length == 0) return false;
        String name = String.valueOf(rawName == null ? "" : rawName)
                .replaceAll("[\\\\/:*?\"<>|\\r\\n]", "_").trim();
        if (name.isEmpty()) name = "沧海软件";
        if (name.length() > 48) name = name.substring(0, 48);
        synchronized (EXPORT_LOCK) {
            sExportBytes = bytes;
            sExportName = name;
            sExportWeb = new java.lang.ref.WeakReference<>(web);
        }
        boolean isZh = "zh-Hans".equals(getLanguage(a));
        String ext = isZh ? ".沧海软件" : ".canghai";
        String clean = name;
        if (clean.toLowerCase().endsWith(".沧海软件") || clean.toLowerCase().endsWith(".canghai")) {
            // already has ext
        } else {
            clean = clean + ext;
        }
        final String title = clean;
        try {
            a.runOnUiThread(() -> {
                try {
                    Intent i = new Intent(Intent.ACTION_CREATE_DOCUMENT);
                    i.addCategory(Intent.CATEGORY_OPENABLE);
                    /* 用通用二进制类型：某些 DocumentsProvider 遇到 application/zip
                       会在自定义扩展名后面再补一个 .zip，反而不方便导入 */
                    i.setType("application/octet-stream");
                    i.putExtra(Intent.EXTRA_TITLE, title);
                    try {
                        i.putExtra("android.provider.extra.INITIAL_URI", Uri.parse(
                                "content://com.android.externalstorage.documents/document/primary%3ADownload"));
                    } catch (Throwable ignored) {}
                    /* CREATE_DOCUMENT 只有 DocumentsUI 一个处理者，直接发即可；
                       再套一层 chooser 反而会让部分机型丢掉初始目录参数。 */
                    a.startActivityForResult(i, reqCode);
                } catch (Throwable t) {
                    Log.w(TAG, "beginExport: " + t.getMessage());
                    reportExport(false, "打不开系统保存界面：" + t.getMessage());
                }
            });
        } catch (Throwable t) {
            return false;
        }
        return true;
    }

    /** Activity.onActivityResult 转发到这里。 */
    public static void finishExport(final Context c, int requestCode, int expectedCode,
                                    int resultCode, final Intent data) {
        if (requestCode != expectedCode) return;
        byte[] bytes;
        String name;
        WebView web;
        synchronized (EXPORT_LOCK) {
            bytes = sExportBytes;
            name = sExportName;
            web = sExportWeb == null ? null : sExportWeb.get();
            sExportBytes = null;
            sExportName = null;
            sExportWeb = null;
        }
        if (bytes == null) return;
        if (resultCode != Activity.RESULT_OK || data == null || data.getData() == null) {
            reportExportTo(web, false, "已取消导出");
            return;
        }
        final byte[] payload = bytes;
        final String appName = name;
        final WebView target = web;
        new Thread(() -> {
            boolean ok = false;
            String err = "";
            java.io.OutputStream os = null;
            try {
                os = c.getContentResolver().openOutputStream(data.getData(), "w");
                if (os == null) throw new IOException("打不开目标文件");
                os.write(payload);
                os.flush();
                ok = true;
            } catch (Throwable t) {
                err = String.valueOf(t.getMessage());
                Log.w(TAG, "finishExport: " + t.getMessage());
            } finally {
                try { if (os != null) os.close(); } catch (Throwable ignored) {}
            }
            final boolean done = ok;
            final String msg = done
                    ? "已导出「" + appName + "」· 到你选的位置了"
                    : "导出失败：" + err;
            reportExportTo(target, done, msg);
        }, "canghai-export").start();
    }

    private static void reportExport(boolean ok, String msg) {
        WebView web;
        synchronized (EXPORT_LOCK) {
            web = sExportWeb == null ? null : sExportWeb.get();
        }
        reportExportTo(web, ok, msg);
    }

    private static void reportExportTo(WebView web, boolean ok, String msg) {
        if (web == null) return;
        try {
            JSONObject o = new JSONObject();
            o.put("ok", ok);
            o.put("msg", msg);
            final String js = "window.__chExportDone && window.__chExportDone("
                    + JSONObject.quote(o.toString()) + ");";
            web.post(() -> {
                try { web.evaluateJavascript(js, null); } catch (Throwable ignored) {}
            });
        } catch (Throwable ignored) {}
    }

    // ==================================================================
    // 共用解压：手机端 / 外屏桌面都能离线解开 .沧海软件 / .zip
    // ==================================================================

    /** 返回 {"ok":true,"files":[{"n":"路径","b":"base64"}]}，页面负责拼装。 */
    public static String unzipStreamJson(InputStream in) {
        final long MAX_ONE = 24L * 1024 * 1024;
        final long MAX_TOTAL = 64L * 1024 * 1024;
        java.util.zip.ZipInputStream zis = null;
        try {
            if (in == null) return "{\"ok\":false,\"err\":\"输入为空\"}";
            zis = new java.util.zip.ZipInputStream(new java.io.BufferedInputStream(in));
            JSONArray arr = new JSONArray();
            long total = 0;
            byte[] buf = new byte[32768];
            java.util.zip.ZipEntry e;
            while ((e = zis.getNextEntry()) != null) {
                if (e.isDirectory()) continue;
                String name = e.getName();
                if (name == null || name.isEmpty() || name.startsWith("__MACOSX")) continue;
                ByteArrayOutputStream bos = new ByteArrayOutputStream();
                long one = 0;
                int n;
                while ((n = zis.read(buf)) > 0) {
                    one += n;
                    total += n;
                    if (one > MAX_ONE) return "{\"ok\":false,\"err\":\"单个文件太大：" + name + "\"}";
                    if (total > MAX_TOTAL) return "{\"ok\":false,\"err\":\"压缩包太大，超过 64 MB\"}";
                    bos.write(buf, 0, n);
                }
                JSONObject f = new JSONObject();
                f.put("n", name);
                f.put("b", Base64.encodeToString(bos.toByteArray(), Base64.NO_WRAP));
                arr.put(f);
            }
            if (arr.length() == 0) return "{\"ok\":false,\"err\":\"这个包是空的\"}";
            JSONObject out = new JSONObject();
            out.put("ok", true);
            out.put("files", arr);
            return out.toString();
        } catch (Throwable t) {
            Log.w(TAG, "unzipStreamJson: " + t.getMessage());
            return "{\"ok\":false,\"err\":\"" + String.valueOf(t.getMessage()).replace("\"", "'") + "\"}";
        } finally {
            try { if (zis != null) zis.close(); } catch (Throwable ignored) {}
            try { if (in != null) in.close(); } catch (Throwable ignored) {}
        }
    }

    public static String unzipUriJson(Context c, String uriStr) {
        try {
            if (uriStr == null || uriStr.isEmpty()) return "{\"ok\":false,\"err\":\"没有拿到文件\"}";
            InputStream in = c.getContentResolver().openInputStream(Uri.parse(uriStr));
            if (in == null) return "{\"ok\":false,\"err\":\"打不开这个文件\"}";
            return unzipStreamJson(in);
        } catch (Throwable t) {
            Log.w(TAG, "unzipUriJson: " + t.getMessage());
            return "{\"ok\":false,\"err\":\"" + String.valueOf(t.getMessage()).replace("\"", "'") + "\"}";
        }
    }

    public static String unzipBase64Json(String base64) {
        try {
            if (base64 == null || base64.isEmpty()) return "{\"ok\":false,\"err\":\"内容为空\"}";
            int comma = base64.indexOf(',');
            if (comma >= 0) base64 = base64.substring(comma + 1);
            byte[] data = Base64.decode(base64, Base64.DEFAULT);
            return unzipStreamJson(new java.io.ByteArrayInputStream(data));
        } catch (Throwable t) {
            Log.w(TAG, "unzipBase64Json: " + t.getMessage());
            return "{\"ok\":false,\"err\":\"Base64解析失败: " + String.valueOf(t.getMessage()).replace("\"", "'") + "\"}";
        }
    }

    // ==================================================================
    // 多屏幕独立分辨率（等比例档位，绝不出黑边，按外设 ID / displayKey 持久化）
    // ==================================================================
    public static final String KEY_DISPLAY_RES_PREFIX = "pref_disp_res_";

    public static String getDisplayResolutionKey(Context c, String displayKey) {
        if (displayKey == null || displayKey.isEmpty()) displayKey = "default";
        return prefs(c).getString(KEY_DISPLAY_RES_PREFIX + displayKey, "1080p");
    }

    public static void setDisplayResolutionKey(Context c, String displayKey, String resKey) {
        if (displayKey == null || displayKey.isEmpty()) displayKey = "default";
        prefs(c).edit().putString(KEY_DISPLAY_RES_PREFIX + displayKey, (resKey == null ? "1080p" : resKey)).apply();
    }

    public static int[] getTargetResolution(int rawW, int rawH, String resKey) {
        if (rawW <= 0 || rawH <= 0) {
            rawW = 1920; rawH = 1080;
        }
        double ratio = (double) rawW / (double) rawH;
        int targetH = 1080;
        if ("360p".equalsIgnoreCase(resKey)) targetH = 360;
        else if ("720p".equalsIgnoreCase(resKey)) targetH = 720;
        else if ("1080p".equalsIgnoreCase(resKey)) targetH = 1080;
        else if ("2k".equalsIgnoreCase(resKey)) targetH = 1440;
        else if ("4k".equalsIgnoreCase(resKey)) targetH = 2160;
        else if ("8k".equalsIgnoreCase(resKey)) targetH = 4320;

        int targetW = ((int) Math.round(targetH * ratio) / 2) * 2;
        return new int[]{targetW, targetH};
    }

    public static org.json.JSONArray getResolutionOptions(int rawW, int rawH, String displayKey, Context c) {
        org.json.JSONArray arr = new org.json.JSONArray();
        if (rawW <= 0 || rawH <= 0) {
            rawW = 1920; rawH = 1080;
        }
        double ratio = (double) rawW / (double) rawH;
        String cur = getDisplayResolutionKey(c, displayKey);

        Object[][] tiers = new Object[][] {
            {360, "360p", "360p"},
            {720, "720p", "720p"},
            {1080, "1080p", "1080p"},
            {1440, "2K", "2K"},
            {2160, "4K", "4K"},
            {4320, "8K", "8K"}
        };

        for (Object[] t : tiers) {
            int h = (Integer) t[0];
            // 严格过滤：绝不展示超过原设备物理支持的虚假超高分辨率
            if (h > rawH + 20) continue;
            String key = (String) t[1];
            String label = (String) t[2];
            if (h == 1080) label = "1080p (推荐)";
            else if (Math.abs(h - rawH) <= 20) label += " (原设备)";
            int w = ((int) Math.round(h * ratio) / 2) * 2;
            try {
                JSONObject o = new JSONObject();
                o.put("key", key);
                o.put("name", label);
                o.put("width", w);
                o.put("height", h);
                o.put("desc", w + " × " + h);
                o.put("current", key.equalsIgnoreCase(cur) || (cur.isEmpty() && h == 1080) || (cur.equalsIgnoreCase("1080p") && h == 1080));
                arr.put(o);
            } catch (Throwable ignored) {}
        }
        return arr;
    }

    // ==================================================================
    // 外设与屏幕绑定偏好（按外设 ID / descriptor 独立记忆目标屏幕 displayId）
    // ==================================================================
    public static final String KEY_PERIPH_TARGET_PREFIX = "pref_periph_target_";

    public static int getPeripheralDisplay(Context c, String peripheralId, int defaultDisplayId) {
        if (c == null || peripheralId == null || peripheralId.isEmpty()) return defaultDisplayId;
        return prefs(c).getInt(KEY_PERIPH_TARGET_PREFIX + peripheralId, defaultDisplayId);
    }

    public static void setPeripheralDisplay(Context c, String peripheralId, int displayId) {
        if (c == null || peripheralId == null || peripheralId.isEmpty()) return;
        prefs(c).edit().putInt(KEY_PERIPH_TARGET_PREFIX + peripheralId, displayId).apply();
    }
}
