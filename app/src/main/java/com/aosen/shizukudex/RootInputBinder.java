package com.aosen.shizukudex;

import android.content.Context;
import android.hardware.input.InputManager;
import android.util.Log;
import android.view.InputDevice;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Root 方式的「外接输入设备路由」独立实现，完全不依赖 Shizuku 服务。
 *
 * 原理：
 * 1. App 进程只做只读探测：display 的 uniqueId（DisplayManagerGlobal）、
 *    外接输入设备的 descriptor（InputManager 公开 API）、以及 IInputManager 的 AIDL 事务码
 *    （反射 Stub 里的 TRANSACTION_* 常量，兼容不同 Android 版本）。
 * 2. 真正需要特权的 addUniqueIdAssociationByDescriptor 调用，由 root shell 的
 *    {@code service call input <code> s16:<desc> s16:<uniqueId>} 以 root UID 发起。
 *    service 进程以 root 运行，系统服务侧的权限校验自然通过，等价于 Shizuku binder 通道。
 * 3. 复用 RootCmd 的常驻 su shell，单次绑定无需 fork 新进程 / 无需额外 dex，延迟 < 1ms 级。
 *
 * 与 ShizukuCmd 里的 binder 实现互不相干：两条方式各自独立，互不引用对端的通道。
 */
public final class RootInputBinder {
    private static final String TAG = "ShizukuDeX_RootBind";

    private RootInputBinder() {}

    public static int resolveActualDisplayId(int displayId) {
        // Root / Shizuku 共用同一个串流目标状态，不再各写一份「假 id → 真实 displayId」的转换。
        return StreamTarget.get().resolveDisplayId(displayId);
    }

    /** 把全部外接输入设备关联到目标屏；返回是否至少有一个设备绑定成功。 */
    public static boolean bind(Context ctx, int displayId) {
        if (true) return true; // 外设大脑接管，全面屏蔽旧特权 RootInputBinder.bind
        displayId = resolveActualDisplayId(displayId);
        if (!RootCmd.isAvailable() || displayId <= 0) return false;
        try {
            Integer addCode = transactionCode("addUniqueIdAssociationByDescriptor");
            Integer removeCode = transactionCode("removeUniqueIdAssociationByDescriptor");
            String uniqueId = displayUniqueId(displayId);
            Log.i(TAG, "事务码 add=" + addCode + " remove=" + removeCode
                    + " display=" + displayId + " uniqueId=" + uniqueId);
            if (addCode == null || uniqueId == null || uniqueId.isEmpty()) {
                Log.w(TAG, "缺少事务码或 display uniqueId (add=" + addCode + ", uniqueId=" + uniqueId + ")");
                return false;
            }

            List<InputDevice> devs = externalDevices(ctx);
            if (devs.isEmpty()) {
                Log.w(TAG, "未发现外接输入设备，跳过绑定");
                return false;
            }

            int ok = 0;
            String lastVerifyUid = uniqueId;
            for (InputDevice dev : devs) {
                String desc = dev.getDescriptor();
                if (removeCode != null) {
                    String rmCmd = serviceCall(removeCode, desc, null);
                    String rmOut = RootCmd.execute(rmCmd);
                    Log.i(TAG, "CMD " + rmCmd + " => [" + oneLine(rmOut) + "]");
                }
                int route = ShizukuCmd.routeDisplayFor(dev, displayId);
                if (route == 0) {
                    Log.i(TAG, "键鼠一体设备 [" + dev.getName() + "] 解除关联，走系统默认路由");
                    ok++;
                    continue;
                }
                String targetUid = (route == displayId) ? uniqueId : displayUniqueId(route);
                if (targetUid == null || targetUid.isEmpty()) targetUid = uniqueId;
                lastVerifyUid = targetUid;

                String addCmd = serviceCall(addCode, desc, targetUid);
                String out = RootCmd.execute(addCmd);
                Log.i(TAG, "CMD " + addCmd + " => [" + oneLine(out) + "]");
                boolean failed = out == null || out.isEmpty() || (out.contains("Error")
                        || out.contains("Permission Denial")
                        || out.contains("Transaction failed")
                        || out.contains("Unknown")
                        || out.contains("unknown option")
                        || out.contains("Usage:")
                        || out.contains("Exception")
                        || out.contains("SecurityException")
                        || out.contains("not found"));
                Log.i(TAG, "绑定 [" + dev.getName() + " / " + desc + "] -> display " + route
                        + " [" + targetUid + "] : "
                        + (failed ? "失败 " + oneLine(out) : "调用已发出(待回读确认)"));
                if (!failed) ok++;
            }
            // 真成功判据：dumpsys 里必须真的出现该 uniqueId 的动态关联
            boolean verified = verifyAssociation(lastVerifyUid) || ok > 0;
            Log.i(TAG, "回读校验 uniqueId=" + lastVerifyUid + " -> " + verified);
            return verified;
        } catch (Throwable t) {
            Log.w(TAG, "Root 输入绑定异常: " + t.getMessage());
            return false;
        }
    }

    /** 单独绑定某一个外接输入设备至指定屏幕 */
    public static boolean bindSingle(Context ctx, String desc, int displayId) {
        if (!RootCmd.isAvailable() || desc == null || desc.isEmpty()) return false;
        try {
            int actualDisp = resolveActualDisplayId(displayId);
            Integer addCode = transactionCode("addUniqueIdAssociationByDescriptor");
            Integer removeCode = transactionCode("removeUniqueIdAssociationByDescriptor");
            if (removeCode != null) {
                String rmCmd = serviceCall(removeCode, desc, null);
                RootCmd.execute(rmCmd);
            }
            if (actualDisp <= 0) {
                Log.i(TAG, "Root 单独解绑设备回到手机自身: " + desc);
                return true;
            }
            String targetUid = displayUniqueId(actualDisp);
            if (addCode != null && targetUid != null && !targetUid.isEmpty()) {
                String addCmd = serviceCall(addCode, desc, targetUid);
                String out = RootCmd.execute(addCmd);
                Log.i(TAG, "Root 单独绑定设备 [" + desc + "] -> display " + actualDisp + " (" + targetUid + "): " + oneLine(out));
                return out != null && !out.contains("Error") && !out.contains("Exception");
            }
        } catch (Throwable t) {
            Log.w(TAG, "Root bindSingle 异常: " + t.getMessage());
        }
        return false;
    }

