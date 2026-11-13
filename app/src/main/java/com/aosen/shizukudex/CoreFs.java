package com.aosen.shizukudex;

import android.content.Context;
import android.os.StatFs;
import android.util.Base64;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/**
 * 沧海文件沙箱：一个应用一个目录，路径只按 appId 寻址。
 *
 * 根目录固定为 <getExternalFilesDir(null)>/Canghai/Data/<appId>/，
 * 手机端与大屏端是同一个进程、同一个目录，天然读同一份数据，不需要同步。
 *
 * 前端契约见 assets/canghai/shared/core.js + desktop/js/canghai-core.js：
 * invoke('fs', method, {appId, path, ...})，方法名与返回结构以那两份文件为准。
 *
 * 安全边界有两道：
 *   1) safeAppId 只放行 [a-zA-Z0-9._-]，且拒绝 "." / ".."；
 *   2) resolve() 取 canonical path 后必须落在 appDir 内，符号链接与 .. 都逃不出去。
 * 注意：appId 的可信度由 CanghaiCore 的实例绑定决定，这里只做格式与路径校验。
 */
final class CoreFs {

    private static final String TAG = "CoreFs";
    private static final long MAX_TEXT = 8L * 1024 * 1024;
    private static final long MAX_BASE64 = 24L * 1024 * 1024;

    private final Context ctx;

    CoreFs(Context context) {
        ctx = context.getApplicationContext();
    }

    /** 带错误码的失败，交给 CanghaiCore 统一包成 {ok:false,error}。 */
    static final class Err extends Exception {
        final String code;

        Err(String code) {
            super(code);
            this.code = code;
        }

        Err(String code, String message) {
            super(message);
            this.code = code;
        }
    }

    /** 应用 ID 白名单，与桌面侧 chValidAppId 保持一致。 */
    static String safeAppId(String raw) throws Err {
        if (raw == null || raw.isEmpty()) return "desktop";
        if (raw.equals(".") || raw.equals("..")) throw new Err("INVALID_ARGS", "非法应用 ID");
        if (!raw.matches("[a-zA-Z0-9._-]+")) throw new Err("INVALID_ARGS", "非法应用 ID");
        return raw;
    }

    private File rootDir() {
        File ext = ctx.getExternalFilesDir(null);
        File base = (ext != null) ? ext : ctx.getFilesDir();
        File root = new File(base, "Canghai");
        if (!root.exists() && !root.mkdirs()) Log.w(TAG, "根目录创建失败: " + root);
        return root;
    }

    private File dataRoot() {
        File dir = new File(rootDir(), "Data");
        if (!dir.exists() && !dir.mkdirs()) Log.w(TAG, "Data 目录创建失败: " + dir);
        return dir;
    }

    private File appDir(String appId) throws Err {
        File dir = new File(dataRoot(), safeAppId(appId));
        if (!dir.exists() && !dir.mkdirs()) {
            throw new Err("IO_ERROR", "沙盒目录创建失败");
        }
        return dir;
    }

    /** 把前端给的相对路径解析成沙盒内的真实文件，越界直接拒绝。 */
    private File resolve(String appId, String path) throws Err {
        try {
            File base = appDir(appId).getCanonicalFile();
            String rel = path == null ? "" : path;
            File target = new File(base, rel).getCanonicalFile();
            String bp = base.getPath();
            String tp = target.getPath();
            if (!tp.equals(bp) && !tp.startsWith(bp + File.separator)) {
                throw new Err("PERMISSION_DENIED", "路径越出沙盒");
            }
            return target;
        } catch (java.io.IOException e) {
            throw new Err("IO_ERROR", e.getMessage());
        }
    }

    private static JSONObject entry(File f) throws Exception {
        JSONObject o = new JSONObject();
        o.put("name", f.getName());
        o.put("dir", f.isDirectory());
        o.put("size", f.isDirectory() ? 0L : f.length());
        o.put("mtime", f.lastModified());
        return o;
    }

