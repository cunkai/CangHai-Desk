package com.aosen.shizukudex;

import android.app.Activity;
import android.content.Context;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;
import android.view.Choreographer;
import android.view.Display;
import android.view.InputDevice;
import android.view.KeyCharacterMap;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.Surface;

import java.util.ArrayDeque;
import java.util.ArrayList;

/**
 * 零权限 Moonlight 画面 / 输入宿主（仅 PrivilegeRoute.Mode.NONE 时启用）。
 *
 * 画面：公开 API DisplayManager.createVirtualDisplay(name,w,h,dpi,surface,0) 把
 * SunshineFrameDispatcher 的采集 Surface 挂成一块私有虚拟屏，然后在同一个进程里
 * 把 DeXPresentation（沧海桌面 + Dock + 自绘虚拟光标）show() 到这块 Display 上。
 * 桌面每一帧由 GPU 直接画进采集 Surface，再分发给 MediaCodec 编码器。
 * 全程不需要 Root / Shizuku / MediaProjection，也不会弹录屏授权框。
 *
 * 输入：Moonlight 键鼠触摸手柄、插在手机上的物理外设产生的事件，全部在主进程里
 * 直接投递给这块 Presentation 的视图树，不经过 IInputManager.injectInputEvent。
 *
 * 有 Root / Shizuku 时本类完全不参与，原有链路一律不变。
 */
public final class LocalMoonlightDisplay {
    private static final String TAG = "Aosen_LocalMoonDisplay";
    private static final String VD_NAME = "Aosen_Moonlight_VD";
    private static final LocalMoonlightDisplay INSTANCE = new LocalMoonlightDisplay();

    public static LocalMoonlightDisplay get() {
        return INSTANCE;
    }

    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private VirtualDisplay virtualDisplay;
    private DeXPresentation presentation;
    private DeXPresentation previousActive;
    private int displayId = -1;
    private int width = 0;
    private int height = 0;
    private int dpi = 0;

    /**
     * 按帧合并的直投队列。
     * 网络输入包可达 100+Hz，逐个 post 到主线程会把消息队列压满，虚拟屏拿不到
     * 新的绘制帧、编码器只能重复旧画面 —— 表现就是「能控制但很卡」。
     * 这里把连续的 MOVE / 相对位移合并到每帧只留最新一份，离散帧（按下、抬起、
     * 滚轮、按键）保持原顺序立即投递，一帧最多一次主线程派发。
     */
    private static final long FRAME_INTERVAL_MS = 16L;
    private final Object frameLock = new Object();
    private final ArrayDeque<FrameEvent> pendingFrames = new ArrayDeque<>();
    private boolean frameScheduled = false;
    private volatile long frameDelay = -1L;
    private volatile boolean remotePressed = false;
    private final Runnable frameFlush = this::flushFrames;
    /** 与 VSYNC 对齐的帧节拍，替代 postDelayed(16ms) 的定时器抖动；主线程 Choreographer。 */
    private volatile Choreographer frameChoreographer;
    private final Choreographer.FrameCallback frameCallback = frameTimeNanos -> flushFrames();

    private static final int ROUTE_JOYSTICK = 0;
    private static final int ROUTE_CURSOR = 1;    // 未按下：只更新自绘光标 + hover
    private static final int ROUTE_TOUCH = 2;     // 按下 / 拖拽 / 抬起：WebView touch
    private static final int ROUTE_GENERIC = 3;   // 滚轮、鼠标按钮等
    private static final int ROUTE_KEY = 4;
    private static final int ROUTE_DELTA = 5;     // 本地物理外设相对位移（累加）
    private static final int ROUTE_CLICK = 6;
    private static final int ROUTE_SCROLL = 7;    // 本地物理外设滚轮（累加）

    private static final int MERGE_CURSOR = 10;
    private static final int MERGE_TOUCH_MOVE = 11;
    private static final int MERGE_DELTA = 12;
    private static final int MERGE_SCROLL = 13;
    private static final int MERGE_JOYSTICK = 14;

