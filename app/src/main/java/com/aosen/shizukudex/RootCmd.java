package com.aosen.shizukudex;

import android.content.Context;
import android.provider.Settings;
import android.util.Log;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.util.concurrent.TimeUnit;

import rikka.shizuku.Shizuku;

/**
 * 完整 Root 特权引擎（支持 Magisk / KernelSU / APatch / 常规 su）。
 *
 * 核心能力：
 * 1. 自动探测与持久会话：维护单一常驻 root shell 管道，命令执行延迟从 50ms 降至 <1ms。
 * 2. 免 Shizuku 独立运行：直接静默下发所有系统命令、权限配置与外设控制。
 * 3. 伴随启动：若手机安装了 Shizuku，可通过 root 一句话无感拉起其后台服务（无需用户打开 Shizuku App）。
 */
public final class RootCmd {
    private static final String TAG = "ShizukuDeX_Root";

    private static volatile Boolean sAvailable = null;
    private static int sProbedBootCount = -1;

    public interface RootCallback {
        void onResult(boolean isRoot);
    }

    private static Process sRootProcess = null;
    private static BufferedWriter sRootWriter = null;
    private static BufferedReader sRootReader = null;
    private static final Object sShellLock = new Object();

    private RootCmd() {}

    /** 当前开机周期内 root 是否可用（基于缓存，不阻塞） */
    public static boolean isAvailable() {
        Boolean v = sAvailable;
        return v != null && v;
    }

    /** 检查系统是否安装了 su 可执行文件 */
    public static boolean hasSuBinary() {
        String[] paths = {
                "/system/bin/su",
                "/system/xbin/su",
                "/sbin/su",
                "/data/adb/ksu/bin/su",
                "/data/adb/ap/bin/su",
                "/system/bin/failsafe/su"
        };
        for (String p : paths) {
            try {
                if (new File(p).exists()) return true;
            } catch (Throwable ignored) {}
        }
        return false;
    }

    private static int getBootCount(Context ctx) {
        if (ctx == null) return -1;
        try {
            return Settings.Global.getInt(ctx.getContentResolver(),
                    Settings.Global.BOOT_COUNT, -1);
        } catch (Throwable t) {
            return -1;
        }
    }

    /** 异步探测（App 启动时调用，首次触发 Root 管理器授权弹窗） */
    public static void probeAsync(Context ctx, RootCallback cb) {
        int boot = getBootCount(ctx);
        Boolean cached = sAvailable;
        if (cached != null && cached && boot == sProbedBootCount) {
            if (cb != null) cb.onResult(true);
            return;
        }
        new Thread(() -> {
            boolean ok = probe(ctx != null ? ctx.getApplicationContext() : null);
            if (cb != null) {
                cb.onResult(ok);
            }
        }).start();
    }

    public static void probeAsync(Context ctx) {
        probeAsync(ctx, null);
    }

    /**
     * 丢弃探测缓存。
     * 典型场景：App 启动时用户尚未在 KernelSU / Magisk 里授权，probe 记下了 false；
     * 之后用户手动授权，若不失效缓存，整个开机周期内都会误判为「无 Root」。
     */
    public static synchronized void invalidate() {
        sAvailable = null;
        sProbedBootCount = -1;
    }

    /** 强制重新探测（用户在 Root 管理器授权后、或回到前台时调用）。回调在后台线程。 */
    public static void reprobeAsync(Context ctx, RootCallback cb) {
        new Thread(() -> {
            boolean ok;
            synchronized (RootCmd.class) {
                invalidate();
                ok = probe(ctx != null ? ctx.getApplicationContext() : null);
            }
            if (cb != null) cb.onResult(ok);
        }).start();
    }

    /** 同步探测（须在后台线程调用）。返回探测后的可用性。 */
    public static synchronized boolean probe(Context ctx) {
        int boot = getBootCount(ctx);
        if (sAvailable != null && boot == sProbedBootCount) return sAvailable;
        boolean ok = false;
        try {
            String out = execOnce("id");
            ok = out != null && out.contains("uid=0");
            Log.i(TAG, "Root 探测结果: " + out + " (ok=" + ok + ")");
        } catch (Throwable t) {
            Log.i(TAG, "Root 探测失败: " + t.getMessage());
        }
        sAvailable = ok;
        sProbedBootCount = boot;
        if (ok) {
            // 初始化常驻 root shell
            ensurePersistentShell();
        }
        return ok;
    }

    /**
     * 维护一个常驻的交互式 root shell，消除每次 fork 进程的巨大开销。
     */
    private static void ensurePersistentShell() {
        synchronized (sShellLock) {
            if (sRootProcess != null) {
                try {
                    sRootProcess.exitValue();
                    // 已退出，需要重新创建
                    destroyPersistentShell();
                } catch (IllegalThreadStateException running) {
                    return; // 正在正常运行
                }
            }
            try {
                Process p = Runtime.getRuntime().exec("su");
                sRootWriter = new BufferedWriter(new OutputStreamWriter(p.getOutputStream()));
                sRootReader = new BufferedReader(new InputStreamReader(p.getInputStream()));
                sRootProcess = p;
                Log.i(TAG, "已建立常驻 Root Shell 交互管道");
            } catch (Throwable t) {
                Log.w(TAG, "建立常驻 Root Shell 失败: " + t.getMessage());
                destroyPersistentShell();
            }
        }
    }

