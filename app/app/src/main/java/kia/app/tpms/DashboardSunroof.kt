package kia.app.tpms

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import kia.app.sunroof.SunroofGlass
import kotlin.math.cos
import kotlin.math.abs
import kotlin.math.min
import kotlin.math.roundToLong

/** Travels from the currently displayed position, including a reversal halfway through. */
internal class RoofSlide(initialOpen: Boolean?) {
    private var known = initialOpen != null
    private var start = if (initialOpen == true) 1f else 0f
    private var end = start
    private var startedAt = 0L
    private var duration = 0L

    fun update(open: Boolean?, animate: Boolean, now: Long) {
        if (open == null) {
            // A USB disconnect says nothing about the physical position.
            known = false
            duration = 0
            return
        }
        val next = if (open) 1f else 0f
        if (!known || !animate) {
            start = next
            end = next
            duration = 0
        } else if (next != end) {
            val reversing = moving(now)
            start = position(now)
            end = next
            startedAt = now + if (reversing) 0L else SunroofGlass.START_DELAY_MS
            duration = (SunroofGlass.TRAVEL_MS * abs(end - start)).roundToLong()
        }
        known = true
    }

    fun moving(now: Long) = duration > 0 && now - startedAt < duration

    fun position(now: Long): Float {
        if (!moving(now)) return end
        val t = ((now - startedAt).toFloat() / duration).coerceIn(0f, 1f)
        val eased = ((1.0 - cos(Math.PI * t)) * .5).toFloat()
        return start + (end - start) * eased
    }
}

/** Body and glass share the original render's 1024 × 1536 coordinate system. */
internal class DashboardSunroof(private val body: Bitmap) {
    private val bodyBounds = RectF(0f, 0f, 1024f, 1536f)
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val opening = RectF(311f, 393f, 716f, 660f)
    private val retracted = RectF(297f, 662f, 729f, 887f)
    private val glass = SunroofGlass()

    fun draw(canvas: Canvas, cx: Float, cy: Float, maxWidth: Float, maxHeight: Float,
             open: Float) {
        val scale = min(maxWidth / bodyBounds.width(), maxHeight / bodyBounds.height())
        if (scale <= 0f) return
        val saved = canvas.save()
        canvas.translate(cx - 512f * scale, cy - 768f * scale)
        canvas.scale(scale, scale)
        paint.color = -1
        paint.style = Paint.Style.FILL
        canvas.drawBitmap(body, null, bodyBounds, paint)
        glass.draw(canvas, opening, retracted, open, 1f, 1024f / 230f)
        canvas.restoreToCount(saved)
    }
}
