package com.aosen.shizukudex;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import android.os.IBinder;
import android.util.Log;

/**
 * root 常驻进程回送 Binder 句柄的即时通道。
 *
 * 关键：ContentProvider.call() 在应用进程的 **Binder 线程** 上执行，不走主线程。
 * 之前用 startService 回送时，onStartCommand 排队在主线程；而虚拟屏创建
 * (surfaceCreated) 也在主线程等句柄，两者互相堵死，真机实测句柄晚到 1.97s。
 *
 * 只接收句柄，不提供任何查询/写入能力。
 */
public class RootBridgeProvider extends ContentProvider {

    private static final String TAG = "AosenRootInputBridge";
    public static final String METHOD_DELIVER = "setBridge";

    @Override
    public boolean onCreate() {
        return true;
    }

    @Override
    public Bundle call(String method, String arg, Bundle extras) {
        if (METHOD_DELIVER.equals(method) && extras != null) {
            try {
                IBinder bridge = extras.getBinder(RootBridgeLinkService.EXTRA_BINDER);
                if (bridge != null && bridge.pingBinder()) {
                    // 本 provider 运行在主进程，call() 落在 Binder 线程：即使主线程
                    // 正在 surfaceCreated / createVirtualDisplay 里等待，句柄也能即时落地。
                    RootInputBridge.setBridgeBinder(bridge);
                    Log.i(TAG, "root 桥 Binder 句柄已由主进程 Binder 线程落地");
                    Bundle ack = new Bundle();
                    ack.putBoolean("ok", true);
                    return ack;
                }
                Log.w(TAG, "收到 root 桥句柄但 binder 已失效或为空");
            } catch (Throwable t) {
                Log.w(TAG, "接收 root 桥句柄失败: " + t);
            }
        }
        return null;
    }

    @Override
    public Cursor query(Uri uri, String[] projection, String selection,
                        String[] selectionArgs, String sortOrder) {
        return null;
    }

    @Override
    public String getType(Uri uri) {
        return null;
    }

    @Override
    public Uri insert(Uri uri, ContentValues values) {
        return null;
    }

    @Override
    public int delete(Uri uri, String selection, String[] selectionArgs) {
        return 0;
    }

    @Override
    public int update(Uri uri, ContentValues values, String selection, String[] selectionArgs) {
        return 0;
    }
}