    private static void destroyPersistentShell() {
        synchronized (sShellLock) {
            if (sRootWriter != null) {
                try { sRootWriter.close(); } catch (Throwable ignored) {}
                sRootWriter = null;
            }
            if (sRootReader != null) {
                try { sRootReader.close(); } catch (Throwable ignored) {}
                sRootReader = null;
            }
            if (sRootProcess != null) {
                try { sRootProcess.destroy(); } catch (Throwable ignored) {}
                sRootProcess = null;
            }
        }
    }

    /**
     * 以 root 身份执行一条命令，返回 stdout；无 root 或失败返回 null。
     * 优先走常驻快速管道，异常时回退到 execOnce。
     */
    public static String execute(String cmd) {
        if (cmd == null || cmd.isEmpty()) return "";
        if (!isAvailable()) return null;

        synchronized (sShellLock) {
            if (sRootProcess != null && sRootWriter != null && sRootReader != null) {
                try {
                    String marker = "__AOSEN_END_" + System.currentTimeMillis() + "__";
                    sRootWriter.write(cmd + "\necho " + marker + "\n");
                    sRootWriter.flush();

                    // readLine() 没有超时：若命令本身卡住，marker 永远不出现，
                    // 调用线程会永久阻塞，还把 sShellLock 占死，之后所有 root 命令
                    // 全部排队 —— 真机 ANR trace 里主线程就是卡在这里 10 秒。
                    // 改为独立读线程 + 2.5s 上限，超时直接销毁 shell 让读线程退出。
                    final BufferedReader reader = sRootReader;
                    final StringBuilder sb = new StringBuilder();
                    final java.util.concurrent.CountDownLatch done =
                            new java.util.concurrent.CountDownLatch(1);
                    Thread t = new Thread(() -> {
                        try {
                            String line;
                            while ((line = reader.readLine()) != null) {
                                if (line.contains(marker)) break;
                                sb.append(line).append('\n');
                            }
                        } catch (Throwable ignored) {
                        } finally {
                            done.countDown();
                        }
                    }, "AosenRootShell-read");
                    t.setDaemon(true);
                    t.start();
                    if (!done.await(2500L, java.util.concurrent.TimeUnit.MILLISECONDS)) {
                        Log.w(TAG, "常驻 shell 读超时，重建 shell: " + cmd);
                        destroyPersistentShell();
                        return null;
                    }
                    return sb.toString().trim();
                } catch (Throwable t) {
                    Log.w(TAG, "常驻 shell 执行失败，回退 execOnce: " + t.getMessage());
                    destroyPersistentShell();
                }
            }
        }

        return execOnce(cmd);
    }

    /**
     * 单次 su -c 执行（带超时防卡死）。
     */
    private static String execOnce(String cmd) {
        Process proc = null;
        try {
            final Process p = Runtime.getRuntime().exec(new String[]{"su", "-c", cmd});
            proc = p;
            final StringBuilder out = new StringBuilder();
            Thread reader = new Thread(() -> {
                try (BufferedReader r = new BufferedReader(
                        new InputStreamReader(p.getInputStream()))) {
                    String line;
                    while ((line = r.readLine()) != null) {
                        if (out.length() < 32000) out.append(line).append('\n');
                    }
                } catch (Throwable ignored) {}
            });
            reader.setDaemon(true);
            reader.start();
            if (!p.waitFor(4, TimeUnit.SECONDS)) {
                p.destroy();
                return out.length() > 0 ? out.toString() : null;
            }
            reader.join(400);
            String s = out.toString().trim();
            return s.isEmpty() ? null : s;
        } catch (Throwable t) {
            return null;
        } finally {
            if (proc != null) proc.destroy();
        }
    }

    /**
     * 通过 Root 静默授予本应用所有核心特权（完全无感，无需用户前往系统设置）。
     */
    public static void grantAppPermissionsViaRoot(Context ctx) {
        if (!isAvailable() || ctx == null) return;
        new Thread(() -> {
            String pkg = ctx.getPackageName();
            Log.i(TAG, "开始通过 Root 静默授予应用特权: " + pkg);
            execute("pm grant " + pkg + " android.permission.SYSTEM_ALERT_WINDOW");
            execute("appops set " + pkg + " SYSTEM_ALERT_WINDOW allow");
            execute("appops set " + pkg + " GET_INSTALLED_APPS allow");
            execute("appops set " + pkg + " READ_INSTALLED_PACKAGES allow");
            execute("pm grant " + pkg + " android.permission.QUERY_ALL_PACKAGES");
            execute("appops set " + pkg + " PROJECT_MEDIA allow");
            execute("appops set " + pkg + " ACCESS_RESTRICTED_SETTINGS allow");
            execute("settings put secure show_ime_with_hard_keyboard 1");
            execute("settings put global force_resizable_activities 1");
            // 不读写任何无障碍设置：外屏鼠标/点击由 Root 通道直接驱动。
            Log.i(TAG, "Root 特权配置完成");
        }).start();
    }

    /**
     * 若手机安装了 Shizuku，且尚未运行，通过 Root 一键在后台无感启动。
     */
    public static void autoStartShizukuIfNeeded() {
        if (!isAvailable()) return;
        new Thread(() -> {
            try {
                if (!Shizuku.pingBinder()) {
                    Log.i(TAG, "检测到 Root 权限，尝试无感拉起 Shizuku 核心服务...");
                    String r = execute("sh /data/user/0/moe.shizuku.privileged.api/starter 2>&1 || sh /data/data/moe.shizuku.privileged.api/starter 2>&1");
                    Log.i(TAG, "Shizuku starter 执行反馈: " + r);
                }
            } catch (Throwable t) {
                Log.w(TAG, "autoStartShizukuIfNeeded 异常: " + t.getMessage());
            }
        }).start();
    }
}
