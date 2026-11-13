/*
 * uinput_bridge.c —— 在 /dev/uinput 里造一个真正的鼠标设备节点。
 *
 * 为什么必须走 native：创建 uinput 设备靠的是 Linux ioctl，Java 层没有任何
 * 标准接口能调它。应用侧的 injectInputEvent 是「把事件塞进系统派发队列」，
 * 系统会校验、会拒收、还会因为你占了别人的指针而收回 PointerCapture；
 * 而 uinput 造出来的设备在系统看来就是「插了一个真鼠标」——走 InputReader
 * 正常读事件、走 InputDispatcher 正常派发，不经注入校验，也不跟手机屏上的
 * 那个真实鼠标抢同一份指针。
 *
 * 只暴露四件事：open / emit / closeDevice / nodeName。
 */
#include <jni.h>
#include <fcntl.h>
#include <unistd.h>
#include <errno.h>
#include <string.h>
#include <stdio.h>
#include <dirent.h>
#include <poll.h>
#include <sys/ioctl.h>
#include <linux/uinput.h>
#include <linux/input.h>

static int g_fd = -1;

/* 相对位移设备：不声明任何绝对轴。见下面 open() 里的说明。 */

/* 每个 event 之后必须跟一个 SYN_REPORT，否则内核不会把这一批事件交出去。 */
static int emit_raw(int fd, int type, int code, int value) {
    struct input_event ev;
    memset(&ev, 0, sizeof(ev));
    ev.type = (unsigned short) type;
    ev.code = (unsigned short) code;
    ev.value = value;
    ssize_t n = write(fd, &ev, sizeof(ev));
    return (n == (ssize_t) sizeof(ev)) ? 0 : -1;
}

JNIEXPORT jint JNICALL
Java_com_aosen_shizukudex_UinputNative_open(JNIEnv *env, jclass clazz, jstring name) {
    if (g_fd >= 0) return 0;

    int fd = open("/dev/uinput", O_WRONLY | O_NONBLOCK);
    if (fd < 0) fd = open("/dev/input/uinput", O_WRONLY | O_NONBLOCK);
    if (fd < 0) return -errno;

    /* 按键 */
    ioctl(fd, UI_SET_EVBIT, EV_KEY);
    ioctl(fd, UI_SET_KEYBIT, BTN_LEFT);
    ioctl(fd, UI_SET_KEYBIT, BTN_RIGHT);
    ioctl(fd, UI_SET_KEYBIT, BTN_MIDDLE);
    ioctl(fd, UI_SET_KEYBIT, BTN_SIDE);
    ioctl(fd, UI_SET_KEYBIT, BTN_EXTRA);

    /* 相对位移 + 滚轮。
     *
     * 必须是 REL_X/REL_Y，不能是 ABS_X/ABS_Y：EventHub 判「这是不是光标设备」的
     * 唯一条件是 BTN_MOUSE + REL_X + REL_Y 三者齐备。改成绝对轴之后这台节点
     * 既不是 cursor、也不满足 touchscreen（没有 INPUT_PROP_DIRECT），classes 为空，
     * EventHub 直接跳过 —— 表现就是「daemon 说建成了，InputDevice 列表里查无此物」，
     * 上层所有 VirtualMouse 逻辑一次都不会执行。上一版就这么把整条链弄死了。 */
    ioctl(fd, UI_SET_EVBIT, EV_REL);
    ioctl(fd, UI_SET_RELBIT, REL_X);
    ioctl(fd, UI_SET_RELBIT, REL_Y);
    ioctl(fd, UI_SET_RELBIT, REL_WHEEL);
    ioctl(fd, UI_SET_RELBIT, REL_HWHEEL);

    ioctl(fd, UI_SET_EVBIT, EV_SYN);

    /* 这一位是「我是一只指针设备」的声明，缺了它后果很重：
     *
     * EventHub 见到 BTN_MOUSE + REL_X/Y 但没有 INPUT_PROP_POINTER 时，会把这个节点判成
     * INPUT_DEVICE_CLASS_TRACKBALL，系统给出的 sources 是 0x20004（SOURCE_MOUSE_RELATIVE，
     * 语义是「相对位移当作导航事件」），而不是 0x2002（SOURCE_MOUSE）。表现为：
     * 没有真正的光标、hover 不做命中测试、只把位移丢给焦点窗口，游戏也判定「这不是鼠标」。
     *
     * 真 USB 鼠标都带这一位 —— 这也是「直接插鼠标一切正常，插我们这台哪儿都不对」的根因。 */
    ioctl(fd, UI_SET_PROPBIT, INPUT_PROP_POINTER);

    struct uinput_setup usetup;
    memset(&usetup, 0, sizeof(usetup));
    usetup.id.bustype = BUS_USB;
    usetup.id.vendor = 0x0a05;      /* 沧海虚拟外设 */
    usetup.id.product = 0x1d01;
    usetup.id.version = 1;

    const char *nm = "Aosen Virtual Mouse";
    if (name != NULL) {
        nm = (*env)->GetStringUTFChars(env, name, NULL);
        if (nm == NULL) nm = "Aosen Virtual Mouse";
    }
    strncpy(usetup.name, nm, UINPUT_MAX_NAME_SIZE - 1);
    usetup.name[UINPUT_MAX_NAME_SIZE - 1] = '\0';

    if (ioctl(fd, UI_DEV_SETUP, &usetup) < 0) {
        int e = errno;
        close(fd);
        return -e;
    }
    if (ioctl(fd, UI_DEV_CREATE) < 0) {
        int e = errno;
        close(fd);
        return -e;
    }
    g_fd = fd;
    return 0;
}

