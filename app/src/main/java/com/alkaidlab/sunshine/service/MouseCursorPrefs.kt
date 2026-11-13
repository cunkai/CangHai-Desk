package com.alkaidlab.sunshine.service

import android.content.Context
import java.util.concurrent.ConcurrentHashMap

/**
 * 统一软件指针的样式与大小设置。
 *
 * 支持多屏幕独立保存：
 * 外部显示器（电视/投影/便携屏）与 Moonlight 远程串流屏幕、手机端各自拥有独立的
 * 鼠标样式与指针大小，互不干扰、互不同步。
 */
object MouseCursorPrefs {
    const val PREFS = "aosen_settings"
    const val KEY_SCALE = "pref_cursor_scale"
    const val KEY_FLOAT_SCALE = "pref_cursor_float_scale"
    const val KEY_STYLE = "pref_cursor_style"
    const val KEY_CUSTOM_SVG = "pref_cursor_custom_svg"
    const val KEY_CUSTOM_HOT_X = "pref_cursor_custom_hot_x"
    const val KEY_CUSTOM_HOT_Y = "pref_cursor_custom_hot_y"
    /** 指针速度：黑屏触控 / 触控板 / 物理鼠标共用，1.0 为原始手感 */
    const val KEY_SPEED = "pref_pointer_speed"

    /** 样式预设：0=箭头 1=圆点 2=方块 3=自定义SVG */
    const val STYLE_ARROW = 0
    const val STYLE_DOT = 1
    const val STYLE_BLOCK = 2
    const val STYLE_CUSTOM_SVG = 3
    @JvmField val STYLE_NAMES = arrayOf("箭头", "圆点", "方块", "自定义SVG")

    /** 大小预设 */
    @JvmField val SCALE_NAMES = arrayOf("标准", "大", "特大")
    @JvmField val SCALES = floatArrayOf(1f, 1.6f, 2.2f)

    const val KEY_AUTO_HIDE = "pref_cursor_autohide_ms"

    @Volatile @JvmStatic var autoHideMs: Long = 2000L
        private set

    @Volatile @JvmStatic var scale: Float = 1f
        private set
    @Volatile @JvmStatic var style: Int = STYLE_ARROW
        private set
    @Volatile @JvmStatic var customSvgPath: String? = null
        private set
    @Volatile @JvmStatic var customHotX: Float = 50f
        private set
    @Volatile @JvmStatic var customHotY: Float = 50f
        private set
    @Volatile @JvmStatic var speed: Float = 1f
        private set

    // 多屏幕独立缓存：displayId -> 属性值
    private val displayScales = ConcurrentHashMap<Int, Float>()
    private val displayStyles = ConcurrentHashMap<Int, Int>()
    private val displaySvgPaths = ConcurrentHashMap<Int, String>()
    private val displayHotspots = ConcurrentHashMap<Int, Pair<Float, Float>>()

    private val listeners = mutableListOf<(Int) -> Unit>()

    @JvmStatic
    fun load(context: Context) {
        val p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (p.contains(KEY_FLOAT_SCALE)) {
            scale = p.getFloat(KEY_FLOAT_SCALE, 1f).coerceIn(0.03f, 400.0f)
        } else {
            scale = indexToScale(p.getInt(KEY_SCALE, 0))
        }
        style = p.getInt(KEY_STYLE, STYLE_ARROW).coerceIn(STYLE_ARROW, STYLE_CUSTOM_SVG)
        customSvgPath = p.getString(KEY_CUSTOM_SVG, null)
        customHotX = p.getFloat(KEY_CUSTOM_HOT_X, 50f).coerceIn(0f, 100f)
        customHotY = p.getFloat(KEY_CUSTOM_HOT_Y, 50f).coerceIn(0f, 100f)
        autoHideMs = p.getLong(KEY_AUTO_HIDE, 0L).coerceAtLeast(0L)
        speed = p.getFloat(KEY_SPEED, 1f).coerceIn(0.4f, 2.6f)
    }

