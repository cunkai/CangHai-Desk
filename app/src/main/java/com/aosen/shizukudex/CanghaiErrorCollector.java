package com.aosen.shizukudex;

import android.content.Context;
import android.os.Environment;
import android.util.Log;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayDeque;
import java.util.Date;
import java.util.Locale;

/**
 * 沧海系统内存错误捕获与日志收集器
 * 1. 严格限制内存占用在 5MB 以内（环形缓冲区）；
 * 2. 收集手机端/大屏桌面/悬浮Dock的 WebView 控制台报错、未捕获 JS 异常以及 Java 后台 Crash；
 * 3. 手机端设置界面无错误时不展示导出按钮；有错误时可查看并一键导出 txt，导出后清空内存。
 */
public class CanghaiErrorCollector {
    private static final String TAG = "CanghaiErrorCollector";
    private static final int MAX_BYTES = 5 * 1024 * 1024; // 5MB 限制

    private static final CanghaiErrorCollector INSTANCE = new CanghaiErrorCollector();

    public static CanghaiErrorCollector getInstance() {
        return INSTANCE;
    }

    public static void logExt(String message) {
        try {
            INSTANCE.log("外屏直显", "INFO", message, "");
            Log.i("Aosen_ExtDisplay", message);
        } catch (Throwable ignored) {}
    }

    public static void logExtError(String message, Throwable t) {
        try {
            String stack = (t != null) ? "\n" + Log.getStackTraceString(t) : "";
            INSTANCE.log("外屏直显", "ERROR", message + stack, "");
            Log.e("Aosen_ExtDisplay", message + stack, t);
        } catch (Throwable ignored) {}
    }

    /* 三指针对账：诊断「自绘指针 / uinput 自算 / 系统黑箭头」三套坐标各从哪来。
     * 移动事件每秒几十帧，统一 600ms 抽样，别把内存队列冲掉；force 用于点击、
     * 窗口挂载这类低频关键事件，一条不漏。纯日志，不参与任何输入决策。 */
    private static final Object AUDIT_LOCK = new Object();
    private static long sLastPointerAudit;
    public static void pointerAudit(String tag, String detail, boolean force) {
        if (!force) {
            synchronized (AUDIT_LOCK) {
                long now = android.os.SystemClock.uptimeMillis();
                if (now - sLastPointerAudit < 600) return;
                sLastPointerAudit = now;
            }
        }
        logExt("[三指针对账/" + tag + "] " + detail);
    }

    public static String getLogcat(int maxLines, String filter) {
        StringBuilder sb = new StringBuilder();
        try {
            int count = (maxLines > 0) ? maxLines : 500;
            Process p = Runtime.getRuntime().exec(new String[]{"logcat", "-d", "-v", "time", "-t", String.valueOf(count)});
            java.io.BufferedReader reader = new java.io.BufferedReader(new java.io.InputStreamReader(p.getInputStream(), java.nio.charset.StandardCharsets.UTF_8));
            String line;
            String lowFilter = (filter != null) ? filter.toLowerCase().trim() : "";
            while ((line = reader.readLine()) != null) {
                if (lowFilter.isEmpty() || line.toLowerCase().contains(lowFilter)) {
                    sb.append(line).append("\n");
                }
            }
            reader.close();
        } catch (Throwable t) {
            sb.append("读取系统 logcat 异常: ").append(t.getMessage()).append("\n");
        }
        return sb.toString();
    }

    public static class LogEntry {
        public final long timestamp;
        public final String source; // "手机端" / "大屏桌面" / "悬浮Dock" / "系统内核"
        public final String level;  // "ERROR" / "CRASH" / "WARN"
        public final String message;
        public final String location; // 文件路径与行号
        public final int byteSize;

        public LogEntry(String source, String level, String message, String location) {
            this.timestamp = System.currentTimeMillis();
            this.source = source != null ? source : "未知";
            this.level = level != null ? level : "ERROR";
            this.message = message != null ? message : "";
            this.location = location != null ? location : "";

            // 预估单条条目的内存字节
            int size = 32; // 时间戳+对象开销
            size += this.source.length() * 2;
            size += this.level.length() * 2;
            size += this.message.length() * 2;
            size += this.location.length() * 2;
            this.byteSize = Math.max(size, 64);
        }