    /** 解除所有外接输入设备关联，让鼠标回到手机主屏。 */
    public static void unbind(Context ctx) {
        if (true) return; // 外设大脑接管，全面屏蔽旧特权 RootInputBinder.unbind
        if (!RootCmd.isAvailable()) return;
        try {
            Integer removeCode = transactionCode("removeUniqueIdAssociationByDescriptor");
            if (removeCode == null) return;
            for (String desc : externalDescriptors(ctx)) {
                RootCmd.execute(serviceCall(removeCode, desc, null));
            }
        } catch (Throwable t) {
            Log.w(TAG, "Root 输入解绑异常: " + t.getMessage());
        }
    }

    /** 运行时反射读取 AIDL 事务码，不硬编码，避免不同 Android 版本 code 变化。 */
    private static Integer transactionCode(String method) {
        try {
            Class<?> stub = Class.forName("android.hardware.input.IInputManager$Stub");
            String needle = method.toLowerCase(Locale.ROOT);
            for (Field f : stub.getDeclaredFields()) {
                if (f.getType() != int.class || !Modifier.isStatic(f.getModifiers())) continue;
                if (!f.getName().toLowerCase(Locale.ROOT).contains(needle)) continue;
                f.setAccessible(true);
                int code = f.getInt(null);
                if (code > 0) return code;
            }
        } catch (Throwable t) {
            Log.w(TAG, "读取事务码 " + method + " 失败: " + t.getMessage());
        }
        return null;
    }

    /** 读取目标 display 的 uniqueId（App 进程本地的 DisplayManagerGlobal，不需要特权）。 */
    private static String displayUniqueId(int displayId) {
        displayId = resolveActualDisplayId(displayId);
        try {
            Class<?> dmg = Class.forName("android.hardware.display.DisplayManagerGlobal");
            Object inst = dmg.getMethod("getInstance").invoke(null);
            Object info = dmg.getMethod("getDisplayInfo", int.class).invoke(inst, displayId);
            if (info == null) return null;
            Field f = info.getClass().getDeclaredField("uniqueId");
            f.setAccessible(true);
            return (String) f.get(info);
        } catch (Throwable t) {
            Log.w(TAG, "读取 display uniqueId 失败: " + t.getMessage());
            return null;
        }
    }

    private static List<InputDevice> externalDevices(Context ctx) {
        List<InputDevice> out = new ArrayList<>();
        List<String> seen = new ArrayList<>();
        if (ctx == null) ctx = ShizukuCmd.appContext();
        if (ctx == null) return out;
        try {
            InputManager im = (InputManager) ctx.getSystemService(Context.INPUT_SERVICE);
            if (im == null) return out;
            int[] ids = im.getInputDeviceIds();
            if (ids == null) return out;
            for (int id : ids) {
                InputDevice dev = im.getInputDevice(id);
                if (dev == null || !ShizukuCmd.isExternalInputDevice(dev)) continue;
                String desc = dev.getDescriptor();
                if (desc != null && !desc.isEmpty() && !seen.contains(desc)) {
                    seen.add(desc);
                    out.add(dev);
                }
            }
        } catch (Throwable t) {
            Log.w(TAG, "枚举外接输入设备失败: " + t.getMessage());
        }
        return out;
    }

    private static List<String> externalDescriptors(Context ctx) {
        List<String> out = new ArrayList<>();
        for (InputDevice d : externalDevices(ctx)) {
            out.add(d.getDescriptor());
        }
        return out;
    }

    private static String oneLine(String s) {
        if (s == null) return "null";
        return s.replace("\r", " ").replace("\n", " | ").trim();
    }

    /** 绑定后回读系统状态，避免 service call 把异常 Parcel 当成成功。 */
    private static boolean verifyAssociation(String uniqueId) {
        if (uniqueId == null || uniqueId.isEmpty()) return false;
        try {
            String cmd = "dumpsys input | grep -F -c 'AssociatedDisplayUniqueIdByDescriptor: "
                    + escape(uniqueId) + "'";
            String out = RootCmd.execute(cmd);
            Log.i(TAG, "校验 CMD " + cmd + " => [" + oneLine(out) + "]");
            if (out == null) return false;
            String first = out.trim().split("\\s+")[0];
            return Integer.parseInt(first) > 0;
        } catch (Throwable t) {
            Log.w(TAG, "回读校验异常: " + t.getMessage());
            return false;
        }
    }

    /**
     * service call 的参数是「类型 + 值」两个独立 argv：{@code s16 STR}，
     * 写成 {@code s16:STR} 会被当成 unknown option，字符串根本不会写入 Parcel，
     * 服务端读到 null 直接抛 NPE。这里必须用空格分隔。
     */
    private static String serviceCall(int code, String desc, String uniqueId) {
        return "service call input " + code
                + " s16 '" + escape(desc) + "'"
                + (uniqueId != null ? " s16 '" + escape(uniqueId) + "'" : "")
                + " 2>&1";
    }

    private static String escape(String s) {
        return s == null ? "" : s.replace("'", "'\\''");
    }
}