    /** 读取指定屏幕的指针缩放比例（默认 1.0f） */
    @JvmStatic
    fun getScaleForDisplay(context: Context, displayId: Int): Float {
        if (displayId > 0 && displayScales.containsKey(displayId)) {
            return displayScales[displayId] ?: 1f
        }
        val p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val k = if (displayId > 0) "${KEY_FLOAT_SCALE}_$displayId" else KEY_FLOAT_SCALE
        val s = if (p.contains(k)) p.getFloat(k, 1f).coerceIn(0.03f, 400.0f) else scale
        if (displayId > 0) displayScales[displayId] = s
        return s
    }

    /** 读取指定屏幕的指针样式（默认 0=箭头） */
    @JvmStatic
    fun getStyleForDisplay(context: Context, displayId: Int): Int {
        if (displayId > 0 && displayStyles.containsKey(displayId)) {
            return displayStyles[displayId] ?: STYLE_ARROW
        }
        val p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val k = if (displayId > 0) "${KEY_STYLE}_$displayId" else KEY_STYLE
        val st = p.getInt(k, style).coerceIn(STYLE_ARROW, STYLE_CUSTOM_SVG)
        if (displayId > 0) displayStyles[displayId] = st
        return st
    }

    /** 读取指定屏幕的自定义 SVG Path */
    @JvmStatic
    fun getCustomSvgForDisplay(context: Context, displayId: Int): String? {
        if (displayId > 0 && displaySvgPaths.containsKey(displayId)) {
            return displaySvgPaths[displayId]
        }
        val p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val k = if (displayId > 0) "${KEY_CUSTOM_SVG}_$displayId" else KEY_CUSTOM_SVG
        val path = p.getString(k, customSvgPath)
        if (displayId > 0 && path != null) displaySvgPaths[displayId] = path
        return path
    }

    /** 读取指定屏幕的自定义热点 (hotX, hotY) */
    @JvmStatic
    fun getCustomHotspotForDisplay(context: Context, displayId: Int): Pair<Float, Float> {
        if (displayId > 0 && displayHotspots.containsKey(displayId)) {
            return displayHotspots[displayId] ?: (50f to 50f)
        }
        val p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val kx = if (displayId > 0) "${KEY_CUSTOM_HOT_X}_$displayId" else KEY_CUSTOM_HOT_X
        val ky = if (displayId > 0) "${KEY_CUSTOM_HOT_Y}_$displayId" else KEY_CUSTOM_HOT_Y
        val hx = p.getFloat(kx, customHotX).coerceIn(0f, 100f)
        val hy = p.getFloat(ky, customHotY).coerceIn(0f, 100f)
        val pair = hx to hy
        if (displayId > 0) displayHotspots[displayId] = pair
        return pair
    }

    /** 为指定屏幕单独设置无级调节大小 */
    @JvmStatic
    fun setContinuousScaleForDisplay(context: Context, displayId: Int, newScale: Float) {
        val s = newScale.coerceIn(0.03f, 400.0f)
        val ed = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
        if (displayId > 0) {
            ed.putFloat("${KEY_FLOAT_SCALE}_$displayId", s)
            displayScales[displayId] = s
        } else {
            ed.putFloat(KEY_FLOAT_SCALE, s)
            scale = s
        }
        ed.apply()
        notifyChanged(displayId)
    }

    /** 为指定屏幕单独设置样式索引 */
    @JvmStatic
    fun setStyleForDisplay(context: Context, displayId: Int, newStyle: Int) {
        val st = newStyle.coerceIn(STYLE_ARROW, STYLE_CUSTOM_SVG)
        val ed = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
        if (displayId > 0) {
            ed.putInt("${KEY_STYLE}_$displayId", st)
            displayStyles[displayId] = st
        } else {
            ed.putInt(KEY_STYLE, st)
            style = st
        }
        ed.apply()
        notifyChanged(displayId)
    }

