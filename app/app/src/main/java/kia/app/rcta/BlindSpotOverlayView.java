package kia.app.rcta;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RadialGradient;
import android.graphics.Shader;
import android.os.SystemClock;
import android.util.AttributeSet;
import android.view.View;

public final class BlindSpotOverlayView extends View {
    private static final int COLOR_AMBER = 0xffffc43b;
    private static final int COLOR_RED = 0xffff5364;
    private static final int GLOW_WIDTH_DP = 400;
    private static final int GLOW_HEIGHT_DP = 300;
    private static final int GLOW_ALPHA = 75;
    private static final int RAIL_MARGIN_X_DP = 32;
    private static final int RAIL_BOTTOM_DP = 24;
    private static final int RAIL_STROKE_DP = 7;
    private static final int RAIL_VERTICAL_HEIGHT_DP = 100;
    private static final int RAIL_HORIZONTAL_WIDTH_DP = 150;
    private static final int CHEVRON_SIZE_DP = 54;
    private static final int CHEVRON_BOTTOM_DP = 60;
    private static final int CHEVRON_BOTTOM_TYPE_2_DP = 44;
    private static final int CHEVRON_STROKE_DP = 7;
    private static final int CHEVRON_TRAVEL_DP = 10;
    private static final int CHEVRON_MARGIN_START_DP = 72;
    private static final int CHEVRON_MARGIN_TYPE_2_START_DP = 34;
    private static final int CHEVRON_MARGIN_STEP_DP = 54;
    private static final int CHEVRON_COUNT_MIN = 3;
    private static final int CHEVRON_COUNT_MAX = 6;

    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path path = new Path();
    private boolean previewLeft = true;
    private boolean previewRight = true;
    private boolean previewUnknown;
    private int bottomLiftPx;
    private int styleType = 1;
    private int alertColor = COLOR_AMBER;
    private int backgroundAlpha = GLOW_ALPHA;
    private int arrowCount = CHEVRON_COUNT_MIN;

    public BlindSpotOverlayView(Context context) {
        this(context, null);
    }

    public BlindSpotOverlayView(Context context, AttributeSet attrs) {
        this(context, attrs, 0);
    }

    public BlindSpotOverlayView(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        setWillNotDraw(false);
        setClickable(false);
    }

    public void setPreview(boolean left, boolean right, boolean unknown) {
        previewLeft = left;
        previewRight = right;
        previewUnknown = unknown;
        invalidate();
    }

    public void setBottomLiftDp(int value) {
        bottomLiftPx = Math.max(0, dp(value));
        invalidate();
    }

    public void setStyleType(int value) {
        styleType = value == 2 ? 2 : 1;
        invalidate();
    }

    public void setAlertColor(int value) {
        alertColor = forceOpaque(value);
        invalidate();
    }

    public void setBackgroundAlpha(int value) {
        backgroundAlpha = clamp(value, 0, 255);
        invalidate();
    }

    public void setArrowCount(int value) {
        arrowCount = clamp(value, CHEVRON_COUNT_MIN, CHEVRON_COUNT_MAX);
        invalidate();
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        postInvalidateOnAnimation();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        if (!previewLeft && !previewRight && !previewUnknown) return;
        int w = getWidth();
        int h = Math.max(1, getHeight() - bottomLiftPx);
        if (w <= 0) return;

        long now = SystemClock.uptimeMillis();
        float phase = isInEditMode() ? 0.5f : (now % 900L) / 900f;
        float pulse = 0.52f + 0.48f * (1f - Math.abs(phase - 0.5f) * 2f);
        boolean drawLeft = previewLeft || previewUnknown;
        boolean drawRight = previewRight || previewUnknown;
        if (drawLeft) drawSide(canvas, w, h, true, phase, pulse);
        if (drawRight) drawSide(canvas, w, h, false, phase, pulse);
        postInvalidateOnAnimation();
    }

    private void drawSide(Canvas canvas, int w, int h, boolean leftSide, float phase, float pulse) {
        int baseColor = previewUnknown ? COLOR_RED : alertColor;
        drawCornerGlow(canvas, w, h, leftSide, baseColor, pulse);
        if (styleType == 1) {
            drawCornerRail(canvas, w, h, leftSide, baseColor);
        }
        drawCornerChevronStack(canvas, w, h, leftSide, baseColor, phase);
    }

