package com.alkaidlab.sunshine.service

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.view.View
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min

/**
 * 统一软件指针视图：USB 物理鼠标、黑屏触控、Moonlight 远程鼠标共用。
 *
 * 绘制使用固定像素基准（不随 display density）——Moonlight 虚拟屏密度被压到
 * 100dpi 左右对齐有线桌面逻辑宽度，若随 density 缩放指针会小到看不清。
 * 大小 = 基准尺寸 × scale；样式由 style 决定。
 *
 * 支持按屏幕 displayId 独立配置：外接电视/显示器设置大指针，Moonlight 串流设置小指针，
 * 互不联动、各存各的。
 */
class MouseCursorView(context: Context) : View(context) {

    var displayId: Int = detectDisplayId(context)
        set(value) {
            field = value
            refresh()
        }

    private var style = MouseCursorPrefs.getStyleForDisplay(context, displayId)
    private var scale = MouseCursorPrefs.getScaleForDisplay(context, displayId)
    private var offsetX = 0f
    private var offsetY = 0f

    // 基准箭头路径（约 20×31 px，经典 Windows 箭头形状）
    private val arrow = Path().apply {
        moveTo(0f, 0f); lineTo(0f, 27f); lineTo(6.8f, 21f)
        lineTo(11.4f, 31f); lineTo(16.2f, 28.7f); lineTo(11.5f, 18.8f)
        lineTo(20f, 18.8f); close()
    }

    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE; style = Paint.Style.FILL
    }
    private val outline = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(15, 20, 30); style = Paint.Style.STROKE
        strokeWidth = 2.2f; strokeJoin = Paint.Join.ROUND
    }
    private val ringFill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(115, 31, 191, 168); style = Paint.Style.FILL
    }

    private val pad = 2.5f

    private var customPath: Path? = null
    private var customOffsetX = 0f
    private var customOffsetY = 0f
    private val pathBounds = RectF()

    private fun intrinsicSize(): Pair<Float, Float> = when (style) {
        MouseCursorPrefs.STYLE_DOT -> 12f to 12f
        MouseCursorPrefs.STYLE_BLOCK -> 14f to 14f
        MouseCursorPrefs.STYLE_CUSTOM_SVG -> {
            val p = customPath
            if (p != null) {
                p.computeBounds(pathBounds, true)
                max(1f, pathBounds.width()) to max(1f, pathBounds.height())
            } else 16f to 16f
        }
        else -> 20f to 31f
    }

    init {
        MouseCursorPrefs.onDisplayChanged { targetId ->
            if (targetId == 0 || targetId == displayId) {
                refresh()
            }
        }
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.N) {
            // 本 View 自己就是光标，系统指针必须为空，否则会多画一个箭头。
            val icon = com.aosen.shizukudex.EmptyPointerFrameLayout.getEmptyPointerIcon(context)
            if (icon != null) pointerIcon = icon
        }
        updateCustomPath(MouseCursorPrefs.getCustomSvgForDisplay(context, displayId))
    }

    private fun detectDisplayId(ctx: Context): Int {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
            try {
                val d = ctx.display
                if (d != null) return d.displayId
            } catch (_: Throwable) {}
        }
        return 0
    }

    private fun updateCustomPath(svgData: String?) {
        customPath = try {
            if (!svgData.isNullOrBlank()) {
                androidx.core.graphics.PathParser.createPathFromPathData(svgData.trim())
            } else null
        } catch (_: Throwable) {
            null
        }
        val p = customPath
        if (p != null) {
            p.computeBounds(pathBounds, true)
            customOffsetX = -pathBounds.left
            customOffsetY = -pathBounds.top
        } else {
            customOffsetX = 0f
            customOffsetY = 0f
        }
    }

    override fun onResolvePointerIcon(event: android.view.MotionEvent, pointerIndex: Int): android.view.PointerIcon? {
        // 光标由 onDraw 绘制，系统层一律返回空图标。
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.N) {
            return com.aosen.shizukudex.EmptyPointerFrameLayout.getEmptyPointerIcon(context)
        }
        return null
    }

    fun getHotspotOffset(): Pair<Float, Float> {
        val (hx, hy) = when (style) {
            MouseCursorPrefs.STYLE_DOT -> 6f to 6f
            MouseCursorPrefs.STYLE_BLOCK -> 7f to 7f
            MouseCursorPrefs.STYLE_CUSTOM_SVG -> {
                val p = customPath
                if (p != null) {
                    p.computeBounds(pathBounds, true)
                    val w = max(1f, pathBounds.width())
                    val h = max(1f, pathBounds.height())
                    val (hotX, hotY) = MouseCursorPrefs.getCustomHotspotForDisplay(context, displayId)
                    (w * (hotX / 100f)) to (h * (hotY / 100f))
                } else {
                    8f to 8f
                }
            }
            else -> 0f to 0f
        }
        val hotPixelX = (hx + pad) * scale
        val hotPixelY = (hy + pad) * scale
        return hotPixelX to hotPixelY
    }

    /** overlay 全屏窗口模式：内部平移定位（窗口内模式用 setX/setY，不走这里） */
    fun moveTo(x: Float, y: Float) {
        val curScale = MouseCursorPrefs.getScaleForDisplay(context, displayId)
        val curStyle = MouseCursorPrefs.getStyleForDisplay(context, displayId)
        if (scale != curScale || style != curStyle) {
            refresh()
        }
        val (hotX, hotY) = getHotspotOffset()
        translationX = 0f
        translationY = 0f
        offsetX = x - hotX
        offsetY = y - hotY
        invalidate()
    }

    /** 视图内模式（DeXPresentation）：直接设置 translation，硬件图层加速零重绘，杜绝卡顿 */
    fun setCursorPosition(x: Float, y: Float) {
        val curScale = MouseCursorPrefs.getScaleForDisplay(context, displayId)
        val curStyle = MouseCursorPrefs.getStyleForDisplay(context, displayId)
        if (scale != curScale || style != curStyle) {
            refresh()
        }
        val (hotX, hotY) = getHotspotOffset()
        offsetX = 0f
        offsetY = 0f
        translationX = x - hotX
        translationY = y - hotY
    }

    override fun onVisibilityChanged(changedView: View, visibility: Int) {
        super.onVisibilityChanged(changedView, visibility)
        if (visibility == VISIBLE) {
            refresh()
        }
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val widthMode = MeasureSpec.getMode(widthMeasureSpec)
        val heightMode = MeasureSpec.getMode(heightMeasureSpec)
        val widthSize = MeasureSpec.getSize(widthMeasureSpec)
        val heightSize = MeasureSpec.getSize(heightMeasureSpec)

        val (iw, ih) = intrinsicSize()
        val extra = pad * 2f + outline.strokeWidth + 2f
        val needW = ceil((iw + extra) * scale).toInt().coerceAtLeast(1)
        val needH = ceil((ih + extra) * scale).toInt().coerceAtLeast(1)

        val width = when (widthMode) {
            MeasureSpec.EXACTLY -> max(widthSize, needW)
            MeasureSpec.AT_MOST -> min(widthSize, needW)
            else -> needW
        }
        val height = when (heightMode) {
            MeasureSpec.EXACTLY -> max(heightSize, needH)
            MeasureSpec.AT_MOST -> min(heightSize, needH)
            else -> needH
        }
        setMeasuredDimension(width, height)
    }

    fun refresh() {
        style = MouseCursorPrefs.getStyleForDisplay(context, displayId)
        scale = MouseCursorPrefs.getScaleForDisplay(context, displayId)
        updateCustomPath(MouseCursorPrefs.getCustomSvgForDisplay(context, displayId))
        if (android.os.Looper.myLooper() == android.os.Looper.getMainLooper()) {
            requestLayout()
            invalidate()
        } else {
            post { requestLayout(); invalidate() }
        }
    }

    /**
     * 虚拟外设刚插到这块屏时，从光标位置向外发一圈扩散波纹，表示「它已经到这个桌面了」。
     * 画在父布局的 overlay 上：不参与事件分发、不改子 View 层级、也不会被光标自身的小方框裁掉。
     * 手机主屏 / 有线外屏 / Moonlight 串流屏共用这一份实现。
     */
    fun showArrivalRipple(x: Float, y: Float) {
        if (android.os.Looper.myLooper() != android.os.Looper.getMainLooper()) {
            post { showArrivalRipple(x, y) }
            return
        }
        val host = parent as? android.view.ViewGroup ?: return
        val ripple = ArrivalRipple(context)
        host.overlay.add(ripple)
        ripple.translationX = x - ArrivalRipple.SIZE / 2f
        ripple.translationY = y - ArrivalRipple.SIZE / 2f
        ripple.start { host.overlay.remove(ripple) }
    }

    override fun onDraw(canvas: Canvas) {
        canvas.save()
        canvas.translate(offsetX, offsetY)
        canvas.scale(scale, scale)
        canvas.translate(pad, pad)
        when (style) {
            MouseCursorPrefs.STYLE_DOT -> {
                canvas.drawCircle(6f, 6f, 6f, fill)
                canvas.drawCircle(6f, 6f, 6f, outline)
            }
            MouseCursorPrefs.STYLE_BLOCK -> {
                canvas.drawRect(0f, 0f, 14f, 14f, fill)
                canvas.drawRect(0f, 0f, 14f, 14f, outline)
            }
            MouseCursorPrefs.STYLE_CUSTOM_SVG -> {
                val p = customPath
                if (p != null) {
                    canvas.save()
                    canvas.translate(customOffsetX, customOffsetY)
                    canvas.drawPath(p, fill)
                    canvas.drawPath(p, outline)
                    canvas.restore()
                } else {
                    canvas.drawCircle(8f, 8f, 7f, ringFill)
                    canvas.drawCircle(8f, 8f, 7f, outline)
                    canvas.drawCircle(8f, 8f, 3f, fill)
                }
            }
            else -> {
                canvas.drawPath(arrow, fill)
                canvas.drawPath(arrow, outline)
            }
        }
        canvas.restore()
    }
}

