package com.aosen.shizukudex;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Build;

public class BootReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        if (Intent.ACTION_BOOT_COMPLETED.equals(intent.getAction())) {
            SharedPreferences sp = context.getSharedPreferences("aosen_settings", Context.MODE_PRIVATE);
            boolean autoWake = sp.getBoolean("pref_auto_wake_screen", false);
            if (autoWake) {
                Intent serviceIntent = new Intent(context, AutoWakeService.class);
                try {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        context.startForegroundService(serviceIntent);
                    } else {
                        context.startService(serviceIntent);
                    }
                } catch (Throwable ignored) {}
            }
        }
    }
}
