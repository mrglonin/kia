package kia.app.sunroof;

/** Pixel geometry shared by the compact window and full-screen warning canvas. */
public final class SunroofOverlayGeometry {
    public static final float SCREEN_WIDTH_FRACTION = .1932f;
    public static final float HEIGHT_PER_WIDTH = 1.5f;

    private SunroofOverlayGeometry() {
    }

    public static float carWidthPx(int screenWidthPx) {
        return Math.max(0, screenWidthPx) * SCREEN_WIDTH_FRACTION;
    }

    public static int compactWidthPx(float carWidthPx) {
        return Math.max(1, (int) Math.ceil(carWidthPx));
    }

    public static int compactHeightPx(float carWidthPx) {
        return Math.max(1, (int) Math.ceil(carWidthPx * HEIGHT_PER_WIDTH));
    }
}
