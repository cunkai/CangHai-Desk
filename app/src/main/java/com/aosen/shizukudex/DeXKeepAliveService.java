package com.aosen.shizukudex;

import android.app.Service;
import android.content.Intent;
import android.os.IBinder;
import android.util.Log;

/**
 * DeX 会话守护：用户从最近任务划掉 App 卡片时，Activity.onDestroy 不保证执行，
 * 而 shell 身份创建的虚拟屏不随 UI 关闭而消失——残屏会把鼠标指针"挟持"在
 * 一块没有内容的幽灵屏上（手机上光标消失）。onTaskRemoved 是唯一可靠钩子。
 */
public class DeXKeepAliveService extends Service {

    private static final String TAG = "ShizukuDeX_KeepAlive";

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        // START_STICKY 仅保持进程优先级，让 onTaskRemoved 尽量有机会执行
        return START_STICKY;
    }

    @Override
    public void onTaskRemoved(Intent rootIntent) {
        // Moonlight 正在串流时，虚拟屏和 root 常驻桥都是画面来源：划掉主界面任务卡
        // 不能把它们一起清掉，否则客户端会瞬间断流。串流结束后用户再划卡即可完整清理。
        final boolean streaming;
        try {
            streaming = AosenMoonlightService.isHostRunning();
        } catch (Throwable t) {
            Log.w(TAG, "读取串流状态失败: " + t.getMessage());
            stopSelf();
            super.onTaskRemoved(rootIntent);
            return;
        }
        Log.w(TAG, "任务被移除：释放外设绑定与恢复系统鼠标光标，串流中=" + streaming);
        try {
            ShizukuCmd.unbindAllExternalInputFromDisplaySync();
        } catch (Throwable t) {
            Log.w(TAG, "解绑外设失败: " + t.getMessage());
        }
        try {
            ShizukuCmd.restoreSystemPointerSprite(this);
        } catch (Throwable ignored) {}
        if (!streaming) {
            try {
                ShizukuCmd.releaseAllTrackedDisplays();
            } catch (Throwable t) {
                Log.w(TAG, "释放虚拟屏失败: " + t.getMessage());
            }
            try {
                // 关闭 stdin → root 常驻进程退出，不留 daemon、不留幽灵屏。
                RootInputBridge.shutdown();
            } catch (Throwable ignored) {}
        } else {
            Log.i(TAG, "Moonlight 串流中：保留 root 桥与虚拟屏，退出串流时由虚拟屏控制器释放");
        }
        stopSelf();
        super.onTaskRemoved(rootIntent);
    }
}
