package kia.app.tpms

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.graphics.RectF
import kia.app.sunroof.SunroofGlass
import kotlin.math.cos
import kotlin.math.abs
import kotlin.math.min
import kotlin.math.roundToInt
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
    private val travel = 241f
    private val paneShape = Path().apply {
        // The two shallow bows follow the cross-members, level across the centreline.
        moveTo(339f, 447f)
        quadTo(339f, 429f, 358f, 427f)
        quadTo(513f, 423f, 668f, 427f)
        quadTo(687f, 429f, 687f, 447f)
        lineTo(687f, 617f)
        quadTo(687f, 632f, 671f, 632f)
        quadTo(513f, 634f, 355f, 632f)
        quadTo(339f, 632f, 339f, 617f)
        close()
    }
    // In the source render the cabin opening is larger than the moving glass. Cover
    // the original trim with one continuous reflection up to the edges of the roof.
    private val panorama = RectF(288f, 355f, 737f, 889f)
    private val fixedRoof = Path().apply {
        fillType = Path.FillType.EVEN_ODD
        moveTo(288f, 390f)
        cubicTo(313f, 346f, 707f, 346f, 737f, 390f)
        lineTo(733f, 889f)
        quadTo(512f, 875f, 292f, 889f)
        close()
        addPath(paneShape)
    }
    private val reflection = Rect(
        (301f * body.width / 1024f).roundToInt(),
        (670f * body.height / 1536f).roundToInt(),
        (725f * body.width / 1024f).roundToInt(),
        (879f * body.height / 1536f).roundToInt())

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
        val fixed = canvas.save()
        canvas.clipPath(fixedRoof)
        canvas.drawBitmap(body, reflection, panorama, paint)
        canvas.restoreToCount(fixed)

        val position = open.coerceIn(0f, 1f)
        paint.color = SunroofGlass.apertureHighlight(position, 1f)
        canvas.drawPath(paneShape, paint)
        canvas.translate(0f, travel * position)
        canvas.clipPath(paneShape)
        // Both surfaces use the same reflection when closed. The glass transmits a
        // little cabin light and carries its own portion of the reflection rearward.
        paint.color = 0xf0ffffff.toInt()
        canvas.drawBitmap(body, reflection, panorama, paint)
        canvas.restoreToCount(saved)
    }
}