    /** 为指定屏幕单独设置自定义 SVG 与热点 */
    @JvmStatic
    fun setCustomSvgForDisplay(context: Context, displayId: Int, pathData: String?, hotX: Float, hotY: Float) {
        val hx = hotX.coerceIn(0f, 100f)
        val hy = hotY.coerceIn(0f, 100f)
        val ed = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
        if (displayId > 0) {
            ed.putString("${KEY_CUSTOM_SVG}_$displayId", pathData)
            ed.putFloat("${KEY_CUSTOM_HOT_X}_$displayId", hx)
            ed.putFloat("${KEY_CUSTOM_HOT_Y}_$displayId", hy)
            ed.putInt("${KEY_STYLE}_$displayId", STYLE_CUSTOM_SVG)
            if (pathData != null) displaySvgPaths[displayId] = pathData
            displayHotspots[displayId] = hx to hy
            displayStyles[displayId] = STYLE_CUSTOM_SVG
        } else {
            ed.putString(KEY_CUSTOM_SVG, pathData)
            ed.putFloat(KEY_CUSTOM_HOT_X, hx)
            ed.putFloat(KEY_CUSTOM_HOT_Y, hy)
            ed.putInt(KEY_STYLE, STYLE_CUSTOM_SVG)
            customSvgPath = pathData
            customHotX = hx
            customHotY = hy
            style = STYLE_CUSTOM_SVG
        }
        ed.apply()
        notifyChanged(displayId)
    }

    @JvmStatic
    fun setCustomHotspot(context: Context, hotX: Float, hotY: Float) {
        val hx = hotX.coerceIn(0f, 100f)
        val hy = hotY.coerceIn(0f, 100f)
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putFloat(KEY_CUSTOM_HOT_X, hx)
            .putFloat(KEY_CUSTOM_HOT_Y, hy)
            .apply()
        customHotX = hx
        customHotY = hy
        notifyChanged(0)
    }

    @JvmStatic
    fun setPointerSpeed(context: Context, newSpeed: Float) {
        val s = newSpeed.coerceIn(0.4f, 2.6f)
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putFloat(KEY_SPEED, s).apply()
        speed = s
    }

    @JvmStatic
    fun scheduleAutoHide(handler: android.os.Handler, hide: Runnable) {
        handler.removeCallbacks(hide)
        if (autoHideMs > 0L) handler.postDelayed(hide, autoHideMs)
    }

    @JvmStatic
    fun setContinuousScale(context: Context, newScale: Float) {
        setContinuousScaleForDisplay(context, 0, newScale)
    }

    @JvmStatic
    fun setCustomSvgPath(context: Context, pathData: String?) {
        setCustomSvgForDisplay(context, 0, pathData, customHotX, customHotY)
    }

    @JvmStatic
    fun setScaleIndex(context: Context, index: Int) {
        val i = index.coerceIn(0, SCALES.size - 1)
        val s = SCALES[i]
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putInt(KEY_SCALE, i).putFloat(KEY_FLOAT_SCALE, s).apply()
        scale = s
        notifyChanged(0)
    }

    @JvmStatic
    fun scaleIndex(): Int {
        var best = 0
        for (i in SCALES.indices) if (kotlin.math.abs(SCALES[i] - scale) < kotlin.math.abs(SCALES[best] - scale)) best = i
        return best
    }

    @JvmStatic
    fun setStyleIndex(context: Context, index: Int) {
        setStyleForDisplay(context, 0, index)
    }

    private fun indexToScale(index: Int): Float =
        SCALES[index.coerceIn(0, SCALES.size - 1)]

    @JvmStatic
    fun onChanged(listener: () -> Unit) {
        synchronized(listeners) { listeners.add { listener() } }
    }

    @JvmStatic
    fun onDisplayChanged(listener: (Int) -> Unit) {
        synchronized(listeners) { listeners.add(listener) }
    }

    private fun notifyChanged(targetDisplayId: Int) {
        synchronized(listeners) { listeners.toList().forEach { it(targetDisplayId) } }
    }
}
