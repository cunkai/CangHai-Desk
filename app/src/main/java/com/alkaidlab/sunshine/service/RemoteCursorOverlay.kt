package com.alkaidlab.sunshine.service

import android.content.Context
import android.graphics.PixelFormat
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.WindowManager

/**
 * PC 远程鼠标 / 真设备鼠标的软件指针。
 *
 * 一块屏一个独立窗口：真实外接屏（大脑层把真实外设绑过去的那块）和 Moonlight
 * 虚拟屏可以同时各有一支光标，互不 remove。以前是单一 view + 单一 displayId，
 * place() 里「displayId 变了就 remove」，两块屏必然互相踢掉 —— 这就是
 * 「外接屏没鼠标、只有 Moonlight 显示」的根因之一。
 *
 * 绘制通道：主进程 TYPE_APPLICATION_OVERLAY 窗口（layer 11，高于所有应用窗口；
 *   SYSTEM_ALERT_WINDOW 由 Root / Shizuku 授权），不需要任何无障碍授权。
 *   注意：Shizuku UserService 裸进程无法添加任何窗口（WMS 报 Unknown pid），
 *   因此必须由主进程绘制。
 *   仅当用户自己在系统设置里开启了可选的置顶指针服务时，才升级到
 *   TYPE_ACCESSIBILITY_OVERLAY（layer 31，可压过三星 DeX 底栏）；
 *   应用不检测、不引导、不自动开启该服务。
 *
 * 事件坐标即目标屏像素坐标；虚拟屏按客户端请求分辨率创建，直接可用。
 */
object RemoteCursorOverlay {
    private val main = Handler(Looper.getMainLooper())

    /** 一块屏一个窗口，只在主线程访问。 */
    private class Entry(val view: MouseCursorView, val manager: WindowManager)
    private val entries = HashMap<Int, Entry>()

    private var appopEnsured = false
    private var failedLog = 0L
    private var appContext: Context? = null
    /** 游戏相对鼠标模式抑制指针：窗口移除但坐标保留，解除时原位恢复 */
    @Volatile private var suppressed = false
    /** 调试用「屏蔽这一层」：屏蔽期间不接受后续 show()，也不再重新挂窗口。 */
    @Volatile private var userBlocked = false
    private var lastX = 0f
    private var lastY = 0f
    private var lastDisplayId = -1

    fun show(context: Context, displayId: Int, x: Float, y: Float) {
        if (displayId <= 0) return
        appContext = context.applicationContext
        lastX = x; lastY = y; lastDisplayId = displayId
        if (userBlocked) return
        if (com.alkaidlab.sunshine.controller.ControllerNavigationService.connected.value) {
            com.alkaidlab.sunshine.controller.ControllerNavigationService.showMouseCursor(displayId, x, y)
            return
        }
        val app = context.applicationContext
        main.post { place(app, displayId, x, y) }
    }

    /** 调试：整层屏蔽 / 恢复，供「管理渲染层」弹窗逐层二分遮挡来源。 */
    fun setUserBlocked(value: Boolean) {
        userBlocked = value
        main.post {
            if (value) {
                com.alkaidlab.sunshine.controller.ControllerNavigationService.hideMouseCursor()
                removeAll()
            } else if (lastDisplayId > 0) {
                appContext?.let { show(it, lastDisplayId, lastX, lastY) }
            }
        }
    }

    fun isUserBlocked(): Boolean = userBlocked

    /** 游戏模式（客户端相对鼠标）抑制软件指针；解除后立即原位恢复。 */
    fun setSuppressed(value: Boolean) {
        if (suppressed == value) return
        suppressed = value
        main.post {
            if (value) {
                com.alkaidlab.sunshine.controller.ControllerNavigationService.hideMouseCursor()
                removeAll()
            } else if (lastDisplayId > 0) {
                appContext?.let { show(it, lastDisplayId, lastX, lastY) }
            }
        }
    }

    fun hide() {
        com.alkaidlab.sunshine.controller.ControllerNavigationService.hideMouseCursor()
        main.post {
            for (e in entries.values) e.view.visibility = View.GONE
            removeAll()
        }
    }

    /** 只收掉某一块屏的光标：串流结束时不该把真实外接屏上的鼠标一起抹掉。 */
    fun hideOnly(displayId: Int) {
        if (displayId <= 0) return
        main.post { removeOne(displayId) }
    }

