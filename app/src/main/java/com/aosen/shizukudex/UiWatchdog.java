package com.aosen.shizukudex;

import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;

/**
 * UI 线程卡顿取证。
 *
 * <p>为什么需要它：17:45:41 按下、51.98 才抬手，中间整整 10 秒没有任何日志，
 * 随后系统弹「应用无响应」。这种形状是「主线程卡死」，但卡在哪一行靠猜没用 ——
 * 我们的输入回调、WebView、JS 桥、su 命令全在主线程上，任何一处同步等待都会这样。
 *
 * <p>做法：主线程每秒打一次心跳；另有看门线程发现心跳超过 3 秒没更新，
 * 就直接把主线程栈的前若干帧打进日志。下一次复现就能一眼看到凶手。
 */
public final class UiWatchdog {

    private static volatile long sBeat;
    private static volatile boolean sStarted;
    private static long sLastDump;

    private UiWatchdog() {}

    public static void start() {
        if (sStarted) return;
        sStarted = true;
        // 先把心跳置成「现在」，否则看门线程第一圈读到的是 0，会报「阻塞 6 万秒」这种假警报
        sBeat = SystemClock.uptimeMillis();
        final Handler h = new Handler(Looper.getMainLooper());
        h.post(new Runnable() {
            @Override
            public void run() {
                sBeat = SystemClock.uptimeMillis();
                h.postDelayed(this, 800);
            }
        });

        Thread t = new Thread(() -> {
            while (true) {
                try {
                    Thread.sleep(1000);
                } catch (InterruptedException ignored) {
                    return;
                }
                long now = SystemClock.uptimeMillis();
                long quiet = now - sBeat;
                if (quiet < 3000) continue;
                if (now - sLastDump < 8000) continue;
                sLastDump = now;
                dumpMain(quiet);
            }
        }, "ui-watchdog");
        t.setDaemon(true);
        t.start();
    }

    private static void dumpMain(long quietMs) {
        StringBuilder sb = new StringBuilder();
        sb.append("[卡顿] 主线程已阻塞 ").append(quietMs / 1000).append(" 秒\n");
        try {
            StackTraceElement[] st = null;
            for (java.util.Map.Entry<Thread, StackTraceElement[]> e
                    : Thread.getAllStackTraces().entrySet()) {
                if (e.getKey().getId() == Looper.getMainLooper().getThread().getId()) {
                    st = e.getValue();
                    break;
                }
            }
            if (st == null) {
                st = Looper.getMainLooper().getThread().getStackTrace();
            }
            int n = 0;
            for (StackTraceElement el : st) {
                if (n++ >= 14) break;
                sb.append("  ").append(el.toString()).append('\n');
            }
        } catch (Throwable t) {
            sb.append("  取栈失败: ").append(t).append('\n');
        }
        CanghaiErrorCollector.logExt(sb.toString());
    }
}