    private static final class FrameEvent {
        int route;
        int mergeKey = -1;   // >=0 表示可与队尾同类帧合并
        MotionEvent motion;
        KeyEvent key;
        float a, b;
        boolean rightClick;
        boolean mouseSource;
    }

    private LocalMoonlightDisplay() {}

    private synchronized DeXPresentation current() {
        return (virtualDisplay != null && presentation != null && presentation.isShowing()) ? presentation : null;
    }

    public synchronized boolean isActive() {
        return current() != null;
    }

    public synchronized int getDisplayId() {
        return displayId;
    }

    /**
     * 建立 / 复用零权限虚拟屏。必须在主线程调用（Presentation.show() 需要主线程 Looper）。
     * 尺寸变化时会先释放旧虚拟屏，因为旧屏仍持有即将被 resize 释放的采集 Surface。
     *
     * @return 虚拟屏 displayId，失败返回 -1
     */
    public synchronized int ensure(Context context, Surface captureSurface, int w, int h, int density) {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            Log.e(TAG, "ensure 必须在主线程调用");
            return -1;
        }
        if (context == null || captureSurface == null || !captureSurface.isValid() || w <= 0 || h <= 0) {
            return -1;
        }
        // Choreographer 绑定创建它的线程，ensure 已在主线程，这里取到的一定是主线程实例。
        frameChoreographer = Choreographer.getInstance();
        if (isActive() && width == w && height == h && dpi == density) {
            return displayId;
        }
        release();

        final Context app = context.getApplicationContext();
        DisplayManager dm = (DisplayManager) app.getSystemService(Context.DISPLAY_SERVICE);
        if (dm == null) return -1;

