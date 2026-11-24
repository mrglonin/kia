package kia.app.sunroof

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.animation.AccelerateDecelerateInterpolator
import kia.app.R
import kotlin.math.max

/** The miniature uses screen pixels to keep its proportions on different head units. */
class SunroofOverlayView(context: Context) : View(context) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val carBounds = RectF()
    private val roofBounds = RectF()
    private val glassBounds = RectF()
    private val glass = SunroofGlass()
    private val car =
        BitmapFactory.decodeResource(
            resources,
            R.drawable.sunroof_car_top,
            BitmapFactory.Options().apply { inScaled = false },
        )
    private var movement: ValueAnimator? = null
    private var appearance: ValueAnimator? = null
    private var pulse: ValueAnimator? = null
    private var roofOpen = 0f
    private var warningPulse = 1f
    private var compactCarWidthPx = 0f
    private var warning = false
    private var dismissAction: Runnable? = null

    init {
        setWillNotDraw(false)
        setBackgroundColor(Color.TRANSPARENT)
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
    }

    fun present(kind: SunroofAlertPolicy.Kind, dismiss: Runnable?) {
        stopAnimations()
        dismissAction = dismiss
        warning = kind == SunroofAlertPolicy.Kind.PARKING_WARNING
        importantForAccessibility =
            if (warning) IMPORTANT_FOR_ACCESSIBILITY_YES else IMPORTANT_FOR_ACCESSIBILITY_NO
        contentDescription =
            when {
                warning ->
                    "Люк открыт при выключенном зажигании. Нажмите, чтобы закрыть предупреждение"
                kind == SunroofAlertPolicy.Kind.OPENING -> "Люк открывается"
                else -> "Люк закрывается"
            }
        isFocusable = warning
        isFocusableInTouchMode = warning
        isClickable = true
        alpha = 1f
        warningPulse = 1f
        roofOpen = if (kind == SunroofAlertPolicy.Kind.CLOSING || warning) 1f else 0f
        if (warning) {
            if (ValueAnimator.areAnimatorsEnabled()) {
                pulse =
                    ValueAnimator.ofFloat(.72f, 1f).apply {
                        duration = 1200L
                        repeatCount = ValueAnimator.INFINITE
                        repeatMode = ValueAnimator.REVERSE
                        addUpdateListener {
                            warningPulse = it.animatedValue as Float
                            invalidate()
                        }
                        start()
                    }
            }
            requestFocus()
        } else {
            val target = if (kind == SunroofAlertPolicy.Kind.OPENING) 1f else 0f
            if (ValueAnimator.areAnimatorsEnabled()) {
                movement =
                    ValueAnimator.ofFloat(roofOpen, target).apply {
                        startDelay = SunroofGlass.START_DELAY_MS
                        duration = SunroofGlass.TRAVEL_MS
                        interpolator = AccelerateDecelerateInterpolator()
                        addUpdateListener {
                            roofOpen = it.animatedValue as Float
                            invalidate()
                        }
                        start()
                    }
                appearance =
                    ValueAnimator.ofFloat(1f, 0f).apply {
                        startDelay = TRANSIENT_HOLD_MS
                        duration = FADE_MS
                        addUpdateListener { alpha = it.animatedValue as Float }
                        start()
                    }
            } else roofOpen = target
        }
        invalidate()
    }

    fun setCompactCarWidthPx(widthPx: Float) {
        compactCarWidthPx = max(0f, widthPx)
        invalidate()
    }

    fun stopAnimations() {
        movement?.cancel()
        appearance?.cancel()
        pulse?.cancel()
        movement = null
        appearance = null
        pulse = null
        animate().cancel()
    }

    override fun onDetachedFromWindow() {
        stopAnimations()
        dismissAction = null
        super.onDetachedFromWindow()
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (event.actionMasked == MotionEvent.ACTION_DOWN) performClick()
        return true
    }

    override fun performClick(): Boolean {
        super.performClick()
        dismissAction?.run()
        return true
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (warning && event.action == KeyEvent.ACTION_DOWN) dismissAction?.run()
        return super.dispatchKeyEvent(event)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val bitmap = car ?: return
        if (width <= 0 || height <= 0) return
        val carWidth =
            if (compactCarWidthPx > 0f) compactCarWidthPx
            else SunroofOverlayGeometry.carWidthPx(width)
        val carHeight = carWidth * SunroofOverlayGeometry.HEIGHT_PER_WIDTH
        val left = (width - carWidth) * .5f
        val top = (height - carHeight) * .5f
        carBounds.set(left, top, left + carWidth, top + carHeight)
        canvas.drawBitmap(bitmap, null, carBounds, paint)
        roofBounds.set(
            left + carWidth * .353f,
            top + carHeight * .456f,
            left + carWidth * .653f,
            top + carHeight * .574f,
        )
        glassBounds.set(roofBounds)
        glassBounds.offset(0f, roofBounds.height() * 1.10f)
        glass.draw(
            canvas,
            roofBounds,
            glassBounds,
            roofOpen,
            warningPulse,
            max(.6f, carWidth / 230f),
        )
    }

    companion object {
        const val SCREEN_WIDTH_FRACTION = SunroofOverlayGeometry.SCREEN_WIDTH_FRACTION
        const val TRANSIENT_HOLD_MS = 4000L
        const val FADE_MS = 400L
        const val TRANSIENT_DURATION_MS = TRANSIENT_HOLD_MS + FADE_MS
    }
}
