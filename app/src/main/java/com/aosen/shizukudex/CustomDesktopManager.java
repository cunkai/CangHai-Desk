package com.aosen.shizukudex;

import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.util.Log;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * 自定义桌面包（zip）管理。
 *
 * 约定：zip 根目录必须有 index.html；CSS/JS/图片用相对路径，完全离线。
 * 若用户打包时多套了一层目录（my-desktop/index.html），这里自动剥掉这一层，
 * 避免「AI 生成的 zip 导不进来」这种最常见的失败。
 */
public final class CustomDesktopManager {

    private static final String TAG = "ShizukuDeX_CustomDesk";
    private static final long MAX_TOTAL = 20L * 1024 * 1024;   // 20MB
    private static final int MAX_ENTRIES = 600;
    private static final String DIR = "custom_desktop";

    private CustomDesktopManager() {}

    public static File dir(Context c) {
        return new File(c.getFilesDir(), DIR);
    }

    public static boolean has(Context c) {
        return new File(dir(c), "index.html").isFile();
    }

    public static String entryUrl(Context c) {
        return "file://" + new File(dir(c), "index.html").getAbsolutePath() + "?role=desktop";
    }

    /** 从系统文件选择器返回的 Uri 解压安装；成功返回 true。 */
    public static boolean install(Context c, Uri uri) {
        File root = dir(c);
        File staging = new File(c.getFilesDir(), DIR + "_tmp");
        deleteRecursively(staging);
        if (!staging.mkdirs()) return false;

        List<String> htmlPaths = new ArrayList<>();
        long total = 0;
        int count = 0;
        ZipInputStream zis = null;
        try {
            InputStream in = c.getContentResolver().openInputStream(uri);
            if (in == null) return false;
            zis = new ZipInputStream(in);
            ZipEntry e;
            byte[] buf = new byte[8192];
            while ((e = zis.getNextEntry()) != null) {
                if (++count > MAX_ENTRIES) throw new IllegalStateException("文件数量过多");
                if (e.isDirectory()) continue;
                String name = e.getName().replace('\\', '/');
                // 防 Zip Slip：禁止绝对路径与 .. 越界
                while (name.startsWith("/")) name = name.substring(1);
                if (name.contains("..") || name.startsWith("data:")) continue;
                long size = e.getSize();
                if (size > MAX_TOTAL) throw new IllegalStateException("单个文件过大");
                File out = new File(staging, name);
                String canon = out.getCanonicalPath();
                if (!canon.startsWith(staging.getCanonicalPath() + File.separator)) continue;
                File parent = out.getParentFile();
                if (parent != null && !parent.exists() && !parent.mkdirs()) continue;

                OutputStream os = new FileOutputStream(out);
                int n;
                while ((n = zis.read(buf)) > 0) {
                    total += n;
                    if (total > MAX_TOTAL) { os.close(); throw new IllegalStateException("压缩包超过 20MB"); }
                    os.write(buf, 0, n);
                }
                os.close();
                zis.closeEntry();
                if (name.toLowerCase().endsWith(".html") || name.toLowerCase().endsWith(".htm")) {
                    htmlPaths.add(name);
                }
            }
        } catch (Throwable t) {
            Log.w(TAG, "解压失败: " + t.getMessage(), t);
            deleteRecursively(staging);
            return false;
        } finally {
            if (zis != null) try { zis.close(); } catch (Throwable ignored) {}
        }

        // 找到 index.html；若被套在一层目录里，把该层当作根
        String index = null;
        for (String p : htmlPaths) {
            String tail = p.substring(p.lastIndexOf('/') + 1).toLowerCase();
            if (tail.equals("index.html") || tail.equals("index.htm")) {
                if (index == null || p.split("/").length < index.split("/").length) index = p;
            }
        }
        if (index == null) {
            Log.w(TAG, "zip 中没有 index.html");
            deleteRecursively(staging);
            return false;
        }
        int slash = index.lastIndexOf('/');
        String prefix = slash < 0 ? "" : index.substring(0, slash + 1);
        File base = prefix.isEmpty() ? staging : new File(staging, prefix);

        deleteRecursively(root);
        if (!base.renameTo(root)) {
            // renameTo 跨目录可能失败，退化成逐文件复制
            if (!copyRecursively(base, root)) {
                Log.w(TAG, "安装目录写入失败");
                deleteRecursively(staging);
                return false;
            }
        }
        deleteRecursively(staging);
        // 若入口叫 index.htm，补一个 index.html
        File html = new File(root, "index.html");
        if (!html.isFile()) {
            File htm = new File(root, "index.htm");
            if (htm.isFile()) htm.renameTo(html);
        }
        try {
            c.getContentResolver().takePersistableUriPermission(uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION);
        } catch (Throwable ignored) {}
        Log.i(TAG, "自定义桌面安装完成: " + entryUrl(c));
        return has(c);
    }

    public static void reset(Context c) {
        deleteRecursively(dir(c));
    }

    private static boolean copyRecursively(File src, File dst) {
        try {
            if (src.isDirectory()) {
                if (!dst.exists() && !dst.mkdirs()) return false;
                File[] kids = src.listFiles();
                if (kids != null) for (File k : kids) {
                    if (!copyRecursively(k, new File(dst, k.getName()))) return false;
                }
                return true;
            }
            File p = dst.getParentFile();
            if (p != null && !p.exists() && !p.mkdirs()) return false;
            InputStream in = new java.io.FileInputStream(src);
            OutputStream os = new FileOutputStream(dst);
            byte[] b = new byte[8192];
            int n;
            while ((n = in.read(b)) > 0) os.write(b, 0, n);
            in.close(); os.close();
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    private static void deleteRecursively(File f) {
        if (f == null || !f.exists()) return;
        if (f.isDirectory()) {
            File[] kids = f.listFiles();
            if (kids != null) for (File k : kids) deleteRecursively(k);
        }
        //noinspection ResultOfMethodCallIgnored
        f.delete();
    }
}
