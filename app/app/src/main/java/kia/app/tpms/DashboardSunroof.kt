package kia.app.tpms

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.graphics.RectF
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
            start = position(now)
            end = next
            startedAt = now
            duration = (1540L * abs(end - start)).roundToLong()
        }
        known = true
    }

    fun moving(now: Long) = duration > 0 && now - startedAt < duration

    fun position(now: Long): Float {
        if (!moving(now)) return end
        val t = ((now - startedAt).toFloat() / duration).coerceIn(0f, 1f)
        return start + (end - start) * t * t * (3f - 2f * t)
    }
}

/** Body and glass share the original render's 1024 × 1536 coordinate system. */
internal class DashboardSunroof(private val body: Bitmap) {
    private val bodyBounds = RectF(0f, 0f, 1024f, 1536f)
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val glassTexture = Rect(body.width * 303 / 1024, body.height * 669 / 1536,
        body.width * 722 / 1024, body.height * 878 / 1536)
    private val panel = RectF()
    private val shape = Path()
    private val carRoof = Path().apply {
        addRoundRect(294f, 381f, 732f, 888f, 34f, 33f, Path.Direction.CW)
    }

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
        if (open < 1f) {
            // The fixed rear pane in the render already shows the glass fully retracted.
            val top = 391f + 265f * open
            panel.set(310f - 9f * open, top, 716f + 9f * open, top + 267f - 31f * open)
            shape.rewind()
            shape.addRoundRect(panel, 29f, 30f, Path.Direction.CW)
            canvas.clipPath(carRoof)
            canvas.clipPath(shape)
            paint.alpha = (255f * ((1f - open) / .14f).coerceIn(0f, 1f)).toInt()
            canvas.drawBitmap(body, glassTexture, panel, paint)
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = 4f
            paint.color = android.graphics.Color.argb((paint.alpha * .42f).toInt(), 153, 164, 171)
            canvas.drawPath(shape, paint)
        }
        canvas.restoreToCount(saved)
    }
}