/** 一圈向外扩散的到达波纹；动画结束由调用方从 overlay 移除。 */
private class ArrivalRipple(context: android.content.Context) : View(context) {
    companion object { const val SIZE = 240f }

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = Color.rgb(96, 205, 255)
    }
    private var progress = 0f

    init {
        layoutParams = android.view.ViewGroup.LayoutParams(SIZE.toInt(), SIZE.toInt())
        // overlay 里的纯装饰 View，绝不拦截触摸。
        isClickable = false
        isFocusable = false
    }

    fun start(onEnd: () -> Unit) {
        val anim = android.animation.ValueAnimator.ofFloat(0f, 1f)
        anim.duration = 720L
        anim.interpolator = android.view.animation.DecelerateInterpolator()
        anim.addUpdateListener {
            progress = it.animatedValue as Float
            invalidate()
        }
        anim.addListener(object : android.animation.AnimatorListenerAdapter() {
            override fun onAnimationEnd(animation: android.animation.Animator) { onEnd() }
        })
        anim.start()
    }

    override fun onDraw(canvas: Canvas) {
        val c = SIZE / 2f
        val maxR = c - 6f
        for (i in 0 until 3) {
            val p = (progress - i * 0.14f).coerceAtLeast(0f)
            if (p <= 0f) continue
            val e = 1f - (1f - p) * (1f - p)   // 向外先快后慢
            paint.alpha = ((1f - e) * 190f).toInt()
            paint.strokeWidth = 4f - 2.4f * e
            canvas.drawCircle(c, c, 8f + (maxR - 8f) * e, paint)
        }
    }
}