        try {
            VirtualDisplay vd;
            try {
                // 标准公开 API，flags=0：私有屏、零权限、零弹窗。
                vd = dm.createVirtualDisplay(VD_NAME, w, h, density, captureSurface, 0);
            } catch (SecurityException denied) {
                // 个别 Android 14+ 固件要求非镜像私有屏显式声明 OWN_CONTENT_ONLY；
                // 仍是公开标准 API、仍是零权限，只是不给系统镜像主屏的机会。
                vd = dm.createVirtualDisplay(VD_NAME, w, h, density, captureSurface,
                        DisplayManager.VIRTUAL_DISPLAY_FLAG_OWN_CONTENT_ONLY);
                Log.i(TAG, "flags=0 被固件拒绝，已用 OWN_CONTENT_ONLY 重建");
            }
            if (vd == null) throw new IllegalStateException("createVirtualDisplay 返回 null");
            Display display = vd.getDisplay();
            if (display == null) {
                vd.release();
                throw new IllegalStateException("虚拟屏没有 Display 对象");
            }
            // 身份从创建这一刻起就可用，不能等 pres.show() 之后的系统广播。
            displayId = display.getDisplayId();
            ScreenRegistry.get().onStreamDisplayReady(displayId, w, h, "local");

            Context host = MainActivity.getInstance();
            if (host instanceof Activity
                    && (((Activity) host).isFinishing() || ((Activity) host).isDestroyed())) {
                host = null;
            }
            if (host == null) host = app;

            previousActive = DeXPresentation.getActiveInstance();
            DeXPresentation pres = new DeXPresentation(host, display, w, h, density);
            pres.show();
            // 直显桌面的点击 / Dock 命中判定必须用虚拟屏真实像素，而不是默认 1080p 缩放值。
            pres.alignDirectDesktopSize(w, h);

            virtualDisplay = vd;
            presentation = pres;
            width = w;
            height = h;
            dpi = density;
            PeripheralBrain.getInstance().setMoonlightPresentation(pres);
            // 让外设大脑看见「Moonlight 远端键鼠」这条网络输入源，并允许本地物理外设
            // 在未显式绑定时自动路由到这块串流桌面。
            PeripheralBrain.getInstance().onMoonlightRemoteAttached();
            Log.i(TAG, "零权限虚拟屏就绪 id=" + displayId + " " + w + "x" + h + "@" + density
                    + " · 沧海桌面已挂载");
            CanghaiErrorCollector.logExt("零权限本地虚拟屏就绪: id=" + displayId
                    + " " + w + "x" + h + "@" + density);
            return displayId;
        } catch (Throwable t) {
            Log.e(TAG, "创建零权限虚拟屏失败: " + t.getMessage(), t);
            CanghaiErrorCollector.logExtError("创建零权限虚拟屏失败: " + t.getMessage(), null);
            release();
            return -1;
        }
    }

    /** 释放虚拟屏与桌面，恢复被覆盖的全局 active 实例。任意线程可调用。 */
    public void release() {
        final VirtualDisplay vd;
        final DeXPresentation pres;
        final DeXPresentation prev;
        synchronized (this) {
            vd = virtualDisplay;
            pres = presentation;
            prev = previousActive;
            virtualDisplay = null;
            presentation = null;
            previousActive = null;
            displayId = -1;
        }
        // 虚拟屏已不存在，队列里没投递的事件副本必须立即回收，不能留给下一帧。
        synchronized (frameLock) {
            for (FrameEvent f : pendingFrames) {
                if (f.motion != null) f.motion.recycle();
            }
            pendingFrames.clear();
            frameScheduled = false;
            frameDelay = -1L;
            remotePressed = false;
        }
        mainHandler.removeCallbacks(frameFlush);
        final Choreographer choreographer = frameChoreographer;
        if (choreographer != null) {
            choreographer.removeFrameCallback(frameCallback);
            frameChoreographer = null;
        }
        try {
            PeripheralBrain peripheral = PeripheralBrain.getInstance();
            peripheral.setMoonlightPresentation(null);
            peripheral.onMoonlightRemoteDetached();
            // 串流屏注销交给注册表统一发事件，外设回流由大脑订阅完成。
            ScreenRegistry.get().onStreamGone();
        } catch (Throwable ignored) {}
        if (pres != null) {
            Runnable dismiss = () -> {
                try {
                    pres.dismiss();
                } catch (Throwable t) {
                    Log.w(TAG, "DeXPresentation.dismiss 异常: " + t.getMessage());
                }
            };
            if (Looper.myLooper() == Looper.getMainLooper()) dismiss.run();
            else mainHandler.post(dismiss);
        }
        if (vd != null) {
            try {
                vd.release();
            } catch (Throwable ignored) {}
        }
        if (prev != null) DeXPresentation.setActiveInstance(prev);
        if (pres != null || vd != null) Log.i(TAG, "零权限虚拟屏已释放");
    }

    // ==============================================================
    // 按帧合并投递：一帧最多一次主线程任务
    // ==============================================================

    /** 把一帧输入放进队列；连续帧与队尾同类帧合并，离散帧保留顺序。 */
    private void enqueue(FrameEvent f) {
        long postDelay = -1L;
        boolean promote = false;
        synchronized (frameLock) {
            if (f.mergeKey >= 0 && !pendingFrames.isEmpty()) {
                FrameEvent tail = pendingFrames.getLast();
                if (tail.mergeKey == f.mergeKey) {
                    if (tail.route == ROUTE_DELTA || tail.route == ROUTE_SCROLL) {
                        // 相对位移 / 滚轮量必须累加，否则一帧内丢掉中间移动量。
                        tail.a += f.a;
                        tail.b += f.b;
                    } else {
                        // 绝对坐标帧只保留最新一份：中间那些 MOVE 对 WebView 没有意义。
                        if (tail.motion != null) tail.motion.recycle();
                        tail.motion = f.motion;
                        tail.mouseSource = f.mouseSource;
                    }
                    f.motion = null;
                    return; // 已排好的那一帧会带走最新值
                }
            }
            pendingFrames.addLast(f);
            if (!frameScheduled) {
                frameScheduled = true;
                frameDelay = (f.mergeKey >= 0) ? FRAME_INTERVAL_MS : 0L;
                postDelay = frameDelay;
            } else if (f.mergeKey < 0 && frameDelay > 0) {
                // 点击 / 抬起不能跟着 MOVE 一起等 16ms，立刻把已排的帧提前。
                frameDelay = 0L;
                promote = true;
            }
        }
        if (postDelay >= 0L) {
            final Choreographer choreographer = frameChoreographer;
            if (choreographer != null) {
                // 对齐 VSYNC：一帧一次回调，整批投递；不再是定时器式的一次 post。
                choreographer.postFrameCallback(frameCallback);
            } else {
                mainHandler.postDelayed(frameFlush, postDelay);
            }
        } else if (promote) {
            mainHandler.removeCallbacks(frameFlush);
            final Choreographer choreographer = frameChoreographer;
            if (choreographer != null) choreographer.removeFrameCallback(frameCallback);
            mainHandler.post(frameFlush);
        }
    }

    /** 主线程执行：整批取出，按原始顺序一次投递完，随后释放事件副本。 */
    private void flushFrames() {
        final ArrayList<FrameEvent> batch;
        synchronized (frameLock) {
            frameScheduled = false;
            frameDelay = -1L;
            if (pendingFrames.isEmpty()) return;
            batch = new ArrayList<>(pendingFrames);
            pendingFrames.clear();
        }
        // 一帧只问一次：这台 Moonlight 客户端对应的虚拟外设现在插在哪块屏。
        // 热插拔改的是插槽的 targetDisplayId，下一帧落地就跟着变，不需要重建任何链路。
        final PeripheralBrain brain = PeripheralBrain.getInstance();
        final PeripheralBrain.Host host = brain.hostOf(PeripheralBrain.MOONLIGHT_REMOTE_SLOT);
        final DeXPresentation pres = current();
        for (FrameEvent f : batch) {
            final MotionEvent ev = f.motion;
            if (ev != null) {
                try { brain.onMoonlightRemoteMotion(ev); }
                catch (Throwable ignored) {}
            }
            try {
                // 只有远端客户端帧参与跨屏路由；本地物理外设的相对位移/点击帧本来就
                // 由大脑按各自插槽分流后才会进这条队列，保持原落点即可。
                final boolean remoteFrame = (ev != null || f.key != null);
                boolean routed = false;
                if (remoteFrame && host != null && host != PeripheralBrain.Host.STREAM) {
                    routed = (ev != null)
                            ? brain.deliverRemoteTo(host, ev)
                            : brain.deliverRemoteKeyTo(host, f.key);
                }
                // 没接住（例如外屏已拔掉）就回退到串流桌面，事件绝不凭空丢失。
                if (!routed && pres != null) deliver(pres, f);
            } catch (Throwable t) {
                Log.w(TAG, "帧投递异常: " + t.getMessage());
            } finally {
                if (ev != null) ev.recycle();
            }
        }
    }

    /** 单一帧的落点：只走 WebView 所在的视图树，绝不经过系统 InputDispatcher。 */
    private void deliver(DeXPresentation pres, FrameEvent f) {
        switch (f.route) {
            case ROUTE_JOYSTICK:
                // 手柄摇杆的导航分支只同步读轴值，内部 post 的 Runnable 不持有事件。
                pres.dispatchExternalMotionEvent(f.motion);
                break;
            case ROUTE_CURSOR:
                // 未按下：只更新自绘光标 + 25ms 节流的 hover。
                // 绝不派发 touch，避免每个 MOVE 都让 WebView 走一遍命中测试与 JS。
                pres.moveBlackTouchCursor(f.motion.getX(), f.motion.getY());
                break;
            case ROUTE_TOUCH:
                if (f.motion.getActionMasked() == MotionEvent.ACTION_MOVE) {
                    // 拖拽时只挪光标，不派 HOVER（HOVER 会打断 WebView Pointer Capture）。
                    pres.updateCursor(f.motion.getX(), f.motion.getY());
                }
                if (f.mouseSource) {
                    // WebView 只在 SOURCE_TOUCHSCREEN 的 DOWN/UP 上生成 DOM 点击。
                    f.motion.setSource(InputDevice.SOURCE_TOUCHSCREEN);
                }
                pres.dispatchTouchEvent(f.motion);
                break;
            case ROUTE_GENERIC:
                pres.dispatchGenericMotionEvent(f.motion);
                break;
            case ROUTE_KEY:
                pres.dispatchExternalKeyEvent(f.key);
                break;
            case ROUTE_DELTA:
                pres.applyPointerDelta(f.a, f.b, 1.0f);
                break;
            case ROUTE_CLICK:
                if (f.rightClick) pres.performRightClickAt(pres.getLastMouseX(), pres.getLastMouseY());
                else pres.performClickAt(pres.getLastMouseX(), pres.getLastMouseY());
                break;
            case ROUTE_SCROLL:
                pres.performScrollAt(pres.getLastMouseX(), pres.getLastMouseY(), f.a, f.b);
                break;
            default:
                break;
        }
    }

    /**
     * Moonlight 远端 MotionEvent 入口。调用方返回后会 recycle 原事件，这里必须
     * obtain 一份副本；副本在整帧投递完成后统一回收。
     */
    public boolean dispatchRemoteMotion(MotionEvent event) {
        if (event == null) return false;
        if (current() == null) return false;
        final MotionEvent copy = MotionEvent.obtain(event);
        final int action = copy.getActionMasked();
        final boolean joystick = copy.isFromSource(InputDevice.SOURCE_JOYSTICK)
                || copy.isFromSource(InputDevice.SOURCE_GAMEPAD);
        final boolean mouse = copy.isFromSource(InputDevice.SOURCE_MOUSE);

        if (action == MotionEvent.ACTION_DOWN || action == MotionEvent.ACTION_POINTER_DOWN) {
            remotePressed = true;
        } else if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL
                || (action == MotionEvent.ACTION_POINTER_UP && copy.getPointerCount() <= 1)) {
            remotePressed = false;
        }
        final boolean pressed = remotePressed
                || (copy.getButtonState() & MotionEvent.BUTTON_PRIMARY) != 0;

        final FrameEvent f = new FrameEvent();
        f.motion = copy;
        f.mouseSource = mouse;
        if (joystick) {
            f.route = ROUTE_JOYSTICK;
            f.mergeKey = MERGE_JOYSTICK; // 摇杆轴帧同样按帧合并，内部还有 180ms 导航节流
        } else if (action == MotionEvent.ACTION_HOVER_MOVE
                || (action == MotionEvent.ACTION_MOVE && !pressed && mouse)) {
            // 无按键移动：只走光标 / hover，合并到每帧一份。
            f.route = ROUTE_CURSOR;
            f.mergeKey = MERGE_CURSOR;
        } else if (action == MotionEvent.ACTION_MOVE) {
            // 按下拖拽：走 touch，同样合并到每帧一份。
            f.route = ROUTE_TOUCH;
            f.mergeKey = MERGE_TOUCH_MOVE;
        } else if (action == MotionEvent.ACTION_DOWN
                || action == MotionEvent.ACTION_UP
                || action == MotionEvent.ACTION_CANCEL
                || action == MotionEvent.ACTION_POINTER_DOWN
                || action == MotionEvent.ACTION_POINTER_UP) {
            // 离散帧不合并且立即投递，保证点击顺序与手感。
            f.route = ROUTE_TOUCH;
        } else {
            // 滚轮、鼠标按钮、HOVER_ENTER/EXIT 等。
            f.route = ROUTE_GENERIC;
        }
        enqueue(f);
        return true;
    }

    /**
     * 统一语义层翻译出来的鼠标按钮动作（右键 / 中键）。
     * 仍以 MotionEvent 形式入队：flushFrames 会把它登记到外设大脑的输入总线，
     * 桌面侧 dispatchGenericMotionEvent 对 BUTTON_SECONDARY 直接弹 contextmenu，
     * 对 BUTTON_TERTIARY（中键）把按钮帧交给 WebView。零权限落地只做派发，不做语义判断。
     */
    public boolean dispatchRemoteButton(float x, float y, int button) {
        if (current() == null) return false;
        final long now = SystemClock.uptimeMillis();
        MotionEvent.PointerProperties[] props = new MotionEvent.PointerProperties[1];
        props[0] = new MotionEvent.PointerProperties();
        props[0].id = 0;
        props[0].toolType = MotionEvent.TOOL_TYPE_MOUSE;
        MotionEvent.PointerCoords[] coords = new MotionEvent.PointerCoords[1];
        coords[0] = new MotionEvent.PointerCoords();
        coords[0].x = x;
        coords[0].y = y;
        MotionEvent ev = MotionEvent.obtain(now, now, MotionEvent.ACTION_BUTTON_PRESS, 1,
                props, coords, 0, button, 1f, 1f, 0, 0, InputDevice.SOURCE_MOUSE, 0);
        final FrameEvent f = new FrameEvent();
        f.motion = ev;
        f.route = ROUTE_GENERIC;   // 离散帧，立即投递
        enqueue(f);
        return true;
    }

    /** Moonlight 远端键盘 / 手柄按键（Ctrl+Alt+Esc 已由统一入口处理，这里只做落地）。 */
    public boolean dispatchRemoteKey(int keyCode, boolean down, int modifiers) {
        if (current() == null) return false;
        try { PeripheralBrain.getInstance().onMoonlightRemoteKey(keyCode, down, modifiers); }
        catch (Throwable ignored) {}
        final boolean gamepad = KeyEvent.isGamepadButton(keyCode)
                || keyCode == KeyEvent.KEYCODE_DPAD_UP || keyCode == KeyEvent.KEYCODE_DPAD_DOWN
                || keyCode == KeyEvent.KEYCODE_DPAD_LEFT || keyCode == KeyEvent.KEYCODE_DPAD_RIGHT
                || keyCode == KeyEvent.KEYCODE_DPAD_CENTER;
        final long now = SystemClock.uptimeMillis();
        final FrameEvent f = new FrameEvent();
        f.route = ROUTE_KEY;
        f.key = new KeyEvent(now, now,
                down ? KeyEvent.ACTION_DOWN : KeyEvent.ACTION_UP,
                keyCode, 0, modifiers, KeyCharacterMap.VIRTUAL_KEYBOARD, 0, 0,
                gamepad ? InputDevice.SOURCE_GAMEPAD : InputDevice.SOURCE_KEYBOARD);
        enqueue(f);
        return true;
    }

    // ==============================================================
    // 手机本地物理外设（由外设大脑 / MainActivity 的高精度 PointerCapture 管道调用）
    // 零权限时不能走 Shizuku 注入，复用桌面自己的坐标系，并且同样按帧合并。
    // ==============================================================

    /** 相对位移：一帧内的多个位移累加成一份，避免高频 post 压满主线程。 */
    public boolean dispatchPeripheralDelta(float dx, float dy) {
        if (current() == null) return false;
        final FrameEvent f = new FrameEvent();
        f.route = ROUTE_DELTA;
        f.mergeKey = MERGE_DELTA;
        f.a = dx;
        f.b = dy;
        enqueue(f);
        return true;
    }

    public boolean dispatchPeripheralClick(boolean rightClick) {
        if (current() == null) return false;
        final FrameEvent f = new FrameEvent();
        f.route = ROUTE_CLICK;
        f.rightClick = rightClick;
        enqueue(f);
        return true;
    }

    public boolean dispatchPeripheralScroll(float vScroll, float hScroll) {
        if (current() == null) return false;
        final FrameEvent f = new FrameEvent();
        f.route = ROUTE_SCROLL;
        f.mergeKey = MERGE_SCROLL;
        f.a = vScroll;
        f.b = hScroll;
        enqueue(f);
        return true;
    }

    public boolean dispatchPeripheralKey(KeyEvent event) {
        if (current() == null || event == null) return false;
        // Android SDK 没有 KeyEvent.obtain(KeyEvent) 拷贝重载，用公开构造器重建一份，
        // 避免调用方回收原事件后异步消费到已失效对象。
        final FrameEvent f = new FrameEvent();
        f.route = ROUTE_KEY;
        f.key = new KeyEvent(
                event.getDownTime(), event.getEventTime(), event.getAction(),
                event.getKeyCode(), event.getRepeatCount(), event.getMetaState(),
                event.getDeviceId(), event.getScanCode(), event.getFlags(), event.getSource());
        enqueue(f);
        return true;
    }
}
