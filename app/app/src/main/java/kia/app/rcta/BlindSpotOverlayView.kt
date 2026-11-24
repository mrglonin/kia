package kia.app.rcta

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.os.SystemClock
import android.util.AttributeSet
import android.view.View
import kia.app.R
import kia.app.core.settings.AppSettings

class BlindSpotOverlayView(context: Context?, attrs: AttributeSet?, defStyleAttr: Int) :
    View(context, attrs, defStyleAttr) {
    private val paint: Paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val teyesPaint: Paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val path: Path = Path()
    private val teyesBounds: RectF = RectF()
    private var teyesLeftWarning: Bitmap? = null
    private var teyesRightWarning: Bitmap? = null
    private var teyesLeftStartedAt: Long = 0
    private var teyesRightStartedAt: Long = 0
    private var previewLeft: Boolean = true
    private var previewRight: Boolean = true
    private var previewUnknown: Boolean = false
    private var bottomLiftPx: Int = 0
    private var styleType: Int = 1
    private var alertColor: Int = BlindSpotOverlayView.COLOR_AMBER
    private var backgroundAlpha: Int = BlindSpotOverlayView.GLOW_ALPHA
    private var arrowCount: Int = BlindSpotOverlayView.CHEVRON_COUNT_MIN

    constructor(context: Context?) : this(context, null)

    constructor(context: Context?, attrs: AttributeSet?) : this(context, attrs, 0)

    init {
        setWillNotDraw(false)
        setClickable(false)
    }

    fun setPreview(left: Boolean, right: Boolean, unknown: Boolean) {
        val now: Long = SystemClock.uptimeMillis()
        if ((left || unknown) && !(previewLeft || previewUnknown)) teyesLeftStartedAt = now
        if ((right || unknown) && !(previewRight || previewUnknown)) teyesRightStartedAt = now
        previewLeft = left
        previewRight = right
        previewUnknown = unknown
        invalidate()
    }

    fun setBottomLiftDp(value: Int) {
        bottomLiftPx = Math.max(0, dp(value))
        invalidate()
    }

    fun setStyleType(value: Int) {
        val next: Int =
            if (value == AppSettings.RCTA_STYLE_TEYES) AppSettings.RCTA_STYLE_TEYES
            else (if (value == 2) 2 else 1)
        if (styleType != next && next == AppSettings.RCTA_STYLE_TEYES) resetTeyesPulse()
        styleType = next
        invalidate()
    }

    fun setAlertColor(value: Int) {
        alertColor = forceOpaque(value)
        invalidate()
    }

    fun setBackgroundAlpha(value: Int) {
        backgroundAlpha = clamp(value, 0, 255)
        invalidate()
    }

    fun setArrowCount(value: Int) {
        arrowCount =
            clamp(
                value,
                BlindSpotOverlayView.CHEVRON_COUNT_MIN,
                BlindSpotOverlayView.CHEVRON_COUNT_MAX,
            )
        invalidate()
    }

    protected override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        if (styleType == AppSettings.RCTA_STYLE_TEYES) resetTeyesPulse()
        postInvalidateOnAnimation()
    }

    protected override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (!previewLeft && !previewRight && !previewUnknown) return
        val w: Int = getWidth()
        val h: Int = Math.max(1, getHeight() - bottomLiftPx)
        if (w <= 0) return

        val now: Long = SystemClock.uptimeMillis()
        if (styleType == AppSettings.RCTA_STYLE_TEYES) {
            drawTeyesWarnings(canvas, w, h, now)
            postInvalidateOnAnimation()
            return
        }
        val phase: Float = if (isInEditMode()) 0.5f else (now % 900L) / 900f
        val pulse: Float = 0.52f + 0.48f * (1f - Math.abs(phase - 0.5f) * 2f)
        val drawLeft: Boolean = previewLeft || previewUnknown
        val drawRight: Boolean = previewRight || previewUnknown
        if (drawLeft) drawSide(canvas, w, h, true, phase, pulse)
        if (drawRight) drawSide(canvas, w, h, false, phase, pulse)
        postInvalidateOnAnimation()
    }

    private fun resetTeyesPulse() {
        teyesLeftStartedAt = SystemClock.uptimeMillis()
        teyesRightStartedAt = teyesLeftStartedAt
    }

    private fun drawTeyesWarnings(canvas: Canvas, w: Int, h: Int, now: Long) {
        // RCTA exposes active left/right only, so use the original red level-1 assets.
        // The amber level-0 assets must not invent a lower-severity vehicle state.
        if (teyesLeftWarning == null) {
            teyesLeftWarning =
                BitmapFactory.decodeResource(getResources(), R.drawable.teyes_lca_warn_left_1)
            teyesRightWarning =
                BitmapFactory.decodeResource(getResources(), R.drawable.teyes_lca_warn_right_1)
        }
        val width: Float = w * BlindSpotOverlayView.TEYES_DANGER_WIDTH_FRACTION
        if (previewLeft || previewUnknown) {
            teyesBounds.set(0f, 0f, width, h.toFloat())
            drawTeyesSide(canvas, teyesLeftWarning, now, teyesLeftStartedAt)
        }
        if (previewRight || previewUnknown) {
            teyesBounds.set(w - width, 0f, w.toFloat(), h.toFloat())
            drawTeyesSide(canvas, teyesRightWarning, now, teyesRightStartedAt)
        }
    }

    private fun drawTeyesSide(canvas: Canvas, bitmap: Bitmap?, now: Long, startedAt: Long) {
        if (bitmap == null) return
        // ShineAlphaView uses 75 ms cosine-eased legs: alpha 1 -> .2 -> 1.
        val phase: Float =
            (Math.max(0L, now - startedAt) % BlindSpotOverlayView.TEYES_PULSE_CYCLE_MS /
                BlindSpotOverlayView.TEYES_PULSE_CYCLE_MS.toFloat())
        val alpha: Float =
            if (isInEditMode()) 1f else 0.6f + 0.4f * Math.cos(phase * Math.PI * 2).toFloat()
        teyesPaint.setAlpha(Math.round(255f * alpha))
        canvas.drawBitmap(bitmap, null, teyesBounds, teyesPaint)
    }

    private fun drawSide(
        canvas: Canvas,
        w: Int,
        h: Int,
        leftSide: Boolean,
        phase: Float,
        pulse: Float,
    ) {
        val baseColor: Int = if (previewUnknown) BlindSpotOverlayView.COLOR_RED else alertColor
        drawCornerGlow(canvas, w, h, leftSide, baseColor, pulse)
        if (styleType == 1) {
            drawCornerRail(canvas, w, h, leftSide, baseColor)
        }
        drawCornerChevronStack(canvas, w, h, leftSide, baseColor, phase)
    }

    private fun drawCornerGlow(
        canvas: Canvas,
        w: Int,
        h: Int,
        leftSide: Boolean,
        baseColor: Int,
        pulse: Float,
    ) {
        val glowW: Float = dp(BlindSpotOverlayView.GLOW_WIDTH_DP).toFloat()
        val glowH: Float = dp(BlindSpotOverlayView.GLOW_HEIGHT_DP).toFloat()
        val centerX: Float = if (leftSide) 0f else w.toFloat()
        val centerY: Float = h.toFloat()
        val radius: Float = Math.max(glowW, glowH)
        val pulsedAlpha: Int = Math.round(backgroundAlpha * clampFloat(pulse, 0.45f, 1f))

        canvas.save()
        if (leftSide) {
            canvas.clipRect(0f, h - glowH, glowW, h.toFloat())
        } else {
            canvas.clipRect(w - glowW, h - glowH, w.toFloat(), h.toFloat())
        }
        paint.setStyle(Paint.Style.FILL)
        paint.setShader(
            RadialGradient(
                centerX,
                centerY,
                radius,
                intArrayOf(
                    withAlpha(baseColor, pulsedAlpha),
                    withAlpha(baseColor, Math.round(pulsedAlpha * 0.42f)),
                    withAlpha(baseColor, 0),
                ),
                floatArrayOf(0f, 0.48f, 1f),
                Shader.TileMode.CLAMP,
            )
        )
        canvas.drawCircle(centerX, centerY, radius, paint)
        paint.setShader(null)
        canvas.restore()
    }

    private fun drawCornerRail(canvas: Canvas, w: Int, h: Int, leftSide: Boolean, baseColor: Int) {
        val cornerX: Float =
            (if (leftSide) dp(BlindSpotOverlayView.RAIL_MARGIN_X_DP)
                else w - dp(BlindSpotOverlayView.RAIL_MARGIN_X_DP))
                .toFloat()
        val bottomY: Float = (h - dp(BlindSpotOverlayView.RAIL_BOTTOM_DP)).toFloat()
        val direction: Float = if (leftSide) 1f else -1f
        val railW: Float = dp(BlindSpotOverlayView.RAIL_HORIZONTAL_WIDTH_DP).toFloat()
        val railH: Float = dp(BlindSpotOverlayView.RAIL_VERTICAL_HEIGHT_DP).toFloat()

        path.reset()
        path.moveTo(cornerX, bottomY - railH)
        path.lineTo(cornerX, bottomY)
        path.lineTo(cornerX + direction * railW, bottomY)
        paint.setStyle(Paint.Style.STROKE)
        paint.setStrokeCap(Paint.Cap.ROUND)
        paint.setStrokeJoin(Paint.Join.ROUND)
        paint.setStrokeWidth(dp(BlindSpotOverlayView.RAIL_STROKE_DP).toFloat())
        paint.setShader(null)
        paint.setColor(forceOpaque(baseColor))
        canvas.drawPath(path, paint)
    }

    private fun drawCornerChevronStack(
        canvas: Canvas,
        w: Int,
        h: Int,
        leftSide: Boolean,
        baseColor: Int,
        phase: Float,
    ) {
        val size: Float = dp(BlindSpotOverlayView.CHEVRON_SIZE_DP).toFloat()
        val bottomDp: Int =
            if (styleType == 2) BlindSpotOverlayView.CHEVRON_BOTTOM_TYPE_2_DP
            else BlindSpotOverlayView.CHEVRON_BOTTOM_DP
        val marginStart: Int =
            if (styleType == 2) BlindSpotOverlayView.CHEVRON_MARGIN_TYPE_2_START_DP
            else BlindSpotOverlayView.CHEVRON_MARGIN_START_DP
        val top: Float = h - dp(bottomDp) - size
        val phaseStep: Float = if (arrowCount <= 3) 0.22f else 0.72f / arrowCount

        paint.setStyle(Paint.Style.STROKE)
        paint.setStrokeCap(Paint.Cap.ROUND)
        paint.setStrokeJoin(Paint.Join.ROUND)
        paint.setStrokeWidth(dp(BlindSpotOverlayView.CHEVRON_STROKE_DP).toFloat())

        for (i in 0..<arrowCount) {
            val margin: Float =
                dp(marginStart + i * BlindSpotOverlayView.CHEVRON_MARGIN_STEP_DP).toFloat()
            val arrowPhase: Float = (phase - i * phaseStep + 1f) % 1f
            val arrowPulse: Float = 1f - Math.abs(arrowPhase - 0.5f) * 2f
            val movement: Float = dp(BlindSpotOverlayView.CHEVRON_TRAVEL_DP) * arrowPulse
            val left: Float = if (leftSide) margin + movement else w - margin - size - movement
            paint.setColor(forceOpaque(baseColor))
            drawChevron(canvas, left, top, size, leftSide)
        }
    }

    private fun drawChevron(
        canvas: Canvas,
        left: Float,
        top: Float,
        size: Float,
        leftSide: Boolean,
    ) {
        path.reset()
        if (leftSide) {
            path.moveTo(left + size * 0.32f, top + size * 0.16f)
            path.lineTo(left + size * 0.70f, top + size * 0.50f)
            path.lineTo(left + size * 0.32f, top + size * 0.84f)
        } else {
            path.moveTo(left + size * 0.68f, top + size * 0.16f)
            path.lineTo(left + size * 0.30f, top + size * 0.50f)
            path.lineTo(left + size * 0.68f, top + size * 0.84f)
        }
        canvas.drawPath(path, paint)
    }

    private fun dp(value: Int): Int {
        return Math.round(value * getResources().getDisplayMetrics().density)
    }

    private fun withAlpha(color: Int, alpha: Int): Int {
        return (clamp(alpha, 0, 255) shl 24) or (color and 0x00ffffff)
    }

    private fun forceOpaque(color: Int): Int {
        return -0x1000000 or (color and 0x00ffffff)
    }

    private fun clamp(value: Int, min: Int, max: Int): Int {
        return Math.max(min, Math.min(max, value))
    }

    private fun clampFloat(value: Float, min: Float, max: Float): Float {
        return Math.max(min, Math.min(max, value))
    }

    companion object {
        private const val COLOR_AMBER: Int = -0x3bc5
        private const val COLOR_RED: Int = -0xac9c
        private const val GLOW_WIDTH_DP: Int = 400
        private const val GLOW_HEIGHT_DP: Int = 300
        private const val GLOW_ALPHA: Int = 75
        private const val RAIL_MARGIN_X_DP: Int = 32
        private const val RAIL_BOTTOM_DP: Int = 24
        private const val RAIL_STROKE_DP: Int = 7
        private const val RAIL_VERTICAL_HEIGHT_DP: Int = 100
        private const val RAIL_HORIZONTAL_WIDTH_DP: Int = 150
        private const val CHEVRON_SIZE_DP: Int = 54
        private const val CHEVRON_BOTTOM_DP: Int = 60
        private const val CHEVRON_BOTTOM_TYPE_2_DP: Int = 44
        private const val CHEVRON_STROKE_DP: Int = 7
        private const val CHEVRON_TRAVEL_DP: Int = 10
        private const val CHEVRON_MARGIN_START_DP: Int = 72
        private const val CHEVRON_MARGIN_TYPE_2_START_DP: Int = 34
        private const val CHEVRON_MARGIN_STEP_DP: Int = 54
        private const val CHEVRON_COUNT_MIN: Int = 3
        private const val CHEVRON_COUNT_MAX: Int = 6
        private const val TEYES_PULSE_CYCLE_MS: Long = 150L
        // Original ADAS danger windows use 943 * (screenWidth / 2000), full height.
        private val TEYES_DANGER_WIDTH_FRACTION: Float = 943f / 2000f
    }
}
