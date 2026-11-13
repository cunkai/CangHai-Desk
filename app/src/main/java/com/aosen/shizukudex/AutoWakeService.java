package com.aosen.shizukudex;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.hardware.display.DisplayManager;
import android.hardware.usb.UsbManager;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;
import android.util.Log;
import android.view.Display;

public class AutoWakeService extends Service {

    private static final String TAG = "AutoWakeService";
    private static final String CHANNEL_ID = "aosen_autowake_channel";
    private static final int NOTIF_ID = 1001;

    private DisplayManager displayManager;
    private DisplayManager.DisplayListener displayListener;
    private BroadcastReceiver usbReceiver;

    private int currentHandledDisplayId = -1;
    private long lastWakeTime = 0L;

    @Override
    public void onCreate() {
        super.onCreate();
        if (!isAutoWakeEnabled()) {
            stopSelf();
            return;
        }
        startForegroundNotification();
        registerDisplayListener();
        registerPlugReceivers();
    }

    private boolean isAutoWakeEnabled() {
        android.content.SharedPreferences sp = getSharedPreferences("aosen_settings", Context.MODE_PRIVATE);
        return sp.getBoolean("pref_auto_wake_screen", false);
    }

    private void startForegroundNotification() {
        NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID,
                    getString(R.string.auto_wake_channel_name),
                    NotificationManager.IMPORTANCE_LOW
            );
            channel.setDescription(getString(R.string.auto_wake_channel_desc));
            if (nm != null) {
                nm.createNotificationChannel(channel);
            }
        }

        Intent intent = new Intent(this, MainActivity.class);
        PendingIntent pi = PendingIntent.getActivity(
                this, 0, intent,
                PendingIntent.FLAG_UPDATE_CURRENT | (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M ? PendingIntent.FLAG_IMMUTABLE : 0)
        );

        Notification.Builder builder = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                ? new Notification.Builder(this, CHANNEL_ID)
                : new Notification.Builder(this);

        Notification notification = builder
                .setContentTitle(getString(R.string.app_name))
                .setContentText(getString(R.string.auto_wake_service_running))
                .setSmallIcon(R.mipmap.ic_launcher)
                .setContentIntent(pi)
                .setOngoing(true)
                .build();

        startForeground(NOTIF_ID, notification);
    }

    private void registerDisplayListener() {
        displayManager = (DisplayManager) getSystemService(Context.DISPLAY_SERVICE);
        if (displayManager == null) return;

        displayListener = new DisplayManager.DisplayListener() {
            @Override
            public void onDisplayAdded(int displayId) {
                handleDisplayEvent(displayId);
            }

            @Override
            public void onDisplayRemoved(int displayId) {
                if (displayId == currentHandledDisplayId) {
                    Log.i(TAG, "外接屏幕已拔出: " + displayId);
                    currentHandledDisplayId = -1;
                }
            }

            @Override
            public void onDisplayChanged(int displayId) {}
        };

        displayManager.registerDisplayListener(displayListener, new Handler(Looper.getMainLooper()));
    }

    private void registerPlugReceivers() {
        usbReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                if (!isAutoWakeEnabled()) {
                    stopSelf();
                    return;
                }
                String action = intent.getAction();
                if ("android.intent.action.HDMI_PLUGGED".equals(action)) {
                    Log.i(TAG, "监听到HDMI/外屏物理接口事件: " + action);
                    currentHandledDisplayId = -1;
                    new Handler(Looper.getMainLooper()).postDelayed(() -> {
                        checkExistingExternalDisplays();
                    }, 800);
                }
            }
        };

        IntentFilter filter = new IntentFilter();
        filter.addAction("android.intent.action.HDMI_PLUGGED");
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(usbReceiver, filter, Context.RECEIVER_EXPORTED);
        } else {
            registerReceiver(usbReceiver, filter);
        }
    }

    private void checkExistingExternalDisplays() {
        if (displayManager == null) return;
        Display[] displays = displayManager.getDisplays();
        for (Display d : displays) {
            if (d.getDisplayId() == Display.DEFAULT_DISPLAY || d.getDisplayId() == 1) continue;
            if (StreamTarget.get().isStreamTarget(d.getDisplayId())) continue;
            if (d.getName() != null && d.getName().contains("Aosen_VD")) continue;
            handleDisplayEvent(d.getDisplayId());
            break;
        }
    }

    private void handleDisplayEvent(int displayId) {
        if (!isAutoWakeEnabled()) {
            stopSelf();
            return;
        }
        if (displayId == Display.DEFAULT_DISPLAY || displayId == 1) return;
        if (StreamTarget.get().isStreamTarget(displayId)) return;
        Display d = displayManager != null ? displayManager.getDisplay(displayId) : null;
        if (d != null && d.getName() != null && d.getName().contains("Aosen_VD")) {
            return;
        }

        if (displayId == currentHandledDisplayId) {
            Log.d(TAG, "外接屏幕已处于激活并唤醒状态: " + displayId + "，跳过");
            return;
        }
        currentHandledDisplayId = displayId;

        Log.i(TAG, "检测到外接屏幕介入: " + displayId + "，准备自动唤醒");
        wakeAndLaunch();
    }

    private synchronized void wakeAndLaunch() {
        if (!isAutoWakeEnabled()) {
            stopSelf();
            return;
        }
        long now = System.currentTimeMillis();
        if (now - lastWakeTime < 3000) {
            return; // 3 秒内防抖，杜绝重复连发
        }
        lastWakeTime = now;

        try {
            PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
            if (pm != null) {
                PowerManager.WakeLock wl = pm.newWakeLock(
                        PowerManager.FULL_WAKE_LOCK | PowerManager.ACQUIRE_CAUSES_WAKEUP | PowerManager.ON_AFTER_RELEASE,
                        "aosen:autowake"
                );
                wl.acquire(2000);
            }
        } catch (Throwable ignored) {}

        // 统一单通道极速拉起
        new Thread(() -> {
            try {
                ShizukuCmd.execute("am start -n " + BuildConfig.APPLICATION_ID + "/" + MainActivity.class.getName() + " --ez auto_black_screen true -f 0x10000000");
            } catch (Throwable t) {
                try {
                    Intent intent = new Intent(AutoWakeService.this, MainActivity.class);
                    intent.putExtra("auto_black_screen", true);
                    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
                    startActivity(intent);
                } catch (Throwable ignored) {}
            }
        }).start();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (!isAutoWakeEnabled()) {
            stopSelf();
            return START_NOT_STICKY;
        }
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        if (displayManager != null && displayListener != null) {
            displayManager.unregisterDisplayListener(displayListener);
        }
        if (usbReceiver != null) {
            try {
                unregisterReceiver(usbReceiver);
            } catch (Throwable ignored) {}
        }
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
