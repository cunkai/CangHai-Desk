package com.aosen.shizukudex;

/**
 * librime 的 Java 门面。原生实现见 {@code app/src/main/cpp/rime_jni.cpp}。
 *
 * <p>只做三件事：初始化一次、喂按键、把候选（JSON 字符串）交给上层。
 * 初始化会跑一次部署（把 yaml 方案编译成词典），所以要放在子线程里调。</p>
 *
 * <p>目前还没接 UI：键盘页的拼音候选栏还没做，见 README 里的下一步。</p>
 */
public final class RimeBridge {

    private static final String TAG = "ShizukuDeX_Rime";
    private static volatile boolean sLoaded;
    private static volatile boolean sInited;

    static {
        try {
            System.loadLibrary("canghairime");
            sLoaded = true;
        } catch (Throwable t) {
            android.util.Log.w(TAG, "加载 libcanghairime.so 失败: " + t);
            sLoaded = false;
        }
    }

    private RimeBridge() {
    }

    public static boolean available() {
        return sLoaded;
    }

    public static String version() {
        if (!sLoaded) return "unavailable";
        try {
            return nativeVersion();
        } catch (Throwable t) {
            return "unavailable";
        }
    }

    /**
     * 初始化引擎。第一次会部署词典，可能要几秒到几十秒，必须在子线程调用。
     *
     * @param sharedDir 只读方案数据目录（assets 解压出来的）
     * @param userDir   用户词典目录（可写）
     * @param logDir    日志目录
     */
    public static boolean init(String sharedDir, String userDir, String logDir) {
        if (!sLoaded) return false;
        if (sInited) return true;
        try {
            sInited = nativeInit(sharedDir, userDir, logDir);
            return sInited;
        } catch (Throwable t) {
            android.util.Log.w(TAG, "init 失败: " + t);
            return false;
        }
    }

    public static boolean ready() {
        return sLoaded && sInited;
    }

    public static long newSession() {
        if (!sLoaded || !sInited) return 0L;
        try {
            return nativeCreateSession();
        } catch (Throwable t) {
            return 0L;
        }
    }

    public static void destroySession(long sid) {
        if (!sLoaded || sid == 0L) return;
        try {
            nativeDestroySession(sid);
        } catch (Throwable ignored) {
        }
    }

    /** 喂一个按键，返回 JSON：{"ok":true,"commit":"","preedit":"ni","sel":0,"cands":[...]} */
    public static String processKey(long sid, int keycode, int mask) {
        if (!sLoaded || sid == 0L) return "{\"ok\":false}";
        try {
            return nativeProcessKey(sid, keycode, mask);
        } catch (Throwable t) {
            return "{\"ok\":false}";
        }
    }

    public static String select(long sid, int index) {
        if (!sLoaded || sid == 0L) return "{\"ok\":false}";
        try {
            return nativeSelect(sid, index);
        } catch (Throwable t) {
            return "{\"ok\":false}";
        }
    }

    public static void reset(long sid) {
        if (!sLoaded || sid == 0L) return;
        try {
            nativeReset(sid);
        } catch (Throwable ignored) {
        }
    }

    private static native String nativeVersion();

    private static native boolean nativeInit(String sharedDir, String userDir, String logDir);

    private static native boolean nativeReady();

    private static native long nativeCreateSession();

    private static native void nativeDestroySession(long sid);

    private static native String nativeProcessKey(long sid, int keycode, int mask);

    private static native String nativeSelect(long sid, int index);

    private static native void nativeReset(long sid);
}