    /** 彻底销毁 Overlay 窗口，释放 WindowManager 占用的系统图层 */
    fun destroy() {
        com.alkaidlab.sunshine.controller.ControllerNavigationService.hideMouseCursor()
        main.post { removeAll() }
    }

    private fun place(context: Context, displayId: Int, x: Float, y: Float) {
        if (suppressed || userBlocked) return
        var e = entries[displayId]
        if (e == null) {
            e = attach(context, displayId) ?: return
            entries[displayId] = e
        }
        e.view.visibility = View.VISIBLE
        e.view.moveTo(x, y)
        scheduleAutoHide()
    }

    private fun scheduleAutoHide() = MouseCursorPrefs.scheduleAutoHide(main, autoHideRunnable)

    private val autoHideRunnable = Runnable {
        for (e in entries.values) e.view.visibility = View.GONE
    }

    private fun attach(context: Context, displayId: Int): Entry? {
        MouseCursorPrefs.load(context)
        val display = (context.getSystemService(Context.DISPLAY_SERVICE) as android.hardware.display.DisplayManager)
            .getDisplay(displayId) ?: return null
        val displayContext = context.createDisplayContext(display)
        val manager = displayContext.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val newView = MouseCursorView(displayContext)
        newView.displayId = displayId
        // 层级参考（AOSP getWindowLayerFromTypeLw）：
        //   TYPE_PRIVATE_PRESENTATION=3 < 应用窗口=2 同层 < TYPE_APPLICATION_OVERLAY=11
        //   < NAVIGATION_BAR=24 < NAVIGATION_BAR_PANEL=25(三星 DeX 底栏)
        //   < TYPE_ACCESSIBILITY_OVERLAY=31（无障碍服务通道，见 show()）
        // 因此优先 APPLICATION_OVERLAY（需 SYSTEM_ALERT_WINDOW appop），PRIVATE_PRESENTATION 兜底。
        ensureAppop()
        val types = intArrayOf(
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.TYPE_PRIVATE_PRESENTATION
        )
        var lastError: Exception? = null
        for (type in types) {
            val params = WindowManager.LayoutParams(WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT,
                type,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                    or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT).apply {
                gravity = Gravity.TOP or Gravity.LEFT
                title = "Sunshine Remote Cursor"
                if (android.os.Build.VERSION.SDK_INT >= 30) setFitInsetsTypes(0)
            }
            try {
                manager.addView(newView, params)
                SunshineLogManager.log("CURSOR", "指针窗口已创建 type=$type display=$displayId")
                return Entry(newView, manager)
            } catch (error: Exception) {
                lastError = error
            }
        }
        if (System.currentTimeMillis() - failedLog > 10_000) {
            failedLog = System.currentTimeMillis()
            SunshineLogManager.log("CURSOR", "指针窗口创建失败: ${lastError?.javaClass?.simpleName}: ${lastError?.message}；请检查悬浮窗权限")
        }
        return null
    }

    /** 供「管理渲染层」判断这一层是否真的挂了窗口（不是只看开关状态）。 */
    fun isShowing(): Boolean = entries.isNotEmpty()

    fun setPointerLocked(locked: Boolean) {
        suppressed = locked
        main.post {
            for (e in entries.values) {
                e.view.visibility = if (locked) View.GONE else View.VISIBLE
            }
        }
    }

    private fun removeOne(displayId: Int) {
        val e = entries.remove(displayId) ?: return
        try { e.manager.removeViewImmediate(e.view) } catch (_: Exception) {}
    }

    private fun removeAll() {
        main.removeCallbacks(autoHideRunnable)
        for (e in entries.values) {
            try { e.manager.removeViewImmediate(e.view) } catch (_: Exception) {}
        }
        entries.clear()
    }

    /** 经 Shizuku 授予 SYSTEM_ALERT_WINDOW appop（Samsung 拦截 settings 写无障碍白名单，但放行 appops）。 */
    private fun ensureAppop() {
        if (appopEnsured) return
        appopEnsured = true
        val pkg = appContext?.packageName ?: return
        Thread {
            PrivilegedShell.run(arrayOf("cmd", "appops", "set", pkg, "SYSTEM_ALERT_WINDOW", "allow"), timeoutMs = 3000)
        }.start()
    }
}
