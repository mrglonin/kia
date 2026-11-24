package kia.app.sunroof

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import kotlin.math.roundToInt

/** Both car views use the same tinted glass, moving edge and illuminated aperture. */
internal class SunroofGlass {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val panel = RectF()
    private val matrix = Matrix()
    private val gradient = LinearGradient(0f, 0f, 1f, 1f,
        intArrayOf(0xff273139.toInt(), 0xff0f171d.toInt(), 0xff202c35.toInt()),
        floatArrayOf(0f, .68f, 1f), Shader.TileMode.CLAMP)

    fun draw(canvas: Canvas, opening: RectF, retracted: RectF, open: Float, pulse: Float,
             strokeWidth: Float) {
        val p = open.coerceIn(0f, 1f)
        val corners = opening.width() * .10f
        paint.style = Paint.Style.FILL
        paint.shader = null
        paint.color = apertureHighlight(p, pulse)
        canvas.drawRoundRect(opening, corners, corners, paint)

        panel.set(opening.left + (retracted.left - opening.left) * p,
            opening.top + (retracted.top - opening.top) * p,
            opening.right + (retracted.right - opening.right) * p,
            opening.bottom + (retracted.bottom - opening.bottom) * p)
        matrix.setScale(panel.width(), panel.height())
        matrix.postTranslate(panel.left, panel.top)
        gradient.setLocalMatrix(matrix)
        paint.color = Color.WHITE
        paint.shader = gradient
        canvas.drawRoundRect(panel, corners, corners, paint)
        paint.shader = null
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = strokeWidth
        paint.color = 0x8096a3aa.toInt()
        canvas.drawRoundRect(panel, corners, corners, paint)
    }

    companion object {
        fun apertureHighlight(open: Float, pulse: Float): Int =
            Color.argb((255f * .24f * open * pulse).roundToInt(), 255, 35, 55)

        const val START_DELAY_MS = 150L
        const val TRAVEL_MS = 900L
    }
}