/* type/code 直接用 linux/input-event-codes.h 的数值，由 Java 侧传进来。 */
JNIEXPORT jint JNICALL
Java_com_aosen_shizukudex_UinputNative_emit(JNIEnv *env, jclass clazz,
                                            jint type, jint code, jint value) {
    if (g_fd < 0) return -1;
    return emit_raw(g_fd, type, code, value);
}

JNIEXPORT jint JNICALL
Java_com_aosen_shizukudex_UinputNative_sync(JNIEnv *env, jclass clazz) {
    if (g_fd < 0) return -1;
    return emit_raw(g_fd, EV_SYN, SYN_REPORT, 0);
}

JNIEXPORT jint JNICALL
Java_com_aosen_shizukudex_UinputNative_closeDevice(JNIEnv *env, jclass clazz) {
    if (g_fd < 0) return 0;
    ioctl(g_fd, UI_DEV_DESTROY);
    close(g_fd);
    g_fd = -1;
    return 0;
}

JNIEXPORT jboolean JNICALL
Java_com_aosen_shizukudex_UinputNative_isOpen(JNIEnv *env, jclass clazz) {
    return (g_fd >= 0) ? JNI_TRUE : JNI_FALSE;
}

/* ============================================================
 * 物理鼠标独占（Android 版的 pointer lock）
 *
 * 为什么要抢：不抢的话，物理鼠标的指针永远停在手机屏上，我们只能靠
 * PointerCapture 拿相对位移；而捕获随时会被系统收回（焦点一变就没），
 * 收回的那几秒指针就跑到手机屏边缘，触发下拉通知 / 返回 / 退桌面。
 * EVIOCGRAB 之后这颗设备的事件只喂给我们，系统完全看不见它 ——
 * 手机屏上没有第二个指针，捕获也没必要了。
 *
 * 我们自己在 /dev/uinput 造的 "Aosen Virtual Mouse" 必须排除，否则会自己吃自己。
 * ============================================================ */

static int is_ours(const char *name) {
    return name != NULL && strncmp(name, "Aosen", 5) == 0;
}

/* 被独占的那颗真实鼠标叫什么 —— 上层要拿它和虚拟设备对账（一一对应）。
 * 日志里必须能同时看到「握住了谁」和「造出了谁」，否则出了问题只能猜。 */
static char g_grabName[256] = {0};

JNIEXPORT jstring JNICALL
Java_com_aosen_shizukudex_UinputNative_grabbedName(JNIEnv *env, jclass clazz) {
    return (*env)->NewStringUTF(env, g_grabName);
}

/* 扫 /dev/input/event*，找出第一颗「有 REL_X/REL_Y 与 BTN_LEFT、且不是我们造的」鼠标，
 * grab 成功返回 fd，失败返回 -errno。 */
