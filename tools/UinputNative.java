package com.aosen.shizukudex;

/**
 * /dev/uinput 的 Java 门面。真正的设备创建在 libaosen_uinput.so 里，
 * 因为创建 uinput 设备只能靠 Linux ioctl，Java 层没有对应接口。
 *
 * 这个类必须和 RootInputDaemon 一起打进 root_bridge.dex：守护进程的
 * CLASSPATH 只有那一个 dex，加载不到主 APK 的类。
 */
public final class UinputNative {

    /* linux/input-event-codes.h 的原值，不要改。 */
    public static final int EV_SYN = 0x00;
    public static final int EV_KEY = 0x01;
    public static final int EV_REL = 0x02;

    public static final int EV_ABS = 0x03;
    public static final int ABS_X = 0x00;
    public static final int ABS_Y = 0x01;


    public static final int REL_X = 0x00;
    public static final int REL_Y = 0x01;
    public static final int REL_HWHEEL = 0x06;
    public static final int REL_WHEEL = 0x08;

    public static final int BTN_LEFT = 0x110;
    public static final int BTN_RIGHT = 0x111;
    public static final int BTN_MIDDLE = 0x112;
    public static final int BTN_SIDE = 0x113;
    public static final int BTN_EXTRA = 0x114;

    /** 返回 0 成功，负数是 -errno（-13 = 没权限，-2 = 没有 /dev/uinput）。 */
    public static native int open(String name);

    public static native int emit(int type, int code, int value);

    public static native int sync();

    public static native int closeDevice();

    public static native boolean isOpen();

    /* ---- 物理鼠标独占（pointer lock）：抢走事件源，系统再也看不见这颗鼠标 ---- */

    /** 抢第一颗「真有 REL_X/REL_Y + BTN_LEFT 且不是我们自己造的」鼠标；
     *  成功返回 fd，失败 -1。 */
    public static native int grabFirstMouse();

    /** 放手并关闭。 */
    public static native int releaseGrab(int fd);

    /** >0 可读，0 超时，<0 出错。 */
    public static native int pollDevice(int fd, int timeoutMs);

    /** 读一条事件填 out[3]={type,code,value}；1 读到，0 没数据，<0 出错。 */
    public static native int readEvent(int fd, int[] out);

    /** 被 EVIOCGRAB 独占的那颗真实鼠标叫什么（没抢到返回空串）。 */
    public static native String grabbedName();

    private UinputNative() {}
}
