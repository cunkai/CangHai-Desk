package com.aosen.shizukudex;

import android.content.Context;
import android.util.Log;

/**
 * 统一特权门面：上层永远只调这里的接口，不关心底层走哪条路线。
 *
 * 两条方式完全独立、互不混用，也不要求同时存在：
 *  - ROOT    ：RootCmd 常驻 su shell + RootInputBinder（service call，root UID 调隐藏 API）
 *  - SHIZUKU ：Shizuku binder 通道（IInputManager / IDisplayManager / newProcess）
 *  - NONE    ：两者都不可用
 *
 * 选路固定为 Root 优先：有 Root 就绝不依赖 Shizuku，且 Root 路径覆盖全部能力
 * （系统命令、虚拟屏、桌面、外屏鼠标/键盘路由），不需要再装或授权 Shizuku。
 */
public final class PrivilegeRoute {
    private static final String TAG = "ShizukuDeX_Route";

    public enum Mode { ROOT, SHIZUKU, NONE }

    private PrivilegeRoute() {}

    public static Mode mode() {
        if (RootCmd.isAvailable()) return Mode.ROOT;
        if (ShizukuCmd.isInputBindingAvailable()) return Mode.SHIZUKU;
        return Mode.NONE;
    }

    public static boolean hasAny() {
        return mode() != Mode.NONE;
    }

    /** 统一的 shell 命令入口：两条方式各自实现，调用方接口一致。 */
    public static String execute(String cmd) {
        switch (mode()) {
            case ROOT: {
                String r = RootCmd.execute(cmd);
                return r != null ? r : "";
            }
            case SHIZUKU:
                // ShizukuCmd.execute 内部此时不会命中 RootCmd，会走 Shizuku shell / UserService
                return ShizukuCmd.execute(cmd);
            default:
                return "";
        }
    }

    /** 统一的「把外接鼠标键盘路由到目标屏」入口。 */
    public static boolean bindExternalInput(Context ctx, int displayId) {
        // 沧海外设大脑已全面接管统一虚拟总线，屏蔽旧特权绑定
        return true;
    }

    /** 统一的「解除路由，鼠标回手机主屏」入口。 */
    public static void unbindExternalInput(Context ctx) {
        // 沧海外设大脑已全面接管统一虚拟总线，屏蔽旧特权解绑
    }
}