JNIEXPORT jint JNICALL
Java_com_aosen_shizukudex_UinputNative_grabFirstMouse(JNIEnv *env, jclass clazz) {
    DIR *dir = opendir("/dev/input");
    if (dir == NULL) return -errno;

    struct dirent *de;
    int found = 0;
    while ((de = readdir(dir)) != NULL) {
        if (strncmp(de->d_name, "event", 5) != 0) continue;
        char path[128];
        snprintf(path, sizeof(path), "/dev/input/%s", de->d_name);

        int fd = open(path, O_RDONLY | O_NONBLOCK);
        if (fd < 0) continue;

        char name[256];
        memset(name, 0, sizeof(name));
        if (ioctl(fd, EVIOCGNAME(sizeof(name) - 1), name) < 0) {
            close(fd);
            continue;
        }

        unsigned long relBits[REL_MAX / (8 * sizeof(unsigned long)) + 1];
        unsigned long keyBits[KEY_MAX / (8 * sizeof(unsigned long)) + 1];
        memset(relBits, 0, sizeof(relBits));
        memset(keyBits, 0, sizeof(keyBits));
        ioctl(fd, EVIOCGBIT(EV_REL, sizeof(relBits)), relBits);
        ioctl(fd, EVIOCGBIT(EV_KEY, sizeof(keyBits)), keyBits);

        int hasRel = (relBits[REL_X / (8 * sizeof(unsigned long))] >> (REL_X % (8 * sizeof(unsigned long)))) & 1
                  && (relBits[REL_Y / (8 * sizeof(unsigned long))] >> (REL_Y % (8 * sizeof(unsigned long)))) & 1;
        int hasLeft = (keyBits[BTN_LEFT / (8 * sizeof(unsigned long))] >> (BTN_LEFT % (8 * sizeof(unsigned long)))) & 1;

        if (!hasRel || !hasLeft || is_ours(name)) {
            close(fd);
            continue;
        }

        if (ioctl(fd, EVIOCGRAB, 1) < 0) {
            close(fd);
            continue;
        }
        memset(g_grabName, 0, sizeof(g_grabName));
        strncpy(g_grabName, name, sizeof(g_grabName) - 1);
        found = fd;
        break;
    }
    closedir(dir);
    return found > 0 ? found : -1;
}

JNIEXPORT jint JNICALL
Java_com_aosen_shizukudex_UinputNative_releaseGrab(JNIEnv *env, jclass clazz, jint fd) {
    if (fd < 0) return -1;
    ioctl(fd, EVIOCGRAB, 0);
    close(fd);
    memset(g_grabName, 0, sizeof(g_grabName));
    return 0;
}

/* 等事件到来；返回 >0 表示可读，0 表示超时，<0 出错。给读线程用，别在主线程调。 */
JNIEXPORT jint JNICALL
Java_com_aosen_shizukudex_UinputNative_pollDevice(JNIEnv *env, jclass clazz, jint fd, jint timeoutMs) {
    if (fd < 0) return -1;
    struct pollfd p;
    p.fd = fd;
    p.events = POLLIN;
    p.revents = 0;
    int r = poll(&p, 1, timeoutMs);
    if (r < 0) return -errno;
    if (r == 0) return 0;
    return (p.revents & POLLIN) ? 1 : 0;
}

/* 读一条事件，填 int[3] = {type, code, value}；返回 1 读到，0 没数据，<0 出错。 */
JNIEXPORT jint JNICALL
Java_com_aosen_shizukudex_UinputNative_readEvent(JNIEnv *env, jclass clazz, jint fd, jintArray out) {
    if (fd < 0 || out == NULL) return -1;
    struct input_event ev;
    ssize_t n = read(fd, &ev, sizeof(ev));
    if (n == (ssize_t) sizeof(ev)) {
        jint vals[3];
        vals[0] = ev.type;
        vals[1] = ev.code;
        vals[2] = ev.value;
        (*env)->SetIntArrayRegion(env, out, 0, 3, vals);
        return 1;
    }
    if (n < 0 && (errno == EAGAIN || errno == EWOULDBLOCK)) return 0;
    return -errno;
}
