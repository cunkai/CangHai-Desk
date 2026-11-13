package com.alkaidlab.sunshine.service

import java.util.concurrent.ConcurrentHashMap

/**
 * 需要自绘指针的显示屏注册表。
 *
 * ControllerNavigationService.onMotionEvent 从输入层拿到 USB 鼠标的真实绝对坐标后，
 * 只有落在「已注册屏幕」上的事件才会自绘指针（TYPE_ACCESSIBILITY_OVERLAY，z=31）。
 *
 * 登记来源：
 * - DeXPresentation（有线外接物理屏，如 display 6）：onShow 注册 / onDismiss 注销；
 * - Sunshine 虚拟屏（Moonlight 串流目标）：ControllerNavigationService 直接读
 *   SunshineRuntimeManager.virtualScreenState，无需在此登记。
 */
object MouseCursorTargets {
    private val targets = ConcurrentHashMap.newKeySet<Int>()

    @JvmStatic fun register(displayId: Int) { if (displayId > 0) targets.add(displayId) }
    @JvmStatic fun unregister(displayId: Int) { targets.remove(displayId) }
    @JvmStatic fun contains(displayId: Int): Boolean = targets.contains(displayId)
    @JvmStatic fun clear() { targets.clear() }
}