    private static long dirSize(File f) {
        if (f == null || !f.exists()) return 0L;
        if (f.isFile()) return f.length();
        File[] kids = f.listFiles();
        if (kids == null) return 0L;
        long n = 0;
        for (File k : kids) n += dirSize(k);
        return n;
    }

    private static void atomicWrite(File target, byte[] bytes) throws Err {
        File parent = target.getParentFile();
        if (parent != null && !parent.exists() && !parent.mkdirs()) {
            throw new Err("IO_ERROR", "父目录创建失败");
        }
        File tmp = new File(parent, target.getName() + ".tmp");
        FileOutputStream out = null;
        try {
            out = new FileOutputStream(tmp, false);
            out.write(bytes);
            out.flush();
            out.close();
            out = null;
            if (target.exists() && !target.delete()) {
                throw new Err("IO_ERROR", "旧文件删除失败");
            }
            if (!tmp.renameTo(target)) {
                throw new Err("IO_ERROR", "原子替换失败");
            }
        } catch (Err e) {
            throw e;
        } catch (Exception e) {
            throw new Err("IO_ERROR", e.getMessage());
        } finally {
            if (out != null) try { out.close(); } catch (Exception ignored) {}
            if (tmp.exists() && !tmp.delete()) Log.w(TAG, "临时文件清理失败: " + tmp);
        }
    }

    private static byte[] readBytes(File f, long max) throws Err {
        if (!f.exists() || f.isDirectory()) throw new Err("NOT_FOUND", "文件不存在");
        long len = f.length();
        if (len > max) throw new Err("TOO_LARGE", "文件超过大小上限");
        if (len > Integer.MAX_VALUE - 8) throw new Err("TOO_LARGE", "文件过大");
        FileInputStream in = null;
        try {
            in = new FileInputStream(f);
            byte[] buf = new byte[(int) len];
            int off = 0;
            while (off < buf.length) {
                int n = in.read(buf, off, buf.length - off);
                if (n < 0) break;
                off += n;
            }
            return Arrays.copyOf(buf, off);
        } catch (Exception e) {
            throw new Err("IO_ERROR", e.getMessage());
        } finally {
            if (in != null) try { in.close(); } catch (Exception ignored) {}
        }
    }

    private static void deleteRecursive(File f) throws Err {
        if (f == null || !f.exists()) return;
        if (f.isDirectory()) {
            File[] kids = f.listFiles();
            if (kids != null) for (File k : kids) deleteRecursive(k);
        }
        if (!f.delete()) throw new Err("IO_ERROR", "删除失败: " + f.getName());
    }

    private static void copyRecursive(File from, File to, boolean overwrite) throws Err {
        if (!from.exists()) throw new Err("NOT_FOUND", "源文件不存在");
        if (to.exists() && !overwrite) throw new Err("ALREADY_EXISTS", "目标已存在");
        if (from.isDirectory()) {
            if (!to.exists() && !to.mkdirs()) throw new Err("IO_ERROR", "目录创建失败");
            File[] kids = from.listFiles();
            if (kids != null) {
                for (File k : kids) copyRecursive(k, new File(to, k.getName()), true);
            }
            return;
        }
        File parent = to.getParentFile();
        if (parent != null && !parent.exists() && !parent.mkdirs()) {
            throw new Err("IO_ERROR", "目录创建失败");
        }
        if (to.exists() && !to.delete()) throw new Err("IO_ERROR", "目标覆盖失败");
        FileInputStream in = null;
        FileOutputStream out = null;
        try {
            in = new FileInputStream(from);
            out = new FileOutputStream(to, false);
            byte[] buf = new byte[64 * 1024];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            out.flush();
        } catch (Exception e) {
            throw new Err("IO_ERROR", e.getMessage());
        } finally {
            if (in != null) try { in.close(); } catch (Exception ignored) {}
            if (out != null) try { out.close(); } catch (Exception ignored) {}
        }
    }

