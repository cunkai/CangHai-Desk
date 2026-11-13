package com.aosen.shizukudex;

import android.content.Context;
import android.hardware.display.DisplayManager;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.Display;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;

/**
 * 把一块 UI（Dock / 任务栏 / 伪装标题栏 / 悬停探针）挂到指定虚拟屏上，浮在该屏应用之上。
 *
 * <p><b>为什么不再是 Presentation</b>：Presentation / TYPE_APPLICATION_ATTACHED_DIALOG 是子窗口类型，
 * 必须绑定一个「默认屏、window token 有效」的 Activity。在这条 Root/Moonlight 路径里，
 * {@code hostBaseContext()} 拿到的其实是 {@code DeXPresentation.getContext()}（Dialog 的
 * ContextThemeWrapper，不是 Activity，无 window token），MainActivity 又不一定处于可挂载状态——
 * 真机实测连续 150 次 {@code new Presentation(...).show()} 全部抛
 * {@code BadTokenException: token null is not valid}。这条路径拿不到 token，重试再久也没用。
 *
 * <p><b>当前挂载方式（与已验证可行的 RemoteCursorOverlay 同构）</b>：用
 * {@code applicationContext.createDisplayContext(display)} 拿到该虚拟屏自己的 WindowManager，
 * 直接 {@code addView(content, lp)}。addView 不需要 Activity window token，从根上消除 BadToken。
 * 窗口类型依次尝试：
 * <ol>
 *   <li>{@code TYPE_PRIVATE_PRESENTATION}：Presentation 类窗口、不是悬浮窗；</li>
 *   <li>{@code TYPE_APPLICATION_OVERLAY}：仅在上者不可用时降级。本机已由 Root 授权
 *       SYSTEM_ALERT_WINDOW appop（绕过系统设置里的开关），Sunshine 远程指针层正是用它。</li>
 * </ol>
 *
 * <p><b>真机实测结论（2026-11-11 10:00 / 10:23 日志，只记可确证的事实）</b>：
 * 整屏 2038 + NOT_TOUCHABLE 的 Dock 会挡住外部应用点击（三星弹「未针对新版安卓优化」）；
 * 去掉 touchableRegion 声明无效。本层从未设置 {@code LayoutParams.alpha}（默认 1.0），
 * 与远端光标层一样；远端光标层同样是整屏 2038、同样没设 alpha、content 只画一个小箭头
 * 且其余像素透明，却不挡点击。两者差异的准确归因需要 dumpsys window 的
 * touchOcclusionMode 才能定案，此处不下结论、不套用 alpha==0 之类的推测。
 * 另外 NOT_TOUCHABLE 让 Dock 自己收不到系统触摸，而外设大脑直派在 Root 通道拿不到
 * 原始鼠标（真实鼠标没能握住: fail -1），两头落空。
 *
 * <p><b>唯一可靠解</b>：窗口本身不要盖在应用区上。Root 虚拟屏分支把 Dock 做成
 * 「底部一条、可触摸」的小窗口（{@code touchable=true}），应用区头顶没有任何本层窗口，
 * 遮挡判定无从触发；Dock 按钮直接吃系统真实触摸，不再依赖大脑直派。
 * 启动台 / 菜单 / 前台 HTML 窗口等全屏态由 {@code updateLayout} 临时拉回全屏，
 * 那是模态状态，本就该由 Dock 独占交互。
 *
 * <p><b>作用域</b>：仅 Root / Moonlight 虚拟屏（CastHomeActivity，displayId>0）分支；
 * 非 Root（默认屏、真实外屏、DeXPresentation 路径）保持整屏 NOT_TOUCHABLE + insets 声明，
 * 一行不动。五层窗口一律精确挂到构造时传入的 {@code displayId}，不跨屏、不借别的 Context。
 */
final class CanghaiVirtualWindow {

    /** 延迟解析 base 的接口：只用来取 applicationContext / DisplayManager，不要求带 window token。 */
    interface BaseResolver {
        Context get();
    }

