package com.aosen.shizukudex;

import android.app.Presentation;
import android.webkit.ConsoleMessage;
import android.webkit.JavascriptInterface;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import org.json.JSONArray;
import org.json.JSONObject;
import android.app.ActivityOptions;
import android.content.Intent;
import android.content.Context;
import android.graphics.PixelFormat;
import android.os.Build;
import android.os.Bundle;
import android.os.SystemClock;
import android.util.Log;
import android.view.Display;
import android.view.Gravity;
import android.view.InputDevice;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.PointerIcon;
import android.view.InputEvent;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;

import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class DeXPresentation extends Presentation {

    private static final String TAG = "ShizukuDeX_Pres";

    public static class Desktop {
        public final int index;
        public final String name;
        public final SurfaceView surfaceView;
        public int displayId = -1;

        public Desktop(int index, String name, SurfaceView surfaceView) {
            this.index = index;
            this.name = name;
            this.surfaceView = surfaceView;
        }
    }

    public interface OnDesktopChangeListener {
        void onDesktopChanged();
        void onDesktopLoadingStateChanged(boolean isLoading);
    }

    private static volatile DeXPresentation sActiveInstance;

    /** 「旧注入通道已关闭」只打一次，避免每帧刷屏。 */
    private static boolean sVirtualDeviceGateLogged = false;

    public static DeXPresentation getActiveInstance() {
        return sActiveInstance;
    }

    /** 零权限 Moonlight 虚拟屏释放后，把全局 active 实例还给有线外屏桌面。 */
    public static void setActiveInstance(DeXPresentation instance) {
        sActiveInstance = instance;
    }

    /**
     * 零权限本地虚拟屏：把内部逻辑分辨率与虚拟屏真实像素对齐。
     * 只改坐标基准，绝不重建 Surface / 虚拟屏（setVirtualDisplayResolution 会重建）。
     */
    public synchronized void alignDirectDesktopSize(int w, int h) {
        if (w > 0 && h > 0) {
            this.screenWidth = w;
            this.screenHeight = h;
        }
    }

    public static void setPointerLocked(boolean locked) {
        DeXPresentation inst = sActiveInstance;
        if (inst != null && inst.mouseCursorView != null) {
            inst.cursorHandler.post(() -> {
                if (inst.mouseCursorView != null) {
                    inst.mouseCursorView.setVisibility(locked ? View.GONE : View.VISIBLE);
                }
            });
        }
    }

    private FrameLayout flDesktopStage;
    private WebView directDesktopWeb;
    // PS 图层架构：独立分层容器
    private FrameLayout flNativeStage;
    private FrameLayout flWebAppStage;
    private FrameLayout flDockStage;
    private FrameLayout flLauncherStage;
    private WebView directDockWeb;
    private boolean directDockReady = false;
    private volatile boolean isLaunchpadOpen = false;
    /** 直显模式下本次按下锁定的接收窗口（按下在 Dock 上，抬起也必须回 Dock） */
    private WebView directPressWeb;
    /** Dock 页面自己上报的"可交互区域"（Dock 栏 + 正在弹出的右键菜单），坐标系为窗口像素 */
    private final android.graphics.Rect directDockHitRect = new android.graphics.Rect();
    private volatile boolean directDockHitValid = false;
    private FrameLayout flGridLabelsContainer;
    private FrameLayout flCursorLayer;
    /** 统一软件指针：USB 物理鼠标 / 黑屏触控 / Moonlight 远程鼠标共用绘制 */
    private com.alkaidlab.sunshine.service.MouseCursorView mouseCursorView;
    private final android.os.Handler cursorHandler = new android.os.Handler(android.os.Looper.getMainLooper());
    private final Runnable cursorAutoHide = () -> {
        if (mouseCursorView != null) {
            mouseCursorView.animate().alpha(0f).setDuration(220).withEndAction(() -> {
                if (mouseCursorView != null) mouseCursorView.setVisibility(View.GONE);
            }).start();
        }
    };
    private View layoutLoadingOverlay;
    private TextView tvLoadingText;
    private View layoutScreenIndicator;
    private TextView tvScreenIndicator;

    private boolean isGridMode = false;
    private boolean isCursorOnThisScreen = true;
    private float lastMouseX = -1f;
    private float lastMouseY = -1f;
    /** 光标是否已经真正定位过。绝不能用 lastMouseX > 0 判断：
     *  贴左/上边缘时 lastMouseX 正好是 0，会被误判成「没定位」，于是回退到屏幕中心
     *  —— 用户看到的「鼠标一贴边就跳回中间」就是这么来的。 */
    private boolean cursorPositioned = false;
    private long lastRoamTime = 0L;
    /** 黑屏触控注入 hover 事件的节流时间戳 */
    private long lastHoverMoveAt = 0L;

    private int virtualDisplayId = -1;
    private int rawWidth = 1920;
    private int rawHeight = 1080;
    private int screenWidth = 1920;
    private int screenHeight = 1080;
    private int dexDpi = 213; // 标清/高清电脑桌面标准 DPI (1440dp 桌面逻辑视口)

    private final List<Desktop> desktops = new CopyOnWriteArrayList<>();
    private int activeDesktopIndex = 0;
    private OnDesktopChangeListener desktopChangeListener;

    private final ExecutorService inputExecutor = Executors.newSingleThreadExecutor();

    private float extDownX = 0f;
    private float extDownY = 0f;
    private long extDownTime = 0L;
    private boolean extIsDrag = false;

    // 鼠标指针与虚拟触控互斥显示标志
    private boolean isVirtualCursorActive = false;

    /** 非主键按下时记住按钮：ACTION_UP 时 MotionEvent.getButtonState() 已被清空。 */
    private volatile int pendingMouseButton = 0;

    /** 右键去重：dispatchTouchEvent 与 dispatchGenericMotionEvent 可能同时描述同一次物理点击。 */
    private volatile long lastSecondaryInjectMs = 0L;
    private static final long SECONDARY_DEDUP_MS = 250L;

    /** 滚轮 16ms 合并窗口：快速滚动最多注入一帧/16ms，绝不逐事件起进程。 */
    private final Object scrollLock = new Object();
    private boolean scrollFlushScheduled = false;
    private int pendingScrollDisplayId = -1;
    private float pendingScrollX = 0f;
    private float pendingScrollY = 0f;
    private float pendingVScroll = 0f;
    private float pendingHScroll = 0f;

    private int currentWindowMode = 5; // 5 = 自由窗口, 1 = 全屏
    private int currentRotation = 0;   // 0 = 0°, 1 = 90°, 2 = 180°, 3 = 270°

    private MainActivity mainActivity;

    public DeXPresentation(Context outerContext, Display display, int width, int height, int dpi) {
        super(outerContext, display);
        sActiveInstance = this;
        if (outerContext instanceof MainActivity) {
            this.mainActivity = (MainActivity) outerContext;
        }
        this.rawWidth = width;
        this.rawHeight = height;
        this.dexDpi = dpi;

        String dKey = (display != null) ? (display.getName() + "_" + width + "x" + height) : "default";
        String resKey = CanghaiStore.getDisplayResolutionKey(outerContext, dKey);
        int[] target = CanghaiStore.getTargetResolution(width, height, resKey);
        this.screenWidth = target[0];
        this.screenHeight = target[1];

        if (outerContext != null) {
            android.content.SharedPreferences sp = outerContext.getSharedPreferences("aosen_settings", Context.MODE_PRIVATE);
            // 窗口模式固定 5（自由窗口）：官方桌面/沧海桌面均以窗口打开应用（1 全屏已废弃）
            this.currentWindowMode = sp.getInt("pref_window_mode", 5);
            if (this.currentWindowMode != 5) this.currentWindowMode = 5;
            this.currentRotation = sp.getInt("pref_screen_rotation", 0);
        }
    }

    /** 仅沧海桌面（pref_home_type=1）启动自绘 DexHome；官方桌面由三星 DeX 桌面自然接管 */
    private boolean shouldLaunchDexHome() {
        try {
            android.content.SharedPreferences sp = getContext()
                    .getSharedPreferences("aosen_settings", Context.MODE_PRIVATE);
            return sp.getInt("pref_home_type", 0) == 1;
        } catch (Throwable t) {
            return true;
        }
    }

    public void setWindowMode(int mode) {
        // 只记录模式，重建虚拟屏后由 configureDesktopDisplayEnvironment 应用到新显示屏。
        // ⚠️ 严禁在现存显示屏上执行 wm set-display-windowing-mode：对已有窗口任务的显示屏
        // 在线切换会触发 Samsung SystemUI 崩溃（12:14 用户真机复现；启动时应用模式则安全）。
        this.currentWindowMode = mode;
    }

    public void setScreenRotation(int rotation) {
        this.currentRotation = rotation;
        for (Desktop d : desktops) {
            if (d.displayId > 0) {
                ShizukuCmd.setScreenRotation(d.displayId, rotation);
            }
        }
        Display ext = getDisplay();
        if (ext != null && ext.getDisplayId() > 0) {
            ShizukuCmd.setScreenRotation(ext.getDisplayId(), rotation);
        }
    }

    public void setOnDesktopChangeListener(OnDesktopChangeListener listener) {
        this.desktopChangeListener = listener;
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.layout_dex_presentation);

        android.view.Window w = getWindow();
        if (w != null) {
            // 外屏 Presentation 作为纯展示与直显容器，加上 FLAG_NOT_FOCUSABLE，绝不抢占手机主屏焦点，
            // 确保 MainActivity 的硬件鼠标捕获通道（PointerCapture）永不被系统销毁！
            w.addFlags(android.view.WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE);
            w.addFlags(android.view.WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL);
        }

        try {
            if (w != null) {
                w.setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(android.graphics.Color.TRANSPARENT));
            }
            View container = findViewById(R.id.dexContainer);
            if (container != null) {
                container.setBackgroundColor(android.graphics.Color.TRANSPARENT);
            }
        } catch (Throwable ignored) {}

        int presDispId = (getDisplay() != null) ? getDisplay().getDisplayId() : -1;
        CanghaiErrorCollector.logExt("DeXPresentation.onCreate 挂载到屏幕: displayId=" + presDispId);
        flDesktopStage = findViewById(R.id.flDesktopStage);
        flNativeStage = findViewById(R.id.flNativeStage);
        flWebAppStage = findViewById(R.id.flWebAppStage);
        flDockStage = findViewById(R.id.flDockStage);
        flLauncherStage = findViewById(R.id.flLauncherStage);
        flGridLabelsContainer = findViewById(R.id.flGridLabelsContainer);
        flCursorLayer = findViewById(R.id.flCursorLayer);
        layoutLoadingOverlay = findViewById(R.id.layoutLoadingOverlay);
        tvLoadingText = findViewById(R.id.tvLoadingText);
        layoutScreenIndicator = findViewById(R.id.layoutScreenIndicator);
        tvScreenIndicator = findViewById(R.id.tvScreenIndicator);
        if (layoutScreenIndicator != null) {
            layoutScreenIndicator.setOnClickListener(v -> resetCursorToCenter());
        }

        // 彻底清除并销毁任何外置 RemoteCursorOverlay，防止屏幕中间滞留死光标
        com.alkaidlab.sunshine.service.RemoteCursorOverlay.INSTANCE.destroy();

        // 统一软件指针：USB 鼠标 / 黑屏触控 / Moonlight
        // 三种输入源都在这里绘制，大小样式由 MouseCursorPrefs 全局控制；
        // 系统原生硬件箭头在本屏上由 EmptyPointerFrameLayout 恒定隐藏。
        CanghaiStore.syncCursorPrefsToNative(getContext());
        try {
            Display d = getDisplay();
            if (d != null && d.getDisplayId() > 0) {
                ShizukuCmd.setPointerHostDisplay(d.getDisplayId());
            }
        } catch (Throwable ignored) {}
        com.alkaidlab.sunshine.service.MouseCursorPrefs.onChanged(() -> {
            cursorHandler.post(() -> {
                setMousePointerVisibility(true);
                if (mouseCursorView != null) {
                    mouseCursorView.refresh();
                    if (isCursorOnThisScreen) {
                        applyCursorPosition(getLastMouseX(), getLastMouseY());
                        mouseCursorView.requestLayout();
                        mouseCursorView.invalidate();
                    }
                }
            });
            return kotlin.Unit.INSTANCE;
        });
        android.content.Context cursorCtx = getContext();
        if (cursorCtx != null) {
            mouseCursorView = new com.alkaidlab.sunshine.service.MouseCursorView(cursorCtx);
            if (getDisplay() != null) {
                mouseCursorView.setDisplayId(getDisplay().getDisplayId());
            }
            mouseCursorView.setVisibility(View.GONE);
            // 必须 WRAP_CONTENT：写死 48dp 会在用户调大光标后把形状裁掉一角，
            // 现在由 MouseCursorView.onMeasure 按「固有尺寸 × scale + 描边/内边距」自适应。
            FrameLayout.LayoutParams cursorLp = new FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.WRAP_CONTENT,
                    FrameLayout.LayoutParams.WRAP_CONTENT);
            if (flCursorLayer != null) {
                flCursorLayer.addView(mouseCursorView, cursorLp);
            } else if (flDesktopStage != null) {
                flDesktopStage.addView(mouseCursorView, cursorLp);
            }
        }
        setMousePointerVisibility(true);

        // 初始化创建桌面 1
        createAndAddDesktop("桌面 1");
    }

    @Override
    public void show() {
        super.show();
        com.alkaidlab.sunshine.service.RemoteCursorOverlay.INSTANCE.destroy();
        try {
            Display d = getDisplay();
            if (d != null) {
                com.alkaidlab.sunshine.service.MouseCursorTargets.unregister(d.getDisplayId());
                if (d.getDisplayId() > 0) {
                    ShizukuCmd.setPointerHostDisplay(d.getDisplayId());
                }
            }
        } catch (Throwable ignored) {}
    }

    @Override
    public void dismiss() {
        if (directDesktopWeb != null) {
            try {
                directDesktopWeb.stopLoading();
                directDesktopWeb.loadUrl("about:blank");
                if (flDesktopStage != null) {
                    flDesktopStage.removeView(directDesktopWeb);
                }
                directDesktopWeb.destroy();
            } catch (Throwable ignored) {}
            directDesktopWeb = null;
        }
        try {
            ShizukuCmd.setPointerHostDisplay(-1);
            Display d = getDisplay();
            if (d != null) com.alkaidlab.sunshine.service.MouseCursorTargets.unregister(d.getDisplayId());
        } catch (Throwable ignored) {}
        try {
            com.aosen.shizukudex.ShizukuCmd.clearPointerSpriteTargets();
            com.aosen.shizukudex.ShizukuCmd.restoreSystemPointerSprite(getContext());
        } catch (Throwable ignored) {}
        super.dismiss();
    }

    public void showLoading(String message) {
        if (layoutLoadingOverlay != null) {
            layoutLoadingOverlay.post(() -> {
                if (tvLoadingText != null && message != null) {
                    tvLoadingText.setText(message);
                }
                layoutLoadingOverlay.setAlpha(1.0f);
                layoutLoadingOverlay.setVisibility(View.VISIBLE);
                layoutLoadingOverlay.bringToFront();
                if (mouseCursorView != null) {
                    mouseCursorView.bringToFront();
                }
            });
        }
        if (desktopChangeListener != null && flDesktopStage != null) {
            flDesktopStage.post(() -> desktopChangeListener.onDesktopLoadingStateChanged(true));
        }
    }

    public void hideLoading() {
        if (layoutLoadingOverlay != null) {
            layoutLoadingOverlay.post(() -> {
                layoutLoadingOverlay.animate()
                        .alpha(0f)
                        .setDuration(300)
                        .withEndAction(() -> {
                            if (layoutLoadingOverlay != null) {
                                layoutLoadingOverlay.setVisibility(View.GONE);
                            }
                        })
                        .start();
            });
        }
        if (desktopChangeListener != null && flDesktopStage != null) {
            flDesktopStage.post(() -> desktopChangeListener.onDesktopLoadingStateChanged(false));
        }
    }

    public synchronized void createAndAddDesktop(String name) {
        if (flDesktopStage == null) return;
        /* 单桌面策略（省功耗）：全局只允许一块桌面虚拟屏。
         * 每多一块虚拟屏就会多常驻一套 SystemUI / 桌面进程 / Surface 合成，
         * 待机耗电明显，而绝大多数用户只用一块桌面。
         * 任何「新建桌面」请求都直接复用现有桌面，不再创建第二块虚拟屏。 */
        if (!desktops.isEmpty()) {
            Log.i(TAG, "单桌面模式：忽略新建请求，复用桌面 1");
            switchToDesktop(0);
            hideLoading();
            return;
        }
        showLoading(getContext().getString(R.string.desktop_loading));
        /* 兜底提示：桌面首帧若因 launcher/SystemUI/建屏失败迟迟不来，遮罩会永久停留。
         * 这里不自动收起 —— 收起后就是纯黑屏，比「正在加载」更糟（用户原话：
         * "关闭了也是黑屏，还不如显示着"）。15s 后只把文案换成重启提示。 */
        if (flDesktopStage != null) {
            flDesktopStage.postDelayed(() -> {
                if (layoutLoadingOverlay != null
                        && layoutLoadingOverlay.getVisibility() == View.VISIBLE) {
                    Log.w(TAG, "桌面加载超过 15s 未完成，提示重启");
                    if (tvLoadingText != null) {
                        tvLoadingText.setText("桌面加载超时，请重启沧海试试");
                    }
                }
            }, 15000);
        }

        boolean hasPrivilege = (PrivilegeRoute.mode() == PrivilegeRoute.Mode.ROOT || ShizukuCmd.isShizukuReady());
        // 只有「拿不到虚拟屏」才退回 Presentation 直显：那种情况下六层只能塞进同一套 View 插槽，
        // 安卓应用是独立 Window，一定会被整块 WebView 盖死 —— 这是无 Root 的固有限制。
        // 有权限时必须走 VirtualDisplay + 底座 Activity：桌面是窗口、安卓应用是系统窗口、
        // Dock 是 TYPE_APPLICATION_OVERLAY 浮窗，层级交给系统调度，插槽才对得上（含沧海桌面）。
        if (!hasPrivilege) {
            CanghaiErrorCollector.logExt("无 Root/Shizuku：外屏走 Presentation 直显（所有层共用一套 View 插槽）");
            setupDirectDesktopStage();
            hideLoading();
            notifyDesktopChanged();
            return;
        }

        // 官方（三星）桌面：Dock 是沧海桌面的东西，先整层撤掉再铺原生 SurfaceView。
        hideDockStageForOfficialDesktop();

        SurfaceView sv = new SurfaceView(getContext());
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
        );
        sv.setLayoutParams(lp);
        sv.getHolder().setFormat(PixelFormat.RGBA_8888);
        sv.getHolder().setFixedSize(screenWidth, screenHeight);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            sv.setPointerIcon(EmptyPointerFrameLayout.getThemedPointerIcon(getContext()));
        }

        final int deskIndex = desktops.size() + 1;
        Desktop desk = new Desktop(deskIndex, name, sv);
        desktops.add(desk);

        sv.getHolder().addCallback(new SurfaceHolder.Callback() {
            @Override
            public void surfaceCreated(@NonNull SurfaceHolder holder) {
                final android.view.Surface surface = holder.getSurface();
                if (surface == null || !surface.isValid()) return;
                /* 建虚拟屏必须走 root 桥，而桥服务冷启动要等 1.5~3.5 秒（servicemanager
                 * 里还没注册时只能睡等）。这段活儿以前同步跑在 surfaceCreated 里，
                 * 等于把整个界面钉住 —— 17:54:32 那条卡顿栈就是
                 * Thread.sleep → awaitBridgeService → createVirtualDisplay。
                 * 建屏本来就不需要主线程，挪出去，界面立刻能响应。 */
                new Thread(() -> createDesktopDisplay(desk, deskIndex, surface),
                        "create-vd-" + deskIndex).start();
            }

            @Override
            public void surfaceChanged(@NonNull SurfaceHolder holder, int format, int width, int height) {
                if (desk.displayId > 0 && width > 0 && height > 0) {
                    ShizukuCmd.resizeVirtualDisplay(desk.displayId, width, height, dexDpi);
                }
            }

            @Override
            public void surfaceDestroyed(@NonNull SurfaceHolder holder) {
                if (desk.displayId > 0) {
                    ShizukuCmd.releaseVirtualDisplay(desk.displayId);
                    desk.displayId = -1;
                }
            }
        });

        flDesktopStage.addView(sv);
        applyDesktopLayout();
    }

    /**
     * 建屏全过程（后台线程）。包含等 root 桥、建虚拟屏、绑真设备、拉起桌面。
     *
     * <p>绝不能在主线程做：等桥那一段是 sleep 循环，冷启动要 1.5~3.5 秒，
     * 卡住的就是整个界面（实测直接 10 秒 ANR）。所有会动 View 的步骤统一 post 回主线程。
     */
    private void createDesktopDisplay(Desktop desk, int deskIndex, android.view.Surface surface) {
        try {
            int dId = ShizukuCmd.createVirtualDisplay(
                    getContext(),
                    "Aosen_VD_Desk_" + deskIndex,
                    screenWidth,
                    screenHeight,
                    dexDpi,
                    surface
            );
            if (dId > 0) {
                desk.displayId = dId;
                virtualDisplayId = dId;
                /* 这块屏的最高层先挂一支指针。它是这条通道下唯一可见的光标：
                 * 位置只跟系统落点，图标是沧海自己的。见 AosenPointerOverlay 注释。 */
                /* 先告诉设备「这块屏有多大」：绝对坐标要按它换算。
                 * 再挂唯一那一层指针 —— 这条通道下一共只有它能画鼠标。 */
                VirtualMouse.setSpace(screenWidth, screenHeight);
                /* 指针层要等设备真的插上再挂。
                 * 挂早了、绑屏又失败的话，屏幕上会留一支永远不动的箭头（它只跟着
                 * VirtualMouse 的坐标走，而那条路没通），看起来就像「鼠标卡死」。
                 * 反过来，绑屏成功才挂，失败时仍由 Presentation 的自绘兜底 —— 至少
                 * 看得见它在动。 */
                cursorHandler.postDelayed(new Runnable() {
                    int tries = 0;
                    @Override
                    public void run() {
                        if (VirtualMouse.isActive()) {
                            /* Presentation.show 只要 Activity token：本类的 getContext() 是
                             * Presentation 构造时包出来的 ContextThemeWrapper，永远不是
                             * Activity，传它必抛 BadToken。优先取这块屏上的 DexHomeActivity，
                             * 没有再退回 mainActivity，两者都没有才回落 getContext()
                             *（由 show() 内的兜底处理，绝不抛到外层）。 */
                            android.app.Activity actBase = DexHomeActivity.findForDisplay(dId);
                            if (actBase == null) actBase = mainActivity;
                            AosenPointerOverlay.show(actBase != null ? actBase : getContext(),
                                    dId, screenWidth, screenHeight);
                            return;
                        }
                        if (++tries < 30) cursorHandler.postDelayed(this, 100);
                    }
                }, 200);
                // 真设备鼠标的唯一绑屏时机：屏刚建好、真实 displayId 才存在。
                // 锚点用用户当前看得见的箭头位置，绑完把系统指针推到同一处。
                try {
                    final float ax = cursorPositioned ? Math.min(screenWidth, lastMouseX) : (screenWidth / 2f);
                    final float ay = cursorPositioned ? Math.min(screenHeight, lastMouseY) : (screenHeight / 2f);
                    /* 串流屏就绪时一律绑它：这块自建 Aosen_VD_Desk_<n> 不是用户看的那块，
                     * 把它写进关联，屏销毁后会留下悬空 uniqueId 继续抢外设。
                     * 只有确实没有串流屏（外接屏模式）才退回 dId。 */
                    int bindId = StreamTarget.get().getDisplayId();
                    if (bindId <= 0) bindId = dId;
                    VirtualMouse.onDisplayReady(getContext(), bindId, Math.round(ax), Math.round(ay));
                } catch (Throwable ignored) {}
                com.alkaidlab.sunshine.service.MouseCursorTargets.register(dId);
                ShizukuCmd.bindAllExternalInputToDisplay(dId, true);
                // 系统指针精灵可能落在虚拟屏（外设绑定目标）或物理外接屏：
                // 两块都登记隐藏，避免"隐藏了物理屏、精灵却在虚拟屏"打偏。
                ShizukuCmd.registerPointerSpriteTarget(dId);
                Display physical = getDisplay();
                if (physical != null && physical.getDisplayId() > 0) {
                    ShizukuCmd.registerPointerSpriteTarget(physical.getDisplayId());
                }
                if (getContext() instanceof MainActivity) {
                    ((MainActivity) getContext()).onVirtualDisplayCreated(dId);
                }
                Log.i(TAG, "已成功创建 " + desk.name + " 特权虚拟屏 ID=" + dId);

                try {
                    Thread.sleep(60);
                    ShizukuCmd.configureDesktopDisplayEnvironment(dId, currentWindowMode, currentRotation);
                    // 沧海桌面（pref_home_type=1）：用自家 DexHome 作为 DeX 桌面。
                    // 官方桌面（默认 0）：必须显式拉起三星 SecondaryLauncher。
                    if (shouldLaunchDexHome()) {
                        Thread.sleep(100);
                        Log.i(TAG, "正在启动自绘 DeX 桌面 (DexHome)...");
                        long t0 = System.currentTimeMillis();
                        startDexHomeOnDisplay(dId);
                        Log.i(TAG, "桌面首帧绘制完成，耗时: " + (System.currentTimeMillis() - t0) + "ms");
                    } else {
                        Thread.sleep(100);
                        Log.i(TAG, "正在启动官方原生桌面 display " + dId);
                        startOfficialDesktopOnDisplay(dId);
                    }
                    MainActivity.refocusPhoneDisplayAfterExternalLaunch();
                } catch (Throwable t) {
                    Log.w(TAG, "启动桌面异常: " + t.getMessage());
                } finally {
                    hideLoading();
                }

                final int idx = desktops.indexOf(desk);
                cursorHandler.post(() -> {
                    try {
                        if (activeDesktopIndex == idx || desktops.size() == 1) switchToDesktop(idx);
                        notifyDesktopChanged();
                    } catch (Throwable ignored) {}
                });
            } else {
                Log.w(TAG, "创建虚拟屏返回无效 dId=" + dId + "，执行物理外屏直显通道");
                Display physical = getDisplay();
                if (physical != null && physical.getDisplayId() > 0) {
                    startDexHomeOnDisplay(physical.getDisplayId());
                }
                hideLoading();
            }
        } catch (Throwable t) {
            Log.e(TAG, "创建桌面虚拟屏异常: " + t.getMessage(), t);
            hideLoading();
        }
    }

    /** 单桌面：不再创建第二块虚拟屏，只把焦点切回桌面 1。 */
    public synchronized boolean createNewDesktop() {
        switchToDesktop(0);
        return false;
    }

    /** 单桌面：宫格排列没有意义，恒保持单屏聚焦。 */
    public synchronized boolean toggleGridMode() {
        isGridMode = false;
        applyDesktopLayout();
        if (mouseCursorView != null) {
            mouseCursorView.bringToFront();
        }
        return false;
    }

    public boolean isGridMode() {
        return isGridMode;
    }

    public int getTileIndexAt(float x, float y) {
        final int n = desktops.size();
        if (n <= 0) return -1;
        int cols = n == 1 ? 1 : (n <= 4 ? 2 : 3);
        int rows = n == 1 ? 1 : (n <= 2 ? 1 : (n <= 4 ? 2 : (n + 2) / 3));
        float colWidth = (float) screenWidth / cols;
        float rowHeight = (float) screenHeight / rows;
        int c = (int) (x / colWidth);
        int r = (int) (y / rowHeight);
        int idx = r * cols + c;
        if (idx >= 0 && idx < n) return idx;
        return -1;
    }

    public synchronized void applyDesktopLayout() {
        if (flDesktopStage == null) return;
        isGridMode = false;   // 单桌面：宫格分支彻底不可达
        final int n = desktops.size();
        if (n == 0) return;

        if (!isGridMode) {
            if (flGridLabelsContainer != null) {
                flGridLabelsContainer.removeAllViews();
                flGridLabelsContainer.setVisibility(View.GONE);
            }
            for (int i = 0; i < n; i++) {
                Desktop d = desktops.get(i);
                d.surfaceView.animate().cancel();
                d.surfaceView.setPivotX(screenWidth / 2f);
                d.surfaceView.setPivotY(screenHeight / 2f);
                if (i == activeDesktopIndex) {
                    d.surfaceView.animate()
                            .scaleX(1.0f)
                            .scaleY(1.0f)
                            .translationX(0f)
                            .translationY(0f)
                            .setDuration(220)
                            .start();
                    virtualDisplayId = d.displayId;
                } else {
                    d.surfaceView.setTranslationX(50000f);
                }
            }
        } else {
            if (flGridLabelsContainer != null) {
                flGridLabelsContainer.removeAllViews();
                flGridLabelsContainer.setVisibility(View.VISIBLE);
                flGridLabelsContainer.bringToFront();
            }
            if (mouseCursorView != null) {
                mouseCursorView.bringToFront();
            }

            int cols = n == 1 ? 1 : (n <= 4 ? 2 : 3);
            int rows = n == 1 ? 1 : (n <= 2 ? 1 : (n <= 4 ? 2 : (n + 2) / 3));
            float scale = n == 1 ? 0.72f : (n <= 2 ? 0.46f : (n <= 4 ? 0.44f : 0.30f));

            float colWidth = (float) screenWidth / cols;
            float rowHeight = (float) screenHeight / rows;

            for (int i = 0; i < n; i++) {
                final int idx = i;
                Desktop d = desktops.get(i);
                int r = i / cols;
                int c = i % cols;

                float targetCenterX = c * colWidth + colWidth / 2f;
                float targetCenterY = r * rowHeight + rowHeight / 2f;

                float tx = targetCenterX - (screenWidth / 2f);
                float ty = targetCenterY - (screenHeight / 2f);

                d.surfaceView.animate().cancel();
                d.surfaceView.setPivotX(screenWidth / 2f);
                d.surfaceView.setPivotY(screenHeight / 2f);
                d.surfaceView.animate()
                        .scaleX(scale)
                        .scaleY(scale)
                        .translationX(tx)
                        .translationY(ty)
                        .setDuration(260)
                        .start();

                if (flGridLabelsContainer != null) {
                    TextView tv = new TextView(getContext());
                    String labelText = d.name;
                    if (d.displayId > 0) {
                        labelText += " (VD #" + d.displayId + ")";
                    }
                    if (i == activeDesktopIndex) {
                        labelText += " · 当前聚焦";
                    }
                    tv.setText(labelText);
                    tv.setTextColor(i == activeDesktopIndex ? 0xFFFFFFFF : 0xFFCCCCCC);
                    tv.setTextSize(14f);
                    tv.setTypeface(null, android.graphics.Typeface.BOLD);
                    tv.setGravity(Gravity.CENTER);
                    tv.setBackgroundResource(R.drawable.bg_black_screen_pill);
                    tv.setPadding(32, 10, 32, 10);

                    FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
                            FrameLayout.LayoutParams.WRAP_CONTENT,
                            FrameLayout.LayoutParams.WRAP_CONTENT
                    );
                    float labelTop = targetCenterY - (screenHeight * scale) / 2f - 46f;
                    lp.leftMargin = (int) Math.max(16, targetCenterX - 110);
                    lp.topMargin = (int) Math.max(16, labelTop);
                    tv.setLayoutParams(lp);

                    tv.setOnClickListener(v -> switchToDesktop(idx));
                    flGridLabelsContainer.addView(tv);
                }
            }
        }
    }

    public synchronized boolean switchToDesktop(int index) {
        if (index < 0 || index >= desktops.size()) return false;
        activeDesktopIndex = index;
        isGridMode = false;
        applyDesktopLayout();

        if (mouseCursorView != null) {
            mouseCursorView.bringToFront();
        }

        notifyDesktopChanged();
        return true;
    }

    /** 单桌面：唯一桌面不允许关闭（关掉会释放虚拟屏、外屏直接黑屏）。 */
    public synchronized boolean closeCurrentDesktop() {
        return false;
    }

    private void notifyDesktopChanged() {
        if (desktopChangeListener != null) {
            flDesktopStage.post(() -> desktopChangeListener.onDesktopChanged());
        }
    }

    public List<Desktop> getDesktops() {
        return Collections.unmodifiableList(desktops);
    }

    public int getActiveDesktopIndex() {
        return activeDesktopIndex;
    }

    public static boolean isFromGameController(InputEvent event) {
        if (event == null) return false;
        if (event.isFromSource(InputDevice.SOURCE_GAMEPAD) || event.isFromSource(InputDevice.SOURCE_JOYSTICK)) {
            return true;
        }
        InputDevice dev = event.getDevice();
        if (dev != null && (dev.supportsSource(InputDevice.SOURCE_GAMEPAD) || dev.supportsSource(InputDevice.SOURCE_JOYSTICK))) {
            return true;
        }
        if (event instanceof KeyEvent) {
            return KeyEvent.isGamepadButton(((KeyEvent) event).getKeyCode());
        }
        return false;
    }

    @Override
    public boolean dispatchKeyEvent(@NonNull KeyEvent event) {
        int keyCode = event.getKeyCode();
        // 关键保护：无论手柄还是物理键盘的 Home / Mode 键，严禁泄露给系统 PhoneWindowManager，
        // 彻底杜绝三星底层将其唤退到官方 DeX 桌面的问题！
        if (keyCode == KeyEvent.KEYCODE_HOME || keyCode == KeyEvent.KEYCODE_BUTTON_MODE) {
            if (event.getAction() == KeyEvent.ACTION_UP) {
                if (isFromGameController(event)) {
                    DexHomeActivity act = DexHomeActivity.findForDisplay(virtualDisplayId);
                    if (act != null) {
                        act.toggleLaunchpadFromController();
                        return true;
                    }
                }
                reopenAosenDesktop();
            }
            return true;
        }

        // 游戏手柄/摇杆按键由底层硬件直接直通目标虚拟屏，严禁拦截或二次队列注入，彻底杜绝 CPU 阻塞卡死！
        if (isFromGameController(event)) {
            return super.dispatchKeyEvent(event);
        }

        if (keyCode == KeyEvent.KEYCODE_BACK) {
            if (MainActivity.tryConsumeBackFromExternal(event)) {
                return true;
            }
            // 关键保护：绝不将返回键注入到外屏虚拟屏中，杜绝误杀外屏正在运行的应用！
            if (event.getAction() == KeyEvent.ACTION_UP) {
                Context ctx = getContext();
                if (ctx instanceof MainActivity) {
                    ((MainActivity) ctx).runOnUiThread(() -> {
                        ((MainActivity) ctx).onPresentationBackRequested();
                    });
                }
            }
            return true;
        }

        // 绝不将普通按键通过已失效的 Binder 注入接口重复排队，直接让系统原生派发
        return super.dispatchKeyEvent(event);
    }

    /**
     * 外设大脑统一调度的物理按键（键盘、手柄 ABXY/方向键、蓝牙遥控器等）
     */
    private long lastExternalStickNavTime = 0L;

    public void dispatchExternalKeyEvent(KeyEvent event) {
        if (event == null) return;
        final int action = event.getAction();
        final int keyCode = event.getKeyCode();
        final boolean isGamepad = isFromGameController(event);

        // 1. 应用台呼出/关闭快捷键：Windows键 / Mac Command键 / 手柄Home键 / 手柄Start键
        if (keyCode == KeyEvent.KEYCODE_BUTTON_MODE
                || keyCode == KeyEvent.KEYCODE_HOME
                || keyCode == KeyEvent.KEYCODE_META_LEFT
                || keyCode == KeyEvent.KEYCODE_META_RIGHT
                || (isGamepad && keyCode == KeyEvent.KEYCODE_BUTTON_START)) {
            if (action == KeyEvent.ACTION_UP && !event.isCanceled()) {
                toggleLaunchpad();
            }
            return;
        }

        // 2. 当应用台打开时，或手柄操作时，拦截方向键/确认键/返回键导航应用台与 Dock
        if (isLaunchpadOpen || isGamepad) {
            if (action == KeyEvent.ACTION_DOWN) {
                switch (keyCode) {
                    case KeyEvent.KEYCODE_DPAD_UP:
                        cursorHandler.post(() -> {
                            if (directDockWeb != null) directDockWeb.evaluateJavascript("window.dockGamepadNav && window.dockGamepadNav('up');", null);
                            if (!isLaunchpadOpen && directDesktopWeb != null) directDesktopWeb.evaluateJavascript("window.gamepadNav && window.gamepadNav('up');", null);
                        });
                        return;
                    case KeyEvent.KEYCODE_DPAD_DOWN:
                        cursorHandler.post(() -> {
                            if (directDockWeb != null) directDockWeb.evaluateJavascript("window.dockGamepadNav && window.dockGamepadNav('down');", null);
                            if (!isLaunchpadOpen && directDesktopWeb != null) directDesktopWeb.evaluateJavascript("window.gamepadNav && window.gamepadNav('down');", null);
                        });
                        return;
                    case KeyEvent.KEYCODE_DPAD_LEFT:
                        cursorHandler.post(() -> {
                            if (directDockWeb != null) directDockWeb.evaluateJavascript("window.dockGamepadNav && window.dockGamepadNav('left');", null);
                            if (!isLaunchpadOpen && directDesktopWeb != null) directDesktopWeb.evaluateJavascript("window.gamepadNav && window.gamepadNav('left');", null);
                        });
                        return;
                    case KeyEvent.KEYCODE_DPAD_RIGHT:
                        cursorHandler.post(() -> {
                            if (directDockWeb != null) directDockWeb.evaluateJavascript("window.dockGamepadNav && window.dockGamepadNav('right');", null);
                            if (!isLaunchpadOpen && directDesktopWeb != null) directDesktopWeb.evaluateJavascript("window.gamepadNav && window.gamepadNav('right');", null);
                        });
                        return;
                    case KeyEvent.KEYCODE_BUTTON_A:
                    case KeyEvent.KEYCODE_DPAD_CENTER:
                    case KeyEvent.KEYCODE_ENTER:
                    case KeyEvent.KEYCODE_NUMPAD_ENTER:
                        cursorHandler.post(() -> {
                            if (directDockWeb != null) directDockWeb.evaluateJavascript("window.dockGamepadSelect && window.dockGamepadSelect();", null);
                            if (!isLaunchpadOpen && directDesktopWeb != null) directDesktopWeb.evaluateJavascript("window.gamepadSelect && window.gamepadSelect();", null);
                        });
                        return;
                    case KeyEvent.KEYCODE_BUTTON_B:
                    case KeyEvent.KEYCODE_BACK:
                    case KeyEvent.KEYCODE_ESCAPE:
                        if (isLaunchpadOpen) {
                            toggleLaunchpad();
                        } else {
                            cursorHandler.post(() -> {
                                if (directDockWeb != null) directDockWeb.evaluateJavascript("window.dockGamepadBack && window.dockGamepadBack();", null);
                                if (directDesktopWeb != null) directDesktopWeb.evaluateJavascript("window.gamepadBack && window.gamepadBack();", null);
                            });
                        }
                        return;
                }
            } else if (action == KeyEvent.ACTION_UP) {
                if (keyCode == KeyEvent.KEYCODE_DPAD_UP || keyCode == KeyEvent.KEYCODE_DPAD_DOWN
                        || keyCode == KeyEvent.KEYCODE_DPAD_LEFT || keyCode == KeyEvent.KEYCODE_DPAD_RIGHT
                        || keyCode == KeyEvent.KEYCODE_BUTTON_A || keyCode == KeyEvent.KEYCODE_DPAD_CENTER
                        || keyCode == KeyEvent.KEYCODE_ENTER || keyCode == KeyEvent.KEYCODE_NUMPAD_ENTER
                        || keyCode == KeyEvent.KEYCODE_BUTTON_B || keyCode == KeyEvent.KEYCODE_BACK
                        || keyCode == KeyEvent.KEYCODE_ESCAPE) {
                    return;
                }
            }
        }

        // 3. 普通键盘输入分发给当前活跃 WebView
        cursorHandler.post(() -> {
            try {
                WebView targetWeb = (isLaunchpadOpen && directDockWeb != null) ? directDockWeb : directDesktopWeb;
                if (targetWeb != null && targetWeb.getVisibility() == View.VISIBLE) {
                    targetWeb.dispatchKeyEvent(event);
                }
            } catch (Throwable ignored) {}
        });
    }

    public void dispatchExternalMotionEvent(MotionEvent event) {
        if (event == null) return;
        if (event.isFromSource(InputDevice.SOURCE_JOYSTICK) || event.isFromSource(InputDevice.SOURCE_GAMEPAD)) {
            float hatX = event.getAxisValue(MotionEvent.AXIS_HAT_X);
            float hatY = event.getAxisValue(MotionEvent.AXIS_HAT_Y);
            float axisX = event.getAxisValue(MotionEvent.AXIS_X);
            float axisY = event.getAxisValue(MotionEvent.AXIS_Y);

            // 摇杆/十字键归中时立即重置冷却，保证连续点按零延迟
            if (Math.abs(hatX) < 0.2f && Math.abs(hatY) < 0.2f && Math.abs(axisX) < 0.2f && Math.abs(axisY) < 0.2f) {
                lastExternalStickNavTime = 0L;
                return;
            }

            long now = SystemClock.uptimeMillis();
            if (now - lastExternalStickNavTime > 180L) {
                String dir = null;
                if (hatX < -0.5f || axisX < -0.5f) dir = "left";
                else if (hatX > 0.5f || axisX > 0.5f) dir = "right";
                else if (hatY < -0.5f || axisY < -0.5f) dir = "up";
                else if (hatY > 0.5f || axisY > 0.5f) dir = "down";

                if (dir != null) {
                    lastExternalStickNavTime = now;
                    final String finalDir = dir;
                    cursorHandler.post(() -> {
                        if (directDockWeb != null) {
                            directDockWeb.evaluateJavascript("window.dockGamepadNav && window.dockGamepadNav('" + finalDir + "');", null);
                        }
                        if (!isLaunchpadOpen && directDesktopWeb != null) {
                            directDesktopWeb.evaluateJavascript("window.gamepadNav && window.gamepadNav('" + finalDir + "');", null);
                        }
                    });
                }
            }
            return;
        }
        cursorHandler.post(() -> {
            try {
                dispatchGenericMotionEvent(event);
            } catch (Throwable ignored) {}
        });
    }

    private void startDexHomeOnDisplay(int dId) {
        CanghaiErrorCollector.logExt("DeXPresentation.startDexHomeOnDisplay 发起启动, 目标 dId=" + dId);
        if (PrivilegeRoute.mode() == PrivilegeRoute.Mode.ROOT || ShizukuCmd.isShizukuReady()) {
            try {
                // -f 0x30000000 = NEW_TASK|SINGLE_TOP：复用桌面 task。少了它，三星 DeX 副屏会按
                // freeform 另起一个带标题栏的小窗口桌面 —— 用户看到的就是「桌面有时是窗口有时全屏」。
                ShizukuCmd.execute("am start"
                        + " -n " + BuildConfig.APPLICATION_ID + "/" + DexHomeActivity.class.getName()
                        + " --display " + dId
                        + " -f 0x30000000"
                        + " --windowingMode 1 --ei " + DexHomeActivity.EXTRA_WINDOWING_MODE + " " + currentWindowMode);
            } catch (Throwable ignored) {}
        }
    }

    /**
     * 官方原生桌面：显式把三星 DeX 副屏桌面（SecondaryLauncher）拉到本虚拟屏。
     *
     * 虚拟屏是私有屏（PRESENTATION），SystemUI 不会自动把 SecondaryLauncher
     * 排到这块屏上；旧代码只「等待接管」→ 黑屏 + 鼠标，所以必须显式启动。
     * Root 通道现在由 aosen.rootbridge 以 root UID 创建虚拟屏，保住了
     * TRUSTED + SHOULD_SHOW_SYSTEM_DECORATIONS，壁纸 / dock / 标题栏才有机会出现。
     *
     * ⚠️ 绝不能给 am start 加 `-c android.intent.category.SECONDARY_HOME`：那会让
     * ActivityStarter 按 HOME 语义启动（task 变 type=home），系统顺手把手机主屏的
     * launcher 带回前台、把我们的 MainActivity `m=TO_BACK`，用户看到的就是「点一下
     * 官方桌面软件就闪退」。实机验证：纯显式 `-n` 启动得到 type=standard 的 task，
     * SecondaryLauncher 在虚拟屏 visible=true / Resumed / 有壁纸窗口，且不动主屏栈。
     */
    private void startOfficialDesktopOnDisplay(int dId) {
        final String samsungHome =
                "com.sec.android.app.launcher/com.honeyspace.dexservice.SecondaryLauncher";

        java.util.List<String> candidates = new java.util.ArrayList<>();
        // 真机 One UI 8.5 / Android 16 已确认该组件存在，放首位。
        candidates.add(samsungHome);

        // 其他固件版本组件名可能不同，再向系统查询所有 SECONDARY_HOME 候选。
        // 注意：绝不能用 `cmd package resolve-activity`，本机有两个候选、无默认首选时
        // 它会返回 android/com.android.internal.app.ResolverActivity（选择框），
        // 误启动后外屏会弹出「选择桌面」对话框，比黑屏更糟。
        String queried = ShizukuCmd.execute("cmd package query-activities --brief"
                + " -a android.intent.action.MAIN"
                + " -c android.intent.category.SECONDARY_HOME");
        for (String c : parseResolvedComponents(queried)) {
            if (!candidates.contains(c)) candidates.add(c);
        }
        Log.i(TAG, "副屏桌面候选: " + candidates);

        for (String component : candidates) {
            // 与 VirtualDisplayUserService.start() 已实测的方案对齐：
            // 用普通 CATEGORY_LAUNCHER（不是 SECONDARY_HOME）启动，避免 HOME 语义
            // 把 MainActivity 压后台 / 触发三星「外接屏模式」接管主屏；
            // -f 0x30000000 = NEW_TASK|SINGLE_TOP，复用桌面 task，避免反复重建图标。
            // 2>&1 必须加：桌面已在前台时 am start 只往 stderr 写
            // "Warning: Activity not started, intent has been delivered..."，
            // 而 RootCmd.execute() 只读 stdout，不重定向会被误判失败。
            String out = ShizukuCmd.execute("am start --display " + dId
                    + " --windowingMode 1"
                    + " -f 0x30000000"
                    + " -a android.intent.action.MAIN"
                    + " -c android.intent.category.LAUNCHER"
                    + " -n " + component + " 2>&1");
            boolean ok = launchSucceeded(out);
            Log.i(TAG, "官方原生桌面启动 -> display " + dId + " / " + component
                    + " : " + (ok ? "OK" : String.valueOf(out)));
            if (ok) return;
        }

        Log.w(TAG, "官方桌面组件不可用，回退沧海桌面，避免外屏黑屏");
        startDexHomeOnDisplay(dId);
    }

    /**
     * am start 成功输出 "Starting: Intent {...}"；目标已在前台时只输出
     * "Warning: Activity not started, intent has been delivered..."（同样是成功）；
     * 失败会带 Error type / Exception / unable to resolve / not found 等。
     */
    private static boolean launchSucceeded(String out) {
        if (out == null) return false;
        String s = out.toLowerCase(Locale.US);
        return !s.contains("error") && !s.contains("exception")
                && !s.contains("unable to resolve") && !s.contains("no activity found")
                && !s.contains("does not exist") && !s.contains("not allowed");
    }

    /**
     * 从 `cmd package query-activities --brief` 输出里挑出所有 "pkg/.Activity" 行。
     * 过滤掉选择框（ResolverActivity）和 SystemUI 封面屏 Home，它们都不是 DeX 桌面。
     */
    private static java.util.List<String> parseResolvedComponents(String out) {
        java.util.List<String> result = new java.util.ArrayList<>();
        if (out == null) return result;
        for (String line : out.split("\\r?\\n")) {
            String s = line.trim();
            if (s.isEmpty() || s.contains("=") || s.contains(" ") || s.startsWith("no ")) continue;
            int slash = s.indexOf('/');
            if (slash <= 0 || slash >= s.length() - 1) continue;
            if (s.contains("ResolverActivity") || s.contains("com.android.internal")) continue;
            if (s.startsWith("com.android.systemui/")) continue;
            if (!result.contains(s)) result.add(s);
        }
        return result;
    }

    /** 保证手柄或按键触发 Home 时停留在自绘桌面 */
    public void reopenAosenDesktop() {
        int dId = getVirtualDisplayId();
        if (dId > 0 && shouldLaunchDexHome()) {
            inputExecutor.execute(() -> startDexHomeOnDisplay(dId));
        }
    }

    private long mousePrimaryDownTime = 0L;
    private boolean isMousePrimaryPressed = false;
    private boolean isMousePrimaryDragging = false;
    private float lastPrimaryDownX = 0f;
    private float lastPrimaryDownY = 0f;

    public synchronized void onMousePrimaryDown(float px, float py) {
        if (isMousePrimaryPressed) return;
        isMousePrimaryPressed = true;
        isMousePrimaryDragging = false;
        mousePrimaryDownTime = SystemClock.uptimeMillis();
        lastPrimaryDownX = px;
        lastPrimaryDownY = py;
        // 按下瞬间立即派发真实鼠标 ACTION_DOWN，支持长按抓取、拖拽与游戏
        injectMousePrimaryFrame(MotionEvent.ACTION_DOWN, px, py);
    }

    public synchronized void onMousePrimaryMove(float px, float py) {
        if (!isMousePrimaryPressed) return;
        isMousePrimaryDragging = true;
        // 拖拽过程实时派发真实鼠标 ACTION_MOVE
        injectMousePrimaryFrame(MotionEvent.ACTION_MOVE, px, py);
    }

    public synchronized void onMousePrimaryUp(float px, float py) {
        if (!isMousePrimaryPressed) return;
        isMousePrimaryPressed = false;
        isMousePrimaryDragging = false;
        // 抬起瞬间立即派发真实鼠标 ACTION_UP
        injectMousePrimaryFrame(MotionEvent.ACTION_UP, px, py);
    }

    /**
     * 直显模式的命中测试：应用台打开 / 底部 Dock 条 / 顶部菜单栏归 Dock 层，
     * 其余归桌面层。两块 WebView 都是 MATCH_PARENT 全屏，坐标可以直接互用。
     */
    private WebView directWebAt(float x, float y) {
        if (directDockWeb == null) return directDesktopWeb;
        if (isLaunchpadOpen) return directDockWeb;
        /* 用 Dock 页面自己上报的矩形，别再按「底部 110dp」去猜。
         * 猜错了后果很直接：Dock 图标的右键菜单是往上弹的，菜单顶部在 110dp 之外，
         * 于是菜单里的点击落到桌面那一层 —— 表现就是「右键能弹出菜单，但点菜单
         * 有时候没反应」。应用台打开时是全屏归 Dock，所以那边一直 100% 正常。 */
        if (directDockHitValid && directDockHitRect.contains((int) x, (int) y)) {
            return directDockWeb;
        }
        float density = getContext().getResources().getDisplayMetrics().density;
        // 顶部菜单栏也归 Dock 页
        if (y <= 42 * density) return directDockWeb;
        // 页面还没上报边界之前的兜底（老规则）
        if (!directDockHitValid && y >= (screenHeight - 110 * density)) {
            return directDockWeb;
        }
        return directDesktopWeb;
    }

    /**
     * 唯一闸门：真设备接管后，这个窗口不再有「物理鼠标事件」这回事。
     *
     * <p>现在的运行形态只剩两种，中间那些「哪块屏、哪路设备、走 root 还是 Shizuku」的判断
     * 全都是历史包袱：
     * <ul>
     *   <li>有特权：物理外设被外设大脑 EVIOCGRAB 独占，系统根本看不见它；
     *       另有一台 uinput 设备替它交给系统，指针由系统自己走、自己算落点。
     *       所以事件要么根本到不了这个窗口，要么来自我们自己那台虚拟设备。</li>
     *   <li>无特权：没有虚拟屏，走 directWeb 直派，压根不经过这里。</li>
     * </ul>
     *
     * <p>结论：一旦走到这里还带着物理鼠标的事件，只可能是独占失败了。那种情况下再补注入
     * 只会让屏幕上出现第二根指针（系统一根、自绘一根），越补越乱。这里统一放手，
     * 并把「那一刻到底是谁来了」写进日志——这是判断独占有没有真的生效的唯一证据。
     */
    private boolean virtualDeviceOwnsInput(MotionEvent event, String where) {
        if (!VirtualMouse.isActive()) return false;
        if (!sVirtualDeviceGateLogged) {
            sVirtualDeviceGateLogged = true;
            String src = "null";
            try {
                if (event != null) {
                    src = "source=0x" + Integer.toHexString(event.getSource())
                            + " tool=" + event.getToolType(0)
                            + " action=" + event.getActionMasked();
                }
            } catch (Throwable ignored) {}
            CanghaiErrorCollector.logExt("[旧通道关闭] 真设备已接管，" + where
                    + " 上的一切注入分支作废；此刻仍有事件到达（" + src
                    + "）说明物理鼠标没被独占住");
        }
        return true;
    }

    @Override
    public boolean dispatchTouchEvent(MotionEvent event) {
        // 自家虚拟外设（绑在这块屏上的那台）的事件是系统派下来的：它已经算好了落点，
        // 这里只交给本层 View 树，绝不能再走一遍注入逻辑（那就是一次点击变两次）。
        if (fromOwnVirtualMouse(event)) {
            return super.dispatchTouchEvent(event);
        }
        if (virtualDeviceOwnsInput(event, "dispatchTouchEvent")) {
            return super.dispatchTouchEvent(event);
        }
        if (isFromGameController(event)) {
            return super.dispatchTouchEvent(event);
        }
        if (isMouseEvent(event)) {
            if (!isCursorOnThisScreen) {
                setCursorFocusScreen(true, null);
            }
            onExternalMouseMoved(event.getX(), event.getY());
            if (directDesktopWeb != null) {
                /* 直显模式没有系统帮我们做命中测试：所有输入都从这一个入口手动派发。
                 * 以前这里写死 directDesktopWeb，于是底部 Dock 栏、应用台（都在
                 * directDockWeb 里）一次都收不到点击 —— Dock 图标点了没反应、应用台
                 * 里点应用没反应、Dock 右键更没反应。按坐标选窗口。 */
                final int a = event.getActionMasked();
                WebView target;
                if (a == MotionEvent.ACTION_DOWN) {
                    target = directWebAt(event.getX(), event.getY());
                    directPressWeb = target;
                } else if (a == MotionEvent.ACTION_UP || a == MotionEvent.ACTION_CANCEL) {
                    target = (directPressWeb != null) ? directPressWeb
                            : directWebAt(event.getX(), event.getY());
                    directPressWeb = null;
                } else {
                    // 拖动过程中跟着按下时那个窗口走，避免中途跨区把 UP 派给另一个页面
                    target = (directPressWeb != null) ? directPressWeb
                            : directWebAt(event.getX(), event.getY());
                }
                if (target != null) {
                    target.dispatchTouchEvent(event);
                }
                return true;
            }
            /* 旧路径已整段删除：物理鼠标已被外设大脑独占，它的事件到不了这个窗口；
             * 虚拟屏上的点击由系统按 uinput 那台设备自己派发。这里既不该补注入，
             * 也不该再判断「当前是哪块屏、走 root 还是 Shizuku」。
             *
             * 但无特权直显下「鼠标点空白处唤起/收起应用台」这条要留着：
             * 那条路径没有虚拟屏，virtualDisplayId 恒为 0。 */
            if (virtualDisplayId <= 0) {
                cursorHandler.post(this::toggleLaunchpad);
            }
            return true;
        }
        if (directDesktopWeb != null) {
            WebView target = directWebAt(event.getX(), event.getY());
            if (target != null) target.dispatchTouchEvent(event);
            return true;
        }
        handleExternalTouchEvent(event);
        return true;
    }

    @Override
    public boolean dispatchGenericMotionEvent(MotionEvent event) {
        if (fromOwnVirtualMouse(event)) {
            return super.dispatchGenericMotionEvent(event);
        }
        if (virtualDeviceOwnsInput(event, "dispatchGenericMotionEvent")) {
            return super.dispatchGenericMotionEvent(event);
        }
        if (isFromGameController(event)) {
            // 摇杆高频模拟轴事件硬件直通，严禁排队注入或误判为鼠标，彻底杜绝 CPU 阻塞
            return super.dispatchGenericMotionEvent(event);
        }

        final boolean isMouse = isMouseEvent(event);
        if (isMouse) {
            onExternalMouseMoved(event.getX(), event.getY());
        }

        int action = event.getActionMasked();
        if (directDesktopWeb != null && isMouse) {
            if (action == MotionEvent.ACTION_BUTTON_PRESS || action == MotionEvent.ACTION_BUTTON_RELEASE) {
                final int ab = event.getActionButton();
                final int bs = event.getButtonState();
                final boolean secondary = (ab & MotionEvent.BUTTON_SECONDARY) != 0 || (bs & MotionEvent.BUTTON_SECONDARY) != 0;
                if (secondary && action == MotionEvent.ACTION_BUTTON_PRESS) {
                    triggerDirectContextMenu(event.getX(), event.getY());
                    return true;
                }
                /* 左键的按钮帧必须在这里截住。
                 * 物理鼠标按左键会同时产生两条流：ACTION_DOWN/UP（走 dispatchTouchEvent）
                 * 和 ACTION_BUTTON_PRESS/RELEASE（走到这里）。以前两条都转给页面，等于
                 * 一次点击点两下 —— 应用台打开后立刻又被第二个 click 关掉（表现就是
                 * 闪一下），列表项也会被点两次。中键/侧键没有触屏流，才需要继续往下转。 */
                if (action == MotionEvent.ACTION_BUTTON_RELEASE || ab == 0
                        || (ab & MotionEvent.BUTTON_PRIMARY) != 0) {
                    return true;
                }
            }
            WebView directTarget = directWebAt(event.getX(), event.getY());
            if (directTarget != null) directTarget.dispatchGenericMotionEvent(event);
            return true;
        }
        /* 旧注入分支已整段删除（左键 DOWN/UP、右键长按、中键侧键、滚轮合并）。
         * 这四个分支之所以存在，是因为以前要用注入把物理鼠标「翻译」成虚拟屏上的事件；
         * 现在物理鼠标在大脑里被独占、另有一台 uinput 设备替它交给系统，
         * 系统自己会把 DOWN/MOVE/UP/滚轮派到指针所在的那个窗口。再补一遍就是双份。 */
        return super.dispatchGenericMotionEvent(event);
    }

    /** 滚轮按 16ms 合并，滚动量累加后只发一帧。 */
    private void queueScroll(MotionEvent event, int dId) {
        final float v = event.getAxisValue(MotionEvent.AXIS_VSCROLL);
        final float h = event.getAxisValue(MotionEvent.AXIS_HSCROLL);
        synchronized (scrollLock) {
            pendingScrollDisplayId = dId;
            pendingScrollX = toVirtualX(event.getX());
            pendingScrollY = toVirtualY(event.getY());
            pendingVScroll += v;
            pendingHScroll += h;
            if (!scrollFlushScheduled) {
                scrollFlushScheduled = true;
                cursorHandler.postDelayed(this::flushScroll, 16L);
            }
        }
    }

    private void flushScroll() {
        int dId;
        float x, y, v, h;
        synchronized (scrollLock) {
            scrollFlushScheduled = false;
            dId = pendingScrollDisplayId;
            x = pendingScrollX;
            y = pendingScrollY;
            v = pendingVScroll;
            h = pendingHScroll;
            pendingVScroll = 0f;
            pendingHScroll = 0f;
        }
        if (dId <= 0 || (v == 0f && h == 0f)) return;
        final int dd = dId;
        final float xx = x, yy = y, vv = v, hh = h;
        inputExecutor.execute(() -> injectMouseEvent(
                dd, MotionEvent.ACTION_SCROLL, 0, 0, xx, yy, vv, hh));
    }

    /**
     * 统一注入入口：优先走常驻 Root 桥（一条 Binder，零进程开销）；
     * 非 Root 通道走原 Shizuku binder；桥不可用时滚轮退到低频 shell scroll。
     */
    private void injectMouseEvent(int dId, int action, int buttonState, int actionButton,
                                  float x, float y, float vscroll, float hscroll) {
        if (dId <= 0) return;
        if (PrivilegeRoute.mode() == PrivilegeRoute.Mode.ROOT) {
            if (RootInputBridge.inject(getContext(), dId, action, buttonState, actionButton,
                    x, y, vscroll, hscroll, InputDevice.SOURCE_MOUSE)) return;
            // 常驻桥不可用（守护进程报过注入失败）时的保命路径：点击退到 shell 一次 tap。
            // 只兜 PRESS，避免 RELEASE 再补一次变成双击；拖动/悬停仍然放弃，宁可不动也不要乱动。
            if (action == MotionEvent.ACTION_SCROLL) {
                ShizukuCmd.execute(String.format(Locale.US,
                        "input mouse -d %d scroll %.1f %.1f --axis VSCROLL,%.2f --axis HSCROLL,%.2f",
                        dId, x, y, vscroll, hscroll));
            } else if (action == MotionEvent.ACTION_BUTTON_PRESS
                    && actionButton == MotionEvent.BUTTON_PRIMARY) {
                RootCmd.execute(String.format(Locale.US, "input -d %d tap %.1f %.1f", dId, x, y));
            }
            return;
        }
        MotionEvent ev = buildMouseEvent(action, buttonState, actionButton, x, y, vscroll, hscroll);
        if (ev == null) return;
        try {
            if (!ShizukuCmd.injectMotionEvent(ev, dId) && action == MotionEvent.ACTION_SCROLL) {
                ShizukuCmd.execute(String.format(Locale.US,
                        "input mouse -d %d scroll %.1f %.1f --axis VSCROLL,%.2f --axis HSCROLL,%.2f",
                        dId, x, y, vscroll, hscroll));
            }
        } finally {
            ev.recycle();
        }
    }

    /** 构造保留 buttonState / actionButton / 滚轮轴的 SOURCE_MOUSE 事件。 */
    private static MotionEvent buildMouseEvent(int action, int buttonState, int actionButton,
                                               float x, float y, float vscroll, float hscroll) {
        long now = android.os.SystemClock.uptimeMillis();
        MotionEvent.PointerProperties props = new MotionEvent.PointerProperties();
        props.id = 0;
        props.toolType = MotionEvent.TOOL_TYPE_MOUSE;
        MotionEvent.PointerCoords coords = new MotionEvent.PointerCoords();
        coords.x = x;
        coords.y = y;
        coords.pressure = (action == MotionEvent.ACTION_DOWN) ? 1f : 0f;
        coords.size = 1f;
        coords.setAxisValue(MotionEvent.AXIS_VSCROLL, vscroll);
        coords.setAxisValue(MotionEvent.AXIS_HSCROLL, hscroll);
        MotionEvent ev = MotionEvent.obtain(now, now, action, 1,
                new MotionEvent.PointerProperties[]{props},
                new MotionEvent.PointerCoords[]{coords},
                0, buttonState, 1f, 1f, 0, 0,
                InputDevice.SOURCE_MOUSE, 0);
        if (actionButton != 0) {
            // setActionButton 是 @hide，compileSdk 的 android.jar 不暴露；反射补齐，
            // 个别固件拦截时降级为「只有 buttonState」，主键点击语义仍然成立。
            try {
                java.lang.reflect.Method m = MotionEvent.class
                        .getDeclaredMethod("setActionButton", int.class);
                m.invoke(ev, actionButton);
            } catch (Throwable ignored) {}
        }
        return ev;
    }

    /**
     * 黑屏触控专用：把自绘光标强制钉在外屏，并压住系统原生箭头。
     *
     * 之前的问题（用户实测）：
     *  - 单指滑动外屏不出现鼠标：光标焦点还留在「手机主屏」，
     *    setCursorFocusScreen(false) 把自绘光标藏了，只剩系统箭头；
     *  - 外屏显示的是系统默认鼠标：系统指针精灵被重置回 TYPE_ARROW，
     *    而透明图标的注入依赖 Shizuku 的 IInputManager，纯 Root 通道拿不到。
     *
     * 这里两件事一起做：焦点强制给外屏 + 不停重喷隐藏系统精灵。
     */
    public void enterBlackTouch() {
        setCursorFocusScreen(true, null);
        if (mouseCursorView != null) {
            mouseCursorView.setVisibility(View.VISIBLE);
            if (flCursorLayer != null) flCursorLayer.bringToFront();
            mouseCursorView.bringToFront();
        }
        // ShizukuCmd.rehideSystemPointerSprite(); // 屏蔽旧特权指针隐藏，统一由外设大脑与自绘光标管理
        // ShizukuCmd.keepSystemPointerHidden();
        try {
            nudgeSystemPointerHidden();
        } catch (Throwable ignored) { }
        // 立刻把箭头画出来，别让用户等第一次滑动
        float initX = cursorPositioned ? Math.min(screenWidth, lastMouseX) : (screenWidth / 2f);
        float initY = cursorPositioned ? Math.min(screenHeight, lastMouseY) : (screenHeight / 2f);
        lastMouseX = initX;
        lastMouseY = initY;
        cursorPositioned = true;
        applyCursorPosition(initX, initY);
        CanghaiErrorCollector.pointerAudit("F-黑屏触控基准",
                "初始=(" + initX + "," + initY + ") virtualDisplayId=" + virtualDisplayId
                        + " ｜这一路画箭头与注入 MotionEvent 用的是同一个绝对坐标，"
                        + "所以既能点安卓又能点 HTML，是三套里唯一正确的一套", true);
    }

    /**
     * 黑屏触控的滑动：箭头跟手 + 压系统箭头 + 进程内通知外屏桌面悬停（Dock 自动弹出）。
     * 绝不向虚拟屏注入 SOURCE_MOUSE MOVE，否则 InputFlinger 会在虚拟屏点亮系统默认鼠标箭头！
     */
    private long lastHoverDispatchTime = 0;
    private void dispatchHoverToDirectWeb(float x, float y) {
        long now = SystemClock.uptimeMillis();
        if (now - lastHoverDispatchTime < 25) return;
        lastHoverDispatchTime = now;
        cursorHandler.post(() -> {
            try {
                MotionEvent hover = MotionEvent.obtain(now, now, MotionEvent.ACTION_HOVER_MOVE, x, y, 0);
                if (directDesktopWeb != null) directDesktopWeb.dispatchGenericMotionEvent(hover);
                if (directDockWeb != null) directDockWeb.dispatchGenericMotionEvent(hover);
                hover.recycle();
            } catch (Throwable ignored) {}
        });
    }

    public void moveBlackTouchCursor(float x, float y) {
        if (!isCursorOnThisScreen) {
            setCursorFocusScreen(true, null);
        }
        updateCursor(x, y);
        // ShizukuCmd.keepSystemPointerHidden(); // 屏蔽旧特权指针隐藏
        if (virtualDisplayId > 0) {
            DexHomeActivity.onExternalPointerHover(virtualDisplayId, toVirtualX(x), toVirtualY(y), screenHeight);
        } else {
            // 只有左键未按下时派发悬停！按下拖拽时派发 HOVER 会破坏 WebView Pointer Capture 导致拖拽中断！
            if (!isMousePrimaryPressed) {
                dispatchHoverToDirectWeb(x, y);
            }
        }
    }

    /** 原生鼠标帧注入（DOWN / MOVE / UP，支持拖拽、窗口控制与小组件移动） */
    private void injectMousePrimaryFrame(int action, float px, float py) {
        CanghaiErrorCollector.pointerAudit("D-点击分支",
                "action=" + MotionEvent.actionToString(action) + " 入参=(" + px + "," + py + ")"
                        + " VirtualMouse.isActive=" + VirtualMouse.isActive()
                        + " virtualDisplayId=" + virtualDisplayId
                        + " ｜" + (VirtualMouse.isActive()
                                ? "走 uinput BTN_LEFT，命中点 = 系统黑箭头位置（与自绘箭头无关）"
                                : "走 targetWeb.dispatchTouchEvent，只命中沧海 HTML 桌面"),
                true);
        /* 真设备接管：不再往系统派发队列注入。
         * 注入的那一帧会被 dispatcher 判成「别的设备抢指针」，把手机窗口的
         * PointerCapture 收回（日志里每次按下 26ms 后「已释放」就是它），
         * 抬手随即漏到手机。改成往 /dev/uinput 的鼠标节点写按键，系统当硬件读，
         * 手机这边的捕获一点都不受影响。位移不用管：相对量已经由外设大脑写过去了。 */
        if (VirtualMouse.isActive()) {
            if (action == MotionEvent.ACTION_DOWN) {
                VirtualMouse.button(MotionEvent.BUTTON_PRIMARY, true);
            } else if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
                VirtualMouse.button(MotionEvent.BUTTON_PRIMARY, false);
            }
            return;
        }
        if (virtualDisplayId <= 0) {
            float density = getContext().getResources().getDisplayMetrics().density;
            boolean isDockArea = (py >= (screenHeight - 110 * density));
            boolean isMenuBarArea = (py <= 42 * density);
            final WebView targetWeb = (isLaunchpadOpen || isDockArea || isMenuBarArea) && directDockWeb != null
                    ? directDockWeb : directDesktopWeb;
            if (targetWeb != null) {
                Runnable r = () -> {
                    try {
                        long now = SystemClock.uptimeMillis();
                        long dt = (mousePrimaryDownTime > 0) ? mousePrimaryDownTime : now;
                        MotionEvent ev = MotionEvent.obtain(dt, now, action, px, py, 0);
                        ev.setSource(InputDevice.SOURCE_TOUCHSCREEN);
                        if (action == MotionEvent.ACTION_DOWN || action == MotionEvent.ACTION_MOVE) {
                            targetWeb.requestDisallowInterceptTouchEvent(true);
                        } else if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
                            targetWeb.requestDisallowInterceptTouchEvent(false);
                        }
                        targetWeb.dispatchTouchEvent(ev);
                    } catch (Throwable ignored) {}
                };
                if (android.os.Looper.myLooper() == android.os.Looper.getMainLooper()) {
                    r.run();
                } else {
                    cursorHandler.post(r);
                }
            }
            return;
        }
        final int dId = virtualDisplayId;
        MainActivity.onExternalInteractionOrIme();

        /* 左键一律按「鼠标协议」注入，绝不能再翻成 SOURCE_TOUCHSCREEN。
         *
         * 沧海桌面是鼠标页面（mousedown / mousemove / mouseup，框选、拖拽、Dock 全在
         * mousemove 里算）。发触摸以后 Chromium 会把鼠标兼容事件压到手势结束才补发：
         * 按住拖动期间页面一帧 mouse 事件都收不到（指针不动、没有拖拽），一松手才
         * 一次性补出 mousedown + mousemove —— 用户看到的就是「原地点击好使，按住
         * 拖动指针不跟，松开后突然开始框选」。发真鼠标事件，三个动作都是实时的。 */
        final int bs = (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL)
                ? 0 : MotionEvent.BUTTON_PRIMARY;
        final int ab = (action == MotionEvent.ACTION_DOWN) ? MotionEvent.BUTTON_PRIMARY : 0;

        if (PrivilegeRoute.mode() == PrivilegeRoute.Mode.ROOT) {
            inputExecutor.execute(() -> {
                if (action == MotionEvent.ACTION_DOWN) {
                    /* 离散鼠标按键（DOWN/UP）由系统按「自己记录的指针位置」挑目标窗口，
                     * 注入事件本身不带指针位置。这块虚拟屏此前只进过触摸/滚轮，系统那边
                     * 的指针还停在 (0,0) 或手机屏，于是左键按下被派给了一个不存在的
                     * 窗口 —— 表现就是「右键（走 JS）正常，左键完全没反应」。
                     * 先补一帧 HOVER_MOVE 把指针钉到这一点，再发 DOWN。 */
                    RootInputBridge.inject(getContext(), dId, MotionEvent.ACTION_HOVER_MOVE,
                            0, 0, px, py, 0f, 0f, InputDevice.SOURCE_MOUSE);
                }
                boolean ok = RootInputBridge.inject(getContext(), dId, action,
                        bs, ab, px, py, 0f, 0f, InputDevice.SOURCE_MOUSE);
                traceRootFrame(action, dId, px, py, ok);
                if (!ok && action == MotionEvent.ACTION_UP) {
                    ShizukuCmd.execute(String.format(Locale.US,
                            "input -d %d tap %.1f %.1f", dId, px, py));
                }
            });
            return;
        }

        inputExecutor.execute(() -> injectMouseEvent(dId, action, bs, ab, px, py, 0f, 0f));
    }

    /** 把真正发出去的左键帧记进调试面板；拖动帧限频，避免刷屏。 */
    private long lastInjectTraceAt = 0L;

    private void traceRootFrame(int action, int dId, float x, float y, boolean ok) {
        String name = (action == MotionEvent.ACTION_DOWN) ? "按下"
                : (action == MotionEvent.ACTION_UP) ? "抬起" : "拖动";
        if (action == MotionEvent.ACTION_MOVE) {
            long now = SystemClock.uptimeMillis();
            if (now - lastInjectTraceAt < 250L) return;
            lastInjectTraceAt = now;
        }
        String msg = "串流鼠标 " + name + " dId=" + dId
                + " (" + Math.round(x) + "," + Math.round(y) + ")" + (ok ? "" : " 写入失败");
        InputTrace.note("串流鼠标", msg);
        // 按下/抬起各留一行在主日志里：面板之外也能确认「到底发没发出去」。
        if (action == MotionEvent.ACTION_DOWN || !ok) {
            CanghaiErrorCollector.logExt(msg);
        }
    }

    private boolean fromOwnVirtualMouse(MotionEvent event) {
        try {
            InputDevice d = event == null ? null : event.getDevice();
            return d != null && VirtualMouse.isOwnDevice(d.getName());
        } catch (Throwable t) {
            return false;
        }
    }

    private boolean isMouseEvent(MotionEvent event) {        if (event == null) return false;
        if (event.isFromSource(InputDevice.SOURCE_MOUSE) || event.isFromSource(InputDevice.SOURCE_TRACKBALL)) {
            return true;
        }
        for (int i = 0; i < event.getPointerCount(); i++) {
            if (event.getToolType(i) == MotionEvent.TOOL_TYPE_MOUSE) {
                return true;
            }
        }
        return false;
    }

    /**
     * USB 物理鼠标接管：与 Moonlight / 黑屏触控完全共用 updateCursor 这条绘制路径。
     *
     * 旧实现用 isSystemPointerHidden() 做开关：判定"系统精灵没隐藏成功"时主动撤掉
     * 软件指针、让系统黑箭头接管。但该标记只是进程内集合、且被 restore 无条件清空，
     * 于是 USB 成了三条输入源里唯一显示系统黑色图标的一路。现在无条件自绘，
     * 黑箭头改由 keepSystemPointerHidden() 节流重喷压住（隐藏失败也不影响白指针）。
     */
    private void onExternalMouseMoved(float x, float y) {
        if (!isCursorOnThisScreen) {
            setCursorFocusScreen(true, null);
        }

        float clampedX = Math.max(0, Math.min(x, getRawWidth()));
        float clampedY = Math.max(0, Math.min(y, getRawHeight()));
        lastMouseX = clampedX;
        lastMouseY = clampedY;
        cursorPositioned = true;

        // 统一由外设大脑与软件自绘光标管理：保证自绘指针在当前外屏处于可见状态
        if (mouseCursorView != null) {
            applyCursorPosition(clampedX, clampedY);
        }

        if (virtualDisplayId > 0) {
            DexHomeActivity.onExternalPointerHover(virtualDisplayId, toVirtualX(clampedX), toVirtualY(clampedY), screenHeight);
        }
    }

    public float getLastMouseX() {
        return cursorPositioned ? lastMouseX : (getRawWidth() / 2f);
    }
    public float getLastMouseY() {
        return cursorPositioned ? lastMouseY : (getRawHeight() / 2f);
    }

    /**
     * 外设大脑专用的物理位移驱动入口
     */
    public void applyPointerDelta(float rawDx, float rawDy, float baseSensitivity) {
        float dist = (float) Math.hypot(rawDx, rawDy);
        float accel = 1.0f + Math.min(2.2f, dist / 15.0f);
        float pointerSpeed = com.alkaidlab.sunshine.service.MouseCursorPrefs.getSpeed();
        float factor = baseSensitivity * accel * pointerSpeed;
        float dx = rawDx * factor;
        float dy = rawDy * factor;

        float curX = getLastMouseX();
        float curY = getLastMouseY();
        float newX = Math.max(0, Math.min(screenWidth, curX + dx));
        float newY = Math.max(0, Math.min(screenHeight, curY + dy));

        int hostDisplay = -1;
        try {
            android.view.Display d = getDisplay();
            if (d != null) hostDisplay = d.getDisplayId();
        } catch (Throwable ignored) {}
        CanghaiErrorCollector.pointerAudit("A-桌面自绘",
                "raw=(" + rawDx + "," + rawDy + ") factor=" + factor
                        + " 自绘=(" + newX + "," + newY + ")"
                        + " 本屏display=" + hostDisplay + " virtualDisplayId=" + virtualDisplayId
                        + " 画布=" + screenWidth + "x" + screenHeight
                        + " ｜带 accel/pointerSpeed 自己积分，只画 mouseCursorView + 给 WebView 发 hover，"
                        + "完全不决定系统命中", false);

        moveBlackTouchCursor(newX, newY);
    }

    /**
     * 设置本屏幕光标绘制层的焦点状态：
     * 当光标漫游到手机或其他屏幕时，本屏隐藏自绘光标，左上角显示徽章告知位置；
     * 重新获得焦点时隐藏徽章，恢复自绘光标。
     */
    public void setCursorFocusScreen(boolean onThisScreen, String currentScreenName) {
        this.isCursorOnThisScreen = onThisScreen;
        cursorHandler.post(() -> {
            if (!onThisScreen) {
                if (mouseCursorView != null) {
                    mouseCursorView.setVisibility(View.GONE);
                }
                if (layoutScreenIndicator != null) {
                    if (tvScreenIndicator != null && currentScreenName != null) {
                        tvScreenIndicator.setText("📍 鼠标在" + currentScreenName);
                    }
                    layoutScreenIndicator.setVisibility(View.VISIBLE);
                    layoutScreenIndicator.bringToFront();
                }
            } else {
                if (layoutScreenIndicator != null) {
                    layoutScreenIndicator.setVisibility(View.GONE);
                }
                // 只要光标落在本屏，无论物理鼠标还是黑屏触控，一律显示自绘光标
                if (mouseCursorView != null && lastMouseX >= 0 && lastMouseY >= 0) {
                    applyCursorPosition(lastMouseX, lastMouseY);
                    mouseCursorView.bringToFront();
                }
            }
        });
    }

    /**
     * 重新绑定外屏输入后立刻压掉系统黑箭头。
     *
     * 绑定会让 InputFlinger 把指针精灵重置成默认图标，纯 Root 下没有 IInputManager
     * binder，keepSystemPointerHidden() 的透明图标注入必然失败；此前只能等用户点一下，
     * 让 ViewRootImpl 重新 resolve 到 EmptyPointerFrameLayout 的透明 pointerIcon。
     * 这里主动注入一次无按键的 SOURCE_MOUSE MOVE（坐标就是当前指针位置，不会误点、
     * 也不会让自绘指针跳动），把这个 resolve 提前到绑定完成的瞬间。
     */
    public void nudgeSystemPointerHidden() {
        // ShizukuCmd.rehideSystemPointerSprite();
        int target = (getDisplay() != null) ? getDisplay().getDisplayId() : -1;
        if (target <= 0) return;
        final int dId = target;
        final float x = getLastMouseX();
        final float y = getLastMouseY();
        // 重新绑定是异步完成的：只注入一次时，InputFlinger 可能还没把精灵重置回默认
        // 黑箭头，这一发 HOVER_MOVE 会被吃掉，于是用户仍看到双指针、点一下左键才消失。
        // 立即 + 250ms 各来一发（第二发带 1px 真实位移，不会被当成重复事件丢弃），
        // 把 resolve 提前到绑定完成的瞬间，且不会误点击任何东西。
        inputExecutor.execute(() -> {
            ShizukuCmd.execute(String.format(Locale.US,
                    "input mouse -d %d motionevent MOVE %.1f %.1f", dId, x, y));
            try { Thread.sleep(250); } catch (InterruptedException ignored) {}
            // ShizukuCmd.rehideSystemPointerSprite();
            ShizukuCmd.execute(String.format(Locale.US,
                    "input mouse -d %d motionevent MOVE %.1f %.1f", dId, x + 1, y));
        });
    }

    /**
     * 将鼠标重置到外接屏幕中央
     */
    public void resetCursorToCenter() {
        setCursorFocusScreen(true, null);
        float cx = getRawWidth() / 2f;
        float cy = getRawHeight() / 2f;
        lastMouseX = cx;
        lastMouseY = cy;
        updateCursor(cx, cy);
        // 与 surfaceCreated 保持一致：外设绑定到桌面虚拟屏，而不是物理外接屏，
        // 避免两处 displayId 不一致导致鼠标事件落屏漂移。
        int bindTarget = virtualDisplayId > 0 ? virtualDisplayId
                : (getDisplay() != null ? getDisplay().getDisplayId() : -1);
        if (bindTarget > 0) {
            ShizukuCmd.bindMouseDeviceToDisplay(bindTarget);
            // ShizukuCmd.rehideSystemPointerSprite();
        }
    }

    public void refreshCursorPrefs() {
        cursorHandler.post(() -> {
            if (mouseCursorView != null) {
                mouseCursorView.refresh();
            }
            setMousePointerVisibility(true);
        });
    }

    /**
     * 控制外接物理鼠标系统箭头的显示与隐藏。
     * 隐藏用「1x1 透明位图」构造空 PointerIcon：三星 One UI 上
     * PointerIcon.getSystemIcon(ctx, TYPE_NULL) 并不可靠（实测箭头仍在），
     * 空位图是公开 API 的确定性隐藏方式。显示时用系统箭头。
     */
    private android.view.PointerIcon getEmptyPointerIcon() {
        return EmptyPointerFrameLayout.getEmptyPointerIcon(getContext());
    }

    /**
     * 本屏所有视图一律使用空 PointerIcon：系统箭头永不显示，指针只由 MouseCursorView 绘制。
     * （show 参数保留仅为兼容旧调用，不再切换成"主题化系统图标"——那正是 USB 鼠标出现第二个图标的原因。）
     */
    public void applyThemedPointerIcon() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N || getContext() == null) return;
        final PointerIcon icon = getEmptyPointerIcon();
        cursorHandler.post(() -> {
            try {
                View root = findViewById(R.id.dexContainer);
                if (root != null) root.setPointerIcon(icon);
                if (flCursorLayer != null) flCursorLayer.setPointerIcon(icon);
                if (flDesktopStage != null) flDesktopStage.setPointerIcon(icon);
                for (Desktop d : desktops) {
                    if (d.surfaceView != null) d.surfaceView.setPointerIcon(icon);
                }
                if (mouseCursorView != null) mouseCursorView.setPointerIcon(icon);
                if (getWindow() != null && getWindow().getDecorView() != null) {
                    getWindow().getDecorView().setPointerIcon(icon);
                }
            } catch (Throwable t) {
                Log.w(TAG, "applyThemedPointerIcon failed: " + t.getMessage());
            }
        });
    }

    public void setMousePointerVisibility(boolean show) {
        applyThemedPointerIcon();
    }

    private void handleExternalTouchEvent(MotionEvent event) {
        if (isGridMode) {
            if (event.getActionMasked() == MotionEvent.ACTION_UP) {
                int targetIdx = getTileIndexAt(event.getX(), event.getY());
                if (targetIdx >= 0 && targetIdx < desktops.size()) {
                    switchToDesktop(targetIdx);
                }
            }
            return;
        }

        if (virtualDisplayId <= 0) {
            cursorHandler.post(() -> toggleLaunchpad());
            return;
        }

        // 主线程同步更新触摸状态（供注入失败时的点击降级判断使用）
        final float x = event.getX();
        final float y = event.getY();
        final int action = event.getActionMasked();
        boolean tapFallback = false;
        switch (action) {
            case MotionEvent.ACTION_DOWN:
                MainActivity.onExternalInteractionOrIme();
                extDownX = x;
                extDownY = y;
                extDownTime = System.currentTimeMillis();
                extIsDrag = false;
                break;
            case MotionEvent.ACTION_MOVE:
                if (Math.abs(x - extDownX) > 18 || Math.abs(y - extDownY) > 18) {
                    extIsDrag = true;
                }
                break;
            case MotionEvent.ACTION_UP:
                long duration = System.currentTimeMillis() - extDownTime;
                float dist = (float) Math.hypot(x - extDownX, y - extDownY);
                tapFallback = !extIsDrag && dist < 25 && duration < 500;
                break;
        }

        // 异步注入：主线程仅拷贝事件（分发后系统会回收原事件，必须先 obtain）
        final MotionEvent copy = MotionEvent.obtain(event);
        final int targetDisplay = virtualDisplayId;
        final boolean isTapFallback = tapFallback;
        inputExecutor.execute(() -> {
            try {
                boolean injected = ShizukuCmd.injectMotionEvent(copy, targetDisplay);
                if (!injected && isTapFallback) {
                    // 注入失败的兜底：通过 input tap 模拟点击
                    ShizukuCmd.execute(String.format(Locale.US, "input -d %d tap %.1f %.1f", targetDisplay, x, y));
                }
            } finally {
                copy.recycle();
            }
        });
    }

    public synchronized void recreateVirtualDisplayWithDpi(int newDpi) {
        this.dexDpi = newDpi;
        showLoading(getContext().getString(R.string.desktop_loading));
        destroyDeXVirtualDisplay();
        new Thread(() -> {
            try {
                Thread.sleep(150);
            } catch (InterruptedException ignored) {}
            if (flDesktopStage != null) {
                flDesktopStage.post(() -> {
                    createAndAddDesktop("桌面 1");
                });
            }
        }).start();
    }

        public synchronized void setVirtualDisplayResolution(int targetW, int targetH) {
        if (targetW <= 0 || targetH <= 0) return;
        this.screenWidth = targetW;
        this.screenHeight = targetH;
        if (!desktops.isEmpty() && desktops.get(0).surfaceView != null) {
            SurfaceView sv = desktops.get(0).surfaceView;
            sv.post(() -> {
                try {
                    sv.getHolder().setFixedSize(targetW, targetH);
                    if (desktops.get(0).displayId > 0) {
                        ShizukuCmd.resizeVirtualDisplay(desktops.get(0).displayId, targetW, targetH, dexDpi);
                    }
                } catch (Throwable t) {
                    Log.w(TAG, "setVirtualDisplayResolution fallback recreate: " + t);
                    recreateVirtualDisplayWithSize(targetW, targetH, -1);
                }
            });
        } else {
            recreateVirtualDisplayWithSize(targetW, targetH, -1);
        }
    }