    private void drawCornerGlow(Canvas canvas, int w, int h, boolean leftSide, int baseColor, float pulse) {
        float glowW = dp(GLOW_WIDTH_DP);
        float glowH = dp(GLOW_HEIGHT_DP);
        float centerX = leftSide ? 0f : w;
        float centerY = h;
        float radius = Math.max(glowW, glowH);
        int pulsedAlpha = Math.round(backgroundAlpha * clampFloat(pulse, 0.45f, 1f));

        canvas.save();
        if (leftSide) {
            canvas.clipRect(0f, h - glowH, glowW, h);
        } else {
            canvas.clipRect(w - glowW, h - glowH, w, h);
        }
        paint.setStyle(Paint.Style.FILL);
        paint.setShader(new RadialGradient(
                centerX,
                centerY,
                radius,
                new int[]{
                        withAlpha(baseColor, pulsedAlpha),
                        withAlpha(baseColor, Math.round(pulsedAlpha * 0.42f)),
                        withAlpha(baseColor, 0)
                },
                new float[]{0f, 0.48f, 1f},
                Shader.TileMode.CLAMP));
        canvas.drawCircle(centerX, centerY, radius, paint);
        paint.setShader(null);
        canvas.restore();
    }

    private void drawCornerRail(Canvas canvas, int w, int h, boolean leftSide, int baseColor) {
        float cornerX = leftSide ? dp(RAIL_MARGIN_X_DP) : w - dp(RAIL_MARGIN_X_DP);
        float bottomY = h - dp(RAIL_BOTTOM_DP);
        float direction = leftSide ? 1f : -1f;
        float railW = dp(RAIL_HORIZONTAL_WIDTH_DP);
        float railH = dp(RAIL_VERTICAL_HEIGHT_DP);

        path.reset();
        path.moveTo(cornerX, bottomY - railH);
        path.lineTo(cornerX, bottomY);
        path.lineTo(cornerX + direction * railW, bottomY);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeCap(Paint.Cap.ROUND);
        paint.setStrokeJoin(Paint.Join.ROUND);
        paint.setStrokeWidth(dp(RAIL_STROKE_DP));
        paint.setShader(null);
        paint.setColor(forceOpaque(baseColor));
        canvas.drawPath(path, paint);
    }

    private void drawCornerChevronStack(Canvas canvas, int w, int h, boolean leftSide, int baseColor,
                                        float phase) {
        float size = dp(CHEVRON_SIZE_DP);
        int bottomDp = styleType == 2 ? CHEVRON_BOTTOM_TYPE_2_DP : CHEVRON_BOTTOM_DP;
        int marginStart = styleType == 2 ? CHEVRON_MARGIN_TYPE_2_START_DP : CHEVRON_MARGIN_START_DP;
        float top = h - dp(bottomDp) - size;
        float phaseStep = arrowCount <= 3 ? 0.22f : 0.72f / arrowCount;

        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeCap(Paint.Cap.ROUND);
        paint.setStrokeJoin(Paint.Join.ROUND);
        paint.setStrokeWidth(dp(CHEVRON_STROKE_DP));

        for (int i = 0; i < arrowCount; i++) {
            float margin = dp(marginStart + i * CHEVRON_MARGIN_STEP_DP);
            float arrowPhase = (phase - i * phaseStep + 1f) % 1f;
            float arrowPulse = 1f - Math.abs(arrowPhase - 0.5f) * 2f;
            float movement = dp(CHEVRON_TRAVEL_DP) * arrowPulse;
            float left = leftSide ? margin + movement : w - margin - size - movement;
            paint.setColor(forceOpaque(baseColor));
            drawChevron(canvas, left, top, size, leftSide);
        }
    }

    private void drawChevron(Canvas canvas, float left, float top, float size, boolean leftSide) {
        path.reset();
        if (leftSide) {
            path.moveTo(left + size * 0.32f, top + size * 0.16f);
            path.lineTo(left + size * 0.70f, top + size * 0.50f);
            path.lineTo(left + size * 0.32f, top + size * 0.84f);
        } else {
            path.moveTo(left + size * 0.68f, top + size * 0.16f);
            path.lineTo(left + size * 0.30f, top + size * 0.50f);
            path.lineTo(left + size * 0.68f, top + size * 0.84f);
        }
        canvas.drawPath(path, paint);
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private int withAlpha(int color, int alpha) {
        return (clamp(alpha, 0, 255) << 24) | (color & 0x00ffffff);
    }

    private int forceOpaque(int color) {
        return 0xff000000 | (color & 0x00ffffff);
    }

    private int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    private float clampFloat(float value, float min, float max) {
        return Math.max(min, Math.min(max, value));
    }
}
