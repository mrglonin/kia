package kia.app.tpms

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
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
    private val pane = RectF(352f, 425f, 674f, 634f)
    private val travel = 241f
    private val paneShape = Path().apply {
        // The two shallow bows follow the cross-members, level across the centreline.
        moveTo(352f, 447f)
        quadTo(352f, 429f, 371f, 427f)
        quadTo(513f, 423f, 655f, 427f)
        quadTo(674f, 429f, 674f, 447f)
        lineTo(674f, 617f)
        quadTo(674f, 632f, 658f, 632f)
        quadTo(513f, 634f, 368f, 632f)
        quadTo(352f, 632f, 352f, 617f)
        close()
    }
    private val surround = Path().apply {
        fillType = Path.FillType.EVEN_ODD
        moveTo(315f, 440f)
        quadTo(315f, 398f, 357f, 396f)
        quadTo(512f, 392f, 670f, 396f)
        quadTo(710f, 398f, 710f, 440f)
        lineTo(710f, 628f)
        quadTo(710f, 655f, 683f, 655f)
        lineTo(342f, 655f)
        quadTo(315f, 655f, 315f, 628f)
        close()
        addPath(paneShape)
    }
    private val surroundFinish = LinearGradient(315f, 415f, 708f, 605f,
        intArrayOf(0xff2b3033.toInt(), 0xff1b1f22.toInt(), 0xff21272b.toInt(),
            0xff31373b.toInt(), 0xff202528.toInt()),
        floatArrayOf(0f, .28f, .55f, .78f, 1f), Shader.TileMode.CLAMP)

    // Sample the same section of rear glass that the movable pane parks above.
    // Its original reflection remains continuous across the sheet and at the parked edges.
    private val glassSource = Rect(
        (pane.left * body.width / 1024f).roundToInt(),
        ((pane.top + travel) * body.height / 1536f).roundToInt(),
        (pane.right * body.width / 1024f).roundToInt(),
        ((pane.bottom + travel) * body.height / 1536f).roundToInt())

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
        paint.shader = surroundFinish
        canvas.drawPath(surround, paint)
        paint.shader = null
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 5f
        paint.color = 0xff0c1012.toInt()
        canvas.drawPath(paneShape, paint)
        paint.style = Paint.Style.FILL

        val position = open.coerceIn(0f, 1f)
        paint.color = SunroofGlass.apertureHighlight(position, 1f)
        canvas.drawPath(paneShape, paint)
        canvas.translate(0f, travel * position)

        val clipped = canvas.save()
        canvas.clipPath(paneShape)
        paint.color = 0xf0ffffff.toInt()
        canvas.drawBitmap(body, glassSource, pane, paint)
        paint.color = 0x24101b23
        canvas.drawPath(paneShape, paint)
        canvas.restoreToCount(clipped)

        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 2.6f
        paint.color = 0xb4181d20.toInt()
        canvas.drawPath(paneShape, paint)
        paint.strokeWidth = 1.2f
        paint.color = 0x807b878c.toInt()
        canvas.drawPath(paneShape, paint)
        canvas.restoreToCount(saved)
    }
}
