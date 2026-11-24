package kia.app.tpms

import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.ColorMatrixColorFilter
import android.graphics.Matrix
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
internal class DashboardSunroof(private val body: Bitmap, private val seats: Bitmap?) {
    private val bodyBounds = RectF(0f, 0f, 1024f, 1536f)
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    // Coordinates are in the original rendering. Shorten just the opening and
    // extend the rear glass into the space it frees. The factory seal stays in the image.
    private val frontTop = 384f
    private val originalDivider = 666f
    private val openingHeight = .70f
    private val divider = frontTop + (originalDivider - frontTop) * openingHeight
    private val front = RectF(295f, frontTop, 732f, divider)
    private val rear = RectF(front.left, divider, front.right, 888f)
    private val frontSource = sourceRect(front.left, frontTop, front.right, originalDivider)
    private val seatSource = seats?.let {
        Rect((it.width * .337f).roundToInt(), (it.height * .59f).roundToInt(),
            (it.width * .674f).roundToInt(), (it.height * .793f).roundToInt())
    }
    private val panorama = RectF(288f, 355f, 737f, 889f)
    private val panoramaShape = Path().apply {
        moveTo(288f, 390f)
        cubicTo(313f, 346f, 707f, 346f, 737f, 390f)
        lineTo(733f, 889f)
        quadTo(512f, 875f, 292f, 889f)
        close()
    }
    private val paneShape = Path().apply {
        // Inner edge of the rubber seal in the user's 480 × 530 roof reference.
        moveTo(319f, 441f)
        cubicTo(319f, 414f, 326f, 404f, 350f, 402f)
        cubicTo(424f, 397f, 604f, 397f, 665f, 402f)
        cubicTo(691f, 403f, 700f, 412f, 701f, 438f)
        lineTo(703f, 630f)
        quadTo(703f, 649f, 687f, 651f)
        lineTo(337f, 651f)
        quadTo(321f, 651f, 321f, 633f)
        close()
        transform(Matrix().apply { setScale(1f, openingHeight, 0f, frontTop) })
    }
    private val pane = RectF().also { paneShape.computeBounds(it, true) }
    private val travel = divider + 4f - pane.top
    private val movingPane = Path()
    private val glassOutsideOpening = Path(panoramaShape).apply {
        op(paneShape, Path.Op.DIFFERENCE)
    }
    private val seal = Path().also {
        // Use only the narrow photographed gasket around the aperture.
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 11f
        }.getFillPath(paneShape, it)
        it.op(paneShape, Path.Op.DIFFERENCE)
    }
    private val leadingEdge = Path().apply {
        moveTo(319f, 441f)
        cubicTo(319f, 414f, 326f, 404f, 350f, 402f)
        cubicTo(424f, 397f, 604f, 397f, 665f, 402f)
        cubicTo(691f, 403f, 700f, 412f, 701f, 438f)
        transform(Matrix().apply { setScale(1f, openingHeight, 0f, frontTop) })
    }
    private val cabinBounds = RectF(bodyBounds).apply {
        // Crop the view through the shortened opening; keep the wheel and seats to scale.
        offset(0f, pane.top - 409f)
    }
    private val reflectionSource = RectF(sourceRect(301f, 670f, 725f, 879f))
    private val reflectionMatrix = Matrix().apply {
        setRectToRect(reflectionSource, panorama, Matrix.ScaleToFit.FILL)
    }
    private fun reflection() = BitmapShader(body, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP)
        .apply { setLocalMatrix(reflectionMatrix) }

    private val fixedGlass = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG).apply {
        shader = reflection()
        alpha = 231
    }
    private val movingReflection = reflection()
    private val movingMatrix = Matrix()
    private val panePaint = Paint(fixedGlass).apply { shader = movingReflection }
    private val seatPaint = Paint(paint).apply {
        // The rear seat photograph already contains a layer of tint.
        colorFilter = ColorMatrixColorFilter(floatArrayOf(
            1.8f, 0f, 0f, 0f, 0f,  0f, 1.8f, 0f, 0f, 0f,
            0f, 0f, 1.8f, 0f, 0f,  0f, 0f, 0f, 1f, 0f))
    }
    private val edgePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1.4f
        color = 0xffb8c6cd.toInt()
    }

    private fun sourceRect(left: Float, top: Float, right: Float, bottom: Float) = Rect(
        (left * body.width / 1024f).roundToInt(),
        (top * body.height / 1536f).roundToInt(),
        (right * body.width / 1024f).roundToInt(),
        (bottom * body.height / 1536f).roundToInt())

    fun draw(canvas: Canvas, cx: Float, cy: Float, maxWidth: Float, maxHeight: Float,
             open: Float) {
        val scale = min(maxWidth / bodyBounds.width(), maxHeight / bodyBounds.height())
        if (scale <= 0f) return
        val saved = canvas.save()
        canvas.translate(cx - 512f * scale, cy - 768f * scale)
        canvas.scale(scale, scale)
        paint.color = -1
        canvas.drawBitmap(body, null, bodyBounds, paint)
        canvas.clipPath(panoramaShape)
        canvas.drawBitmap(body, frontSource, front, paint)
        if (seats != null && seatSource != null) canvas.drawBitmap(seats, seatSource, rear, seatPaint)

        val interior = canvas.save()
        canvas.clipPath(paneShape)
        canvas.drawBitmap(body, null, cabinBounds, paint)
        canvas.restoreToCount(interior)

        val position = open.coerceIn(0f, 1f)
        if (position == 0f) {
            canvas.drawPath(panoramaShape, fixedGlass)
        } else {
            val offset = travel * position
            paneShape.offset(0f, offset, movingPane)
            canvas.drawPath(glassOutsideOpening, fixedGlass)

            val exposed = canvas.save()
            canvas.clipOutPath(movingPane)
            paint.color = SunroofGlass.apertureHighlight(position, 1f)
            canvas.drawPath(paneShape, paint)
            canvas.clipPath(seal)
            paint.color = -1
            paint.alpha = (170f * position).roundToInt()
            canvas.drawBitmap(body, frontSource, front, paint)
            canvas.restoreToCount(exposed)

            // The two parallel sheets reflect the same sky. The small parallax is
            // relative to the roof, so the highlight stays continuous across the overlap.
            movingMatrix.set(reflectionMatrix)
            movingMatrix.postTranslate(-5f * position, 8f * position)
            movingReflection.setLocalMatrix(movingMatrix)
            canvas.drawPath(movingPane, panePaint)
            val edge = canvas.save()
            canvas.translate(0f, offset)
            edgePaint.alpha = (55f * position).roundToInt()
            canvas.drawPath(leadingEdge, edgePaint)
            canvas.restoreToCount(edge)
        }
        canvas.restoreToCount(saved)
    }
}
