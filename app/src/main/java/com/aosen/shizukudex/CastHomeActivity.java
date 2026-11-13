package com.aosen.shizukudex;

/**
 * WiFi 投屏专用桌面实例。
 *
 * DexHomeActivity 是 launchMode="singleTask"：如果投屏屏也直接启动同一个组件，
 * 系统可能把外屏上已经存在的那个实例搬到投屏虚拟屏上，外屏随即变黑。
 * 用一个独立组件名就能拿到独立 task，外屏桌面与投屏桌面互不干扰。
 *
 * 这里刻意不覆写任何方法：桌面 UI、Aosen 桥、窗口壳、Dock 全部原样继承，
 * 投屏只是换了一个「画面输出 Surface」，桌面代码一行都不用改。
 */
public class CastHomeActivity extends DexHomeActivity {
}
