package com.alkaidlab.sunshine.engine

import android.content.Context
import android.os.SystemClock
import android.util.DisplayMetrics
import android.view.KeyCharacterMap
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.WindowManager
import com.alkaidlab.sunshine.service.CaptureGeometry
import com.alkaidlab.sunshine.service.SunshineRuntimeManager

/** Android events are delivered by the host's existing Shizuku input service. */
object InputBroker {
    @JvmStatic fun controllerState(id: Int, buttons: Int, lx: Float, ly: Float, rx: Float, ry: Float, lt: Float, rt: Float) {
        try { com.alkaidlab.sunshine.service.SunshineEngineBridge.host?.controllerState(id, buttons, lx, ly, rx, ry, lt, rt) } catch (_: android.os.RemoteException) {}
    }
    @JvmStatic fun controllerRemoved(id: Int) {
        try { com.alkaidlab.sunshine.service.SunshineEngineBridge.host?.controllerRemoved(id) } catch (_: android.os.RemoteException) {}
    }
    private var context: Context? = null
    private var captureWidth = 1
    private var captureHeight = 1
    private var inputWidth = 1
    private var inputHeight = 1
    private var clientWidth = 1
    private var clientHeight = 1
    private var rotationCompat = false
    private var displayRotation = 0
    private var sourceDisplayId = 0
    private val geometryCache = InputGeometryCache()
    private val displayMetrics = DisplayMetrics()
    private var displayManager: android.hardware.display.DisplayManager? = null
    private val displayListener = object : android.hardware.display.DisplayManager.DisplayListener {
        override fun onDisplayAdded(id: Int) = invalidateGeometry(id)
        override fun onDisplayChanged(id: Int) = invalidateGeometry(id)
        override fun onDisplayRemoved(id: Int) = invalidateGeometry(id)
    }
    @Synchronized private fun invalidateGeometry(id: Int) { geometryCache.displayChanged(id) }
    @JvmStatic @Synchronized fun setSourceDisplay(id: Int, width: Int, height: Int) {
        if (sourceDisplayId != id || captureWidth != width || captureHeight != height) reset()
        sourceDisplayId = id
        geometryCache.sourceChanged(id)
        setCaptureDimensions(width, height)
        inputWidth = width
        inputHeight = height
    }
    @JvmStatic @Synchronized fun setRotationCompatibility(enabled: Boolean) { rotationCompat = enabled }
    private var mouseX = 0f
    private var mouseY = 0f
    private var downTime = 0L
    private var modifiers = 0
    private data class Finger(val androidId: Int, var x: Float, var y: Float, var pressure: Float)
    private val fingers = linkedMapOf<Long, Finger>()
    /** 三指及以上手势保护中：整段手势都不再注入系统，避免弹输入法 / 系统手势。 */
    private var gestureGuard = false

