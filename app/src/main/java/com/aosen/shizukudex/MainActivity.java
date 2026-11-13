package com.aosen.shizukudex;


import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.hardware.display.DisplayManager;
import android.hardware.input.InputManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.provider.Settings;
import android.util.DisplayMetrics;
import android.util.Log;
import android.view.Display;
import android.view.Gravity;
import android.view.InputDevice;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.Surface;
import android.view.View;
import android.view.WindowManager;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;
import android.widget.CheckBox;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;


import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;


import org.lsposed.hiddenapibypass.HiddenApiBypass;


import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.util.Enumeration;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;


import rikka.shizuku.Shizuku;


public class MainActivity extends AppCompatActivity implements DeXPresentation.OnDesktopChangeListener {


    private static final String TAG = "Aosen_Main";
    private static final String PREF_AUTO_WAKE = "pref_auto_wake_screen";
    /** 手机端网页 <input type=file> 的系统文件选择器请求码。 */
    private static final int REQ_WEB_FILE = 4711;
    /** 导出 .沧海软件 时系统「另存为」的请求码。 */
    private static final int REQ_EXPORT = 4712;
    /** 手机应用导出 APK 的 SAF 请求码。 */
    private static final int REQ_EXPORT_APK = 4714;
    private static final Object APK_EXPORT_LOCK = new Object();
    private static String pendingApkSource;
    private static String pendingApkName;
    private android.webkit.ValueCallback<android.net.Uri[]> webFileCallback;
    /** 最近一次从系统选择器选中的文件 uri。
     *  老机器 WebView 只有 72，没有 DecompressionStream，页面里自带的 inflate 必然失败，
     *  所以 ZIP 解压要回到原生做，页面靠这个 uri 把包交过来。 */
    private String lastPickedUri;


    private DisplayManager displayManager;
    private DeXPresentation dexPresentation;


    private TextView tvShizukuWarning;
    // 用户在 Root 管理器里手动授权后回到前台，需要重新探测；节流避免反复弹 su 授权框
    private long lastRootReprobeMs = 0L;
    private View viewTouchpad;
    private View viewBlackScreenOverlay;
    // 黑屏触控 dock 提醒：10 秒倒计时到 0s 收起，恢复纯黑屏
    private View blackHintDock;
    private View blackHintPortrait;
    private View blackHintLandscape;
    private TextView tvHintCountdown;
    private TextView tvHintCountdownLand;
    private TextView tvHintTitle;
    private TextView tvHintSubtitle;
    private android.widget.ProgressBar pbHintCountdown;
    private android.os.CountDownTimer blackHintTimer;
    private Runnable blackHintHideRunnable;


