package com.aosen.shizukudex;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.SystemClock;
import android.util.Log;

/**
 * 沧海系统 模式下的 HOME 键兜底（失焦自愈）。
 *
 * 为什么只能在桌面侧兜底：
 * 手柄 / 键盘的 HOME 在输入分发的 policy 阶段就被 PhoneWindowManager 直接消费，
 * 应用侧 dispatchKeyEvent、injectKey 全都收不到（真机验证：给虚拟屏注入
 * keyevent 3 之后，该屏前台立刻变成
 * com.sec.android.app.launcher/com.honeyspace.dexservice.SecondaryLauncher）。
 *
 * 做法：桌面 onPause 之后确认该屏前台窗口，是系统副屏桌面就把自己拉回来。
 *
 * 连按 HOME 的处理（重要）：
 * onPause 只在「前台 → 后台」那一次触发。连按时第一下自愈把桌面拉回后，
 * 第二下 HOME 又把系统桌面顶上来，此时桌面已经是 paused 状态，**不会再收到
 * onPause**，靠事件驱动就必然漏。所以拉回之后必须主动复核几轮：只要发现
 * 该屏前台又变成系统桌面（或整屏没焦点），立刻再拉。
 *
 * 成本控制：
 *  - 仅在「沧海桌面」模式下生效，官方桌面模式完全不走；
 *  - 用户从桌面点开应用后 1.2s 内跳过，不查 dumpsys；
 *  - 每次失焦最多探测 2 次，复核最多 2 轮（grep 后输出很小，约 100~200ms），全程后台线程；
 *  - 250ms 瞬时去重，避免同一批事件重复开线程；线程忙时挂起一次，不丢事件。
 */
public final class HomeGuard {

    private static final String TAG = "ShizukuDeX_HomeGuard";
    private static final String PREFS = "aosen_settings";
    private static final String PREF_HOME_TYPE = "pref_home_type";
    private static final int HOME_AOSEN = 1;

    /** One UI 上副屏 HOME 固定解析到这个组件（真机 dumpsys 确认）。 */
    private static final String DEX_HOME_PKG = "com.sec.android.app.launcher";

    /** 拉回后连续复核的轮数与间隔：专门对付「系统桌面刚起来又被按一下」。 */
    private static final int RECHECK_ROUNDS = 2;
    private static final long RECHECK_GAP_MS = 320L;

    /** 自愈次数上限：10 秒内最多 12 次。连按 HOME 也不至于被限流放弃，
     *  但仍能挡住「误判 → 无限拉回」的极端情况。 */
    private static final int MAX_FIRES = 12;
    private static final long FIRE_WINDOW_MS = 10000L;

    private static volatile long sLastFireAt = 0L;
    private static volatile long sLastOpenAppAt = 0L;
    private static volatile boolean sBusy = false;
    private static volatile boolean sPending = false;
    private static volatile long sWindowStart = 0L;
    private static volatile int sFiresInWindow = 0;

    private HomeGuard() {}

    /** 用户从桌面主动点开应用时调用：这段时间内的失焦不算「被 HOME 顶掉」。 */
    static void noteUserOpenedApp() {
        sLastOpenAppAt = SystemClock.uptimeMillis();
    }

    /** 桌面 onPause 时调用。 */
    static void onDesktopPaused(Context ctx, int displayId, String activityName) {
        if (ctx == null || displayId <= 0 || activityName == null) return;
        if (!isAosenHome(ctx)) return;
        if (!PrivilegeRoute.hasAny()) {
            Log.i(TAG, "跳过: 无特权通道 mode=" + PrivilegeRoute.mode());
            return;
        }

        long now = SystemClock.uptimeMillis();
        if (now - sLastOpenAppAt < 1200L) {
            Log.i(TAG, "跳过: 用户刚点开应用");
            return;
        }
        /* 只做「同一瞬间」去重：连按 HOME 的两下间隔通常在 300ms 以上，
         * 过大的防抖会把第二次直接吞掉，用户看到的就是退回原生桌面。 */
        if (now - sLastFireAt < 250L) {
            sPending = true;
            Log.i(TAG, "瞬时去重: 挂起一次待处理");
            return;
        }
        schedule(ctx.getApplicationContext(), displayId, activityName);
    }

    private static void schedule(final Context app, final int displayId, final String activityName) {
        final String pkg = app.getPackageName();
        new Thread(() -> {
            if (sBusy) {
                sPending = true;
                Log.i(TAG, "守卫线程忙: 挂起一次待处理");
                return;
            }
            sBusy = true;
            try {
                runGuard(pkg, displayId, activityName);
            } catch (InterruptedException ignored) {
                // 进程退出 / 线程被中断，直接放弃
            } catch (Throwable t) {
                Log.w(TAG, "自愈失败: " + t.getMessage());
            } finally {
                sBusy = false;
                if (sPending) {
                    sPending = false;
                    sLastFireAt = 0L;   // 挂起的这一次不再被瞬时去重挡住
                    Log.i(TAG, "补跑挂起的自愈");
                    schedule(app, displayId, activityName);
                }
            }
        }, "aosen-home-guard").start();
    }