    private static final String TAG = "沧海虚拟窗";

    private static final int MAX_ATTEMPTS = 40;
    private static final long RETRY_DELAY_MS = 120;
    private static final int PROGRESS_EVERY = 10;

    private static final int[] WINDOW_TYPES = {
            WindowManager.LayoutParams.TYPE_PRIVATE_PRESENTATION,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
    };

    private final BaseResolver baseResolver;
    private final int displayId;
    private final View content;
    private final int defWidth, defHeight, defX, defY, defGravity, defFlags, defFormat;
    private final boolean touchable;          // 设计上的默认可触摸性
    private volatile boolean touchableNow;    // 运行期真实可触摸状态

    private WindowManager wm;
    private WindowManager.LayoutParams lp;
    private boolean showing = false;
    private int attempts = 0;

    private final Handler main = new Handler(Looper.getMainLooper());

    /** touchable=true 时窗口收系统真实触摸（Root 的 Dock 条带），false 时纯展示、不收触摸。 */
    CanghaiVirtualWindow(BaseResolver baseResolver, int displayId, View content,
                         int width, int height, int x, int y, int gravity, int flags, int format,
                         boolean touchable) {
        this.baseResolver = baseResolver;
        this.displayId = displayId;
        this.content = content;
        this.defWidth = width;
        this.defHeight = height;
        this.defX = x;
        this.defY = y;
        this.defGravity = gravity;
        this.defFlags = flags;
        this.defFormat = format;
        this.touchable = touchable;
        this.touchableNow = touchable;
    }

    boolean isShowing() {
        return showing;
    }

    /** 窗口当前可触摸时返回 true，外设大脑直派闸据此跳过，避免双击。 */
    boolean isTouchable() {
        return touchableNow;
    }

    int getLayoutHeight() {
        return lp != null ? lp.height : defHeight;
    }

    void show() {
        attempts = 0;
        main.post(this::tryShow);
    }

    private void tryShow() {
        if (showing || content == null) return;

        Context base = (baseResolver != null) ? baseResolver.get() : null;
        if (base == null) {
            if (attempts == 0) {
                CanghaiErrorCollector.logExt("[沧海虚拟窗] base 暂为 null，准备重试");
            }
            if (!scheduleRetry("[沧海虚拟窗] base 始终为 null，放弃 UI 层挂载")) return;
            return;
        }

        try {
            Context app = base.getApplicationContext();
            DisplayManager dm = (DisplayManager) app.getSystemService(Context.DISPLAY_SERVICE);
            Display display = (dm != null) ? dm.getDisplay(displayId) : null;
            if (display == null) {
                if (attempts == 0) {
                    CanghaiErrorCollector.logExt("[沧海虚拟窗] display " + displayId + " 未就绪，准备重试");
                }
                if (!scheduleRetry("[沧海虚拟窗] display " + displayId + " 未就绪，放弃 UI 层")) return;
                return;
            }

            Context dc = app.createDisplayContext(display);
            WindowManager manager = (WindowManager) dc.getSystemService(Context.WINDOW_SERVICE);
            if (manager == null) throw new IllegalStateException("WINDOW_SERVICE 不可用");

            detachContent();

            int flags = baseFlags();
            WindowManager.LayoutParams a = new WindowManager.LayoutParams(
                    defWidth, defHeight, WINDOW_TYPES[0], flags, defFormat);
            a.gravity = defGravity;
            a.x = defX;
            a.y = defY;
            a.setTitle("CanghaiUI-" + displayId);
            if (Build.VERSION.SDK_INT >= 30) a.setFitInsetsTypes(0);

            Throwable last = null;
            for (int type : WINDOW_TYPES) {
                a.type = type;
                try {
                    manager.addView(content, a);
                    wm = manager;
                    lp = a;
                    showing = true;
                    attempts = 0;
                    CanghaiErrorCollector.logExt("[沧海虚拟窗] 已在虚拟屏 display " + displayId
                            + " 挂载 UI 层（type=" + type + " " + defWidth + "×" + defHeight
                            + " gravity=" + defGravity + " touchable=" + touchableNow
                            + (touchableNow ? "，条带窗口收系统真实触摸" : "，NOT_TOUCHABLE 透传")
                            + "）");
                    return;
                } catch (Throwable e) {
                    last = e;
                    detachContent();
                }
            }
            throw (last != null ? last : new IllegalStateException("addView 全部窗口类型失败"));
        } catch (Throwable t) {
            if (attempts == 0) {
                CanghaiErrorCollector.logExt("[沧海虚拟窗] 挂载异常（"
                        + t.getClass().getSimpleName() + "），准备重试");
            }
            if (!scheduleRetry("[沧海虚拟窗] 挂载失败: " + t)) {
                Log.w(TAG, "UI 层挂载失败: " + t);
            }
        }
    }