    private android.app.KeyguardManager keyguardManager;
    private final android.content.BroadcastReceiver lockStateReceiver = new android.content.BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            String action = intent != null ? intent.getAction() : null;
            if (android.content.Intent.ACTION_USER_PRESENT.equals(action)
                    || android.content.Intent.ACTION_SCREEN_OFF.equals(action)
                    || android.content.Intent.ACTION_SCREEN_ON.equals(action)) {
                final boolean userPresent =
                        android.content.Intent.ACTION_USER_PRESENT.equals(action);
                runOnUiThread(() -> {
                    // 解锁瞬间立刻重试挂载外屏（锁屏期间无法投?DeX?
                    if (!isKeyguardLocked()) {
                        detectAndMountExternalDisplay();
                    }
                    /* 每次解锁重放一遍黑屏触控介绍（最上方就是「四指轻点退出」），
                       防止用户解锁后对着一片纯黑以为手机坏了。 */
                    if (userPresent && isBlackScreenActive()) {
                        showBlackHintDock();
                    }
                });
            }
        }
    };


    private boolean isKeyguardLocked() {
        try {
            return keyguardManager != null && keyguardManager.isKeyguardLocked();
        } catch (Throwable ignored) {
            return false;
        }
    }


    // ==================== 界面三大核心状态视?====================
    private View layoutStateShizuku;
    private View layoutStateWaiting;
    private View layoutStateConnected;


    // 第一步：Shizuku 未授权状态控?
    private TextView tvShizukuBigTitle;
    private View btnRequestRoot;
    private View btnRequestShizuku;
    private View btnOpenShizukuApp;


    // 第二步：等待设备连接状态控?
    private CheckBox cbMoonlightWaiting;
    private TextView tvMoonlightIp;
    private View btnCopyIp;


    // 第三步：已连接屏幕列表工作台控件
    private View itemPhoneScreen;
    private TextView tvPhoneResRatio;
    private TextView tvPhoneActiveBadge;
    private TextView btnPhoneActivate;
    private TextView btnPhoneMore;


    private View itemExternalScreen;
    private TextView tvExtCardTitle;
    private TextView tvExtResRatio;
    private TextView tvExtActiveBadge;
    private TextView btnExtActivate;
    private TextView btnExtBlackTouchpad;
    private TextView btnExtMore;
    private TextView btnExtHomeOfficial;
    private TextView btnExtHomeAosen;


    // Moonlight 串流卡片控件
    private View itemMoonlightScreen;
    private TextView tvMoonlightCardTitle;
    private TextView tvMoonlightCardSub;
    private TextView tvMoonlightCardBadge;
    private TextView btnMoonlightCardToggle;
    private TextView btnMoonlightCardCopyIp;


    // 专属设置页面控件
    private View layoutScreenDetailPage;
    private TextView btnDetailBack;
    private TextView tvDetailTitle;
    private TextView tvDetailDisplayInfo;
    private TextView tvDetailScalePercent;
    private TextView btnDetailScaleMinus;
    private SeekBar sbDetailScale;
    private TextView btnDetailScalePlus;
    private FrameLayout flDetailPreviewContainer;
    private TextView btnDetailStyleArrow;
    private TextView btnDetailStyleDot;
    private TextView btnDetailStyleBlock;
    private TextView btnDetailStyleSvg;
    private View llDetailSvgContainer;
    private EditText etDetailCustomSvg;
    private TextView btnDetailSvgSample1;
    private TextView btnDetailSvgSample2;
    private TextView btnDetailSvgSample3;
    private TextView btnDetailApplySvg;
    private View cardDetailScale;
    private View cardDetailStyle;


    private static volatile MainActivity sInstance;
    private com.alkaidlab.sunshine.service.MouseCursorView phoneCursorView = null;
    private FrameLayout rootBox = null;
    private float lastPhysMouseX = -1f;
    private float lastPhysMouseY = -1f;
    private float phoneCursorX = 300f;
    private float phoneCursorY = 500f;

    public static MainActivity getInstance() {
        return sInstance;
    }

    public DeXPresentation getDexPresentation() {
        return dexPresentation;
    }

    public void setActiveDisplayId(int id) {
        this.activeDisplayId = id;
        refreshConnectedStateUI();
        syncToPhoneWeb();
    }

    private volatile boolean isPhoneResumed = false;

    /**
     * 外屏上有一个「按下还没抬起」的手势。
     * 按下那一瞬间还在 PointerCapture 里，但注入到外屏的 DOWN 会让系统把指针
     * 判给相邻窗口、顺手收回我们的捕获（日志里「按下 → 40ms 后已释放」就是它）。
     * 捕获一丢，渲染帧被系统翻译成「无设备的触摸」，UP 就被当成手机上的手指点 ——
     * 外屏永远收不到抬起，系统认为一直按着：点应用只选中不打开，一动就框选。
     * 这个标记让 UP 无论如何都补回外屏。
     */
    private volatile boolean externalPressActive = false;

    /** 夺回 PointerCapture 的重试任务；同一时刻只允许一条在跑。 */
    private Runnable captureRetryRunnable;
    private int captureRetryCount;

    /**
     * 外屏刚通过 am start 拉起桌面后，WindowManager 可能把顶层焦点切到虚拟屏，
     * 导致用户在手机端屏幕上按返回键被派发到外屏。若手机端正在前台操作（且未黑屏），
     * 立即把顶层焦点拉回手机主屏（Display 0）。
     */
    public static void refocusPhoneDisplayAfterExternalLaunch() {
        final MainActivity inst = sInstance;
        if (inst == null) return;
        inst.runOnUiThread(inst::refocusPhoneDisplayIfResumed);
    }

    private void refocusPhoneDisplayIfResumed() {
        if (!isPhoneResumed || isFinishing() || isDestroyed() || isBlackScreenActive()) return;
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                android.app.ActivityOptions opts = android.app.ActivityOptions.makeBasic();
                opts.setLaunchDisplayId(Display.DEFAULT_DISPLAY);
                Intent self = new Intent(this, MainActivity.class);
                self.addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP
                        | Intent.FLAG_ACTIVITY_REORDER_TO_FRONT
                        | Intent.FLAG_ACTIVITY_NO_ANIMATION);
                startActivity(self, opts.toBundle());
            }
            if (getWindow() != null && getWindow().getDecorView() != null) {
                getWindow().getDecorView().requestFocus();
            }
            if (webPhoneHome != null && webPhoneHome.getVisibility() == View.VISIBLE) {
                webPhoneHome.requestFocus();
            }
        } catch (Throwable ignored) {}
    }

    private void scheduleRefocusPhoneDisplay() {
        Handler h = new Handler(Looper.getMainLooper());
        h.postDelayed(this::refocusPhoneDisplayIfResumed, 240L);
        h.postDelayed(this::refocusPhoneDisplayIfResumed, 500L);
        h.postDelayed(this::refocusPhoneDisplayIfResumed, 820L);
    }

    /**
     * 若系统因外屏 Activity 刚启动而把手机返回键派发到了 DexHomeActivity，
     * 先转交手机端 MainActivity 判断是否有黑屏遮罩或配置弹层需要关闭。
     */
    public static boolean tryConsumeBackFromExternal(KeyEvent event) {
        final MainActivity inst = sInstance;
        if (inst == null || !inst.isPhoneResumed || inst.isFinishing() || inst.isDestroyed() || inst.isBlackScreenActive()) {
            return false;
        }
        if (event != null && event.getAction() == KeyEvent.ACTION_UP) {
            inst.runOnUiThread(inst::handleSystemBack);
        }
        return true;
    }

    private volatile boolean externalImeLandscapeLocked = false;
    private volatile long lastPhoneWebTouchAt = 0L;
    private volatile int physicalHoldRotation = Surface.ROTATION_0;
    private android.view.OrientationEventListener physicalOrientationListener = null;

    /**
     * 当用户在外屏 / Moonlight 上点击、唤醒输入法或处于黑屏触控板时，
     * 将手机主屏（Display 0）置为横屏方向（ROTATION_90），让输入法读取到主屏横屏状态并自动启用精巧的「横屏悬浮键盘」；
     * 一旦用户回到手机页面触摸操作，立即恢复竖屏/自由旋转。
     */
    public static void onExternalInteractionOrIme() {
        final MainActivity inst = sInstance;
        if (inst == null) {
            ShizukuCmd.setDisplayZeroLandscapeForExternalIme(true);
            return;
        }
        inst.runOnUiThread(() -> inst.setExternalImeLandscapeLock(true));
    }

    public void setExternalImeLandscapeLock(boolean lock) {
        // 不再强制改变手机当前 Activity 的物理屏幕方向，保持手机端稳定自由旋转
        externalImeLandscapeLocked = false;
    }

    private void initPhysicalOrientationListener() {
        if (physicalOrientationListener != null) return;
        try {
            physicalOrientationListener = new android.view.OrientationEventListener(this) {
                @Override
                public void onOrientationChanged(int orientation) {
                    if (orientation == ORIENTATION_UNKNOWN) return;
                    int newRot;
                    if (orientation >= 315 || orientation < 45) {
                        newRot = Surface.ROTATION_0;
                    } else if (orientation >= 45 && orientation < 135) {
                        newRot = Surface.ROTATION_270;
                    } else if (orientation >= 135 && orientation < 225) {
                        newRot = Surface.ROTATION_180;
                    } else {
                        newRot = Surface.ROTATION_90;
                    }
                    if (newRot != physicalHoldRotation) {
                        physicalHoldRotation = newRot;
                        if (isBlackScreenActive() && blackHintDock != null && blackHintDock.getVisibility() == View.VISIBLE) {
                            updateBlackHintDockRotation();
                        }
                    }
                }
            };
            if (physicalOrientationListener.canDetectOrientation()) {
                physicalOrientationListener.enable();
            }
        } catch (Throwable ignored) {}
    }

    /**
     * 黑屏触控下若窗口被锁为横屏（ROTATION_90 以供外屏悬浮输入法），而用户实际竖握手机，
     * 自动将手指滑动的 (rawDx, rawDy) 旋转回用户握持手机的自然方向。
     */
    private float[] remapTouchDeltaForRotation(float rawDx, float rawDy) {
        if (!isBlackScreenActive()) return new float[]{ rawDx, rawDy };
        int dispRot = Surface.ROTATION_0;
        try {
            dispRot = getWindowManager().getDefaultDisplay().getRotation();
        } catch (Throwable ignored) {}
        int diff = (dispRot - physicalHoldRotation + 4) % 4;
        if (diff == 1) {
            return new float[]{ -rawDy, rawDx };
        } else if (diff == 2) {
            return new float[]{ -rawDx, -rawDy };
        } else if (diff == 3) {
            return new float[]{ rawDy, -rawDx };
        }
        return new float[]{ rawDx, rawDy };
    }

    private void updateBlackHintDockRotation() {
        if (blackHintDock == null) return;
        int dispRot = Surface.ROTATION_0;
        try {
            dispRot = getWindowManager().getDefaultDisplay().getRotation();
        } catch (Throwable ignored) {}
        int diff = (dispRot - physicalHoldRotation + 4) % 4;
        float deg = 0f;
        if (diff == 1) deg = -90f;
        else if (diff == 2) deg = 180f;
        else if (diff == 3) deg = 90f;
        blackHintDock.setRotation(deg);
    }


    public static void toggleActiveDisplayFromDesktop() {
        if (sInstance != null) {
            sInstance.runOnUiThread(() -> {
                if (sInstance.activeDisplayId == 0) {
                    int target = (sInstance.externalDisplayId > 0) ? sInstance.externalDisplayId : 2;
                    sInstance.activateDisplay(target);
                } else {
                    sInstance.activateDisplay(0);
                }
            });
        }
    }


    public static int getActiveDisplayIdStatic() {
        return (sInstance != null) ? sInstance.activeDisplayId : 0;
    }

    /** 外屏桌面左上角改了光标样式/大小后，让手机端网页同步显示当前选择。 */
    public static void notifyCursorPrefsChanged() {
        MainActivity inst = sInstance;
        if (inst != null) inst.syncToPhoneWeb();
    }

    /**
     * 应用数据（装 / 卸 / 改名 / 图标 / 光标库）在任意一端变化后，
     * 让手机页重新读一次 CanghaiStore，而不是继续显示上一次的内存副本。
     */
    public static void notifyAppsChanged() {
        final MainActivity inst = sInstance;
        if (inst == null) return;
        inst.runOnUiThread(() -> {
            try {
                if (inst.webPhoneHome != null) {
                    inst.webPhoneHome.evaluateJavascript(
                            "window.__chReloadState && window.__chReloadState();", null);
                }
            } catch (Throwable ignored) {}
        });
    }

    public static void notifyLanguageChangedFromExt(final String lang) {
        final MainActivity inst = sInstance;
        if (inst == null) return;
        inst.runOnUiThread(() -> {
            try {
                if (inst.webPhoneHome != null) {
                    inst.webPhoneHome.evaluateJavascript(
                            "window.CHI18n && window.CHI18n.setLang && window.CHI18n.setLang("
                                    + org.json.JSONObject.quote(lang) + ");", null);
                }
            } catch (Throwable ignored) {}
        });
    }


    public static void updateRemoteMouse(float x, float y) {
        MainActivity inst = sInstance;
        if (inst == null) return;
        // 只有把「Moonlight 客户端」这个虚拟外设插到有线外屏时，才在那块屏上画软件光标。
        // 插在串流屏 / 手机屏时这里什么都不做，光标由各自的落点自己维护。
        PeripheralBrain.Host host = PeripheralBrain.getInstance()
                .hostOf(PeripheralBrain.MOONLIGHT_REMOTE_SLOT);
        if (host == PeripheralBrain.Host.WIRED && inst.dexPresentation != null) {
            inst.dexPresentation.updateCursor(x, y);
        }
    }


    private int activeDisplayId = 0; // 0=手机主屏, >0=外接屏幕
    private int currentDetailDisplayId = 0;
    private com.alkaidlab.sunshine.service.MouseCursorView detailPreviewCursor = null;


    private static final String PREF_WINDOW_MODE = "pref_window_mode";
    private static final String PREF_HOME_TYPE = "pref_home_type";
    private static final String PREF_MOONLIGHT_CHECKED = "pref_moonlight_checked";
    public static final int HOME_OFFICIAL = 0; // 三星官方 DeX 桌面（默认，窗口模式?
    public static final int HOME_AOSEN = 1;    // 自绘沧海桌面（DexHome?

    private static final String PREF_SCREEN_ROTATION = "pref_screen_rotation";


    private EditText etDpiInput;
    private TextView btnConfirmDpi;


    private int externalDisplayId = -1;
    private int externalWidth = 1920;
    private int externalHeight = 1080;
    private int currentDpi = 213; // 标准 213 DPI


    private float cursorX = 960f;
    private float cursorY = 540f;
    private boolean isMouseOnPhoneScreen = false;


    private float touchLastX;
    private float touchLastY;
    private float touchDownX;
    private float touchDownY;
    private float twoFingerStartX = 0f;
    private float twoFingerStartY = 0f;
    private float twoFingerLastX = 0f;
    private float twoFingerLastY = 0f;
    private long twoFingerDownTime = 0L;
    private long lastTwoFingerSwipeTime = 0L;
    private boolean twoFingerMoved = false;
    private boolean twoFingerActive = false;
    private long touchDownTime;
    private boolean isDragMotion = false;


    // 多指手势识别?指退出黑屏；3指宫格已随单桌面下线?
    private int gestureMaxPointers = 1;
    private long gestureStartTime = 0L;
    private boolean gestureMoved = false;
    private boolean multiFingerActionTriggered = false;
    private float multiFingerStartX = 0f;
    private float multiFingerStartY = 0f;


    // 会话状态管理：外接显示器插入期间的自动黑屏触发与用户手动退出状?
    private static boolean sAutoWakeTriggeredForCurrentPlug = false;
    private static boolean sUserExitedForCurrentPlug = false;
    private int lastExternalDisplayId = -1;


    private final Shizuku.OnBinderReceivedListener binderReceivedListener = () -> {
        Log.i(TAG, "Shizuku Binder received, 正在初始?..");
        ShizukuCmd.init();
        new Thread(() -> {
            try {
                ShizukuCmd.unbindAllExternalInputFromDisplaySync();
                ShizukuCmd.restoreSystemPointerSprite(MainActivity.this);
            } catch (Throwable ignored) {}
        }).start();
        runOnUiThread(this::updateUiState);
    };


    private final Shizuku.OnBinderDeadListener binderDeadListener = () -> {
        Log.w(TAG, "Shizuku Binder dead, 返回第一步授权页?");
        runOnUiThread(() -> {
            updateUiState();
            Toast.makeText(MainActivity.this, "Shizuku 服务已断开", Toast.LENGTH_SHORT).show();
        });
    };


    @Override
    protected void attachBaseContext(Context newBase) {
        super.attachBaseContext(LocaleHelper.onAttach(newBase));
    }


    @Override
    protected void onResume() {
        super.onResume();
        boolean wasResumed = isPhoneResumed;
        isPhoneResumed = true;
        // 黑屏触控状态下从后台回到前台（或切回应用），提示 10 秒操作手势
        if (isBlackScreenActive() && !wasResumed) {
            showBlackHintDock();
        }
        checkShizukuPermissionStatus();
        /* 特权状态一律不缓存：每次进前台都重新探一次 Root + Shizuku。
           用户可能刚在 Magisk / KernelSU 里手动授权，也可能中途把授权收回，
           只信开机时那一次探测就会出现「明明能用却显示没有、明明没了还显示有」。
           800ms 内连点回来不重复探，避免首屏被反复 su 拖慢。 */
        if (System.currentTimeMillis() - lastRootReprobeMs > 800L) {
            lastRootReprobeMs = System.currentTimeMillis();
            refreshRootPermission(false);
        } else {
            updateUiState();
        }
        try {
            if (!AosenMoonlightService.isHostRunning()) {
                AosenMoonlightService.start(this, null);
            }
        } catch (Throwable ignored) {}
    }

    @Override
    protected void onPause() {
        isPhoneResumed = false;
        /* 手机态独占的物理鼠标必须还给系统：我们退到后台还攥着 fd，用户在别的应用里
           会以为鼠标彻底坏了。真设备通道（绑了外屏）自己管生命周期，不在这里动。 */
        try { PeripheralBrain.releasePhoneMouseGrabIfIdle(); } catch (Throwable ignored) { }
        // 软件退至后台或切走时，立即解除对手机主屏（Display 0）的方向锁定与解冻旋转
        setExternalImeLandscapeLock(false);
        ShizukuCmd.setDisplayZeroLandscapeForExternalIme(false);
        super.onPause();
    }

    @Override
    protected void onStop() {
        // 兜底再次确保方向恢复自由
        setExternalImeLandscapeLock(false);
        ShizukuCmd.setDisplayZeroLandscapeForExternalIme(false);
        super.onStop();
    }


    private void checkShizukuPermissionStatus() {
        // Root 已可用时同样视为就绪，不能只认 Shizuku 授权，否则纯 Root 用户会一直看到「必须权限」
        boolean authorized = isShizukuReady();
        if (tvShizukuWarning != null) {
            tvShizukuWarning.setVisibility(authorized ? View.GONE : View.VISIBLE);
        }
    }

    /**
     * 用户可能是在 KernelSU / Magisk 里手动打开的 Root 开关，此时 App 内缓存的探测结果仍是 false。
     * 这里强制重新跑一次 su -c id，并把结果同步给原生界面与手机端网页。
     */
    private void refreshRootPermission(boolean withToast) {
        /* 探之前先记一下旧值：每次进前台都会重探，只有「刚刚从没有变成有」时才做
           补权限 / 拉 Shizuku 这些重活，否则每次切回来都跑一遍会明显拖慢首屏。 */
        final boolean hadRoot = RootCmd.isAvailable();
        RootCmd.reprobeAsync(getApplicationContext(), isRoot -> {
            runOnUiThread(() -> {
                if (isRoot && !hadRoot) {
                    RootCmd.grantAppPermissionsViaRoot(getApplicationContext());
                    RootCmd.autoStartShizukuIfNeeded();
                }
                if (isRoot && withToast) {
                    Toast.makeText(MainActivity.this, "特权通道已就绪", Toast.LENGTH_SHORT).show();
                } else if (!isRoot && withToast) {
                    Toast.makeText(MainActivity.this,
                            "没有检测到特权通道，需要在 Root 管理器或 Shizuku 里允许本应用",
                            Toast.LENGTH_LONG).show();
                }
                checkShizukuPermissionStatus();
                updateUiState(); // 内部会 syncToPhoneWeb()，网页特权小标随之刷新
            });
        });
    }


    // ==========================================================
    // 桌面快捷方式：点开直达某个沧海应用，不用先进沧海系统
    // ==========================================================

    /** 快捷方式 Intent 上携带的待打开应用 id。 */
    public static final String EXTRA_SHORTCUT_APP = "canghai_open_app";

    private boolean hasShortcutPermission() {
        try {
            return checkSelfPermission("com.android.launcher.permission.INSTALL_SHORTCUT")
                    == android.content.pm.PackageManager.PERMISSION_GRANTED;
        } catch (Throwable t) {
            return false;
        }
    }

    /** 从 CanghaiStore 里取应用名，拿不到就退回 id；内置应用从 assets 的 app.json 取。 */
    private String findHtmlAppName(String appId) {
        try {
            org.json.JSONArray arr = new org.json.JSONArray(CanghaiStore.htmlAppsJson(this));
            for (int i = 0; i < arr.length(); i++) {
                org.json.JSONObject o = arr.optJSONObject(i);
                if (o != null && appId.equals(o.optString("id"))) {
                    String n = o.optString("name", "");
                    return n.isEmpty() ? appId : n;
                }
            }
        } catch (Throwable ignored) {
        }
        if (CanghaiStore.isBuiltinApp(this, appId)) return CanghaiStore.builtinAppName(this, appId);
        return appId;
    }

    /**
     * 真正往桌面放图标。
     * Android 8+ 走 ShortcutManager.requestPinShortcut —— 系统会弹「添加到主屏幕」确认框，
     * 由用户决定，不需要我们去猜 ROM 有没有放行；
     * 再老的机器退回 INSTALL_SHORTCUT 广播。
     * icon 传 null 时退回应用自己的图标；有用户设置的图标就必须用它。
     * 必须在主线程调用。返回「是否成功发起了创建」，发起不等于桌面真出现图标。
     */
    private boolean pinShortcut(Intent target, String id, String name, android.graphics.Bitmap icon) {
        try {
            if (icon == null) icon = appIconBitmap(getPackageName());
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                android.content.pm.ShortcutManager sm =
                        getSystemService(android.content.pm.ShortcutManager.class);
                boolean supported = sm != null && sm.isRequestPinShortcutSupported();
                Log.i(TAG, "pinShortcut id=" + id + " supported=" + supported
                        + " icon=" + (icon != null) + " launcher=" + getHomeLauncher());
                if (supported) {
                    android.content.pm.ShortcutInfo info = new android.content.pm.ShortcutInfo.Builder(this, id)
                            .setShortLabel(name)
                            .setLongLabel(name)
                            .setIcon(android.graphics.drawable.Icon.createWithBitmap(icon))
                            .setIntent(target)
                            .build();
                    sm.requestPinShortcut(info, null);
                    return true;
                }
                /* 桌面不支持 pin shortcut（这台平板的默认桌面是定制的 Funbook），
                   走老广播也只是尽力而为 —— Android 8+ 上系统通常直接忽略。 */
            }

            Intent i = new Intent("com.android.launcher.action.INSTALL_SHORTCUT");
            i.putExtra(Intent.EXTRA_SHORTCUT_NAME, name);
            if (icon != null) {
                i.putExtra(Intent.EXTRA_SHORTCUT_ICON, icon);
            } else {
                i.putExtra(Intent.EXTRA_SHORTCUT_ICON_RESOURCE,
                        Intent.ShortcutIconResource.fromContext(this, R.mipmap.ic_launcher));
            }
            i.putExtra(Intent.EXTRA_SHORTCUT_INTENT, target);
            i.putExtra("duplicate", false);
            sendBroadcast(i);
            Log.i(TAG, "pinShortcut 已发 INSTALL_SHORTCUT 广播（老桌面路径）");
            return true;
        } catch (Throwable t) {
            Log.w(TAG, "pinShortcut 失败: " + t);
            return false;
        }
    }

    /** 把任一 Drawable 画成方形 Bitmap，做快捷方式图标用。 */
    private static android.graphics.Bitmap drawableToBitmap(android.graphics.drawable.Drawable d, int size) {
        try {
            if (d == null) return null;
            if (d instanceof android.graphics.drawable.BitmapDrawable
                    && ((android.graphics.drawable.BitmapDrawable) d).getBitmap() != null) {
                return android.graphics.Bitmap.createScaledBitmap(
                        ((android.graphics.drawable.BitmapDrawable) d).getBitmap(), size, size, true);
            }
            android.graphics.Bitmap bmp = android.graphics.Bitmap.createBitmap(
                    size, size, android.graphics.Bitmap.Config.ARGB_8888);
            android.graphics.Canvas cv = new android.graphics.Canvas(bmp);
            d.setBounds(0, 0, size, size);
            d.draw(cv);
            return bmp;
        } catch (Throwable t) {
            return null;
        }
    }

    /** 某个包的启动图标 → 方形 Bitmap。 */
    private android.graphics.Bitmap appIconBitmap(String pkg) {
        try {
            return drawableToBitmap(getPackageManager().getApplicationIcon(pkg), 128);
        } catch (Throwable t) {
            return null;
        }
    }

    /** data:image/png;base64,... 或纯 base64 → Bitmap，解不开返回 null。 */
    private static android.graphics.Bitmap dataUrlToBitmap(String dataUrl) {
        try {
            if (dataUrl == null) return null;
            int comma = dataUrl.indexOf(',');
            String b64 = comma >= 0 ? dataUrl.substring(comma + 1) : dataUrl;
            byte[] raw = android.util.Base64.decode(b64, android.util.Base64.DEFAULT);
            return android.graphics.BitmapFactory.decodeByteArray(raw, 0, raw.length);
        } catch (Throwable t) {
            return null;
        }
    }

    private String getHomeLauncher() {
        try {
            Intent i = new Intent(Intent.ACTION_MAIN);
            i.addCategory(Intent.CATEGORY_HOME);
            android.content.pm.ResolveInfo r = getPackageManager().resolveActivity(i, 0);
            return r == null ? "?" : r.activityInfo.packageName;
        } catch (Throwable t) {
            return "?";
        }
    }

    /** 在主线程跑一件事并同步拿到布尔结果，给 JS 桥用（JS 线程不能直接碰 UI）。 */
    private boolean runPinOnUiThread(java.util.concurrent.Callable<Boolean> job) {
        if (android.os.Looper.myLooper() == android.os.Looper.getMainLooper()) {
            try {
                return Boolean.TRUE.equals(job.call());
            } catch (Throwable t) {
                return false;
            }
        }
        final boolean[] out = { false };
        final java.util.concurrent.CountDownLatch latch = new java.util.concurrent.CountDownLatch(1);
        runOnUiThread(() -> {
            try {
                out[0] = Boolean.TRUE.equals(job.call());
            } catch (Throwable ignored) {
            } finally {
                latch.countDown();
            }
        });
        try {
            latch.await(2, java.util.concurrent.TimeUnit.SECONDS);
        } catch (InterruptedException ignored) {
        }
        return out[0];
    }

    // ==========================================================
    // 沉浸式全屏：内容铺到系统栏下面，页面自己按 inset 留白
    // ==========================================================

    private void applyEdgeToEdge() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                getWindow().setStatusBarColor(android.graphics.Color.TRANSPARENT);
                getWindow().setNavigationBarColor(android.graphics.Color.TRANSPARENT);
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                getWindow().setStatusBarContrastEnforced(false);
                getWindow().setNavigationBarContrastEnforced(false);
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                /* 打孔屏默认会把整页压到挖孔下方，顶部露出一条系统黑边，和页面背景很割裂。
                   SHORT_EDGES 让背景一直铺到挖孔两侧，页面再按 sysTop/sysLeft/sysRight
                   把「沧海系统 / IP / 黑屏触控」这些信息和按钮留在安全区里。 */
                WindowManager.LayoutParams attrs = getWindow().getAttributes();
                attrs.layoutInDisplayCutoutMode =
                        WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES;
                getWindow().setAttributes(attrs);
            }
            getWindow().getDecorView().setSystemUiVisibility(
                    View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                            | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                            | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION);
        } catch (Throwable t) {
            Log.w(TAG, "applyEdgeToEdge: " + t);
        }
    }

    private int systemBarHeight(String name) {
        try {
            int id = getResources().getIdentifier(name, "dimen", "android");
            return id > 0 ? getResources().getDimensionPixelSize(id) : 0;
        } catch (Throwable t) {
            return 0;
        }
    }

    /** 打孔屏的安全区 {top, left, right}（物理像素）；拿不到就全 0，页面退回状态栏高度。 */
    private int[] cutoutSafeInsets() {
        int[] out = {0, 0, 0};
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            try {
                android.view.WindowInsets wi = getWindow().getDecorView().getRootWindowInsets();
                android.view.DisplayCutout c = wi != null ? wi.getDisplayCutout() : null;
                if (c != null) {
                    out[0] = c.getSafeInsetTop();
                    out[1] = c.getSafeInsetLeft();
                    out[2] = c.getSafeInsetRight();
                }
            } catch (Throwable ignored) { }
        }
        return out;
    }

    /** 快捷方式点进来时把应用直接拉起来；应用已被移除就留在主界面并提示。 */
    private void consumeShortcutIntent(Intent intent) {
        if (intent == null) return;
        String appId = intent.getStringExtra(EXTRA_SHORTCUT_APP);
        if (appId == null || appId.isEmpty()) return;
        intent.removeExtra(EXTRA_SHORTCUT_APP);
        try {
            if (CanghaiStore.readHtmlApp(this, appId) != null) {
                /* 已经开着的窗口直接提回来，别为了一个快捷方式再开一份新的 */
                if (HtmlAppActivity.focusExistingWindow(this, appId)) {
                    Log.i(TAG, "快捷方式复用已开着的窗口 " + appId);
                    return;
                }
                Intent open = new Intent(this, HtmlAppActivity.class);
                open.putExtra(HtmlAppActivity.EXTRA_APP_ID, appId);
                open.putExtra(HtmlAppActivity.EXTRA_APP_TITLE, findHtmlAppName(appId));
                open.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                startActivity(open);
                /* 这里原来会 moveTaskToBack(true) 把主页踹到最后面，于是壳一关就掉到桌面。
                   新任务本身就会把主页压到后面，不需要再踹一脚。 */
            } else {
                Toast.makeText(this, "这个应用已经被移除了", Toast.LENGTH_SHORT).show();
            }
        } catch (Throwable t) {
            Log.w(TAG, "consumeShortcutIntent: " + t);
        }
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        sInstance = this;
        CanghaiErrorCollector.getInstance().init(this);
        PeripheralBrain.getInstance().init(this);
        // 纯 Root 通道枚举外接输入设备需要 Context（外屏重绑/解绑都用得到）。
        ShizukuCmd.setAppContext(getApplicationContext());
        // 蓝牙手柄闲置会自己休眠断开，重连后是新的 deviceId，旧关联失效。
        // 注册热插拔监听，设备一上线就自动补绑到当前虚拟屏。
        ShizukuCmd.watchInputDevices(getApplicationContext());
        ShizukuCmd.addInputDeviceListener(() -> runOnUiThread(this::syncToPhoneWeb));
        // 指针样式/大小是手机端网页与外屏桌面共用的一份设置，主进程启动时先载入，
        // 否则 syncToPhoneWeb 读到的永远是默认值，界面显示与实际指针不一致。
        com.alkaidlab.sunshine.service.MouseCursorPrefs.load(getApplicationContext());


        // 探测 Root 特权（Magisk / KernelSU / APatch 等）?
        // 若手机已 root，自动静默授权并无感进入，彻底绕?Shizuku 授权门槛
        lastRootReprobeMs = System.currentTimeMillis();
        RootCmd.probeAsync(getApplicationContext(), isRoot -> {
            if (isRoot) {
                Log.i(TAG, "Root 权限已就绪，自动授予应用特权并无感进?");
                RootCmd.grantAppPermissionsViaRoot(getApplicationContext());
                RootCmd.autoStartShizukuIfNeeded();
                runOnUiThread(this::updateUiState);
            }
        });


        // 壁纸读取权限：主路径 getWallpaperFile 不需要，但三?getDrawable 回退
        // 路径会读媒体文件。静默申请，拒绝也不影响主要功能?
        normalizeWindowMode();
        // 用户要求：软件开启中，Moonlight 始终自动开启，无需手动点击
        try {
            if (!AosenMoonlightService.isHostRunning()) {
                AosenMoonlightService.start(this, null);
            }
        } catch (Throwable ignored) {}


        // 允许在锁屏上展示并自动亮屏，支持插入外屏时直接唤?
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true);
            setTurnScreenOn(true);
        } else {
            getWindow().addFlags(
                    WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED
                    | WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON
                    | WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD
            );
        }


        try {
            HiddenApiBypass.addHiddenApiExemptions("");
            Log.i(TAG, "成功添加隐藏API豁免");
        } catch (Throwable t) {
            Log.w(TAG, "HiddenApiBypass: " + t.getMessage());
        }

        // 提前启动 root 常驻桥，让它的 Binder 句柄在外接屏 surfaceCreated 之前送达主线程。
        // 虚拟屏由 root UID 创建才能保住 TRUSTED + SHOULD_SHOW_SYSTEM_DECORATIONS。
        try {
            CanghaiStore.syncCursorPrefsToNative(this);
            RootInputBridge.warmUp(this);
        } catch (Throwable ignored) {}

        // 沉浸式全屏：内容铺到系统栏下面，页面按 inset 自己留白（否则被三大金刚键挤掉一截）
        applyEdgeToEdge();

        setContentView(R.layout.activity_main);
        setupDebugErrorOverlay();

        // 手机屏幕自绘光标层与系统黑箭头隐藏，并开启无边界硬件 PointerCapture 监听
        rootBox = findViewById(R.id.rootContainer);
        if (rootBox != null) {
            rootBox.setDescendantFocusability(android.view.ViewGroup.FOCUS_BEFORE_DESCENDANTS);
            rootBox.setFocusable(true);
            rootBox.setFocusableInTouchMode(true);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                android.view.PointerIcon nullIcon = android.view.PointerIcon.getSystemIcon(this, android.view.PointerIcon.TYPE_NULL);
                rootBox.setPointerIcon(nullIcon);
                if (getWindow() != null && getWindow().getDecorView() != null) {
                    getWindow().getDecorView().setPointerIcon(nullIcon);
                }
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                rootBox.setOnCapturedPointerListener((v, ev) -> handleCapturedPointerEvent(ev));
                if (getWindow() != null && getWindow().getDecorView() != null) {
                    getWindow().getDecorView().setOnCapturedPointerListener((v, ev) -> handleCapturedPointerEvent(ev));
                }
            }
            phoneCursorView = new com.alkaidlab.sunshine.service.MouseCursorView(this);
            phoneCursorView.setDisplayId(0);
            phoneCursorView.setVisibility(View.GONE);
            phoneCursorView.setElevation(100000f);
            FrameLayout.LayoutParams clp = new FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.WRAP_CONTENT,
                    FrameLayout.LayoutParams.WRAP_CONTENT);
            rootBox.addView(phoneCursorView, clp);
        }

        // 应用装/卸监听：之前手机应用走系统卸载框后没人刷新列表，
        // 「手机端的安装软件卸载后图标还在」就是这里漏的；装/卸/覆盖一律通知双端重读。
        try {
            android.content.IntentFilter pf = new android.content.IntentFilter();
            pf.addAction(Intent.ACTION_PACKAGE_ADDED);
            pf.addAction(Intent.ACTION_PACKAGE_REMOVED);
            pf.addAction(Intent.ACTION_PACKAGE_REPLACED);
            pf.addDataScheme("package");
            pkgWatchReceiver = new android.content.BroadcastReceiver() {
                @Override
                public void onReceive(Context context, Intent intent) {
                    CanghaiStore.notifyDataChanged(MainActivity.this);
                }
            };
            if (Build.VERSION.SDK_INT >= 33) {
                registerReceiver(pkgWatchReceiver, pf, Context.RECEIVER_NOT_EXPORTED);
            } else {
                registerReceiver(pkgWatchReceiver, pf);
            }
        } catch (Throwable t) {
            Log.w(TAG, "pkgWatchReceiver: " + t.getMessage());
        }


        // 提前在手机主屏预热应用列表权限（三星 One UI 权限弹窗将在手机屏弹出供用户点击，避免弹到外屏）
        prewarmAppListPermission();


        // 启动时主动清理系统底层可能残留的输入设备绑定，并还原手机主屏的原生鼠标指针
        new Thread(() -> {
            try {
                // 不要用 Shizuku.pingBinder() 挡门：装了 Shizuku 但没授权时它也返回 true，
                // 会走进 Shizuku 分支抛 SecurityException 被 catch 吞掉，清理静默失效，
                // 手柄/鼠标就永久卡在一块已销毁的屏幕 id 上。选路由 ShizukuCmd 内部判定。
                ShizukuCmd.unbindAllExternalInputFromDisplaySync();
                ShizukuCmd.restoreSystemPointerSprite(MainActivity.this);
            } catch (Throwable ignored) {}
        }).start();


        viewBlackScreenOverlay = findViewById(R.id.viewBlackScreenOverlay);


        // 锁屏状态监听：锁屏时展示提示遮罩，解锁后自动恢复投屏流?
        keyguardManager = (android.app.KeyguardManager) getSystemService(Context.KEYGUARD_SERVICE);
        android.content.IntentFilter lockFilter = new android.content.IntentFilter();
        lockFilter.addAction(android.content.Intent.ACTION_USER_PRESENT);
        lockFilter.addAction(android.content.Intent.ACTION_SCREEN_OFF);
        lockFilter.addAction(android.content.Intent.ACTION_SCREEN_ON);
        registerReceiver(lockStateReceiver, lockFilter);


        // 建立全套 3 状态响应式现代化新界面
        initPhysicalOrientationListener();
        setupNewUI();
        setupPhoneHomeWeb();
        setupTouchpad();
        setupDisplayMonitoring();


        initShizuku();


        /* Android 13+ 可能走 OnBackInvokedDispatcher，这条回调和 onBackPressed()
           必须落到同一套逻辑。以前这里只处理黑屏和 native 详情页，其余直接退后台，
           配置弹窗开着时按返回键就变成「退到后台」，看起来像返回键失灵。 */
        getOnBackPressedDispatcher().addCallback(this, new androidx.activity.OnBackPressedCallback(true) {
            @Override
            public void handleOnBackPressed() { handleSystemBack(); }
        });


        consumeShortcutIntent(getIntent());
        checkAutoBlackScreen(getIntent());
        handleIncomingPackageIntent(getIntent());
    }


    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        consumeShortcutIntent(intent);
        checkAutoBlackScreen(intent);
        handleIncomingPackageIntent(intent);
    }


    private long lastBlackScreenToastTime = 0L;


    private void checkAutoBlackScreen(Intent intent) {
        if (!isAutoWakeEnabled() || sUserExitedForCurrentPlug || isKeyguardLocked()) {
            // 未开启自动唤醒、用户已手动退出、或锁屏中（锁屏时投不出 DeX，黑屏无意义）——绝不自动进入黑?
            return;
        }
        if (intent != null && intent.getBooleanExtra("auto_black_screen", false)) {
            intent.removeExtra("auto_black_screen");
            if (!sAutoWakeTriggeredForCurrentPlug) {
                sAutoWakeTriggeredForCurrentPlug = true;
                new Handler(Looper.getMainLooper()).postDelayed(() -> {
                    if (isAutoWakeEnabled() && viewBlackScreenOverlay != null && viewBlackScreenOverlay.getVisibility() != View.VISIBLE
                            && !sUserExitedForCurrentPlug) {
                        enterBlackScreenMode();
                    }
                }, 300);
            }
        }
    }

    private String pendingIncomingFilesJson = null;

    private void handleIncomingPackageIntent(Intent intent) {
        if (intent == null) return;
        String action = intent.getAction();
        if (!Intent.ACTION_VIEW.equals(action) && !Intent.ACTION_SEND.equals(action) && !Intent.ACTION_SEND_MULTIPLE.equals(action)) {
            return;
        }
        final List<Uri> uris = new java.util.ArrayList<>();
        if (intent.getData() != null) uris.add(intent.getData());
        if (intent.getParcelableExtra(Intent.EXTRA_STREAM) instanceof Uri) {
            uris.add((Uri) intent.getParcelableExtra(Intent.EXTRA_STREAM));
        }
        java.util.ArrayList<Uri> list = intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM);
        if (list != null) uris.addAll(list);
        if (intent.getClipData() != null) {
            for (int i = 0; i < intent.getClipData().getItemCount(); i++) {
                Uri u = intent.getClipData().getItemAt(i).getUri();
                if (u != null && !uris.contains(u)) uris.add(u);
            }
        }
        if (uris.isEmpty()) return;

        new Thread(() -> {
            try {
                final org.json.JSONArray arr = new org.json.JSONArray();
                for (Uri u : uris) {
                    String name = getDisplayName(u);
                    String lower = (name == null) ? "" : name.toLowerCase();
                    if (lower.endsWith(".apk")) {
                        Log.i(TAG, "忽略外部 APK 文件: " + name);
                        continue;
                    }
                    if (!lower.endsWith(".沧海软件") && !lower.endsWith(".canghai")
                            && !lower.endsWith(".html") && !lower.endsWith(".htm") && !lower.endsWith(".zip")) {
                        continue;
                    }
                    try (java.io.InputStream in = getContentResolver().openInputStream(u);
                         java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream()) {
                        if (in == null) continue;
                        byte[] buf = new byte[32768];
                        int n;
                        while ((n = in.read(buf)) > 0) {
                            bos.write(buf, 0, n);
                        }
                        org.json.JSONObject item = new org.json.JSONObject();
                        item.put("name", name);
                        item.put("uri", u.toString());
                        item.put("b64", android.util.Base64.encodeToString(bos.toByteArray(), android.util.Base64.NO_WRAP));
                        arr.put(item);
                    } catch (Throwable t) {
                        Log.w(TAG, "读取外部软件包失败: " + u, t);
                    }
                }
                if (arr.length() > 0) {
                    final String json = arr.toString();
                    runOnUiThread(() -> {
                        pendingIncomingFilesJson = json;
                        if (webPhoneHome != null) {
                            webPhoneHome.evaluateJavascript(
                                    "window.__canghaiHandleIncomingFiles && window.__canghaiHandleIncomingFiles("
                                            + json + ");", null);
                        }
                    });
                }
            } catch (Throwable t) {
                Log.w(TAG, "handleIncomingPackageIntent error", t);
            }
        }, "ch-pkg-receiver").start();
    }

    private String getDisplayName(Uri uri) {
        if (uri == null) return "package.zip";
        String displayName = null;
        if ("content".equals(uri.getScheme())) {
            try (android.database.Cursor cursor = getContentResolver().query(
                    uri, new String[]{android.provider.OpenableColumns.DISPLAY_NAME}, null, null, null)) {
                if (cursor != null && cursor.moveToFirst()) {
                    int idx = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME);
                    if (idx >= 0) displayName = cursor.getString(idx);
                }
            } catch (Throwable ignored) {}
        }
        if (displayName == null || displayName.isEmpty()) {
            displayName = uri.getLastPathSegment();
        }
        return (displayName == null || displayName.isEmpty()) ? "package.zip" : displayName;
    }


    private void initShizuku() {
        try {
            Shizuku.addBinderReceivedListenerSticky(binderReceivedListener);
            Shizuku.addBinderDeadListener(binderDeadListener);
            // 无条件注册：纯 Root 用户启动时 Shizuku 服务可能没跑，之后点「一键授权」也要能收到结果
            Shizuku.addRequestPermissionResultListener((requestCode, grantResult) -> {
                if (grantResult == PackageManager.PERMISSION_GRANTED) {
                    ShizukuCmd.init();
                    grantAppListPermissionSilently();
                    runOnUiThread(() -> {
                        updateUiState();
                        Toast.makeText(MainActivity.this, R.string.toast_shizuku_granted, Toast.LENGTH_SHORT).show();
                    });
                } else {
                    runOnUiThread(this::updateUiState);
                }
            });
            if (Shizuku.isPreV11() || !Shizuku.pingBinder()) {
                updateUiState();
            } else if (Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED) {
                ShizukuCmd.init();
                grantAppListPermissionSilently();
                updateUiState();
            } else {
                updateUiState();
            }
        } catch (Throwable t) {
            Log.e(TAG, "初始?Shizuku 失败: " + t.getMessage(), t);
            updateUiState();
        }
    }


    private void grantAppListPermissionSilently() {
        new Thread(() -> {
            try {
                if (isShizukuReady()) {
                    ShizukuCmd.execute("appops set " + getPackageName() + " GET_INSTALLED_APPS allow");
                    ShizukuCmd.execute("appops set " + getPackageName() + " READ_INSTALLED_PACKAGES allow");
                    ShizukuCmd.execute("pm grant " + getPackageName() + " android.permission.QUERY_ALL_PACKAGES");
                }
                android.content.pm.LauncherApps launcherApps = (android.content.pm.LauncherApps)
                        getSystemService(Context.LAUNCHER_APPS_SERVICE);
                if (launcherApps != null) {
                    launcherApps.getActivityList(null, android.os.Process.myUserHandle());
                }
            } catch (Throwable ignored) {}
        }).start();
    }


    // ==================== 响应式三步流转核心逻辑 ====================


    public boolean isShizukuReady() {
        if (RootCmd.isAvailable()) return true;
        try {
            if (Shizuku.isPreV11() || !Shizuku.pingBinder()) return false;
            return Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED;
        } catch (Throwable ignored) {
            return false;
        }
    }

    /** 真实的 Shizuku 授权状态（纯 Root 不算）——外屏鼠标/键盘路由必需 */
    public boolean isShizukuGranted() {
        return ShizukuCmd.isInputBindingAvailable();
    }


    public boolean hasConnectableWiredDisplay() {
        return ScreenRegistry.get().hasWired();
    }


    public boolean isMoonlightActive() {
        try {
            com.alkaidlab.sunshine.service.SunshineRuntimeManager rt =
                    com.alkaidlab.sunshine.service.SunshineRuntimeManager.getInstance();
            if (rt.isStreaming().getValue()) return true;
            List<com.alkaidlab.sunshine.data.RuntimeSession> sessions = rt.getActiveSessionsFlow().getValue();
            if (sessions != null && !sessions.isEmpty()) return true;
        } catch (Throwable ignored) {}
        return AosenMoonlightService.isHostRunning();
    }


    public boolean isAnyDisplayConnected() {
        return hasConnectableWiredDisplay() || isMoonlightActive();
    }


    private void updateUiState() {
        syncToPhoneWeb();
        // Root 和 Shizuku 是两条并行通道，任一可用就算就绪。
        // 这里原来只判 isShizukuReady()，有 Root 的机器会被误判成「没准备好」，
        // 于是那层 Shizuku 引导页一直被设成 VISIBLE（视觉上被 WebView 盖住，但仍在 View 树里）。
        final boolean rootOk = RootCmd.isAvailable();
        runOnUiThread(() -> {
            boolean privilegeReady = isShizukuReady() || rootOk;
            if (!privilegeReady) {
                // 两条通道都没有：提示去授权（Root 或 Shizuku 任一）
                if (layoutStateShizuku != null) layoutStateShizuku.setVisibility(View.VISIBLE);
                if (layoutStateWaiting != null) layoutStateWaiting.setVisibility(View.GONE);
                if (layoutStateConnected != null) layoutStateConnected.setVisibility(View.GONE);
                return;
            }


            boolean connected = isAnyDisplayConnected();
            if (!connected) {
                // 第二步：等待设备连接（有线或 Moonlight?
                if (layoutStateShizuku != null) layoutStateShizuku.setVisibility(View.GONE);
                if (layoutStateWaiting != null) layoutStateWaiting.setVisibility(View.VISIBLE);
                if (layoutStateConnected != null) layoutStateConnected.setVisibility(View.GONE);
                refreshWaitingStateUI();
            } else {
                // 第三步：已连接，展示屏幕画布工作?
                if (layoutStateShizuku != null) layoutStateShizuku.setVisibility(View.GONE);
                if (layoutStateWaiting != null) layoutStateWaiting.setVisibility(View.GONE);
                if (layoutStateConnected != null) layoutStateConnected.setVisibility(View.VISIBLE);
                refreshConnectedStateUI();
            }
        });
    }


    private void refreshWaitingStateUI() {
        if (tvMoonlightIp != null) {
            tvMoonlightIp.setText(getLocalIpAddress());
        }
        if (cbMoonlightWaiting != null) {
            cbMoonlightWaiting.setChecked(AosenMoonlightService.isHostRunning());
        }
    }


    private String getExternalDisplayTitle() {
        for (ScreenRegistry.Screen s : ScreenRegistry.get().all()) {
            if (s.isWired()) return s.name;
        }
        return "外接显示设备";
    }


    public void activateDisplay(int displayId) {
        activeDisplayId = displayId;
        if (displayId == 0) {
            // 激活手机主屏：由统一门面解除路由，Root / Shizuku 各走自己的解绑实现
            isMouseOnPhoneScreen = true;
            PrivilegeRoute.unbindExternalInput(this);
            ShizukuCmd.restoreSystemPointerSprite(this);
            if (dexPresentation != null) {
                dexPresentation.setCursorFocusScreen(false, "手机主屏");
            }
            Toast.makeText(this, "🎯 已激活：手机主屏 (鼠标 + 外设)", Toast.LENGTH_SHORT).show();
        } else {
            // 激活外接屏幕：绑定动作统一走门面，后台执行避免 su / binder 调用卡 UI
            isMouseOnPhoneScreen = false;
            // 只能绑物理外接屏：虚拟屏的 uniqueId 不在 InputDispatcher 的 viewport 列表里，
            // 绑过去系统会回落默认屏 0，鼠标就跑回手机。DeXPresentation 挂在物理屏上，
            // 由它把点击转成 finger 注入虚拟屏。
            final int targetInputDisplay = displayId;
            final com.aosen.shizukudex.PrivilegeRoute.Mode route = PrivilegeRoute.mode();
            new Thread(() -> {
                boolean bound = PrivilegeRoute.bindExternalInput(getApplicationContext(), targetInputDisplay);
                Log.i(TAG, "外接输入路由绑定结果=" + bound + " 方式=" + route);
                // 绑定完成后再提示，且按真实结果说话，不再弹「已激活」的假成功
                runOnUiThread(() -> {
                    String msg;
                    if (bound) {
                        msg = "🎯 外屏输入已绑定 (鼠标 + 键盘) · "
                                + (route == PrivilegeRoute.Mode.ROOT ? "Root 方式" : "Shizuku 方式");
                    } else if (route == PrivilegeRoute.Mode.NONE) {
                        msg = "🎯 沧海外屏直显与外设总线已就绪 (免 Root 模式)";
                    } else {
                        msg = "⚠️ 外屏输入绑定未生效，请点「重新绑定外屏输入」重试";
                    }
                    if (bound && dexPresentation != null) {
                        // 绑定成功后立刻重喷透明指针，避免出现「系统黑箭头 + 自绘白指针」双指针
                        dexPresentation.nudgeSystemPointerHidden();
                    }
                    Toast.makeText(MainActivity.this, msg, Toast.LENGTH_LONG).show();
                    refreshConnectedStateUI();
                    syncToPhoneWeb();
                });
            }, "bind-external-input").start();
            com.alkaidlab.sunshine.service.RemoteCursorOverlay.INSTANCE.destroy();
            if (dexPresentation != null) {
                // 保持光标原位，绝不强制扯回中?
                dexPresentation.setCursorFocusScreen(true, null);
            }
        }
        refreshConnectedStateUI();
        syncToPhoneWeb();
    }


    /** 虚拟屏就绪回调：只刷新 UI，不再改绑输入目标（绑虚拟屏会让鼠标回落手机主屏）。 */
    public void onVirtualDisplayCreated(int vdId) {
        runOnUiThread(() -> {
            if (activeDisplayId > 0) {
                // 物理屏绑定已在 activateDisplay 完成；这里重复绑一次同一目标做自愈，但绝不传 vdId
                final int physicalDisplayId = activeDisplayId;
                new Thread(() -> PrivilegeRoute.bindExternalInput(getApplicationContext(), physicalDisplayId),
                        "bind-virtual-input").start();
                refreshConnectedStateUI();
            }
        });
    }


    /** 设置时，对应目标屏幕的鼠标立即激活并显示5?*/
    private void pulseCursorOnTargetDisplay(int displayId) {
        if (displayId <= 0) {
            isMouseOnPhoneScreen = true;
            PrivilegeRoute.unbindExternalInput(this);
            ShizukuCmd.restoreSystemPointerSprite(this);
            if (dexPresentation != null) {
                dexPresentation.setCursorFocusScreen(false, "手机主屏");
            }
        } else {
            isMouseOnPhoneScreen = false;
            // 同 activateDisplay：始终绑物理屏，绝不绑虚拟屏，否则鼠标会回落到手机主屏
            final int targetInputDisplay = displayId;
            new Thread(() -> PrivilegeRoute.bindExternalInput(getApplicationContext(), targetInputDisplay),
                    "pulse-bind-input").start();
            com.alkaidlab.sunshine.service.RemoteCursorOverlay.INSTANCE.destroy();
            if (dexPresentation != null) {
                // 保持光标原位，绝不强制扯回中?
                dexPresentation.setCursorFocusScreen(true, null);
            }
        }
    }


    private void refreshConnectedStateUI() {
        // 1. 📱 手机主屏卡片
        if (tvPhoneResRatio != null) {
            DisplayMetrics dm = getResources().getDisplayMetrics();
            int pw = dm.widthPixels;
            int ph = dm.heightPixels;
            tvPhoneResRatio.setText(pw + "×" + ph + " · " + getAspectRatioString(pw, ph) + " · Display #0");
        }


        boolean isPhoneActive = (activeDisplayId == 0);
        if (tvPhoneActiveBadge != null) {
            tvPhoneActiveBadge.setText(isPhoneActive ? "当前活跃" : "未活跃");
            tvPhoneActiveBadge.setTextColor(Color.parseColor(isPhoneActive ? "#10B981" : "#94A3B8"));
        }
        if (btnPhoneActivate != null) {
            btnPhoneActivate.setText(isPhoneActive ? "已激活" : "激活");
            btnPhoneActivate.setBackgroundResource(isPhoneActive ? R.drawable.bg_oneui_control_btn : R.drawable.bg_btn_primary);
        }
        if (itemPhoneScreen != null) {
            itemPhoneScreen.setBackgroundResource(isPhoneActive ? R.drawable.bg_screen_card_active : R.drawable.bg_screen_card);
        }


        // 2. 🖥?外接有线显示器卡片（存在物理外屏时展示）
        boolean hasWired = hasConnectableWiredDisplay();
        if (itemExternalScreen != null) {
            itemExternalScreen.setVisibility(hasWired ? View.VISIBLE : View.GONE);
        }
        if (hasWired) {
            if (tvExtResRatio != null) {
                tvExtResRatio.setText(externalWidth + "×" + externalHeight + " · " + getAspectRatioString(externalWidth, externalHeight));
            }
            if (tvExtCardTitle != null) {
                tvExtCardTitle.setText(getExternalDisplayTitle());
            }


            boolean isExtActive = (activeDisplayId != 0);
            if (btnExtActivate != null) {
                btnExtActivate.setVisibility(View.VISIBLE);
                btnExtActivate.setText(isExtActive ? "已激活" : "激活");
                btnExtActivate.setBackgroundResource(isExtActive ? R.drawable.bg_oneui_control_btn : R.drawable.bg_btn_primary);
            }
            if (btnExtBlackTouchpad != null) {
                btnExtBlackTouchpad.setVisibility(View.VISIBLE);
            }
            if (tvExtActiveBadge != null) {
                tvExtActiveBadge.setText(isExtActive ? "当前活跃" : "未活跃");
                tvExtActiveBadge.setTextColor(Color.parseColor(isExtActive ? "#10B981" : "#94A3B8"));
            }
            if (itemExternalScreen != null) {
                itemExternalScreen.setBackgroundResource(isExtActive ? R.drawable.bg_screen_card_active : R.drawable.bg_screen_card);
            }
        }


        // 3. 📶 Moonlight 无线串流设备卡片（支持与有线外接屏同时并存展示！?
        if (itemMoonlightScreen != null) {
            itemMoonlightScreen.setVisibility(View.VISIBLE);
            boolean isStreaming = false;
            String clientName = null;
            try {
                com.alkaidlab.sunshine.service.SunshineRuntimeManager rt =
                        com.alkaidlab.sunshine.service.SunshineRuntimeManager.getInstance();
                List<com.alkaidlab.sunshine.data.RuntimeSession> sessions = rt.getActiveSessionsFlow().getValue();
                if (sessions != null && !sessions.isEmpty()) {
                    isStreaming = true;
                    clientName = sessions.get(0).getClientName();
                }
            } catch (Throwable ignored) {}


            boolean isHostRunning = AosenMoonlightService.isHostRunning();
            if (isStreaming) {
                if (tvMoonlightCardBadge != null) {
                    tvMoonlightCardBadge.setText("?串流?");
                    tvMoonlightCardBadge.setTextColor(Color.parseColor("#10B981"));
                }
                if (tvMoonlightCardSub != null) {
                tvMoonlightCardSub.setText("已连接: " + (clientName != null ? clientName : "客户端"));
                }
                if (btnMoonlightCardToggle != null) {
                    btnMoonlightCardToggle.setText("🛑 停止主机");
                    btnMoonlightCardToggle.setBackgroundResource(R.drawable.bg_oneui_control_btn);
                }
            } else if (isHostRunning) {
                if (tvMoonlightCardBadge != null) {
                    tvMoonlightCardBadge.setText("?运行?");
                    tvMoonlightCardBadge.setTextColor(Color.parseColor("#38BDF8"));
                }
                if (tvMoonlightCardSub != null) {
                    tvMoonlightCardSub.setText(getLocalIpAddress() + " · 等待连接");
                }
                if (btnMoonlightCardToggle != null) {
                    btnMoonlightCardToggle.setText("🛑 停止主机");
                    btnMoonlightCardToggle.setBackgroundResource(R.drawable.bg_oneui_control_btn);
                }
            } else {
                if (tvMoonlightCardBadge != null) {
                    tvMoonlightCardBadge.setText("?未开?");
                    tvMoonlightCardBadge.setTextColor(Color.parseColor("#94A3B8"));
                }
                if (tvMoonlightCardSub != null) {
                    tvMoonlightCardSub.setText("未开启无线串流主?");
                }
                if (btnMoonlightCardToggle != null) {
                    btnMoonlightCardToggle.setText("?开启主?");
                    btnMoonlightCardToggle.setBackgroundResource(R.drawable.bg_btn_primary);
                }
            }
        }


        updateHomeUI();
    }


    private void openScreenDetailPage(int displayId) {
        currentDetailDisplayId = displayId;
        if (layoutStateConnected != null) layoutStateConnected.setVisibility(View.GONE);
        if (layoutScreenDetailPage != null) layoutScreenDetailPage.setVisibility(View.VISIBLE);


        if (displayId == 0) {
            if (tvDetailTitle != null) tvDetailTitle.setText("📱 本机屏幕 · 屏幕属?");
            if (cardDetailScale != null) cardDetailScale.setVisibility(View.GONE);
            if (cardDetailStyle != null) cardDetailStyle.setVisibility(View.GONE);
            if (tvDetailDisplayInfo != null) {
                DisplayMetrics dm = getResources().getDisplayMetrics();
                Display defaultDisplay = (displayManager != null) ? displayManager.getDisplay(Display.DEFAULT_DISPLAY) : null;
                float refreshRate = (defaultDisplay != null) ? defaultDisplay.getRefreshRate() : 60f;
                tvDetailDisplayInfo.setText("设备名称: 手机本机屏幕 (Display #0)\n"
                        + "物理分辨? " + dm.widthPixels + "×" + dm.heightPixels + "\n"
                        + "屏幕宽高? " + getAspectRatioString(dm.widthPixels, dm.heightPixels) + "\n"
                        + "屏幕刷新? " + Math.round(refreshRate) + " Hz\n"
                        + "屏幕像素密度: " + dm.densityDpi + " DPI (" + String.format(java.util.Locale.US, "%.1fx", dm.density) + ")\n"
                        + "控制模式: 默认由系统触?鼠标原生控制");
            }
        } else {
            if (tvDetailTitle != null) tvDetailTitle.setText("🖥?" + getExternalDisplayTitle() + " · 专属设置");
            if (cardDetailScale != null) cardDetailScale.setVisibility(View.VISIBLE);
            if (cardDetailStyle != null) cardDetailStyle.setVisibility(View.VISIBLE);
            if (tvDetailDisplayInfo != null) {
                Display d = (displayManager != null) ? displayManager.getDisplay(displayId) : null;
                float refreshRate = (d != null) ? d.getRefreshRate() : 60f;
                tvDetailDisplayInfo.setText("设备名称: " + getExternalDisplayTitle() + " (Display #" + displayId + ")\n"
                        + "物理分辨? " + externalWidth + "×" + externalHeight + "\n"
                        + "屏幕宽高? " + getAspectRatioString(externalWidth, externalHeight) + "\n"
                        + "屏幕刷新? " + Math.round(refreshRate) + " Hz\n"
                        + "显示缩放? " + currentDpi + " DPI\n"
                        + "控制模式: 独立软件光标 (可自定义大小与样?");
            }


            // 初始化光标大小数值与滑动?
            float curScale = com.alkaidlab.sunshine.service.MouseCursorPrefs.getScale();
            int progress = Math.round((curScale - 0.4f) / 0.031f);
            if (sbDetailScale != null) {
                sbDetailScale.setProgress(Math.max(0, Math.min(100, progress)));
            }
            if (tvDetailScalePercent != null) {
                tvDetailScalePercent.setText(Math.round(curScale * 100) + "%");
            }


            updateDetailStyleButtonsUI(com.alkaidlab.sunshine.service.MouseCursorPrefs.getStyle());


            if (detailPreviewCursor != null) {
                detailPreviewCursor.refresh();
            }


            // 打开外接屏专属设置页面时，目标屏幕上的鼠标立即激活并显示?
            pulseCursorOnTargetDisplay(displayId);
        }
    }


    private void updateDetailStyleButtonsUI(int style) {
        int activeBg = R.drawable.bg_btn_primary;
        int normalBg = R.drawable.bg_oneui_control_btn;
        int activeColor = Color.WHITE;
        int normalColor = Color.parseColor("#CBD5E1");


        if (btnDetailStyleArrow != null) {
            btnDetailStyleArrow.setBackgroundResource(style == com.alkaidlab.sunshine.service.MouseCursorPrefs.STYLE_ARROW ? activeBg : normalBg);
            btnDetailStyleArrow.setTextColor(style == com.alkaidlab.sunshine.service.MouseCursorPrefs.STYLE_ARROW ? activeColor : normalColor);
        }
        if (btnDetailStyleDot != null) {
            btnDetailStyleDot.setBackgroundResource(style == com.alkaidlab.sunshine.service.MouseCursorPrefs.STYLE_DOT ? activeBg : normalBg);
            btnDetailStyleDot.setTextColor(style == com.alkaidlab.sunshine.service.MouseCursorPrefs.STYLE_DOT ? activeColor : normalColor);
        }
        if (btnDetailStyleBlock != null) {
            btnDetailStyleBlock.setBackgroundResource(style == com.alkaidlab.sunshine.service.MouseCursorPrefs.STYLE_BLOCK ? activeBg : normalBg);
            btnDetailStyleBlock.setTextColor(style == com.alkaidlab.sunshine.service.MouseCursorPrefs.STYLE_BLOCK ? activeColor : normalColor);
        }
        if (btnDetailStyleSvg != null) {
            btnDetailStyleSvg.setBackgroundResource(style == com.alkaidlab.sunshine.service.MouseCursorPrefs.STYLE_CUSTOM_SVG ? activeBg : normalBg);
            btnDetailStyleSvg.setTextColor(style == com.alkaidlab.sunshine.service.MouseCursorPrefs.STYLE_CUSTOM_SVG ? activeColor : normalColor);
        }


        if (llDetailSvgContainer != null) {
            llDetailSvgContainer.setVisibility(style == com.alkaidlab.sunshine.service.MouseCursorPrefs.STYLE_CUSTOM_SVG ? View.VISIBLE : View.GONE);
        }
    }


    private void setupNewUI() {
        layoutStateShizuku = findViewById(R.id.layoutStateShizuku);
        layoutStateWaiting = findViewById(R.id.layoutStateWaiting);
        layoutStateConnected = findViewById(R.id.layoutStateConnected);
        layoutScreenDetailPage = findViewById(R.id.layoutScreenDetailPage);


        // State 1: Shizuku / Root
        tvShizukuBigTitle = findViewById(R.id.tvShizukuBigTitle);
        btnRequestRoot = findViewById(R.id.btnRequestRoot);
        btnRequestShizuku = findViewById(R.id.btnRequestShizuku);
        btnOpenShizukuApp = findViewById(R.id.btnOpenShizukuApp);


        if (btnRequestRoot != null) {
            // 必须强制重探：旧实现只读缓存，且三个分支都弹「已获取」，用户手动授权后永远无法恢复
            btnRequestRoot.setOnClickListener(v -> refreshRootPermission(true));
        }


        if (btnRequestShizuku != null) {
            btnRequestShizuku.setOnClickListener(v -> {
                try {
                    Shizuku.requestPermission(1001);
                } catch (Throwable t) {
                    Toast.makeText(this, "申请授权失败: " + t.getMessage(), Toast.LENGTH_SHORT).show();
                }
            });
        }
        if (btnOpenShizukuApp != null) {
            btnOpenShizukuApp.setOnClickListener(v -> {
                try {
                    Intent intent = getPackageManager().getLaunchIntentForPackage("moe.shizuku.privileged.api");
                    if (intent != null) {
                        startActivity(intent);
                    } else {
                        Toast.makeText(this, "未检测到 Shizuku，请先下载安?Shizuku", Toast.LENGTH_SHORT).show();
                    }
                } catch (Throwable t) {
                    Toast.makeText(this, "打开 Shizuku 失败: " + t.getMessage(), Toast.LENGTH_SHORT).show();
                }
            });
        }


        // State 2: 等待连接
        cbMoonlightWaiting = findViewById(R.id.cbMoonlightWaiting);
        tvMoonlightIp = findViewById(R.id.tvMoonlightIp);
        btnCopyIp = findViewById(R.id.btnCopyIp);


        if (tvMoonlightIp != null) {
            tvMoonlightIp.setText(getLocalIpAddress());
        }
        if (btnCopyIp != null) {
            btnCopyIp.setOnClickListener(v -> {
                String ip = tvMoonlightIp != null ? tvMoonlightIp.getText().toString() : getLocalIpAddress();
                ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
                if (cm != null) {
                    cm.setPrimaryClip(ClipData.newPlainText("Moonlight IP", ip));
                    Toast.makeText(this, "已复?IP: " + ip, Toast.LENGTH_SHORT).show();
                }
            });
        }
        if (cbMoonlightWaiting != null) {
            cbMoonlightWaiting.setChecked(AosenMoonlightService.isHostRunning());
            cbMoonlightWaiting.setOnClickListener(v -> {
                boolean turnOn = !AosenMoonlightService.isHostRunning();
                cbMoonlightWaiting.setChecked(turnOn);
                if (turnOn) {
                    AosenMoonlightService.start(MainActivity.this, null);
                    Toast.makeText(this, "Moonlight 已开启", Toast.LENGTH_SHORT).show();
                } else {
                    AosenMoonlightService.stop(MainActivity.this);
                    Toast.makeText(this, "Moonlight 已关闭", Toast.LENGTH_SHORT).show();
                }
                updateUiState();
            });
        }


        // State 3: 垂直屏幕列表工作?
        itemPhoneScreen = findViewById(R.id.itemPhoneScreen);
        tvPhoneResRatio = findViewById(R.id.tvPhoneResRatio);
        tvPhoneActiveBadge = findViewById(R.id.tvPhoneActiveBadge);
        btnPhoneActivate = findViewById(R.id.btnPhoneActivate);
        btnPhoneMore = findViewById(R.id.btnPhoneMore);


        itemExternalScreen = findViewById(R.id.itemExternalScreen);
        tvExtCardTitle = findViewById(R.id.tvExtCardTitle);
        tvExtResRatio = findViewById(R.id.tvExtResRatio);
        tvExtActiveBadge = findViewById(R.id.tvExtActiveBadge);
        btnExtActivate = findViewById(R.id.btnExtActivate);
        btnExtBlackTouchpad = findViewById(R.id.btnExtBlackTouchpad);
        btnExtMore = findViewById(R.id.btnExtMore);


        btnExtHomeOfficial = findViewById(R.id.btnExtHomeOfficial);
        btnExtHomeAosen = findViewById(R.id.btnExtHomeAosen);


        View.OnClickListener officialClickListener = v -> setDesktopHome(HOME_OFFICIAL);
        View.OnClickListener aosenClickListener = v -> setDesktopHome(HOME_AOSEN);


        if (btnExtHomeOfficial != null) btnExtHomeOfficial.setOnClickListener(officialClickListener);
        if (btnExtHomeAosen != null) btnExtHomeAosen.setOnClickListener(aosenClickListener);


        updateHomeUI();


        if (btnPhoneActivate != null) {
            btnPhoneActivate.setOnClickListener(v -> activateDisplay(0));
        }
        if (btnPhoneMore != null) {
            btnPhoneMore.setOnClickListener(v -> openScreenDetailPage(0));
        }


        if (btnExtActivate != null) {
            btnExtActivate.setOnClickListener(v -> {
                int target = (externalDisplayId > 0) ? externalDisplayId : 2;
                activateDisplay(target);
            });
        }
        if (btnExtBlackTouchpad != null) {
            btnExtBlackTouchpad.setOnClickListener(v -> enterBlackScreenMode());
        }
        if (btnExtMore != null) {
            btnExtMore.setOnClickListener(v -> {
                int target = (externalDisplayId > 0) ? externalDisplayId : 2;
                openScreenDetailPage(target);
            });
        }


        // Moonlight 屏幕卡片控件绑定
        itemMoonlightScreen = findViewById(R.id.itemMoonlightScreen);
        tvMoonlightCardTitle = findViewById(R.id.tvMoonlightCardTitle);
        tvMoonlightCardSub = findViewById(R.id.tvMoonlightCardSub);
        tvMoonlightCardBadge = findViewById(R.id.tvMoonlightCardBadge);
        btnMoonlightCardToggle = findViewById(R.id.btnMoonlightCardToggle);
        btnMoonlightCardCopyIp = findViewById(R.id.btnMoonlightCardCopyIp);


        if (btnMoonlightCardToggle != null) {
            btnMoonlightCardToggle.setOnClickListener(v -> {
                boolean turnOn = !AosenMoonlightService.isHostRunning();
                if (turnOn) {
                    AosenMoonlightService.start(MainActivity.this, null);
                    Toast.makeText(this, "Moonlight 已开启", Toast.LENGTH_SHORT).show();
                } else {
                    AosenMoonlightService.stop(MainActivity.this);
                    Toast.makeText(this, "Moonlight 已关闭", Toast.LENGTH_SHORT).show();
                }
                updateUiState();
            });
        }
        if (btnMoonlightCardCopyIp != null) {
            btnMoonlightCardCopyIp.setOnClickListener(v -> {
                String ip = getLocalIpAddress();
                ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
                if (cm != null) {
                    cm.setPrimaryClip(ClipData.newPlainText("Moonlight IP", ip));
                    Toast.makeText(this, "已复?IP: " + ip, Toast.LENGTH_SHORT).show();
                }
            });
        }


        // 专属设置页面绑定
        cardDetailScale = findViewById(R.id.cardDetailScale);
        cardDetailStyle = findViewById(R.id.cardDetailStyle);
        btnDetailBack = findViewById(R.id.btnDetailBack);
        tvDetailTitle = findViewById(R.id.tvDetailTitle);
        tvDetailDisplayInfo = findViewById(R.id.tvDetailDisplayInfo);
        tvDetailScalePercent = findViewById(R.id.tvDetailScalePercent);
        btnDetailScaleMinus = findViewById(R.id.btnDetailScaleMinus);
        sbDetailScale = findViewById(R.id.sbDetailScale);
        btnDetailScalePlus = findViewById(R.id.btnDetailScalePlus);
        flDetailPreviewContainer = findViewById(R.id.flDetailPreviewContainer);


        btnDetailStyleArrow = findViewById(R.id.btnDetailStyleArrow);
        btnDetailStyleDot = findViewById(R.id.btnDetailStyleDot);
        btnDetailStyleBlock = findViewById(R.id.btnDetailStyleBlock);
        btnDetailStyleSvg = findViewById(R.id.btnDetailStyleSvg);
        llDetailSvgContainer = findViewById(R.id.llDetailSvgContainer);
        etDetailCustomSvg = findViewById(R.id.etDetailCustomSvg);
        btnDetailSvgSample1 = findViewById(R.id.btnDetailSvgSample1);
        btnDetailSvgSample2 = findViewById(R.id.btnDetailSvgSample2);
        btnDetailSvgSample3 = findViewById(R.id.btnDetailSvgSample3);
        btnDetailApplySvg = findViewById(R.id.btnDetailApplySvg);


        if (btnDetailBack != null) {
            btnDetailBack.setOnClickListener(v -> {
                if (layoutScreenDetailPage != null) layoutScreenDetailPage.setVisibility(View.GONE);
                if (layoutStateConnected != null) layoutStateConnected.setVisibility(View.VISIBLE);
                updateUiState();
            });
        }


        if (flDetailPreviewContainer != null) {
            detailPreviewCursor = new com.alkaidlab.sunshine.service.MouseCursorView(this);
            flDetailPreviewContainer.addView(detailPreviewCursor);
        }


        if (sbDetailScale != null) {
            sbDetailScale.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
                @Override
                public void onProgressChanged(SeekBar seekBar, int p, boolean fromUser) {
                    float newScale = 0.4f + p * 0.031f; // 0.4x ~ 3.5x
                    if (tvDetailScalePercent != null) {
                        tvDetailScalePercent.setText(Math.round(newScale * 100) + "%");
                    }
                    com.alkaidlab.sunshine.service.MouseCursorPrefs.setContinuousScale(MainActivity.this, newScale);
                    if (detailPreviewCursor != null) detailPreviewCursor.refresh();
                    if (fromUser) {
                        pulseCursorOnTargetDisplay(currentDetailDisplayId);
                    }
                }
                @Override public void onStartTrackingTouch(SeekBar seekBar) {}
                @Override public void onStopTrackingTouch(SeekBar seekBar) {}
            });
        }


        if (btnDetailScaleMinus != null) {
            btnDetailScaleMinus.setOnClickListener(v -> {
                if (sbDetailScale != null) {
                    sbDetailScale.setProgress(Math.max(0, sbDetailScale.getProgress() - 3));
                    pulseCursorOnTargetDisplay(currentDetailDisplayId);
                }
            });
        }
        if (btnDetailScalePlus != null) {
            btnDetailScalePlus.setOnClickListener(v -> {
                if (sbDetailScale != null) {
                    sbDetailScale.setProgress(Math.min(100, sbDetailScale.getProgress() + 3));
                    pulseCursorOnTargetDisplay(currentDetailDisplayId);
                }
            });
        }


        if (etDetailCustomSvg != null && com.alkaidlab.sunshine.service.MouseCursorPrefs.getCustomSvgPath() != null) {
            etDetailCustomSvg.setText(com.alkaidlab.sunshine.service.MouseCursorPrefs.getCustomSvgPath());
        }


        View.OnClickListener styleClickListener = v -> {
            int id = v.getId();
            int chosenStyle = com.alkaidlab.sunshine.service.MouseCursorPrefs.STYLE_ARROW;
            if (id == R.id.btnDetailStyleArrow) {
                chosenStyle = com.alkaidlab.sunshine.service.MouseCursorPrefs.STYLE_ARROW;
            } else if (id == R.id.btnDetailStyleDot) {
                chosenStyle = com.alkaidlab.sunshine.service.MouseCursorPrefs.STYLE_DOT;
            } else if (id == R.id.btnDetailStyleBlock) {
                chosenStyle = com.alkaidlab.sunshine.service.MouseCursorPrefs.STYLE_BLOCK;
            } else if (id == R.id.btnDetailStyleSvg) {
                chosenStyle = com.alkaidlab.sunshine.service.MouseCursorPrefs.STYLE_CUSTOM_SVG;
            }
            com.alkaidlab.sunshine.service.MouseCursorPrefs.setStyleIndex(this, chosenStyle);
            updateDetailStyleButtonsUI(chosenStyle);
            if (detailPreviewCursor != null) detailPreviewCursor.refresh();
            pulseCursorOnTargetDisplay(currentDetailDisplayId);
        };


        if (btnDetailStyleArrow != null) btnDetailStyleArrow.setOnClickListener(styleClickListener);
        if (btnDetailStyleDot != null) btnDetailStyleDot.setOnClickListener(styleClickListener);
        if (btnDetailStyleBlock != null) btnDetailStyleBlock.setOnClickListener(styleClickListener);
        if (btnDetailStyleSvg != null) btnDetailStyleSvg.setOnClickListener(styleClickListener);


        if (btnDetailSvgSample1 != null) {
            btnDetailSvgSample1.setOnClickListener(v -> {
                if (etDetailCustomSvg != null) etDetailCustomSvg.setText("M-10,0 L10,0 M0,-10 L0,10");
            });
        }
        if (btnDetailSvgSample2 != null) {
            btnDetailSvgSample2.setOnClickListener(v -> {
                if (etDetailCustomSvg != null) etDetailCustomSvg.setText("M0,0 L0,24 L6,18 L10,26 L14,24 L10,16 L18,16 Z");
            });
        }
        if (btnDetailSvgSample3 != null) {
            btnDetailSvgSample3.setOnClickListener(v -> {
                if (etDetailCustomSvg != null) etDetailCustomSvg.setText("M0,0 L15,15 L10,25 L0,20 Z");
            });
        }


        if (btnDetailApplySvg != null) {
            btnDetailApplySvg.setOnClickListener(v -> {
                if (etDetailCustomSvg == null) return;
                String svg = etDetailCustomSvg.getText().toString().trim();
                if (svg.isEmpty()) {
                    Toast.makeText(this, "请输入有效 SVG Path", Toast.LENGTH_SHORT).show();
                    return;
                }
                try {
                    androidx.core.graphics.PathParser.createPathFromPathData(svg);
                    com.alkaidlab.sunshine.service.MouseCursorPrefs.setCustomSvgPath(this, svg);
                    com.alkaidlab.sunshine.service.MouseCursorPrefs.setStyleIndex(this, com.alkaidlab.sunshine.service.MouseCursorPrefs.STYLE_CUSTOM_SVG);
                    updateDetailStyleButtonsUI(com.alkaidlab.sunshine.service.MouseCursorPrefs.STYLE_CUSTOM_SVG);
                    if (detailPreviewCursor != null) detailPreviewCursor.refresh();
                    pulseCursorOnTargetDisplay(currentDetailDisplayId);
                    Toast.makeText(this, "SVG 应用成功", Toast.LENGTH_SHORT).show();
                } catch (Throwable t) {
                    Toast.makeText(this, "SVG Path 解析失败", Toast.LENGTH_SHORT).show();
                }
            });
        }


        // 默认自启 Moonlight 轮询
        moonlightHandler.post(moonlightPoller);
    }


    private boolean hasExternalInputDevices() {
        try {
            InputManager im = (InputManager) getSystemService(Context.INPUT_SERVICE);
            if (im == null) return false;
            for (int id : im.getInputDeviceIds()) {
                InputDevice dev = im.getInputDevice(id);
                if (ShizukuCmd.isExternalInputDevice(dev)) {
                    return true;
                }
            }
        } catch (Throwable ignored) {}
        return false;
    }


    private String getAspectRatioString(int width, int height) {
        if (width <= 0 || height <= 0) return "16:9";
        int gcd = gcd(width, height);
        int rW = width / gcd;
        int rH = height / gcd;
        if (rW > 50 || rH > 50) {
            float ratio = (float) Math.max(width, height) / Math.min(width, height);
            if (Math.abs(ratio - 16f / 9f) < 0.1f) return "16:9";
            if (Math.abs(ratio - 20f / 9f) < 0.1f) return "9:20";
            if (Math.abs(ratio - 22f / 9f) < 0.1f) return "9:22";
            if (Math.abs(ratio - 4f / 3f) < 0.1f) return "4:3";
            return String.format(Locale.getDefault(), "%.1f:1", ratio);
        }
        return rW + ":" + rH;
    }


    private int gcd(int a, int b) {
        while (b != 0) {
            int t = b;
            b = a % b;
            a = t;
        }
        return a;
    }


    public static String getLocalIpAddress() {
        try {
            for (Enumeration<NetworkInterface> en = NetworkInterface.getNetworkInterfaces(); en.hasMoreElements(); ) {
                NetworkInterface intf = en.nextElement();
                if (intf.isLoopback() || !intf.isUp()) continue;
                for (Enumeration<InetAddress> enumIpAddr = intf.getInetAddresses(); enumIpAddr.hasMoreElements(); ) {
                    InetAddress inetAddress = enumIpAddr.nextElement();
                    if (!inetAddress.isLoopbackAddress() && inetAddress instanceof Inet4Address) {
                        return inetAddress.getHostAddress();
                    }
                }
            }
        } catch (Throwable ignored) {}
        return "127.0.0.1";
    }


    private void setupControls() {
    }


    // ==================== Moonlight 投屏（Sunshine 引擎?====================


    private final Handler moonlightHandler = new Handler(Looper.getMainLooper());
    private boolean pinDialogShowing = false;
    /** 用户手动断开的有线外接屏：不再被 detectAndMountExternalDisplay 自动重连 */
    private final Set<Integer> manuallyDisconnectedDisplays = new HashSet<>();


    /** 1 秒轮询：勾选状态、PIN 配对弹窗、状态更新?*/
    private final Runnable moonlightPoller = new Runnable() {
        @Override public void run() {
            try {
                com.alkaidlab.sunshine.service.SunshineRuntimeManager rt =
                        com.alkaidlab.sunshine.service.SunshineRuntimeManager.getInstance();
                boolean hostRunning = AosenMoonlightService.isHostRunning();
                if (cbMoonlightWaiting != null) {
                    cbMoonlightWaiting.setChecked(hostRunning);
                }
                String pin = rt.getPinRequested().getValue();
                if (pin != null) {
                    maybeShowPinDialog(rt, pin);
                }
                updateUiState();
            } catch (Throwable ignored) {}
            moonlightHandler.postDelayed(this, 1000);
        }
    };


    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }


    private void maybeShowPinDialog(com.alkaidlab.sunshine.service.SunshineRuntimeManager rt, String client) {
        if (pinDialogShowing || isFinishing()) return;
        pinDialogShowing = true;
        Log.i(TAG, "触发 Moonlight 配对 PIN 录入请求，通过前端底升 Sheet 模态展示 client=" + client);
        if (webPhoneHome != null) {
            final String js = "window.showMoonlightPinDialog && window.showMoonlightPinDialog("
                    + org.json.JSONObject.quote(client != null ? client : "Moonlight 客户端") + ");";
            webPhoneHome.post(() -> {
                try { webPhoneHome.evaluateJavascript(js, null); } catch (Throwable ignored) {}
            });
        }
    }




    private void showTextInputDialog() {
        LinearLayout container = new LinearLayout(this);
        container.setOrientation(LinearLayout.VERTICAL);
        container.setPadding(48, 36, 48, 16);


        TextView tvTitle = new TextView(this);
        tvTitle.setText(R.string.dialog_input_text_title);
        tvTitle.setTextSize(17f);
        tvTitle.setTextColor(0xFFF4F5F7);
        tvTitle.setTypeface(null, android.graphics.Typeface.BOLD);
        tvTitle.setPadding(0, 0, 0, 20);
        container.addView(tvTitle);


        final EditText input = new EditText(this);
        input.setHint(R.string.dialog_input_text_hint);
        input.setMinLines(3);
        input.setMaxLines(6);
        input.setGravity(Gravity.TOP | Gravity.START);
        input.setBackgroundResource(R.drawable.bg_dialog_input_box);
        input.setTextColor(0xFFFFFFFF);
        input.setHintTextColor(0xFFA0A4AE);
        input.setPadding(32, 24, 32, 24);
        container.addView(input);


        AlertDialog dialog = new AlertDialog.Builder(this)
                .setView(container)
                .setPositiveButton(R.string.btn_send, (d, which) -> {
                    String text = input.getText().toString();
                    if (!text.isEmpty()) {
                        sendTextToDesktop(text);
                    }
                })
                .setNegativeButton(android.R.string.cancel, null)
                .create();


        dialog.show();


        if (dialog.getWindow() != null) {
            dialog.getWindow().setBackgroundDrawableResource(R.drawable.bg_oneui_card);
        }
        if (dialog.getButton(AlertDialog.BUTTON_POSITIVE) != null) {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setTextColor(getColor(R.color.oneui_blue_light));
        }
        if (dialog.getButton(AlertDialog.BUTTON_NEGATIVE) != null) {
            dialog.getButton(AlertDialog.BUTTON_NEGATIVE).setTextColor(getColor(R.color.oneui_text_secondary));
        }


        input.postDelayed(() -> {
            input.requestFocus();
            InputMethodManager imm = (InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
            if (imm != null) {
                imm.showSoftInput(input, InputMethodManager.SHOW_IMPLICIT);
            }
        }, 150);
    }


    public void sendTextToDesktop(String text) {
        if (dexPresentation == null || !dexPresentation.isShowing()) {
            Toast.makeText(this, R.string.status_disconnected, Toast.LENGTH_SHORT).show();
            return;
        }
        int vId = dexPresentation.getVirtualDisplayId();
        if (vId <= 0) return;


        try {
            // 1. 设置系统剪贴板内?
            ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
            if (cm != null) {
                cm.setPrimaryClip(ClipData.newPlainText("text", text));
            }


            // 2. 延迟 200ms 等待弹窗完全关闭、大屏焦点恢复后仅发送一?KEYCODE_PASTE (279) 指令，杜绝触发两?
            new Handler(Looper.getMainLooper()).postDelayed(() -> {
                new Thread(() -> {
                    try {
                        // keyevent 279 = KEYCODE_PASTE (仅发一次，杜绝"你好你好"双份粘贴)
                        ShizukuCmd.execute("input -d " + vId + " keyevent 279");
                    } catch (Throwable ignored) {}
                }).start();
            }, 200);


            Toast.makeText(this, R.string.toast_text_sent, Toast.LENGTH_SHORT).show();
        } catch (Throwable t) {
            Log.w(TAG, "sendTextToDesktop 异常: " + t.getMessage());
        }
    }


    private boolean isAutoWakeEnabled() {
        SharedPreferences sp = getSharedPreferences("aosen_settings", Context.MODE_PRIVATE);
        return sp.getBoolean(PREF_AUTO_WAKE, false);
    }


    private void updateAutoWakeButtonUI() {
        boolean enabled = isAutoWakeEnabled();
        Intent serviceIntent = new Intent(this, AutoWakeService.class);
        if (enabled) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(serviceIntent);
            } else {
                startService(serviceIntent);
            }
        } else {
            stopService(serviceIntent);
        }
    }


    private void toggleAutoWake() {
        boolean current = isAutoWakeEnabled();
        boolean target = !current;


        if (target) {
            // 自动通过 Shizuku 授权 SYSTEM_ALERT_WINDOW (控制屏幕/上层显示)
            new Thread(() -> {
                try {
                    ShizukuCmd.execute("appops set " + getPackageName() + " SYSTEM_ALERT_WINDOW allow");
                } catch (Throwable ignored) {}
            }).start();


            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(this)) {
                try {
                    Intent intent = new Intent(
                            Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                            Uri.parse("package:" + getPackageName())
                    );
                    startActivity(intent);
                } catch (Throwable ignored) {}
            }
            Toast.makeText(this, R.string.auto_wake_granted, Toast.LENGTH_SHORT).show();
        }


        SharedPreferences sp = getSharedPreferences("aosen_settings", Context.MODE_PRIVATE);
        sp.edit().putBoolean(PREF_AUTO_WAKE, target).apply();
        updateAutoWakeButtonUI();
    }


    /** 旧版窗口模式已废弃：沧海/官方桌面均为窗口模式，强制归?5（自由窗口） */
    private void normalizeWindowMode() {
        SharedPreferences sp = getSharedPreferences("aosen_settings", Context.MODE_PRIVATE);
        if (sp.getInt(PREF_WINDOW_MODE, 5) != 5) {
            sp.edit().putInt(PREF_WINDOW_MODE, 5).apply();
        }
    }


    private int getDesktopHome() {
        SharedPreferences sp = getSharedPreferences("aosen_settings", Context.MODE_PRIVATE);
        return sp.getInt(PREF_HOME_TYPE, HOME_OFFICIAL); // 默认官方桌面
    }


    private boolean isAppListAvailable() {
        try {
            android.content.pm.LauncherApps launcherApps = (android.content.pm.LauncherApps)
                    getSystemService(Context.LAUNCHER_APPS_SERVICE);
            if (launcherApps != null) {
                java.util.List<android.content.pm.LauncherActivityInfo> list =
                        launcherApps.getActivityList(null, android.os.Process.myUserHandle());
                return list != null && !list.isEmpty();
            }
        } catch (Throwable ignored) {}
        return false;
    }


    private void setDesktopHome(int home) {
        if (home == HOME_AOSEN) {
            if (!isAppListAvailable()) {
                    Toast.makeText(this, "未能获取应用列表权限", Toast.LENGTH_LONG).show();
                new Thread(() -> {
                    boolean granted = false;
                    for (int i = 0; i < 16; i++) {
                        try {
                            android.content.pm.LauncherApps launcherApps = (android.content.pm.LauncherApps)
                                    getSystemService(Context.LAUNCHER_APPS_SERVICE);
                            if (launcherApps != null) {
                                java.util.List<android.content.pm.LauncherActivityInfo> list =
                                        launcherApps.getActivityList(null, android.os.Process.myUserHandle());
                                if (list != null && !list.isEmpty()) {
                                    granted = true;
                                    break;
                                }
                            }
                        } catch (Throwable ignored) {}
                        try {
                            Thread.sleep(500);
                        } catch (InterruptedException ignored) {
                            break;
                        }
                    }
                    final boolean success = granted;
                    runOnUiThread(() -> {
                        if (success) {
                            applyDesktopHome(HOME_AOSEN);
                        } else {
                            updateHomeUI();
                    Toast.makeText(MainActivity.this, "未能获取应用列表权限", Toast.LENGTH_LONG).show();
                        }
                    });
                }).start();
                return;
            }
        }
        applyDesktopHome(home);
    }


    private void applyDesktopHome(int home) {
        SharedPreferences sp = getSharedPreferences("aosen_settings", Context.MODE_PRIVATE);
        sp.edit().putInt(PREF_HOME_TYPE, home).apply();
        // 两种桌面均为窗口模式（官方桌?= 窗口模式；沧海桌面应用也按窗口打开?
        sp.edit().putInt(PREF_WINDOW_MODE, 5).apply();
        updateHomeUI();
        Toast.makeText(this,
                home == HOME_AOSEN ? R.string.toast_home_aosen : R.string.toast_home_official,
                Toast.LENGTH_SHORT).show();


        // 系统装饰?12）两种桌面都需要：官方桌面的任务栏、沧海桌面的自由窗口标题?
        ShizukuCmd.setSystemDecorationsEnabled(true);
        if (home == HOME_OFFICIAL) {
            try {
                DexHomeActivity.finishAllForOfficialDesktop();
            } catch (Throwable ignored) {}
        }
        if (dexPresentation != null) {
            dexPresentation.setWindowMode(5);
            dexPresentation.recreateVirtualDisplayWithDpi(currentDpi);
        }
        scheduleRefocusPhoneDisplay();
        // 串流（Moonlight / 纯 Root）通道：会话进行中切换桌面必须立刻热切换。
        // 旧实现只写了 SharedPreferences，控制器从未收到变更，
        // 用户表现就是「必须关掉 Moonlight 重连才能换桌面」。
        try {
            com.alkaidlab.sunshine.service.SunshineRuntimeManager.getInstance()
                    .setDesktopHomeType(home == HOME_AOSEN ? 1 : 0);
        } catch (Throwable t) {
            Log.w(TAG, "下发桌面类型到串流通道失败: " + t.getMessage());
        }
    }


    /**
     * 提前在手机主屏预热读取应用列表：
     * 三星 One UI 等系统首次调?LauncherApps 会弹出「允许读取已安装应用列表」系统授权窗?
     * 在手机端前台主动触发，确保弹窗展示在手机屏幕上供用户点击?
     * 避免在外接显示器/虚拟屏上启动沧海桌面时弹窗被甩到外屏、无法用触控点击?
     */
    private void prewarmAppListPermission() {
        new Thread(() -> {
            try {
                android.content.pm.LauncherApps launcherApps = (android.content.pm.LauncherApps)
                        getSystemService(Context.LAUNCHER_APPS_SERVICE);
                if (launcherApps != null) {
                    launcherApps.getActivityList(null, android.os.Process.myUserHandle());
                    Log.i(TAG, "已在手机前台预热已安装应用列表权?");
                }
            } catch (Throwable t) {
                Log.w(TAG, "预热应用列表权限异常: " + t.getMessage());
            }
        }).start();
    }


    private void updateHomeUI() {
        int home = getDesktopHome();
        boolean isOfficial = (home == HOME_OFFICIAL);


        int activeBg = R.drawable.bg_capsule_active;
        int activeText = Color.WHITE;
        int inactiveText = Color.parseColor("#94A3B8");


        // 更新外屏卡片上的胶囊切换按钮
        if (btnExtHomeOfficial != null) {
            btnExtHomeOfficial.setBackgroundResource(isOfficial ? activeBg : 0);
            btnExtHomeOfficial.setTextColor(isOfficial ? activeText : inactiveText);
            btnExtHomeOfficial.setTypeface(null, isOfficial ? android.graphics.Typeface.BOLD : android.graphics.Typeface.NORMAL);
        }
        if (btnExtHomeAosen != null) {
            btnExtHomeAosen.setBackgroundResource(!isOfficial ? activeBg : 0);
            btnExtHomeAosen.setTextColor(!isOfficial ? activeText : inactiveText);
            btnExtHomeAosen.setTypeface(null, !isOfficial ? android.graphics.Typeface.BOLD : android.graphics.Typeface.NORMAL);
        }
    }


    private int getScreenRotation() {
        SharedPreferences sp = getSharedPreferences("aosen_settings", Context.MODE_PRIVATE);
        return sp.getInt(PREF_SCREEN_ROTATION, 0); // 默认 0° 横屏
    }


    private void setScreenRotation(int rotation) {
        SharedPreferences sp = getSharedPreferences("aosen_settings", Context.MODE_PRIVATE);
        sp.edit().putInt(PREF_SCREEN_ROTATION, rotation).apply();
        updateScreenRotationUI();
        if (dexPresentation != null) {
            dexPresentation.setScreenRotation(rotation);
        }
        if (externalDisplayId > 0) {
            ShizukuCmd.setScreenRotation(externalDisplayId, rotation);
        }
        Toast.makeText(this, getString(R.string.toast_rotation_applied, rotation * 90), Toast.LENGTH_SHORT).show();
    }


    private void updateScreenRotationUI() {
    }


    private void showLanguageDialog() {
        final String[] items = new String[]{
                getString(R.string.lang_system),
                getString(R.string.lang_zh),
                getString(R.string.lang_en)
        };
        final String[] codes = new String[]{"system", "zh", "en"};


        String currentCode = LocaleHelper.getPersistedLanguage(this);
        int checkedItem = 0;
        for (int i = 0; i < codes.length; i++) {
            if (codes[i].equalsIgnoreCase(currentCode)) {
                checkedItem = i;
                break;
            }
        }


        // 语言切换面板底部添加发布地址与版本号展示
        LinearLayout footerLayout = new LinearLayout(this);
        footerLayout.setOrientation(LinearLayout.VERTICAL);
        footerLayout.setGravity(Gravity.CENTER_HORIZONTAL);
        footerLayout.setPadding(32, 12, 32, 16);


        TextView tvUrl = new TextView(this);
        tvUrl.setText(R.string.release_url_label);
        tvUrl.setTextColor(getColor(R.color.oneui_blue_light));
        tvUrl.setTextSize(12f);
        tvUrl.setGravity(Gravity.CENTER);
        tvUrl.setPadding(12, 12, 12, 4);
        tvUrl.setOnClickListener(v -> openBrowserUrl("https://gitee.com/cunkai/tips"));


        TextView tvVer = new TextView(this);
        tvVer.setText(R.string.app_version_label);
        tvVer.setTextColor(getColor(R.color.oneui_text_muted));
        tvVer.setTextSize(11f);
        tvVer.setGravity(Gravity.CENTER);


        footerLayout.addView(tvUrl);
        footerLayout.addView(tvVer);


        new AlertDialog.Builder(this)
                .setTitle(R.string.language)
                .setSingleChoiceItems(items, checkedItem, (dialog, which) -> {
                    dialog.dismiss();
                    String selectedLang = codes[which];
                    if (!selectedLang.equalsIgnoreCase(LocaleHelper.getPersistedLanguage(MainActivity.this))) {
                        LocaleHelper.persistLanguage(MainActivity.this, selectedLang);
                        recreate();
                    }
                })
                .setView(footerLayout)
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }


    public void openBrowserUrl(String url) {
        try {
            Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse(url));
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(intent);
        } catch (Throwable t) {
            Toast.makeText(this, url, Toast.LENGTH_SHORT).show();
        }
    }


    @Override
    public void onDesktopChanged() {
        runOnUiThread(() -> {
            updateDesktopTabs();
            if (dexPresentation != null) {
                updateGridButtonUI(dexPresentation.isGridMode());
            }
        });
    }


    private void updateGridButtonUI(boolean isGrid) {
    }


    @Override
    public void onDesktopLoadingStateChanged(boolean isLoading) {
        // 桌面加载状态已并入外接显示设备列表（列表行自带连接状态），不再单独提?
    }


    /** 单桌面：桌面切换标签栏已下线，不再创建任何标?View?*/
    private void updateDesktopTabs() {
    }


    private void applyAndRefreshDpi() {
        if (etDpiInput == null) return;
        String valStr = etDpiInput.getText().toString().trim();
        if (valStr.isEmpty()) return;


        try {
            int newDpi = Integer.parseInt(valStr);
            if (newDpi < 100 || newDpi > 600) return;


            currentDpi = newDpi;


            // 收起软键?
            InputMethodManager imm = (InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
            if (imm != null && getCurrentFocus() != null) {
                imm.hideSoftInputFromWindow(getCurrentFocus().getWindowToken(), 0);
            }


            // 重建特权虚拟屏并刷新桌面
            if (dexPresentation != null) {
                dexPresentation.recreateVirtualDisplayWithDpi(currentDpi);
                Toast.makeText(this, String.format(Locale.getDefault(), getString(R.string.toast_dpi_updated), currentDpi), Toast.LENGTH_SHORT).show();
            } else {
                detectAndMountExternalDisplay();
            }
        } catch (NumberFormatException ignored) {}
    }


    /* ==========================================================
     * 黑屏触控的目标屏：不再死盯 DeXPresentation（那是 Aosen_VD 沧海桌面虚拟屏）。
     * 用户连 Moonlight 时看到的是 Sunshine 另建的一块虚拟屏，注入到 Aosen_VD 上
     * 就会「本机/另一块屏在动、串流画面没反应」。这里按「当前激活目标」解析：
     *   用户在设备卡选中的屏 > Moonlight 串流虚拟屏 > 沧海桌面虚拟屏。
     * ========================================================== */
    private volatile int blackTouchTargetDisplay = -1;
    private volatile boolean blackTouchViaMoonlight = false;
    private volatile int blackTouchTargetW = 0;
    private volatile int blackTouchTargetH = 0;
    /** 设备详情「一键黑屏触控」指定的目标屏；-1 = 不指定，按当前激活屏解析。 */
    private volatile int blackTouchForcedDisplay = -1;
    /**
     * 已锁定一块目标屏。只要它还活着，中途连入的新屏绝不抢焦点；
     * 只有它断开、回到「没有控制外屏」的状态，才重新自动挑选。
     */
    private volatile boolean blackTouchBound = false;
    /** 黑屏期间的轻量巡检：Moonlight 客户端上下线 / 虚拟屏重建都能被感知。 */
    private final Handler blackTouchWatchHandler = new Handler(Looper.getMainLooper());
    private boolean blackTouchWatchRunning = false;
    private final Runnable blackTouchWatchRunnable = new Runnable() {
        @Override public void run() {
            if (!isBlackScreenActive()) { blackTouchWatchRunning = false; return; }
            try { refreshBlackTouchTarget(true); } catch (Throwable ignored) { }
            blackTouchWatchHandler.postDelayed(this, 1500L);
        }
    };
    private long lastBlackHoverAt = 0L;

    /** 找到可注入的屏；返回 false 表示真的没有目标（这时才提示「需要外屏」）。 */
    private boolean resolveBlackTouchTarget() {
        return resolveBlackTouchTarget(blackTouchForcedDisplay);
    }

    private boolean resolveBlackTouchTarget(int forced) {
        blackTouchTargetDisplay = -1;
        blackTouchViaMoonlight = false;
        blackTouchTargetW = externalWidth > 0 ? externalWidth : 0;
        blackTouchTargetH = externalHeight > 0 ? externalHeight : 0;

        int moonDisplay = -1;
        int moonW = 0, moonH = 0;
        boolean hasMoonClient = false;
        com.alkaidlab.sunshine.service.SunshineRuntimeManager rt = null;
        try {
            rt = com.alkaidlab.sunshine.service.SunshineRuntimeManager.getInstance();
            com.alkaidlab.sunshine.service.VirtualScreenState st =
                    rt.getVirtualScreenState().getValue();
            /* displayId == 0 是「三星 DeX 镜像主屏」，串流画面确实是这块，允许。
               只有 -1（虚拟屏没建起来）才算无目标。 */
            if (st != null && st.getActive() && st.getDisplayId() >= 0) {
                moonDisplay = st.getDisplayId();
                moonW = st.getWidth();
                moonH = st.getHeight();
            }
            java.util.List<com.alkaidlab.sunshine.data.RuntimeSession> sessions =
                    rt.getActiveSessionsFlow().getValue();
            hasMoonClient = sessions != null && !sessions.isEmpty();
        } catch (Throwable ignored) { }
        /* 运行时状态偶尔滞后（刚连上、状态还没刷）：串流屏的 id 由建屏方
           在创建那一刻上报给 StreamTarget，直接取用，不再靠屏幕名字反查。 */
        if (moonDisplay < 0) {
            int streamId = StreamTarget.get().getDisplayId();
            if (streamId > 0) {
                moonDisplay = streamId;
                moonW = StreamTarget.get().getWidth();
                moonH = StreamTarget.get().getHeight();
            }
        }

        /* 0. 设备详情里点「一键黑屏触控」：这块屏就是目标，不再看全局 activeDisplayId。
              displayId StreamTarget.UI_ID 是设备卡给 Moonlight 串流保留的 id，落到真正的 Sunshine 虚拟屏上。 */
        if (forced > 0) {
            /* 指定屏已经拔掉时不能硬认：否则会一直锁着一块不存在的屏，
               后面连进来的新屏也永远接管不了。StreamTarget.UI_ID 是 Moonlight 虚拟标记，单独处理。 */
            boolean forcedAlive = forced == StreamTarget.UI_ID
                    || (displayManager != null && displayManager.getDisplay(forced) != null);
            if (!forcedAlive) {
                Log.i(TAG, "黑屏触控指定目标已断开 display=" + forced + "，改走自动挑选");
            } else if (forced == StreamTarget.UI_ID) {
                blackTouchViaMoonlight = true;
                if (moonDisplay >= 0) {
                    blackTouchTargetDisplay = moonDisplay;
                    if (moonW > 0) blackTouchTargetW = moonW;
                    if (moonH > 0) blackTouchTargetH = moonH;
                } else {
                    blackTouchTargetDisplay = StreamTarget.UI_ID;
                    blackTouchTargetW = 1920;
                    blackTouchTargetH = 1080;
                }
                Log.i(TAG, "黑屏触控目标(锁定Moonlight StreamTarget.UI_ID) display=" + blackTouchTargetDisplay
                        + " " + blackTouchTargetW + "x" + blackTouchTargetH);
                return true;
            } else {
                int resolvedForced = forced;
                if (dexPresentation != null && dexPresentation.isShowing()
                        && dexPresentation.getVirtualDisplayId() > 0
                        && forced == externalDisplayId) {
                    resolvedForced = dexPresentation.getVirtualDisplayId();
                }
                boolean isMoon = StreamTarget.get().isStreamTarget(resolvedForced)
                        || (moonDisplay >= 0 && resolvedForced == moonDisplay);
                int fw = 0, fh = 0;
                if (displayManager != null) {
                    try {
                        for (Display dd : displayManager.getDisplays()) {
                            if (dd.getDisplayId() != resolvedForced) continue;
                            android.graphics.Point sz = new android.graphics.Point();
                            dd.getRealSize(sz);
                            fw = sz.x;
                            fh = sz.y;
                            break;
                        }
                    } catch (Throwable ignored) { }
                }
                blackTouchTargetDisplay = resolvedForced;
                blackTouchViaMoonlight = isMoon;
                if (fw > 0) blackTouchTargetW = fw;
                if (fh > 0) blackTouchTargetH = fh;
                Log.i(TAG, "黑屏触控目标(设备卡指定) display=" + resolvedForced
                        + " moon=" + isMoon + " " + blackTouchTargetW + "x" + blackTouchTargetH);
                return true;
            }
        }

        /* 1. 用户在设备面板里明确选中了 Moonlight（卡片 id StreamTarget.UI_ID） */
        if (activeDisplayId == StreamTarget.UI_ID && moonDisplay >= 0) {
            blackTouchTargetDisplay = moonDisplay;
            blackTouchViaMoonlight = true;
            if (moonW > 0) blackTouchTargetW = moonW;
            if (moonH > 0) blackTouchTargetH = moonH;
            return true;
        }
        /* 2. 用户明确选中了某块外接屏：若有线外屏已通过 DeXPresentation 承载虚拟屏，
              其黑屏触控目标应为虚拟屏 virtualDisplayId，这样才能走 DeXPresentation 自绘光标与点击链路。 */
        if (activeDisplayId > 0 && activeDisplayId != StreamTarget.UI_ID) {
            if (dexPresentation != null && dexPresentation.isShowing()
                    && dexPresentation.getVirtualDisplayId() > 0) {
                blackTouchTargetDisplay = dexPresentation.getVirtualDisplayId();
            } else {
                blackTouchTargetDisplay = activeDisplayId;
            }
            return true;
        }
        /* 3. 没手动选：Moonlight 正在串流，就控制用户正在看的那块屏 */
        if (moonDisplay >= 0) {
            blackTouchTargetDisplay = moonDisplay;
            blackTouchViaMoonlight = true;
            if (moonW > 0) blackTouchTargetW = moonW;
            if (moonH > 0) blackTouchTargetH = moonH;
            return true;
        }
        /* 4. 只剩沧海自己建的桌面虚拟屏 / 物理外接屏 */
        if (dexPresentation != null && dexPresentation.isShowing()) {
            if (dexPresentation.getVirtualDisplayId() > 0) {
                blackTouchTargetDisplay = dexPresentation.getVirtualDisplayId();
                return true;
            } else if (dexPresentation.getDisplay() != null) {
                blackTouchTargetDisplay = dexPresentation.getDisplay().getDisplayId();
                if (displayManager != null) {
                    try {
                        Display dd = displayManager.getDisplay(blackTouchTargetDisplay);
                        if (dd != null) {
                            android.graphics.Point sz = new android.graphics.Point();
                            dd.getRealSize(sz);
                            blackTouchTargetW = sz.x;
                            blackTouchTargetH = sz.y;
                        }
                    } catch (Throwable ignored) {}
                }
                return true;
            }
        }
        return false;
    }

    /** 当前锁定的目标屏是否还活着：拔线、虚拟屏销毁、Moonlight 客户端全断开都算死。 */
    private boolean isBlackTouchTargetAlive(int id, boolean moon) {
        if (id < 0) return false;
        if (moon || id == StreamTarget.UI_ID) {
            try {
                com.alkaidlab.sunshine.service.SunshineRuntimeManager rt =
                        com.alkaidlab.sunshine.service.SunshineRuntimeManager.getInstance();
                com.alkaidlab.sunshine.service.VirtualScreenState st =
                        rt.getVirtualScreenState().getValue();
                if (st != null && st.getActive() && (st.getDisplayId() == id || id == StreamTarget.UI_ID)) return true;
                java.util.List<com.alkaidlab.sunshine.data.RuntimeSession> sessions =
                        rt.getActiveSessionsFlow().getValue();
                if (sessions != null && !sessions.isEmpty()) return true;
            } catch (Throwable ignored) { }
            return true;
        } else if (dexPresentation != null && dexPresentation.isShowing()
                && dexPresentation.getDisplay() != null
                && id == dexPresentation.getDisplay().getDisplayId()) {
            return true;
        } else if (dexPresentation != null && !dexPresentation.isShowing()
                && id == dexPresentation.getVirtualDisplayId()) {
            return false;
        }
        if (displayManager != null) {
            try {
                return displayManager.getDisplay(id) != null;
            } catch (Throwable ignored) { }
        }
        /* 拿不到 DisplayManager 时保守保住当前目标，避免误判成断开而乱切屏 */
        return true;
    }

    /**
     * 黑屏触控的「目标屏管家」—— 全程只管目标，绝不主动退出黑屏：
     *   已锁定且目标还在  → 保持，新连入的设备不抢；
     *   目标断开          → 解绑回到无目标，并立刻接管此刻已存在的屏；
     *   没有目标          → 一旦探测到可用屏就接管，之前只是安静等待。
     */
    private boolean refreshBlackTouchTarget(boolean hintOnChange) {
        if (!isBlackScreenActive()) return false;

        if (blackTouchBound && blackTouchTargetDisplay >= 0
                && isBlackTouchTargetAlive(blackTouchTargetDisplay, blackTouchViaMoonlight)) {
            return false;
        }
        boolean justLost = false;
        if (blackTouchBound) {
            Log.i(TAG, "黑屏触控：原目标断开，回到无目标等待状态");
            blackTouchBound = false;
            blackTouchTargetDisplay = -1;
            blackTouchViaMoonlight = false;
            justLost = true;
        }

        int oldW = blackTouchTargetW, oldH = blackTouchTargetH;
        if (!resolveBlackTouchTarget(blackTouchForcedDisplay)) {
            /* 设备没了就保持黑屏等待，既不退出也不弹「需要外屏」 */
            blackTouchTargetDisplay = -1;
            blackTouchViaMoonlight = false;
            blackTouchBound = false;
            return false;
        }
        blackTouchBound = true;
        /* 光标坐标跨手势保留：只在目标尺寸变了 / 坐标越界时才回到中心 */
        if (blackTouchTargetW > 0 && blackTouchTargetH > 0
                && (oldW != blackTouchTargetW || oldH != blackTouchTargetH
                || cursorX <= 0 || cursorY <= 0
                || cursorX > blackTouchTargetW || cursorY > blackTouchTargetH)) {
            cursorX = blackTouchTargetW * 0.5f;
            cursorY = blackTouchTargetH * 0.5f;
        }
        /* 接管新目标时把自绘光标摆过去，别让它停在上一块屏的位置。
           若有线外屏走 DeXPresentation，则直接调用 enterBlackTouch() 画自绘光标，
           绝不向虚拟屏注入 SOURCE_MOUSE HOVER_MOVE（否则会在虚拟屏点亮系统硬件黑箭头）。 */
        if (dexPresentation != null && dexPresentation.isShowing()
                && !blackTouchViaMoonlight
                && (blackTouchTargetDisplay == dexPresentation.getVirtualDisplayId()
                    || (dexPresentation.getDisplay() != null && blackTouchTargetDisplay == dexPresentation.getDisplay().getDisplayId()))) {
            dexPresentation.enterBlackTouch();
        } else {
            long now = android.os.SystemClock.uptimeMillis();
            MotionEvent hover = MotionEvent.obtain(now, now,
                    MotionEvent.ACTION_HOVER_MOVE, cursorX, cursorY, 0);
            hover.setSource(InputDevice.SOURCE_MOUSE);
            injectBlackMotion(hover);
            hover.recycle();
        }
        Log.i(TAG, "黑屏触控接管 display=" + blackTouchTargetDisplay
                + " moon=" + blackTouchViaMoonlight
                + " " + blackTouchTargetW + "x" + blackTouchTargetH);
        return true;
    }

    private void startBlackTouchWatch() {
        if (blackTouchWatchRunning) return;
        blackTouchWatchRunning = true;
        blackTouchWatchHandler.removeCallbacks(blackTouchWatchRunnable);
        blackTouchWatchHandler.postDelayed(blackTouchWatchRunnable, 1500L);
    }

    private void stopBlackTouchWatch() {
        blackTouchWatchRunning = false;
        blackTouchWatchHandler.removeCallbacks(blackTouchWatchRunnable);
    }

    /** 构造一帧单指事件；点击用 SOURCE_TOUCHSCREEN，悬停用 SOURCE_MOUSE。 */
    private static MotionEvent blackTouchFrame(int action, float x, float y, int source) {
        long t = android.os.SystemClock.uptimeMillis();
        MotionEvent.PointerProperties[] pp = { new MotionEvent.PointerProperties() };
        pp[0].id = 0;
        pp[0].toolType = (source == InputDevice.SOURCE_TOUCHSCREEN)
                ? MotionEvent.TOOL_TYPE_FINGER : MotionEvent.TOOL_TYPE_MOUSE;
        MotionEvent.PointerCoords[] pc = { new MotionEvent.PointerCoords() };
        pc[0].x = x;
        pc[0].y = y;
        pc[0].pressure = (action == MotionEvent.ACTION_UP) ? 0f : 1f;
        pc[0].size = (source == InputDevice.SOURCE_TOUCHSCREEN) ? 0.5f : 0f;
        return MotionEvent.obtain(t, t, action, 1, pp, pc, 0, 0, 1f, 1f, 0, 0, source, 0);
    }

    /** 把一帧送到目标屏：Moonlight 虚拟屏走它自己的控制器（会写 displayId、Root 桥兜底）。 */
    private void injectBlackMotion(MotionEvent src) {
        if (blackTouchTargetDisplay < 0 || src == null) return;
        MotionEvent copy = MotionEvent.obtain(src);
        try {
            boolean viaRuntime = false;
            if (blackTouchViaMoonlight) {
                try {
                    com.alkaidlab.sunshine.service.VirtualScreenState st =
                            com.alkaidlab.sunshine.service.SunshineRuntimeManager.getInstance()
                                    .getVirtualScreenState().getValue();
                    viaRuntime = st != null && st.getActive();
                } catch (Throwable ignored) { }
            }
            if (viaRuntime) {
                /* 虚拟屏控制器会自己写 displayId，并用 Root 桥兜底，最稳。 */
                com.alkaidlab.sunshine.service.SunshineRuntimeManager.getInstance()
                        .injectMotionEventFromPeripheral(copy);
            } else if (blackTouchTargetDisplay > 0) {
                /* 状态没同步/物理屏：直接带 displayId 注入，绝不落到 display 0。 */
                boolean ok = ShizukuCmd.injectMotionEvent(copy, blackTouchTargetDisplay);
                if (!ok) {
                    RootInputBridge.inject(this, blackTouchTargetDisplay,
                            copy.getAction(), copy.getButtonState(), copy.getActionButton(),
                            copy.getX(), copy.getY(),
                            copy.getAxisValue(MotionEvent.AXIS_VSCROLL),
                            copy.getAxisValue(MotionEvent.AXIS_HSCROLL), copy.getSource());
                }
            }
        } catch (Throwable t) {
            Log.w(TAG, "injectBlackMotion: " + t);
        } finally {
            copy.recycle();
        }
    }

    private void blackTouchClick(float x, float y) {
        MotionEvent down = blackTouchFrame(MotionEvent.ACTION_DOWN, x, y,
                InputDevice.SOURCE_TOUCHSCREEN);
        injectBlackMotion(down);
        down.recycle();
        MotionEvent up = blackTouchFrame(MotionEvent.ACTION_UP, x, y,
                InputDevice.SOURCE_TOUCHSCREEN);
        injectBlackMotion(up);
        up.recycle();
    }

    /** 右键 = 目标屏上的一次长按（和 DeX 桌面右键菜单语义一致）。 */
    private void blackTouchRightClick(float x, float y) {
        MotionEvent down = blackTouchFrame(MotionEvent.ACTION_DOWN, x, y,
                InputDevice.SOURCE_TOUCHSCREEN);
        injectBlackMotion(down);
        down.recycle();
        View anchor = viewBlackScreenOverlay != null ? viewBlackScreenOverlay : getWindow().getDecorView();
        anchor.postDelayed(() -> {
            if (!isBlackScreenActive()) return;
            MotionEvent up = blackTouchFrame(MotionEvent.ACTION_UP, x, y,
                    InputDevice.SOURCE_TOUCHSCREEN);
            injectBlackMotion(up);
            up.recycle();
        }, 620L);
    }

    private void blackTouchScroll(float x, float y, float vScroll, float hScroll) {
        long t = android.os.SystemClock.uptimeMillis();
        MotionEvent.PointerProperties[] pp = { new MotionEvent.PointerProperties() };
        pp[0].id = 0;
        pp[0].toolType = MotionEvent.TOOL_TYPE_MOUSE;
        MotionEvent.PointerCoords[] pc = { new MotionEvent.PointerCoords() };
        pc[0].x = x;
        pc[0].y = y;
        pc[0].setAxisValue(MotionEvent.AXIS_VSCROLL, vScroll);
        pc[0].setAxisValue(MotionEvent.AXIS_HSCROLL, hScroll);
        MotionEvent ev = MotionEvent.obtain(t, t, MotionEvent.ACTION_SCROLL, 1, pp, pc,
                0, 0, 1f, 1f, 0, 0, InputDevice.SOURCE_MOUSE, 0);
        injectBlackMotion(ev);
        ev.recycle();
    }

    private void enterBlackScreenMode() {
        if (viewBlackScreenOverlay == null) return;
        if (viewBlackScreenOverlay.getVisibility() == View.VISIBLE) {
            return;
        }


        // 用户主动或自动进入黑屏，重置主动退出标?
        sUserExitedForCurrentPlug = false;


        // 收起键盘
        InputMethodManager imm = (InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
        if (imm != null && getCurrentFocus() != null) {
            imm.hideSoftInputFromWindow(getCurrentFocus().getWindowToken(), 0);
        }


        viewBlackScreenOverlay.setVisibility(View.VISIBLE);
        /* 彻底隐藏底层 WebView 并屏蔽无障碍/识屏捕获：
           避免触摸操作穿透到底层网页文字，或长按触发手机系统「询问 AI / 智慧识屏」。 */
        if (webPhoneHome != null) {
            webPhoneHome.clearFocus();
            webPhoneHome.setVisibility(View.INVISIBLE);
        }
        viewBlackScreenOverlay.requestFocus();
        View decor = getWindow().getDecorView();
        decor.setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                | View.SYSTEM_UI_FLAG_FULLSCREEN
                | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                | View.SYSTEM_UI_FLAG_LAYOUT_STABLE
        );


        /* 黑屏触控 = 手机屏当触控板，控制「用户正在看的那块屏」。
           Moonlight 串流时那是 Sunshine 虚拟屏，不再是 Aosen_VD：
           以前一律注入 Aosen_VD，用户看到的就是「控制的本机、串流里没反应」。
           只有目标确实是沧海桌面虚拟屏时，才需要抢光标焦点、压住系统箭头。 */
        sBlackTouchWasPhoneActive = (activeDisplayId <= 0);
        /* 允许「先进黑屏、再连屏幕」：没有目标也不退出，只清成等待状态，
           由目标管家在设备出现 / 消失时自动接管或继续等待。 */
        blackTouchBound = false;
        blackTouchTargetDisplay = -1;
        blackTouchViaMoonlight = false;
        refreshBlackTouchTarget(false);
        startBlackTouchWatch();

        // 黑屏触控期间手机屏幕纯黑，将主屏（Display 0）置为横屏方向，
        // 使外屏 / Moonlight 任意应用唤起输入法时均自动进入横屏悬浮键盘模式，
        // 同时触控板位移与提示卡片已按物理握持方向自动补偿，竖握/横握手机手感完全一致。
        initPhysicalOrientationListener();
        setExternalImeLandscapeLock(true);

        // dock 风格操作提醒：显示 10 秒，倒计时到 0s 后收起、整屏纯黑
        showBlackHintDock();
    }

    /** 极简白色线框提醒：自适应横竖屏两套 UI，强制根据屏幕缩放保证文字 100% 显示 */
    private void showBlackHintDock() {
        if (blackHintDock == null) {
            blackHintDock = findViewById(R.id.blackHintDock);
            blackHintPortrait = findViewById(R.id.blackHintPortrait);
            blackHintLandscape = findViewById(R.id.blackHintLandscape);
            tvHintCountdown = findViewById(R.id.tvHintCountdown);
            tvHintCountdownLand = findViewById(R.id.tvHintCountdownLand);
        }
        if (blackHintDock == null) return;

        updateBlackHintOrientationAndScale();

        blackHintDock.animate().cancel();
        if (blackHintHideRunnable != null) blackHintDock.removeCallbacks(blackHintHideRunnable);
        blackHintDock.setAlpha(1f);
        blackHintDock.setVisibility(View.VISIBLE);
        if (tvHintCountdown != null) tvHintCountdown.setText("10s");
        if (tvHintCountdownLand != null) tvHintCountdownLand.setText("10s");

        if (blackHintTimer != null) blackHintTimer.cancel();
        blackHintTimer = new android.os.CountDownTimer(10000, 100) {
            @Override
            public void onTick(long millisUntilFinished) {
                int sec = (int) ((millisUntilFinished + 999) / 1000);
                if (tvHintCountdown != null) tvHintCountdown.setText(sec + "s");
                if (tvHintCountdownLand != null) tvHintCountdownLand.setText(sec + "s");
            }

            @Override
            public void onFinish() {
                if (tvHintCountdown != null) tvHintCountdown.setText("0s");
                if (tvHintCountdownLand != null) tvHintCountdownLand.setText("0s");
                // 归零即关闭提示，屏幕只剩纯黑
                blackHintHideRunnable = () -> {
                    if (blackHintDock == null) return;
                    blackHintDock.animate().alpha(0f).setDuration(350)
                            .withEndAction(() -> blackHintDock.setVisibility(View.GONE)).start();
                };
                blackHintDock.postDelayed(blackHintHideRunnable, 250);
            }
        }.start();
    }

    /** 横竖屏两套 UI 切换与自适应强制等比缩小，确保文字 100% 完整显示在屏幕中 */
    private void updateBlackHintOrientationAndScale() {
        if (blackHintDock == null) return;
        android.util.DisplayMetrics dm = getResources().getDisplayMetrics();
        boolean isLandscape = (dm.widthPixels > dm.heightPixels);

        if (blackHintPortrait != null && blackHintLandscape != null) {
            blackHintPortrait.setVisibility(isLandscape ? View.GONE : View.VISIBLE);
            blackHintLandscape.setVisibility(isLandscape ? View.VISIBLE : View.GONE);
        }

        android.widget.FrameLayout.LayoutParams lp =
                (android.widget.FrameLayout.LayoutParams) blackHintDock.getLayoutParams();
        lp.width = android.widget.FrameLayout.LayoutParams.WRAP_CONTENT;
        lp.height = android.widget.FrameLayout.LayoutParams.WRAP_CONTENT;
        lp.gravity = Gravity.CENTER;
        blackHintDock.setLayoutParams(lp);

        blackHintDock.post(() -> {
            if (blackHintDock == null) return;
            int screenW = dm.widthPixels;
            int screenH = dm.heightPixels;
            int measuredW = blackHintDock.getWidth();
            int measuredH = blackHintDock.getHeight();
            if (measuredW <= 0 || measuredH <= 0) {
                blackHintDock.measure(View.MeasureSpec.UNSPECIFIED, View.MeasureSpec.UNSPECIFIED);
                measuredW = blackHintDock.getMeasuredWidth();
                measuredH = blackHintDock.getMeasuredHeight();
            }
            if (measuredW <= 0 || measuredH <= 0) return;

            float maxW = screenW * (isLandscape ? 0.88f : 0.86f);
            float maxH = screenH * (isLandscape ? 0.82f : 0.82f);
            float scale = 1.0f;
            if (measuredW > maxW || measuredH > maxH) {
                scale = Math.min(maxW / (float) measuredW, maxH / (float) measuredH);
            }
            blackHintDock.setPivotX(measuredW / 2f);
            blackHintDock.setPivotY(measuredH / 2f);
            blackHintDock.setScaleX(scale);
            blackHintDock.setScaleY(scale);
        });
    }

    /**
     * 将目标显示屏 ID 解析为真正承载窗口与应用的虚拟屏 ID
     * - 有线外接屏（物理 display）解析为 DeXPresentation 的 virtualDisplayId
     * - Moonlight（StreamTarget.UI_ID 或未指定）解析为 Sunshine 的 VirtualScreen displayId
     */
    public int resolveDisplayForInput(int targetDisplayId) {
        if (targetDisplayId <= 0 || targetDisplayId == StreamTarget.UI_ID) {
            // 优先检查 Moonlight 串流虚拟屏
            try {
                com.alkaidlab.sunshine.service.SunshineRuntimeManager rt =
                        com.alkaidlab.sunshine.service.SunshineRuntimeManager.getInstance();
                com.alkaidlab.sunshine.service.VirtualScreenState st =
                        rt.getVirtualScreenState().getValue();
                if (st != null && st.getActive() && st.getDisplayId() > 0) {
                    return st.getDisplayId();
                }
            } catch (Throwable ignored) {}
            int streamId = StreamTarget.get().getDisplayId();
            if (streamId > 0) return streamId;
            // 检查外接屏虚拟桌面
            if (dexPresentation != null && dexPresentation.isShowing() && dexPresentation.getVirtualDisplayId() > 0) {
                return dexPresentation.getVirtualDisplayId();
            }
        } else if (dexPresentation != null && dexPresentation.isShowing()) {
            int physId = dexPresentation.getDisplay() != null ? dexPresentation.getDisplay().getDisplayId() : -1;
            if (targetDisplayId == physId && dexPresentation.getVirtualDisplayId() > 0) {
                return dexPresentation.getVirtualDisplayId();
            }
        }
        return targetDisplayId;
    }

    /** 三指轻点模拟手机系统返回键（KEYCODE_BACK），派发给当前黑屏触控目标 */
    private void triggerThreeFingerBackAction() {
        try {
            android.os.Vibrator v = (android.os.Vibrator) getSystemService(Context.VIBRATOR_SERVICE);
            if (v != null && v.hasVibrator()) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    v.vibrate(android.os.VibrationEffect.createOneShot(35, android.os.VibrationEffect.DEFAULT_AMPLITUDE));
                } else {
                    v.vibrate(35);
                }
            }
        } catch (Throwable ignored) {}

        int targetId = resolveDisplayForInput(blackTouchTargetDisplay);
        final int finalTargetId = targetId;
        // 严格规范：三指返回只作用于外屏/大屏，没有外屏时绝对不响应，绝不退出手机软件！
        if (finalTargetId <= 0) {
            Log.i(TAG, "三指返回忽略：当前无外屏/大屏目标，绝不影响手机主屏");
            return;
        }
        new Thread(() -> {
            try {
                long now = SystemClock.uptimeMillis();
                KeyEvent down = new KeyEvent(now, now, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_BACK, 0);
                KeyEvent up = new KeyEvent(now, now + 35, KeyEvent.ACTION_UP, KeyEvent.KEYCODE_BACK, 0);
                boolean ok = ShizukuCmd.injectKeyEvent(down, finalTargetId) && ShizukuCmd.injectKeyEvent(up, finalTargetId);
                if (!ok) {
                    ShizukuCmd.execute(String.format(Locale.US, "input -d %d keyevent 4", finalTargetId));
                }
                Log.i(TAG, "三指返回键成功派发至目标显示屏 displayId=" + finalTargetId);
            } catch (Throwable t) {
                Log.w(TAG, "三指返回键触发失败: " + t.getMessage());
            }
        }).start();
    }

    /** 退出黑屏 / Activity 销毁时取消倒计时并立即移除 dock */
    private void hideBlackHintDock() {
        if (blackHintTimer != null) { blackHintTimer.cancel(); blackHintTimer = null; }
        if (blackHintDock != null) {
            blackHintDock.animate().cancel();
            if (blackHintHideRunnable != null) blackHintDock.removeCallbacks(blackHintHideRunnable);
            blackHintDock.setAlpha(0f);
            blackHintDock.setVisibility(View.GONE);
        }
    }


    private void exitBlackScreenMode() {
        // 标记用户主动退出了黑屏，在本次外接设备未拔出前绝不再自动拉入黑?
        sUserExitedForCurrentPlug = true;
        stopBlackTouchWatch();
        blackTouchBound = false;
        hideBlackHintDock();
        setExternalImeLandscapeLock(false);
        if (viewBlackScreenOverlay == null) return;
        viewBlackScreenOverlay.setVisibility(View.GONE);
        if (webPhoneHome != null) {
            webPhoneHome.setVisibility(View.VISIBLE);
            webPhoneHome.requestFocus();
        }
        View decor = getWindow().getDecorView();
        decor.setSystemUiVisibility(View.SYSTEM_UI_FLAG_VISIBLE);
        if (getIntent() != null) {
            getIntent().removeExtra("auto_black_screen");
        }
        /* 退出黑屏后，把光标焦点还回进入前的位置：
           进入黑屏时我们把焦点强制抢到了外屏（否则外屏看不到自绘鼠标），
           这里还回去，别让用户回来发现鼠标跑到别处去了。 */
        if (sBlackTouchWasPhoneActive && dexPresentation != null) {
            dexPresentation.setCursorFocusScreen(false, "手机主屏");
        }
        sBlackTouchWasPhoneActive = false;
        /* 黑屏一退出就不再往目标屏注入，免得残留事件打到串流画面上。
         * 只收掉黑屏那块屏的光标，不能用全清 —— 真实外屏上的鼠标是另一条关系。 */
        final int blackTouchGoneDisplay = blackTouchTargetDisplay;
        blackTouchTargetDisplay = -1;
        blackTouchViaMoonlight = false;
        blackTouchForcedDisplay = -1;
        try {
            if (blackTouchGoneDisplay > 0) {
                com.alkaidlab.sunshine.service.RemoteCursorOverlay.INSTANCE
                        .hideOnly(blackTouchGoneDisplay);
            }
        } catch (Throwable ignored) { }
    }

    /** 进入黑屏触控前，手机主屏是不是激活目标（决定退出后要不要还回焦点）。 */
    private boolean sBlackTouchWasPhoneActive = false;


    public boolean isBlackScreenActive() {
        return viewBlackScreenOverlay != null && viewBlackScreenOverlay.getVisibility() == View.VISIBLE;
    }

    public static boolean isBlackTouchActive() {
        return sInstance != null && sInstance.isBlackScreenActive();
    }


    public void onPresentationBackRequested() {
        if (isBlackScreenActive()) {
            exitBlackScreenMode();
        }
    }


    private int lastPhysicalMinPx = 0;
    private int lastPhysicalMaxPx = 0;
    private final Handler foldRebuildHandler = new Handler(Looper.getMainLooper());
    private final Runnable foldRebuildRunnable = this::checkAndRebuildWebOnFold;

    private int[] currentPhysicalDisplayMinMax() {
        try {
            android.util.DisplayMetrics dm = new android.util.DisplayMetrics();
            getWindowManager().getDefaultDisplay().getRealMetrics(dm);
            return new int[]{ Math.min(dm.widthPixels, dm.heightPixels), Math.max(dm.widthPixels, dm.heightPixels), dm.densityDpi };
        } catch (Throwable t) {
            android.util.DisplayMetrics dm = getResources().getDisplayMetrics();
            return new int[]{ Math.min(dm.widthPixels, dm.heightPixels), Math.max(dm.widthPixels, dm.heightPixels), dm.densityDpi };
        }
    }

    private void checkAndRebuildWebOnFold() {
        if (isFinishing() || isDestroyed() || webPhoneHome == null) return;
        int[] mm = currentPhysicalDisplayMinMax();
        int minPx = mm[0], maxPx = mm[1];
        if (minPx <= 0 || maxPx <= 0) return;
        if (lastPhysicalMinPx > 0 && lastPhysicalMaxPx > 0
                && (Math.abs(minPx - lastPhysicalMinPx) > 40 || Math.abs(maxPx - lastPhysicalMaxPx) > 40)) {
            Log.i(TAG, "检测到折叠屏内外屏物理尺寸切换 (" + lastPhysicalMinPx + "x" + lastPhysicalMaxPx
                    + " -> " + minPx + "x" + maxPx + ")，原位重建 WebView 视口");
            lastPhysicalMinPx = minPx;
            lastPhysicalMaxPx = maxPx;
            rebuildPhoneWebViewInPlace();
            return;
        }
        lastPhysicalMinPx = minPx;
        lastPhysicalMaxPx = maxPx;
        notifyFoldOrResizeToWeb();
    }

    private void rebuildPhoneWebViewInPlace() {
        try {
            if (webPhoneHome == null) return;
            android.view.ViewParent parent = webPhoneHome.getParent();
            if (!(parent instanceof android.view.ViewGroup)) {
                notifyFoldOrResizeToWeb();
                return;
            }
            // 同步当前屏幕真实 DisplayMetrics 到 Resources，防止折叠屏切换后沿用旧屏 density
            try {
                android.util.DisplayMetrics realDm = new android.util.DisplayMetrics();
                getWindowManager().getDefaultDisplay().getRealMetrics(realDm);
                android.util.DisplayMetrics resDm = getResources().getDisplayMetrics();
                resDm.setTo(realDm);
            } catch (Throwable ignored) {}

            android.view.ViewGroup vg = (android.view.ViewGroup) parent;
            int idx = vg.indexOfChild(webPhoneHome);
            android.view.ViewGroup.LayoutParams lp = webPhoneHome.getLayoutParams();
            int vis = isBlackScreenActive() ? View.INVISIBLE : View.VISIBLE;
            android.webkit.WebView oldWeb = webPhoneHome;
            vg.removeView(oldWeb);
            try {
                oldWeb.stopLoading();
                oldWeb.destroy();
            } catch (Throwable ignored) {}

            /* 重建后沿用普通 WebView：系统原生输入法会自行接管输入框。 */
            android.webkit.WebView freshWeb = new android.webkit.WebView(this);
            freshWeb.setId(R.id.webPhoneHome);
            freshWeb.setLayoutParams(lp);
            freshWeb.setVisibility(vis);
            vg.addView(freshWeb, idx);
            webPhoneHome = freshWeb;
            setupPhoneHomeWeb();
        } catch (Throwable t) {
            Log.w(TAG, "rebuildPhoneWebViewInPlace: " + t.getMessage());
            notifyFoldOrResizeToWeb();
        }
    }

    @Override
    public void onConfigurationChanged(android.content.res.Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        Log.i(TAG, "系统配置发生变更(折叠屏开合/分辨率变更/外设插拔)，重算布局并保持 DeX 持续稳定运行");
        scheduleFoldOrResizeSync();
    }

    private void scheduleFoldOrResizeSync() {
        foldRebuildHandler.removeCallbacks(foldRebuildRunnable);
        foldRebuildHandler.postDelayed(foldRebuildRunnable, 90L);
        notifyFoldOrResizeToWeb();
        foldRebuildHandler.postDelayed(this::notifyFoldOrResizeToWeb, 220L);
        foldRebuildHandler.postDelayed(this::notifyFoldOrResizeToWeb, 450L);
    }

    private void notifyFoldOrResizeToWeb() {
        if (webPhoneHome == null || isFinishing() || isDestroyed()) return;
        try {
            if (isBlackScreenActive() && blackHintDock != null && blackHintDock.getVisibility() == View.VISIBLE) {
                android.util.DisplayMetrics dm = getResources().getDisplayMetrics();
                int shortSide = Math.min(dm.widthPixels, dm.heightPixels);
                int width80 = Math.round(shortSide * 0.82f);
                android.widget.FrameLayout.LayoutParams lp =
                        (android.widget.FrameLayout.LayoutParams) blackHintDock.getLayoutParams();
                lp.width = width80;
                blackHintDock.setLayoutParams(lp);
                updateBlackHintDockRotation();
            }
            int[] cut = cutoutSafeInsets();
            int sysTop = Math.max(systemBarHeight("status_bar_height"), cut[0]);
            int sysBottom = systemBarHeight("navigation_bar_height");
            int sysLeft = cut[1];
            int sysRight = cut[2];
            String js = "window.__chOnFoldOrResize && window.__chOnFoldOrResize("
                    + sysTop + "," + sysBottom + "," + sysLeft + "," + sysRight + ");";
            webPhoneHome.evaluateJavascript(js, null);
        } catch (Throwable ignored) {}
    }

    @Override
    public void onProvideAssistData(Bundle data) {
        if (isBlackScreenActive() || isPointerServingOtherScreen()) {
            // 黑屏触控 / 鼠标已经挂到别的屏：绝不向系统「长按识屏 / 询问 AI」暴露任何文字结构
            return;
        }
        super.onProvideAssistData(data);
    }

    @Override
    public void onProvideAssistContent(android.app.assist.AssistContent outContent) {
        if (isBlackScreenActive() || isPointerServingOtherScreen()) {
            return;
        }
        super.onProvideAssistContent(outContent);
    }


    /** 外设大脑当前是否有指针外设正服务别的屏（手机这一刻不该有长按/识屏语义）。 */
    private boolean isPointerServingOtherScreen() {
        try {
            return PeripheralBrain.getInstance().isPointerAwayFromPhone();
        } catch (Throwable t) {
            return false;
        }
    }


    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        if (event == null) return super.dispatchKeyEvent(event);

        // 外设大脑统一总拦截闸门 1：所有外部物理外设按键 (手柄、外接键盘、蓝牙遥控器等)
        if (PeripheralBrain.getInstance().interceptKeyEvent(event)) {
            return true;
        }

        if (event.getKeyCode() == KeyEvent.KEYCODE_BACK) {
            if (viewBlackScreenOverlay != null && viewBlackScreenOverlay.getVisibility() == View.VISIBLE) {
                if (event.getAction() == KeyEvent.ACTION_UP) {
                    exitBlackScreenMode();
                }
                return true;
            }
        }
        return super.dispatchKeyEvent(event);
    }


    /** 上一次按返回的时间：1 秒内连按两次才退出，跟主流软件一致。 */
    private long lastBackPressAt = 0L;

    @Override
    public void onBackPressed() {
        /* 不调 super：统一走 handleSystemBack，避免和 OnBackPressedDispatcher 的
           callback 两套逻辑打架（Android 13+ 可能只走其中一条）。 */
        handleSystemBack();
    }

    /** 返回键唯一入口：黑屏 → native 详情页 → 页面弹层 → 双击退后台。 */
    private long lastSystemBackAt = 0L;
    private void handleSystemBack() {
        /* 老 onBackPressed 与 Android 13+ 系统回调极短时间内的重复派发只算一次，
           否则一次返回会连关两层弹窗。120ms 远小于「1 秒内连按两次退出」的窗口。 */
        long now = System.currentTimeMillis();
        if (now - lastSystemBackAt < 120L) return;
        lastSystemBackAt = now;
        if (isBlackScreenActive()) {
            exitBlackScreenMode();
            return;
        }
        if (layoutScreenDetailPage != null && layoutScreenDetailPage.getVisibility() == View.VISIBLE) {
            layoutScreenDetailPage.setVisibility(View.GONE);
            if (layoutStateConnected != null) layoutStateConnected.setVisibility(View.VISIBLE);
            updateUiState();
            return;
        }
        // 先问页面：有弹层（运行器 / 屏幕配置 / 各弹窗）就让页面自己关掉，返回键被它吃掉
        if (webPhoneHome != null) {
            webPhoneHome.evaluateJavascript("window.chOnBack ? !!window.chOnBack() : false",
                    value -> {
                        if (value != null && value.contains("true")) return;
                        handleBackAfterPage();
                    });
            return;
        }
        handleBackAfterPage();
    }

    /** 页面没有要吃的返回事件时：1 秒内第二次按才退到后台。 */
    private void handleBackAfterPage() {
        long now = System.currentTimeMillis();
        if (now - lastBackPressAt < 1000L) {
            // 返回手机主页（退到后台桌面），保?DeXPresentation 在外屏持续运行，绝不锁死
            moveTaskToBack(true);
            lastBackPressAt = 0L;
        } else {
            lastBackPressAt = now;
            Toast.makeText(this, R.string.toast_back_again_to_exit, Toast.LENGTH_SHORT).show();
        }
    }


    private void setupTouchpad() {
        View.OnTouchListener touchListener = (v, event) -> handleTouchpadEvent(v, event);
        if (viewTouchpad != null) {
            viewTouchpad.setOnTouchListener(touchListener);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                viewTouchpad.setPointerIcon(android.view.PointerIcon.getSystemIcon(this, android.view.PointerIcon.TYPE_NULL));
            }
        }
        if (viewBlackScreenOverlay != null) {
            viewBlackScreenOverlay.setOnTouchListener(touchListener);
            viewBlackScreenOverlay.setLongClickable(false);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                viewBlackScreenOverlay.setContextClickable(false);
            }
            disableTextSelectionAndAssistRecursively(viewBlackScreenOverlay);
        }
    }

    /** 递归禁止黑屏触控遮罩内所有文字的选择复制、长按菜单与系统识屏捕获 */
    private void disableTextSelectionAndAssistRecursively(View v) {
        if (v == null) return;
        v.setLongClickable(false);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            v.setContextClickable(false);
        }
        v.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            v.setImportantForContentCapture(View.IMPORTANT_FOR_CONTENT_CAPTURE_NO_EXCLUDE_DESCENDANTS);
        }
        if (v instanceof TextView) {
            TextView tv = (TextView) v;
            tv.setTextIsSelectable(false);
            tv.setCustomSelectionActionModeCallback(new android.view.ActionMode.Callback() {
                @Override public boolean onCreateActionMode(android.view.ActionMode m, android.view.Menu menu) { return false; }
                @Override public boolean onPrepareActionMode(android.view.ActionMode m, android.view.Menu menu) { return false; }
                @Override public boolean onActionItemClicked(android.view.ActionMode m, android.view.MenuItem item) { return false; }
                @Override public void onDestroyActionMode(android.view.ActionMode m) {}
            });
        }
        if (v instanceof android.view.ViewGroup) {
            android.view.ViewGroup vg = (android.view.ViewGroup) v;
            for (int i = 0; i < vg.getChildCount(); i++) {
                disableTextSelectionAndAssistRecursively(vg.getChildAt(i));
            }
        }
    }


    private void setupCursorCustomization() {
    }




    /** 绑定四向键盘的一个方向键：向对应方向滑动指定像素距离 */
    private void bindDpad(int viewId, float dx, float dy) {
        View btn = findViewById(viewId);
        if (btn != null) {
            btn.setOnClickListener(v -> {
                if (dexPresentation != null && dexPresentation.isShowing()) {
                    dexPresentation.performSwipeAt(cursorX, cursorY, dx, dy, 200);
                }
            });
        }
    }


    /** 单桌面：宫格排列已下线，三指点按不再触发任何切换?*/
    private void triggerGridModeToggle() {
        // no-op：保留方法名，避免多处手势分支改动引入回?
    }


    private void resetTouchState() {
        twoFingerActive = false;
        twoFingerMoved = false;
        gestureMoved = false;
        multiFingerActionTriggered = false;
        gestureMaxPointers = 1;
    }


    /** 判断事件是否来自物理鼠标（使用 Android 原生 isFromSource，避免 SOURCE_CLASS_POINTER 低位掩码误判触屏） */
    private boolean isMouseLike(MotionEvent event) {
        if (event == null) return false;
        if (event.isFromSource(InputDevice.SOURCE_MOUSE) || event.isFromSource(InputDevice.SOURCE_TRACKBALL)) {
            return true;
        }
        for (int i = 0; i < event.getPointerCount(); i++) {
            if (event.getToolType(i) == MotionEvent.TOOL_TYPE_MOUSE) return true;
        }
        return false;
    }


        /**
     * 外设大脑无边界硬件指针捕获与多屏物理路由分发
     */
    public void ensurePointerCapture() {
        /* 只要我们的真鼠标节点已经建出来，这套捕获必须彻底停手。
         *
         * 以前这里判的是 isActive()（绑屏之后才为真），于是「建了节点、还没绑屏」的那段
         * 时间窗口还在抢捕获 —— 而捕获一开，系统会把所有鼠标设备切进
         * MODE_POINTER_RELATIVE（sources=0x20004），光标被 fade 掉、不做命中测试，
         * 位移只送给捕获窗口。等我们再去绑屏，设备已经是「相对设备」了，
         * 虚拟屏上自然点不动。改成 hasDevice()，节点一建成这里就永久闭嘴。 */
        if (VirtualMouse.hasDevice() || VirtualMouse.captureBanned()) return;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            if (rootBox == null) rootBox = findViewById(R.id.rootContainer);
            if (rootBox != null && !rootBox.hasPointerCapture()) {
                rootBox.post(() -> {
                    if (rootBox != null && !rootBox.hasPointerCapture()) {
                        /* 焦点只在确实没焦点时才动。以前每次申请捕获都重设一遍
                         * focusable + requestFocus()，焦点一变系统就把 PointerCapture
                         * 收回去 —— 日志里「已捕获/已释放」每秒抖好几次就是这么来的，
                         * 而释放的那零点几秒里物理指针会跑到手机屏上，贴着边缘按一下
                         * 就会触发系统的返回/通知手势。 */
                        if (!rootBox.isFocused()) {
                            rootBox.setDescendantFocusability(android.view.ViewGroup.FOCUS_BEFORE_DESCENDANTS);
                            rootBox.setFocusable(true);
                            rootBox.setFocusableInTouchMode(true);
                            rootBox.requestFocus();
                        }
                        try {
                            rootBox.requestPointerCapture();
                            Log.i(TAG, "外设大脑已向系统申请 PointerCapture 硬件独占捕获");
                        } catch (Throwable t) {
                            Log.w(TAG, "requestPointerCapture 失败: " + t);
                        }
                    }
                });
            }
        }
    }

    /**
     * 捕获被系统收走后，指针会立刻回到手机屏上乱跑，贴到边就是通知栏/返回/退桌面。
     * 所以这里不是「试一次就算了」，而是每 60ms 重试一次、最多 3 秒 —— 之前 14:59
     * 那次丢了整整 16 秒才恢复，中间物理鼠标一直在手机屏上。失败原因也写进面板，
     * 因为大多数时候抢不回来是窗口没焦点，而不是接口不支持。
     */
    private void forceCaptureRetry() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return;
        if (VirtualMouse.hasDevice()) return;   // 真设备节点已建，捕获会把它降级成相对设备，一律不抢
        if (VirtualMouse.captureBanned()) return;
        if (rootBox == null) rootBox = findViewById(R.id.rootContainer);
        if (rootBox == null) return;
        if (captureRetryRunnable != null) return;
        captureRetryCount = 0;
        captureRetryRunnable = new Runnable() {
            @Override
            public void run() {
                if (rootBox == null) {
                    captureRetryRunnable = null;
                    return;
                }
                /* 重抢循环一旦跑起来会连抢 3 秒。真设备通道放手的那一刻它正好在跑，
                 * 就会把捕获又抢回去、把设备压成相对模式 —— 每轮都必须重新问一次。 */
                if (VirtualMouse.hasDevice() || VirtualMouse.captureBanned()) {
                    captureRetryRunnable = null;
                    return;
                }
                if (rootBox.hasPointerCapture()) {
                    if (captureRetryCount > 0) {
                        InputTrace.note("捕获", "指针已夺回，重试 " + captureRetryCount + " 次");
                    }
                    captureRetryRunnable = null;
                    return;
                }
                if (captureRetryCount >= 50) {
                    captureRetryRunnable = null;
                    InputTrace.note("捕获", "3 秒内没夺回指针（窗口焦点=" + hasWindowFocus()
                            + "），物理鼠标这段时间落在手机屏上");
                    return;
                }
                captureRetryCount++;
                if (!rootBox.isFocused()) {
                    rootBox.setDescendantFocusability(android.view.ViewGroup.FOCUS_BEFORE_DESCENDANTS);
                    rootBox.setFocusable(true);
                    rootBox.setFocusableInTouchMode(true);
                    rootBox.requestFocus();
                }
                try {
                    rootBox.requestPointerCapture();
                } catch (Throwable t) {
                    InputTrace.note("捕获", "重试申请抛错: " + t);
                }
                rootBox.postDelayed(this, 60);
            }
        };
        rootBox.post(captureRetryRunnable);
    }

    public void releasePointerCaptureIfHeld() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            if (rootBox != null && rootBox.hasPointerCapture()) {
                rootBox.post(() -> {
                    try {
                        rootBox.releasePointerCapture();
                        Log.i(TAG, "外设大脑已释放 PointerCapture 硬件捕获");
                    } catch (Throwable ignored) {}
                });
            }
        }
    }

    @Override
    public void onPointerCaptureChanged(boolean hasCapture) {
        super.onPointerCaptureChanged(hasCapture);
        Log.i(TAG, "系统 PointerCapture 捕获状态变化: hasCapture=" + hasCapture);
        CanghaiErrorCollector.logExt("鼠标硬件捕获状态: " + (hasCapture ? "已捕获 (无边界限制)" : "已释放"));
        // 捕获被收回的那一刻，系统指针就回到了手机屏上，空窗期越长越容易碰上边缘手势。
        // 所以不是「试一次就算」，而是持续重试直到夺回（最多 3 秒）。
        // 但虚拟设备绑屏之后不能再抢：那时指针本来就该由系统交给虚拟屏，
        // 抢回来只会和系统来回拉锯，点击全落在拉锯的空窗里。
        if (!hasCapture && isPhoneResumed && !VirtualMouse.hasDevice()
                && !VirtualMouse.captureBanned()) {
            forceCaptureRetry();
        }
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) {
            ensurePointerCapture();
        }
    }

    /**
     * 指针类外设事件（鼠标 / 触控板 / 轨迹球 / toolType=MOUSE）。
     *
     * 判据只看事件本身，绝不再看「有没有 device」：PointerCapture 和三星系统翻译
     * 都会把 device 抹掉，一按 device 判就会把「鼠标已经挂到 Moonlight 了」的
     * 事件放给手机主屏 —— 表现正是「手机上看不见鼠标，但按住不放会触发手机
     * 自己的长按识屏 / 询问 AI」。
     */
    private boolean isPointerPeripheralEvent(MotionEvent e) {
        if (e == null) return false;
        // 带 device 且是机身内置设备（屏幕当触控板用）：仍归手机自己，不动。
        // 只有「外接设备」和「device 被系统抹掉」这两种才归大脑。
        InputDevice dev = null;
        try { dev = e.getDevice(); } catch (Throwable ignored) {}
        if (dev != null && !dev.isExternal()) return false;

        int src = e.getSource();
        if ((src & InputDevice.SOURCE_MOUSE) == InputDevice.SOURCE_MOUSE) return true;
        if ((src & InputDevice.SOURCE_TOUCHPAD) == InputDevice.SOURCE_TOUCHPAD) return true;
        if ((src & InputDevice.SOURCE_TRACKBALL) == InputDevice.SOURCE_TRACKBALL) return true;
        try {
            for (int i = 0; i < e.getPointerCount(); i++) {
                if (e.getToolType(i) == MotionEvent.TOOL_TYPE_MOUSE) return true;
            }
        } catch (Throwable ignored) {}
        return false;
    }

    private String pointerDeviceName(InputDevice dev) {
        if (dev != null && dev.getName() != null && !dev.getName().isEmpty()) return dev.getName();
        return "外接鼠标";
    }

    /**
     * 物理鼠标唯一入口（PointerCapture 独占通道）。
     * 这里只把「设备发生了什么」交给外设大脑：不判断目标屏，也不画光标。
     */
    private boolean handleCapturedPointerEvent(MotionEvent event) {
        if (event == null) return false;

        // 大脑前面唯一记录点：系统通过独占捕获给了什么，先原样记一条。
        InputTrace.system("捕获", event);

        InputDevice dev = event.getDevice();
        String desc = (dev != null) ? dev.getDescriptor() : "";
        String devName = pointerDeviceName(dev);
        int action = event.getActionMasked();
        int actionBtn = event.getActionButton();
        int btnState = event.getButtonState();
        PeripheralBrain brain = PeripheralBrain.getInstance();

        if (action == MotionEvent.ACTION_MOVE || action == MotionEvent.ACTION_HOVER_MOVE) {
            float dx = event.getAxisValue(MotionEvent.AXIS_RELATIVE_X);
            float dy = event.getAxisValue(MotionEvent.AXIS_RELATIVE_Y);
            if (Math.abs(dx) > 0.05f || Math.abs(dy) > 0.05f) {
                brain.dispatchPhysicalMotion(dev, desc, devName, dx, dy, event.getX(), event.getY(), false);
            }
            return true;
        }

        if (action == MotionEvent.ACTION_SCROLL) {
            if (!brain.dispatchPhysicalScroll(dev, desc, devName,
                    event.getAxisValue(MotionEvent.AXIS_VSCROLL),
                    event.getAxisValue(MotionEvent.AXIS_HSCROLL))) {
                InputTrace.drop("捕获", event, "滚轮无归属外设");
            }
            return true;
        }

        if (action == MotionEvent.ACTION_BUTTON_PRESS || action == MotionEvent.ACTION_DOWN) {
            int btn = actionBtn;
            if (btn == 0) {
                if ((btnState & MotionEvent.BUTTON_SECONDARY) != 0) btn = MotionEvent.BUTTON_SECONDARY;
                else if ((btnState & MotionEvent.BUTTON_TERTIARY) != 0) btn = MotionEvent.BUTTON_TERTIARY;
                else if ((btnState & MotionEvent.BUTTON_PRIMARY) != 0) btn = MotionEvent.BUTTON_PRIMARY;
            }
            if (btn != 0 && !brain.dispatchPhysicalButton(dev, desc, devName, btn, true)) {
                InputTrace.drop("捕获", event, "按键无归属外设");
            }
            if (btn == MotionEvent.BUTTON_PRIMARY) externalPressActive = true;
            return true;
        }

        if (action == MotionEvent.ACTION_BUTTON_RELEASE || action == MotionEvent.ACTION_UP) {
            externalPressActive = false;
            brain.dispatchPhysicalButton(dev, desc, devName, actionBtn, false);
            return true;
        }

        return true;
    }

    /**
     * 外设大脑物理鼠标拦截与多屏路由调度 (完全免 Root，通用兼容通道)
     */
    @Override
    public boolean dispatchGenericMotionEvent(MotionEvent event) {
        if (event == null) return super.dispatchGenericMotionEvent(event);

        // 手柄摇杆 / 扳机等模拟量仍由大脑的闸门处理
        if (PeripheralBrain.getInstance().interceptGenericMotionEvent(event)) {
            return true;
        }

        // 指针类外设一律归大脑：不看 device 在不在，也不再把任何一帧 super 给
        // 手机 WebView。漏一帧 = 手机自己判一次长按。
        if (!isPointerPeripheralEvent(event)) return super.dispatchGenericMotionEvent(event);

        /* 独占通道已经接走的事件，这里连记录都不做：同一帧在三条入口各记一行，
         * 面板上就是「一次点击七八条」，根本看不出链路。 */
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                && rootBox != null && rootBox.hasPointerCapture()) {
            return true;
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) ensurePointerCapture();

        InputDevice dev = event.getDevice();
        String desc = (dev != null) ? dev.getDescriptor() : "";
        String devName = pointerDeviceName(dev);
        int action = event.getActionMasked();
        PeripheralBrain brain = PeripheralBrain.getInstance();

        InputTrace.system("通用", event);

        float relDx = event.getAxisValue(MotionEvent.AXIS_RELATIVE_X);
        float relDy = event.getAxisValue(MotionEvent.AXIS_RELATIVE_Y);
        if (Math.abs(relDx) < 0.001f && Math.abs(relDy) < 0.001f) {
            relDx = (lastPhysMouseX >= 0) ? event.getX() - lastPhysMouseX : 0f;
            relDy = (lastPhysMouseY >= 0) ? event.getY() - lastPhysMouseY : 0f;
            lastPhysMouseX = event.getX();
            lastPhysMouseY = event.getY();
        }

        if (action == MotionEvent.ACTION_HOVER_MOVE || action == MotionEvent.ACTION_MOVE) {
            if (Math.abs(relDx) > 0.05f || Math.abs(relDy) > 0.05f) {
                /* 外接屏在的时候，这里的 getX()/getY() 是手机屏坐标（系统把同一帧
                 * 又投给了前台 Activity），拿它去对齐外屏光标就会把指针拽回手机那套
                 * 坐标空间 —— 点桌面还能蒙中，点浮窗就必然打偏。所以只有没有外接屏、
                 * 鼠标确实在手机屏上时，绝对坐标才可信。 */
                boolean absTrusted = !ScreenRegistry.get().hasWired();
                brain.dispatchPhysicalMotion(dev, desc, devName, relDx, relDy,
                        event.getX(), event.getY(), absTrusted);
            }
            return true;
        }

        if (action == MotionEvent.ACTION_SCROLL) {
            if (!brain.dispatchPhysicalScroll(dev, desc, devName,
                    event.getAxisValue(MotionEvent.AXIS_VSCROLL),
                    event.getAxisValue(MotionEvent.AXIS_HSCROLL))) {
                InputTrace.drop("通用", event, "滚轮无归属外设");
            }
            return true;
        }

        return true;
    }

    /**
     * 外设大脑物理鼠标点击拦截与分发
     */
    @Override
    public boolean dispatchTouchEvent(MotionEvent event) {
        if (event != null && (event.getActionMasked() == MotionEvent.ACTION_UP || event.getActionMasked() == MotionEvent.ACTION_CANCEL)) {
            // 用户物理手指触控结束，离开屏幕后立即恢复硬件鼠标独占捕获
            if (rootBox != null) {
                rootBox.postDelayed(() -> ensurePointerCapture(), 120);
            }
        }
        if (event == null) return super.dispatchTouchEvent(event);

        // 手势粘滞：鼠标按下时可能已经丢了 PointerCapture，于是这一轮的抬起被系统
        // 翻译成「无设备的触摸」，会落到手机页面上。只要上一帧是我们往外屏按下的，
        // 这一帧的抬起/取消就必须补回外屏，否则外屏一直保持按下态。
        if (externalPressActive) {
            final int stickyAction = event.getActionMasked();
            if (stickyAction == MotionEvent.ACTION_UP
                    || stickyAction == MotionEvent.ACTION_CANCEL) {
                externalPressActive = false;
                PeripheralBrain.getInstance().releaseLastPress();
                return true;
            }
            if (stickyAction == MotionEvent.ACTION_MOVE && !isPointerPeripheralEvent(event)) {
                // 按键仍按着，这一帧拖动不能泄到手机上（坐标是手机屏的，泄过去就会
                // 在手机上拖动/触发边缘手势）。
                return true;
            }
        }

        if (isPointerPeripheralEvent(event)) {
            // 已被硬件独占捕获时完全由 handleCapturedPointerEvent 处理
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && rootBox != null && rootBox.hasPointerCapture()) {
                return true;
            }
            InputDevice dev = event.getDevice();
            String desc = (dev != null) ? dev.getDescriptor() : "";
            String devName = pointerDeviceName(dev);
            int action = event.getActionMasked();
            int actionBtn = event.getActionButton();
            int btnState = event.getButtonState();
            PeripheralBrain brain = PeripheralBrain.getInstance();

            InputTrace.system("触摸", event);

            if (action == MotionEvent.ACTION_DOWN || action == MotionEvent.ACTION_BUTTON_PRESS) {
                int btn = actionBtn;
                if (btn == 0) {
                    if ((btnState & MotionEvent.BUTTON_SECONDARY) != 0) btn = MotionEvent.BUTTON_SECONDARY;
                    else if ((btnState & MotionEvent.BUTTON_TERTIARY) != 0) btn = MotionEvent.BUTTON_TERTIARY;
                    else if ((btnState & MotionEvent.BUTTON_PRIMARY) != 0) btn = MotionEvent.BUTTON_PRIMARY;
                }
                if (btn != 0 && !brain.dispatchPhysicalButton(dev, desc, devName, btn, true)) {
                    InputTrace.drop("触摸", event, "按键无归属外设");
                }
                if (btn == MotionEvent.BUTTON_PRIMARY) externalPressActive = true;
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) ensurePointerCapture();
                return true;
            }

            if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_BUTTON_RELEASE) {
                externalPressActive = false;
                brain.dispatchPhysicalButton(dev, desc, devName, actionBtn, false);
                return true;
            }
            return true;
        }

        // 手指触摸一定带内置触摸屏设备；「没有 device 的触摸帧」只可能是系统把鼠标
        // 翻译成了触摸。分不清手指，只能放行，但必须在面板上留一行证据。
        try {
            if (event.getDevice() == null && event.isFromSource(InputDevice.SOURCE_TOUCHSCREEN)) {
                InputTrace.leak("触摸", event, "无设备的触摸帧（疑似系统翻译）");
            }
        } catch (Throwable ignored) {}

        return super.dispatchTouchEvent(event);
    }

    public void injectMoonlightKey(KeyEvent event) {
        if (event == null) return;
        // 零权限本地虚拟屏：本地物理键盘/手柄按键进程内直投，绝不走 Shizuku 注入。
        if (LocalMoonlightDisplay.get().dispatchPeripheralKey(event)) return;
        try {
            int targetId = (blackTouchTargetDisplay >= 0) ? blackTouchTargetDisplay : StreamTarget.UI_ID;
            ShizukuCmd.injectKeyEvent(event, targetId);
        } catch (Throwable ignored) {}
    }

    public void dispatchMoonlightPointerDelta(float rawDx, float rawDy) {
        // 零权限本地虚拟屏：复用桌面自己的光标坐标系，避免黑屏触控那套
        // cursorX/blackTouchTargetW 与虚拟屏分辨率不一致造成指针漂移。
        if (LocalMoonlightDisplay.get().dispatchPeripheralDelta(rawDx, rawDy)) return;
        float dist = (float) Math.hypot(rawDx, rawDy);
        float pointerSpeed = com.alkaidlab.sunshine.service.MouseCursorPrefs.getSpeed();
        float factor = 1.4f * (1.0f + Math.min(2.2f, dist / 15.0f)) * pointerSpeed;
        cursorX = Math.max(0, Math.min(blackTouchTargetW, cursorX + rawDx * factor));
        cursorY = Math.max(0, Math.min(blackTouchTargetH, cursorY + rawDy * factor));
        long t = android.os.SystemClock.uptimeMillis();
        if (t - lastBlackHoverAt >= 50L) {
            lastBlackHoverAt = t;
            MotionEvent hov = MotionEvent.obtain(t, t,
                    MotionEvent.ACTION_HOVER_MOVE, cursorX, cursorY, 0);
            hov.setSource(InputDevice.SOURCE_MOUSE);
            injectBlackMotion(hov);
            hov.recycle();
        }
    }

    public void triggerMoonlightClick() {
        if (LocalMoonlightDisplay.get().dispatchPeripheralClick(false)) return;
        blackTouchClick(cursorX, cursorY);
    }

    public void triggerMoonlightRightClick() {
        if (LocalMoonlightDisplay.get().dispatchPeripheralClick(true)) return;
        blackTouchRightClick(cursorX, cursorY);
    }

    public void triggerMoonlightScroll(float vScroll, float hScroll) {
        if (LocalMoonlightDisplay.get().dispatchPeripheralScroll(vScroll, hScroll)) return;
        blackTouchScroll(cursorX, cursorY, vScroll, hScroll);
    }

    // ---------------------------------------------------------------
    // 串流屏（Root / Shizuku 系统虚拟屏）的物理外设落地
    //
    // 这条链和 Moonlight 客户端自己发来的输入完全同源：都进
    // SunshineRuntimeManager.injectMotionEvent，由它按权限分叉到虚拟屏。
    // 光标坐标由本类按 StreamTarget 分辨率累计，不借用黑屏触控的目标屏状态。
    // ---------------------------------------------------------------
    private long lastStreamHoverAt = 0L;

    /** 串流屏分辨率：光标位置由外设大脑唯一持有，这里只提供边界。 */
    public int[] streamScreenSize() {
        int w = StreamTarget.get().getWidth();
        int h = StreamTarget.get().getHeight();
        if (w <= 0) w = 1920;
        if (h <= 0) h = 1080;
        return new int[]{w, h};
    }

    private MotionEvent buildStreamMouseFrame(int action, float x, float y,
                                              int buttonState, int actionButton,
                                              float vScroll, float hScroll) {
        long t = SystemClock.uptimeMillis();
        MotionEvent.PointerProperties[] pp = { new MotionEvent.PointerProperties() };
        pp[0].id = 0;
        pp[0].toolType = MotionEvent.TOOL_TYPE_MOUSE;
        MotionEvent.PointerCoords[] pc = { new MotionEvent.PointerCoords() };
        pc[0].x = x;
        pc[0].y = y;
        pc[0].pressure = 1f;
        pc[0].size = 1f;
        if (vScroll != 0f || hScroll != 0f) {
            pc[0].setAxisValue(MotionEvent.AXIS_VSCROLL, vScroll);
            pc[0].setAxisValue(MotionEvent.AXIS_HSCROLL, hScroll);
        }
        return MotionEvent.obtain(t, t, action, 1, pp, pc, 0, buttonState,
                1f, 1f, 0, 0, InputDevice.SOURCE_MOUSE, 0);
    }

    private void injectStreamFrame(MotionEvent ev) {
        if (ev == null) return;
        try {
            // 这一帧是大脑决定的落地：系统原值 + 落点已经在入口记过，注入端不再重复记录。
            com.alkaidlab.sunshine.service.SunshineRuntimeManager.getInstance()
                    .injectMotionEventFromPeripheral(ev);
        } catch (Throwable ignored) {
        } finally {
            ev.recycle();
        }
    }

    /** 光标位置只由外设大脑持有，这里仅把这一帧搬到串流屏，不自己记账。 */
    public void deliverStreamPointerAt(float x, float y) {
        long now = SystemClock.uptimeMillis();
        if (now - lastStreamHoverAt < 8L) return;
        lastStreamHoverAt = now;
        injectStreamFrame(buildStreamMouseFrame(MotionEvent.ACTION_HOVER_MOVE, x, y, 0, 0, 0f, 0f));
    }

    public void deliverStreamButton(float x, float y, int btn, boolean down) {
        if (btn == MotionEvent.BUTTON_PRIMARY) {
            injectStreamFrame(buildStreamMouseFrame(
                    down ? MotionEvent.ACTION_DOWN : MotionEvent.ACTION_UP,
                    x, y, down ? MotionEvent.BUTTON_PRIMARY : 0, 0, 0f, 0f));
            return;
        }
        injectStreamFrame(buildStreamMouseFrame(
                down ? MotionEvent.ACTION_BUTTON_PRESS : MotionEvent.ACTION_BUTTON_RELEASE,
                x, y, down ? btn : 0, btn, 0f, 0f));
    }

    public void deliverStreamScroll(float x, float y, float vScroll, float hScroll) {
        injectStreamFrame(buildStreamMouseFrame(MotionEvent.ACTION_SCROLL, x, y, 0, 0, vScroll, hScroll));
    }

    public void updatePhoneCursor(float x, float y) {
        phoneCursorX = x;
        phoneCursorY = y;
        // 所有映射路径的最后一道闸：只夹在屏幕自身的 [0,w]×[0,h] 内，不再内缩。
        clampPhoneCursor();
        if (phoneCursorView == null) return;
        if (phoneCursorView.getVisibility() != View.VISIBLE) {
            // 手机自身屏幕（displayId=0）读的是全局指针尺寸。光标第一次出现前强制对齐一次，
            // 避免进程重启后拿到别处遗留的小缩放，显示成一个小点。
            try {
                com.alkaidlab.sunshine.service.MouseCursorPrefs.setContinuousScaleForDisplay(
                        this, 0, CanghaiStore.cursorSize(this) / 28f);
            } catch (Throwable ignored) {}
            phoneCursorView.setVisibility(View.VISIBLE);
            phoneCursorView.setElevation(100000f);
            phoneCursorView.bringToFront();
            phoneCursorView.refresh();
        }
        phoneCursorView.setCursorPosition(phoneCursorX, phoneCursorY);
    }

    public float getPhoneCursorX() { return phoneCursorX; }

    public float getPhoneCursorY() { return phoneCursorY; }

    /** 光标离开手机屏时只隐藏这一份，不销毁、不重置位置。 */
    public void hidePhoneCursor() {
        if (phoneCursorView != null && phoneCursorView.getVisibility() == View.VISIBLE) {
            phoneCursorView.setVisibility(View.GONE);
        }
    }

    /** 虚拟外设刚插到手机主屏：在当前光标位置发一圈向外扩散的到达波纹。 */
    public void pulsePhoneCursorArrival() {
        runOnUiThread(() -> {
            try {
                if (phoneCursorView == null || phoneCursorView.getParent() == null) return;
                float x = phoneCursorX > 0 ? phoneCursorX
                        : getResources().getDisplayMetrics().widthPixels / 2f;
                float y = phoneCursorY > 0 ? phoneCursorY
                        : getResources().getDisplayMetrics().heightPixels / 2f;
                phoneCursorView.showArrivalRipple(x, y);
            } catch (Throwable ignored) {}
        });
    }

    public void triggerPhoneClick(float x, float y, boolean isRight) {
        runOnUiThread(() -> {
            try {
                if (webPhoneHome == null) return;
                float density = getResources().getDisplayMetrics().density;
                float cssX = x / density;
                float cssY = y / density;
                if (isRight) {
                    String js = "window.__chContextMenuAt ? window.__chContextMenuAt(" + cssX + ", " + cssY + ") : (window.__chClickAt && window.__chClickAt(" + cssX + ", " + cssY + "));";
                    webPhoneHome.evaluateJavascript(js, null);
                } else {
                    String js = "window.__chClickAt && window.__chClickAt(" + cssX + ", " + cssY + ");";
                    webPhoneHome.evaluateJavascript(js, null);
                }
            } catch (Throwable ignored) {}
        });
    }

    // ==============================================================
    // 手机主屏落点：虚拟外设插在 PHONE 时，把事件投进 phonehome WebView 的视图树。
    // 这是同进程直投，不经过系统 InputDispatcher，也不需要任何权限。
    // ==============================================================

    /** 虚拟外设插在手机主屏时，大脑把该外设的按键交给这里。 */
    public boolean deliverPhoneRemoteKey(KeyEvent event) {
        if (event == null || webPhoneHome == null) return false;
        runOnUiThread(() -> {
            try {
                android.webkit.WebView web = webPhoneHome;
                if (web != null) web.dispatchKeyEvent(event);
            } catch (Throwable ignored) {}
        });
        return true;
    }

    /** 虚拟外设插在手机主屏时，大脑把该外设的 MotionEvent 交给这里。 */
    public boolean deliverPhoneRemoteMotion(MotionEvent event) {
        if (event == null || webPhoneHome == null) return false;
        final MotionEvent copy = MotionEvent.obtain(event);
        final int srcW = StreamTarget.get().getWidth();
        final int srcH = StreamTarget.get().getHeight();
        final int action = copy.getActionMasked();
        final boolean generic = action == MotionEvent.ACTION_HOVER_MOVE
                || action == MotionEvent.ACTION_SCROLL
                || action == MotionEvent.ACTION_BUTTON_PRESS
                || action == MotionEvent.ACTION_BUTTON_RELEASE;
        runOnUiThread(() -> {
            try {
                android.webkit.WebView web = webPhoneHome;
                if (web == null) return;
                int vw = web.getWidth();
                int vh = web.getHeight();
                // 源坐标是串流画布像素，线性映射到手机主屏 WebView 的实际尺寸。
                float x = copy.getX();
                float y = copy.getY();
                if (vw > 0 && vh > 0 && srcW > 0 && srcH > 0) {
                    x = x * vw / srcW;
                    y = y * vh / srcH;
                }
                if (vw > 0) x = Math.max(0, Math.min(vw, x));
                if (vh > 0) y = Math.max(0, Math.min(vh, y));
                copy.setLocation(x, y);

                // 手机屏的鼠标是自绘 View，WebView 收到 MotionEvent 不会画指针。
                // 跨屏过来必须显式同步绝对坐标，否则串流侧「断开」了、手机侧却看不见鼠标。
                updatePhoneCursor(x, y);
                long now = SystemClock.uptimeMillis();
                if (now - lastPhoneHoverAt >= 45L) {
                    lastPhoneHoverAt = now;
                    float density = getResources().getDisplayMetrics().density;
                    final float cssX = x / (density > 0 ? density : 1f);
                    final float cssY = y / (density > 0 ? density : 1f);
                    web.evaluateJavascript("window.__chHoverAt && window.__chHoverAt("
                            + cssX + ", " + cssY + ");", null);
                }

                if (!generic && copy.isFromSource(InputDevice.SOURCE_MOUSE)) {
                    copy.setSource(InputDevice.SOURCE_TOUCHSCREEN);
                }
                if (generic) web.dispatchGenericMotionEvent(copy);
                else web.dispatchTouchEvent(copy);
            } catch (Throwable ignored) {
            } finally {
                copy.recycle();
            }
        });
        return true;
    }

    /** 物理外设的点击 / 滚轮绑到手机主屏时的统一落点。 */
    public void triggerPhoneClickAtCursor(boolean isRight) {
        triggerPhoneClick(phoneCursorX, phoneCursorY, isRight);
    }

    public void triggerPhoneScroll(float vScroll, float hScroll) {
        phoneMouseScroll(phoneCursorX, phoneCursorY, vScroll, hScroll);
    }

    // ---------------------------------------------------------------
    // 手机屏：物理鼠标的真实按下 / 拖动 / 抬起 / 滚轮
    //
    // 手机首页是 WebView，点击、拖拽（拖卡片）、滚轮滚动全部由 Chromium
    // 自己判定。所以这里只把鼠标语义原样投进去，不自己猜点击、不调 JS。
    // ---------------------------------------------------------------
    private long phoneMouseDownTime = 0L;
    private boolean phoneMousePressed = false;

    private MotionEvent buildPhoneMouseEvent(int action, float x, float y) {
        android.webkit.WebView web = webPhoneHome;
        if (web == null) return null;
        long now = SystemClock.uptimeMillis();
        if (action == MotionEvent.ACTION_DOWN) phoneMouseDownTime = now;
        if (phoneMouseDownTime == 0L) phoneMouseDownTime = now;
        float lx = x - web.getLeft() - web.getTranslationX();
        float ly = y - web.getTop() - web.getTranslationY();
        MotionEvent e = MotionEvent.obtain(phoneMouseDownTime, now, action, lx, ly, 0);
        e.setSource(InputDevice.SOURCE_MOUSE);
        return e;
    }

    public void phoneMouseDown(float x, float y) {
        MotionEvent e = buildPhoneMouseEvent(MotionEvent.ACTION_DOWN, x, y);
        if (e == null) return;
        phoneMousePressed = true;
        try { webPhoneHome.dispatchTouchEvent(e); } catch (Throwable ignored) {}
        finally { e.recycle(); }
    }

    public void phoneMouseMove(float x, float y) {
        if (!phoneMousePressed) return;
        MotionEvent e = buildPhoneMouseEvent(MotionEvent.ACTION_MOVE, x, y);
        if (e == null) return;
        try { webPhoneHome.dispatchTouchEvent(e); } catch (Throwable ignored) {}
        finally { e.recycle(); }
    }

    public void phoneMouseUp(float x, float y) {
        if (!phoneMousePressed) return;
        phoneMousePressed = false;
        MotionEvent e = buildPhoneMouseEvent(MotionEvent.ACTION_UP, x, y);
        if (e == null) return;
        try { webPhoneHome.dispatchTouchEvent(e); } catch (Throwable ignored) {}
        finally { e.recycle(); }
    }

    /**
     * 取消手机上残留的那次「按住不放」。
     *
     * 手机首页是 WebView：只要有一次 ACTION_DOWN 没等到 UP，Chromium 就会自己
     * 判长按（选文字 / 唤起识屏 AI）。外设已经挂到别的屏了，这次按下就必须当场取消。
     */
    public void cancelPhoneMousePress() {
        if (!phoneMousePressed) return;
        phoneMousePressed = false;
        final android.webkit.WebView web = webPhoneHome;
        if (web == null) return;
        MotionEvent e = buildPhoneMouseEvent(MotionEvent.ACTION_CANCEL, phoneCursorX, phoneCursorY);
        if (e == null) return;
        try { web.dispatchTouchEvent(e); } catch (Throwable ignored) {}
        finally { e.recycle(); }
    }

    /** 光标下的滚动容器自己滚；MotionEvent 的轴值无法公开设置，改在页面内按元素滚。 */
    public void phoneMouseScroll(float x, float y, float vScroll, float hScroll) {
        final android.webkit.WebView web = webPhoneHome;
        if (web == null) return;
        float density = getResources().getDisplayMetrics().density;
        final float cssX = x / (density > 0 ? density : 1f);
        final float cssY = y / (density > 0 ? density : 1f);
        final int dy = (int) (-vScroll * 140f);
        final int dx = (int) (-hScroll * 140f);
        if (dy == 0 && dx == 0) return;
        web.post(() -> {
            try {
                web.evaluateJavascript("(function(){"
                        + "var el=document.elementFromPoint(" + cssX + "," + cssY + ");"
                        + "while(el&&el!==document.body){"
                        + "var s=getComputedStyle(el);"
                        + "if(/(auto|scroll)/.test(s.overflowY)&&el.scrollHeight>el.clientHeight){el.scrollTop+=" + dy + ";return;}"
                        + "if(/(auto|scroll)/.test(s.overflowX)&&el.scrollWidth>el.clientWidth){el.scrollLeft+=" + dx + ";return;}"
                        + "el=el.parentElement;}"
                        + "window.scrollBy(" + dx + "," + dy + ");})();", null);
            } catch (Throwable ignored) {}
        });
    }

    private long lastPhoneHoverAt = 0L;

    /**
     * 手机屏上光标的可去范围 = 这块屏的完整物理像素 [0,w] × [0,h]。
     *
     * <p>以前这里内缩出「状态栏 / 上滑条 / 左右返回手势」三条安全区，代价是自绘指针
     * 走到离边缘还有几十 px 的地方就撞上一堵看不见的墙 —— 而系统指针（以及系统自己
     * 记录的落点）照样能走到最边上，两边从此各算各的，越走越错位。
     * 既然系统指针本来就能到每一个像素，我们没有任何理由比它小一圈：
     * 缩进全部删掉，边界就是屏幕本身。
     */
    private void clampPhoneCursor() {
        int[] b = phoneBounds();
        phoneCursorX = Math.max(0f, Math.min(b[0], phoneCursorX));
        phoneCursorY = Math.max(0f, Math.min(b[1], phoneCursorY));
    }

    /** 手机屏的真实像素尺寸（含状态栏 / 挖孔区，不含任何缩进）。 */
    private int[] phoneBounds() {
        int w = 0, h = 0;
        try {
            Display disp = getWindowManager().getDefaultDisplay();
            DisplayMetrics m = new DisplayMetrics();
            disp.getRealMetrics(m);
            w = m.widthPixels;
            h = m.heightPixels;
        } catch (Throwable ignored) { }
        if (w <= 0 || h <= 0) {
            DisplayMetrics m = getResources().getDisplayMetrics();
            w = m.widthPixels;
            h = m.heightPixels;
        }
        return new int[]{ w, h };
    }

    private float dpToPx(float dp) {
        return dp * getResources().getDisplayMetrics().density;
    }

    public void applyPhoneCursorDelta(float rawDx, float rawDy) {
        phoneCursorX = phoneCursorX + rawDx;
        phoneCursorY = phoneCursorY + rawDy;
        clampPhoneCursor();
        updatePhoneCursor(phoneCursorX, phoneCursorY);
        pushPhoneHoverAt();
    }

    /**
     * 系统指针没被独占捕获时，指针的绝对位置就是真实位置：一步对齐。
     * 相对量一旦丢帧就会永久差一截，绝对位置不会。
     */
    public void movePhoneCursorTo(float x, float y) {
        phoneCursorX = x;
        phoneCursorY = y;
        clampPhoneCursor();
        updatePhoneCursor(phoneCursorX, phoneCursorY);
        pushPhoneHoverAt();
    }

    /** 把手机光标的当前位置同步给首页 Web（hover 高亮），45ms 采样。 */
    private void pushPhoneHoverAt() {
        long now = SystemClock.uptimeMillis();
        if (webPhoneHome == null || now - lastPhoneHoverAt < 45L) return;
        lastPhoneHoverAt = now;
        final float density = getResources().getDisplayMetrics().density;
        final float cssX = phoneCursorX / (density > 0 ? density : 1f);
        final float cssY = phoneCursorY / (density > 0 ? density : 1f);
        final float px = phoneCursorX;
        final float py = phoneCursorY;
        runOnUiThread(() -> {
            /* 鼠标被我们独占之后，系统不再向手机屏派发悬停帧 —— 页面上的 CSS :hover
             * 会整体失效（Dock 放大、图标高亮全没）。所以这里自己补一帧 HOVER_MOVE，
             * 让浏览器仍然认为指针就在这个点上。 */
            try {
                if (RootInputBridge.isRawGrabbed()) dispatchPhoneHover(px, py);
            } catch (Throwable ignored) { }
        });
        webPhoneHome.post(() -> {
            try {
                if (webPhoneHome != null) {
                    webPhoneHome.evaluateJavascript("window.__chHoverAt && window.__chHoverAt(" + cssX + ", " + cssY + ");", null);
                }
            } catch (Throwable ignored) {}
        });
    }

    /** 独占鼠标期间自补悬停：系统不发了，我们自己发。 */
    private void dispatchPhoneHover(float x, float y) {
        android.webkit.WebView web = webPhoneHome;
        if (web == null || phoneMousePressed) return;
        MotionEvent e = buildPhoneMouseEvent(MotionEvent.ACTION_HOVER_MOVE, x, y);
        if (e == null) return;
        try { web.dispatchTouchEvent(e); } catch (Throwable ignored) {}
        finally { e.recycle(); }
    }

    private boolean handleMoonlightTouchEvent(View v, MotionEvent event) {
        int pointerCount = event.getPointerCount();
        int actionMasked = event.getActionMasked();
        switch (actionMasked) {
            case MotionEvent.ACTION_DOWN: {
                if (v.getParent() != null) v.getParent().requestDisallowInterceptTouchEvent(true);
                resetTouchState();
                InputTrace.touchpadTouch(true);
                /* 不要再重置 cursorX/cursorY！光标位置必须跨手势保留，
                   否则每次按下（包括单指点击、双指右键）鼠标都会跳回屏幕中心。 */
                gestureStartTime = System.currentTimeMillis();
                touchDownX = event.getX();
                touchDownY = event.getY();
                touchLastX = touchDownX;
                touchLastY = touchDownY;
                touchDownTime = gestureStartTime;
                isDragMotion = false;
                lastBlackHoverAt = 0L;
                return true;
            }

            case MotionEvent.ACTION_POINTER_DOWN: {
                if (v.getParent() != null) v.getParent().requestDisallowInterceptTouchEvent(true);
                if (pointerCount > gestureMaxPointers) gestureMaxPointers = pointerCount;
                if (pointerCount >= 2) twoFingerActive = true;
                if (pointerCount == 2) {
                    float midX = (event.getX(0) + event.getX(1)) / 2f;
                    float midY = (event.getY(0) + event.getY(1)) / 2f;
                    twoFingerDownTime = System.currentTimeMillis();
                    twoFingerStartX = midX;
                    twoFingerStartY = midY;
                    twoFingerLastX = midX;
                    twoFingerLastY = midY;
                    twoFingerMoved = false;
                } else if (pointerCount >= 3) {
                    float sx = 0f, sy = 0f;
                    for (int i = 0; i < pointerCount; i++) {
                        sx += event.getX(i);
                        sy += event.getY(i);
                    }
                    multiFingerStartX = sx / pointerCount;
                    multiFingerStartY = sy / pointerCount;
                }
                return true;
            }

            case MotionEvent.ACTION_MOVE: {
                if (v.getParent() != null) v.getParent().requestDisallowInterceptTouchEvent(true);
                if (pointerCount > gestureMaxPointers) gestureMaxPointers = pointerCount;

                if (pointerCount >= 3) {
                    float sx = 0f, sy = 0f;
                    for (int i = 0; i < pointerCount; i++) {
                        sx += event.getX(i);
                        sy += event.getY(i);
                    }
                    if (Math.hypot(sx / pointerCount - multiFingerStartX,
                            sy / pointerCount - multiFingerStartY) > 35) gestureMoved = true;
                    return true;
                }

                if (pointerCount == 2) {
                    float midX = (event.getX(0) + event.getX(1)) / 2f;
                    float midY = (event.getY(0) + event.getY(1)) / 2f;
                    if (Math.hypot(midX - twoFingerStartX, midY - twoFingerStartY) > 16) {
                        twoFingerMoved = true;
                        gestureMoved = true;
                    }
                    float rawScrollDx = midX - twoFingerLastX;
                    float rawScrollDy = midY - twoFingerLastY;
                    float[] sDelta = remapTouchDeltaForRotation(rawScrollDx, rawScrollDy);
                    float dx = sDelta[0];
                    float dy = sDelta[1];
                    long now = System.currentTimeMillis();
                    if ((Math.abs(dx) > 3 || Math.abs(dy) > 3)
                            && now - lastTwoFingerSwipeTime > 30) {
                        lastTwoFingerSwipeTime = now;
                        blackTouchScroll(cursorX, cursorY, dy / 20f, -dx / 20f);
                        InputTrace.touchpadScroll(cursorX, cursorY, dy / 20f, -dx / 20f);
                        twoFingerLastX = midX;
                        twoFingerLastY = midY;
                    }
                    return true;
                }

                if (pointerCount == 1) {
                    if (gestureMaxPointers >= 2 || twoFingerActive) return true;
                    float x = event.getX();
                    float y = event.getY();
                    float[] mDelta = remapTouchDeltaForRotation(x - touchLastX, y - touchLastY);
                    float rawDx = mDelta[0];
                    float rawDy = mDelta[1];
                    touchLastX = x;
                    touchLastY = y;
                    float dist = (float) Math.hypot(rawDx, rawDy);
                    /* 指针速度由手机端 / 外屏设置共用，真的影响移动距离 */
                    float pointerSpeed = com.alkaidlab.sunshine.service.MouseCursorPrefs.getSpeed();
                    float factor = 1.4f * (1.0f + Math.min(2.2f, dist / 15.0f)) * pointerSpeed;
                    if (Math.abs(x - touchDownX) > 16 || Math.abs(y - touchDownY) > 16) {
                        isDragMotion = true;
                    }
                    cursorX = Math.max(0, Math.min(blackTouchTargetW, cursorX + rawDx * factor));
                    cursorY = Math.max(0, Math.min(blackTouchTargetH, cursorY + rawDy * factor));
                    InputTrace.touchpadMotion(cursorX, cursorY, rawDx, rawDy);
                    long t = android.os.SystemClock.uptimeMillis();
                    if (t - lastBlackHoverAt >= 50L) {
                        lastBlackHoverAt = t;
                        MotionEvent hov = MotionEvent.obtain(t, t,
                                MotionEvent.ACTION_HOVER_MOVE, cursorX, cursorY, 0);
                        hov.setSource(InputDevice.SOURCE_MOUSE);
                        injectBlackMotion(hov);
                        hov.recycle();
                    }
                }
                return true;
            }

            case MotionEvent.ACTION_POINTER_UP:
            case MotionEvent.ACTION_UP: {
                long duration = System.currentTimeMillis() - gestureStartTime;
                if (!multiFingerActionTriggered && !gestureMoved && duration < 550) {
                    if (gestureMaxPointers >= 4) {
                        multiFingerActionTriggered = true;
                        exitBlackScreenMode();
                        resetTouchState();
                        return true;
                    } else if (gestureMaxPointers == 3) {
                        /* 三指轻点：模拟手机系统返回键，派发给对应外屏或 Moonlight */
                        multiFingerActionTriggered = true;
                        triggerThreeFingerBackAction();
                        resetTouchState();
                        return true;
                    } else if (gestureMaxPointers == 2 && !twoFingerMoved) {
                        multiFingerActionTriggered = true;
                        blackTouchRightClick(cursorX, cursorY);
                        InputTrace.touchpadClick(cursorX, cursorY, true);
                        CanghaiErrorCollector.logExt("Moonlight触控: 双指右键 @ (" + (int)cursorX + ", " + (int)cursorY + ")");
                    } else if (gestureMaxPointers == 1
                            && actionMasked == MotionEvent.ACTION_UP) {
                        float x = event.getX(), y = event.getY();
                        if (!isDragMotion && Math.hypot(x - touchDownX, y - touchDownY) < 35) {
                            blackTouchClick(cursorX, cursorY);
                            InputTrace.touchpadClick(cursorX, cursorY, false);
                            CanghaiErrorCollector.logExt("Moonlight触控: 单指左键 @ (" + (int)cursorX + ", " + (int)cursorY + ")");
                        }
                    }
                    InputTrace.touchpadTouch(false);
                }
                if (actionMasked == MotionEvent.ACTION_UP) {
                    if (v.getParent() != null) {
                        v.getParent().requestDisallowInterceptTouchEvent(false);
                    }
                    resetTouchState();
                }
                return true;
            }

            case MotionEvent.ACTION_CANCEL: {
                if (v.getParent() != null) {
                    v.getParent().requestDisallowInterceptTouchEvent(false);
                }
                resetTouchState();
                InputTrace.touchpadTouch(false);
                return true;
            }
        }
        return true;
    }


    /** 目标是 Moonlight 串流屏 / 物理外接屏时，用带 displayId 的通用注入通道，
     *  不再依赖 DeXPresentation（它只对应 Aosen_VD 那一块虚拟屏）。 */
    private boolean blackTouchNeedsGenericPath() {
        /* 等待目标（还没连屏 / 屏刚拔掉）时也必须走通用路径：
           四指退出的手势判定在那里，而且不该在已经不受控的虚拟屏上继续画自绘光标。 */
        if (blackTouchTargetDisplay < 0) return true;
        if (blackTouchViaMoonlight) return true;
        if (dexPresentation == null || !dexPresentation.isShowing()) return true;
        int pDispId = (dexPresentation.getDisplay() != null) ? dexPresentation.getDisplay().getDisplayId() : -1;
        int vDispId = dexPresentation.getVirtualDisplayId();
        // 若黑屏触控目标就是当前的虚拟屏或外接物理直显屏，由 DeXPresentation 直接自绘光标并分发
        if (blackTouchTargetDisplay == vDispId || (pDispId > 0 && blackTouchTargetDisplay == pDispId)) {
            return false;
        }
        return true;
    }

    private boolean handleTouchpadEvent(View v, MotionEvent event) {
        /* 设备卡指定屏 / Moonlight 串流 / 物理外接屏：走通用注入通道。 */
        if (isBlackScreenActive() && blackTouchNeedsGenericPath()) {
            return handleMoonlightTouchEvent(v, event);
        }
        if (dexPresentation == null || !dexPresentation.isShowing()) {
            return true;
        }

        int pointerCount = event.getPointerCount();
        int actionMasked = event.getActionMasked();

        switch (actionMasked) {
            case MotionEvent.ACTION_DOWN: {
                if (v.getParent() != null) {
                    v.getParent().requestDisallowInterceptTouchEvent(true);
                }
                resetTouchState();
                InputTrace.touchpadTouch(true);
                /* 黑屏模式下光标坐标是跨手势累积的，不要在每次按下时从外屏
                   「上一次鼠标位置」重新取一遍（那往往是屏幕中心），否则点一下就跳中心。 */
                if (!isBlackScreenActive() && dexPresentation != null) {
                    cursorX = dexPresentation.getLastMouseX();
                    cursorY = dexPresentation.getLastMouseY();
                }
                gestureStartTime = System.currentTimeMillis();
                touchDownX = event.getX();
                touchDownY = event.getY();
                touchLastX = touchDownX;
                touchLastY = touchDownY;
                touchDownTime = gestureStartTime;
                isDragMotion = false;
                return true;
            }


            case MotionEvent.ACTION_POINTER_DOWN: {
                if (v.getParent() != null) {
                    v.getParent().requestDisallowInterceptTouchEvent(true);
                }
                if (pointerCount > gestureMaxPointers) {
                    gestureMaxPointers = pointerCount;
                }
                if (pointerCount >= 2) {
                    twoFingerActive = true;
                }
                if (pointerCount == 2) {
                    float midX = (event.getX(0) + event.getX(1)) / 2f;
                    float midY = (event.getY(0) + event.getY(1)) / 2f;
                    twoFingerDownTime = System.currentTimeMillis();
                    twoFingerStartX = midX;
                    twoFingerStartY = midY;
                    twoFingerLastX = midX;
                    twoFingerLastY = midY;
                    twoFingerMoved = false;
                } else if (pointerCount >= 3) {
                    float sumX = 0f, sumY = 0f;
                    for (int i = 0; i < pointerCount; i++) {
                        sumX += event.getX(i);
                        sumY += event.getY(i);
                    }
                    multiFingerStartX = sumX / pointerCount;
                    multiFingerStartY = sumY / pointerCount;
                }
                return true;
            }


            case MotionEvent.ACTION_MOVE: {
                // 触控区手指移动绝不触发外层页面滚?
                if (v.getParent() != null) {
                    v.getParent().requestDisallowInterceptTouchEvent(true);
                }
                if (pointerCount > gestureMaxPointers) {
                    gestureMaxPointers = pointerCount;
                }


                if (pointerCount >= 3) {
                    float sumX = 0f, sumY = 0f;
                    for (int i = 0; i < pointerCount; i++) {
                        sumX += event.getX(i);
                        sumY += event.getY(i);
                    }
                    float avgX = sumX / pointerCount;
                    float avgY = sumY / pointerCount;
                    if (Math.hypot(avgX - multiFingerStartX, avgY - multiFingerStartY) > 35) {
                        gestureMoved = true;
                    }
                    return true;
                }


                if (pointerCount == 2) {
                    float midX = (event.getX(0) + event.getX(1)) / 2f;
                    float midY = (event.getY(0) + event.getY(1)) / 2f;
                    float totalDist = (float) Math.hypot(midX - twoFingerStartX, midY - twoFingerStartY);
                    if (totalDist > 16) {
                        twoFingerMoved = true;
                        gestureMoved = true;
                    }
                    float[] sDelta = remapTouchDeltaForRotation(midX - twoFingerLastX, midY - twoFingerLastY);
                    float dx = sDelta[0];
                    float dy = sDelta[1];
                    long now = System.currentTimeMillis();
                    if ((Math.abs(dx) > 3 || Math.abs(dy) > 3) && (now - lastTwoFingerSwipeTime > 30)) {
                        lastTwoFingerSwipeTime = now;
                        // 双指滑动走标准滚轮 MotionEvent 注入，零 shell 命令，顺畅自然滚动
                        float vScroll = dy / 20f;
                        float hScroll = -(dx / 20f);
                        dexPresentation.performScrollAt(cursorX, cursorY, vScroll, hScroll);
                        InputTrace.touchpadScroll(cursorX, cursorY, vScroll, hScroll);
                        twoFingerLastX = midX;
                        twoFingerLastY = midY;
                    }
                    return true;
                }


                // 单指滑动光标
                if (pointerCount == 1) {
                    if (gestureMaxPointers >= 2 || twoFingerActive) {
                        // 多指操作释放过程中残留的单指，禁止拖动光标，防误触
                        return true;
                    }
                    float x = event.getX();
                    float y = event.getY();
                    float[] mDelta = remapTouchDeltaForRotation(x - touchLastX, y - touchLastY);
                    float rawDx = mDelta[0];
                    float rawDy = mDelta[1];
                    touchLastX = x;
                    touchLastY = y;


                    // 连续高精度动态加速度曲线（消除速度阶梯跳变，微调精准，飞划迅速，手感如同笔记本高精度触控板）
                    float dist = (float) Math.hypot(rawDx, rawDy);
                    float accel = 1.0f + Math.min(2.2f, dist / 15.0f);
                    float baseSensitivity = 1.4f;
                    float pointerSpeed = com.alkaidlab.sunshine.service.MouseCursorPrefs.getSpeed();
                    float factor = baseSensitivity * accel * pointerSpeed;
                    float dx = rawDx * factor;
                    float dy = rawDy * factor;


                    if (Math.abs(x - touchDownX) > 16 || Math.abs(y - touchDownY) > 16) {
                        isDragMotion = true;
                    }


                    cursorX = Math.max(0, Math.min(externalWidth, cursorX + dx));
                    cursorY = Math.max(0, Math.min(externalHeight, cursorY + dy));


                    /* 黑屏触控：走专用通道 —— 自绘光标强制落在外屏、系统箭头压住、
                       同时给外屏应用补 hover 事件。普通触控板仍用老路径。 */
                    if (isBlackScreenActive()) {
                        dexPresentation.moveBlackTouchCursor(cursorX, cursorY);
                    } else {
                        dexPresentation.updateCursor(cursorX, cursorY);
                    }
                    InputTrace.touchpadMotion(cursorX, cursorY, rawDx, rawDy);
                    return true;
                }
                return true;
            }


            case MotionEvent.ACTION_POINTER_UP: {
                long duration = System.currentTimeMillis() - gestureStartTime;
                if (!gestureMoved && duration < 550 && !multiFingerActionTriggered) {
                    if (gestureMaxPointers >= 4) {
                        // 四指一起点击：在黑屏触控模式下退出黑?
                        if (v == viewBlackScreenOverlay || isBlackScreenActive()) {
                            multiFingerActionTriggered = true;
                            exitBlackScreenMode();
                            return true;
                        }
                    } else if (gestureMaxPointers == 3) {
                        // 三指轻点：模拟手机系统返回键
                        multiFingerActionTriggered = true;
                        triggerThreeFingerBackAction();
                        return true;
                    } else if (gestureMaxPointers == 2 && !twoFingerMoved) {
                        // 双指轻按松开：触发右键
                        multiFingerActionTriggered = true;
                        dexPresentation.performRightClickAt(cursorX, cursorY);
                        InputTrace.touchpadClick(cursorX, cursorY, true);
                        CanghaiErrorCollector.logExt("黑屏触控: 双指右键 @ (" + (int)cursorX + ", " + (int)cursorY + ")");
                        return true;
                    }
                }
                return true;
            }


            case MotionEvent.ACTION_UP: {
                if (v.getParent() != null) {
                    v.getParent().requestDisallowInterceptTouchEvent(false);
                }
                long duration = System.currentTimeMillis() - gestureStartTime;
                if (!multiFingerActionTriggered && !gestureMoved && duration < 550) {
                    if (gestureMaxPointers >= 4) {
                        if (v == viewBlackScreenOverlay || isBlackScreenActive()) {
                            multiFingerActionTriggered = true;
                            exitBlackScreenMode();
                            resetTouchState();
                            return true;
                        }
                    } else if (gestureMaxPointers == 3) {
                        multiFingerActionTriggered = true;
                        triggerThreeFingerBackAction();
                        resetTouchState();
                        return true;
                    } else if (gestureMaxPointers == 2 && !twoFingerMoved) {
                        multiFingerActionTriggered = true;
                        dexPresentation.performRightClickAt(cursorX, cursorY);
                        resetTouchState();
                        return true;
                    } else if (gestureMaxPointers == 1) {
                        float x = event.getX();
                        float y = event.getY();
                        float totalDist = (float) Math.hypot(x - touchDownX, y - touchDownY);
                        if (!isDragMotion && totalDist < 35 && duration < 450) {
                            dexPresentation.performClickAt(cursorX, cursorY);
                        }
                    }
                    InputTrace.touchpadTouch(false);
                }
                resetTouchState();
                return true;
            }


            case MotionEvent.ACTION_CANCEL: {
                if (v.getParent() != null) {
                    v.getParent().requestDisallowInterceptTouchEvent(false);
                }
                resetTouchState();
                return true;
            }
        }
        return true;
    }


    private boolean isRealExternalDisplay(int displayId) {
        if (displayId == Display.DEFAULT_DISPLAY || displayId == 1) return false;
        if (displayManager == null) return false;
        Display d = displayManager.getDisplay(displayId);
        if (d == null) return false;
        if (StreamTarget.get().isStreamTarget(displayId)) return false;
        String name = d.getName();
        if (name != null && name.contains("Aosen_VD")) return false;
        return name == null;
    }


    /**
     * 可挂载的物理外接屏（自动挂载与手动「连接」共用）?
     * ⚠️ ?isRealExternalDisplay 宽：接受任意命名的真实外接屏。曾经把挂载过滤换成
     * isRealExternalDisplay（只?name==null 的屏），结果带名字的有线屏不再挂载，
     * 系统回落为手机画面镜像——这是有线连接失效的根因，不要再用错?
     */
    private boolean isConnectableExternalDisplay(Display d) {
        // 只认注册表的判定：手机主屏、我们自建的 Aosen_VD 串流虚拟屏都不算物理外屏。
        return ScreenRegistry.get().isConnectableWired(d);
    }


    /** 屏幕集合的指纹：只有物理屏真的增减才重挂桌面，避免分辨率变更反复 mount。 */
    private String lastWiredSignature = "";

    /** 总屏幕管理的唯一订阅口：屏幕的事实只有这里下发。 */
    private final ScreenRegistry.Listener screenListener = new ScreenRegistry.Listener() {
        @Override
        public void onScreensChanged() {
            final String signature = wiredSignature();
            final boolean wiredChanged = !signature.equals(lastWiredSignature);
            lastWiredSignature = signature;
            runOnUiThread(() -> {
                if (wiredChanged) {
                    Log.i(TAG, "物理屏集合变化: " + signature);
                    detectAndMountExternalDisplay();
                }
                refreshBlackTouchTarget(false);
                syncToPhoneWeb();
            });
        }

        @Override
        public void onScreenGone(int key) {
            releasePointerCaptureIfHeld();
            runOnUiThread(() -> {
                refreshBlackTouchTarget(true);
                if (phoneCursorView != null) {
                    updatePhoneCursor(phoneCursorX, phoneCursorY);
                }
                syncToPhoneWeb();
            });
        }
    };

    private String wiredSignature() {
        StringBuilder sb = new StringBuilder();
        for (ScreenRegistry.Screen s : ScreenRegistry.get().all()) {
            if (s.isWired()) sb.append(s.key).append(':').append(s.width).append('x').append(s.height).append(';');
        }
        return sb.toString();
    }

    /** ScreenRegistry 发现新物理屏时回调：交给挂载流程，注册表本身不碰桌面。 */
    public void onWiredDisplayAdded(int displayId) {
        Log.i(TAG, "检测到物理外屏接入 ID=" + displayId);
        runOnUiThread(this::detectAndMountExternalDisplay);
    }

    private void setupDisplayMonitoring() {
        displayManager = (DisplayManager) getSystemService(Context.DISPLAY_SERVICE);
        if (displayManager == null) return;

        // 屏幕的枚举与接/断判断只由 ScreenRegistry 做，这里只订阅结果：
        // 物理屏集合变了才重挂桌面，屏消失才回退黑屏触控目标。
        ScreenRegistry.get().init(this);
        ScreenRegistry.get().addListener(screenListener);

        // 监听外部输入设备（鼠?触控板）插拔，实时将鼠标绑定至外?
        InputManager inputManager = (InputManager) getSystemService(Context.INPUT_SERVICE);
        if (inputManager != null) {
            inputManager.registerInputDeviceListener(new InputManager.InputDeviceListener() {
                @Override
                public void onInputDeviceAdded(int deviceId) {
                    runOnUiThread(MainActivity.this::syncToPhoneWeb);
                }


                @Override
                public void onInputDeviceRemoved(int deviceId) {}


                @Override
                public void onInputDeviceChanged(int deviceId) {
                    // 严禁在此发起绑定：绑定会改变输入设备关联进而再次触发此回调，导致递归风暴卡死系统
                }
            }, new Handler(Looper.getMainLooper()));
        }


        detectAndMountExternalDisplay();
    }


    private synchronized void detectAndMountExternalDisplay() {
        if (displayManager == null) return;


        // 锁屏期间无法投出 DeX：跳过挂载，等解锁广播（ACTION_USER_PRESENT）后自动重试
        if (isKeyguardLocked()) {
            return;
        }


        Display targetExternal = null;
        for (ScreenRegistry.Screen s : ScreenRegistry.get().all()) {
            if (!s.isWired()) continue;
            // 用户手动断开的屏：不自动重连（点列表里的「连接」才会再挂）
            if (manuallyDisconnectedDisplays.contains(s.key)) continue;
            targetExternal = displayManager.getDisplay(s.key);
            if (targetExternal != null) break;
        }


        if (targetExternal != null) {
            mountExternalDisplay(targetExternal);
        } else if (dexPresentation != null) {
            // 当前挂着?DeXPresentation 所在屏已消失（拔线）且没有新的可挂屏：卸载
            Display cur = dexPresentation.getDisplay();
            boolean stillExists = cur != null && ScreenRegistry.get().isAlive(cur.getDisplayId());
            if (!stillExists) {
                try {
                    dexPresentation.destroyDeXVirtualDisplay();
                    dexPresentation.dismiss();
                } catch (Throwable ignored) {}
                dexPresentation = null;
                ShizukuCmd.unbindAllExternalInputFromDisplay();
                externalDisplayId = -1;
                lastExternalDisplayId = -1;
                sAutoWakeTriggeredForCurrentPlug = false;
                sUserExitedForCurrentPlug = false;
            }
        } else {
            // 没有任何已连接外屏：解绑外接鼠标，让鼠标恢复控制手机
            if (externalDisplayId != -1) {
                ShizukuCmd.unbindAllExternalInputFromDisplay();
            }
            externalDisplayId = -1;
        }
        updateUiState();
        /* 挂载 / 卸载完成后再让黑屏目标管家看一眼：
           解锁后重建的虚拟屏、拔线后仍连着的另一块屏都能被接管。 */
        refreshBlackTouchTarget(false);
    }


    /** 把一块物理外接屏挂载为沧海桌面（detectAndMountExternalDisplay 与手动「连接」共用）?*/
    private void mountExternalDisplay(Display targetExternal) {
        int currentExtId = targetExternal.getDisplayId();
        boolean isNewPlug = (currentExtId != lastExternalDisplayId);
        if (isNewPlug) {
            // 物理新插入或切换了外接屏：重置触发标志和手动退出标?
            lastExternalDisplayId = currentExtId;
            sAutoWakeTriggeredForCurrentPlug = false;
            sUserExitedForCurrentPlug = false;
        }


        externalDisplayId = targetExternal.getDisplayId();
        DisplayMetrics dm = new DisplayMetrics();
        targetExternal.getRealMetrics(dm);
        externalWidth = dm.widthPixels;
        externalHeight = dm.heightPixels;
        if (isNewPlug) {
            cursorX = externalWidth / 2.0f;
            cursorY = externalHeight / 2.0f;
        }


        // 默认激活外接屏幕（鼠标 + 外设自动切换至外屏）
        if (activeDisplayId <= 0 || isNewPlug) {
            activateDisplay(externalDisplayId);
        }


        if (dexPresentation == null || dexPresentation.getDisplay() == null
                || dexPresentation.getDisplay().getDisplayId() != externalDisplayId) {
            if (dexPresentation != null) {
                try {
                    dexPresentation.destroyDeXVirtualDisplay();
                    dexPresentation.dismiss();
                } catch (Throwable ignored) {}
            }
            try {
                dexPresentation = new DeXPresentation(this, targetExternal, externalWidth, externalHeight, currentDpi);
                dexPresentation.setOnDesktopChangeListener(this);
                PeripheralBrain.getInstance().setDexPresentation(dexPresentation);
                dexPresentation.show();
                Log.i(TAG, "已成功在外接屏幕 (ID: " + externalDisplayId + ") 挂载 DeXPresentation");
                updateDesktopTabs();


                // 启动会话守护：用户划掉任务卡时由 onTaskRemoved 释放虚拟?外设绑定
                try {
                    startService(new Intent(this, DeXKeepAliveService.class));
                } catch (Throwable ignored) {}


                // 只有开启了自动唤醒，且本次插入尚未触发过，且用户没有手动退出过，才自动进入息屏触控?
                if (isAutoWakeEnabled() && !sAutoWakeTriggeredForCurrentPlug && !sUserExitedForCurrentPlug
                        && viewBlackScreenOverlay != null && viewBlackScreenOverlay.getVisibility() != View.VISIBLE) {
                    sAutoWakeTriggeredForCurrentPlug = true;
                    enterBlackScreenMode();
                }
            } catch (Throwable t) {
                Log.e(TAG, "挂载 DeXPresentation 异常: " + t.getMessage(), t);
            }
        }
        updateUiState();
    }


    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (sInstance == this) sInstance = null;
        moonlightHandler.removeCallbacksAndMessages(null);
        try {
            unregisterReceiver(lockStateReceiver);
        } catch (Throwable ignored) {}
        if (pkgWatchReceiver != null) {
            try { unregisterReceiver(pkgWatchReceiver); } catch (Throwable ignored) {}
            pkgWatchReceiver = null;
        }
        try {
            ShizukuCmd.unbindAllExternalInputFromDisplaySync();
            ShizukuCmd.restoreSystemPointerSprite(this);
        } catch (Throwable ignored) {}
        Shizuku.removeBinderReceivedListener(binderReceivedListener);
        Shizuku.removeBinderDeadListener(binderDeadListener);
        // 屏幕监听只有注册表那一份，Activity 销毁时只退订，不 unregister 别人的监听。
        ScreenRegistry.get().removeListener(screenListener);
        if (dexPresentation != null) {
            dexPresentation.destroyDeXVirtualDisplay();
            dexPresentation.dismiss();
            dexPresentation = null;
        }
    }


    private android.webkit.WebView webPhoneHome;

    /** 应用装/卸监听：系统卸载确认完成后立刻刷新双端列表，图标不再残留。 */
    private android.content.BroadcastReceiver pkgWatchReceiver;


    @android.annotation.SuppressLint("SetJavaScriptEnabled")
    /**
     * 手机端网页的「选择软件包」按钮。
     *
     * 沧海软件包是 .ch / .zip / 自定义扩展名，都没有对应的标准 MIME，
     * WebView 默认的 FileChooserParams 会按 accept 拼 MIME，结果系统选择器里一个文件都点不亮。
     * 这里统一按「任意类型」打开系统文件选择器（先由 chooser 让用户选文件管理器），
     * 扩展名合不合法交给页面自己校验并提示。
     */
    private boolean pickWebFile(android.webkit.ValueCallback<android.net.Uri[]> callback) {
        if (webFileCallback != null) {
            try { webFileCallback.onReceiveValue(null); } catch (Throwable ignored) {}
        }
        webFileCallback = callback;
        try {
            Intent intent = new Intent(Intent.ACTION_GET_CONTENT);
            intent.addCategory(Intent.CATEGORY_OPENABLE);
            intent.setType("*/*");
            intent.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
            // 系统选择器默认停在「最近」页，而那一页只索引图片 / 视频 / 音频，
            // 沧海软件包（.zip、.沧海软件 这类自定义扩展名）根本不会被列出来，
            // 用户点开只看到一堆照片，就会以为「选不到软件包」。
            // 这里把起始目录钉死到「下载」，一打开就是真实文件列表。
            try {
                intent.putExtra("android.provider.extra.INITIAL_URI", android.net.Uri.parse(
                        "content://com.android.externalstorage.documents/document/primary%3ADownload"));
            } catch (Throwable ignored) {}
            startActivityForResult(Intent.createChooser(intent, "选择沧海软件包"), REQ_WEB_FILE);
            return true;
        } catch (Throwable t) {
            Log.w(TAG, "打开文件选择器失败: " + t.getMessage());
            webFileCallback = null;
            return false;
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQ_EXPORT) {
            CanghaiStore.finishExport(this, requestCode, REQ_EXPORT, resultCode, data);
            return;
        }
        if (requestCode == REQ_EXPORT_APK) {
            finishApkExport(resultCode, data);
            return;
        }
        if (requestCode != REQ_WEB_FILE) return;
        android.net.Uri[] picked = null;
        if (resultCode == RESULT_OK && data != null) {
            if (data.getClipData() != null) {
                int count = data.getClipData().getItemCount();
                picked = new android.net.Uri[count];
                for (int i = 0; i < count; i++) {
                    picked[i] = data.getClipData().getItemAt(i).getUri();
                }
            } else if (data.getData() != null) {
                picked = new android.net.Uri[]{ data.getData() };
            }
        }
        if (webFileCallback != null) {
            lastPickedUri = (picked != null && picked.length > 0) ? picked[0].toString() : null;
            try { webFileCallback.onReceiveValue(picked); } catch (Throwable ignored) {}
            webFileCallback = null;
        }
    }

    /** APK 导出：用户选好位置后，后台把安装包流式拷过去，完成回调页面。 */
    private void finishApkExport(int resultCode, Intent data) {
        String src, name;
        synchronized (APK_EXPORT_LOCK) {
            src = pendingApkSource;
            name = pendingApkName;
            pendingApkSource = null;
            pendingApkName = null;
        }
        if (src == null) return;
        if (resultCode != RESULT_OK || data == null || data.getData() == null) {
            reportApkExport(false, "已取消导出");
            return;
        }
        final Uri target = data.getData();
        final String appName = name;
        new Thread(() -> {
            boolean ok = false;
            String err = "";
            java.io.InputStream in = null;
            java.io.OutputStream os = null;
            try {
                in = new java.io.FileInputStream(src);
                os = getContentResolver().openOutputStream(target, "w");
                if (os == null) throw new java.io.IOException("打不开目标文件");
                byte[] buf = new byte[65536];
                int n;
                while ((n = in.read(buf)) > 0) os.write(buf, 0, n);
                os.flush();
                ok = true;
            } catch (Throwable t) {
                err = String.valueOf(t.getMessage());
                Log.w(TAG, "finishApkExport: " + t.getMessage());
            } finally {
                try { if (in != null) in.close(); } catch (Throwable ignored) {}
                try { if (os != null) os.close(); } catch (Throwable ignored) {}
            }
            reportApkExport(ok, ok ? "已导出「" + appName + "」的安装包" : "导出失败：" + err);
        }, "canghai-apk-export").start();
    }

        /** APK 导出结果回给页面（与 .沧海软件 导出共用 __chExportDone）。 */
        private void reportApkExport(boolean ok, String msg) {
            if (webPhoneHome == null) return;
            try {
                org.json.JSONObject o = new org.json.JSONObject();
                o.put("ok", ok);
                o.put("msg", msg);
                final String js = "window.__chExportDone && window.__chExportDone("
                        + org.json.JSONObject.quote(o.toString()) + ");";
            webPhoneHome.post(() -> {
                try { webPhoneHome.evaluateJavascript(js, null); } catch (Throwable ignored) {}
            });
        } catch (Throwable ignored) {}
    }

    /** 每 7 天静默检查一次更新：Native 后台拉 json 落盘，结果只推给页面点亮绿点。
     *  大忌防护：绝不自动打开浏览器、绝不自动下载——打开下载页只允许用户亲手点「下载新版本」。 */
    private void autoUpdateCheckIfDue() {
        if (!CanghaiStore.updateCheckDue(this)) return;
        new Thread(() -> {
            String r = CanghaiStore.checkUpdateJson(this);
            CanghaiStore.recordUpdateResult(this, r);
            if (webPhoneHome == null) return;
            final String js = "window.__canghaiUpdHint && window.__canghaiUpdHint("
                    + org.json.JSONObject.quote(CanghaiStore.updateHintJson(this)) + ");";
            webPhoneHome.post(() -> {
                try { webPhoneHome.evaluateJavascript(js, null); } catch (Throwable ignored) {}
            });
        }, "canghai-upd-check").start();
    }

    private void setupPhoneHomeWeb() {
        try {
            if (webPhoneHome == null) {
                webPhoneHome = findViewById(R.id.webPhoneHome);
            }
            if (webPhoneHome == null) return;
            // 严禁手机端 WebView 抢夺全局 View 焦点（手指点击时绝不允许篡位抢走 rootBox 焦点，
            // 从而彻底防止 Android 内核强制杀死 PointerCapture 硬件鼠标独占捕获，保证外设鼠标永不冻结）：
            webPhoneHome.setFocusable(false);
            webPhoneHome.setFocusableInTouchMode(false);
            // 系统指针一律不画：WebView 会按 CSS cursor 自己解析出箭头，
            // 那就是 USB 鼠标在手机上多出来的第二个图标；统一压成空图标。
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                webPhoneHome.setPointerIcon(EmptyPointerFrameLayout.getEmptyPointerIcon(this));
            }
            int[] initMm = currentPhysicalDisplayMinMax();
            if (initMm[0] > 0 && initMm[1] > 0) {
                lastPhysicalMinPx = initMm[0];
                lastPhysicalMaxPx = initMm[1];
            }
            /* 用户在手机端主界面触摸操作时，若此前因外屏输入法锁了横屏，立即恢复竖屏/自由旋转 */
            webPhoneHome.setOnTouchListener((v, ev) -> {
                if (ev != null && ev.getActionMasked() == MotionEvent.ACTION_DOWN && !isBlackScreenActive()) {
                    lastPhoneWebTouchAt = System.currentTimeMillis();
                    if (externalImeLandscapeLocked) {
                        setExternalImeLandscapeLock(false);
                    }
                }
                return false;
            });
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                webPhoneHome.setPointerIcon(android.view.PointerIcon.getSystemIcon(this, android.view.PointerIcon.TYPE_NULL));
            }
            /* 页面渐变的底色，不是近黑：加载那一瞬间不会闪出一条黑边 */
            webPhoneHome.setBackgroundColor(0xFF0D2F4D);
            android.webkit.WebSettings s = webPhoneHome.getSettings();
            s.setJavaScriptEnabled(true);
            s.setDomStorageEnabled(true);
            s.setAllowFileAccess(true);
            s.setAllowContentAccess(true);
            s.setCacheMode(android.webkit.WebSettings.LOAD_NO_CACHE);
            webPhoneHome.setWebViewClient(new android.webkit.WebViewClient() {
                @Override
                public boolean onRenderProcessGone(android.webkit.WebView view, android.webkit.RenderProcessGoneDetail detail) {
                    Log.w(TAG, "webPhoneHome onRenderProcessGone: didCrash=" + (detail != null && detail.didCrash()));
                    try {
                        android.view.ViewGroup p = (android.view.ViewGroup) view.getParent();
                        if (p != null) p.removeView(view);
                        view.destroy();
                    } catch (Throwable ignored) {}
                    new android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(() -> {
                        try {
                            if (!isFinishing() && !isDestroyed()) {
                                recreate();
                            }
                        } catch (Throwable ignored) {}
                    }, 500);
                    return true;
                }

                @Override
                public void onPageFinished(android.webkit.WebView view, String url) {
                    syncToPhoneWeb();
                    autoUpdateCheckIfDue();
                    if (pendingIncomingFilesJson != null) {
                        final String json = pendingIncomingFilesJson;
                        pendingIncomingFilesJson = null;
                        view.postDelayed(() -> {
                            view.evaluateJavascript(
                                    "window.__canghaiHandleIncomingFiles && window.__canghaiHandleIncomingFiles("
                                            + json + ");", null);
                        }, 300);
                    }
                }
            });
            webPhoneHome.setWebChromeClient(new android.webkit.WebChromeClient() {
                @Override
                public boolean onShowFileChooser(android.webkit.WebView view,
                                                 android.webkit.ValueCallback<android.net.Uri[]> callback,
                                                 android.webkit.WebChromeClient.FileChooserParams params) {
                    return pickWebFile(callback);
                }

                /** 页面里的 JS 报错默认完全静默，出问题根本无从查起，这里捞到 logcat 并记录入内存错误收集器。 */
                @Override
                public boolean onConsoleMessage(android.webkit.ConsoleMessage m) {
                    if (m == null) return false;
                    String msg = m.message();
                    String loc = m.sourceId() + ":" + m.lineNumber();
                    Log.i(TAG + "-web", msg + " @" + loc);
                    if (m.messageLevel() == android.webkit.ConsoleMessage.MessageLevel.ERROR
                            || (msg != null && (msg.toLowerCase().contains("uncaught") || msg.toLowerCase().contains("error")))) {
                        CanghaiErrorCollector.getInstance().log("手机端", "ERROR", msg, loc);
                        updateDebugFloatingBadge(CanghaiErrorCollector.getInstance().getErrorCount());
                    } else if (BuildConfig.DEBUG && m.messageLevel() == android.webkit.ConsoleMessage.MessageLevel.WARNING) {
                        CanghaiErrorCollector.getInstance().log("手机端", "WARN", msg, loc);
                    }
                    return true;
                }

                /* alert()/confirm() 不实现就静默失败：confirm 恒返回 false、不弹任何东西。
                   页面里所有「问一句再走」的确认都会变成什么都不发生。 */
                @Override
                public boolean onJsAlert(android.webkit.WebView view, String url, String message,
                                         android.webkit.JsResult result) {
                    try {
                        new androidx.appcompat.app.AlertDialog.Builder(MainActivity.this)
                                .setTitle("提示")
                                .setMessage(message == null ? "" : message)
                                .setPositiveButton("确定", (d, w) -> result.confirm())
                                .setOnCancelListener(d -> result.cancel())
                                .show();
                    } catch (Throwable t) {
                        result.confirm();
                    }
                    return true;
                }

                @Override
                public boolean onJsConfirm(android.webkit.WebView view, String url, String message,
                                           android.webkit.JsResult result) {
                    try {
                        new androidx.appcompat.app.AlertDialog.Builder(MainActivity.this)
                                .setTitle("确认")
                                .setMessage(message == null ? "" : message)
                                .setPositiveButton("确定", (d, w) -> result.confirm())
                                .setNegativeButton("取消", (d, w) -> result.cancel())
                                .setOnCancelListener(d -> result.cancel())
                                .show();
                    } catch (Throwable t) {
                        result.cancel();
                    }
                    return true;
                }
            });
            webPhoneHome.addJavascriptInterface(new PhoneHomeBridge(), "AosenPhone");
            /* 新名字：页面统一用 Canghai；旧名保留给还没更新的自定义桌面页面 */
            webPhoneHome.addJavascriptInterface(new PhoneHomeBridge(), "CanghaiPhone");
            webPhoneHome.addJavascriptInterface(new PhoneHomeBridge(), "CanghaiDesk");
            /* 手机主页是 L1 宿主；与大屏桌面共用同一个 CanghaiCore / CoreFs 数据目录 */
            CanghaiCore.attach(this, webPhoneHome, null);
            /* 折叠屏开合（内屏 <-> 正方形外屏）或屏幕尺寸发生变动时，立即通知页面重算视口与安全区 */
            webPhoneHome.addOnLayoutChangeListener((v, left, top, right, bottom, oldLeft, oldTop, oldRight, oldBottom) -> {
                int w = right - left, h = bottom - top;
                int ow = oldRight - oldLeft, oh = oldBottom - oldTop;
                if (w > 0 && h > 0 && (w != ow || h != oh)) {
                    scheduleFoldOrResizeSync();
                }
            });
            /* 系统栏高度走 URL 参数带过去，页面自己按它留白；
               不用 evaluateJavascript 是因为页面还没加载完时调用会丢。 */
            int[] cut = cutoutSafeInsets();
            int sysTop = Math.max(systemBarHeight("status_bar_height"), cut[0]);
            int sysBottom = systemBarHeight("navigation_bar_height");
            webPhoneHome.loadUrl("file:///android_asset/phonehome/index.html"
                    + "?sysTop=" + sysTop + "&sysBottom=" + sysBottom
                    + "&sysLeft=" + cut[1] + "&sysRight=" + cut[2]);
        } catch (Throwable t) {
            Log.e(TAG, "setupPhoneHomeWeb failed: " + t.getMessage(), t);
        }
    }


    /** 无障碍服务（键鼠注入 / 触控透传）是否已开启。 */
    public boolean isAccessibilityServiceEnabled() {
        try {
            android.content.ComponentName mine = new android.content.ComponentName(this,
                    com.alkaidlab.sunshine.controller.ControllerNavigationService.class);
            String flat = mine.flattenToString();
            String flatShort = mine.flattenToShortString();
            String enabled = android.provider.Settings.Secure.getString(
                    getContentResolver(),
                    android.provider.Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
            if (enabled == null) return false;
            for (String s : enabled.split(":")) {
                if (s.equalsIgnoreCase(flat) || s.equalsIgnoreCase(flatShort)) return true;
            }
            return false;
        } catch (Throwable t) {
            return false;
        }
    }

    /** 本地网络发现（Moonlight 找设备）所需的运行时权限是否已给。 */
    public boolean isLocalNetworkPermissionGranted() {
        try {
            if (android.os.Build.VERSION.SDK_INT >= 33) {
                return checkSelfPermission("android.permission.NEARBY_WIFI_DEVICES")
                        == android.content.pm.PackageManager.PERMISSION_GRANTED;
            }
            return checkSelfPermission(android.Manifest.permission.ACCESS_FINE_LOCATION)
                    == android.content.pm.PackageManager.PERMISSION_GRANTED;
        } catch (Throwable t) {
            return false;
        }
    }

    /** 是否已把沧海排除在电池优化之外（保活投屏用）。 */
    public boolean isIgnoringBatteryOptimizations() {
        try {
            android.os.PowerManager pm = (android.os.PowerManager) getSystemService(Context.POWER_SERVICE);
            return pm != null && pm.isIgnoringBatteryOptimizations(getPackageName());
        } catch (Throwable t) {
            return false;
        }
    }

    public boolean isOverlayPermissionGranted() {        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M) {
            return android.provider.Settings.canDrawOverlays(this);
        }
        return true;
    }

    public String getDisplayNameForId(int displayId) {
        return ScreenRegistry.get().nameOf(displayId);
    }

    /** 外接屏用系统给的 Display 名字；不再把 displayId 当名字显示。 */
    private String friendlyDisplayName(Display d) {
        String n = (d == null) ? null : d.getName();
        return (n == null || n.trim().isEmpty()) ? "外接显示器" : n.trim();
    }

    public String getAvailableDisplaysJson() {
        return ScreenRegistry.get().toJsonArray().toString();
    }

    public org.json.JSONArray getPeripheralsListJson() {
        org.json.JSONArray arr = new org.json.JSONArray();
        try {
            java.util.Collection<PeripheralBrain.VirtualSlot> slots = PeripheralBrain.getInstance().getAllSlots();
            for (PeripheralBrain.VirtualSlot slot : slots) {
                if ("internal_sensor".equals(slot.type) || "internal_touch".equals(slot.type)) {
                    continue; // 过滤内置按键/传感器/触摸屏
                }
                org.json.JSONObject obj = new org.json.JSONObject();
                // 一个 Moonlight 客户端就是一个整体虚拟外设：展示上打 Moonlight 标识 +
                // 专用图标，内部 slot.type 仍是 mouse，输入诊断分支不受影响。
                final boolean moonClient = PeripheralBrain.MOONLIGHT_REMOTE_SLOT.equals(slot.id);
                obj.put("id", slot.id);
                obj.put("name", slot.name);
                obj.put("type", moonClient ? "moon_input" : slot.type);
                if (moonClient) obj.put("brand", "Moonlight");
                // 目标屏只信虚拟外设自己的解析结果，不再按「外屏是否显示」临时改写。
                int target = PeripheralBrain.getInstance().effectiveTargetOf(slot.id);
                /* 真实硬件鼠标在 Root / Shizuku 下由大脑层绑定到真实外屏。
                 * 槽位里那个值是「虚拟外设插到哪块画布」的内部路由，不是用户看到的屏；
                 * 软件端要展示的是真实外设 → 真实外屏，不能拿 Aosen_VD_Desk_* 当落点。
                 * 非特权链路一律不动，Moonlight 客户端也不动。 */
                RealPeripheralBinding real = RealPeripheralBinding.get();
                if (!moonClient && "mouse".equals(slot.type) && real.isPrivileged()) {
                    /* 特权链路下真实绑定就是唯一信源：用户拖回手机后 uiKey() 变 0，
                     * 卡片必须跟着走，不能再用槽位里那个内部画布路由把它盖回去。 */
                    real.setBoundSlotId(slot.id);
                    target = real.uiKey();
                    obj.put("targetDisplayName", real.realDisplayName());
                    obj.put("targetKind", real.kind());
                } else {
                    obj.put("targetDisplayName", getDisplayNameForId(target));
                    obj.put("targetKind", StreamTarget.get().isStreamTarget(target) ? "stream" : (target > 0 ? "wired" : "phone"));
                }
                obj.put("targetDisplayId", target);
                arr.put(obj);
            }

            /* 列表空的时候，真实外设其实可能就在手上：真鼠标被 EVIOCGRAB 独占之后，
             * 系统 InputDevice 列表里就没有它了，外设大脑的槽位也跟着没了；
             * 而我们自己造的那台虚拟鼠标又被 isOwnDevice() 当「自己人」过滤掉。
             * 两边一起消失，右键菜单就永远显示「暂无已连接外设」——
             * 但输入链路明明是通的（独占 ok、绑屏 ok）。所以这里按「大脑手里握着谁」
             * 兜一条出来，保证面板和事实一致。 */
            if (arr.length() == 0) {
                String held = "";
                try { held = RootInputBridge.grabbedName(); } catch (Throwable ignored) {}
                if (held != null && !held.isEmpty()) {
                    org.json.JSONObject obj = new org.json.JSONObject();
                    obj.put("id", "grabbed_real_mouse");
                    obj.put("name", held);
                    obj.put("type", "mouse");
                    /* 这张卡就是「大脑手里握着的真实鼠标」。它的落点只能是真实外屏，
                     * 绝不能用 VirtualMouse.displayId()（那是自建虚拟屏，会显示成
                     * Aosen_VD_Desk_*「鼠标被它强行占有」）。 */
                    RealPeripheralBinding real = RealPeripheralBinding.get();
                    real.setBoundSlotId("grabbed_real_mouse");
                    int key = real.uiKey();
                    if (key <= 0 && !real.isPhoneBound()) {
                        key = (dexPresentation != null && dexPresentation.isShowing())
                                ? dexPresentation.getDisplay().getDisplayId() : 0;
                    }
                    obj.put("targetDisplayId", key);
                    obj.put("targetDisplayName", key > 0 ? real.realDisplayName()
                            : getDisplayNameForId(key));
                    obj.put("targetKind", key > 0 ? real.kind() : "phone");
                    arr.put(obj);
                }
            }

            // 如果此时还没有插槽，至少确保黑屏触控板存在
            if (arr.length() == 0) {
                org.json.JSONObject tp = new org.json.JSONObject();
                tp.put("id", "virtual_touchpad");
                tp.put("name", "黑屏触控 (内置触控板)");
                tp.put("type", "touchpad");
                int def = (dexPresentation != null && dexPresentation.isShowing())
                        ? dexPresentation.getDisplay().getDisplayId() : 0;
                tp.put("targetDisplayId", def);
                tp.put("targetDisplayName", getDisplayNameForId(def));
                arr.put(tp);
            }
        } catch (Throwable t) {
            Log.w(TAG, "getPeripheralsListJson err: " + t);
        }
        return arr;
    }

    public org.json.JSONObject buildHostStatusJson() {
        org.json.JSONObject obj = new org.json.JSONObject();
        try {
            obj.put("ip", getLocalIpAddress());
            /* 特权 = Root 或 Shizuku（任一即可）。前端只用「有没有特权」这一件事，
               具体是哪条通道只在标题栏那枚小标上体现，避免到处出现 Root/Shizuku 字样。 */
            boolean privRoot = RootCmd.isAvailable();
            boolean privShizuku = ShizukuCmd.isShizukuReady();
            obj.put("privilege", privRoot || privShizuku);
            obj.put("privRoot", privRoot);
            obj.put("privShizuku", privShizuku);
            obj.put("privMode", privRoot ? "root" : (privShizuku ? "shizuku" : "none"));
            obj.put("isRoot", privRoot);
            obj.put("isShizuku", isShizukuReady());
            obj.put("isShizukuGranted", isShizukuGranted());
            obj.put("privilegeMode", PrivilegeRoute.mode().name());
            obj.put("isOverlay", isOverlayPermissionGranted());
            obj.put("activeDisplayId", activeDisplayId);
            obj.put("streamTargetId", StreamTarget.UI_ID);
            obj.put("streamTargetName", StreamTarget.get().getName());
            /* 主屏 = 外设输入当前路由到的那块屏，手机端用它标「已是主屏」 */
            obj.put("mainDisplayId", activeDisplayId);
            obj.put("homeType", getDesktopHome());
            obj.put("cursorStyle", com.alkaidlab.sunshine.service.MouseCursorPrefs.getStyle());
            /* 光标三件套推给手机端：外屏设置里改了，手机上立刻同步显示 */
            obj.put("cursorCurrent", CanghaiStore.cursorCurrent(MainActivity.this));
            obj.put("cursorSize", CanghaiStore.cursorSize(MainActivity.this));
            obj.put("cursorSpeed", (double) CanghaiStore.cursorSpeed(MainActivity.this));
            obj.put("userName", CanghaiStore.userName(MainActivity.this));
            obj.put("userAvatar", CanghaiStore.userAvatar(MainActivity.this));
            obj.put("language", CanghaiStore.getLanguage(MainActivity.this));

            org.json.JSONArray devs = ScreenRegistry.get().devicesJson(activeDisplayId);
            for (int i = 0; i < devs.length(); i++) {
                org.json.JSONObject d = devs.getJSONObject(i);
                final int id = d.optInt("id");
                d.put("desk", (getDesktopHome() == HOME_AOSEN) ? "canghai" : "native");
                d.put("fps", "stream".equals(d.optString("kind")) ? "60 fps" : "60 Hz");
                d.put("cursorSize", CanghaiStore.cursorSizeForDisplay(MainActivity.this, id));
                if (!"stream".equals(d.optString("kind"))) {
                    String uId = "disp_" + id;
                    try {
                        Display disp = (displayManager != null) ? displayManager.getDisplay(id) : null;
                        if (disp != null) {
                            if (disp.getName() != null) uId = disp.getName();
                            java.lang.reflect.Method m = disp.getClass().getMethod("getUniqueId");
                            Object val = m.invoke(disp);
                            if (val != null) uId = val.toString();
                        }
                    } catch (Throwable ignored) {}
                    d.put("uniqueId", uId);
                }
            }
            obj.put("devices", devs);
            obj.put("peripherals", getPeripheralsListJson());
            /* 手机端展示的唯一真实关系：真实外设 → 真实外屏。 */
            obj.put("realBinding", RealPeripheralBinding.get().toJson());
        } catch (Throwable ignored) {}
        return obj;
    }

    public void syncToPhoneWeb() {
        if (webPhoneHome == null) return;
        runOnUiThread(() -> {
            try {
                org.json.JSONObject obj = buildHostStatusJson();
                String js = "window.onHostStatusSync && window.onHostStatusSync(" + obj.toString() + ");";
                webPhoneHome.evaluateJavascript(js, null);
            } catch (Throwable ignored) {}
        });
    }

    
    /* ==================== Debug 模式悬浮调试日志窗口 ==================== */
    private android.widget.TextView tvDebugFloatingBadge;

    private void setupDebugErrorOverlay() {
        if (!BuildConfig.DEBUG) return;
        android.widget.FrameLayout root = findViewById(R.id.rootContainer);
        if (root == null) return;

        tvDebugFloatingBadge = new android.widget.TextView(this);
        tvDebugFloatingBadge.setText("🐞 0");
        tvDebugFloatingBadge.setTextColor(Color.WHITE);
        tvDebugFloatingBadge.setTextSize(12);
        tvDebugFloatingBadge.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        tvDebugFloatingBadge.setGravity(Gravity.CENTER);
        tvDebugFloatingBadge.setPadding(dp(10), dp(5), dp(10), dp(5));

        android.graphics.drawable.GradientDrawable gd = new android.graphics.drawable.GradientDrawable();
        gd.setColor(Color.parseColor("#CC1E293B"));
        gd.setCornerRadius(dp(16));
        gd.setStroke(dp(1), Color.parseColor("#475569"));
        tvDebugFloatingBadge.setBackground(gd);
        tvDebugFloatingBadge.setElevation(dp(8));

        android.widget.FrameLayout.LayoutParams lp = new android.widget.FrameLayout.LayoutParams(
                android.widget.FrameLayout.LayoutParams.WRAP_CONTENT,
                android.widget.FrameLayout.LayoutParams.WRAP_CONTENT
        );
        lp.gravity = Gravity.BOTTOM | Gravity.END;
        lp.bottomMargin = dp(88);
        lp.rightMargin = dp(16);
        tvDebugFloatingBadge.setLayoutParams(lp);

        tvDebugFloatingBadge.setOnClickListener(v -> showDebugErrorDialog());

        tvDebugFloatingBadge.setOnTouchListener(new View.OnTouchListener() {
            private float dX, dY;
            private float startX, startY;
            private boolean isClick = true;

            @Override
            public boolean onTouch(View view, MotionEvent event) {
                switch (event.getAction()) {
                    case MotionEvent.ACTION_DOWN:
                        dX = view.getX() - event.getRawX();
                        dY = view.getY() - event.getRawY();
                        startX = event.getRawX();
                        startY = event.getRawY();
                        isClick = true;
                        return true;
                    case MotionEvent.ACTION_MOVE:
                        float diffX = Math.abs(event.getRawX() - startX);
                        float diffY = Math.abs(event.getRawY() - startY);
                        if (diffX > 10 || diffY > 10) isClick = false;
                        view.animate().x(event.getRawX() + dX).y(event.getRawY() + dY).setDuration(0).start();
                        return true;
                    case MotionEvent.ACTION_UP:
                        if (isClick) {
                            view.performClick();
                        }
                        return true;
                }
                return false;
            }
        });

        root.addView(tvDebugFloatingBadge);
        updateDebugFloatingBadge(CanghaiErrorCollector.getInstance().getErrorCount());
    }

    public void updateDebugFloatingBadge(final int count) {
        if (!BuildConfig.DEBUG || tvDebugFloatingBadge == null) return;
        runOnUiThread(() -> {
            try {
                if (count > 0) {
                    tvDebugFloatingBadge.setText("🐞 " + count + "报错");
                    android.graphics.drawable.GradientDrawable gd = new android.graphics.drawable.GradientDrawable();
                    gd.setColor(Color.parseColor("#E6DC2626"));
                    gd.setCornerRadius(dp(16));
                    gd.setStroke(dp(1), Color.parseColor("#FCA5A5"));
                    tvDebugFloatingBadge.setBackground(gd);
                } else {
                    tvDebugFloatingBadge.setText("🐞 0");
                    android.graphics.drawable.GradientDrawable gd = new android.graphics.drawable.GradientDrawable();
                    gd.setColor(Color.parseColor("#CC1E293B"));
                    gd.setCornerRadius(dp(16));
                    gd.setStroke(dp(1), Color.parseColor("#475569"));
                    tvDebugFloatingBadge.setBackground(gd);
                }
            } catch (Throwable ignored) {}
        });
    }

    private void showDebugErrorDialog() {
        String json = CanghaiErrorCollector.getInstance().getRecentErrorsJson(100);
        StringBuilder sb = new StringBuilder();
        int errCount = 0;
        try {
            org.json.JSONArray arr = new org.json.JSONArray(json);
            errCount = arr.length();
            if (arr.length() == 0) {
                sb.append("🎉 目前暂无报错/警告日志，网页与系统运行良好！\n\n- 控制台信息会自动捕获网页 uncaught exception、console.error、资源加载失败等。\n- 发布版本 (Release) 此悬浮入口会自动隐藏。");
            } else {
                java.text.SimpleDateFormat sdf = new java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.getDefault());
                for (int i = 0; i < arr.length(); i++) {
                    org.json.JSONObject obj = arr.getJSONObject(i);
                    String timeStr = obj.optString("time", "");
                    if (timeStr.isEmpty()) {
                        long ts = obj.optLong("ts", System.currentTimeMillis());
                        timeStr = sdf.format(new java.util.Date(ts));
                    }
                    String src = obj.optString("source", obj.optString("src", "系统"));
                    String lvl = obj.optString("level", obj.optString("lvl", "INFO"));
                    String msg = obj.optString("message", obj.optString("msg", "（无详细内容）"));
                    String loc = obj.optString("location", obj.optString("loc", ""));
                    sb.append("[").append(timeStr).append("][").append(src).append("][").append(lvl).append("]\n");
                    sb.append(msg).append("\n");
                    if (!loc.isEmpty()) {
                        sb.append("  📍 ").append(loc).append("\n");
                    }
                    sb.append("------------------------------------\n");
                }
            }
        } catch (Throwable t) {
            sb.append("解析日志失败: ").append(t.getMessage());
        }

        android.widget.ScrollView sv = new android.widget.ScrollView(this);
        sv.setPadding(dp(16), dp(12), dp(16), dp(12));
        android.widget.TextView tv = new android.widget.TextView(this);
        tv.setText(sb.toString());
        tv.setTextSize(13);
        tv.setTextColor(Color.parseColor("#E2E8F0"));
        tv.setTypeface(android.graphics.Typeface.MONOSPACE);
        tv.setTextIsSelectable(true);
        sv.addView(tv);

        new androidx.appcompat.app.AlertDialog.Builder(this)
                .setTitle("🛠️ 网页与系统调试日志 (Debug · " + errCount + "条)")
                .setView(sv)
                .setPositiveButton("复制全部", (d, w) -> {
                    android.content.ClipboardManager cm = (android.content.ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
                    if (cm != null) {
                        cm.setPrimaryClip(android.content.ClipData.newPlainText("debug_logs", sb.toString()));
                        Toast.makeText(MainActivity.this, "已复制所有日志到剪贴板", Toast.LENGTH_SHORT).show();
                    }
                })
                .setNeutralButton("清空日志", (d, w) -> {
                    CanghaiErrorCollector.getInstance().clear();
                    updateDebugFloatingBadge(0);
                    Toast.makeText(MainActivity.this, "已清空日志", Toast.LENGTH_SHORT).show();
                })
                .setNegativeButton("关闭", null)
                .show();
    }

    public class PhoneHomeBridge {
        @android.webkit.JavascriptInterface
        public boolean isDebug() {
            return true;
        }

        /* ============ 调试外设：输入探针入口 ============ */
        @android.webkit.JavascriptInterface
        public boolean isPrivileged() {
            return PrivilegeRoute.hasAny();
        }

        /* ---------- 输入诊断（手机端「调试外设」面板） ----------
         *
         * 手机端这块页面和桌面那块读的是同一个 InputTrace 单例，但只有 DexHome /
         * DeXPresentation / HtmlAppActivity 暴露过这三个口，MainActivity 的桥里一直缺。
         * 页面轮询 chCall("inputTrace") 拿不到任何东西，就永远停在「等待输入设备…」，
         * 哪怕大脑那边已经握着真鼠标、事件一条条在记。补齐这三个口，数据源本来就是现成的。 */

        /** 当前按下了什么 + 最近事件日志，JSON 字符串。 */
        @android.webkit.JavascriptInterface
        public String inputTrace() {
            return InputTrace.snapshotJson();
        }

        @android.webkit.JavascriptInterface
        public void inputTraceEnable(boolean on) {
            InputTrace.setEnabled(on);
        }

        @android.webkit.JavascriptInterface
        public void inputTraceClear() {
            InputTrace.clear();
        }

        /** 只在 Root / Shizuku 下开放：把 InputProbeActivity 全屏起到串流虚拟屏上。 */
        @android.webkit.JavascriptInterface
        public void openInputProbe() {
            runOnUiThread(() -> {
                try {
                    if (!PrivilegeRoute.hasAny()) {
                        Toast.makeText(MainActivity.this, "输入探针需要 Root 或 Shizuku",
                                Toast.LENGTH_SHORT).show();
                        return;
                    }
                    int d = StreamTarget.get().resolveDisplayId(StreamTarget.UI_ID);
                    if (d <= 0) {
                        Toast.makeText(MainActivity.this, "串流屏还没就绪，先连上 Moonlight",
                                Toast.LENGTH_SHORT).show();
                        return;
                    }
                    String out = PrivilegeRoute.execute("am start --display " + d
                            + " --windowingMode 1 -n com.cunkai.canghai/com.aosen.shizukudex.InputProbeActivity");
                    Toast.makeText(MainActivity.this, "输入探针 → 串流屏 display " + d,
                            Toast.LENGTH_SHORT).show();
                    android.util.Log.i("CanghaiProbe", "openInputProbe display=" + d + " out=" + out);
                } catch (Throwable t) {
                    Toast.makeText(MainActivity.this, "启动探针失败: " + t.getMessage(),
                            Toast.LENGTH_SHORT).show();
                }
            });
        }

        /* ============ 管理渲染层：从上到下的窗口栈 + 逐层屏蔽 ============ */
        @android.webkit.JavascriptInterface
        public String getRenderLayers() {
            return RenderLayerRegistry.json();
        }

        @android.webkit.JavascriptInterface
        public boolean setRenderLayerEnabled(final String id, final boolean on) {
            runOnUiThread(() -> RenderLayerRegistry.setEnabled(id, on));
            return true;
        }

        @android.webkit.JavascriptInterface
        public void resetRenderLayers() {
            runOnUiThread(RenderLayerRegistry::resetAll);
        }

        @android.webkit.JavascriptInterface
        public String getSystemLogs() {
            return CanghaiErrorCollector.getInstance().exportSummary();
        }

        @android.webkit.JavascriptInterface
        public String getDebugLogs(final String filter) {
            return CanghaiErrorCollector.getInstance().getFilteredLogsText(filter);
        }

        @android.webkit.JavascriptInterface
        public void copyLogToClipboard(final String text) {
            runOnUiThread(() -> {
                try {
                    android.content.ClipboardManager cm = (android.content.ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
                    if (cm != null) {
                        android.content.ClipData clip = android.content.ClipData.newPlainText("CanghaiLog", text);
                        cm.setPrimaryClip(clip);
                        Toast.makeText(MainActivity.this, "日志已复制到剪贴板", Toast.LENGTH_SHORT).show();
                    }
                } catch (Throwable t) {
                    Toast.makeText(MainActivity.this, "复制失败: " + t.getMessage(), Toast.LENGTH_SHORT).show();
                }
            });
        }

        @android.webkit.JavascriptInterface
        public void clearDebugLogs() {
            CanghaiErrorCollector.getInstance().clear();
        }

        @android.webkit.JavascriptInterface
        public String getSystemLogsJson() {
            return CanghaiErrorCollector.getInstance().exportLogsJson();
        }

        @android.webkit.JavascriptInterface
        public int getDockOffsetX() {
            return CanghaiStore.getDockOffsetX(MainActivity.this);
        }

        @android.webkit.JavascriptInterface
        public int getDockOffsetY() {
            return CanghaiStore.getDockOffsetY(MainActivity.this);
        }

        @android.webkit.JavascriptInterface
        public void setDockOffsets(final int x, final int y) {
            runOnUiThread(() -> {
                CanghaiStore.setDockOffsets(MainActivity.this, x, y);
                DexHomeActivity.notifyDockOffsetsChanged(x, y);
            });
        }

        @android.webkit.JavascriptInterface
        public void enterDesktopOnPhone() {
            runOnUiThread(() -> {
                try {
                    Intent intent = new Intent(MainActivity.this, DexHomeActivity.class);
                    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                    startActivity(intent);
                } catch (Throwable t) {
                    Toast.makeText(MainActivity.this, "打开系统桌面失败: " + t.getMessage(), Toast.LENGTH_SHORT).show();
                }
            });
        }

        @android.webkit.JavascriptInterface
        public String getHostStatus() {
            try {
                return buildHostStatusJson().toString();
            } catch (Throwable t) {
                return "{}";
            }
        }

        /**
         * 设备卡「设为主屏」：主屏的含义是「连进手机的外设（鼠标 / 键盘 / 触控）
         * 全部控制这块屏」，所以这里直接把外部输入路由绑到它，不再有单独的「输入激活」按钮。
         * displayId StreamTarget.UI_ID 是手机端给 Moonlight 串流保留的虚拟 id，走串流输入通道，
         * 不能拿去 bindExternalInput（那要求真实物理 Display）。
         */
        @android.webkit.JavascriptInterface
        public void setMainDisplay(final int displayId) {
            runOnUiThread(() -> {
                try {
                    if (displayId == StreamTarget.UI_ID) {
                        activeDisplayId = StreamTarget.UI_ID;
                        refreshConnectedStateUI();
                        syncToPhoneWeb();
                        Toast.makeText(MainActivity.this, "已设为主屏 · 外设输入指向 Moonlight 串流画面",
                                Toast.LENGTH_SHORT).show();
                        return;
                    }
                    if (displayId > 0) {
                        activateDisplay(displayId);
                        Toast.makeText(MainActivity.this, "已设为主屏 · 连入的外设全部控制这块屏",
                                Toast.LENGTH_SHORT).show();
                    }
                } catch (Throwable t) {
                    Log.w(TAG, "setMainDisplay: " + t);
                }
            });
        }

        @android.webkit.JavascriptInterface
        public String getAvailableDisplays() {
            return getAvailableDisplaysJson();
        }

        @android.webkit.JavascriptInterface
        public String getPeripherals() {
            try {
                return getPeripheralsListJson().toString();
            } catch (Throwable t) {
                return "[]";
            }
        }

        @android.webkit.JavascriptInterface
        public void setPeripheralDisplay(final String peripheralId, final int displayId) {
            runOnUiThread(() -> {
                try {
                    CanghaiStore.setPeripheralDisplay(MainActivity.this, peripheralId, displayId);
                    PeripheralBrain.getInstance().setDeviceTargetDisplay(peripheralId, displayId);
                    /* 拖的是「真实外设 → 真实外屏」这条关系本身：特权链路下交给唯一信源，
                     * 卡片、外接屏光标、大脑层输入投递一起改。改绑要走 root/shizuku shell，
                     * 放子线程，改完再刷一次卡片。 */
                    RealPeripheralBinding real = RealPeripheralBinding.get();
                    if (real.ownsSlot(peripheralId)) {
                        real.setUserTarget(displayId);
                    }
                    if ("virtual_touchpad".equals(peripheralId)) {
                        int fallbackTarget = StreamTarget.UI_ID;
                        if (dexPresentation != null && dexPresentation.isShowing() && dexPresentation.getDisplay() != null) {
                            fallbackTarget = dexPresentation.getDisplay().getDisplayId();
                        } else if (activeDisplayId > 0) {
                            fallbackTarget = activeDisplayId;
                        }
                        int safeTarget = displayId <= 0 ? fallbackTarget : displayId;
                        CanghaiStore.setPeripheralDisplay(MainActivity.this, peripheralId, safeTarget);
                        blackTouchForcedDisplay = safeTarget;
                        resolveBlackTouchTarget(safeTarget);
                        if (isBlackScreenActive()) {
                            refreshBlackTouchTarget(true);
                        }
                        if (safeTarget == StreamTarget.UI_ID && dexPresentation != null) {
                            dexPresentation.hideMouseCursor();
                        }
                        Toast.makeText(MainActivity.this, "黑屏触控已绑定至 " + getDisplayNameForId(safeTarget),
                                Toast.LENGTH_SHORT).show();
                    } else {
                        // 所有真实外设（含 Moonlight 客户端）都由大脑统一改挂载点，不再有按设备名开的特例分支。
                        if (dexPresentation != null && !isBlackTouchActive()) {
                            dexPresentation.hideMouseCursor();
                        }
                        Toast.makeText(MainActivity.this, "外设已绑定至 " + getDisplayNameForId(displayId),
                                Toast.LENGTH_SHORT).show();
                    }
                    syncToPhoneWeb();
                } catch (Throwable t) {
                    Log.w(TAG, "setPeripheralDisplay err: " + t);
                }
            });
        }

        // ==========================================================
        // 处理用户选中的压缩包
        // ==========================================================

        @android.webkit.JavascriptInterface
        public String getPendingIncomingFiles() {
            String p = pendingIncomingFilesJson;
            pendingIncomingFilesJson = null;
            return p == null ? "[]" : p;
        }

        @android.webkit.JavascriptInterface
        public String getLanguage() {
            return CanghaiStore.getLanguage(MainActivity.this);
        }

        @android.webkit.JavascriptInterface
        public void setLanguage(final String lang) {
            runOnUiThread(() -> {
                CanghaiStore.setLanguage(MainActivity.this, lang);
                if (webPhoneHome != null) {
                    webPhoneHome.evaluateJavascript("window.CHI18n && window.CHI18n.setLang && window.CHI18n.setLang("
                            + org.json.JSONObject.quote(lang) + ");", null);
                }
                DexHomeActivity.notifyLanguageChanged(lang);
            });
        }

        /** 最近一次选中的文件 uri，页面解不开 zip 时拿它走原生解压。 */
        @android.webkit.JavascriptInterface
        public String getPickedUri() {
            return lastPickedUri == null ? "" : lastPickedUri;
        }

        /**
         * 用 java.util.zip 原生解开用户选中的压缩包。
         *
         * 为什么不在页面里解：这台老机器 WebView 只有 72，DecompressionStream 是 Chrome 80
         * 才有的 API，页面自带的 inflate 一遇到 deflate 压缩就抛 no-deflate，
         * 用户看到的就是「选了包没反应」。原生解压没有版本限制。
         *
         * 返回 {"ok":true,"files":[{"n":"路径","b":"base64"}]} 或 {"ok":false,"err":"原因"}。
         */
        @android.webkit.JavascriptInterface
        public String unzipUri(String uriStr) {
            return CanghaiStore.unzipUriJson(MainActivity.this, uriStr);
        }

        @android.webkit.JavascriptInterface
        public String unzipBase64(String base64) {
            return CanghaiStore.unzipBase64Json(base64);
        }

        @android.webkit.JavascriptInterface
        public boolean disconnectDisplay(final int displayId) {
            runOnUiThread(() -> {
                try {
                    if (displayId == StreamTarget.UI_ID) {
                        Log.i(TAG, "手机端请求断开 Moonlight 设备，释放串流与虚拟屏");
                        try {
                            AosenMoonlightService.stop(MainActivity.this);
                        } catch (Throwable ignored) {}
                        try {
                            com.alkaidlab.sunshine.service.SunshineRuntimeManager rt =
                                    com.alkaidlab.sunshine.service.SunshineRuntimeManager.getInstance();
                            rt.onEngineDisconnected();
                        } catch (Throwable ignored) {}
                        ShizukuCmd.releaseAllTrackedDisplays();
                        if (activeDisplayId == StreamTarget.UI_ID) activeDisplayId = -1;
                        syncToPhoneWeb();
                        Toast.makeText(MainActivity.this, "已断开 Moonlight · 串流与虚拟屏已释放", Toast.LENGTH_SHORT).show();
                        return;
                    }
                    if (displayId > 0) {
                        Log.i(TAG, "手机端请求断开外接屏: " + displayId);
                        manuallyDisconnectedDisplays.add(displayId);
                        if (dexPresentation != null && dexPresentation.getDisplay().getDisplayId() == displayId) {
                            dexPresentation.destroyDeXVirtualDisplay();
                            dexPresentation.dismiss();
                            dexPresentation = null;
                        }
                        ShizukuCmd.releaseVirtualDisplay(displayId);
                        ShizukuCmd.onDisplayReleased(displayId);
                        if (activeDisplayId == displayId) activeDisplayId = -1;
                        syncToPhoneWeb();
                        Toast.makeText(MainActivity.this, "已断开外接屏 · 对应虚拟屏已释放", Toast.LENGTH_SHORT).show();
                    }
                } catch (Throwable t) {
                    Log.w(TAG, "disconnectDisplay failed: " + t.getMessage());
                }
            });
            return true;
        }

        /** 重新连接外接屏 */
        @android.webkit.JavascriptInterface
        public boolean reconnectDisplay(final int displayId) {
            runOnUiThread(() -> {
                try {
                    Log.i(TAG, "手机端请求重新连接外接屏: " + displayId);
                    manuallyDisconnectedDisplays.remove(displayId);
                    detectAndMountExternalDisplay();
                    syncToPhoneWeb();
                    Toast.makeText(MainActivity.this, "正在重新连接外接屏幕…", Toast.LENGTH_SHORT).show();
                } catch (Throwable t) {
                    Log.w(TAG, "reconnectDisplay failed: " + t.getMessage());
                }
            });
            return true;
        }

        /** 提交 Moonlight PIN 码 */
        @android.webkit.JavascriptInterface
        public boolean submitMoonlightPin(final String pin) {
            try {
                com.alkaidlab.sunshine.service.SunshineRuntimeManager rt =
                        com.alkaidlab.sunshine.service.SunshineRuntimeManager.getInstance();
                boolean ok = rt.submitPin(pin != null ? pin.trim() : "");
                if (ok) {
                    pinDialogShowing = false;
                }
                return ok;
            } catch (Throwable t) {
                Log.w(TAG, "submitMoonlightPin failed: " + t.getMessage());
                return false;
            }
        }

        /** 取消/忽略 Moonlight PIN 配对 */
        @android.webkit.JavascriptInterface
        public void cancelMoonlightPin() {
            pinDialogShowing = false;
            /* 只把本地标志清掉是不够的：引擎那边的 pinRequested 还是非空，
               1 秒轮询一读又弹，点取消就变成无限弹窗。必须把请求本身丢弃。 */
            try {
                com.alkaidlab.sunshine.service.SunshineRuntimeManager.getInstance()
                        .dismissPinRequest();
            } catch (Throwable t) {
                Log.w(TAG, "dismissPinRequest failed: " + t.getMessage());
            }
            Log.i(TAG, "用户取消 Moonlight 配对，已丢弃本次 PIN 请求");
        }

        /** 打开系统的输入法设置页（无特权时唯一的路，也是用户想换回去的地方） */
        @android.webkit.JavascriptInterface
        public void openImeSettings() {
            runOnUiThread(() -> {
                try {
                    startActivity(new Intent(Settings.ACTION_INPUT_METHOD_SETTINGS)
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
                } catch (Throwable t) {
                    Toast.makeText(MainActivity.this, "打不开输入法设置页",
                            Toast.LENGTH_SHORT).show();
                }
            });
        }

        /** 向目标显示屏发送经典导航按键（多任务、主页、返回） */
        @android.webkit.JavascriptInterface
        public void sendNavKeyToDisplay(final int displayId, final String key) {
            int keyCode;
            if ("recent".equalsIgnoreCase(key) || "app_switch".equalsIgnoreCase(key)) {
                keyCode = KeyEvent.KEYCODE_APP_SWITCH; // 187
            } else if ("home".equalsIgnoreCase(key)) {
                keyCode = KeyEvent.KEYCODE_HOME; // 3
            } else if ("back".equalsIgnoreCase(key)) {
                keyCode = KeyEvent.KEYCODE_BACK; // 4
            } else {
                return;
            }
            final int targetId = resolveDisplayForInput(displayId);
            if (targetId <= 0) return;
            new Thread(() -> {
                try {
                    long now = SystemClock.uptimeMillis();
                    KeyEvent down = new KeyEvent(now, now, KeyEvent.ACTION_DOWN, keyCode, 0);
                    KeyEvent up = new KeyEvent(now, now + 40, KeyEvent.ACTION_UP, keyCode, 0);
                    boolean ok = ShizukuCmd.injectKeyEvent(down, targetId) && ShizukuCmd.injectKeyEvent(up, targetId);
                    if (!ok) {
                        ShizukuCmd.execute(String.format(Locale.US, "input -d %d keyevent %d", targetId, keyCode));
                    }
                    Log.i(TAG, "向显示屏 displayId=" + targetId + " 发送导航键 key=" + key + " (keyCode=" + keyCode + ")");
                } catch (Throwable t) {
                    Log.w(TAG, "sendNavKeyToDisplay failed: " + t.getMessage());
                }
            }).start();
        }

        // ==========================================================
        // 沧海单一数据源：手机端与「沧海系统」外屏读同一份，绝不各存一套
        // ==========================================================

        /** 应用注册表 / HTML 软件 / 鼠标样式库 / 壁纸 / 网格，一次全给。 */
        @android.webkit.JavascriptInterface
        public String getState() {
            try {
                return CanghaiStore.stateJson(MainActivity.this);
            } catch (Throwable t) {
                return "{}";
            }
        }

        /** 权限与运行环境（Root / Shizuku / 悬浮窗 / 无障碍 / 读应用列表）。 */
        @android.webkit.JavascriptInterface
        public String getPerms() {
            try {
                org.json.JSONObject o = new org.json.JSONObject();
                o.put("root", RootCmd.isAvailable());
                o.put("shizuku", isShizukuReady());
                o.put("shizukuGranted", isShizukuGranted());
                o.put("privilegeMode", PrivilegeRoute.mode().name());
                o.put("overlay", isOverlayPermissionGranted());
                o.put("a11y", isAccessibilityServiceEnabled());
                o.put("network", isLocalNetworkPermissionGranted());
                o.put("battery", isIgnoringBatteryOptimizations());
                o.put("shortcut", hasShortcutPermission());
                return o.toString();
            } catch (Throwable t) {
                return "{}";
            }
        }

        /** 读一个已装沧海软件的 HTML 正文（懒加载，列表只带元数据）。
            源码工程包在这里统一注入内置 three 运行时，保证预览也能跑起来。 */
        @android.webkit.JavascriptInterface
        public String getHtmlApp(final String id) {
            try {
                String s = CanghaiStore.readHtmlAppForRun(MainActivity.this, id);
                return s == null ? "" : s;
            } catch (Throwable t) {
                return "";
            }
        }

        /** 装一个 HTML 软件，返回它的 id（失败返回空串）。png 是页面渲染好的图标 data URL。 */
        @android.webkit.JavascriptInterface
        public String installHtml(final String name, final String html, final String iconSvg, final String png) {
            try {
                String id = CanghaiStore.installHtml(MainActivity.this, name, html, iconSvg, png);
                if (id != null) CanghaiStore.notifyDataChanged(MainActivity.this);
                return id == null ? "" : id;
            } catch (Throwable t) {
                return "";
            }
        }

        /**
         * 导出 .沧海软件：页面把 zip 打包成 base64，这里拉起系统「另存为」，
         * 让用户自己挑目录和文件名，写完再回调页面显示真实结果。
         */
        @android.webkit.JavascriptInterface
        public boolean exportPackage(final String name, final String base64) {
            try {
                return CanghaiStore.beginExport(MainActivity.this, REQ_EXPORT,
                        name, base64, webPhoneHome);
            } catch (Throwable t) {
                Log.w(TAG, "exportPackage: " + t.getMessage());
                return false;
            }
        }

        /** 在手机上直接打开一个已安装的 Android 应用。返回 "ok" / "no"。 */
        @android.webkit.JavascriptInterface
        public String launchNativeApp(final String pkg) {
            try {
                Intent i = getPackageManager().getLaunchIntentForPackage(pkg == null ? "" : pkg);
                if (i == null) return "no";
                i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                startActivity(i);
                return "ok";
            } catch (Throwable t) {
                Log.w(TAG, "launchNativeApp: " + t.getMessage());
                return "no";
            }
        }

        /** 打开系统「应用信息」设置页（用户在那里管权限、存储、卸载）。 */
        @android.webkit.JavascriptInterface
        public void openAppDetails(final String pkg) {
            try {
                Intent i = new Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                        Uri.parse("package:" + pkg));
                i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                startActivity(i);
            } catch (Throwable t) {
                Log.w(TAG, "openAppDetails: " + t.getMessage());
            }
        }

        /** 走系统卸载确认框卸载一个 Android 应用（系统会再让用户确认一次）。 */
        @android.webkit.JavascriptInterface
        public void uninstallNativeApp(final String pkg) {
            try {
                Intent i = new Intent(Intent.ACTION_DELETE, Uri.parse("package:" + pkg));
                i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                startActivity(i);
            } catch (Throwable t) {
                Log.w(TAG, "uninstallNativeApp: " + t.getMessage());
            }
        }

        /**
         * 导出手机应用的 APK：拉起系统「另存为」，用户选目录后由 Native
         * 把安装包源文件流式拷过去（APK 可能几十 MB，不走 base64 桥）。
         */
        @android.webkit.JavascriptInterface
        public boolean exportApk(final String pkg) {
            try {
                android.content.pm.PackageInfo pi =
                        getPackageManager().getPackageInfo(pkg == null ? "" : pkg, 0);
                final String src = pi.applicationInfo != null ? pi.applicationInfo.sourceDir : null;
                if (src == null) return false;
                CharSequence label = pi.applicationInfo.loadLabel(getPackageManager());
                String name = String.valueOf(label == null ? "" : label)
                        .replaceAll("[\\\\/:*?\"<>|\\r\\n]", "_").trim();
                if (name.isEmpty()) name = pkg;
                synchronized (APK_EXPORT_LOCK) {
                    pendingApkSource = src;
                    pendingApkName = name;
                }
                final String apkTitle = name;
                runOnUiThread(() -> {
                    try {
                        Intent i = new Intent(Intent.ACTION_CREATE_DOCUMENT);
                        i.addCategory(Intent.CATEGORY_OPENABLE);
                        i.setType("application/octet-stream");
                        i.putExtra(Intent.EXTRA_TITLE, apkTitle + ".apk");
                        try {
                            i.putExtra("android.provider.extra.INITIAL_URI", Uri.parse(
                                    "content://com.android.externalstorage.documents/document/primary%3ADownload"));
                        } catch (Throwable ignored) {}
                        startActivityForResult(i, REQ_EXPORT_APK);
                    } catch (Throwable t) {
                        Log.w(TAG, "exportApk: " + t.getMessage());
                        reportApkExport(false, "打不开系统保存界面：" + t.getMessage());
                    }
                });
                return true;
            } catch (Throwable t) {
                Log.w(TAG, "exportApk: " + t.getMessage());
                return false;
            }
        }

        /**
         * 读内置的共享资源文本（目前是 three 运行时）。页面内运行器兜底时要把
         * 运行时拼进 HTML，独立窗口则由 HtmlAppActivity 自己在 native 侧拼。
         * 只允许 canghai/shared/ 下的资源，避免被当成任意文件读取。
         */
        @android.webkit.JavascriptInterface
        public String readSharedAsset(final String rel) {
            if (rel == null || !rel.matches("[A-Za-z0-9_./-]+")
                    || rel.contains("..") || !rel.startsWith("canghai/shared/")) {
                return "";
            }
            try (java.io.InputStream in = getAssets().open(rel)) {
                java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
                byte[] buf = new byte[16384];
                int n;
                while ((n = in.read(buf)) > 0) bos.write(buf, 0, n);
                return new String(bos.toByteArray(), java.nio.charset.StandardCharsets.UTF_8);
            } catch (Throwable t) {
                Log.w(TAG, "readSharedAsset " + rel + ": " + t);
                return "";
            }
        }

        /** 给沧海软件改名，返回新名字（失败返回空串）。 */
        @android.webkit.JavascriptInterface
        public String renameHtml(final String id, final String name) {
            try {
                String n = CanghaiStore.renameHtml(MainActivity.this, id, name);
                if (n != null) CanghaiStore.notifyDataChanged(MainActivity.this);
                return n == null ? "" : n;
            } catch (Throwable t) {
                return "";
            }
        }

        /** 换沧海软件的图标：svg 源码 + 页面渲染好的 PNG data URL。 */
        @android.webkit.JavascriptInterface
        public boolean setHtmlIcon(final String id, final String iconSvg, final String png) {
            try {
                boolean ok = CanghaiStore.setHtmlIcon(MainActivity.this, id, iconSvg, png);
                if (ok) CanghaiStore.notifyDataChanged(MainActivity.this);
                return ok;
            } catch (Throwable t) {
                return false;
            }
        }

        /** 锁定沧海软件横竖屏：auto / portrait / landscape。已开着的窗口立即生效。 */
        @android.webkit.JavascriptInterface
        public boolean setHtmlOrientation(final String id, final String mode) {
            try {
                boolean ok = CanghaiStore.setHtmlOrientation(MainActivity.this, id, mode);
                if (ok) {
                    HtmlAppActivity.applyOrientationTo(MainActivity.this, id, mode);
                    CanghaiStore.notifyDataChanged(MainActivity.this);
                }
                return ok;
            } catch (Throwable t) {
                return false;
            }
        }

        /**
         * 在独立窗口里打开一个沧海软件（多开：一次一个新 Task）。
         * 内置应用（assets/canghai/apps/）与装入的应用走同一条路，窗口表现一致。
         * 返回 "ok" / "gone"（应用不存在）/ "fail"。
         *
         * 注意：@JavascriptInterface 的方法参数个数必须与 JS 调用完全一致，
         * 多传一个参数 Chromium 会抛异常、JS 侧只能拿到 null —— 之前 1 个参数的版本
         * 被传了 4 个参数，所以永远开不出窗口，只能退回页内 iframe。
         */
        @android.webkit.JavascriptInterface
        public String openHtmlAppWindow(final String appId, final String title) {
            Log.i(TAG, "openHtmlAppWindow appId=" + appId + " title=" + title);
            synchronized (mWindowLock) { mWindowResult = null; }
            runOnUiThread(() -> {
                try {
                    boolean installed = appId != null && CanghaiStore.readHtmlApp(MainActivity.this, appId) != null;
                    boolean builtin = appId != null && CanghaiStore.isBuiltinApp(MainActivity.this, appId);
                    if (!installed && !builtin) {
                        Log.w(TAG, "openHtmlAppWindow 找不到应用 " + appId);
                        Toast.makeText(MainActivity.this, "这个应用已经被移除了", Toast.LENGTH_SHORT).show();
                        setResultReady("gone");
                        return;
                    }
                    Intent open = new Intent(MainActivity.this, HtmlAppActivity.class);
                    open.putExtra(HtmlAppActivity.EXTRA_APP_ID, appId);
                    String nm = (title == null || title.trim().isEmpty()) ? findHtmlAppName(appId) : title;
                    open.putExtra(HtmlAppActivity.EXTRA_APP_TITLE, nm);
                    /* 这个应用已经开在后台（收起来没销毁）就直接提回来：
                       页面里的游戏、填了一半的表单都还在，不会重新加载。 */
                    if (HtmlAppActivity.focusExistingWindow(MainActivity.this, appId)) {
                        Log.i(TAG, "复用已开着的窗口 " + appId);
                        setResultReady("ok");
                        return;
                    }
                    open.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_MULTIPLE_TASK);
                    startActivity(open);
                    setResultReady("ok");
                } catch (Throwable t) {
                    Log.w(TAG, "openHtmlAppWindow 启动失败 " + appId, t);
                    CanghaiErrorCollector.logExt("[应用壳] 打开失败 " + appId + ": " + t);
                    setResultReady("fail");
                }
            });
            return waitResult();
        }

        /* openHtmlAppWindow 的同步回执：JS 桥是同步调用，页面要拿结果决定提示 */
        private final Object mWindowLock = new Object();
        private String mWindowResult = null;
        private void setResultReady(String r) {
            synchronized (mWindowLock) {
                mWindowResult = r;
                mWindowLock.notifyAll();
            }
        }
        private String waitResult() {
            synchronized (mWindowLock) {
                long deadline = System.currentTimeMillis() + 3000;
                while (mWindowResult == null && System.currentTimeMillis() < deadline) {
                    try {
                        mWindowLock.wait(deadline - System.currentTimeMillis());
                    } catch (InterruptedException ignored) {
                    }
                }
                return mWindowResult != null ? mWindowResult : "ok";
            }
        }

        @android.webkit.JavascriptInterface
        public void removeHtml(final String id) {
            try {
                CanghaiStore.removeHtml(MainActivity.this, id);
                CanghaiStore.notifyDataChanged(MainActivity.this);
                /* 指向它的桌面快捷方式一起禁用：否则软件卸了，
                   手机桌面上的图标还留着，点开只会报错。 */
                try {
                    android.content.pm.ShortcutManager sm =
                            getSystemService(android.content.pm.ShortcutManager.class);
                    if (sm != null) {
                        java.util.List<String> ids = new java.util.ArrayList<>();
                        ids.add("ch_app_" + id);
                        sm.disableShortcuts(ids, null);
                    }
                } catch (Throwable ignored) {}
            } catch (Throwable ignored) {
            }
        }

        /** 存/改一个自定义鼠标样式（两端共用同一份样式库）。 */
        @android.webkit.JavascriptInterface
        public String saveCursor(final String id, final String name, final String svg,
                                  final int hotX, final int hotY) {
            String real = "";
            try {
                real = CanghaiStore.upsertCursor(MainActivity.this, id, name, svg, hotX, hotY);
            } catch (Throwable t) {
                return "";
            }
            DexHomeActivity.notifyCursorPrefsChanged();
            return real == null ? "" : real;
        }

        @android.webkit.JavascriptInterface
        public void removeCursor(final String id) {
            try {
                CanghaiStore.removeCursor(MainActivity.this, id);
            } catch (Throwable ignored) {
            }
            DexHomeActivity.notifyCursorPrefsChanged();
        }

        @android.webkit.JavascriptInterface
        public void selectCursor(final String id) {
            selectCursorForDisplay(0, id);
        }

        @android.webkit.JavascriptInterface
        public void selectCursorForDisplay(final int displayId, final String id) {
            // displayId=0 明确代表「手机自身屏幕」，绝不能再拿 activeDisplayId 顶替，
            // 否则手机屏上的光标样式设置会写到外屏，自己永远是默认值。
            final int targetDisp = displayId > 0 ? displayId : 0;
            try {
                CanghaiStore.selectCursorForDisplay(MainActivity.this, targetDisp, id);
                if (displayId == StreamTarget.UI_ID) {
                    try {
                        com.alkaidlab.sunshine.service.SunshineRuntimeManager mgr = com.alkaidlab.sunshine.service.SunshineRuntimeManager.getInstance();
                        if (mgr != null && mgr.getVirtualScreenState().getValue().getActive()) {
                            int vid = mgr.getVirtualScreenState().getValue().getDisplayId();
                            if (vid > 0) CanghaiStore.selectCursorForDisplay(MainActivity.this, vid, id);
                        }
                    } catch (Throwable ignored) {}
                }
                if (dexPresentation != null && dexPresentation.getDisplay() != null
                        && dexPresentation.getDisplay().getDisplayId() == targetDisp
                        && dexPresentation.getVirtualDisplayId() > 0) {
                    CanghaiStore.selectCursorForDisplay(MainActivity.this,
                            dexPresentation.getVirtualDisplayId(), id);
                }
            } catch (Throwable ignored) {
            }
            EmptyPointerFrameLayout.clearThemedPointerCache();
            DexHomeActivity.notifyCursorPrefsChanged();
            new Thread(() -> {
                if (targetDisp > 0) ShizukuCmd.hideSystemPointerSprite(targetDisp);
                if (dexPresentation != null) {
                    if (dexPresentation.getDisplay() != null) {
                        ShizukuCmd.hideSystemPointerSprite(dexPresentation.getDisplay().getDisplayId());
                    }
                    if (dexPresentation.getVirtualDisplayId() > 0) {
                        ShizukuCmd.hideSystemPointerSprite(dexPresentation.getVirtualDisplayId());
                    }
                }
            }, "push-cursor-sprite").start();
            if (dexPresentation != null) {
                dexPresentation.refreshCursorPrefs();
            }
            syncToPhoneWeb();
        }

        @android.webkit.JavascriptInterface
        public void setCursorSize(final int px) {
            setCursorSizeForDisplay(0, px);
        }

        /** 按指定屏幕单独保存指针大小；0 = 手机自身屏幕，不再被当前外屏顶替。 */
        @android.webkit.JavascriptInterface
        public void setCursorSizeForDisplay(final int displayId, final int px) {
            final int targetDisp = displayId > 0 ? displayId : 0;
            try {
                CanghaiStore.setCursorSizeForDisplay(MainActivity.this, targetDisp, px);
                if (displayId == StreamTarget.UI_ID) {
                    try {
                        com.alkaidlab.sunshine.service.SunshineRuntimeManager mgr = com.alkaidlab.sunshine.service.SunshineRuntimeManager.getInstance();
                        if (mgr != null && mgr.getVirtualScreenState().getValue().getActive()) {
                            int vid = mgr.getVirtualScreenState().getValue().getDisplayId();
                            if (vid > 0) CanghaiStore.setCursorSizeForDisplay(MainActivity.this, vid, px);
                        }
                    } catch (Throwable ignored) {}
                }
                if (dexPresentation != null && dexPresentation.getDisplay() != null
                        && dexPresentation.getDisplay().getDisplayId() == targetDisp
                        && dexPresentation.getVirtualDisplayId() > 0) {
                    CanghaiStore.setCursorSizeForDisplay(MainActivity.this,
                            dexPresentation.getVirtualDisplayId(), px);
                }
            } catch (Throwable ignored) {
            }
            EmptyPointerFrameLayout.clearThemedPointerCache();
            DexHomeActivity.notifyCursorPrefsChanged();
            new Thread(() -> {
                if (targetDisp > 0) ShizukuCmd.hideSystemPointerSprite(targetDisp);
                if (dexPresentation != null) {
                    if (dexPresentation.getDisplay() != null) {
                        ShizukuCmd.hideSystemPointerSprite(dexPresentation.getDisplay().getDisplayId());
                    }
                    if (dexPresentation.getVirtualDisplayId() > 0) {
                        ShizukuCmd.hideSystemPointerSprite(dexPresentation.getVirtualDisplayId());
                    }
                }
            }, "push-cursor-sprite").start();
            if (dexPresentation != null) {
                dexPresentation.refreshCursorPrefs();
            }
            syncToPhoneWeb();
        }

        @android.webkit.JavascriptInterface
        public int getCursorSizeForDisplay(final int displayId) {
            try {
                return CanghaiStore.cursorSizeForDisplay(MainActivity.this, displayId);
            } catch (Throwable t) {
                return 28;
            }
        }

        @android.webkit.JavascriptInterface
        public void setUserProfile(final String name, final String avatar) {
            try {
                if (name != null) CanghaiStore.setUserName(MainActivity.this, name);
                if (avatar != null) CanghaiStore.setUserAvatar(MainActivity.this, avatar);
                CanghaiStore.notifyDataChanged(MainActivity.this);
                syncToPhoneWeb();
            } catch (Throwable ignored) {}
        }

        @android.webkit.JavascriptInterface
        public String getUserProfile() {
            try {
                org.json.JSONObject o = new org.json.JSONObject();
                o.put("userName", CanghaiStore.userName(MainActivity.this));
                o.put("userAvatar", CanghaiStore.userAvatar(MainActivity.this));
                return o.toString();
            } catch (Throwable t) {
                return "{}";
            }
        }

        @android.webkit.JavascriptInterface
        public void setCursorSpeed(final float v) {
            try {
                CanghaiStore.setCursorSpeed(MainActivity.this, v);
            } catch (Throwable ignored) {
            }
            EmptyPointerFrameLayout.clearThemedPointerCache();
            DexHomeActivity.notifyCursorPrefsChanged();
            final int tDisp1 = activeDisplayId > 0 ? activeDisplayId : 0;
            new Thread(() -> {
                if (tDisp1 > 0) ShizukuCmd.hideSystemPointerSprite(tDisp1);
                if (dexPresentation != null) {
                    if (dexPresentation.getDisplay() != null) {
                        ShizukuCmd.hideSystemPointerSprite(dexPresentation.getDisplay().getDisplayId());
                    }
                    if (dexPresentation.getVirtualDisplayId() > 0) {
                        ShizukuCmd.hideSystemPointerSprite(dexPresentation.getVirtualDisplayId());
                    }
                }
            }, "push-cursor-sprite").start();
            if (dexPresentation != null) {
                dexPresentation.refreshCursorPrefs();
            }
            syncToPhoneWeb();
        }

        @android.webkit.JavascriptInterface
        public String getAboutInfo() {
            return CanghaiStore.aboutInfoJson(MainActivity.this);
        }

        @android.webkit.JavascriptInterface
        public String checkUpdate() {
            String r = CanghaiStore.checkUpdateJson(MainActivity.this);
            /* 手动检查同样落盘：重置 7 天计时，绿点跟着结果走 */
            CanghaiStore.recordUpdateResult(MainActivity.this, r);
            return r;
        }

        /** 页面启动拉一次 7 天自动检查的落盘结果，只用来点亮绿点。 */
        @android.webkit.JavascriptInterface
        public String getUpdateHint() {
            return CanghaiStore.updateHintJson(MainActivity.this);
        }

        @android.webkit.JavascriptInterface
        public void openUpdateUrl(final String url) {
            runOnUiThread(() -> {
                try {
                    if (url == null || !url.startsWith("https://")) return;
                    android.content.Intent it = new android.content.Intent(
                            android.content.Intent.ACTION_VIEW, android.net.Uri.parse(url));
                    it.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK);
                    startActivity(it);
                } catch (Throwable ignored) {
                }
            });
        }

        /**
         * 把沧海软件投到某块屏：桌面活着就直接让它以窗口打开，
         * 没活着就留一次意图，等桌面开机自己取走。
         */
        @android.webkit.JavascriptInterface
        public void launchHtmlOnDisplay(final String appId, final int reqDisplayId) {
            runOnUiThread(() -> {
                try {
                    CanghaiStore.setPendingOpen(MainActivity.this, appId);
                    int target = reqDisplayId;
                    if (target <= 0) target = (activeDisplayId > 0) ? activeDisplayId : 0;
                    DexHomeActivity dexHome = DexHomeActivity.findForDisplay(target);
                    if (dexHome != null) {
                        DexHomeActivity.postOpenApp(appId);
                        Toast.makeText(MainActivity.this, "已在大屏窗口打开", Toast.LENGTH_SHORT).show();
                        return;
                    }
                    launchAppOnDisplay(MainActivity.this.getPackageName() + "/.DexHomeActivity", target);
                    Toast.makeText(MainActivity.this, "正在把沧海系统投到屏幕", Toast.LENGTH_SHORT).show();
                } catch (Throwable t) {
                    Toast.makeText(MainActivity.this, "投屏失败: " + t.getMessage(), Toast.LENGTH_SHORT).show();
                }
            });
        }

        @android.webkit.JavascriptInterface
        public void copyText(final String text) {            runOnUiThread(() -> {
                try {
                    android.content.ClipboardManager cm = (android.content.ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
                    if (cm != null) {
                        cm.setPrimaryClip(android.content.ClipData.newPlainText("text", text));
                        Toast.makeText(MainActivity.this, "已复制：" + text, Toast.LENGTH_SHORT).show();
                    }
                } catch (Throwable ignored) {}
            });
        }

        @android.webkit.JavascriptInterface
        public void requestShizuku() {
            runOnUiThread(() -> {
                try {
                    if (Shizuku.pingBinder()) {
                        // Shizuku 服务活着就直接弹系统授权确认框，不用用户去 Shizuku App 里点
                        Shizuku.requestPermission(1001);
                        return;
                    }
                    // 服务没跑：Root 环境下后台拉起服务，回头提示再点一次
                    RootCmd.autoStartShizukuIfNeeded();
                    android.content.Intent intent = getPackageManager().getLaunchIntentForPackage("moe.shizuku.privileged.api");
                    if (intent != null) startActivity(intent);
                    Toast.makeText(MainActivity.this,
                            "Shizuku 服务未运行，已尝试启动；稍等几秒再点一次就能直接授权",
                            Toast.LENGTH_LONG).show();
                } catch (Throwable t) {
                    Toast.makeText(MainActivity.this, "无法调起 Shizuku 授权: " + t.getMessage(), Toast.LENGTH_SHORT).show();
                }
            });
        }

        @android.webkit.JavascriptInterface
        public void requestShizukuPermission() {
            runOnUiThread(() -> {
                try {
                    if (!Shizuku.pingBinder()) {
                        // Root 环境下可直接后台拉起 Shizuku 服务，免去用户手动打开 App
                        RootCmd.autoStartShizukuIfNeeded();
                        Toast.makeText(MainActivity.this,
                                "Shizuku 服务未运行，已尝试启动；请稍后再点一次，或打开 Shizuku App 启动服务",
                                Toast.LENGTH_LONG).show();
                        return;
                    }
                    Shizuku.requestPermission(1001);
                } catch (Throwable t) {
                    Toast.makeText(MainActivity.this,
                            "申请 Shizuku 授权失败: " + t.getMessage(), Toast.LENGTH_LONG).show();
                }
            });
        }

        @android.webkit.JavascriptInterface
        public void requestRoot() {
            // 旧实现只读 RootCmd.isAvailable() 缓存，点了永远提示「未获取」；改为强制重探
            runOnUiThread(() -> refreshRootPermission(true));
        }

        @android.webkit.JavascriptInterface
        public void openOverlay() {
            runOnUiThread(() -> {
                try {
                    if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M) {
                        android.content.Intent intent = new android.content.Intent(
                                android.provider.Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                                android.net.Uri.parse("package:" + getPackageName())
                        );
                        startActivity(intent);
                    }
                } catch (Throwable t) {
                    Toast.makeText(MainActivity.this, "无法打开悬浮窗设置", Toast.LENGTH_SHORT).show();
                }
            });
        }

        @android.webkit.JavascriptInterface
        public String getInstalledApps() {
            try {
                org.json.JSONArray arr = new org.json.JSONArray();
                android.content.pm.PackageManager pm = getPackageManager();
                android.content.Intent mainIntent = new android.content.Intent(android.content.Intent.ACTION_MAIN, null);
                mainIntent.addCategory(android.content.Intent.CATEGORY_LAUNCHER);
                java.util.List<android.content.pm.ResolveInfo> apps = pm.queryIntentActivities(mainIntent, 0);
                for (android.content.pm.ResolveInfo ri : apps) {
                    if (ri.activityInfo == null) continue;
                    String pkg = ri.activityInfo.packageName;
                    if (getPackageName().equals(pkg)) continue;
                    String cls = ri.activityInfo.name;
                    String label = ri.loadLabel(pm).toString();

                    java.io.File iconFile = new java.io.File(getCacheDir(), "appicon_" + pkg + ".png");
                    if (!iconFile.exists()) {
                        try {
                            android.graphics.drawable.Drawable d = ri.loadIcon(pm);
                            android.graphics.Bitmap bmp = null;
                            if (d instanceof android.graphics.drawable.BitmapDrawable) {
                                bmp = ((android.graphics.drawable.BitmapDrawable) d).getBitmap();
                            } else {
                                bmp = android.graphics.Bitmap.createBitmap(Math.max(1, d.getIntrinsicWidth()), Math.max(1, d.getIntrinsicHeight()), android.graphics.Bitmap.Config.ARGB_8888);
                                android.graphics.Canvas canvas = new android.graphics.Canvas(bmp);
                                d.setBounds(0, 0, canvas.getWidth(), canvas.getHeight());
                                d.draw(canvas);
                            }
                            if (bmp != null) {
                                java.io.FileOutputStream fos = new java.io.FileOutputStream(iconFile);
                                bmp.compress(android.graphics.Bitmap.CompressFormat.PNG, 85, fos);
                                fos.flush();
                                fos.close();
                            }
                        } catch (Throwable ignored) {}
                    }

                    org.json.JSONObject o = new org.json.JSONObject();
                    o.put("comp", pkg + "/" + cls);
                    o.put("pkg", pkg);
                    o.put("label", label);
                    o.put("icon", iconFile.exists() ? ("file://" + iconFile.getAbsolutePath()) : "");
                    arr.put(o);
                }
                return arr.toString();
            } catch (Throwable t) {
                return "[]";
            }
        }

        @android.webkit.JavascriptInterface
        public void launchApp(final String comp) {
            launchAppOnDisplay(comp, 0);
        }

        @android.webkit.JavascriptInterface
        public void launchAppOnDisplay(final String comp, final int reqDisplayId) {
            runOnUiThread(() -> {
                try {
                    String pkg = comp.contains("/") ? comp.substring(0, comp.indexOf("/")) : comp;
                    android.content.Intent launchIt = getPackageManager().getLaunchIntentForPackage(pkg);
                    android.content.ComponentName cn = (launchIt != null) ? launchIt.getComponent() : android.content.ComponentName.unflattenFromString(comp);
                    String realComp = (cn != null) ? cn.flattenToShortString() : comp;

                    int targetDisplayId = reqDisplayId;
                    if (targetDisplayId <= 0) {
                        targetDisplayId = (activeDisplayId > 0) ? activeDisplayId
                                : ((dexPresentation != null && dexPresentation.isShowing())
                                        ? dexPresentation.getDisplay().getDisplayId() : 0);
                    }
                    // 若请求的是有线外接物理屏 ID，转换为其承载桌面的虚拟屏 ID
                    if (dexPresentation != null && dexPresentation.isShowing()
                            && dexPresentation.getDisplay() != null
                            && targetDisplayId == dexPresentation.getDisplay().getDisplayId()
                            && dexPresentation.getVirtualDisplayId() > 0) {
                        targetDisplayId = dexPresentation.getVirtualDisplayId();
                    }

                    // 统一路径：若目标屏上正运行自研桌面，直接交由其窗口壳以窗口打开（不再额外执行全屏 am start）
                    final DexHomeActivity dexHome = DexHomeActivity.findForDisplay(targetDisplayId);
                    if (dexHome != null) {
                        final String finalComp = realComp;
                        dexHome.runOnUiThread(() -> {
                            try {
                                dexHome.launchByComponent(finalComp, finalComp);
                            } catch (Throwable ignored) {}
                        });
                        Toast.makeText(MainActivity.this, "已在大屏窗口打开应用", Toast.LENGTH_SHORT).show();
                        return;
                    }

                    // 兜底（官方原生桌面模式）：使用 am start 拉起 Activity 到目标屏幕
                    final int finalDisplayId = targetDisplayId;
                    final String finalCompStr = realComp;
                    new Thread(() -> {
                        try {
                            String cmd = "am start -n " + finalCompStr + " -f 0x10000000";
                            if (finalDisplayId > 0) {
                                cmd += " --display " + finalDisplayId;
                            }
                            if (RootCmd.isAvailable()) {
                                RootCmd.execute(cmd);
                            } else {
                                ShizukuCmd.execute(cmd);
                            }
                        } catch (Throwable t) {
                            Log.w(TAG, "am start error: " + t.getMessage());
                        }
                    }).start();

                    Toast.makeText(MainActivity.this, "已投屏打开应用", Toast.LENGTH_SHORT).show();
                } catch (Throwable t) {
                    Toast.makeText(MainActivity.this, "启动失败: " + t.getMessage(), Toast.LENGTH_SHORT).show();
                }
            });
        }

        @android.webkit.JavascriptInterface
        public void activateDisplay(final int id) {
            runOnUiThread(() -> {
                MainActivity.this.activateDisplay(id);
                syncToPhoneWeb();
            });
        }

        @android.webkit.JavascriptInterface
        public void setDesktopType(final int id, final int type) {
            runOnUiThread(() -> {
                // 旧实现只写 SharedPreferences，从不真正切换桌面，所以两个按钮都「没反应」。
                // setDesktopHome 会写配置、刷新 UI、重建虚拟屏并下发 setHomeType。
                MainActivity.this.setDesktopHome(type == 1 ? HOME_AOSEN : HOME_OFFICIAL);
                syncToPhoneWeb();
            });
        }

        /** 手机端「设置 → 默认投屏桌面」：持久化到 aosen_settings，不再 2 秒后被轮询冲回。 */
        @android.webkit.JavascriptInterface
        public void setDefaultDesktopType(final int type) {
            runOnUiThread(() -> {
                int targetHome = (type == 1) ? HOME_AOSEN : HOME_OFFICIAL;
                SharedPreferences sp = getSharedPreferences("aosen_settings", Context.MODE_PRIVATE);
                sp.edit().putInt(PREF_HOME_TYPE, targetHome).putInt(PREF_WINDOW_MODE, 5).apply();
                updateHomeUI();
                if (isAnyDisplayConnected()) {
                    MainActivity.this.setDesktopHome(targetHome);
                }
                syncToPhoneWeb();
            });
        }

        /** 手机端「光标指针样式」按钮：与外屏桌面左上角的光标设置共用同一份 MouseCursorPrefs。 */
        @android.webkit.JavascriptInterface
        public void setCursorStyle(final int style) {
            runOnUiThread(() -> {
                com.alkaidlab.sunshine.service.MouseCursorPrefs.load(getApplicationContext());
                com.alkaidlab.sunshine.service.MouseCursorPrefs.setStyleIndex(getApplicationContext(), style);
                EmptyPointerFrameLayout.clearThemedPointerCache();
                DexHomeActivity.notifyCursorPrefsChanged();
                new Thread(() -> {
                    if (dexPresentation != null) {
                        if (dexPresentation.getDisplay() != null) {
                            ShizukuCmd.hideSystemPointerSprite(dexPresentation.getDisplay().getDisplayId());
                        }
                        if (dexPresentation.getVirtualDisplayId() > 0) {
                            ShizukuCmd.hideSystemPointerSprite(dexPresentation.getVirtualDisplayId());
                        }
                    }
                }, "push-cursor-sprite-style").start();
                if (dexPresentation != null) {
                    dexPresentation.refreshCursorPrefs();
                }
                syncToPhoneWeb();
            });
        }

        /**
         * 一键黑屏触控板：整屏纯黑，整块屏幕即触控板（手势逻辑见 handleTouchpadEvent /
         * handleMoonlightTouchEvent）。外接屏、沧海桌面虚拟屏、Moonlight 串流虚拟屏都能当目标。
         * 返回 "moon" / "wired" = 已进入且有目标；"waiting" = 已进入、等屏幕接入。
         * 没有外接屏时也照样进黑屏，用户可能是想先进来再连设备。
         */
        @android.webkit.JavascriptInterface
        public String enterBlackTouchpad() {
            int savedTarget = CanghaiStore.getPeripheralDisplay(MainActivity.this, "virtual_touchpad", -1);
            if (savedTarget > 0) {
                blackTouchForcedDisplay = savedTarget;
            } else {
                blackTouchForcedDisplay = -1;
            }
            boolean found = (blackTouchForcedDisplay > 0)
                    ? resolveBlackTouchTarget(blackTouchForcedDisplay)
                    : resolveBlackTouchTarget();
            String mode = found ? (blackTouchViaMoonlight ? "moon" : "wired") : "waiting";
            runOnUiThread(() -> enterBlackScreenMode());
            return mode;
        }

        /**
         * 设备详情里的「一键黑屏触控」：直接控制这块屏，不再要求用户先把它设成激活屏。
         * displayId 来自设备卡的 displayId（Moonlight 卡片是 StreamTarget.UI_ID）。
         * 返回 "moon" / "wired" = 已进入且有目标；"waiting" = 已进入、等这块屏回来。
         * 指定的屏已经拔掉时同样进黑屏等待，不再直接拒绝。
         */
        @android.webkit.JavascriptInterface
        public String enterBlackTouchpadFor(final int displayId) {
            blackTouchForcedDisplay = displayId;
            boolean found = resolveBlackTouchTarget(displayId);
            String mode = found ? (blackTouchViaMoonlight ? "moon" : "wired") : "waiting";
            runOnUiThread(() -> enterBlackScreenMode());
            return mode;
        }

        /** 手机端「系统桌面设置 → 隐藏 Dock」，与外屏桌面共用 dexhome_prefs。 */
        @android.webkit.JavascriptInterface
        public boolean getDockAutoHide() {
            return getSharedPreferences("dexhome_prefs", MODE_PRIVATE)
                    .getBoolean("dock_auto_hide", true);
        }

                @android.webkit.JavascriptInterface
        public String getResolutionsForDisplay(final int dId) {
            try {
                int rw = 1920;
                int rh = 1080;
                String dKey = "disp_" + dId;
                if (dId == StreamTarget.UI_ID) {
                    dKey = "moonlight_resolution";
                    rw = 1920;
                    rh = 1080;
                } else if (dexPresentation != null && dexPresentation.isShowing()) {
                    rw = dexPresentation.getRawWidth();
                    rh = dexPresentation.getRawHeight();
                    dKey = (dexPresentation.getDisplay() != null) ? (dexPresentation.getDisplay().getName() + "_" + rw + "x" + rh) : ("ext_" + rw + "x" + rh);
                } else if (externalWidth > 0 && externalHeight > 0) {
                    rw = externalWidth;
                    rh = externalHeight;
                    dKey = "ext_" + rw + "x" + rh;
                } else if (dId > 0) {
                    DisplayManager dm = (DisplayManager) getSystemService(Context.DISPLAY_SERVICE);
                    Display d = (dm != null) ? dm.getDisplay(dId) : null;
                    if (d != null) {
                        DisplayMetrics m = new DisplayMetrics();
                        d.getRealMetrics(m);
                        rw = m.widthPixels;
                        rh = m.heightPixels;
                        dKey = d.getName() + "_" + rw + "x" + rh;
                    }
                }
                return CanghaiStore.getResolutionOptions(rw, rh, dKey, MainActivity.this).toString();
            } catch (Throwable t) {
                return "[]";
            }
        }

        @android.webkit.JavascriptInterface
        public void setResolutionForDisplay(final int dId, final String resKey) {
            runOnUiThread(() -> {
                try {
                    String dKey = "disp_" + dId;
                    if (dId == StreamTarget.UI_ID) {
                        dKey = "moonlight_resolution";
                        CanghaiStore.setDisplayResolutionKey(MainActivity.this, dKey, resKey);
                        int[] target = CanghaiStore.getTargetResolution(1920, 1080, resKey);
                        Toast.makeText(MainActivity.this, "已设置 Moonlight 串流分辨率: " + resKey + " (" + target[0] + "×" + target[1] + ")", Toast.LENGTH_SHORT).show();
                        syncToPhoneWeb();
                        return;
                    }
                    if (dexPresentation != null && dexPresentation.isShowing()) {
                        int rw = dexPresentation.getRawWidth();
                        int rh = dexPresentation.getRawHeight();
                        dKey = (dexPresentation.getDisplay() != null) ? (dexPresentation.getDisplay().getName() + "_" + rw + "x" + rh) : ("ext_" + rw + "x" + rh);
                        CanghaiStore.setDisplayResolutionKey(MainActivity.this, dKey, resKey);
                        int[] target = CanghaiStore.getTargetResolution(rw, rh, resKey);
                        dexPresentation.setVirtualDisplayResolution(target[0], target[1]);
                        Toast.makeText(MainActivity.this, "已切换外接屏分辨率为 " + resKey + " (" + target[0] + "×" + target[1] + ")", Toast.LENGTH_SHORT).show();
                    } else {
                        CanghaiStore.setDisplayResolutionKey(MainActivity.this, dKey, resKey);
                        Toast.makeText(MainActivity.this, "已保存分辨率设置: " + resKey, Toast.LENGTH_SHORT).show();
                    }
                    syncToPhoneWeb();
                } catch (Throwable t) {
                    Log.w(TAG, "setResolutionForDisplay failed: " + t);
                }
            });
        }

        @android.webkit.JavascriptInterface
        public void setDockAutoHide(final boolean on) {
            getSharedPreferences("dexhome_prefs", MODE_PRIVATE)
                    .edit().putBoolean("dock_auto_hide", on).apply();
            try {
                com.aosen.shizukudex.DexHomeActivity.onDockAutoHideChanged(on);
            } catch (Throwable ignored) { }
        }

        // ==========================================================
        // 桌面快捷方式：可选能力，没权限不拦人，点创建时才提示去授权
        // ==========================================================

        /** 权限状态给页面用来画「可选」标记。 */
        @android.webkit.JavascriptInterface
        public String shortcutStatus() {
            try {
                org.json.JSONObject o = new org.json.JSONObject();
                o.put("perm", hasShortcutPermission());
                o.put("supported", true);
                return o.toString();
            } catch (Throwable t) {
                return "{}";
            }
        }

        /**
         * 创建桌面快捷方式。
         * 返回 {"ok":true} 表示已发起（Android 8+ 系统还会弹一次确认框，由用户决定），
         * {"ok":false,"reason":"perm"} 表示没桌面快捷方式权限、需要用户去授权。
         */
        @android.webkit.JavascriptInterface
        public String createShortcut(String appId, String name) {
            try {
                org.json.JSONObject o = new org.json.JSONObject();
                if (appId == null || appId.isEmpty()) {
                    o.put("ok", false);
                    o.put("reason", "fail");
                    o.put("msg", "没有指定应用");
                    return o.toString();
                }
                if (!hasShortcutPermission()) {
                    o.put("ok", false);
                    o.put("reason", "perm");
                    o.put("msg", "没有创建桌面快捷方式的权限");
                    return o.toString();
                }
                if (CanghaiStore.readHtmlApp(MainActivity.this, appId) == null) {
                    o.put("ok", false);
                    o.put("reason", "gone");
                    o.put("msg", "这个应用已经被移除了");
                    return o.toString();
                }
                final String aId = appId;
                final String aName = (name == null || name.isEmpty()) ? findHtmlAppName(appId) : name;
                /* 图标用用户安装时设置的那个：CanghaiStore 里存了页面渲染好的 PNG。
                   老条目没有 png 就退回应用图标。 */
                final android.graphics.Bitmap aIcon =
                        dataUrlToBitmap(CanghaiStore.htmlIconPng(MainActivity.this, appId));
                o.put("ok", runPinOnUiThread(() -> {
                    Intent t = new Intent(MainActivity.this, MainActivity.class);
                    t.setAction(Intent.ACTION_VIEW);
                    t.putExtra(EXTRA_SHORTCUT_APP, aId);
                    t.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
                    return pinShortcut(t, "ch_app_" + aId, aName, aIcon);
                }));
                return o.toString();
            } catch (Throwable t) {
                try {
                    org.json.JSONObject o = new org.json.JSONObject();
                    o.put("ok", false);
                    o.put("reason", "fail");
                    o.put("msg", String.valueOf(t.getMessage()));
                    return o.toString();
                } catch (Throwable ignored) {
                    return "{\"ok\":false}";
                }
            }
        }

        /**
         * 给已经装好的原生 APK 也加一个桌面入口（它们本身在桌面上有图标，
         * 这里只是补一个「点开直达」的快捷方式）。返回格式同 createShortcut。
         */
        @android.webkit.JavascriptInterface
        public String createAppShortcut(String pkg, String name) {
            try {
                org.json.JSONObject o = new org.json.JSONObject();
                if (pkg == null || pkg.isEmpty()) {
                    o.put("ok", false);
                    o.put("reason", "fail");
                    o.put("msg", "没有指定应用");
                    return o.toString();
                }
                if (!hasShortcutPermission()) {
                    o.put("ok", false);
                    o.put("reason", "perm");
                    o.put("msg", "没有创建桌面快捷方式的权限");
                    return o.toString();
                }
                final Intent target = getPackageManager().getLaunchIntentForPackage(pkg);
                if (target == null) {
                    o.put("ok", false);
                    o.put("reason", "gone");
                    o.put("msg", "这个应用没有启动入口");
                    return o.toString();
                }
                final String aName = (name == null || name.isEmpty()) ? pkg : name;
                final String aId = "ch_pkg_" + pkg;
                /* 原生 APK 用它自己桌面上那颗图标，不用咱们软件的默认图标 */
                final android.graphics.Bitmap aIcon = appIconBitmap(pkg);
                o.put("ok", runPinOnUiThread(() -> {
                    target.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
                    return pinShortcut(target, aId, aName, aIcon);
                }));
                return o.toString();
            } catch (Throwable t) {
                try {
                    org.json.JSONObject o = new org.json.JSONObject();
                    o.put("ok", false);
                    o.put("reason", "fail");
                    o.put("msg", String.valueOf(t.getMessage()));
                    return o.toString();
                } catch (Throwable ignored) {
                    return "{\"ok\":false}";
                }
            }
        }

        /** 打开系统里「桌面快捷方式」相关设置页，方便用户手动给权限。 */
        @android.webkit.JavascriptInterface
        public void openShortcutSettings() {
            runOnUiThread(() -> {
                try {
                    Intent i = new Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                            android.net.Uri.parse("package:" + getPackageName()));
                    i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                    startActivity(i);
                } catch (Throwable ignored) {
                }
            });
        }

        @android.webkit.JavascriptInterface
        public boolean hasErrors() {
            return CanghaiErrorCollector.getInstance().hasErrors();
        }

        @android.webkit.JavascriptInterface
        public int getErrorCount() {
            return CanghaiErrorCollector.getInstance().getErrorCount();
        }

        @android.webkit.JavascriptInterface
        public String getRecentErrorsJson(int maxCount) {
            return CanghaiErrorCollector.getInstance().getRecentErrorsJson(maxCount);
        }

        @android.webkit.JavascriptInterface
        public String exportErrorsAndClear() {
            String path = CanghaiErrorCollector.getInstance().exportToFileAndClear(MainActivity.this);
            if (path != null) {
                return "已导出至: " + path + "\n内存报错已清空";
            }
            return "导出失败或无报错日志";
        }

        @android.webkit.JavascriptInterface
        public void clearErrors() {
            CanghaiErrorCollector.getInstance().clear();
        }

        @android.webkit.JavascriptInterface
        public void showSoftKeyboard() {
            runOnUiThread(() -> {
                try {
                    if (webPhoneHome == null) return;
                    webPhoneHome.setFocusable(true);
                    webPhoneHome.setFocusableInTouchMode(true);
                    webPhoneHome.requestFocus();
                    InputMethodManager imm = (InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
                    if (imm != null) {
                        imm.showSoftInput(webPhoneHome, InputMethodManager.SHOW_IMPLICIT);
                    }
                } catch (Throwable ignored) {}
            });
        }

        @android.webkit.JavascriptInterface
        public void hideSoftKeyboard() {
            runOnUiThread(() -> {
                try {
                    if (webPhoneHome == null) return;
                    InputMethodManager imm = (InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
                    if (imm != null) {
                        imm.hideSoftInputFromWindow(webPhoneHome.getWindowToken(), 0);
                    }
                    webPhoneHome.clearFocus();
                    webPhoneHome.setFocusable(false);
                    webPhoneHome.setFocusableInTouchMode(false);
                    ensurePointerCapture();
                } catch (Throwable ignored) {}
            });
        }

        @android.webkit.JavascriptInterface
        public void reportJsError(String source, String message, String stack) {
            CanghaiErrorCollector.getInstance().log(source != null ? source : "手机端", "ERROR", message, stack != null ? stack : "");
        }
    }
}
