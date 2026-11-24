package kia.app.tpms

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.os.SystemClock
import android.view.View
import java.util.HashMap
import java.util.Locale
import kia.app.R
import kia.app.core.model.NavigationState
import kia.app.core.model.TpmsState
import kia.app.core.model.VehicleState
import kia.app.navigation.domain.DashboardRoadSpeedLimit
import kia.app.sunroof.SunroofStateMemory

class TpmsDashboardView(context: Context?) : View(context) {
    private val paint: Paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val dst: RectF = RectF()
    private val path: Path = Path()
    private val cache: MutableMap<Int, Bitmap> = HashMap()
    private var state: TpmsState = TpmsState.empty()
    private var navigation: NavigationState = NavigationState.empty()
    private val roofSlide: RoofSlide
    private var carRenderer: DashboardSunroof? = null
    private var reportedRoof: Boolean? = null
    private var roadLimit: DashboardRoadSpeedLimit? = DashboardRoadSpeedLimit.NONE
    private var motionSpeedKmh: Float = 0f
    private var compactSpeedBadgeOffset: Float = Float.NaN
    private var widgetMode: Boolean = false
    private val freshnessRefresh: Runnable = Runnable {
        updateAccessibilitySummary()
        invalidate()
    }

    init {
        paint.setFilterBitmap(true)
        val last: SunroofStateMemory.Snapshot = SunroofStateMemory.snapshot(getContext())
        roofSlide = RoofSlide(if (last.known) last.open else null)
    }

    fun setState(value: TpmsState?) {
        state = if (value == null) TpmsState.empty() else value
        updateAccessibilitySummary()
        scheduleFreshnessRefresh()
        invalidate()
    }

    fun setMotionSpeedKmh(value: Float) {
        val clean: Float = Math.max(0f, Math.min(240f, value))
        val wasMoving: Boolean = moving()
        motionSpeedKmh = clean
        if (wasMoving || moving()) invalidate()
    }

    fun setNavigationState(value: NavigationState?, limit: DashboardRoadSpeedLimit?) {
        navigation = if (value == null) NavigationState.empty() else value
        if (roadLimit !== limit) {
            roadLimit = limit
            updateAccessibilitySummary()
        }
        invalidate()
    }

    fun setVehicleState(value: VehicleState?) {
        val open: Boolean? = if (value != null && value.sunroofKnown) value.sunroofOpen else null
        if (if (open == null) reportedRoof == null else open.equals(reportedRoof)) return
        reportedRoof = open
        roofSlide.update(
            open,
            isShown() && ValueAnimator.areAnimatorsEnabled(),
            SystemClock.uptimeMillis(),
        )
        updateAccessibilitySummary()
        invalidate()
    }

    fun setWidgetMode(value: Boolean) {
        if (widgetMode == value) return
        widgetMode = value
        compactSpeedBadgeOffset = Float.NaN
        invalidate()
    }

    protected override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val width: Int = getWidth()
        val height: Int = getHeight()
        val portrait: Boolean = width < height
        val compactWindow: Boolean = width < 900 || height <= 560
        if ((widgetMode && portrait) || portrait || compactWindow) {
            drawDarkBackground(canvas)
            drawCompactDashboard(canvas, width, height)
            scheduleFreshnessRefresh()
            if (moving()) postInvalidateOnAnimation()
            else if (hasAlert()) postInvalidateDelayed(360L)
            return
        }
        val scale: Float = Math.min(width / 1280f, height / 720f)
        val ox: Float = (width - 1280f * scale) / 2f
        val oy: Float = (height - 720f * scale) / 2f

