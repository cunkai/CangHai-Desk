package com.alkaidlab.sunshine.service

import java.util.concurrent.ConcurrentHashMap

/**
 * 需要自绘指针的显示屏注册表。
 *
 * 登记来源：
 * - DeXPresentation（有线外接物理屏，如 display 6）：onShow 注册 / onDismiss 注销。
 */
object MouseCursorTargets {
    private val targets = ConcurrentHashMap.newKeySet<Int>()

    @JvmStatic fun register(displayId: Int) { if (displayId > 0) targets.add(displayId) }
    @JvmStatic fun unregister(displayId: Int) { targets.remove(displayId) }
    @JvmStatic fun contains(displayId: Int): Boolean = targets.contains(displayId)
    @JvmStatic fun clear() { targets.clear() }
}