    /** fs 命名空间分发。返回值由 CanghaiCore 包成 {ok:true,data}。 */
    Object handle(String method, JSONObject p) throws Err {
        if (method == null) throw new Err("INVALID_ARGS", "缺少方法名");
        String appId = safeAppId(p == null ? null : p.optString("appId", ""));
        String path = p == null ? "" : p.optString("path", "");

        try {
            switch (method) {
                case "root": {
                    JSONObject o = new JSONObject();
                    o.put("mode", "internal");
                    o.put("path", rootDir().getAbsolutePath());
                    o.put("uri", "");
                    o.put("internalPath", rootDir().getAbsolutePath());
                    o.put("dataPath", dataRoot().getAbsolutePath());
                    o.put("moving", false);
                    return o;
                }
                case "setRootMode": {
                    String mode = p == null ? "" : p.optString("mode", "");
                    if (!"internal".equals(mode)) {
                        throw new Err("EXTERNAL_ROOT_PENDING", "外置根目录尚未接入");
                    }
                    CanghaiCore.emit("fs:root", rootInfoEnvelope());
                    return new JSONObject().put("started", false).put("from", "internal").put("to", "internal");
                }
                case "pickRoot":
                    throw new Err("EXTERNAL_ROOT_PENDING", "外置根目录尚未接入");

                case "appDir":
                    return new JSONObject().put("path", appDir(appId).getAbsolutePath());

                case "list": {
                    File dir = resolve(appId, path);
                    if (!dir.exists()) return new JSONArray();
                    if (!dir.isDirectory()) throw new Err("NOT_A_DIRECTORY", "目标不是目录");
                    File[] kids = dir.listFiles();
                    if (kids == null) return new JSONArray();
                    List<File> files = new ArrayList<>(Arrays.asList(kids));
                    Collections.sort(files, new Comparator<File>() {
                        @Override
                        public int compare(File a, File b) {
                            if (a.isDirectory() != b.isDirectory()) return a.isDirectory() ? -1 : 1;
                            return a.getName().compareToIgnoreCase(b.getName());
                        }
                    });
                    JSONArray arr = new JSONArray();
                    for (File f : files) arr.put(entry(f));
                    return arr;
                }
                case "stat": {
                    File f = resolve(appId, path);
                    if (!f.exists()) throw new Err("NOT_FOUND", "文件不存在");
                    return entry(f);
                }
                case "exists":
                    return resolve(appId, path).exists();

                case "mkdir": {
                    File f = resolve(appId, path);
                    if (f.exists()) return f.isDirectory();
                    if (!f.mkdirs()) throw new Err("IO_ERROR", "创建目录失败");
                    return true;
                }
                case "delete": {
                    File f = resolve(appId, path);
                    File base = appDir(appId).getCanonicalFile();
                    if (f.getCanonicalFile().equals(base)) {
                        throw new Err("PERMISSION_DENIED", "不能删除沙盒根目录");
                    }
                    boolean recursive = p != null && p.optBoolean("recursive", false);
                    if (f.isDirectory() && !recursive) {
                        File[] kids = f.listFiles();
                        if (kids != null && kids.length > 0) {
                            throw new Err("NOT_EMPTY", "目录非空");
                        }
                    }
                    deleteRecursive(f);
                    return true;
                }
                case "rename": {
                    File from = resolve(appId, path);
                    File to = resolve(appId, p == null ? "" : p.optString("to", ""));
                    if (!from.exists()) throw new Err("NOT_FOUND", "源文件不存在");
                    if (to.exists() && !to.delete()) throw new Err("IO_ERROR", "目标已存在");
                    if (!from.renameTo(to)) throw new Err("IO_ERROR", "改名失败");
                    return true;
                }
                case "copy": {
                    File from = resolve(appId, path);
                    File to = resolve(appId, p == null ? "" : p.optString("to", ""));
                    copyRecursive(from, to, p != null && p.optBoolean("overwrite", false));
                    return true;
                }
                case "move": {
                    File from = resolve(appId, path);
                    File to = resolve(appId, p == null ? "" : p.optString("to", ""));
                    if (!from.exists()) throw new Err("NOT_FOUND", "源文件不存在");
                    if (to.exists()) {
                        if (p == null || !p.optBoolean("overwrite", false)) {
                            throw new Err("ALREADY_EXISTS", "目标已存在");
                        }
                        deleteRecursive(to);
                    }
                    if (!from.renameTo(to)) {
                        copyRecursive(from, to, false);
                        deleteRecursive(from);
                    }
                    return true;
                }
                case "readText": {
                    long max = p == null ? MAX_TEXT : p.optLong("max", MAX_TEXT);
                    if (max <= 0 || max > MAX_TEXT) max = MAX_TEXT;
                    byte[] b = readBytes(resolve(appId, path), max);
                    return new String(b, StandardCharsets.UTF_8);
                }
                case "writeText": {
                    File f = resolve(appId, path);
                    byte[] data = (p == null ? "" : p.optString("text", "")).getBytes(StandardCharsets.UTF_8);
                    if (p != null && p.optBoolean("append", false) && f.exists()) {
                        RandomAccessFile raf = null;
                        try {
                            raf = new RandomAccessFile(f, "rw");
                            raf.seek(raf.length());
                            raf.write(data);
                        } finally {
                            if (raf != null) try { raf.close(); } catch (Exception ignored) {}
                        }
                    } else {
                        atomicWrite(f, data);
                    }
                    return new JSONObject().put("size", f.length());
                }
                case "readBase64": {
                    long max = p == null ? MAX_BASE64 : p.optLong("max", MAX_BASE64);
                    if (max <= 0 || max > MAX_BASE64) max = MAX_BASE64;
                    byte[] b = readBytes(resolve(appId, path), max);
                    return Base64.encodeToString(b, Base64.NO_WRAP);
                }
                case "writeBase64": {
                    File f = resolve(appId, path);
                    byte[] data;
                    try {
                        data = Base64.decode(p == null ? "" : p.optString("base64", ""), Base64.DEFAULT);
                    } catch (IllegalArgumentException e) {
                        throw new Err("INVALID_ARGS", "base64 非法");
                    }
                    atomicWrite(f, data);
                    return new JSONObject().put("size", f.length());
                }
                case "listApps": {
                    File[] kids = dataRoot().listFiles();
                    JSONArray arr = new JSONArray();
                    if (kids == null) return arr;
                    for (File f : kids) {
                        if (!f.isDirectory()) continue;
                        JSONObject o = new JSONObject();
                        o.put("appId", f.getName());
                        o.put("size", dirSize(f));
                        o.put("mtime", f.lastModified());
                        arr.put(o);
                    }
                    return arr;
                }
                case "storage": {
                    StatFs st = new StatFs(dataRoot().getAbsolutePath());
                    JSONObject o = new JSONObject();
                    o.put("path", dataRoot().getAbsolutePath());
                    o.put("free", st.getAvailableBytes());
                    o.put("total", st.getTotalBytes());
                    o.put("dataSize", dirSize(dataRoot()));
                    return o;
                }
                default:
                    throw new Err("UNKNOWN_METHOD", "未知的 fs 方法: " + method);
            }
        } catch (Err e) {
            throw e;
        } catch (Exception e) {
            Log.w(TAG, "fs." + method + " 失败", e);
            throw new Err("IO_ERROR", e.getMessage());
        }
    }

    private JSONObject rootInfoEnvelope() {
        JSONObject o = new JSONObject();
        try {
            o.put("status", "done");
            o.put("mode", "internal");
            o.put("path", rootDir().getAbsolutePath());
            o.put("uri", "");
        } catch (Exception ignored) {}
        return o;
    }
}