        drawDarkBackground(canvas)
        drawRoad(canvas, ox, oy, scale)
        drawCar(canvas, ox, oy, scale)
        drawTires(canvas, ox, oy, scale)
        // Wide dashboards used to omit navigation completely. Keep the hint
        // inside the center lane so it never covers the tire cards.
        drawNavigationHints(canvas, ox + 408f * scale, ox + 872f * scale, oy + 18f * scale)
        scheduleFreshnessRefresh()
        if (moving()) postInvalidateOnAnimation() else if (hasAlert()) postInvalidateDelayed(360L)
    }

    protected override fun onDetachedFromWindow() {
        removeCallbacks(freshnessRefresh)
        super.onDetachedFromWindow()
    }

    private fun scheduleFreshnessRefresh() {
        removeCallbacks(freshnessRefresh)
        if (state == null || state.known == null || state.wheelUpdatedAt == null) return
        val signRemaining: Long = roadLimit!!.remainingMs(SystemClock.elapsedRealtime())
        var nextDelay: Long = if (signRemaining > 0L) signRemaining else Long.MAX_VALUE
        for (wheel in 0..<TpmsState.WHEEL_COUNT) {
            if (!known(wheel) || !state.isWheelFresh(wheel)) continue
            val remaining: Long = TpmsState.WHEEL_STALE_AFTER_MS + 1L - state.wheelAgeMs(wheel)
            nextDelay = Math.min(nextDelay, Math.max(1L, remaining))
        }
        if (nextDelay != Long.MAX_VALUE) postDelayed(freshnessRefresh, nextDelay)
    }

    private fun updateAccessibilitySummary() {
        val summary: String = if (state == null) "TPMS: нет данных" else state.summary()
        val limit: Int = speedLimitKmh()
        setContentDescription(
            if (limit > 0) summary + ". Ограничение скорости " + limit + " км/ч" else summary
        )
    }

    private fun drawDarkBackground(canvas: Canvas) {
        canvas.drawColor(-0xf2f0ed)
        paint.clearShadowLayer()
        paint.setStyle(Paint.Style.FILL)
        paint.setShader(
            LinearGradient(
                0f,
                0f,
                0f,
                getHeight().toFloat(),
                -0xebe8e3,
                -0xf7f6f4,
                Shader.TileMode.CLAMP,
            )
        )
        dst.set(0f, 0f, getWidth().toFloat(), getHeight().toFloat())
        canvas.drawRect(dst, paint)
        paint.setShader(null)
    }

    private fun drawTires(canvas: Canvas, ox: Float, oy: Float, scale: Float) {
        val panelW: Float = 350f * scale
        val panelH: Float = 196f * scale
        val side: Float = 42f * scale
        val left: Float = ox + side
        val right: Float = ox + 1280f * scale - side - panelW
        val top: Float = oy + 120f * scale
        val bottom: Float = oy + 404f * scale

        drawTire(canvas, 0, "Л.П.(L.F.)", left, top, panelW, panelH, scale)
        drawTire(canvas, 1, "П.П.(R.F.)", right, top, panelW, panelH, scale)
        drawTire(canvas, 2, "Л.З.(L.R.)", left, bottom, panelW, panelH, scale)
        drawTire(canvas, 3, "П.З.(R.R.)", right, bottom, panelW, panelH, scale)
    }

    private fun drawTire(
        canvas: Canvas,
        wheel: Int,
        title: String?,
        x: Float,
        y: Float,
        w: Float,
        h: Float,
        scale: Float,
    ) {
        val known: Boolean = known(wheel)
        val fresh: Boolean = fresh(wheel)
        val warning: Int = warning(wheel)
        val severity: Int = severity(wheel)
        val alert: Boolean = fresh && severity != TpmsAlertController.SEVERITY_NONE
        val alertColor: Int = severityColor(severity)
        drawTireCardBackground(canvas, x, y, w, h, scale, severity)
        val leftPad: Float = 30f * scale
        val rightPad: Float = 26f * scale

        paint.clearShadowLayer()
        paint.setStyle(Paint.Style.FILL)
        paint.setTypeface(Typeface.DEFAULT_BOLD)
        paint.setColor(-0x655c51)
        paint.setTextSize(17f * scale)
        paint.setTextAlign(Paint.Align.LEFT)
        drawTextCenterY(canvas, title, x + leftPad, y + 31f * scale, paint)

        drawStatusChip(
            canvas,
            known,
            fresh,
            warning,
            severity,
            x + w - 80f * scale,
            y + 31f * scale,
            scale,
        )

        paint.setTypeface(Typeface.DEFAULT_BOLD)
        paint.setTextSize(58f * scale)
        paint.setTextAlign(Paint.Align.LEFT)
        paint.setColor(if (fresh) -0xb0e16 else -0x8b8277)
        if (fresh) setTextShadow(scale) else paint.clearShadowLayer()
        drawTextBottom(
            canvas,
            if (known) pressureText(wheel) else "__",
            x + leftPad,
            y + h - 42f * scale,
            paint,
        )

        paint.clearShadowLayer()
        paint.setTypeface(Typeface.DEFAULT_BOLD)
        paint.setTextSize(21f * scale)
        paint.setColor(if (fresh) -0xb0e16 else -0x8b8277)
        drawTextBottom(canvas, "Bar", x + leftPad + 5f * scale, y + h - 18f * scale, paint)

        paint.setTextAlign(Paint.Align.RIGHT)
        paint.setTypeface(Typeface.DEFAULT_BOLD)
        paint.setTextSize(32f * scale)
        paint.setColor(if (alert) alertColor else (if (fresh) -0xb0e16 else -0x8b8277))
        drawTextBottom(
            canvas,
            (if (known) tempText(wheel) else "__").toString() + "°C",
            x + w - rightPad,
            y + h - 74f * scale,
            paint,
        )

        paint.clearShadowLayer()
        paint.setTextAlign(Paint.Align.RIGHT)
        paint.setTypeface(Typeface.DEFAULT_BOLD)
        paint.setTextSize(16f * scale)
        paint.setColor(-0x655c51)
        drawTextBottom(canvas, "Температура", x + w - rightPad, y + h - 36f * scale, paint)
    }

    private fun drawCompactDashboard(canvas: Canvas, width: Int, height: Int) {
        if (height <= 560 && width <= 900) {
            drawCompactGridOnly(canvas, width, height)
        } else if (width > height && height <= 680) {
            drawCompactWideDashboard(canvas, width, height)
        } else {
            drawCompactPortraitDashboard(canvas, width, height)
        }
    }

    private fun drawCompactPortraitDashboard(canvas: Canvas, width: Int, height: Int) {
        val min: Float = Math.min(width, height).toFloat()
        val narrowTall: Boolean = narrowTallWidgetMode()
        val pad: Float =
            if (narrowTall) clamp(min * 0.038f, 14f, 22f) else clamp(min * 0.045f, 12f, 26f)
        val topH: Float =
            if (narrowTall) clamp(height * 0.52f, 320f, Math.min(height * 0.58f, width * 0.98f))
            else clamp(height * 0.59f, 280f, Math.min(height * 0.66f, width * 1.18f))
        val carCx: Float = width * 0.5f
        val verticalShift: Float = topH * (if (narrowTall) 0.070f else 0.10f)
        val carCy: Float = pad + topH * (if (narrowTall) 0.66f else 0.56f) + verticalShift
        drawCompactRoad(
            canvas,
            carCx,
            pad * 0.25f + verticalShift,
            pad + topH + pad * 0.5f + verticalShift,
            width * (if (narrowTall) 0.30f else 0.34f),
            width * (if (narrowTall) 0.68f else 0.78f),
        )
        drawCompactNavigationHints(canvas, width, pad)
        val carW: Float =
            Math.min(
                width * (if (narrowTall) 0.64f else 0.64f),
                topH * (if (narrowTall) 0.68f else 0.66f),
            )
        val carH: Float =
            Math.min(
                topH * (if (narrowTall) 0.86f else 0.92f),
                height * (if (narrowTall) 0.47f else 0.52f),
            )
        drawSportage(canvas, carCx, carCy, carW, carH)
        if (hasAlert()) {
            val warningSize: Float = Math.min(width * 0.20f, topH * 0.26f)
            drawFitAspect(
                canvas,
                R.drawable.tpms_car_warning_center,
                carCx,
                carCy - topH * 0.02f,
                warningSize,
                warningSize,
            )
        }

        var gridTop: Float = pad + topH + pad * (if (narrowTall) 0.35f else 0.55f)
        val gridBottom: Float = height - pad
        val gap: Float = clamp(width * 0.020f, 8f, 14f)
        if (gridBottom - gridTop < 230f) gridTop = height * 0.42f
        val cardW: Float = (width - pad * 2f - gap) / 2f
        val cardH: Float =
            if (narrowTall) clamp((gridBottom - gridTop - gap) / 2f, 118f, 136f)
            else clamp((gridBottom - gridTop - gap) / 2f, 124f, 146f)
        gridTop = gridBottom - cardH * 2f - gap
        drawCompactTireCard(canvas, TpmsState.WHEEL_FL, pad, gridTop, cardW, cardH)
        drawCompactTireCard(canvas, TpmsState.WHEEL_FR, pad + cardW + gap, gridTop, cardW, cardH)
        drawCompactTireCard(canvas, TpmsState.WHEEL_RL, pad, gridTop + cardH + gap, cardW, cardH)
        drawCompactTireCard(
            canvas,
            TpmsState.WHEEL_RR,
            pad + cardW + gap,
            gridTop + cardH + gap,
            cardW,
            cardH,
        )
    }

    private fun drawCompactWideDashboard(canvas: Canvas, width: Int, height: Int) {
        val pad: Float = clamp(height * 0.045f, 10f, 18f)
        val gap: Float = clamp(height * 0.026f, 8f, 12f)
        val cardW: Float = clamp(width * 0.26f, 210f, width * 0.31f)
        val cardH: Float = (height - pad * 2f - gap) / 2f
        drawCompactTireCard(canvas, TpmsState.WHEEL_FL, pad, pad, cardW, cardH)
        drawCompactTireCard(canvas, TpmsState.WHEEL_RL, pad, pad + cardH + gap, cardW, cardH)
        drawCompactTireCard(canvas, TpmsState.WHEEL_FR, width - pad - cardW, pad, cardW, cardH)
        drawCompactTireCard(
            canvas,
            TpmsState.WHEEL_RR,
            width - pad - cardW,
            pad + cardH + gap,
            cardW,
            cardH,
        )
        val carCx: Float = width * 0.5f
        val carCy: Float = height * 0.52f
        val roadTop: Float = pad * 0.5f
        val roadBottom: Float = height - pad * 0.4f
        drawCompactRoad(canvas, carCx, roadTop, roadBottom, width * 0.10f, width * 0.25f)
        drawNavigationHints(canvas, pad + cardW + gap, width - pad - cardW - gap, pad)
        drawSportage(
            canvas,
            carCx,
            carCy,
            Math.max(120f, width - cardW * 2f - pad * 5f),
            height * 0.96f,
        )
        if (hasAlert()) {
            val warningSize: Float = Math.min(height * 0.26f, 76f)
            drawFitAspect(
                canvas,
                R.drawable.tpms_car_warning_center,
                carCx,
                carCy - height * 0.02f,
                warningSize,
                warningSize,
            )
        }
    }

    private fun drawCompactGridOnly(canvas: Canvas, width: Int, height: Int) {
        val pad: Float = clamp(Math.min(width, height) * 0.038f, 9f, 16f)
        val gap: Float = clamp(Math.min(width, height) * 0.025f, 7f, 12f)
        var top: Float = pad
        val limit: Int = speedLimitKmh()
        if (limit > 0 && height >= 240) {
            val radius: Float = clamp(height * 0.06f, 14f, 26f)
            drawSpeedLimitBadge(canvas, width * 0.5f, pad + radius, radius, limit)
            top += radius * 2f + gap
        }
        val cardW: Float = (width - pad * 2f - gap) / 2f
        val cardH: Float = (height - pad - top - gap) / 2f
        drawCompactTireCard(canvas, TpmsState.WHEEL_FL, pad, top, cardW, cardH)
        drawCompactTireCard(canvas, TpmsState.WHEEL_FR, pad + cardW + gap, top, cardW, cardH)
        drawCompactTireCard(canvas, TpmsState.WHEEL_RL, pad, top + cardH + gap, cardW, cardH)
        drawCompactTireCard(
            canvas,
            TpmsState.WHEEL_RR,
            pad + cardW + gap,
            top + cardH + gap,
            cardW,
            cardH,
        )
    }

    private fun drawCompactTireCard(
        canvas: Canvas,
        wheel: Int,
        x: Float,
        y: Float,
        w: Float,
        h: Float,
    ) {
        val known: Boolean = known(wheel)
        val fresh: Boolean = fresh(wheel)
        val warning: Int = warning(wheel)
        val severity: Int = severity(wheel)
        val alert: Boolean = fresh && severity != TpmsAlertController.SEVERITY_NONE
        val tinyWidget: Boolean = tinyWidgetMode()
        val narrowTall: Boolean = narrowTallWidgetMode()
        val scale: Float = clamp(Math.min(w / 300f, h / 170f), 0.64f, 1.12f)
        drawTireCardBackground(canvas, x, y, w, h, scale, severity)

        val padX: Float =
            if (tinyWidget) clamp(w * 0.082f, 18f, 28f)
            else (if (narrowTall) clamp(w * 0.095f, 24f, 30f) else clamp(w * 0.078f, 14f, 24f))
        val padY: Float =
            if (tinyWidget) clamp(h * 0.18f, 26f, 34f)
            else (if (narrowTall) clamp(h * 0.20f, 25f, 31f) else clamp(h * 0.15f, 18f, 24f))

        paint.clearShadowLayer()
        paint.setStyle(Paint.Style.FILL)
        paint.setTextAlign(Paint.Align.LEFT)
        paint.setTypeface(Typeface.DEFAULT_BOLD)
        paint.setColor(-0x5a5247)
        paint.setTextSize(
            if (tinyWidget) clamp(h * 0.22f, 30f, 40f)
            else (if (narrowTall) clamp(h * 0.158f, 19f, 23f) else clamp(h * 0.132f, 16f, 22f))
        )
        drawTextCenterY(canvas, wheelTitle(wheel), x + padX, y + padY, paint)

        var chipRight: Float = x + w - padX
        if (tinyWidget && wheel == TpmsState.WHEEL_FR) {
            chipRight -= clamp(w * 0.12f, 42f, 54f)
        }
        drawCompactStatusChip(canvas, known, fresh, warning, severity, chipRight, y + padY, scale)

        paint.setTypeface(Typeface.DEFAULT_BOLD)
        paint.setTextAlign(Paint.Align.LEFT)
        paint.setColor(if (fresh) -0xb0e16 else -0x8b8277)
        paint.setTextSize(
            if (tinyWidget) clamp(Math.min(w * 0.28f, h * 0.46f), 56f, 76f)
            else
                (if (narrowTall) clamp(Math.min(w * 0.20f, h * 0.32f), 38f, 48f)
                else clamp(Math.min(w * 0.22f, h * 0.34f), 34f, 56f))
        )
        if (fresh) setTextShadow(scale) else paint.clearShadowLayer()
        drawTextBottom(
            canvas,
            if (known) pressureText(wheel) else "__",
            x + padX,
            y + h - (if (tinyWidget) clamp(h * 0.24f, 42f, 56f) else clamp(h * 0.23f, 30f, 42f)),
            paint,
        )

        paint.clearShadowLayer()
        paint.setTextSize(
            if (tinyWidget) clamp(h * 0.18f, 25f, 34f)
            else (if (narrowTall) clamp(h * 0.128f, 16f, 20f) else clamp(h * 0.128f, 16f, 22f))
        )
        drawTextBottom(
            canvas,
            "Bar",
            x + padX + 2f * scale,
            y + h - (if (tinyWidget) clamp(h * 0.08f, 18f, 26f) else clamp(h * 0.10f, 15f, 22f)),
            paint,
        )

        paint.setTextAlign(Paint.Align.RIGHT)
        paint.setTypeface(Typeface.DEFAULT_BOLD)
        paint.setColor(if (alert) severityColor(severity) else (if (fresh) -0xb0e16 else -0x8b8277))
        paint.setTextSize(
            if (tinyWidget) clamp(h * 0.39f, 55f, 75f)
            else (if (narrowTall) clamp(h * 0.265f, 35f, 44f) else clamp(h * 0.247f, 32f, 46f))
        )
        drawTextBottom(
            canvas,
            (if (known) tempText(wheel) else "__").toString() + "°C",
            x + w - padX,
            y + h -
                (if (tinyWidget) clamp(h * 0.32f, 54f, 70f)
                else (if (narrowTall) clamp(h * 0.32f, 41f, 52f) else clamp(h * 0.28f, 36f, 50f))),
            paint,
        )

        paint.clearShadowLayer()
        paint.setTypeface(Typeface.DEFAULT_BOLD)
        paint.setColor(-0x655c51)
        paint.setTextSize(
            if (tinyWidget) clamp(h * 0.135f, 20f, 26f)
            else (if (narrowTall) clamp(h * 0.11f, 14f, 17f) else clamp(h * 0.095f, 12f, 15f))
        )
        drawTextBottom(
            canvas,
            "Температура",
            x + w - padX,
            y + h -
                (if (tinyWidget) clamp(h * 0.10f, 20f, 28f)
                else (if (narrowTall) clamp(h * 0.105f, 14f, 18f) else clamp(h * 0.10f, 14f, 19f))),
            paint,
        )
    }

    private fun drawCompactStatusChip(
        canvas: Canvas,
        known: Boolean,
        fresh: Boolean,
        warning: Int,
        severity: Int,
        right: Float,
        cy: Float,
        scale: Float,
    ) {
        val text: String =
            if (!known) "ОЖИД." else (if (!fresh) "УСТАР." else compactWarningText(warning))
        val alert: Boolean = fresh && severity != TpmsAlertController.SEVERITY_NONE
        paint.setTypeface(Typeface.DEFAULT_BOLD)
        val tinyWidget: Boolean = tinyWidgetMode()
        val narrowTall: Boolean = narrowTallWidgetMode()
        paint.setTextSize(
            if (tinyWidget) 24.75f * scale else (if (narrowTall) 17.25f else 16.875f * scale)
        )
        paint.clearShadowLayer()
        val tw: Float = paint.measureText(text)
        val chipW: Float =
            if (tinyWidget) Math.max(96f * scale, tw + 25.5f * scale)
            else
                (if (narrowTall) Math.max(78f, tw + 21f)
                else Math.max(77f * scale, tw + 21f * scale))
        val chipHalfH: Float =
            if (tinyWidget) 22f * scale else (if (narrowTall) 18f else 17f * scale)
        dst.set(right - chipW, cy - chipHalfH, right, cy + chipHalfH)
        paint.setStyle(Paint.Style.FILL)
        paint.setColor(
            if (alert) severityColor(severity)
            else (if (fresh) -0xd8cec6 else (if (known) -0xcecbc6 else -0xddd8d1))
        )
        canvas.drawRoundRect(dst, 7f * scale, 7f * scale, paint)
        paint.setTextAlign(Paint.Align.CENTER)
        paint.setColor(
            if (alert && severity == TpmsAlertController.SEVERITY_WARNING) -0xebeef5 else -0xb0e16
        )
        drawTextCenterY(canvas, text, right - chipW / 2f, cy, paint)
    }

    private fun compactWarningText(warning: Int): String {
        when (warning) {
            TpmsAlertController.WARNING_LOW_PRESSURE -> return "НИЗКОЕ"
            TpmsAlertController.WARNING_HIGH_PRESSURE -> return "ВЫСОКОЕ"
            TpmsAlertController.WARNING_LOW_TEMP -> return "ХОЛОД"
            TpmsAlertController.WARNING_HIGH_TEMP -> return "ЖАРА"
            TpmsAlertController.WARNING_FAST_LEAKAGE -> return "УТЕЧКА"
            TpmsAlertController.WARNING_LOW_BATTERY -> return "БАТ."
            else -> return "НОРМА"
        }
    }

    private fun drawCompactRoad(
        canvas: Canvas,
        cx: Float,
        top: Float,
        bottom: Float,
        topW: Float,
        bottomW: Float,
    ) {
        path.reset()
        path.moveTo(cx - topW * 0.5f, top)
        path.lineTo(cx + topW * 0.5f, top)
        path.lineTo(cx + bottomW * 0.5f, bottom)
        path.lineTo(cx - bottomW * 0.5f, bottom)
        path.close()

        paint.clearShadowLayer()
        paint.setStyle(Paint.Style.FILL)
        paint.setShader(
            LinearGradient(
                cx,
                top,
                cx,
                bottom,
                intArrayOf(0x00181f27, 0x2227303a, 0x3a303942, 0x18161b21, 0x00161b21),
                floatArrayOf(0f, 0.18f, 0.52f, 0.82f, 1f),
                Shader.TileMode.CLAMP,
            )
        )
        canvas.drawPath(path, paint)
        paint.setShader(null)

        paint.setStyle(Paint.Style.STROKE)
        paint.setStrokeWidth(Math.max(1f, (bottom - top) / 520f))
        paint.setShader(
            LinearGradient(
                cx,
                top,
                cx,
                bottom,
                intArrayOf(0x00ffffff, 0x38ffffff, 0x30ffffff, 0x00ffffff),
                floatArrayOf(0f, 0.24f, 0.72f, 1f),
                Shader.TileMode.CLAMP,
            )
        )
        canvas.drawLine(cx - topW * 0.5f, top, cx - bottomW * 0.5f, bottom, paint)
        canvas.drawLine(cx + topW * 0.5f, top, cx + bottomW * 0.5f, bottom, paint)
        paint.setShader(null)

        canvas.save()
        canvas.clipPath(path)
        drawRoadDashes(canvas, cx, top, bottom, Math.max(0.62f, (bottom - top) / 720f))
        drawRoadSweep(
            canvas,
            cx,
            top,
            bottom,
            topW,
            bottomW,
            Math.max(0.62f, (bottom - top) / 720f),
        )
        canvas.restore()
        paint.setStyle(Paint.Style.FILL)
    }

    private fun drawCompactNavigationHints(canvas: Canvas, width: Int, pad: Float) {
        drawNavigationHints(canvas, 0f, width.toFloat(), pad)
    }

    private fun drawNavigationHints(
        canvas: Canvas,
        leftBound: Float,
        rightBound: Float,
        pad: Float,
    ) {
        val limit: Int = speedLimitKmh()
        val speed: Int = currentSpeedKmh()
        val speedVisibility: DashboardSpeedVisibilityPolicy.Decision =
            DashboardSpeedVisibilityPolicy.resolve(moving(), speed, limit)
        val hasLimit: Boolean = speedVisibility.showSpeedLimit
        val hasSpeed: Boolean = speedVisibility.showCurrentSpeed
        val hasRoute: Boolean = hasRouteHint()
        if (!hasLimit && !hasSpeed && !hasRoute) return

        val availableWidth: Float = Math.max(0f, rightBound - leftBound)
        val narrowTall: Boolean = narrowTallWidgetMode()
        val badgeRadius: Float =
            if (narrowTall) clamp(availableWidth * 0.085f, 42f, 54f)
            else if (availableWidth < 560) clamp(availableWidth * 0.105f, 46f, 62f)
            else clamp(availableWidth * 0.115f, 64f, 88f)
        val cx: Float = leftBound + pad + badgeRadius + 2f
        val cy: Float = pad + badgeRadius + (if (narrowTall) 7f else 2f)
        val limitRadius: Float = badgeRadius * 0.7225f
        if (hasSpeed) {
            val offset: Float = updateCompactSpeedBadgeOffset(hasLimit)
            val currentRadius: Float = badgeRadius * 0.81f
            val smallRadius: Float = badgeRadius * (if (narrowTall) 0.583f else 0.632f)
            val topCy: Float = cy
            val bottomCy: Float =
                cy + limitRadius + pad * (if (narrowTall) 0.34f else 0.54f) + smallRadius
            val speedCy: Float = lerp(topCy, bottomCy, offset)
            val speedRadius: Float = lerp(currentRadius, smallRadius, offset)
            drawCurrentSpeedBadge(
                canvas,
                cx,
                speedCy,
                speedRadius,
                speed,
                !hasLimit || offset < 0.35f,
            )
        } else {
            compactSpeedBadgeOffset = Float.NaN
        }
        if (hasLimit) {
            drawSpeedLimitBadge(canvas, cx, cy, limitRadius, limit)
        }
        if (hasRoute) {
            var left: Float =
                if (hasLimit || hasSpeed)
                    cx + badgeRadius + pad * (if (narrowTall) 0.82f else 1.05f)
                else pad
            if (!hasLimit && !hasSpeed) left += leftBound
            val right: Float = rightBound - pad - (if (narrowTall) 46f else 0f)
            if (right - left > 130f) {
                val halfH: Float = badgeRadius * (if (narrowTall) 0.88f else 0.92f)
                drawRouteHint(canvas, left, cy - halfH, right, cy + halfH)
            }
        }
    }

    private fun drawSportage(canvas: Canvas?, cx: Float, cy: Float, maxW: Float, maxH: Float) {
        if (carRenderer == null) {
            val car: Bitmap? = bitmap(R.drawable.sportage_dashboard)
            if (car == null) return
            carRenderer = DashboardSunroof(car, bitmap(R.drawable.sunroof_car_top))
        }
        val now: Long = SystemClock.uptimeMillis()
        carRenderer!!.draw(canvas!!, cx, cy, maxW * .90f, maxH * .84f, roofSlide.position(now))
        if (roofSlide.moving(now)) postInvalidateOnAnimation()
    }

    private fun drawSpeedLimitBadge(
        canvas: Canvas,
        cx: Float,
        cy: Float,
        radius: Float,
        limit: Int,
    ) {
        var cy: Float = cy
        var radius: Float = radius
        val progress: Float =
            clamp((SystemClock.elapsedRealtime() - roadLimit!!.changedAt) / 260f, 0f, 1f)
        if (progress < 1f) {
            val remaining: Float = (1f - progress) * (1f - progress)
            cy += radius * 0.22f * remaining
            radius *= 1f - 0.20f * remaining
            postInvalidateOnAnimation()
        }
        paint.clearShadowLayer()
        paint.setShader(null)
        paint.setStyle(Paint.Style.FILL)
        paint.setColor(-0x80809)
        canvas.drawCircle(cx, cy, radius, paint)
        paint.setStyle(Paint.Style.STROKE)
        paint.setStrokeWidth(Math.max(3f, radius * 0.16f))
        paint.setColor(-0x22cbbe)
        canvas.drawCircle(cx, cy, radius * 0.82f, paint)
        paint.setStyle(Paint.Style.FILL)
        paint.setTypeface(Typeface.DEFAULT_BOLD)
        paint.setTextAlign(Paint.Align.CENTER)
        setFittingTextSize(
            java.lang.String.valueOf(limit),
            radius * 1.34f,
            radius * 1.02f,
            radius * 0.64f,
        )
        paint.setColor(-0xeeeeef)
        drawTextCenterY(canvas, java.lang.String.valueOf(limit), cx, cy, paint)
    }

    private fun updateCompactSpeedBadgeOffset(hasLimit: Boolean): Float {
        val target: Float = if (hasLimit) 1f else 0f
        if (compactSpeedBadgeOffset.isNaN()) {
            compactSpeedBadgeOffset = target
            return compactSpeedBadgeOffset
        }
        val delta: Float = target - compactSpeedBadgeOffset
        if (Math.abs(delta) < 0.015f) {
            compactSpeedBadgeOffset = target
            return compactSpeedBadgeOffset
        }
        compactSpeedBadgeOffset += delta * 0.24f
        postInvalidateOnAnimation()
        return compactSpeedBadgeOffset
    }

    private fun drawCurrentSpeedBadge(
        canvas: Canvas,
        cx: Float,
        cy: Float,
        radius: Float,
        speed: Int,
        primary: Boolean,
    ) {
        paint.clearShadowLayer()
        paint.setStyle(Paint.Style.FILL)
        paint.setColor(if (primary) -0x27221c else -0x80809)
        canvas.drawCircle(cx, cy, radius, paint)
        paint.setStyle(Paint.Style.STROKE)
        paint.setStrokeWidth(Math.max(2f, radius * (if (primary) 0.07f else 0.08f)))
        paint.setColor(if (primary) -0x6f978d81 else -0x7fccc5bc)
        canvas.drawCircle(cx, cy, radius * 0.88f, paint)
        paint.setStyle(Paint.Style.FILL)
        paint.setTextAlign(Paint.Align.CENTER)
        paint.setTypeface(Typeface.DEFAULT_BOLD)
        val narrowTall: Boolean = narrowTallWidgetMode()
        setFittingTextSize(
            java.lang.String.valueOf(speed),
            radius * 1.28f,
            clamp(
                radius * 0.98f,
                if (narrowTall) (if (primary) 36f else 29f) else (if (primary) 44f else 36f),
                if (narrowTall) (if (primary) 58f else 45f) else (if (primary) 84f else 68f),
            ),
            24f,
        )
        paint.setColor(-0xeeeeef)
        drawTextCenterY(canvas, java.lang.String.valueOf(speed), cx, cy, paint)
    }

    private fun drawRouteHint(
        canvas: Canvas,
        left: Float,
        top: Float,
        right: Float,
        bottom: Float,
    ) {
        val snapshot: DashboardNavigationSnapshot = navigationSnapshot()
        var text: String? = clean(snapshot.distance)
        if (text!!.isEmpty()) text = clean(snapshot.presentation!!.fallbackLabel)
        if (text!!.length > 16) text = text.substring(0, 16)

        paint.clearShadowLayer()
        paint.setStyle(Paint.Style.FILL)
        paint.setColor(-0x26e5dfd8)
        dst.set(left, top, right, bottom)
        canvas.drawRoundRect(dst, 14f, 14f, paint)
        paint.setStyle(Paint.Style.STROKE)
        paint.setStrokeWidth(1.4f)
        paint.setColor(0x38ffffff)
        canvas.drawRoundRect(dst, 14f, 14f, paint)
        paint.setStyle(Paint.Style.FILL)

        val narrowTall: Boolean = narrowTallWidgetMode()
        val boxH: Float = bottom - top
        val iconCx: Float = left + boxH * (if (narrowTall) 0.47f else 0.48f)
        paint.setColor(-0xdbcec3)
        canvas.drawCircle(
            iconCx,
            (top + bottom) / 2f,
            boxH * (if (narrowTall) 0.28f else 0.30f),
            paint,
        )

        paint.setTypeface(Typeface.DEFAULT_BOLD)
        paint.setTextAlign(Paint.Align.CENTER)
        paint.setColor(-0xb0e16)
        val glyph: String? = maneuverGlyph()
        setFittingTextSize(
            glyph,
            boxH * 0.48f,
            if (narrowTall) clamp(boxH * 0.46f, 34f, 44f) else clamp(boxH * 0.46f, 44f, 72f),
            if (narrowTall) 25f else 30f,
        )
        drawTextCenterY(canvas, glyph, iconCx, (top + bottom) / 2f, paint)

        paint.setTextAlign(Paint.Align.LEFT)
        paint.setTextSize(
            if (narrowTall) clamp(boxH * 0.34f, 30f, 38f) else clamp(boxH * 0.25f, 30f, 46f)
        )
        paint.setColor(-0xb0e16)
        drawTextCenterY(
            canvas,
            text,
            left + boxH * (if (narrowTall) 0.88f else 0.92f),
            top + boxH * (if (narrowTall) 0.35f else 0.42f),
            paint,
        )
        paint.setTextSize(
            if (narrowTall) clamp(boxH * 0.22f, 20f, 26f) else clamp(boxH * 0.16f, 18f, 26f)
        )
        paint.setColor(-0x231c16)
        drawTextCenterY(
            canvas,
            maneuverLabel(),
            left + boxH * (if (narrowTall) 0.88f else 0.92f),
            top + boxH * (if (narrowTall) 0.74f else 0.68f),
            paint,
        )
    }

    private fun speedLimitKmh(): Int {
        return roadLimit!!.valueAt(SystemClock.elapsedRealtime())
    }

    private fun currentSpeedKmh(): Int {
        return parseKmh(if (navigation == null) "" else navigation.currentSpeed, true)
    }

    private fun parseKmh(raw: String, allowZero: Boolean): Int {
        val number: StringBuilder = StringBuilder()
        for (i in 0..<raw.length) {
            val ch: Char = raw.get(i)
            if (ch >= '0' && ch <= '9') {
                number.append(ch)
            } else if (number.length > 0) {
                break
            }
        }
        if (number.length == 0) return -1
        try {
            val value: Int = Integer.parseInt(number.toString())
            return if ((value > 0 || allowZero) && value <= 240) value else -1
        } catch (ignored: NumberFormatException) {
            return -1
        }
    }

    private fun hasRouteHint(): Boolean {
        if (navigation == null || !navigation.active || navigation.finishReached) return false
        return !navigationSnapshot().maneuverId!!.isEmpty()
    }

    private fun maneuverGlyph(): String? {
        return navigationSnapshot().presentation!!.glyph
    }

    private fun maneuverLabel(): String? {
        val snapshot: DashboardNavigationSnapshot = navigationSnapshot()
        val presentation: DashboardManeuverPresentation? = snapshot.presentation
        if (snapshot.clusterBacked) {
            val value: String? = presentation!!.fallbackLabel
            return if (value!!.length > 18) value.substring(0, 18) else value
        }
        var value: String? = clean(if (navigation == null) "" else navigation.maneuverText)
        if (
            presentation!!.roundaboutExit > 0 &&
                (value!!.isEmpty() || !value.matches(Regex(".*\\d.*")))
        ) {
            value = presentation.fallbackLabel
        } else if (value!!.isEmpty()) {
            value = presentation.fallbackLabel
        }
        if (value!!.length > 18) value = value.substring(0, 18)
        return value
    }

    private fun navigationSnapshot(): DashboardNavigationSnapshot {
        return DashboardNavigationSnapshot.resolve(navigation)
    }

    private fun drawTireCardBackground(
        canvas: Canvas,
        x: Float,
        y: Float,
        w: Float,
        h: Float,
        scale: Float,
        severity: Int,
    ) {
        val alert: Boolean = severity != TpmsAlertController.SEVERITY_NONE
        val critical: Boolean = severity == TpmsAlertController.SEVERITY_CRITICAL
        val accent: Int = severityColor(severity)
        paint.clearShadowLayer()
        paint.setShader(null)
        paint.setStyle(Paint.Style.FILL)
        paint.setColor(if (!alert) -0x11e6e2dd else (if (critical) -0xd0ede9 else -0xd5deed))
        dst.set(x, y, x + w, y + h)
        canvas.drawRoundRect(dst, 8f * scale, 8f * scale, paint)

        if (alert) {
            paint.setColor(accent)
            dst.set(x, y, x + 6f * scale, y + h)
            canvas.drawRoundRect(dst, 8f * scale, 8f * scale, paint)
        }

        paint.setStyle(Paint.Style.STROKE)
        paint.setStrokeWidth(1f * scale)
        paint.setColor(if (alert) accent else -0xcbc5bc)
        dst.set(x + 0.5f * scale, y + 0.5f * scale, x + w - 0.5f * scale, y + h - 0.5f * scale)
        canvas.drawRoundRect(dst, 8f * scale, 8f * scale, paint)
        paint.setStyle(Paint.Style.FILL)
    }

    private fun drawStatusChip(
        canvas: Canvas,
        known: Boolean,
        fresh: Boolean,
        warning: Int,
        severity: Int,
        cx: Float,
        cy: Float,
        scale: Float,
    ) {
        val text: String =
            if (!known) "ОЖИДАНИЕ" else (if (!fresh) "ДАННЫЕ УСТАРЕЛИ" else warningText(warning))
        val alert: Boolean = fresh && severity != TpmsAlertController.SEVERITY_NONE
        val critical: Boolean = severity == TpmsAlertController.SEVERITY_CRITICAL
        paint.setTypeface(Typeface.DEFAULT_BOLD)
        paint.setTextSize(12f * scale)
        paint.clearShadowLayer()
        val tw: Float = paint.measureText(text)
        val chipW: Float = Math.max(92f * scale, tw + 22f * scale)
        dst.set(cx - chipW / 2f, cy - 12f * scale, cx + chipW / 2f, cy + 12f * scale)
        paint.setStyle(Paint.Style.FILL)
        paint.setColor(
            if (alert) severityColor(severity)
            else (if (fresh) -0xd9cfc6 else (if (known) -0xcecbc6 else -0xddd8d1))
        )
        canvas.drawRoundRect(dst, 8f * scale, 8f * scale, paint)
        paint.setColor(if (alert && !critical) -0xebeef5 else -0xb0e16)
        paint.setTextAlign(Paint.Align.CENTER)
        drawTextCenterY(canvas, text, cx, cy, paint)
    }

    private fun drawCar(canvas: Canvas, ox: Float, oy: Float, scale: Float) {
        val carW: Float = 420f * scale
        val carH: Float = 660f * scale
        val carCx: Float = ox + 640f * scale
        val carCy: Float = oy + 360f * scale
        drawSportage(canvas, carCx, carCy, carW, carH)
        if (hasAlert()) {
            val warningSize: Float = 150f * scale
            drawFitAspect(
                canvas,
                R.drawable.tpms_car_warning_center,
                carCx,
                carCy - 16f * scale,
                warningSize,
                warningSize,
            )
        }
    }

    private fun drawRoad(canvas: Canvas, ox: Float, oy: Float, scale: Float) {
        val cx: Float = ox + 640f * scale
        val top: Float = oy - 84f * scale
        val bottom: Float = oy + 792f * scale
        val topW: Float = 332f * scale
        val bottomW: Float = 520f * scale

        path.reset()
        path.moveTo(cx - topW * 0.5f, top)
        path.lineTo(cx + topW * 0.5f, top)
        path.lineTo(cx + bottomW * 0.5f, bottom)
        path.lineTo(cx - bottomW * 0.5f, bottom)
        path.close()

        paint.clearShadowLayer()
        paint.setStyle(Paint.Style.FILL)
        paint.setShader(
            LinearGradient(
                cx,
                top,
                cx,
                bottom,
                intArrayOf(0x0027303a, 0x1a27303a, 0x44303942, 0x301b2027, 0x00161b21, 0x00161b21),
                floatArrayOf(0f, 0.20f, 0.48f, 0.76f, 0.90f, 1f),
                Shader.TileMode.CLAMP,
            )
        )
        canvas.drawPath(path, paint)
        paint.setShader(null)

        paint.setStyle(Paint.Style.STROKE)
        paint.setStrokeWidth(1.2f * scale)
        paint.setShader(
            LinearGradient(
                cx,
                top,
                cx,
                bottom,
                intArrayOf(0x00ffffff, 0x00ffffff, 0x42ffffff, 0x38ffffff, 0x00ffffff, 0x00ffffff),
                floatArrayOf(0f, 0.14f, 0.28f, 0.72f, 0.88f, 1f),
                Shader.TileMode.CLAMP,
            )
        )
        canvas.drawLine(cx - topW * 0.5f, top, cx - bottomW * 0.5f, bottom, paint)
        canvas.drawLine(cx + topW * 0.5f, top, cx + bottomW * 0.5f, bottom, paint)
        paint.setShader(null)

        canvas.save()
        canvas.clipPath(path)
        drawRoadDashes(canvas, cx, top, bottom, scale)
        drawRoadSweep(canvas, cx, top, bottom, topW, bottomW, scale)
        canvas.restore()
        paint.setStyle(Paint.Style.FILL)
    }

    private fun drawRoadDashes(canvas: Canvas, cx: Float, top: Float, bottom: Float, scale: Float) {
        val moving: Boolean = moving()
        val dashH: Float = 44f * scale
        val gap: Float = 48f * scale
        val period: Float = dashH + gap
        val speed: Float = Math.max(24f, Math.min(135f, motionSpeedKmh))
        val phase: Float =
            if (moving) ((SystemClock.uptimeMillis() * (0.16f + speed / 280f)) % period) else 0f
        paint.setStyle(Paint.Style.FILL)
        paint.setShader(
            LinearGradient(
                cx,
                top,
                cx,
                bottom,
                if (moving)
                    intArrayOf(
                        0x00f4f1ea,
                        0x00f4f1ea,
                        -0x550b0e16,
                        -0x730b0e16,
                        0x00f4f1ea,
                        0x00f4f1ea,
                    )
                else
                    intArrayOf(
                        0x00f4f1ea,
                        0x00f4f1ea,
                        0x48f4f1ea,
                        0x34f4f1ea,
                        0x00f4f1ea,
                        0x00f4f1ea,
                    ),
                floatArrayOf(0f, 0.16f, 0.28f, 0.70f, 0.86f, 1f),
                Shader.TileMode.CLAMP,
            )
        )
        var y: Float = top - period + phase
        while (y < bottom + period) {
            dst.set(cx - 3.2f * scale, y, cx + 3.2f * scale, y + dashH)
            canvas.drawRoundRect(dst, 3.2f * scale, 3.2f * scale, paint)
            y += period
        }
        paint.setShader(null)
    }

    private fun drawRoadSweep(
        canvas: Canvas,
        cx: Float,
        top: Float,
        bottom: Float,
        topW: Float,
        bottomW: Float,
        scale: Float,
    ) {
        if (!moving()) return
        val period: Float = 220f * scale
        val phase: Float = (SystemClock.uptimeMillis() * (0.10f + motionSpeedKmh / 420f)) % period
        paint.setStyle(Paint.Style.STROKE)
        paint.setStrokeWidth(2f * scale)
        paint.setShader(
            LinearGradient(
                cx,
                top,
                cx,
                bottom,
                intArrayOf(0x0039d3be, 0x0039d3be, 0x3039d3be, 0x2439d3be, 0x0039d3be, 0x0039d3be),
                floatArrayOf(0f, 0.16f, 0.30f, 0.68f, 0.84f, 1f),
                Shader.TileMode.CLAMP,
            )
        )
        var y: Float = top - period + phase
        while (y < bottom + period) {
            val t: Float = Math.max(0f, Math.min(1f, (y - top) / Math.max(1f, bottom - top)))
            val half: Float = (topW + (bottomW - topW) * t) * 0.46f
            canvas.drawLine(cx - half, y, cx + half, y, paint)
            y += period
        }
        paint.setShader(null)
    }

    private fun known(wheel: Int): Boolean {
        return state != null &&
            state.known != null &&
            wheel >= 0 &&
            wheel < state.known.size &&
            state.known.get(wheel)
    }

    private fun fresh(wheel: Int): Boolean {
        return state != null && state.isWheelFresh(wheel)
    }

    private fun warning(wheel: Int): Int {
        return TpmsAlertController.warningState(getContext(), state, wheel)
    }

    private fun severity(wheel: Int): Int {
        return TpmsAlertController.warningSeverity(getContext(), state, wheel)
    }

    private fun severityColor(severity: Int): Int {
        if (severity == TpmsAlertController.SEVERITY_CRITICAL) return -0xac9c
        if (severity == TpmsAlertController.SEVERITY_WARNING) return -0x3bc5
        return -0xc62c42
    }

    private fun pressureText(wheel: Int): String? {
        return String.format(Locale.US, "%.2f", state.pressureKpa.get(wheel) / 100f)
    }

    private fun tempText(wheel: Int): String? {
        return java.lang.String.valueOf(state.temperatureC.get(wheel))
    }

    private fun hasAlert(): Boolean {
        return TpmsAlertController.hasWarnings(getContext(), state)
    }

    private fun moving(): Boolean {
        return motionSpeedKmh > 0.5f
    }

    private fun tinyWidgetMode(): Boolean {
        return getHeight() < 560 && getWidth() < 900
    }

    private fun narrowTallWidgetMode(): Boolean {
        return getWidth() < 700 && getHeight() > 700
    }

    private fun firstKnownWheel(): Int {
        if (state == null || state.known == null) return -1
        for (wheel in 0..<TpmsState.WHEEL_COUNT) {
            if (state.known.get(wheel)) return wheel
        }
        return -1
    }

    private fun wheelTitle(wheel: Int): String? {
        when (wheel) {
            TpmsState.WHEEL_FL -> return "Л.П.(L.F.)"
            TpmsState.WHEEL_FR -> return "П.П.(R.F.)"
            TpmsState.WHEEL_RL -> return "Л.З.(L.R.)"
            TpmsState.WHEEL_RR -> return "П.З.(R.R.)"
            else -> return "TPMS"
        }
    }

    private fun clamp(value: Float, min: Float, max: Float): Float {
        return Math.max(min, Math.min(max, value))
    }

    private fun lerp(start: Float, end: Float, progress: Float): Float {
        return start + (end - start) * clamp(progress, 0f, 1f)
    }

    private fun clean(value: String?): String? {
        return if (value == null) "" else value.trim()
    }

    private fun warningText(warning: Int): String {
        when (warning) {
            TpmsAlertController.WARNING_LOW_PRESSURE -> return "НИЗКОЕ ДАВЛЕНИЕ"
            TpmsAlertController.WARNING_HIGH_PRESSURE -> return "ВЫСОКОЕ ДАВЛЕНИЕ"
            TpmsAlertController.WARNING_LOW_TEMP -> return "НИЗКАЯ ТЕМП."
            TpmsAlertController.WARNING_HIGH_TEMP -> return "ВЫСОКАЯ ТЕМП."
            TpmsAlertController.WARNING_FAST_LEAKAGE -> return "БЫСТРАЯ УТЕЧКА"
            TpmsAlertController.WARNING_LOW_BATTERY -> return "НИЗКИЙ ЗАРЯД"
            else -> return "НОРМА"
        }
    }

    private fun setFittingTextSize(
        text: String?,
        maxWidth: Float,
        preferredSize: Float,
        minSize: Float,
    ) {
        paint.setTextSize(preferredSize)
        val measured: Float = paint.measureText(if (text == null) "" else text)
        if (measured <= maxWidth || measured <= 0f) return
        paint.setTextSize(Math.max(minSize, preferredSize * maxWidth / measured))
    }

    private fun drawTextCenterY(canvas: Canvas, text: String?, x: Float, cy: Float, paint: Paint) {
        val fm: Paint.FontMetrics? = paint.getFontMetrics()
        canvas.drawText(
            if (text == null) "" else text,
            x,
            cy - (fm!!.ascent + fm.descent) / 2f,
            paint,
        )
    }

    private fun drawTextBottom(
        canvas: Canvas,
        text: String?,
        x: Float,
        bottom: Float,
        paint: Paint,
    ) {
        val fm: Paint.FontMetrics? = paint.getFontMetrics()
        canvas.drawText(if (text == null) "" else text, x, bottom - fm!!.descent, paint)
    }

    private fun drawFit(canvas: Canvas, resId: Int, x: Float, y: Float, w: Float, h: Float) {
        val bitmap: Bitmap? = bitmap(resId)
        if (bitmap == null) return
        paint.setShader(null)
        paint.setAlpha(255)
        paint.setColorFilter(null)
        paint.clearShadowLayer()
        dst.set(x, y, x + w, y + h)
        canvas.drawBitmap(bitmap, null, dst, paint)
    }

    private fun drawFitAspect(
        canvas: Canvas,
        resId: Int,
        cx: Float,
        cy: Float,
        maxW: Float,
        maxH: Float,
    ) {
        val bitmap: Bitmap? = bitmap(resId)
        if (bitmap == null) return
        val scale: Float = Math.min(maxW / bitmap.getWidth(), maxH / bitmap.getHeight())
        val w: Float = bitmap.getWidth() * scale
        val h: Float = bitmap.getHeight() * scale
        drawFit(canvas, resId, cx - w / 2f, cy - h / 2f, w, h)
    }

    private fun setTextShadow(scale: Float) {
        paint.setShadowLayer(3f * scale, 2f * scale, 2f * scale, -0x1000000)
    }

    private fun bitmap(resId: Int): Bitmap? {
        val cached: Bitmap? = cache.get(resId)
        if (cached != null) return cached
        val decoded: Bitmap? = BitmapFactory.decodeResource(getResources(), resId)
        if (decoded != null) cache.put(resId, decoded)
        return decoded
    }
}
