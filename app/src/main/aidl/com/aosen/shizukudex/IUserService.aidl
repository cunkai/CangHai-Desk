package com.aosen.shizukudex;

import android.view.Surface;

interface IUserService {
    void destroy() = 16777114;
    void exit() = 1;
    String executeCommand(String cmd) = 2;
    int createVirtualDisplay(String name, int width, int height, int dpi, in Surface surface, int flags) = 3;
    void resizeVirtualDisplay(int displayId, int width, int height, int dpi) = 4;
    void releaseVirtualDisplay(int displayId) = 5;

    /* ---- /dev/uinput 真鼠标 ---- 
     * 这段代码跑在 Shizuku 的 shell 进程（uid 2000）里，所以能写 /dev/uinput；
     * 放在应用进程里会被 SELinux 拦（untrusted_app 没有 uinput_device 的权限，
     * 跟 /dev/uinput 是 0666 没关系）。 */
    int uinputOpen(String soPath, String apkPath, String name) = 6;
    boolean uinputReady() = 7;
    int uinputMove(int dx, int dy) = 8;
    int uinputButton(int button, boolean down) = 9;
    int uinputScroll(int vScroll, int hScroll) = 10;
    void uinputClose() = 11;
    String uinputStatus() = 12;
}