    private static void runGuard(String pkg, int displayId, String activityName) throws InterruptedException {
        /* 只看「该屏当前 resumed 的 Activity」是不是系统副屏桌面。
         * 解析不到就当没发生 —— 宁可漏一次，也绝不能因为误判反复拉回，
         * 真机上出现过「启动台自己一直开开关关」。 */
        Thread.sleep(180L);
        String top = queryTopPkg(displayId);
        Log.i(TAG, "探测 top=" + top);
        if (top == null) return;
        if (!DEX_HOME_PKG.equals(top)) return;
        if (!allowFire()) {
            Log.i(TAG, "自愈过于频繁，本轮跳过");
            return;
        }
        pullBack(pkg, displayId, activityName, true);

        /* 连按 HOME：桌面已 paused，不会再有 onPause，只能主动复核。
         * 复核只负责把人拉回前台，不再切换启动台。 */
        for (int k = 0; k < RECHECK_ROUNDS; k++) {
            Thread.sleep(RECHECK_GAP_MS);
            String f = queryTopPkg(displayId);
            Log.i(TAG, "复核#" + (k + 1) + " top=" + f);
            if (f == null || !DEX_HOME_PKG.equals(f)) return;
            if (!allowFire()) return;
            pullBack(pkg, displayId, activityName, false);
        }
    }

    /** 8 秒窗口内最多自愈 MAX_FIRES 次。 */
    private static boolean allowFire() {
        long now = SystemClock.uptimeMillis();
        if (now - sWindowStart > FIRE_WINDOW_MS) {
            sWindowStart = now;
            sFiresInWindow = 0;
        }
        if (sFiresInWindow >= MAX_FIRES) return false;
        sFiresInWindow++;
        return true;
    }

    private static void pullBack(String pkg, int displayId, String activityName, boolean withHome) {
        sLastFireAt = SystemClock.uptimeMillis();
        Log.i(TAG, "沧海系统 被系统桌面顶掉 display=" + displayId + (withHome ? "，拉回并切换启动台" : "，复核拉回"));
        /* 必须带 --windowingMode 1（全屏）：
         * 三星 DeX 副屏的默认窗口模式是 freeform，不带这个参数拉回来的桌面
         * 会变成一个小窗口（用户反馈："沧海桌面变成了窗口"）。 */
        String cmd = "am start --display " + displayId
                + " --windowingMode 1"
                + " -f 0x30000000 -n " + pkg + "/" + activityName
                + " --ei desktop_display_id " + displayId;
        /* desktop_page=home：启动台开着就关、关着就开，连按 HOME 才有「切换」的手感。 */
        if (withHome) cmd += " --es desktop_page " + DexHomeActivity.PAGE_HOME;
        cmd += " 2>&1";
        String out = PrivilegeRoute.execute(cmd);
        Log.i(TAG, "拉回结果: " + (out == null ? "" : out.trim()));
        /* 必须把系统副屏桌面的 task 从这块屏上摘掉：
         * 否则它一直躺在栈里，下一次 HOME / 任何一次栈重建都会把它重新顶上来。 */
        clearLauncherTasks(displayId);
    }

    /** 移除该屏上的三星副屏桌面 task（SecondaryLauncher）。 */
    private static void clearLauncherTasks(int displayId) {
        try {
            String dump = PrivilegeRoute.execute("am stack list");
            if (dump == null || dump.isEmpty()) return;
            int curDisplay = -1;
            for (String raw : dump.split("\n")) {
                String line = raw.trim();
                if (line.startsWith("RootTask") && line.contains("displayId=")) {
                    curDisplay = ShizukuCmd.extractDisplayId(line);
                    continue;
                }
                if (curDisplay == displayId && line.startsWith("taskId=")
                        && line.contains("com.sec.android.app.launcher")
                        && line.contains("SecondaryLauncher")) {
                    int colon = line.indexOf(':');
                    if (colon <= "taskId=".length()) continue;
                    String tid = line.substring("taskId=".length(), colon).trim();
                    Log.i(TAG, "移除三星副屏桌面任务 taskId=" + tid);
                    PrivilegeRoute.execute("am stack remove " + tid);
                }
            }
        } catch (Throwable t) {
            Log.w(TAG, "清理三星桌面失败: " + t.getMessage());
        }
    }

    /**
     * 取该屏「当前 resumed 的 Activity」的包名。
     *
     * 用 activity 栈而不是 window focus：window focus 在多窗口/freeform 下经常
     * 短暂为空，把它当成「被顶掉」会导致误判 → 反复拉回 → 启动台自己开开关关。
     * 解析不到返回 null，调用方据此直接放弃本轮。
     */
    private static String queryTopPkg(int displayId) {
        String out;
        try {
            out = PrivilegeRoute.execute("dumpsys activity activities 2>/dev/null"
                    + " | grep -A 20 'Display #" + displayId + " ' 2>&1");
        } catch (Throwable t) {
            return null;
        }
        if (out == null || out.isEmpty()) return null;
        /* grep -A 20 会串到下一块屏：该屏没有 Activity 时，输出里跟着的
         * "Display #0" 段会被误读成这块屏的前台（真机上就是这么误判，
         * 导致以为被系统桌面顶掉 → 启动台自己开开关关）。这里先截断。 */
        int self = out.indexOf("Display #");
        int next = self >= 0 ? out.indexOf("Display #", self + 8) : -1;
        if (next > 0) out = out.substring(0, next);
        try {
            java.util.regex.Matcher m = java.util.regex.Pattern
                    .compile("topResumedActivity=ActivityRecord\\{\\S+\\s+u\\d+\\s+([\\w.]+)/")
                    .matcher(out);
            if (m.find()) return m.group(1);
        } catch (Throwable ignored) {}
        return null;
    }

    /** 与 MainActivity 共用一份设置：1 = 沧海桌面（沧海系统）。 */
    private static boolean isAosenHome(Context ctx) {
        try {
            SharedPreferences sp = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
            return sp.getInt(PREF_HOME_TYPE, 0) == HOME_AOSEN;
        } catch (Throwable t) {
            return false;
        }
    }
}