public synchronized void recreateVirtualDisplayWithSize(int newW, int newH, int newDpi) {
        if (newW > 0) this.screenWidth = newW;
        if (newH > 0) this.screenHeight = newH;
        if (newDpi > 0) this.dexDpi = newDpi;
        showLoading(getContext().getString(R.string.desktop_loading));
        destroyDeXVirtualDisplay();
        new Thread(() -> {
            try {
                Thread.sleep(150);
            } catch (InterruptedException ignored) {}
            if (flDesktopStage != null) {
                flDesktopStage.post(() -> {
                    createAndAddDesktop("桌面 1");
                });
            }
        }).start();
    }

    public int getRawWidth() { return rawWidth > 0 ? rawWidth : screenWidth; }
    public int getRawHeight() { return rawHeight > 0 ? rawHeight : screenHeight; }

    public float toVirtualX(float physX) {
        int rw = getRawWidth();
        return (rw > 0 && screenWidth > 0) ? (physX * screenWidth / (float) rw) : physX;
    }

    public float toVirtualY(float physY) {
        int rh = getRawHeight();
        return (rh > 0 && screenHeight > 0) ? (physY * screenHeight / (float) rh) : physY;
    }

    public void restartDeX() {
        if (virtualDisplayId > 0) {
            showLoading(getContext().getString(R.string.toast_desktop_restarting));
            final int dId = virtualDisplayId;
            new Thread(() -> {
                try {
                    ShizukuCmd.configureDesktopDisplayEnvironment(dId);
                    Thread.sleep(80);
                    if (shouldLaunchDexHome()) {
                        startDexHomeOnDisplay(dId);
                    } else {
                        // 官方桌面同样要主动拉起，不能靠 SystemUI 自动接管；
                        // 从沧海切过来时 Dock 层还挂着，必须先撤。
                        hideDockStageForOfficialDesktop();
                        startOfficialDesktopOnDisplay(dId);
                    }
                } catch (Throwable t) {
                    Log.w(TAG, "restartDeX error: " + t.getMessage());
                } finally {
                    hideLoading();
                }
            }).start();
        }
    }

    public void toggleKeyboard() {
        if (virtualDisplayId <= 0) {
            cursorHandler.post(() -> toggleLaunchpad());
            return;
        }
        MainActivity.onExternalInteractionOrIme();
        int shell = AppShellManager.getActiveAppDisplayId();
        final int dId = shell > 0 ? shell : virtualDisplayId;
        inputExecutor.execute(() -> {
            ShizukuCmd.enableFloatingKeyboardSync();
            ShizukuCmd.execute("cmd input_method show-soft-input");
            ShizukuCmd.execute("ime show");
            ShizukuCmd.execute("input -d " + dId + " keyevent 170");
        });
    }

    /**
     * 发送硬件键盘中/英文切换指令 (Shift + Space)
     * 在三星 One UI / Android 中，物理键盘的中英文输入法通过该快捷键无缝切换
     */
    public void toggleLanguage() {
        if (virtualDisplayId <= 0) {
            cursorHandler.post(() -> toggleLaunchpad());
            return;
        }
        int shell = AppShellManager.getActiveAppDisplayId();
        final int dId = shell > 0 ? shell : virtualDisplayId;
        inputExecutor.execute(() -> {
            ShizukuCmd.execute("input -d " + dId + " keycombination 59 62");
        });
    }

    /**
     * 移动统一软件指针：触控板滑动与 Moonlight 远程控制均走这里；
     * 主线程直接硬件图层加速平移，零消息队列积压，杜绝一顿一顿卡顿；
     * 停止操作 2.5 秒后自动消失，避免屏幕残留死箭头。
     */
    /** 虚拟外设刚插到本屏（有线外屏或 Moonlight 串流屏）：从当前光标位置向外发一圈到达波纹。 */
    public void pulseCursorArrival() {
        if (mouseCursorView == null) return;
        mouseCursorView.post(() -> {
            try {
                float x = cursorPositioned ? lastMouseX : getRawWidth() / 2f;
                float y = cursorPositioned ? lastMouseY : getRawHeight() / 2f;
                mouseCursorView.showArrivalRipple(x, y);
            } catch (Throwable ignored) {}
        });
    }

    public void updateCursor(float x, float y) {
        // 0 是合法坐标（贴左/上边缘），只有负值才代表"还没定位"。
        if (x < 0 && y < 0) return;
        lastMouseX = Math.max(0, Math.min(x, getRawWidth()));
        lastMouseY = Math.max(0, Math.min(y, getRawHeight()));
        cursorPositioned = true;
        if (android.os.Looper.myLooper() == android.os.Looper.getMainLooper()) {
            applyCursorPosition(lastMouseX, lastMouseY);
        } else {
            cursorHandler.post(() -> applyCursorPosition(lastMouseX, lastMouseY));
        }
    }

    /** 真设备通道用：只挪自绘光标，不做任何注入（系统那边的指针由 uinput 驱动）。
     *  系统落点（onNativePointerAt）优先：刚收到过系统坐标就别再用增量推算覆盖它，
     *  否则两条源每帧互相打架，箭头会抖，且越抖越偏。 */
    /**
     * 真设备（uinput）接管后，屏幕上那根指针由我们自己画 —— 系统精灵在虚拟屏上不绘制，
     * 而且图标必须用沧海自己的。
     *
     * <p>但位置绝不能自己算：系统的指针位置带指针加速、带屏边界钳制，起点还受绑屏归位影响，
     * 我们按「原始增量 × 灵敏度」自己累加，走几帧就和真正的落点错开 —— 用户瞄箭头、点落点，
     * 桌面大图标还能碰上，安卓窗口就一个都点不中。
     *
     * <p>所以位置只有一个信源：目标屏（139）上窗口收到的自家设备事件里的 x/y，
     * 那就是系统算好的落点。走 {@link #onNativePointerAt} 喂过来，箭头画在那儿，
     * 图标仍然是沧海自己的。
     */
    private boolean nativePointerActive() {
        try { return VirtualMouse.isActive(); } catch (Throwable t) { return false; }
    }

    /** 最近一次收到「系统落点」的时间。系统坐标是权威，收到它的时候增量推算必须让位。 */
    private volatile long sLastNativeAt = 0L;
    private long sLastNativeTrace = 0L;

    /** 目标屏上报的系统落点（虚拟屏坐标）：只挪箭头，绝不注入。 */
    public void onNativePointerAt(final float vx, final float vy) {
        sLastNativeAt = SystemClock.uptimeMillis();
        /* 真设备通道下，这一幕的箭头位置以「系统落点」为准：增量推算再准也会因为
         * 丢帧、贴边钳制慢慢偏几百像素，而下面这个 x/y 是系统自己算好、
         * 命中测试真正要用的那个点 —— 它才是权威，箭头对齐它就没有「看见的和点到的
         * 不是一处」。偏差过大的一律不动（那一帧多半是捕获期残留的旧坐标）。 */
        if (nativePointerActive()) {
            final float[] p = VirtualMouse.position();
            /* 无条件贴过去，不做任何阈值判断。
             *
             * 21:03 的日志已经证明「我方」这个累加器是不可信的：同一个手势，系统
             * 记到 955,545，我们记到 1912,1079 —— 差整整一倍，而且几十组数据里
             * 比例一会儿 1.7 一会儿 0.5，根本不是「换算」能补的。既然目标屏窗口
             * 收到的 x/y 就是系统待会儿要点击的那个点，那就只有一条规矩：
             * 箭头永远画在那儿。这样「看见哪」和「点到哪」在构造上就是同一个数。
             *
             * 之前这里有个 400 像素的门槛，本意是防脏数据，结果正好把唯一可靠的
             * 校正挡在外面 —— 偏差越大越不修，越走越偏。 */
            if (p != null) {
                /* 无条件贴过去，不再问「顶层指针层在不在」。
                 *
                 * 之前这里要求 AosenPointerOverlay.isShowing()，而它在外屏这条通道上
                 * 是**挂载失败**的（BadTokenException），于是这条唯一可靠的校正从来
                 * 没执行过：我们的累加器和系统落点一路分叉（日志里稳定差 1.36 倍，
                 * 就是系统的指针加速）。后果是分层的 ——
                 *   · 左键：我们按自己的坐标注入触摸，命中准确，看着「能用」；
                 *   · 右键 / 拖拽 / 滚轮：要么走系统按键、要么用我们的坐标去做
                 *     命中与 DOM 派发，全部落在离箭头 300 多像素以外，看着「彻底没反应」。
                 * 一次只修一条，就会像打地鼠：修好这边，那边又断。
                 *
                 * 现在只留一条规矩：位置永远等于系统落点这一帧给的值，
                 * 箭头画在这个数上，注入和派发也用这个数 —— 「看见哪」「点到哪」
                 * 在构造上就是同一个数，不存在两套坐标可对不上。 */
                int[] sp = VirtualMouse.space();
                boolean inSpace = vx >= 0f && vy >= 0f
                        && (sp[0] <= 1 || vx <= sp[0] - 1f)
                        && (sp[1] <= 1 || vy <= sp[1] - 1f);
                if (inSpace) VirtualMouse.resync(vx, vy);
            }
            long now = SystemClock.uptimeMillis();
            if (now - sLastNativeTrace > 800) {
                sLastNativeTrace = now;
                if (p != null) {
                    CanghaiErrorCollector.logExt("[指针对账] 我方 " + Math.round(p[0]) + ","
                            + Math.round(p[1]) + " / 系统落点 " + Math.round(vx) + ","
                            + Math.round(vy) + " 偏差 " + Math.round(Math.abs(p[0] - vx))
                            + "," + Math.round(Math.abs(p[1] - vy)));
                }
            }
            return;
        }
        /* 有虚拟屏时，指针画在**那块屏自己的顶层悬浮窗**上（AosenPointerOverlay），
         * 坐标就是系统给的落点，1:1 摆过去 —— 不换算、不累加。这条路走通了，
         * Presentation 里那支按增量推算的箭头就必须彻底不画，否则屏幕上两根指针。 */
        if (AosenPointerOverlay.isShowing()) {
            AosenPointerOverlay.move(vx, vy);
            long now = SystemClock.uptimeMillis();
            if (now - sLastNativeTrace > 800) {
                sLastNativeTrace = now;
                CanghaiErrorCollector.logExt("[指针对账] 系统落点 " + Math.round(vx) + ","
                        + Math.round(vy) + " → 顶层指针（不换算）");
            }
            return;
        }
        cursorHandler.post(() -> {
            int rw = getRawWidth();
            int rh = getRawHeight();
            // 虚拟屏逻辑坐标 → 本层视图坐标（两块屏分辨率不同时必须换算）
            float x = screenWidth > 0 ? vx * rw / (float) screenWidth : vx;
            float y = screenHeight > 0 ? vy * rh / (float) screenHeight : vy;
            updateCursor(x, y);
            long now = SystemClock.uptimeMillis();
            if (now - sLastNativeTrace > 800) {
                sLastNativeTrace = now;
                CanghaiErrorCollector.logExt("[指针对账] 系统落点 v=" + Math.round(vx) + ","
                        + Math.round(vy) + " → 自绘 " + Math.round(x) + "," + Math.round(y)
                        + "（屏 " + screenWidth + "×" + screenHeight + "，视图 " + rw + "×" + rh + "）");
            }
        });
    }

    private void applyCursorPosition(float x, float y) {
        /* 这条通道下一律不画：有真设备时屏幕上的指针只有 AosenPointerOverlay 那一层，
         * 位置由 VirtualMouse 的绝对坐标给出。这里多画一支就是第二根指针，
         * 就是用户看到的「好几套在画、按下去图标还停住」。 */
        /* 只认「顶层指针层是否真的挂上了」。不能认 nativePointerActive()：
         * 设备绑上了、指针层还没来得及挂（或挂失败）的那一刻如果也不画，
         * 屏幕上就一根指针都没有。谁在，谁画。 */
        if (AosenPointerOverlay.isShowing()) {
            if (mouseCursorView != null && mouseCursorView.getVisibility() != View.GONE) {
                cursorHandler.removeCallbacks(cursorAutoHide);
                mouseCursorView.animate().cancel();
                mouseCursorView.setVisibility(View.GONE);
            }
            return;
        }
        /* 没有真设备时必须无条件画。
         * 之前为了「系统会画硬件指针」把自绘藏了，结果纯注入通道下屏幕上什么都没有 ——
         * 用户看到的直接现象是「鼠标不见了」。 */
        if (mouseCursorView != null) {
            if (mouseCursorView.getParent() == null) {
                FrameLayout.LayoutParams cursorLp = new FrameLayout.LayoutParams(
                        FrameLayout.LayoutParams.WRAP_CONTENT,
                        FrameLayout.LayoutParams.WRAP_CONTENT);
                if (flCursorLayer != null) {
                    flCursorLayer.addView(mouseCursorView, cursorLp);
                } else if (flDesktopStage != null) {
                    flDesktopStage.addView(mouseCursorView, cursorLp);
                }
            }
            if (flCursorLayer != null) {
                flCursorLayer.bringToFront();
            }
            mouseCursorView.setCursorPosition(x, y);
            if (mouseCursorView.getVisibility() != View.VISIBLE || mouseCursorView.getAlpha() < 1f) {
                mouseCursorView.animate().cancel();
                mouseCursorView.setAlpha(1f);
                mouseCursorView.setVisibility(View.VISIBLE);
            }
        }
        long hideDelay = com.alkaidlab.sunshine.service.MouseCursorPrefs.getAutoHideMs();
        if (hideDelay <= 0L) hideDelay = 2500L;
        cursorHandler.removeCallbacks(cursorAutoHide);
        cursorHandler.postDelayed(cursorAutoHide, hideDelay);
    }

    public void hideMouseCursor() {
        cursorHandler.post(() -> {
            cursorHandler.removeCallbacks(cursorAutoHide);
            if (mouseCursorView != null) {
                mouseCursorView.animate().cancel();
                mouseCursorView.setVisibility(View.GONE);
            }
        });
    }

    public void performClickAt(float x, float y) {
        if (isGridMode) {
            int targetIdx = getTileIndexAt(x, y);
            if (targetIdx >= 0 && targetIdx < desktops.size()) {
                switchToDesktop(targetIdx);
                return;
            }
        }
        if (virtualDisplayId <= 0) {
            // 物理外屏直显零特权模式：根据图层命中测试，将点击分发给 Dock 或 Desktop
            float density = getContext().getResources().getDisplayMetrics().density;
            boolean isDockArea = (y >= (screenHeight - 110 * density));
            boolean isMenuBarArea = (y <= 42 * density);
            final WebView targetWeb = (isLaunchpadOpen || isDockArea || isMenuBarArea) && directDockWeb != null
                    ? directDockWeb : directDesktopWeb;

            cursorHandler.post(() -> {
                try {
                    long now = SystemClock.uptimeMillis();
                    // 1. Android 原生 TouchEvent 分发 (带真实延时，绝不提前 recycle 导致内核丢弃)
                    if (targetWeb != null) {
                        MotionEvent down = MotionEvent.obtain(now, now, MotionEvent.ACTION_DOWN, x, y, 0);
                        down.setSource(InputDevice.SOURCE_TOUCHSCREEN);
                        targetWeb.dispatchTouchEvent(down);

                        targetWeb.postDelayed(() -> {
                            try {
                                long upTime = SystemClock.uptimeMillis();
                                MotionEvent up = MotionEvent.obtain(now, upTime, MotionEvent.ACTION_UP, x, y, 0);
                                up.setSource(InputDevice.SOURCE_TOUCHSCREEN);
                                targetWeb.dispatchTouchEvent(up);
                            } catch (Throwable ignored) {}
                        }, 40);
                    }

                    // 2. JavaScript 双重坐标（物理/CSS逻辑像素）智能探测与精确点击
                    String js = String.format(java.util.Locale.US,
                            "(function(x, y) {" +
                            "  var dpr = window.devicePixelRatio || 1;" +
                            "  var el = document.elementFromPoint(x / dpr, y / dpr) || document.elementFromPoint(x, y);" +
                            "  if (!el) return;" +
                            "  var item = el.closest('.dockItem') || el.closest('.lpItem2') || el.closest('.chMenuItem') || el.closest('.appCell') || el.closest('.appIcon') || el.closest('.deskItem') || el.closest('.bfMini') || el;" +
                            "  try {" +
                            "    var rect = item.getBoundingClientRect();" +
                            "    var cx = rect.left + rect.width / 2;" +
                            "    var cy = rect.top + rect.height / 2;" +
                            "    var pd = new PointerEvent('pointerdown', { bubbles: true, cancelable: true, clientX: cx, clientY: cy, button: 0, buttons: 1 });" +
                            "    item.dispatchEvent(pd);" +
                            "    var pu = new PointerEvent('pointerup', { bubbles: true, cancelable: true, clientX: cx, clientY: cy, button: 0, buttons: 0 });" +
                            "    item.dispatchEvent(pu);" +
                            "  } catch(e) {}" +
                            "  try { item.click(); } catch(e) {}" +
                            "})(%.1f, %.1f);", x, y);

                    if (isDockArea || isMenuBarArea) {
                        if (directDockWeb != null) directDockWeb.evaluateJavascript(js, null);
                    } else {
                        if (directDesktopWeb != null) directDesktopWeb.evaluateJavascript(js, null);
                    }
                } catch (Throwable ignored) {}
            });
            return;
        }
        MainActivity.onExternalInteractionOrIme();
        final int dId = virtualDisplayId;
        inputExecutor.execute(() -> {
            try {
                boolean ok = ShizukuCmd.injectVirtualLeftClick(dId, x, y);
                if (!ok) {
                    String out = ShizukuCmd.execute(String.format(Locale.US, "input -d %d tap %.1f %.1f", dId, x, y));
                    if (out != null && !out.trim().isEmpty()) {
                        com.alkaidlab.sunshine.service.SunshineLogManager.log("CLICK", "注入回执 display=" + dId + ": " + out.trim());
                    }
                }
            } catch (Throwable t) {
                com.alkaidlab.sunshine.service.SunshineLogManager.log("CLICK_ERR", "注入失败 display=" + dId + ": " + t.getMessage());
            }
        });
    }

    public void performRightClickAt(float x, float y) {
        if (VirtualMouse.isActive()) {
            // 真鼠标的右键由系统直接派给外屏那个窗口，页面自己会弹菜单，
            // 既不用 JS 补，也不用注入，更不会误关菜单。
            VirtualMouse.click(MotionEvent.BUTTON_SECONDARY);
            return;
        }
        if (virtualDisplayId <= 0) {
            cursorHandler.post(() -> triggerDirectContextMenu(x, y));
            return;
        }
        final int dId = virtualDisplayId;
        final float vx = toVirtualX(x);
        final float vy = toVirtualY(y);
        // 底座 Activity 模式：桌面菜单在它自己的 WebView 里，光往虚拟屏注入裸 BUTTON_SECONDARY，
        // 页面不会自己弹菜单（这就是「右键没反应」）。Activity 已经处理就别再注入，
        // 否则那一发 mouseup 会把刚弹出来的菜单立刻关掉。
        if (DexHomeActivity.onExternalRightClick(dId, vx, vy)) return;
        inputExecutor.execute(() -> {
            boolean handled = ShizukuCmd.injectVirtualRightClick(dId, vx, vy);
            if (!handled) {
                // 兜底降级方案：长按触控以唤出系统右键/快捷菜单
                ShizukuCmd.execute(String.format(Locale.US, "input -d %d swipe %.1f %.1f %.1f %.1f 550", dId, vx, vy, vx, vy));
            }
        });
    }

    public void toggleLaunchpad() {
        // 底座 Activity 模式：应用台在 Dock 悬浮窗里，必须让它自己翻。
        if (virtualDisplayId > 0 && DexHomeActivity.onExternalToggleLaunchpad(virtualDisplayId)) return;
        cursorHandler.post(() -> {
            if (directDockWeb != null) {
                directDockWeb.evaluateJavascript(
                    "var lp = document.getElementById('lpLayer'); if (lp) window.__lpSetOpen ? window.__lpSetOpen(!lp.classList.contains('open')) : lp.classList.toggle('open');",
                    null);
            }
        });
    }

    public void performMiddleClickAt(float x, float y) {
        if (virtualDisplayId <= 0) {
            cursorHandler.post(() -> toggleLaunchpad());
            return;
        }
        // 中键就是「呼出应用台」，不是把一颗裸中键丢进虚拟屏让页面猜——
        // 应用台根本不在那个 WebView 里，它在 Dock 悬浮层。
        if (!DexHomeActivity.onExternalToggleLaunchpad(virtualDisplayId)) {
            final int dId = virtualDisplayId;
            final float vx = toVirtualX(x);
            final float vy = toVirtualY(y);
            inputExecutor.execute(() -> ShizukuCmd.injectVirtualMiddleClick(dId, vx, vy));
        }
    }

    public void performScrollAt(float x, float y, float vScroll, float hScroll) {
        InputTrace.touchpadScroll(x, y, vScroll, hScroll);
        if (virtualDisplayId <= 0) {
            if (directDesktopWeb != null) {
                cursorHandler.post(() -> {
                    try {
                        long now = SystemClock.uptimeMillis();
                        MotionEvent.PointerProperties[] pp = new MotionEvent.PointerProperties[1];
                        pp[0] = new MotionEvent.PointerProperties();
                        pp[0].id = 0;
                        pp[0].toolType = MotionEvent.TOOL_TYPE_MOUSE;
                        MotionEvent.PointerCoords[] pc = new MotionEvent.PointerCoords[1];
                        pc[0] = new MotionEvent.PointerCoords();
                        pc[0].x = x;
                        pc[0].y = y;
                        pc[0].setAxisValue(MotionEvent.AXIS_VSCROLL, vScroll);
                        pc[0].setAxisValue(MotionEvent.AXIS_HSCROLL, hScroll);
                        MotionEvent ev = MotionEvent.obtain(now, now, MotionEvent.ACTION_SCROLL, 1, pp, pc, 0, 0, 1f, 1f, 0, 0, InputDevice.SOURCE_MOUSE, 0);
                        directDesktopWeb.dispatchGenericMotionEvent(ev);
                        ev.recycle();
                    } catch (Throwable ignored) {}
                });
            }
            return;
        }
        final int dId = virtualDisplayId;
        inputExecutor.execute(() -> {
            try {
                long now = android.os.SystemClock.uptimeMillis();
                MotionEvent.PointerProperties[] pp = new MotionEvent.PointerProperties[1];
                pp[0] = new MotionEvent.PointerProperties();
                pp[0].id = 0;
                pp[0].toolType = MotionEvent.TOOL_TYPE_MOUSE;

                MotionEvent.PointerCoords[] pc = new MotionEvent.PointerCoords[1];
                pc[0] = new MotionEvent.PointerCoords();
                pc[0].x = x;
                pc[0].y = y;
                pc[0].setAxisValue(MotionEvent.AXIS_VSCROLL, vScroll);
                pc[0].setAxisValue(MotionEvent.AXIS_HSCROLL, hScroll);

                MotionEvent event = MotionEvent.obtain(
                        now, now, MotionEvent.ACTION_SCROLL, 1, pp, pc,
                        0, 0, 1.0f, 1.0f, 0, 0,
                        InputDevice.SOURCE_MOUSE, 0
                );
                boolean injected = ShizukuCmd.injectMotionEvent(event, dId);
                event.recycle();
                if (!injected) {
                    performSwipeAt(x, y, hScroll * 40f, vScroll * 40f, 50);
                }
            } catch (Throwable t) {
                Log.w(TAG, "performScrollAt failed: " + t.getMessage());
            }
        });
    }

    public void performSwipeAt(float x, float y, float dx, float dy, int durationMs) {
        if (virtualDisplayId <= 0) {
            cursorHandler.post(() -> toggleLaunchpad());
            return;
        }
        final int dId = virtualDisplayId;
        final float toX = Math.max(0, Math.min(screenWidth - 1, x + dx));
        final float toY = Math.max(0, Math.min(screenHeight - 1, y + dy));
        inputExecutor.execute(() -> {
            try {
                long now = android.os.SystemClock.uptimeMillis();
                android.view.MotionEvent.PointerProperties[] pp = new android.view.MotionEvent.PointerProperties[1];
                pp[0] = new android.view.MotionEvent.PointerProperties();
                pp[0].id = 0;
                pp[0].toolType = android.view.MotionEvent.TOOL_TYPE_FINGER;

                android.view.MotionEvent.PointerCoords[] pcDown = new android.view.MotionEvent.PointerCoords[1];
                pcDown[0] = new android.view.MotionEvent.PointerCoords();
                pcDown[0].x = x;
                pcDown[0].y = y;
                pcDown[0].pressure = 1.0f;
                pcDown[0].size = 1.0f;

                android.view.MotionEvent down = android.view.MotionEvent.obtain(now, now, android.view.MotionEvent.ACTION_DOWN, 1, pp, pcDown, 0, 0, 1.0f, 1.0f, 0, 0, android.view.InputDevice.SOURCE_TOUCHSCREEN, 0);
                ShizukuCmd.injectMotionEvent(down, dId);
                down.recycle();

                android.view.MotionEvent.PointerCoords[] pcMove = new android.view.MotionEvent.PointerCoords[1];
                pcMove[0] = new android.view.MotionEvent.PointerCoords();
                pcMove[0].x = toX;
                pcMove[0].y = toY;
                pcMove[0].pressure = 1.0f;
                pcMove[0].size = 1.0f;

                android.view.MotionEvent move = android.view.MotionEvent.obtain(now, now + 25, android.view.MotionEvent.ACTION_MOVE, 1, pp, pcMove, 0, 0, 1.0f, 1.0f, 0, 0, android.view.InputDevice.SOURCE_TOUCHSCREEN, 0);
                ShizukuCmd.injectMotionEvent(move, dId);
                move.recycle();

                android.view.MotionEvent up = android.view.MotionEvent.obtain(now, now + 50, android.view.MotionEvent.ACTION_UP, 1, pp, pcMove, 0, 0, 1.0f, 1.0f, 0, 0, android.view.InputDevice.SOURCE_TOUCHSCREEN, 0);
                ShizukuCmd.injectMotionEvent(up, dId);
                up.recycle();
            } catch (Throwable t) {
                android.util.Log.w("DeXPresentation", "performSwipeAt failed: " + t.getMessage());
            }
        });
    }

    public synchronized void destroyDeXVirtualDisplay() {
        // 屏没了，挂在它上面的顶层指针也必须摘掉，否则下次建屏会挂出第二支。
        AosenPointerOverlay.hide();
        if (directDesktopWeb != null) {
            try {
                directDesktopWeb.stopLoading();
                directDesktopWeb.loadUrl("about:blank");
                if (flDesktopStage != null) {
                    flDesktopStage.removeView(directDesktopWeb);
                }
                directDesktopWeb.destroy();
            } catch (Throwable ignored) {}
            directDesktopWeb = null;
        }
        for (Desktop d : desktops) {
            if (d.displayId > 0) {
                com.alkaidlab.sunshine.service.MouseCursorTargets.unregister(d.displayId);
                ShizukuCmd.releaseVirtualDisplay(d.displayId);
            }
        }
        if (flDesktopStage != null) {
            flDesktopStage.removeAllViews();
        }
        desktops.clear();
        virtualDisplayId = -1;
        // 屏没了，真设备不能再往那块已释放的 display 上写，先解绑
        try { VirtualMouse.onDisplayGone(); } catch (Throwable ignored) {}
        notifyDesktopChanged();
    }

    public int getVirtualDisplayId() {
        return virtualDisplayId;
    }

    public WebView getDirectDesktopWeb() {
        return directDesktopWeb;
    }

    /**
     * Dock 栏只属于沧海桌面。flDockStage 是叠在虚拟屏 SurfaceView 之上的 WebView 层，
     * 走三星官方桌面时若不撤掉，它就会一直压在原生桌面上（用户看到「原生桌面带着咱们的 Dock」）。
     * 重入是安全的：没建过 Dock 时这里什么都不做。
     */
    public void hideDockStageForOfficialDesktop() {
        if (flDockStage == null) return;
        try {
            flDockStage.setVisibility(View.GONE);
            if (directDockWeb != null) {
                directDockWeb.stopLoading();
                directDockWeb.loadUrl("about:blank");
                directDockWeb.setVisibility(View.GONE);
            }
            flDockStage.removeAllViews();
        } catch (Throwable t) {
            Log.w(TAG, "hideDockStageForOfficialDesktop: " + t.getMessage());
        }
        directDockWeb = null;
        directDockReady = false;
        isLaunchpadOpen = false;
    }

    public synchronized void setupDirectDesktopStage() {
        if (flDesktopStage == null) return;
        flDesktopStage.removeAllViews();
        flDesktopStage.setVisibility(View.VISIBLE);
        if (flDockStage != null) {
            flDockStage.removeAllViews();
            flDockStage.setVisibility(View.VISIBLE);
        }
        if (flWebAppStage != null) {
            flWebAppStage.removeAllViews();
            flWebAppStage.setVisibility(View.VISIBLE);
        }

        try {
            View container = findViewById(R.id.dexContainer);
            if (container != null) {
                container.setBackgroundColor(0xFF0E1014);
            }
            if (getWindow() != null) {
                getWindow().setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(0xFF0E1014));
            }
        } catch (Throwable ignored) {}

        Context ctx = getContext();
        DirectBridge bridge = new DirectBridge();

        // ------------------ 【Layer 0】底座桌面 Web 容器 ------------------
        directDesktopWeb = new WebView(ctx) {
            @Override
            public android.view.PointerIcon onResolvePointerIcon(MotionEvent event, int pointerIndex) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                    return EmptyPointerFrameLayout.getThemedPointerIcon(getContext());
                }
                return super.onResolvePointerIcon(event, pointerIndex);
            }
        };

        FrameLayout.LayoutParams deskLp = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT);
        directDesktopWeb.setLayoutParams(deskLp);

        WebSettings ws = directDesktopWeb.getSettings();
        ws.setJavaScriptEnabled(true);
        ws.setAllowFileAccess(true);
        ws.setAllowFileAccessFromFileURLs(true);
        ws.setAllowUniversalAccessFromFileURLs(true);
        ws.setAllowContentAccess(true);
        ws.setDomStorageEnabled(true);
        ws.setMediaPlaybackRequiresUserGesture(false);
        ws.setCacheMode(WebSettings.LOAD_NO_CACHE);

        directDesktopWeb.setBackgroundColor(0xFF0E1014);
        directDesktopWeb.setFocusable(true);
        directDesktopWeb.setFocusableInTouchMode(true);

        directDesktopWeb.setWebChromeClient(new WebChromeClient() {
            @Override
            public boolean onConsoleMessage(ConsoleMessage m) {
                if (m == null) return false;
                String msg = m.message();
                String loc = m.sourceId() + ":" + m.lineNumber();
                Log.i(TAG, "[directDesktopWeb] " + msg + " @" + loc);
                if (m.messageLevel() == ConsoleMessage.MessageLevel.ERROR
                        || (msg != null && (msg.toLowerCase().contains("uncaught") || msg.toLowerCase().contains("error")))) {
                    CanghaiErrorCollector.getInstance().log("外屏直显桌面", "ERROR", msg, loc);
                }
                return true;
            }
        });

        final String busJs = "(function(){if(window.AosenBus)return;var h={};window.AosenBus={"
                + "on:function(ev,fn){(h[ev]||(h[ev]=[])).push(fn)},"
                + "off:function(ev,fn){if(!h[ev])return;h[ev]=h[ev].filter(function(f){return f!==fn})},"
                + "emit:function(ev,data){(h[ev]||[]).forEach(function(f){try{f(data)}catch(e){}})},"
                + "pull:function(name){try{var m='get'+name+'Json';return typeof Aosen[m]==='function'?JSON.parse(Aosen[m]()||'[]'):null}catch(e){return null}},"
                + "snapshot:function(){try{return{apps:JSON.parse(Aosen.getAppsJson()||'[]'),dock:JSON.parse(Aosen.getDockJson()||'[]'),items:JSON.parse(Aosen.getItemsJson()||'[]'),system:JSON.parse(Aosen.getSystemInfoJson()||'{}'),time:Date.now()}}catch(e){return{apps:[],dock:[],items:[],system:{},time:Date.now()}}}"
                + "};})();\n"
                + "try{window.onAosenReady&&window.onAosenReady()}catch(e){}\n"
                + "try{document.dispatchEvent(new Event('aosen:ready'))}catch(e){}";

        directDesktopWeb.setWebViewClient(new WebViewClient() {
            @Override
            public void onPageFinished(WebView view, String url) {
                CanghaiErrorCollector.logExt("DeXPresentation 直显桌面 onPageFinished 成功: " + url);
                view.evaluateJavascript(busJs, null);
                view.evaluateJavascript("try{window.chBoot&&window.chBoot()}catch(e){}", null);
                hideLoading();
            }

            @Override
            public void onReceivedError(WebView view, int errorCode, String description, String failingUrl) {
                CanghaiErrorCollector.logExtError("DeXPresentation 直显桌面加载失败: " + description + " (" + failingUrl + ")", null);
                hideLoading();
            }
        });

        directDesktopWeb.addJavascriptInterface(bridge, "Aosen");
        directDesktopWeb.addJavascriptInterface(bridge, "Canghai");
        directDesktopWeb.addJavascriptInterface(bridge, "CanghaiDock");
        /* 直显桌面同样挂统一通道，保证与大屏桌面读到同一份沙盒数据 */
        CanghaiCore.attach(ctx, directDesktopWeb, null);
        flDesktopStage.addView(directDesktopWeb);

        String deskUrl = CustomDesktopManager.has(ctx)
                ? CustomDesktopManager.entryUrl(ctx)
                : "file:///android_asset/canghai/desktop/index.html";
        CanghaiErrorCollector.logExt("DeXPresentation 直显桌面开始加载页面: " + deskUrl);
        directDesktopWeb.loadUrl(deskUrl);

        // ------------------ 【Layer 2.2】顶层 Dock 栏与应用台 Web 容器 ------------------
        if (flDockStage != null) {
            directDockWeb = new WebView(ctx) {
                @Override
                public android.view.PointerIcon onResolvePointerIcon(MotionEvent event, int pointerIndex) {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                        return EmptyPointerFrameLayout.getThemedPointerIcon(getContext());
                    }
                    return super.onResolvePointerIcon(event, pointerIndex);
                }
            };

            FrameLayout.LayoutParams dockLp = new FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.MATCH_PARENT);
            directDockWeb.setLayoutParams(dockLp);

            WebSettings dockWs = directDockWeb.getSettings();
            dockWs.setJavaScriptEnabled(true);
            dockWs.setAllowFileAccess(true);
            dockWs.setAllowFileAccessFromFileURLs(true);
            dockWs.setAllowUniversalAccessFromFileURLs(true);
            dockWs.setAllowContentAccess(true);
            dockWs.setDomStorageEnabled(true);
            dockWs.setMediaPlaybackRequiresUserGesture(false);
            dockWs.setCacheMode(WebSettings.LOAD_NO_CACHE);

            directDockWeb.setBackgroundColor(android.graphics.Color.TRANSPARENT);
            directDockWeb.setFocusable(true);
            directDockWeb.setFocusableInTouchMode(true);

            directDockWeb.setWebChromeClient(new WebChromeClient() {
                @Override
                public boolean onConsoleMessage(ConsoleMessage m) {
                    if (m == null) return false;
                    String msg = m.message();
                    String loc = m.sourceId() + ":" + m.lineNumber();
                    Log.i(TAG, "[directDockWeb] " + msg + " @" + loc);
                    if (m.messageLevel() == ConsoleMessage.MessageLevel.ERROR
                            || (msg != null && (msg.toLowerCase().contains("uncaught") || msg.toLowerCase().contains("error")))) {
                        CanghaiErrorCollector.getInstance().log("外屏直显Dock", "ERROR", msg, loc);
                    }
                    return true;
                }
            });

            directDockWeb.setWebViewClient(new WebViewClient() {
                @Override
                public void onPageFinished(WebView view, String url) {
                    CanghaiErrorCollector.logExt("DeXPresentation 直显Dock onPageFinished 成功: " + url);
                    directDockReady = true;
                    view.evaluateJavascript(busJs, null);
                    view.evaluateJavascript("window.CanghaiDock = window.CanghaiDock || window.Canghai || window.Aosen; try{window.__dockRefresh&&window.__dockRefresh()}catch(e){}", null);
                }

                @Override
                public void onReceivedError(WebView view, int errorCode, String description, String failingUrl) {
                    CanghaiErrorCollector.logExtError("DeXPresentation 直显Dock加载失败: " + description + " (" + failingUrl + ")", null);
                }
            });

            directDockWeb.addJavascriptInterface(bridge, "Aosen");
            directDockWeb.addJavascriptInterface(bridge, "Canghai");
            directDockWeb.addJavascriptInterface(bridge, "CanghaiDock");
            flDockStage.addView(directDockWeb);

            String dockUrl = "file:///android_asset/canghai/dock/index.html";
            CanghaiErrorCollector.logExt("DeXPresentation 直显Dock开始加载页面: " + dockUrl);
            directDockWeb.loadUrl(dockUrl);
        }

        // ------------------ 【Layer 3】顶层光标与指示器 ------------------
        if (flCursorLayer != null) {
            flCursorLayer.bringToFront();
        }
        if (mouseCursorView != null) {
            isCursorOnThisScreen = true;
            mouseCursorView.setVisibility(View.VISIBLE);
            mouseCursorView.bringToFront();
            applyCursorPosition(screenWidth / 2f, screenHeight / 2f);
        }
    }

    public void dispatchInjectedBlackTouch(MotionEvent event) {
        if (event == null) return;
        cursorHandler.post(() -> {
            try {
                WebView target = directWebAt(event.getX(), event.getY());
                if (target != null) {
                    target.dispatchTouchEvent(event);
                }
            } catch (Throwable ignored) {}
        });
    }

    public void triggerDirectContextMenu(float x, float y) {
        // Dock 图标 / 应用台图标的右键必须派给 directDockWeb，以前写死桌面层，
        // 所以那两处的右键一直没有反应（菜单弹在了看不见的页面里）。
        final WebView target = directWebAt(x, y);
        if (target == null) return;
        String js = String.format(java.util.Locale.US,
                "(function(x, y) {" +
                "  var dpr = window.devicePixelRatio || 1;" +
                "  var cx = x / dpr;" +
                "  var cy = y / dpr;" +
                "  var el = document.elementFromPoint(cx, cy) || document.getElementById('desktop');" +
                "  if (el) {" +
                "    var ev = new MouseEvent('contextmenu', {" +
                "      bubbles: true, cancelable: true, view: window," +
                "      button: 2, buttons: 2, clientX: cx, clientY: cy" +
                "    });" +
                "    el.dispatchEvent(ev);" +
                "  }" +
                "})(%.1f, %.1f);", x, y);
        target.post(() -> {
            try {
                target.evaluateJavascript(js, null);
            } catch (Throwable ignored) {}
        });
    }

    public class DirectBridge {
        /* 自绘键盘已移除：系统原生输入法会自行接管输入框。保留空实现以防老页面调用报错。 */
        @JavascriptInterface
        public void imeWanted(String mode) {
        }

        @JavascriptInterface
        public void imeHide() {
        }

        @JavascriptInterface
        public String getPeripherals() {
            try {
                Context ctx = getContext();
                if (ctx instanceof MainActivity) {
                    return ((MainActivity) ctx).getPeripheralsListJson().toString();
                }
                if (mainActivity != null) {
                    return mainActivity.getPeripheralsListJson().toString();
                }
                return "[]";
            } catch (Throwable t) {
                return "[]";
            }
        }

        @JavascriptInterface
        public String getAvailableDisplays() {
            try {
                Context ctx = getContext();
                if (ctx instanceof MainActivity) {
                    return ((MainActivity) ctx).getAvailableDisplaysJson();
                }
                if (mainActivity != null) {
                    return mainActivity.getAvailableDisplaysJson();
                }
                return "[]";
            } catch (Throwable t) {
                return "[]";
            }
        }

        @JavascriptInterface
        public void setPeripheralDisplay(final String peripheralId, final int displayId) {
            try {
                Context ctx = getContext();
                MainActivity ma = (ctx instanceof MainActivity) ? (MainActivity) ctx : mainActivity;
                if (ma != null) {
                    ma.runOnUiThread(() -> {
                        CanghaiStore.setPeripheralDisplay(ma, peripheralId, displayId);
                        PeripheralBrain.getInstance().setDeviceTargetDisplay(peripheralId, displayId);
                    });
                } else {
                    PeripheralBrain.getInstance().setDeviceTargetDisplay(peripheralId, displayId);
                }
            } catch (Throwable ignored) {}
        }

        @JavascriptInterface
        public String getState() {
            try {
                int dId = (getDisplay() != null) ? getDisplay().getDisplayId() : 0;
                return CanghaiStore.stateJsonForDisplay(getContext(), dId);
            } catch (Throwable t) {
                return "{}";
            }
        }

        @JavascriptInterface
        public String getDeskState() {
            try {
                return CanghaiStore.desktopItems(getContext());
            } catch (Throwable t) {
                return "[]";
            }
        }

        @JavascriptInterface
        public void saveDeskState(final String json) {
            try {
                CanghaiStore.setDesktopItems(getContext(), json);
            } catch (Throwable ignored) {}
        }

        @JavascriptInterface
        public String getAppsJson() {
            try {
                return CanghaiStore.nativeAppsJson(getContext());
            } catch (Throwable t) {
                return "[]";
            }
        }

        @JavascriptInterface
        public String getDockJson() {
            try {
                return CanghaiStore.dockPinsJson(getContext());
            } catch (Throwable t) {
                return "[]";
            }
        }

        @JavascriptInterface
        public String getItemsJson() {
            try {
                return CanghaiStore.desktopItems(getContext());
            } catch (Throwable t) {
                return "[]";
            }
        }

        @JavascriptInterface
        public String getSystemInfoJson() {
            try {
                JSONObject o = new JSONObject();
                o.put("battery", 100);
                o.put("charging", true);
                o.put("net", "wifi");
                return o.toString();
            } catch (Throwable t) {
                return "{}";
            }
        }

        @JavascriptInterface
        public String getAboutInfo() {
            try {
                return CanghaiStore.aboutInfoJson(getContext());
            } catch (Throwable t) {
                return "{}";
            }
        }

        @JavascriptInterface
        public void checkUpdate() {
        }

        @JavascriptInterface
        public void openUpdateUrl(final String url) {
            cursorHandler.post(() -> {
                try {
                    if (url == null || !url.startsWith("https://")) return;
                    Intent it = new Intent(Intent.ACTION_VIEW, android.net.Uri.parse(url));
                    it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                    getContext().startActivity(it);
                } catch (Throwable ignored) {}
            });
        }

        @JavascriptInterface
        public String getDockAutoHide() {
            return String.valueOf(CanghaiStore.getDockAutoHide(getContext()));
        }

        @JavascriptInterface
        public void setDockAutoHide(final boolean autoHide) {
            CanghaiStore.setDockAutoHide(getContext(), autoHide);
        }

        @JavascriptInterface
        public void setDockPins(final String json) {
            CanghaiStore.setDockPinsJson(getContext(), json);
        }

        @JavascriptInterface
        public void setDockRunState(final String json) {
        }

        @JavascriptInterface
        public String getResolutions() {
            try {
                String dName = (getDisplay() != null) ? getDisplay().getName() : "";
                String dKey = dName + "_" + rawWidth + "x" + rawHeight;
                return CanghaiStore.getResolutionOptions(rawWidth, rawHeight, dKey, getContext()).toString();
            } catch (Throwable t) {
                return "[]";
            }
        }

        @JavascriptInterface
        public void setResolution(final String resKey) {
            try {
                String dName = (getDisplay() != null) ? getDisplay().getName() : "";
                CanghaiStore.setDisplayResolutionKey(getContext(), dName + "_" + rawWidth + "x" + rawHeight, resKey);
            } catch (Throwable ignored) {}
        }

        @JavascriptInterface
        public void saveWallpaper(final String wp) {
            CanghaiStore.setWallpaper(getContext(), wp);
        }

        @JavascriptInterface
        public void saveGrid(final int cols, final int rows) {
            CanghaiStore.setGrid(getContext(), cols, rows);
        }

        @JavascriptInterface
        public void setUserProfile(final String name, final String avatar) {
            if (name != null) CanghaiStore.setUserName(getContext(), name);
            if (avatar != null) CanghaiStore.setUserAvatar(getContext(), avatar);
            CanghaiStore.notifyDataChanged(getContext());
        }

        @JavascriptInterface
        public String getUserProfile() {
            try {
                JSONObject o = new JSONObject();
                o.put("userName", CanghaiStore.userName(getContext()));
                o.put("userAvatar", CanghaiStore.userAvatar(getContext()));
                return o.toString();
            } catch (Throwable t) {
                return "{}";
            }
        }

        @JavascriptInterface
        public void setCursorSize(final int size) {
            int dId = (getDisplay() != null) ? getDisplay().getDisplayId() : 0;
            CanghaiStore.setCursorSizeForDisplay(getContext(), dId, size);
        }

        @JavascriptInterface
        public void setCursorSpeed(final float speed) {
            CanghaiStore.setCursorSpeed(getContext(), speed);
        }

        @JavascriptInterface
        public void selectCursor(final String id) {
            int dId = (getDisplay() != null) ? getDisplay().getDisplayId() : 0;
            CanghaiStore.selectCursorForDisplay(getContext(), dId, id);
        }

        @JavascriptInterface
        public String saveCursor(final String id, final String name, final String svg, final int hx, final int hy) {
            return CanghaiStore.upsertCursor(getContext(), id, name, svg, hx, hy);
        }

        @JavascriptInterface
        public void removeCursor(final String id) {
            CanghaiStore.removeCursor(getContext(), id);
        }

        @JavascriptInterface
        public String getHtmlAppSource(final String id) {
            String s = CanghaiStore.readHtmlApp(getContext(), id);
            return s == null ? "" : s;
        }

        @JavascriptInterface
        public String getHtmlApp(final String id) {
            String s = CanghaiStore.readHtmlAppForRun(getContext(), id);
            return s == null ? "" : s;
        }

        @JavascriptInterface
        public String installHtml(final String name, final String html) {
            return installHtml(name, html, null);
        }

        @JavascriptInterface
        public String installHtml(final String name, final String html, final String iconSvg) {
            String id = CanghaiStore.installHtml(getContext(), name, html, iconSvg);
            if (id != null) CanghaiStore.notifyDataChanged(getContext());
            return id == null ? "" : id;
        }

        @JavascriptInterface
        public void removeHtml(final String id) {
            CanghaiStore.removeHtml(getContext(), id);
            CanghaiStore.notifyDataChanged(getContext());
        }

        @JavascriptInterface
        public String renameHtml(final String id, final String newName) {
            String n = CanghaiStore.renameHtml(getContext(), id, newName);
            if (n != null) CanghaiStore.notifyDataChanged(getContext());
            return n == null ? "" : n;
        }

        @JavascriptInterface
        public boolean setHtmlIcon(final String id, final String iconSvg) {
            boolean ok = CanghaiStore.setHtmlIcon(getContext(), id, iconSvg, null);
            if (ok) CanghaiStore.notifyDataChanged(getContext());
            return ok;
        }

        @JavascriptInterface
        public String unzipBase64(final String base64) {
            return CanghaiStore.unzipBase64Json(base64);
        }

        @JavascriptInterface
        public String unzipUri(final String uriStr) {
            return CanghaiStore.unzipUriJson(getContext(), uriStr);
        }

        @JavascriptInterface
        public void reportJsError(final String source, final String msg, final String loc) {
            CanghaiErrorCollector.getInstance().log(source != null ? source : "直显桌面", "ERROR", msg, loc != null ? loc : "");
        }

        @JavascriptInterface
        public void copyText(final String text) {
            cursorHandler.post(() -> {
                try {
                    android.content.ClipboardManager cm = (android.content.ClipboardManager)
                            getContext().getSystemService(Context.CLIPBOARD_SERVICE);
                    if (cm != null) {
                        cm.setPrimaryClip(android.content.ClipData.newPlainText("text", text));
                    }
                } catch (Throwable ignored) {}
            });
        }

        @JavascriptInterface
        public void toast(final String msg) {
            cursorHandler.post(() -> {
                try {
                    android.widget.Toast.makeText(getContext(), msg, android.widget.Toast.LENGTH_SHORT).show();
                } catch (Throwable ignored) {}
            });
        }

        @JavascriptInterface
        public String takePendingOpen() {
            return CanghaiStore.takePendingOpen(getContext());
        }

        @JavascriptInterface
        public void setPointerLocked(final boolean locked) {
            DeXPresentation.setPointerLocked(locked);
        }

        @JavascriptInterface
        public void launch(final String comp) {
            openApp(comp);
        }

        @JavascriptInterface
        public void openApp(final String target) {
            if (target == null || target.isEmpty()) return;
            cursorHandler.post(() -> {
                try {
                    if (!target.contains("/") && !target.contains(".")) {
                        if (directDesktopWeb != null) {
                            directDesktopWeb.evaluateJavascript("try { openApp('" + target + "'); } catch(e){}", null);
                        }
                        return;
                    }
                    Context ctx = getContext();
                    Intent launchIntent = null;
                    if (target.contains("/")) {
                        String pkg = target.substring(0, target.indexOf('/'));
                        String cls = target.substring(target.indexOf('/') + 1);
                        if (cls.startsWith(".")) cls = pkg + cls;
                        launchIntent = new Intent();
                        launchIntent.setComponent(new android.content.ComponentName(pkg, cls));
                    } else if (target.contains(".")) {
                        launchIntent = ctx.getPackageManager().getLaunchIntentForPackage(target);
                    }
                    if (launchIntent != null) {
                        launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                        ctx.startActivity(launchIntent);
                        CanghaiErrorCollector.logExt("直显桌面启动应用: " + target);
                    }
                } catch (Throwable t) {
                    CanghaiErrorCollector.logExtError("直显桌面启动应用失败: " + target, t);
                }
            });
        }

        @JavascriptInterface
        public void notifyLaunchpadState(final boolean open) {
            isLaunchpadOpen = open;
        }

        /**
         * Dock 页面上报当前可交互区域（窗口像素）：Dock 栏本身 + 正在弹出的右键菜单。
         * 直显模式没有系统命中测试，输入要由 Java 手动派发，只能靠页面告诉我们
         * "这一块归我"，否则就只能靠猜固定高度，菜单一弹出到区域外就点不动了。
         */
        @JavascriptInterface
        public void setDockHitRect(final int left, final int top, final int right, final int bottom) {
            directDockHitRect.set(left, top, right, bottom);
            directDockHitValid = true;
        }

        @JavascriptInterface
        public void setDockLaunchpad(final boolean open) {
            isLaunchpadOpen = open;
            cursorHandler.post(() -> {
                if (directDockWeb != null) {
                    directDockWeb.evaluateJavascript(
                        "window.__lpSetOpen ? window.__lpSetOpen(" + open + ") : " +
                        "(document.getElementById('lpLayer') && document.getElementById('lpLayer').classList.toggle('open', " + open + "));",
                        null);
                }
            });
        }

        @JavascriptInterface
        public void openLaunchpad() {
            setDockLaunchpad(true);
        }

        @JavascriptInterface
        public void toggleLaunchpad() {
            // 走 setDockLaunchpad 而不是自己发一段 toggle JS：命中测试要靠
            // isLaunchpadOpen 判断"这一屏现在归不归应用台"，状态漏了就会出现
            // 应用台开着却点不动（输入还派给桌面层）。
            setDockLaunchpad(!isLaunchpadOpen);
        }

        @JavascriptInterface
        public void exitDesktop() {
            cursorHandler.post(() -> dismiss());
        }

        @JavascriptInterface
        public String inputTrace() {
            return InputTrace.snapshotJson();
        }

        @JavascriptInterface
        public void inputTraceEnable(final boolean on) {
            InputTrace.setEnabled(on);
        }

        @JavascriptInterface
        public void inputTraceClear() {
            InputTrace.clear();
        }

        @JavascriptInterface
        public String getLanguage() {
            return CanghaiStore.getLanguage(getContext());
        }

        @JavascriptInterface
        public void setLanguage(final String lang) {
            CanghaiStore.setLanguage(getContext(), lang);
        }

        @JavascriptInterface
        public boolean isDockOverlayOn() {
            return false;
        }

        @JavascriptInterface
        public String getCursorLib() {
            return CanghaiStore.cursorLibJson(getContext());
        }
    }

}