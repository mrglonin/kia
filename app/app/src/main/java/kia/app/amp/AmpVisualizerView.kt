package kia.app.amp

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.util.AttributeSet
import android.view.View
import kia.app.core.model.AmpState

class AmpVisualizerView : View {
    private val paint: Paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val path: Path = Path()
    private val rect: RectF = RectF()
    private var state: AmpState = AmpState.empty()
    private var enabled: Boolean = true

    constructor(context: Context?) : super(context) {
        init()
    }

    constructor(context: Context?, attrs: AttributeSet?) : super(context, attrs) {
        init()
    }

    private fun init() {
        setMinimumHeight(dp(210))
    }

    fun setState(nextState: AmpState?, nextEnabled: Boolean) {
        state = if (nextState == null) AmpState.empty() else nextState
        enabled = nextEnabled
        invalidate()
    }

    protected override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val width: Int = MeasureSpec.getSize(widthMeasureSpec)
        val target: Int = if (width < dp(760)) dp(230) else dp(255)
        val height: Int = resolveSize(target, heightMeasureSpec)
        setMeasuredDimension(width, height)
    }

    protected override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w: Float = getWidth().toFloat()
        val h: Float = getHeight().toFloat()
        val alpha: Float = if (enabled) 1f else 0.38f

        drawStage(canvas, w, h, alpha)
        drawSignalGlow(canvas, w, h, alpha)
        drawSpeakerMap(canvas, w, h, alpha)
        drawAmpCore(canvas, w, h, alpha)
        drawToneBars(canvas, w, h, alpha)
        drawBalanceFader(canvas, w, h, alpha)
        drawFooter(canvas, w, h, alpha)
    }

    private fun drawStage(canvas: Canvas, w: Float, h: Float, alpha: Float) {
        val radius: Float = dp(8).toFloat()
        rect.set(0f, 0f, w, h)
        paint.setStyle(Paint.Style.FILL)
        paint.setShader(
            LinearGradient(
                0f,
                0f,
                w,
                h,
                intArrayOf(
                    AmpVisualizerView.COLOR_BG,
                    Color.rgb(20, 25, 33),
                    AmpVisualizerView.COLOR_BG,
                ),
                floatArrayOf(0f, 0.55f, 1f),
                Shader.TileMode.CLAMP,
            )
        )
        canvas.drawRoundRect(rect, radius, radius, paint)
        paint.setShader(null)

        paint.setStyle(Paint.Style.STROKE)
        paint.setStrokeWidth(dp(1).toFloat())
        paint.setColor(AmpVisualizerView.applyAlpha(Color.WHITE, Math.round(16 * alpha)))
        canvas.drawRoundRect(rect, radius, radius, paint)
    }

    private fun drawSignalGlow(canvas: Canvas, w: Float, h: Float, alpha: Float) {
        val cx: Float = w * 0.5f
        val cy: Float = h * 0.48f
        val radius: Float = Math.min(w, h) * 0.44f
        paint.setStyle(Paint.Style.FILL)
        paint.setShader(
            RadialGradient(
                cx,
                cy,
                radius,
                intArrayOf(
                    AmpVisualizerView.applyAlpha(
                        AmpVisualizerView.COLOR_ACCENT,
                        Math.round(62 * alpha),
                    ),
                    AmpVisualizerView.applyAlpha(
                        AmpVisualizerView.COLOR_ACCENT,
                        Math.round(18 * alpha),
                    ),
                    Color.TRANSPARENT,
                ),
                floatArrayOf(0f, 0.45f, 1f),
                Shader.TileMode.CLAMP,
            )
        )
        canvas.drawCircle(cx, cy, radius, paint)
        paint.setShader(null)
    }

    private fun drawSpeakerMap(canvas: Canvas, w: Float, h: Float, alpha: Float) {
        val pad: Float = Math.max(dp(18).toFloat(), w * 0.04f)
        val top: Float = h * 0.18f
        val bottom: Float = h * 0.72f
        val left: Float = pad + dp(20)
        val right: Float = w - pad - dp(20)
        val coreLeft: Float = w * 0.39f
        val coreRight: Float = w * 0.61f
        val coreTop: Float = h * 0.31f
        val coreBottom: Float = h * 0.61f

        paint.setStyle(Paint.Style.STROKE)
        paint.setStrokeWidth(dp(2).toFloat())
        paint.setColor(
            AmpVisualizerView.applyAlpha(AmpVisualizerView.COLOR_LINE, Math.round(180 * alpha))
        )
        drawCable(canvas, left + dp(18), top + dp(13), coreLeft, coreTop + dp(12))
        drawCable(canvas, right - dp(18), top + dp(13), coreRight, coreTop + dp(12))
        drawCable(canvas, left + dp(18), bottom + dp(13), coreLeft, coreBottom - dp(12))
        drawCable(canvas, right - dp(18), bottom + dp(13), coreRight, coreBottom - dp(12))

        drawSpeaker(canvas, left, top, "FL", alpha)
        drawSpeaker(canvas, right, top, "FR", alpha)
        drawSpeaker(canvas, left, bottom, "RL", alpha)
        drawSpeaker(canvas, right, bottom, "RR", alpha)
    }

    private fun drawCable(canvas: Canvas, x1: Float, y1: Float, x2: Float, y2: Float) {
        path.reset()
        path.moveTo(x1, y1)
        path.cubicTo((x1 + x2) * 0.5f, y1, (x1 + x2) * 0.5f, y2, x2, y2)
        canvas.drawPath(path, paint)
    }

    private fun drawSpeaker(canvas: Canvas, x: Float, y: Float, label: String?, alpha: Float) {
        val size: Float = dp(44).toFloat()
        rect.set(x - size * 0.5f, y - size * 0.5f, x + size * 0.5f, y + size * 0.5f)
        paint.setStyle(Paint.Style.FILL)
        paint.setColor(
            AmpVisualizerView.applyAlpha(AmpVisualizerView.COLOR_PANEL, Math.round(235 * alpha))
        )
        canvas.drawRoundRect(rect, dp(7).toFloat(), dp(7).toFloat(), paint)
        paint.setStyle(Paint.Style.STROKE)
        paint.setStrokeWidth(dp(2).toFloat())
        paint.setColor(
            AmpVisualizerView.applyAlpha(AmpVisualizerView.COLOR_ACCENT, Math.round(135 * alpha))
        )
        canvas.drawRoundRect(rect, dp(7).toFloat(), dp(7).toFloat(), paint)

        paint.setStyle(Paint.Style.FILL)
        paint.setColor(
            AmpVisualizerView.applyAlpha(AmpVisualizerView.COLOR_ACCENT, Math.round(225 * alpha))
        )
        canvas.drawCircle(x, y, dp(10).toFloat(), paint)
        paint.setColor(
            AmpVisualizerView.applyAlpha(AmpVisualizerView.COLOR_BG, Math.round(255 * alpha))
        )
        canvas.drawCircle(x, y, dp(5).toFloat(), paint)
        drawText(
            canvas,
            label,
            x,
            y + size * 0.5f + dp(17),
            dp(12).toFloat(),
            AmpVisualizerView.COLOR_MUTED,
            Paint.Align.CENTER,
            alpha,
            true,
        )
    }

    private fun drawAmpCore(canvas: Canvas, w: Float, h: Float, alpha: Float) {
        val coreW: Float = Math.min(dp(360).toFloat(), w * 0.34f)
        val coreH: Float = Math.min(dp(112).toFloat(), h * 0.42f)
        val cx: Float = w * 0.5f
        val cy: Float = h * 0.46f
        rect.set(cx - coreW * 0.5f, cy - coreH * 0.5f, cx + coreW * 0.5f, cy + coreH * 0.5f)

        paint.setStyle(Paint.Style.FILL)
        paint.setShader(
            LinearGradient(
                rect.left,
                rect.top,
                rect.right,
                rect.bottom,
                intArrayOf(Color.rgb(13, 16, 22), Color.rgb(27, 34, 43), Color.rgb(12, 15, 20)),
                null,
                Shader.TileMode.CLAMP,
            )
        )
        canvas.drawRoundRect(rect, dp(12).toFloat(), dp(12).toFloat(), paint)
        paint.setShader(null)

        paint.setStyle(Paint.Style.STROKE)
        paint.setStrokeWidth(dp(1).toFloat())
        paint.setColor(
            AmpVisualizerView.applyAlpha(AmpVisualizerView.COLOR_ACCENT, Math.round(115 * alpha))
        )
        canvas.drawRoundRect(rect, dp(12).toFloat(), dp(12).toFloat(), paint)

        val volume: Float = AmpVisualizerView.clamp01(state.volume / 40f)
        val knobCx: Float = rect.left + coreW * 0.22f
        val knobCy: Float = cy
        val knobRadius: Float = Math.min(dp(36).toFloat(), coreH * 0.32f)
        paint.setStyle(Paint.Style.STROKE)
        paint.setStrokeCap(Paint.Cap.ROUND)
        paint.setStrokeWidth(dp(7).toFloat())
        paint.setColor(AmpVisualizerView.applyAlpha(Color.WHITE, Math.round(30 * alpha)))
        canvas.drawArc(
            knobCx - knobRadius,
            knobCy - knobRadius,
            knobCx + knobRadius,
            knobCy + knobRadius,
            135f,
            270f,
            false,
            paint,
        )
        paint.setColor(
            AmpVisualizerView.applyAlpha(AmpVisualizerView.COLOR_ACCENT, Math.round(230 * alpha))
        )
        canvas.drawArc(
            knobCx - knobRadius,
            knobCy - knobRadius,
            knobCx + knobRadius,
            knobCy + knobRadius,
            135f,
            270 * volume,
            false,
            paint,
        )
        paint.setStrokeCap(Paint.Cap.BUTT)
        drawText(
            canvas,
            java.lang.String.valueOf(state.volume),
            knobCx,
            knobCy + dp(5),
            dp(18).toFloat(),
            AmpVisualizerView.COLOR_TEXT,
            Paint.Align.CENTER,
            alpha,
            true,
        )
        drawText(
            canvas,
            "VOL",
            knobCx,
            knobCy + dp(27),
            dp(10).toFloat(),
            AmpVisualizerView.COLOR_MUTED,
            Paint.Align.CENTER,
            alpha,
            true,
        )

        val textX: Float = rect.left + coreW * 0.42f
        drawText(
            canvas,
            "AMP",
            textX,
            cy - dp(12),
            dp(22).toFloat(),
            AmpVisualizerView.COLOR_TEXT,
            Paint.Align.LEFT,
            alpha,
            true,
        )
        drawText(
            canvas,
            if (enabled) "баланс / фейдер" else "выключен",
            textX,
            cy + dp(16),
            dp(12).toFloat(),
            if (enabled) AmpVisualizerView.COLOR_MUTED else AmpVisualizerView.COLOR_WARNING,
            Paint.Align.LEFT,
            alpha,
            false,
        )

        val ledX: Float = rect.right - dp(20)
        val ledY: Float = rect.top + dp(18)
        paint.setStyle(Paint.Style.FILL)
        paint.setColor(
            AmpVisualizerView.applyAlpha(
                if (enabled) AmpVisualizerView.COLOR_ACCENT else AmpVisualizerView.COLOR_WARNING,
                Math.round(215 * alpha),
            )
        )
        canvas.drawCircle(ledX, ledY, dp(5).toFloat(), paint)
    }

    private fun drawToneBars(canvas: Canvas, w: Float, h: Float, alpha: Float) {
        val startX: Float = w * 0.5f - dp(92)
        val y: Float = h * 0.77f
        drawToneBar(canvas, startX, y, "B", state.bass - 10, alpha)
        drawToneBar(canvas, startX + dp(92), y, "M", state.mid - 10, alpha)
        drawToneBar(canvas, startX + dp(184), y, "T", state.treble - 10, alpha)
    }

    private fun drawToneBar(
        canvas: Canvas,
        x: Float,
        y: Float,
        label: String?,
        value: Int,
        alpha: Float,
    ) {
        val barW: Float = dp(58).toFloat()
        val base: Float = y + dp(20)
        val top: Float = y - dp(36)
        paint.setStyle(Paint.Style.STROKE)
        paint.setStrokeWidth(dp(5).toFloat())
        paint.setStrokeCap(Paint.Cap.ROUND)
        paint.setColor(AmpVisualizerView.applyAlpha(Color.WHITE, Math.round(38 * alpha)))
        canvas.drawLine(x, base, x, top, paint)
        val pos: Float = base - (base - top) * ((value + 10) / 20f)
        paint.setColor(
            AmpVisualizerView.applyAlpha(
                if (value >= 0) AmpVisualizerView.COLOR_ACCENT else AmpVisualizerView.COLOR_WARNING,
                Math.round(215 * alpha),
            )
        )
        canvas.drawLine(x, base, x, pos, paint)
        paint.setStrokeCap(Paint.Cap.BUTT)
        drawText(
            canvas,
            label,
            x,
            base + dp(18),
            dp(12).toFloat(),
            AmpVisualizerView.COLOR_MUTED,
            Paint.Align.CENTER,
            alpha,
            true,
        )
        drawText(
            canvas,
            AmpVisualizerView.signed(value),
            x + barW * 0.38f,
            pos + dp(5),
            dp(11).toFloat(),
            AmpVisualizerView.COLOR_TEXT,
            Paint.Align.LEFT,
            alpha,
            true,
        )
    }

    private fun drawBalanceFader(canvas: Canvas, w: Float, h: Float, alpha: Float) {
        val coreW: Float = Math.min(dp(360).toFloat(), w * 0.34f)
        val coreH: Float = Math.min(dp(112).toFloat(), h * 0.42f)
        val cx: Float = w * 0.5f
        val cy: Float = h * 0.46f
        val coreRight: Float = cx + coreW * 0.5f
        val box: Float = Math.min(dp(52).toFloat(), coreH * 0.46f)
        val mapCx: Float = coreRight - dp(62)
        val mapCy: Float = cy
        val balance: Float = AmpVisualizerView.clampSigned(state.balance - 10)
        val fader: Float = AmpVisualizerView.clampSigned(state.fader - 10)
        val dotX: Float = mapCx + balance / 10f * box * 0.34f
        val dotY: Float = mapCy - fader / 10f * box * 0.34f

        rect.set(mapCx - box * 0.5f, mapCy - box * 0.5f, mapCx + box * 0.5f, mapCy + box * 0.5f)
        paint.setStyle(Paint.Style.FILL)
        paint.setColor(AmpVisualizerView.applyAlpha(Color.WHITE, Math.round(12 * alpha)))
        canvas.drawRoundRect(rect, dp(6).toFloat(), dp(6).toFloat(), paint)

        paint.setStyle(Paint.Style.STROKE)
        paint.setStrokeWidth(dp(1).toFloat())
        paint.setColor(AmpVisualizerView.applyAlpha(Color.WHITE, Math.round(44 * alpha)))
        canvas.drawLine(rect.left + dp(7), mapCy, rect.right - dp(7), mapCy, paint)
        canvas.drawLine(mapCx, rect.top + dp(7), mapCx, rect.bottom - dp(7), paint)

        paint.setStyle(Paint.Style.FILL)
        paint.setColor(
            AmpVisualizerView.applyAlpha(AmpVisualizerView.COLOR_WARNING, Math.round(210 * alpha))
        )
        canvas.drawCircle(dotX, dotY, dp(5).toFloat(), paint)
    }

    private fun drawFooter(canvas: Canvas, w: Float, h: Float, alpha: Float) {
        val text: String =
            if (state.updatedAt == 0L) "ожидание ответа усилителя" else "mode " + state.mode
        drawText(
            canvas,
            text,
            w - dp(18),
            h - dp(16),
            dp(12).toFloat(),
            AmpVisualizerView.COLOR_MUTED,
            Paint.Align.RIGHT,
            alpha,
            false,
        )
    }

    private fun drawText(
        canvas: Canvas,
        text: String?,
        x: Float,
        y: Float,
        size: Float,
        color: Int,
        align: Paint.Align?,
        alpha: Float,
        bold: Boolean,
    ) {
        paint.setShader(null)
        paint.setStyle(Paint.Style.FILL)
        paint.setTextAlign(align)
        paint.setTextSize(size)
        paint.setFakeBoldText(bold)
        paint.setColor(AmpVisualizerView.applyAlpha(color, Math.round(Color.alpha(color) * alpha)))
        canvas.drawText(text!!, x, y, paint)
        paint.setFakeBoldText(false)
    }

    private fun dp(value: Int): Int {
        return Math.round(value * getResources().getDisplayMetrics().density)
    }

    companion object {
        private val COLOR_BG: Int = Color.rgb(27, 32, 40)
        private val COLOR_PANEL: Int = Color.rgb(15, 18, 24)
        private val COLOR_LINE: Int = Color.argb(78, 255, 255, 255)
        private val COLOR_TEXT: Int = Color.rgb(245, 248, 252)
        private val COLOR_MUTED: Int = Color.rgb(157, 172, 190)
        private val COLOR_ACCENT: Int = Color.rgb(57, 211, 190)
        private val COLOR_WARNING: Int = Color.rgb(245, 176, 78)

        private fun signed(value: Int): String? {
            return if (value > 0) "+" + value else java.lang.String.valueOf(value)
        }

        private fun clamp01(value: Float): Float {
            return Math.max(0f, Math.min(1f, value))
        }

        private fun clampSigned(value: Int): Float {
            return Math.max(-10f, Math.min(10f, value.toFloat()))
        }

        private fun applyAlpha(color: Int, alpha: Int): Int {
            return Color.argb(
                Math.max(0, Math.min(255, alpha)),
                Color.red(color),
                Color.green(color),
                Color.blue(color),
            )
        }
    }
}