    private int baseFlags() {
        // 可触摸的条带窗口不加 LAYOUT_IN_SCREEN：与已验证可行的远程光标层保持同一套标志，
        // 避免整屏布局语义把它拉回「覆盖全屏」的遮挡判定。
        int flags = defFlags
                | WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS;
        if (touchableNow) {
            flags |= WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL;
        } else {
            flags |= WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                    | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                    | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL;
        }
        return flags;
    }

    /** addView 复用同一 content 时必须先解绑旧父，避免 child already has a parent。 */
    private void detachContent() {
        if (content == null || content.getParent() == null) return;
        try {
            ((ViewGroup) content.getParent()).removeView(content);
        } catch (Throwable ignored) {
        }
    }

    /** 递增重试计数：未耗尽则延时重试并返回 true；耗尽则记放弃日志并返回 false。 */
    private boolean scheduleRetry(String giveUpMsg) {
        if (attempts++ < MAX_ATTEMPTS) {
            if (attempts % PROGRESS_EVERY == 0) {
                CanghaiErrorCollector.logExt("[沧海虚拟窗] 仍在重试挂载（第 " + attempts
                        + "/" + MAX_ATTEMPTS + " 次）");
            }
            main.postDelayed(this::tryShow, RETRY_DELAY_MS);
            return true;
        }
        Log.w(TAG, giveUpMsg);
        CanghaiErrorCollector.logExt(giveUpMsg);
        return false;
    }

    /** 摘掉这一层（等价于 removeView）。重复调用安全。 */
    void dismiss() {
        main.post(() -> {
            if (!showing) return;
            WindowManager w = wm;
            showing = false;
            wm = null;
            lp = null;
            if (w != null && content != null) {
                try {
                    w.removeView(content);
                } catch (Throwable ignored) {
                }
            }
        });
    }

    /** 改尺寸（开始菜单展开 / 收起时窗口长高）。width/height 用像素或 MATCH_PARENT。 */
    void updateLayout(int width, int height) {
        main.post(() -> {
            if (!showing || wm == null || lp == null) return;
            lp.width = width;
            lp.height = height;
            try {
                wm.updateViewLayout(content, lp);
            } catch (Throwable ignored) {
            }
        });
    }

    /** 改位置（伪装标题栏跟随应用窗口左上角移动）。x/y 用像素。 */
    void updatePosition(int x, int y) {
        main.post(() -> {
            if (!showing || wm == null || lp == null) return;
            lp.x = x;
            lp.y = y;
            try {
                wm.updateViewLayout(content, lp);
            } catch (Throwable ignored) {
            }
        });
    }

    /** 改附加窗口标志；基础标志始终按当前 touchable 状态重新补齐。 */
    void setFlags(int flags) {
        main.post(() -> {
            if (!showing || wm == null || lp == null) return;
            int f = flags
                    | WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                    | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS;
            if (touchableNow) {
                f |= WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL;
            } else {
                f |= WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                        | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                        | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL;
            }
            lp.flags = f;
            try {
                wm.updateViewLayout(content, lp);
            } catch (Throwable ignored) {
            }
        });
    }
}