    @JvmStatic @Synchronized fun setContext(value: Context) {
        val app = value.applicationContext
        if (context !== app || displayManager == null) {
            displayManager?.unregisterDisplayListener(displayListener)
            context = app
            displayManager = app.getSystemService(android.hardware.display.DisplayManager::class.java)
            displayManager?.registerDisplayListener(displayListener, android.os.Handler(android.os.Looper.getMainLooper()))
        }
        updateDisplayDimensions()
    }
    @JvmStatic @Synchronized fun setCaptureDimensions(width: Int, height: Int) {
        if (width > 0 && height > 0) { captureWidth = width; captureHeight = height }
    }
    @JvmStatic @Synchronized fun setActiveClientDimensions(width: Int, height: Int) {
        if (width > 0 && height > 0) { clientWidth = width; clientHeight = height }
    }
    @JvmStatic @Synchronized fun updateDisplayDimensions() {
        geometryCache.sourceChanged(sourceDisplayId)
        ensureDisplayDimensions()
    }
    private fun ensureDisplayDimensions() = geometryCache.refresh { readDisplayDimensions() }
    private fun readDisplayDimensions() {
        val display = displayManager?.getDisplay(sourceDisplayId)
        if (display != null) {
            val metrics = displayMetrics
            @Suppress("DEPRECATION") display.getRealMetrics(metrics)
            displayRotation = display.rotation
            inputWidth = metrics.widthPixels
            inputHeight = metrics.heightPixels
            // A DeX mirror can fit a landscape system display inside a portrait capture surface.
            if (sourceDisplayId == 0) setCaptureDimensions(inputWidth, inputHeight)
            return
        }
        // Never substitute primary-screen metrics for a missing secondary display.
        if (sourceDisplayId != 0) return
        val wm = context?.getSystemService(Context.WINDOW_SERVICE) as? WindowManager ?: return
        @Suppress("DEPRECATION")
        displayRotation = wm.defaultDisplay.rotation
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
            val bounds = wm.maximumWindowMetrics.bounds
            setCaptureDimensions(bounds.width(), bounds.height())
            inputWidth = captureWidth; inputHeight = captureHeight
            return
        }
        val metrics = displayMetrics
        @Suppress("DEPRECATION") wm.defaultDisplay.getRealMetrics(metrics)
        setCaptureDimensions(metrics.widthPixels, metrics.heightPixels)
        inputWidth = captureWidth; inputHeight = captureHeight
    }
    private fun position(x: Float, y: Float, width: Int, height: Int): Pair<Float, Float> {
        ensureDisplayDimensions()
        return CaptureGeometry.mapCapturedPosition(x, y, inputWidth, inputHeight, captureWidth, captureHeight,
            width.coerceAtLeast(1), height.coerceAtLeast(1),
            CaptureGeometry.compatibilityTurn(captureWidth, captureHeight, width, height, displayRotation, rotationCompat))
    }
    private fun emit(action: Int, changed: Long? = null) {
        // 保护中的多指帧一律不注入（系统会把 3/4 指解释成输入法/辅助手势）。
        if (gestureGuard) return
        if (fingers.isEmpty()) return
        val ids = fingers.keys.toList()
        val properties = fingers.values.map { f -> MotionEvent.PointerProperties().apply { id = f.androidId; toolType = MotionEvent.TOOL_TYPE_FINGER } }.toTypedArray()
        val coordinates = fingers.values.map { f -> MotionEvent.PointerCoords().apply { x = f.x; y = f.y; pressure = f.pressure.coerceIn(.01f, 1f); size = .05f } }.toTypedArray()
        val encoded = action or (if (action == MotionEvent.ACTION_POINTER_DOWN || action == MotionEvent.ACTION_POINTER_UP)
            ids.indexOf(changed).coerceAtLeast(0) shl MotionEvent.ACTION_POINTER_INDEX_SHIFT else 0)
        val event = MotionEvent.obtain(downTime, SystemClock.uptimeMillis(), encoded, fingers.size, properties, coordinates,
            0, 0, 1f, 1f, 0, 0, android.view.InputDevice.SOURCE_TOUCHSCREEN, 0)
        try { SunshineRuntimeManager.getInstance().injectMotionEvent(event) } finally { event.recycle() }
    }
    @JvmStatic @Synchronized fun nativeTouch(type: Int, id: Long, x: Float, y: Float, pressure: Float, width: Int, height: Int) {
        if (!x.isFinite() || !y.isFinite() || !pressure.isFinite() || width < 1 || height < 1) return
        val (px, py) = position(x * width, y * height, width, height)
        /* 真·多点触控是支持的（最多 32 指、SOURCE_TOUCHSCREEN、带 displayId 送到虚拟屏），
           但 One UI / Android 会把 3 指、4 指解释成系统手势——用户实测会唤出输入法。
           从第 3 指落下开始进入保护：先补一帧 CANCEL 把已经注入的 1~2 指状态清掉，
           整段手势不再往下发；所有手指抬起后自动恢复 1~2 指触控。 */
        if (!gestureGuard && type == 1 && id !in fingers && fingers.size >= 2) {
            emit(MotionEvent.ACTION_CANCEL)
            gestureGuard = true
        }
        when (type) {
            1 -> {
                if (id in fingers || fingers.size >= 32) return
                if (fingers.isEmpty()) downTime = SystemClock.uptimeMillis()
                val used = fingers.values.map { it.androidId }.toSet()
                fingers[id] = Finger((0..31).first { it !in used }, px, py, pressure)
                emit(if (fingers.size == 1) MotionEvent.ACTION_DOWN else MotionEvent.ACTION_POINTER_DOWN, id)
            }
            2 -> { fingers[id]?.let { it.x = px; it.y = py; emit(if (fingers.size == 1) MotionEvent.ACTION_UP else MotionEvent.ACTION_POINTER_UP, id); fingers.remove(id) } }
            3 -> { fingers[id]?.let { it.x = px; it.y = py; it.pressure = pressure; emit(MotionEvent.ACTION_MOVE) } }
            4 -> { if (id in fingers) { emit(if (fingers.size == 1) MotionEvent.ACTION_UP else MotionEvent.ACTION_POINTER_UP, id); fingers.remove(id) } }
            7 -> clearClient(id ushr 32)
        }
        if (fingers.isEmpty()) gestureGuard = false
    }
    @JvmStatic @Synchronized fun reset() {
        if (fingers.isNotEmpty() && !gestureGuard) emit(MotionEvent.ACTION_CANCEL)
        fingers.clear()
        gestureGuard = false
    }
    @JvmStatic @Synchronized fun clearClient(client: Long) {
        val owned = fingers.keys.filter { it >= 0 && (it ushr 32) == client }
        owned.forEach { id -> emit(if (fingers.size == 1) MotionEvent.ACTION_UP else MotionEvent.ACTION_POINTER_UP, id); fingers.remove(id) }
        if (fingers.isEmpty()) gestureGuard = false
    }
    @JvmStatic @Synchronized fun absoluteMouse(x: Float, y: Float, width: Int, height: Int) {
        setActiveClientDimensions(width, height)
        mouseX = x; mouseY = y
        val p = position(x, y, width, height)
        com.aosen.shizukudex.MainActivity.updateRemoteMouse(p.first, p.second)
        fingers[-1L]?.let { it.x = p.first; it.y = p.second; emit(MotionEvent.ACTION_MOVE) }
            ?: emitHoverMove()
    }
    /**
     * 沧海集成：无按键移动鼠标时注入 SOURCE_MOUSE 的 ACTION_HOVER_MOVE，
     * Android 会在目标 display 上渲染鼠标指针；此前仅按压状态下发 MOVE，
     * 纯移动丢失导致虚拟屏看不到指针。
     */
    private fun emitHoverMove() {
        val (px, py) = position(mouseX, mouseY, clientWidth, clientHeight)
        val event = MotionEvent.obtain(downTime, SystemClock.uptimeMillis(), MotionEvent.ACTION_HOVER_MOVE,
            px, py, 0).apply { source = android.view.InputDevice.SOURCE_MOUSE }
        try { SunshineRuntimeManager.getInstance().injectMotionEvent(event) } finally { event.recycle() }
    }
    @JvmStatic @Synchronized fun relativeMouse(x: Int, y: Int) {
        val nextX = (mouseX + x).coerceIn(0f, clientWidth.toFloat())
        val nextY = (mouseY + y).coerceIn(0f, clientHeight.toFloat())
        absoluteMouse(nextX, nextY, clientWidth, clientHeight)
    }

    /** 会话全部结束：复位指针状态 */
    @JvmStatic @Synchronized fun resetCursorMode() {
        // no-op
    }
    @JvmStatic @Synchronized fun mouseButton(button: Int, down: Int) {
        when (button) {
            1 -> nativeTouch(if (down == 1) 1 else 2, -1L, mouseX / clientWidth, mouseY / clientHeight, 1f, clientWidth, clientHeight)
            // 中键此前完全没有映射，客户端按下/抬起收不到任何信号。统一交给
            // RuntimeManager 的语义层：有权限注入标准 BUTTON_TERTIARY，零权限投桌面。
            2 -> {
                val point = position(mouseX, mouseY, clientWidth, clientHeight)
                val now = SystemClock.uptimeMillis()
                val props = arrayOf(MotionEvent.PointerProperties().apply { id = 0; toolType = MotionEvent.TOOL_TYPE_MOUSE })
                val coords = arrayOf(MotionEvent.PointerCoords().apply { x = point.first; y = point.second })
                val action = if (down == 1) MotionEvent.ACTION_BUTTON_PRESS else MotionEvent.ACTION_BUTTON_RELEASE
                val ev = MotionEvent.obtain(now, now, action, 1, props, coords, 0,
                    MotionEvent.BUTTON_TERTIARY, 1f, 1f, 0, 0,
                    android.view.InputDevice.SOURCE_MOUSE, 0)
                try { SunshineRuntimeManager.getInstance().injectMotionEvent(ev) } finally { ev.recycle() }
            }
            // 客户端把右键编成 KEYCODE_BACK；带私有标记，避免手柄/系统返回键被误认。
            3 -> SunshineRuntimeManager.getInstance().handleKeyboardEvent(
                KeyEvent.KEYCODE_BACK, down == 1, SunshineRuntimeManager.MOUSE_RIGHT_FLAG)
        }
    }
    @JvmStatic @Synchronized fun scroll(horizontal: Int, vertical: Int) {
        val point = position(mouseX, mouseY, clientWidth, clientHeight)
        // Scroll axes require the pointer-coordinate constructor.
        val props = arrayOf(MotionEvent.PointerProperties().apply { id = 0; toolType = MotionEvent.TOOL_TYPE_MOUSE })
        val coords = arrayOf(MotionEvent.PointerCoords().apply { x = point.first; y = point.second; setAxisValue(MotionEvent.AXIS_HSCROLL, horizontal / 120f); setAxisValue(MotionEvent.AXIS_VSCROLL, vertical / 120f) })
        val wheel = MotionEvent.obtain(0, SystemClock.uptimeMillis(), MotionEvent.ACTION_SCROLL, 1, props, coords, 0, 0, 1f, 1f, 0, 0, android.view.InputDevice.SOURCE_MOUSE, 0)
        try { SunshineRuntimeManager.getInstance().injectMotionEvent(wheel) } finally { wheel.recycle() }
    }
    @JvmStatic @Synchronized fun virtualKey(code: Int, down: Boolean, flags: Int) {
        val androidCode = when (code) {
            in 0x41..0x5a -> KeyEvent.KEYCODE_A + code - 0x41
            in 0x30..0x39 -> KeyEvent.KEYCODE_0 + code - 0x30
            in 0x60..0x69 -> KeyEvent.KEYCODE_0 + code - 0x60
            in 0x70..0x7b -> KeyEvent.KEYCODE_F1 + code - 0x70
            0x5b, 0x5c -> KeyEvent.KEYCODE_HOME; 8 -> KeyEvent.KEYCODE_DEL; 9 -> KeyEvent.KEYCODE_TAB; 13 -> KeyEvent.KEYCODE_ENTER
            27 -> KeyEvent.KEYCODE_ESCAPE; 32 -> KeyEvent.KEYCODE_SPACE
            37 -> KeyEvent.KEYCODE_DPAD_LEFT; 38 -> KeyEvent.KEYCODE_DPAD_UP; 39 -> KeyEvent.KEYCODE_DPAD_RIGHT; 40 -> KeyEvent.KEYCODE_DPAD_DOWN
            33 -> KeyEvent.KEYCODE_PAGE_UP; 34 -> KeyEvent.KEYCODE_PAGE_DOWN; 35 -> KeyEvent.KEYCODE_MOVE_END; 36 -> KeyEvent.KEYCODE_MOVE_HOME
            45 -> KeyEvent.KEYCODE_INSERT; 46 -> KeyEvent.KEYCODE_FORWARD_DEL
            16, 0xa0 -> KeyEvent.KEYCODE_SHIFT_LEFT; 0xa1 -> KeyEvent.KEYCODE_SHIFT_RIGHT
            17, 0xa2 -> KeyEvent.KEYCODE_CTRL_LEFT; 0xa3 -> KeyEvent.KEYCODE_CTRL_RIGHT
            18, 0xa4 -> KeyEvent.KEYCODE_ALT_LEFT; 0xa5 -> KeyEvent.KEYCODE_ALT_RIGHT
            0xba -> KeyEvent.KEYCODE_SEMICOLON; 0xbb -> KeyEvent.KEYCODE_EQUALS; 0xbc -> KeyEvent.KEYCODE_COMMA
            0xbd -> KeyEvent.KEYCODE_MINUS; 0xbe -> KeyEvent.KEYCODE_PERIOD; 0xbf -> KeyEvent.KEYCODE_SLASH
            0xc0 -> KeyEvent.KEYCODE_GRAVE; 0xdb -> KeyEvent.KEYCODE_LEFT_BRACKET; 0xdc -> KeyEvent.KEYCODE_BACKSLASH
            0xdd -> KeyEvent.KEYCODE_RIGHT_BRACKET; 0xde -> KeyEvent.KEYCODE_APOSTROPHE
            else -> return
        }
        val mask = when (androidCode) {
            KeyEvent.KEYCODE_SHIFT_LEFT, KeyEvent.KEYCODE_SHIFT_RIGHT -> KeyEvent.META_SHIFT_ON
            KeyEvent.KEYCODE_CTRL_LEFT, KeyEvent.KEYCODE_CTRL_RIGHT -> KeyEvent.META_CTRL_ON
            KeyEvent.KEYCODE_ALT_LEFT, KeyEvent.KEYCODE_ALT_RIGHT -> KeyEvent.META_ALT_ON
            else -> 0
        }
        modifiers = if (down) modifiers or mask else modifiers and mask.inv()
        SunshineRuntimeManager.getInstance().handleKeyboardEvent(androidCode, down, modifiers)
    }
    @JvmStatic fun text(value: String) {
        val events = KeyCharacterMap.load(KeyCharacterMap.VIRTUAL_KEYBOARD).getEvents(value.toCharArray()) ?: return
        events.forEach { SunshineRuntimeManager.getInstance().handleKeyboardEvent(it.keyCode, it.action == KeyEvent.ACTION_DOWN, it.metaState) }
    }
    @JvmStatic fun handleTouchPacket(action: Int, flags: Int, id: Long, x: Float, y: Float, pressure: Float, area: Float, rotation: Float) {
        nativeTouch(action, id, x, y, pressure, clientWidth, clientHeight)
    }
    @JvmStatic fun handleAbsMouseMovePacket(x: Float, y: Float, width: Float, height: Float) { absoluteMouse(x, y, width.toInt(), height.toInt()) }
    @JvmStatic fun handleLeftMouseButton(release: Boolean) { mouseButton(1, if (release) 0 else 1) }
}
