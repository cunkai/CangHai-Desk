package com.aosen.shizukudex;

import android.app.Service;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.os.IBinder;
import android.util.Log;

/**
 * 接收 root 常驻进程回送的 Binder 句柄，并转发到主进程。
 *
 * 为什么不直接让应用调 ServiceManager.getService("aosen.rootbridge")：
 * 自定义服务名在 service_contexts 里只能落到 default_android_service，而 SELinux
 * 明确拒绝 untrusted_app / shell 对它执行 { find }（真机 dmesg 可复现），只有 root UID
 * 能查到。因此改为 root 侧通过 IActivityManager.startService + Bundle.putBinder
 * 把句柄主动送进应用进程，绕开 servicemanager 的 find 检查。
 *
 * 为什么本 Service 放在 ":bridge" 独立进程：
 * startService 的 onStartCommand 一定排在进程主线程。主进程的主线程可能正被
 * surfaceCreated → createVirtualDisplay → awaitBridgeService 占住，真机实测过
 * 句柄因此晚到约 2 秒并把回调和等待互相堵死。独立进程的主线程是空闲的，能立刻收到。
 *
 * 为什么还要再转发一次：
 * RootInputBridge.deliveredBridge 是进程本地静态变量。":bridge" 进程存了没用，
 * 真正建虚拟屏的是主进程。这里用 ContentResolver.call 把句柄交给主进程的
 * RootBridgeProvider —— provider.call 跑在 Binder 线程，不受任何主线程排队影响。
 *
 * 只收句柄、不执行任何命令，收到后立即 stopSelf。
 */
public class RootBridgeLinkService extends Service {

    private static final String TAG = "AosenRootInputBridge";
    public static final String EXTRA_BUNDLE = "aosen_root_bridge_bundle";
    public static final String EXTRA_BINDER = "aosen_root_bridge";
    private static final String AUTHORITY_SUFFIX = ".aosenbridge";

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null) {
            try {
                Bundle outer = intent.getBundleExtra(EXTRA_BUNDLE);
                if (outer != null) {
                    // 兼容两种打包方式：root daemon 用外层 Bundle 套内层 Bundle，
                    // 也允许句柄直接放在外层。
                    IBinder bridge = outer.getBinder(EXTRA_BINDER);
                    Bundle inner = outer.getBundle(EXTRA_BUNDLE);
                    if (bridge == null && inner != null) bridge = inner.getBinder(EXTRA_BINDER);
                    if (bridge != null) deliver(bridge);
                }
            } catch (Throwable t) {
                Log.w(TAG, "解析 root 桥句柄失败: " + t);
            }
        }
        stopSelf();
        return START_NOT_STICKY;
    }

    private void deliver(IBinder bridge) {
        Bundle forward = new Bundle();
        forward.putBinder(EXTRA_BINDER, bridge);
        try {
            Uri uri = Uri.parse("content://" + getPackageName() + AUTHORITY_SUFFIX);
            getContentResolver().call(uri, RootBridgeProvider.METHOD_DELIVER, null, forward);
            Log.i(TAG, "root 桥 Binder 句柄已由 :bridge 进程转发到主进程");
        } catch (Throwable t) {
            // 兜底：万一 provider 路由异常，本进程仍留一份，便于诊断；主进程侧
            // 会继续走应用侧 DisplayManager 兜底，不会导致 Moonlight 无画面。
            Log.w(TAG, "转发 root 桥句柄失败: " + t);
            RootInputBridge.setBridgeBinder(bridge);
        }
    }
}