        public String format(SimpleDateFormat sdf) {
            String timeStr = sdf.format(new Date(timestamp));
            StringBuilder sb = new StringBuilder();
            sb.append("[").append(timeStr).append("] [")
              .append(source).append("] [")
              .append(level).append("] ");
            if (!location.isEmpty()) {
                sb.append("(").append(location).append(") ");
            }
            sb.append(message);
            return sb.toString();
        }
    }

    private final ArrayDeque<LogEntry> logQueue = new ArrayDeque<>();
    private int currentTotalBytes = 0;
    private int errorCount = 0;
    private final SimpleDateFormat dateFormat = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.getDefault());
    private boolean initialized = false;

    private CanghaiErrorCollector() {}

    /**
     * 初始化全局未捕获异常捕获
     */
    public synchronized void init(Context context) {
        if (initialized) return;
        initialized = true;

        final Thread.UncaughtExceptionHandler defaultHandler = Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler((thread, throwable) -> {
            try {
                String stackTrace = Log.getStackTraceString(throwable);
                log("系统内核", "CRASH", "线程 [" + thread.getName() + "] 发生未捕获异常: " + stackTrace, "");
            } catch (Throwable ignored) {
            }
            if (defaultHandler != null) {
                defaultHandler.uncaughtException(thread, throwable);
            }
        });
    }

    /**
     * 写入一条日志（内存循环队列，超 5MB 自动弹出最旧日志）
     */
    public synchronized void log(String source, String level, String message, String location) {
        if (message == null || message.trim().isEmpty()) return;

        LogEntry entry = new LogEntry(source, level, message, location);
        logQueue.addLast(entry);
        currentTotalBytes += entry.byteSize;
        if ("CRASH".equals(level) || "ERROR".equals(level)) {
            errorCount++;
        }

        // 超出 5MB 限制时，弹出队首较旧记录
        while (currentTotalBytes > MAX_BYTES && !logQueue.isEmpty()) {
            LogEntry removed = logQueue.removeFirst();
            currentTotalBytes -= removed.byteSize;
        }

        // 同时也打入 logcat 方便 ADB 查看
        if ("CRASH".equals(level) || "ERROR".equals(level)) {
            Log.e("CanghaiLog", "[" + source + "] " + message + (location.isEmpty() ? "" : " @" + location));
        } else {
            Log.w("CanghaiLog", "[" + source + "] " + message + (location.isEmpty() ? "" : " @" + location));
        }
    }

    /**
     * 是否存在报错
     */
    public synchronized boolean hasErrors() {
        return !logQueue.isEmpty() && errorCount > 0;
    }

    /**
     * 报错总数
     */
    public synchronized int getErrorCount() {
        return errorCount;
    }

    /**
     * 获取最近的报错摘要列表（用于手机端 UI 预览，至多 maxCount 条）
     */
    public synchronized String getRecentErrorsJson(int maxCount) {
        if (logQueue.isEmpty()) return "[]";
        org.json.JSONArray arr = new org.json.JSONArray();
        int skip = Math.max(0, logQueue.size() - maxCount);
        int i = 0;
        for (LogEntry entry : logQueue) {
            if (i++ < skip) continue;
            try {
                org.json.JSONObject obj = new org.json.JSONObject();
                String timeStr = dateFormat.format(new Date(entry.timestamp));
                obj.put("time", timeStr);
                obj.put("ts", entry.timestamp);
                obj.put("source", entry.source);
                obj.put("src", entry.source);
                obj.put("level", entry.level);
                obj.put("lvl", entry.level);
                obj.put("message", entry.message);
                obj.put("msg", entry.message);
                obj.put("location", entry.location);
                obj.put("loc", entry.location);
                arr.put(obj);
            } catch (Throwable ignored) {}
        }
        return arr.toString();
    }

    /**
     * 获取全部日志的完整文本
     */
        public synchronized String exportSummary() {
        return getAllLogsText();
    }

    public synchronized String exportLogsJson() {
        return getRecentErrorsJson(1000);
    }

        public synchronized String getFilteredLogsText(String filter) {
        StringBuilder sb = new StringBuilder();
        String lowFilter = (filter != null) ? filter.trim().toLowerCase(java.util.Locale.ROOT) : "";
        sb.append("=========================================\n");
        sb.append("      沧海系统运行与调试日志");
        if (!lowFilter.isEmpty()) {
            sb.append(" [筛选: ").append(filter).append("]");
        }
        sb.append("\n");
        sb.append("      查询时间: ").append(dateFormat.format(new java.util.Date())).append("\n");
        sb.append("      内存队列条目: ").append(logQueue.size()).append(" 条\n");
        sb.append("=========================================\n\n");

        int count = 0;
        for (LogEntry entry : logQueue) {
            String src = entry.source != null ? entry.source.toLowerCase(java.util.Locale.ROOT) : "";
            String lvl = entry.level != null ? entry.level.toLowerCase(java.util.Locale.ROOT) : "";
            String msg = entry.message != null ? entry.message.toLowerCase(java.util.Locale.ROOT) : "";
            String loc = entry.location != null ? entry.location.toLowerCase(java.util.Locale.ROOT) : "";
            if (lowFilter.isEmpty()
                    || src.contains(lowFilter)
                    || lvl.contains(lowFilter)
                    || msg.contains(lowFilter)
                    || loc.contains(lowFilter)) {
                sb.append(entry.format(dateFormat)).append("\n");
                count++;
            }
        }

        // 如果用户筛选的是特定链路（如 Moonlight / 外屏链路 Aosen_ExtDisplay），或暂无匹配项，抓取对应 logcat 补充
        if (lowFilter.contains("moonlight") || lowFilter.contains("aosen_extdisplay") || count == 0) {
            String logcatFilter = lowFilter.isEmpty() ? "canghai" : lowFilter;
            String lc = getLogcat(200, logcatFilter);
            if (lc != null && !lc.trim().isEmpty()) {
                sb.append("\n--- [系统底层 Logcat 抓取 (").append(logcatFilter).append(")] ---\n");
                sb.append(lc);
            }
        }

        return sb.toString();
    }

    public synchronized String getAllLogsText() {
        StringBuilder sb = new StringBuilder();
        sb.append("=========================================\n");
        sb.append("      沧海系统运行与界面报错导出报告       \n");
        sb.append("      导出时间: ").append(dateFormat.format(new Date())).append("\n");
        sb.append("      错误总计: ").append(errorCount).append(" 条\n");
        sb.append("=========================================\n\n");

        for (LogEntry entry : logQueue) {
            sb.append(entry.format(dateFormat)).append("\n");
        }
        return sb.toString();
    }

    /**
     * 导出为 txt 文件，并清空内存队列
     * 返回导出文件的绝对路径，若无错误或导出失败返回 null
     */
    public synchronized String exportToFileAndClear(Context context) {
        if (logQueue.isEmpty()) return null;

        String fullText = getAllLogsText();
        File exportDir = null;

        try {
            // 优先导出到外部 Downloads 目录，方便用户在手机文件管理器中直接打开
            File downloads = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS);
            if (downloads != null && (downloads.exists() || downloads.mkdirs())) {
                exportDir = downloads;
            }
        } catch (Throwable ignored) {}

        if (exportDir == null || !exportDir.canWrite()) {
            if (context != null) {
                exportDir = context.getExternalFilesDir(Environment.DIRECTORY_DOCUMENTS);
                if (exportDir == null) exportDir = context.getFilesDir();
            }
        }

        if (exportDir == null) return null;

        String fileName = "canghai_error_report_" + new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(new Date()) + ".txt";
        File file = new File(exportDir, fileName);

        try (FileOutputStream fos = new FileOutputStream(file);
             OutputStreamWriter osw = new OutputStreamWriter(fos, StandardCharsets.UTF_8)) {
            osw.write(fullText);
            osw.flush();

            // 导出成功后清空内存
            clear();
            Log.i(TAG, "报错日志已导出至: " + file.getAbsolutePath() + "，内存记录已清空");
            return file.getAbsolutePath();
        } catch (Throwable t) {
            Log.e(TAG, "导出报错日志失败: " + t.getMessage(), t);
            return null;
        }
    }

    /**
     * 清空内存日志
     */
    public synchronized void clear() {
        logQueue.clear();
        currentTotalBytes = 0;
        errorCount = 0;
    }
}
