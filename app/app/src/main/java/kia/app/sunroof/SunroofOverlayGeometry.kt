package kia.app.sunroof

/** Pixel geometry shared by the compact window and full-screen warning canvas. */
object SunroofOverlayGeometry {
    const val SCREEN_WIDTH_FRACTION: Float = .1932f
    const val HEIGHT_PER_WIDTH: Float = 1.5f

    @JvmStatic
    fun carWidthPx(screenWidthPx: Int): Float {
        return Math.max(0, screenWidthPx) * SunroofOverlayGeometry.SCREEN_WIDTH_FRACTION
    }

    @JvmStatic
    fun compactWidthPx(carWidthPx: Float): Int {
        return Math.max(1, Math.ceil(carWidthPx.toDouble()).toInt())
    }

    @JvmStatic
    fun compactHeightPx(carWidthPx: Float): Int {
        return Math.max(
            1,
            Math.ceil((carWidthPx * SunroofOverlayGeometry.HEIGHT_PER_WIDTH).toDouble()).toInt(),
        )
    }
}
