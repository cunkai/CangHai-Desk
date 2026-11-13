package com.aosen.shizukudex;

import android.os.RemoteException;
import android.util.Log;
import android.view.Surface;

import java.io.BufferedReader;
import java.io.InputStreamReader;

public class UserService extends IUserService.Stub {

    private static final String TAG = "ShizukuDeX_UserService";

    public UserService() {
        Log.i(TAG, "UserService instantiated in Shell UID 2000");
    }

    @Override
    public void destroy() {
        System.exit(0);
    }

    @Override
    public void exit() {
        destroy();
    }

    @Override
    public String executeCommand(String cmd) {
        StringBuilder sb = new StringBuilder();
        try {
            // 合并 stderr：am 等命令在 App UID 下的 Permission Denial 原本被丢弃，
            // 导致上层误判为执行成功。走 sh -c 也便于解析带参数的命令。
            Process process = Runtime.getRuntime().exec(
                    new String[]{"sh", "-c", cmd + " 2>&1"});
            BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()));
            String line;
            while ((line = reader.readLine()) != null) {
                sb.append(line).append("\n");
            }
            reader.close();
            process.waitFor();
            return sb.toString().trim();
        } catch (Throwable t) {
            return "ERROR: " + t.getMessage();
        }
    }

    @Override
    public int createVirtualDisplay(String name, int width, int height, int dpi, Surface surface, int flags) {
        return -1;
    }

    @Override
    public void resizeVirtualDisplay(int displayId, int width, int height, int dpi) {}

    @Override
    public void releaseVirtualDisplay(int displayId) {}

    // ==================== /dev/uinput 真鼠标 ====================
    /* /dev/uinput 在 ueventd.rc 里是 0666 root root，谁都写得动，真正的门是 SELinux：
     * untrusted_app 域没有 uinput_device 的权限，shell 域有。所以设备必须在
     * Shizuku 拉起的这个独立进程里建，不能在应用进程里建。
     * 如果这台机器的 UserService 实际跑在应用 UID 下，open 会返回 -13（EACCES），
     * 日志里能直接看到，那条路就得改走 Shizuku.newProcess。 */

    private static volatile boolean sSoLoaded = false;
    private static volatile boolean sOpened = false;
    private static volatile String sSoError = "";

    private static synchronized boolean loadUinputSo(String soPath, String apkPath) {
        if (sSoLoaded) return true;
        // 1) 直接用 APK 的 lib 目录（多数机型 shell 读得到）
        if (soPath != null && !soPath.isEmpty()) {
            try {
                System.load(soPath);
                sSoLoaded = true;
                Log.i(TAG, "uinput so 已加载: " + soPath);
                return true;
            } catch (Throwable t) {
                sSoError = "load(" + soPath + "): " + t;
            }
        }
        // 2) 从 APK 里解出来放到 shell 自己的临时目录再加载。
        //    /data/app 下的 lib 目录被 SELinux 拦是常见的，APK 本体一定可读。
        try {
            java.io.File dir = new java.io.File("/data/local/tmp/aosen_uinput");
            if (!dir.exists()) //noinspection ResultOfMethodCallIgnored
                dir.mkdirs();
            java.io.File out = new java.io.File(dir, "libaosen_uinput.so");
            String[] abis = {"arm64-v8a", "armeabi-v7a"};
            boolean extracted = false;
            java.util.zip.ZipFile zip = new java.util.zip.ZipFile(apkPath);
            try {
                for (String abi : abis) {
                    java.util.zip.ZipEntry e = zip.getEntry("lib/" + abi + "/libaosen_uinput.so");
                    if (e == null) continue;
                    java.io.InputStream is = zip.getInputStream(e);
                    java.io.FileOutputStream os = new java.io.FileOutputStream(out);
                    byte[] buf = new byte[8192];
                    int n;
                    while ((n = is.read(buf)) > 0) os.write(buf, 0, n);
                    os.close();
                    is.close();
                    extracted = true;
                    break;
                }
            } finally {
                zip.close();
            }
            if (!extracted) {
                sSoError += " | APK 里没有 libaosen_uinput.so";
                return false;
            }
            //noinspection ResultOfMethodCallIgnored
            out.setReadable(true, false);
            //noinspection ResultOfMethodCallIgnored
            out.setExecutable(true, false);
            System.load(out.getAbsolutePath());
            sSoLoaded = true;
            Log.i(TAG, "uinput so 已从 APK 解出并加载: " + out.getAbsolutePath());
            return true;
        } catch (Throwable t) {
            sSoError += " | apk 解包: " + t;
            Log.w(TAG, "uinput so 加载失败: " + sSoError);
            return false;
        }
    }

    @Override
    public int uinputOpen(String soPath, String apkPath, String name) {
        synchronized (UserService.class) {
            if (sOpened) return 0;
            if (!loadUinputSo(soPath, apkPath)) return -1000;
            try {
                int rc = UinputNative.open(name == null || name.isEmpty()
                        ? "Aosen Virtual Mouse" : name);
                sOpened = (rc == 0);
                sSoError = sOpened ? "" : ("UinputNative.open rc=" + rc);
                Log.i(TAG, "uinput open rc=" + rc + (sOpened ? " 设备已创建" : ""));
                return rc;
            } catch (Throwable t) {
                sSoError = "UinputNative.open: " + t;
                Log.w(TAG, sSoError);
                return -1001;
            }
        }
    }

    @Override
    public boolean uinputReady() {
        return sOpened;
    }

    @Override
    public int uinputMove(int dx, int dy) {
        if (!sOpened) return -1;
        try {
            if (dx != 0) UinputNative.emit(UinputNative.EV_REL, UinputNative.REL_X, dx);
            if (dy != 0) UinputNative.emit(UinputNative.EV_REL, UinputNative.REL_Y, dy);
            if (dx != 0 || dy != 0) UinputNative.sync();
            return 0;
        } catch (Throwable t) {
            sSoError = "move: " + t;
            return -2;
        }
    }


    @Override
    public int uinputButton(int button, boolean down) {
        if (!sOpened) return -1;
        try {
            UinputNative.emit(UinputNative.EV_KEY, button, down ? 1 : 0);
            UinputNative.sync();
            return 0;
        } catch (Throwable t) {
            sSoError = "button: " + t;
            return -2;
        }
    }

    @Override
    public int uinputScroll(int vScroll, int hScroll) {
        if (!sOpened) return -1;
        try {
            if (vScroll != 0) UinputNative.emit(UinputNative.EV_REL, UinputNative.REL_WHEEL, vScroll);
            if (hScroll != 0) UinputNative.emit(UinputNative.EV_REL, UinputNative.REL_HWHEEL, hScroll);
            if (vScroll != 0 || hScroll != 0) UinputNative.sync();
            return 0;
        } catch (Throwable t) {
            sSoError = "scroll: " + t;
            return -2;
        }
    }

    @Override
    public void uinputClose() {
        synchronized (UserService.class) {
            try {
                if (sSoLoaded) UinputNative.closeDevice();
            } catch (Throwable ignored) {}
            sOpened = false;
        }
    }

    @Override
    public String uinputStatus() {
        return "loaded=" + sSoLoaded + " opened=" + sOpened
                + (sSoError.isEmpty() ? "" : " err=" + sSoError);
    }
}
