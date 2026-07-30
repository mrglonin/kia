package kia.app.tpms;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.os.SystemClock;
import android.view.View;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

import kia.app.R;
import kia.app.core.model.NavigationState;
import kia.app.core.model.TpmsState;
import kia.app.core.model.VehicleState;

public final class TpmsDashboardView extends View {
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF dst = new RectF();
    private final Path path = new Path();
    private final Map<Integer, Bitmap> cache = new HashMap<>();
    private TpmsState state = TpmsState.empty();
    private NavigationState navigation = NavigationState.empty();
    private VehicleState vehicle = VehicleState.empty();
    private float motionSpeedKmh;
    private float compactSpeedBadgeOffset = Float.NaN;
    private boolean widgetMode;
    private final Runnable freshnessRefresh = () -> {
        updateAccessibilitySummary();
        invalidate();
    };

    public TpmsDashboardView(Context context) {
        super(context);
        paint.setFilterBitmap(true);
    }

    public void setState(TpmsState value) {
        state = value == null ? TpmsState.empty() : value;
        updateAccessibilitySummary();
        scheduleFreshnessRefresh();
        invalidate();
    }

    public void setMotionSpeedKmh(float value) {
        float clean = Math.max(0f, Math.min(240f, value));
        boolean wasMoving = moving();
        motionSpeedKmh = clean;
        if (wasMoving || moving()) invalidate();
    }

    public void setNavigationState(NavigationState value) {
        navigation = value == null ? NavigationState.empty() : value;
        invalidate();
    }

    public void setVehicleState(VehicleState value) {
        vehicle = value == null ? VehicleState.empty() : value;
        invalidate();
    }

    public void setWidgetMode(boolean value) {
        if (widgetMode == value) return;
        widgetMode = value;
        compactSpeedBadgeOffset = Float.NaN;
        invalidate();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        int width = getWidth();
        int height = getHeight();
        boolean portrait = width < height;
        boolean compactWindow = width < 900 || height <= 560;
        if ((widgetMode && portrait) || portrait || compactWindow) {
            drawDarkBackground(canvas);
            drawCompactDashboard(canvas, width, height);
            scheduleFreshnessRefresh();
            if (moving()) postInvalidateOnAnimation();
            else if (hasAlert()) postInvalidateDelayed(360L);
            return;
        }
        float scale = Math.min(width / 1280f, height / 720f);
        float ox = (width - 1280f * scale) / 2f;
        float oy = (height - 720f * scale) / 2f;

        drawDarkBackground(canvas);
        drawRoad(canvas, ox, oy, scale);
        drawCar(canvas, ox, oy, scale);
        drawTires(canvas, ox, oy, scale);
        // Wide dashboards used to omit navigation completely. Keep the hint
        // inside the center lane so it never covers the tire cards.
        drawNavigationHints(canvas,
                ox + 408f * scale, ox + 872f * scale,
                oy + 18f * scale);
        scheduleFreshnessRefresh();
        if (moving()) postInvalidateOnAnimation();
        else if (hasAlert()) postInvalidateDelayed(360L);
    }

    @Override
    protected void onDetachedFromWindow() {
        removeCallbacks(freshnessRefresh);
        super.onDetachedFromWindow();
    }

    private void scheduleFreshnessRefresh() {
        removeCallbacks(freshnessRefresh);
        if (state == null || state.known == null || state.wheelUpdatedAt == null) return;
        long now = System.currentTimeMillis();
        long nextDelay = Long.MAX_VALUE;
        for (int wheel = 0; wheel < TpmsState.WHEEL_COUNT; wheel++) {
            if (!known(wheel) || !state.isWheelFresh(wheel, now)) continue;
            long expiresAt = state.wheelUpdatedAt[wheel] + TpmsState.WHEEL_STALE_AFTER_MS + 1L;
            nextDelay = Math.min(nextDelay, Math.max(1L, expiresAt - now));
        }
        if (nextDelay != Long.MAX_VALUE) postDelayed(freshnessRefresh, nextDelay);
    }

    private void updateAccessibilitySummary() {
        setContentDescription(state == null ? "TPMS: нет данных" : state.summary());
    }

    private void drawDarkBackground(Canvas canvas) {
        canvas.drawColor(0xff0d0f13);
        paint.clearShadowLayer();
        paint.setStyle(Paint.Style.FILL);
        paint.setShader(new LinearGradient(0, 0, 0, getHeight(),
                0xff14171d, 0xff08090c, Shader.TileMode.CLAMP));
        dst.set(0, 0, getWidth(), getHeight());
        canvas.drawRect(dst, paint);
        paint.setShader(null);
    }

    private void drawTires(Canvas canvas, float ox, float oy, float scale) {
        float panelW = 350f * scale;
        float panelH = 196f * scale;
        float side = 42f * scale;
        float left = ox + side;
        float right = ox + 1280f * scale - side - panelW;
        float top = oy + 120f * scale;
        float bottom = oy + 404f * scale;

        drawTire(canvas, 0, "Л.П.(L.F.)", left, top, panelW, panelH, scale);
        drawTire(canvas, 1, "П.П.(R.F.)", right, top, panelW, panelH, scale);
        drawTire(canvas, 2, "Л.З.(L.R.)", left, bottom, panelW, panelH, scale);
        drawTire(canvas, 3, "П.З.(R.R.)", right, bottom, panelW, panelH, scale);
    }

    private void drawTire(Canvas canvas, int wheel, String title, float x, float y, float w, float h, float scale) {
        boolean known = known(wheel);
        boolean fresh = fresh(wheel);
        int warning = warning(wheel);
        int severity = severity(wheel);
        boolean alert = fresh && severity != TpmsAlertController.SEVERITY_NONE;
        int alertColor = severityColor(severity);
        drawTireCardBackground(canvas, x, y, w, h, scale, severity);
        float leftPad = 30f * scale;
        float rightPad = 26f * scale;

        paint.clearShadowLayer();
        paint.setStyle(Paint.Style.FILL);
        paint.setTypeface(Typeface.DEFAULT_BOLD);
        paint.setColor(0xff9aa3af);
        paint.setTextSize(17f * scale);
        paint.setTextAlign(Paint.Align.LEFT);
        drawTextCenterY(canvas, title, x + leftPad, y + 31f * scale, paint);

        drawStatusChip(canvas, known, fresh, warning, severity,
                x + w - 80f * scale, y + 31f * scale, scale);

        paint.setTypeface(Typeface.DEFAULT_BOLD);
        paint.setTextSize(58f * scale);
        paint.setTextAlign(Paint.Align.LEFT);
        paint.setColor(fresh ? 0xfff4f1ea : 0xff747d89);
        if (fresh) setTextShadow(scale);
        else paint.clearShadowLayer();
        drawTextBottom(canvas, known ? pressureText(wheel) : "__", x + leftPad, y + h - 42f * scale, paint);

        paint.clearShadowLayer();
        paint.setTypeface(Typeface.DEFAULT_BOLD);
        paint.setTextSize(21f * scale);
        paint.setColor(fresh ? 0xfff4f1ea : 0xff747d89);
        drawTextBottom(canvas, "Bar", x + leftPad + 5f * scale, y + h - 18f * scale, paint);

        paint.setTextAlign(Paint.Align.RIGHT);
        paint.setTypeface(Typeface.DEFAULT_BOLD);
        paint.setTextSize(32f * scale);
        paint.setColor(alert ? alertColor : (fresh ? 0xfff4f1ea : 0xff747d89));
        drawTextBottom(canvas, (known ? tempText(wheel) : "__") + "°C", x + w - rightPad, y + h - 74f * scale, paint);

        paint.clearShadowLayer();
        paint.setTextAlign(Paint.Align.RIGHT);
        paint.setTypeface(Typeface.DEFAULT_BOLD);
        paint.setTextSize(16f * scale);
        paint.setColor(0xff9aa3af);
        drawTextBottom(canvas, "Температура", x + w - rightPad, y + h - 36f * scale, paint);
    }

    private void drawCompactDashboard(Canvas canvas, int width, int height) {
        if (height <= 560 && width <= 900) {
            drawCompactGridOnly(canvas, width, height);
        } else if (width > height && height <= 680) {
            drawCompactWideDashboard(canvas, width, height);
        } else {
            drawCompactPortraitDashboard(canvas, width, height);
        }
    }

    private void drawCompactPortraitDashboard(Canvas canvas, int width, int height) {
        float min = Math.min(width, height);
        boolean narrowTall = narrowTallWidgetMode();
        float pad = narrowTall ? clamp(min * 0.038f, 14f, 22f) : clamp(min * 0.045f, 12f, 26f);
        float topH = narrowTall
                ? clamp(height * 0.52f, 320f, Math.min(height * 0.58f, width * 0.98f))
                : clamp(height * 0.59f, 280f, Math.min(height * 0.66f, width * 1.18f));
        float carCx = width * 0.5f;
        float verticalShift = topH * (narrowTall ? 0.070f : 0.10f);
        float carCy = pad + topH * (narrowTall ? 0.66f : 0.56f) + verticalShift;
        drawCompactRoad(canvas, carCx, pad * 0.25f + verticalShift,
                pad + topH + pad * 0.5f + verticalShift,
                width * (narrowTall ? 0.30f : 0.34f), width * (narrowTall ? 0.68f : 0.78f));
        drawCompactNavigationHints(canvas, width, pad);
        float carW = Math.min(width * (narrowTall ? 0.64f : 0.64f), topH * (narrowTall ? 0.68f : 0.66f));
        float carH = Math.min(topH * (narrowTall ? 0.86f : 0.92f), height * (narrowTall ? 0.47f : 0.52f));
        drawFitAspect(canvas, carTopViewRes(), carCx, carCy, carW, carH);
        if (hasAlert()) {
            float warningSize = Math.min(width * 0.20f, topH * 0.26f);
            drawFitAspect(canvas, R.drawable.tpms_car_warning_center,
                    carCx, carCy - topH * 0.02f, warningSize, warningSize);
        }

        float gridTop = pad + topH + pad * (narrowTall ? 0.35f : 0.55f);
        float gridBottom = height - pad;
        float gap = clamp(width * 0.020f, 8f, 14f);
        if (gridBottom - gridTop < 230f) gridTop = height * 0.42f;
        float cardW = (width - pad * 2f - gap) / 2f;
        float cardH = narrowTall
                ? clamp((gridBottom - gridTop - gap) / 2f, 118f, 136f)
                : clamp((gridBottom - gridTop - gap) / 2f, 124f, 146f);
        gridTop = gridBottom - cardH * 2f - gap;
        drawCompactTireCard(canvas, TpmsState.WHEEL_FL, pad, gridTop, cardW, cardH);
        drawCompactTireCard(canvas, TpmsState.WHEEL_FR, pad + cardW + gap, gridTop, cardW, cardH);
        drawCompactTireCard(canvas, TpmsState.WHEEL_RL, pad, gridTop + cardH + gap, cardW, cardH);
        drawCompactTireCard(canvas, TpmsState.WHEEL_RR, pad + cardW + gap,
                gridTop + cardH + gap, cardW, cardH);
    }

    private void drawCompactWideDashboard(Canvas canvas, int width, int height) {
        float pad = clamp(height * 0.045f, 10f, 18f);
        float gap = clamp(height * 0.026f, 8f, 12f);
        float cardW = clamp(width * 0.26f, 210f, width * 0.31f);
        float cardH = (height - pad * 2f - gap) / 2f;
        drawCompactTireCard(canvas, TpmsState.WHEEL_FL, pad, pad, cardW, cardH);
        drawCompactTireCard(canvas, TpmsState.WHEEL_RL, pad, pad + cardH + gap, cardW, cardH);
        drawCompactTireCard(canvas, TpmsState.WHEEL_FR, width - pad - cardW, pad, cardW, cardH);
        drawCompactTireCard(canvas, TpmsState.WHEEL_RR, width - pad - cardW,
                pad + cardH + gap, cardW, cardH);
        float carCx = width * 0.5f;
        float carCy = height * 0.52f;
        float roadTop = pad * 0.5f;
        float roadBottom = height - pad * 0.4f;
        drawCompactRoad(canvas, carCx, roadTop, roadBottom, width * 0.10f, width * 0.25f);
        drawNavigationHints(canvas,
                pad + cardW + gap, width - pad - cardW - gap, pad);
        drawFitAspect(canvas, carTopViewRes(), carCx, carCy,
                Math.max(120f, width - cardW * 2f - pad * 5f), height * 0.96f);
        if (hasAlert()) {
            float warningSize = Math.min(height * 0.26f, 76f);
            drawFitAspect(canvas, R.drawable.tpms_car_warning_center,
                    carCx, carCy - height * 0.02f, warningSize, warningSize);
        }
    }

    private void drawCompactGridOnly(Canvas canvas, int width, int height) {
        float pad = clamp(Math.min(width, height) * 0.038f, 9f, 16f);
        float gap = clamp(Math.min(width, height) * 0.025f, 7f, 12f);
        drawCompactNavigationHints(canvas, width, pad);
        float cardW = (width - pad * 2f - gap) / 2f;
        float cardH = (height - pad * 2f - gap) / 2f;
        drawCompactTireCard(canvas, TpmsState.WHEEL_FL, pad, pad, cardW, cardH);
        drawCompactTireCard(canvas, TpmsState.WHEEL_FR, pad + cardW + gap, pad, cardW, cardH);
        drawCompactTireCard(canvas, TpmsState.WHEEL_RL, pad, pad + cardH + gap, cardW, cardH);
        drawCompactTireCard(canvas, TpmsState.WHEEL_RR, pad + cardW + gap,
                pad + cardH + gap, cardW, cardH);
    }

    private void drawCompactTireCard(Canvas canvas, int wheel, float x, float y, float w, float h) {
        boolean known = known(wheel);
        boolean fresh = fresh(wheel);
        int warning = warning(wheel);
        int severity = severity(wheel);
        boolean alert = fresh && severity != TpmsAlertController.SEVERITY_NONE;
        boolean tinyWidget = tinyWidgetMode();
        boolean narrowTall = narrowTallWidgetMode();
        float scale = clamp(Math.min(w / 300f, h / 170f), 0.64f, 1.12f);
        drawTireCardBackground(canvas, x, y, w, h, scale, severity);

        float padX = tinyWidget ? clamp(w * 0.082f, 18f, 28f)
                : (narrowTall ? clamp(w * 0.095f, 24f, 30f) : clamp(w * 0.078f, 14f, 24f));
        float padY = tinyWidget ? clamp(h * 0.18f, 26f, 34f)
                : (narrowTall ? clamp(h * 0.20f, 25f, 31f) : clamp(h * 0.15f, 18f, 24f));

        paint.clearShadowLayer();
        paint.setStyle(Paint.Style.FILL);
        paint.setTextAlign(Paint.Align.LEFT);
        paint.setTypeface(Typeface.DEFAULT_BOLD);
        paint.setColor(0xffa5adb9);
        paint.setTextSize(tinyWidget ? clamp(h * 0.22f, 30f, 40f)
                : (narrowTall ? clamp(h * 0.158f, 19f, 23f) : clamp(h * 0.132f, 16f, 22f)));
        drawTextCenterY(canvas, wheelTitle(wheel), x + padX, y + padY, paint);

        float chipRight = x + w - padX;
        if (tinyWidget && wheel == TpmsState.WHEEL_FR) {
            chipRight -= clamp(w * 0.12f, 42f, 54f);
        }
        drawCompactStatusChip(canvas, known, fresh, warning, severity,
                chipRight, y + padY, scale);

        paint.setTypeface(Typeface.DEFAULT_BOLD);
        paint.setTextAlign(Paint.Align.LEFT);
        paint.setColor(fresh ? 0xfff4f1ea : 0xff747d89);
        paint.setTextSize(tinyWidget
                ? clamp(Math.min(w * 0.28f, h * 0.46f), 56f, 76f)
                : (narrowTall ? clamp(Math.min(w * 0.20f, h * 0.32f), 38f, 48f)
                : clamp(Math.min(w * 0.22f, h * 0.34f), 34f, 56f)));
        if (fresh) setTextShadow(scale);
        else paint.clearShadowLayer();
        drawTextBottom(canvas, known ? pressureText(wheel) : "__",
                x + padX, y + h - (tinyWidget ? clamp(h * 0.24f, 42f, 56f)
                        : clamp(h * 0.23f, 30f, 42f)), paint);

        paint.clearShadowLayer();
        paint.setTextSize(tinyWidget ? clamp(h * 0.18f, 25f, 34f)
                : (narrowTall ? clamp(h * 0.128f, 16f, 20f) : clamp(h * 0.128f, 16f, 22f)));
        drawTextBottom(canvas, "Bar", x + padX + 2f * scale,
                y + h - (tinyWidget ? clamp(h * 0.08f, 18f, 26f)
                        : clamp(h * 0.10f, 15f, 22f)), paint);

        paint.setTextAlign(Paint.Align.RIGHT);
        paint.setTypeface(Typeface.DEFAULT_BOLD);
        paint.setColor(alert ? severityColor(severity) : (fresh ? 0xfff4f1ea : 0xff747d89));
        paint.setTextSize(tinyWidget ? clamp(h * 0.39f, 55f, 75f)
                : (narrowTall ? clamp(h * 0.265f, 35f, 44f) : clamp(h * 0.247f, 32f, 46f)));
        drawTextBottom(canvas, (known ? tempText(wheel) : "__") + "°C",
                x + w - padX, y + h - (tinyWidget ? clamp(h * 0.32f, 54f, 70f)
                        : (narrowTall ? clamp(h * 0.32f, 41f, 52f)
                        : clamp(h * 0.28f, 36f, 50f))), paint);

        paint.clearShadowLayer();
        paint.setTypeface(Typeface.DEFAULT_BOLD);
        paint.setColor(0xff9aa3af);
        paint.setTextSize(tinyWidget ? clamp(h * 0.135f, 20f, 26f)
                : (narrowTall ? clamp(h * 0.11f, 14f, 17f) : clamp(h * 0.095f, 12f, 15f)));
        drawTextBottom(canvas, "Температура", x + w - padX,
                y + h - (tinyWidget ? clamp(h * 0.10f, 20f, 28f)
                        : (narrowTall ? clamp(h * 0.105f, 14f, 18f)
                        : clamp(h * 0.10f, 14f, 19f))), paint);
    }

    private void drawCompactStatusChip(Canvas canvas, boolean known, boolean fresh,
                                       int warning, int severity,
                                       float right, float cy, float scale) {
        String text = !known ? "ОЖИД." : (!fresh ? "УСТАР." : compactWarningText(warning));
        boolean alert = fresh && severity != TpmsAlertController.SEVERITY_NONE;
        paint.setTypeface(Typeface.DEFAULT_BOLD);
        boolean tinyWidget = tinyWidgetMode();
        boolean narrowTall = narrowTallWidgetMode();
        paint.setTextSize(tinyWidget ? 24.75f * scale : (narrowTall ? 17.25f : 16.875f * scale));
        paint.clearShadowLayer();
        float tw = paint.measureText(text);
        float chipW = tinyWidget ? Math.max(96f * scale, tw + 25.5f * scale)
                : (narrowTall ? Math.max(78f, tw + 21f) : Math.max(77f * scale, tw + 21f * scale));
        float chipHalfH = tinyWidget ? 22f * scale : (narrowTall ? 18f : 17f * scale);
        dst.set(right - chipW, cy - chipHalfH, right, cy + chipHalfH);
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(alert ? severityColor(severity)
                : (fresh ? 0xff27313a : (known ? 0xff31343a : 0xff22272f)));
        canvas.drawRoundRect(dst, 7f * scale, 7f * scale, paint);
        paint.setTextAlign(Paint.Align.CENTER);
        paint.setColor(alert && severity == TpmsAlertController.SEVERITY_WARNING
                ? 0xff14110b : 0xfff4f1ea);
        drawTextCenterY(canvas, text, right - chipW / 2f, cy, paint);
    }

    private String compactWarningText(int warning) {
        switch (warning) {
            case TpmsAlertController.WARNING_LOW_PRESSURE:
                return "НИЗКОЕ";
            case TpmsAlertController.WARNING_HIGH_PRESSURE:
                return "ВЫСОКОЕ";
            case TpmsAlertController.WARNING_LOW_TEMP:
                return "ХОЛОД";
            case TpmsAlertController.WARNING_HIGH_TEMP:
                return "ЖАРА";
            case TpmsAlertController.WARNING_FAST_LEAKAGE:
                return "УТЕЧКА";
            case TpmsAlertController.WARNING_LOW_BATTERY:
                return "БАТ.";
            default:
                return "НОРМА";
        }
    }

    private void drawCompactRoad(Canvas canvas, float cx, float top, float bottom,
                                 float topW, float bottomW) {
        path.reset();
        path.moveTo(cx - topW * 0.5f, top);
        path.lineTo(cx + topW * 0.5f, top);
        path.lineTo(cx + bottomW * 0.5f, bottom);
        path.lineTo(cx - bottomW * 0.5f, bottom);
        path.close();

        paint.clearShadowLayer();
        paint.setStyle(Paint.Style.FILL);
        paint.setShader(new LinearGradient(cx, top, cx, bottom,
                new int[]{0x00181f27, 0x2227303a, 0x3a303942, 0x18161b21, 0x00161b21},
                new float[]{0f, 0.18f, 0.52f, 0.82f, 1f}, Shader.TileMode.CLAMP));
        canvas.drawPath(path, paint);
        paint.setShader(null);

        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(Math.max(1f, (bottom - top) / 520f));
        paint.setShader(new LinearGradient(cx, top, cx, bottom,
                new int[]{0x00ffffff, 0x38ffffff, 0x30ffffff, 0x00ffffff},
                new float[]{0f, 0.24f, 0.72f, 1f}, Shader.TileMode.CLAMP));
        canvas.drawLine(cx - topW * 0.5f, top, cx - bottomW * 0.5f, bottom, paint);
        canvas.drawLine(cx + topW * 0.5f, top, cx + bottomW * 0.5f, bottom, paint);
        paint.setShader(null);

        canvas.save();
        canvas.clipPath(path);
        drawRoadDashes(canvas, cx, top, bottom, Math.max(0.62f, (bottom - top) / 720f));
        drawRoadSweep(canvas, cx, top, bottom, topW, bottomW,
                Math.max(0.62f, (bottom - top) / 720f));
        canvas.restore();
        paint.setStyle(Paint.Style.FILL);
    }

    private void drawCompactNavigationHints(Canvas canvas, int width, float pad) {
        drawNavigationHints(canvas, 0f, width, pad);
    }

    private void drawNavigationHints(Canvas canvas, float leftBound, float rightBound, float pad) {
        int limit = speedLimitKmh();
        int speed = currentSpeedKmh();
        DashboardSpeedVisibilityPolicy.Decision speedVisibility =
                DashboardSpeedVisibilityPolicy.resolve(moving(), speed, limit);
        boolean hasLimit = speedVisibility.showSpeedLimit;
        boolean hasSpeed = speedVisibility.showCurrentSpeed;
        boolean hasRoute = hasRouteHint();
        if (!hasLimit && !hasSpeed && !hasRoute) return;

        float availableWidth = Math.max(0f, rightBound - leftBound);
        boolean narrowTall = narrowTallWidgetMode();
        float badgeRadius = narrowTall
                ? clamp(availableWidth * 0.085f, 42f, 54f)
                : availableWidth < 560
                ? clamp(availableWidth * 0.105f, 46f, 62f)
                : clamp(availableWidth * 0.115f, 64f, 88f);
        float cx = leftBound + pad + badgeRadius + 2f;
        float cy = pad + badgeRadius + (narrowTall ? 7f : 2f);
        float limitRadius = badgeRadius * 0.7225f;
        if (hasSpeed) {
            float offset = updateCompactSpeedBadgeOffset(hasLimit);
            float currentRadius = badgeRadius * 0.81f;
            float smallRadius = badgeRadius * (narrowTall ? 0.583f : 0.632f);
            float topCy = cy;
            float bottomCy = cy + limitRadius + pad * (narrowTall ? 0.34f : 0.54f) + smallRadius;
            float speedCy = lerp(topCy, bottomCy, offset);
            float speedRadius = lerp(currentRadius, smallRadius, offset);
            drawCurrentSpeedBadge(canvas, cx, speedCy, speedRadius, speed, !hasLimit || offset < 0.35f);
        } else {
            compactSpeedBadgeOffset = Float.NaN;
        }
        if (hasLimit) {
            drawSpeedLimitBadge(canvas, cx, cy, limitRadius, limit);
        }
        if (hasRoute) {
            float left = (hasLimit || hasSpeed) ? cx + badgeRadius + pad * (narrowTall ? 0.82f : 1.05f) : pad;
            if (!hasLimit && !hasSpeed) left += leftBound;
            float right = rightBound - pad - (narrowTall ? 46f : 0f);
            if (right - left > 130f) {
                float halfH = badgeRadius * (narrowTall ? 0.88f : 0.92f);
                drawRouteHint(canvas, left, cy - halfH, right, cy + halfH);
            }
        }
    }

    private int carTopViewRes() {
        return vehicle != null && vehicle.sunroofKnown && vehicle.sunroofOpen
                ? R.drawable.kia_top_view_sunroof_open
                : R.drawable.kia_top_view;
    }

    private void drawSpeedLimitBadge(Canvas canvas, float cx, float cy, float radius, int limit) {
        paint.clearShadowLayer();
        paint.setShader(null);
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(0xfff7f7f7);
        canvas.drawCircle(cx, cy, radius, paint);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(Math.max(3f, radius * 0.16f));
        paint.setColor(0xffdd3442);
        canvas.drawCircle(cx, cy, radius * 0.82f, paint);
        paint.setStyle(Paint.Style.FILL);
        paint.setTypeface(Typeface.DEFAULT_BOLD);
        paint.setTextAlign(Paint.Align.CENTER);
        setFittingTextSize(String.valueOf(limit), radius * 1.16f,
                clamp(radius * 0.98f, narrowTallWidgetMode() ? 38f : 46f,
                        narrowTallWidgetMode() ? 58f : 84f), 30f);
        paint.setColor(0xff111111);
        drawTextCenterY(canvas, String.valueOf(limit), cx, cy, paint);
    }

    private float updateCompactSpeedBadgeOffset(boolean hasLimit) {
        float target = hasLimit ? 1f : 0f;
        if (Float.isNaN(compactSpeedBadgeOffset)) {
            compactSpeedBadgeOffset = target;
            return compactSpeedBadgeOffset;
        }
        float delta = target - compactSpeedBadgeOffset;
        if (Math.abs(delta) < 0.015f) {
            compactSpeedBadgeOffset = target;
            return compactSpeedBadgeOffset;
        }
        compactSpeedBadgeOffset += delta * 0.24f;
        postInvalidateOnAnimation();
        return compactSpeedBadgeOffset;
    }

    private void drawCurrentSpeedBadge(Canvas canvas, float cx, float cy, float radius,
                                       int speed, boolean primary) {
        paint.clearShadowLayer();
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(primary ? 0xffd8dde4 : 0xfff7f7f7);
        canvas.drawCircle(cx, cy, radius, paint);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(Math.max(2f, radius * (primary ? 0.07f : 0.08f)));
        paint.setColor(primary ? 0x9068727f : 0x80333a44);
        canvas.drawCircle(cx, cy, radius * 0.88f, paint);
        paint.setStyle(Paint.Style.FILL);
        paint.setTextAlign(Paint.Align.CENTER);
        paint.setTypeface(Typeface.DEFAULT_BOLD);
        boolean narrowTall = narrowTallWidgetMode();
        setFittingTextSize(String.valueOf(speed), radius * 1.28f,
                clamp(radius * 0.98f, narrowTall ? (primary ? 36f : 29f) : (primary ? 44f : 36f),
                        narrowTall ? (primary ? 58f : 45f) : (primary ? 84f : 68f)), 24f);
        paint.setColor(0xff111111);
        drawTextCenterY(canvas, String.valueOf(speed), cx, cy, paint);
    }

    private void drawRouteHint(Canvas canvas, float left, float top, float right, float bottom) {
        DashboardNavigationSnapshot snapshot = navigationSnapshot();
        String text = clean(snapshot.distance);
        if (text.isEmpty()) text = clean(snapshot.presentation.fallbackLabel);
        if (text.length() > 16) text = text.substring(0, 16);

        paint.clearShadowLayer();
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(0xd91a2028);
        dst.set(left, top, right, bottom);
        canvas.drawRoundRect(dst, 14f, 14f, paint);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(1.4f);
        paint.setColor(0x38ffffff);
        canvas.drawRoundRect(dst, 14f, 14f, paint);
        paint.setStyle(Paint.Style.FILL);

        boolean narrowTall = narrowTallWidgetMode();
        float boxH = bottom - top;
        float iconCx = left + boxH * (narrowTall ? 0.47f : 0.48f);
        paint.setColor(0xff24313d);
        canvas.drawCircle(iconCx, (top + bottom) / 2f, boxH * (narrowTall ? 0.28f : 0.30f), paint);

        paint.setTypeface(Typeface.DEFAULT_BOLD);
        paint.setTextAlign(Paint.Align.CENTER);
        paint.setColor(0xfff4f1ea);
        String glyph = maneuverGlyph();
        setFittingTextSize(glyph, boxH * 0.48f,
                narrowTall ? clamp(boxH * 0.46f, 34f, 44f)
                        : clamp(boxH * 0.46f, 44f, 72f),
                narrowTall ? 25f : 30f);
        drawTextCenterY(canvas, glyph, iconCx, (top + bottom) / 2f, paint);

        paint.setTextAlign(Paint.Align.LEFT);
        paint.setTextSize(narrowTall ? clamp(boxH * 0.34f, 30f, 38f)
                : clamp(boxH * 0.25f, 30f, 46f));
        paint.setColor(0xfff4f1ea);
        drawTextCenterY(canvas, text, left + boxH * (narrowTall ? 0.88f : 0.92f),
                top + boxH * (narrowTall ? 0.35f : 0.42f), paint);
        paint.setTextSize(narrowTall ? clamp(boxH * 0.22f, 20f, 26f)
                : clamp(boxH * 0.16f, 18f, 26f));
        paint.setColor(0xffdce3ea);
        drawTextCenterY(canvas, maneuverLabel(), left + boxH * (narrowTall ? 0.88f : 0.92f),
                top + boxH * (narrowTall ? 0.74f : 0.68f), paint);
    }

    private int speedLimitKmh() {
        return parseKmh(navigation == null ? "" : navigation.speedLimit, false);
    }

    private int currentSpeedKmh() {
        return parseKmh(navigation == null ? "" : navigation.currentSpeed, true);
    }

    private int parseKmh(String raw, boolean allowZero) {
        StringBuilder number = new StringBuilder();
        for (int i = 0; i < raw.length(); i++) {
            char ch = raw.charAt(i);
            if (ch >= '0' && ch <= '9') {
                number.append(ch);
            } else if (number.length() > 0) {
                break;
            }
        }
        if (number.length() == 0) return -1;
        try {
            int value = Integer.parseInt(number.toString());
            return (value > 0 || allowZero) && value <= 240 ? value : -1;
        } catch (NumberFormatException ignored) {
            return -1;
        }
    }

    private boolean hasRouteHint() {
        if (navigation == null || !navigation.active || navigation.finishReached) return false;
        return !navigationSnapshot().maneuverId.isEmpty();
    }

    private String maneuverGlyph() {
        return navigationSnapshot().presentation.glyph;
    }

    private String maneuverLabel() {
        DashboardNavigationSnapshot snapshot = navigationSnapshot();
        DashboardManeuverPresentation presentation = snapshot.presentation;
        if (snapshot.clusterBacked) {
            String value = presentation.fallbackLabel;
            return value.length() > 18 ? value.substring(0, 18) : value;
        }
        String value = clean(navigation == null ? "" : navigation.maneuverText);
        if (presentation.roundaboutExit > 0
                && (value.isEmpty() || !value.matches(".*\\d.*"))) {
            value = presentation.fallbackLabel;
        } else if (value.isEmpty()) {
            value = presentation.fallbackLabel;
        }
        if (value.length() > 18) value = value.substring(0, 18);
        return value;
    }

    private DashboardNavigationSnapshot navigationSnapshot() {
        return DashboardNavigationSnapshot.resolve(navigation);
    }

    private void drawTireCardBackground(Canvas canvas, float x, float y, float w, float h, float scale, int severity) {
        boolean alert = severity != TpmsAlertController.SEVERITY_NONE;
        boolean critical = severity == TpmsAlertController.SEVERITY_CRITICAL;
        int accent = severityColor(severity);
        paint.clearShadowLayer();
        paint.setShader(null);
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(!alert ? 0xee191d23 : (critical ? 0xff2f1217 : 0xff2a2113));
        dst.set(x, y, x + w, y + h);
        canvas.drawRoundRect(dst, 8f * scale, 8f * scale, paint);

        if (alert) {
            paint.setColor(accent);
            dst.set(x, y, x + 6f * scale, y + h);
            canvas.drawRoundRect(dst, 8f * scale, 8f * scale, paint);
        }

        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(1f * scale);
        paint.setColor(alert ? accent : 0xff343a44);
        dst.set(x + 0.5f * scale, y + 0.5f * scale, x + w - 0.5f * scale, y + h - 0.5f * scale);
        canvas.drawRoundRect(dst, 8f * scale, 8f * scale, paint);
        paint.setStyle(Paint.Style.FILL);
    }

    private void drawStatusChip(Canvas canvas, boolean known, boolean fresh,
                                int warning, int severity,
                                float cx, float cy, float scale) {
        String text = !known ? "ОЖИДАНИЕ" : (!fresh ? "ДАННЫЕ УСТАРЕЛИ" : warningText(warning));
        boolean alert = fresh && severity != TpmsAlertController.SEVERITY_NONE;
        boolean critical = severity == TpmsAlertController.SEVERITY_CRITICAL;
        paint.setTypeface(Typeface.DEFAULT_BOLD);
        paint.setTextSize(12f * scale);
        paint.clearShadowLayer();
        float tw = paint.measureText(text);
        float chipW = Math.max(92f * scale, tw + 22f * scale);
        dst.set(cx - chipW / 2f, cy - 12f * scale, cx + chipW / 2f, cy + 12f * scale);
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(alert ? severityColor(severity)
                : (fresh ? 0xff26303a : (known ? 0xff31343a : 0xff22272f)));
        canvas.drawRoundRect(dst, 8f * scale, 8f * scale, paint);
        paint.setColor(alert && !critical ? 0xff14110b : 0xfff4f1ea);
        paint.setTextAlign(Paint.Align.CENTER);
        drawTextCenterY(canvas, text, cx, cy, paint);
    }

    private void drawCar(Canvas canvas, float ox, float oy, float scale) {
        float carW = 420f * scale;
        float carH = 660f * scale;
        float carCx = ox + 640f * scale;
        float carCy = oy + 360f * scale;
        drawFitAspect(canvas, carTopViewRes(), carCx, carCy, carW, carH);
        if (hasAlert()) {
            float warningSize = 150f * scale;
            drawFitAspect(canvas, R.drawable.tpms_car_warning_center,
                    carCx, carCy - 16f * scale, warningSize, warningSize);
        }
    }

    private void drawRoad(Canvas canvas, float ox, float oy, float scale) {
        float cx = ox + 640f * scale;
        float top = oy - 84f * scale;
        float bottom = oy + 792f * scale;
        float topW = 332f * scale;
        float bottomW = 520f * scale;

        path.reset();
        path.moveTo(cx - topW * 0.5f, top);
        path.lineTo(cx + topW * 0.5f, top);
        path.lineTo(cx + bottomW * 0.5f, bottom);
        path.lineTo(cx - bottomW * 0.5f, bottom);
        path.close();

        paint.clearShadowLayer();
        paint.setStyle(Paint.Style.FILL);
        paint.setShader(new LinearGradient(cx, top, cx, bottom,
                new int[]{0x0027303a, 0x1a27303a, 0x44303942, 0x301b2027, 0x00161b21, 0x00161b21},
                new float[]{0f, 0.20f, 0.48f, 0.76f, 0.90f, 1f}, Shader.TileMode.CLAMP));
        canvas.drawPath(path, paint);
        paint.setShader(null);

        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(1.2f * scale);
        paint.setShader(new LinearGradient(cx, top, cx, bottom,
                new int[]{0x00ffffff, 0x00ffffff, 0x42ffffff, 0x38ffffff, 0x00ffffff, 0x00ffffff},
                new float[]{0f, 0.14f, 0.28f, 0.72f, 0.88f, 1f}, Shader.TileMode.CLAMP));
        canvas.drawLine(cx - topW * 0.5f, top, cx - bottomW * 0.5f, bottom, paint);
        canvas.drawLine(cx + topW * 0.5f, top, cx + bottomW * 0.5f, bottom, paint);
        paint.setShader(null);

        canvas.save();
        canvas.clipPath(path);
        drawRoadDashes(canvas, cx, top, bottom, scale);
        drawRoadSweep(canvas, cx, top, bottom, topW, bottomW, scale);
        canvas.restore();
        paint.setStyle(Paint.Style.FILL);
    }

    private void drawRoadDashes(Canvas canvas, float cx, float top, float bottom, float scale) {
        boolean moving = moving();
        float dashH = 44f * scale;
        float gap = 48f * scale;
        float period = dashH + gap;
        float speed = Math.max(24f, Math.min(135f, motionSpeedKmh));
        float phase = moving ? ((SystemClock.uptimeMillis() * (0.16f + speed / 280f)) % period) : 0f;
        paint.setStyle(Paint.Style.FILL);
        paint.setShader(new LinearGradient(cx, top, cx, bottom,
                moving
                        ? new int[]{0x00f4f1ea, 0x00f4f1ea, 0xaaf4f1ea, 0x8cf4f1ea, 0x00f4f1ea, 0x00f4f1ea}
                        : new int[]{0x00f4f1ea, 0x00f4f1ea, 0x48f4f1ea, 0x34f4f1ea, 0x00f4f1ea, 0x00f4f1ea},
                new float[]{0f, 0.16f, 0.28f, 0.70f, 0.86f, 1f}, Shader.TileMode.CLAMP));
        for (float y = top - period + phase; y < bottom + period; y += period) {
            dst.set(cx - 3.2f * scale, y, cx + 3.2f * scale, y + dashH);
            canvas.drawRoundRect(dst, 3.2f * scale, 3.2f * scale, paint);
        }
        paint.setShader(null);
    }

    private void drawRoadSweep(Canvas canvas, float cx, float top, float bottom,
                               float topW, float bottomW, float scale) {
        if (!moving()) return;
        float period = 220f * scale;
        float phase = (SystemClock.uptimeMillis() * (0.10f + motionSpeedKmh / 420f)) % period;
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(2f * scale);
        paint.setShader(new LinearGradient(cx, top, cx, bottom,
                new int[]{0x0039d3be, 0x0039d3be, 0x3039d3be, 0x2439d3be, 0x0039d3be, 0x0039d3be},
                new float[]{0f, 0.16f, 0.30f, 0.68f, 0.84f, 1f}, Shader.TileMode.CLAMP));
        for (float y = top - period + phase; y < bottom + period; y += period) {
            float t = Math.max(0f, Math.min(1f, (y - top) / Math.max(1f, bottom - top)));
            float half = (topW + (bottomW - topW) * t) * 0.46f;
            canvas.drawLine(cx - half, y, cx + half, y, paint);
        }
        paint.setShader(null);
    }

    private boolean known(int wheel) {
        return state != null && state.known != null && wheel >= 0
                && wheel < state.known.length && state.known[wheel];
    }

    private boolean fresh(int wheel) {
        return state != null && state.isWheelFresh(wheel);
    }

    private int warning(int wheel) {
        return TpmsAlertController.warningState(getContext(), state, wheel);
    }

    private int severity(int wheel) {
        return TpmsAlertController.warningSeverity(getContext(), state, wheel);
    }

    private int severityColor(int severity) {
        if (severity == TpmsAlertController.SEVERITY_CRITICAL) return 0xffff5364;
        if (severity == TpmsAlertController.SEVERITY_WARNING) return 0xffffc43b;
        return 0xff39d3be;
    }

    private String pressureText(int wheel) {
        return String.format(Locale.US, "%.2f", state.pressureKpa[wheel] / 100f);
    }

    private String tempText(int wheel) {
        return String.valueOf(state.temperatureC[wheel]);
    }

    private boolean hasAlert() {
        return TpmsAlertController.hasWarnings(getContext(), state);
    }

    private boolean moving() {
        return motionSpeedKmh > 0.5f;
    }

    private boolean tinyWidgetMode() {
        return getHeight() < 560 && getWidth() < 900;
    }

    private boolean narrowTallWidgetMode() {
        return getWidth() < 700 && getHeight() > 700;
    }

    private int firstKnownWheel() {
        if (state == null || state.known == null) return -1;
        for (int wheel = 0; wheel < TpmsState.WHEEL_COUNT; wheel++) {
            if (state.known[wheel]) return wheel;
        }
        return -1;
    }

    private String wheelTitle(int wheel) {
        switch (wheel) {
            case TpmsState.WHEEL_FL:
                return "Л.П.(L.F.)";
            case TpmsState.WHEEL_FR:
                return "П.П.(R.F.)";
            case TpmsState.WHEEL_RL:
                return "Л.З.(L.R.)";
            case TpmsState.WHEEL_RR:
                return "П.З.(R.R.)";
            default:
                return "TPMS";
        }
    }

    private float clamp(float value, float min, float max) {
        return Math.max(min, Math.min(max, value));
    }

    private float lerp(float start, float end, float progress) {
        return start + (end - start) * clamp(progress, 0f, 1f);
    }

    private String clean(String value) {
        return value == null ? "" : value.trim();
    }

    private String warningText(int warning) {
        switch (warning) {
            case TpmsAlertController.WARNING_LOW_PRESSURE:
                return "НИЗКОЕ ДАВЛЕНИЕ";
            case TpmsAlertController.WARNING_HIGH_PRESSURE:
                return "ВЫСОКОЕ ДАВЛЕНИЕ";
            case TpmsAlertController.WARNING_LOW_TEMP:
                return "НИЗКАЯ ТЕМП.";
            case TpmsAlertController.WARNING_HIGH_TEMP:
                return "ВЫСОКАЯ ТЕМП.";
            case TpmsAlertController.WARNING_FAST_LEAKAGE:
                return "БЫСТРАЯ УТЕЧКА";
            case TpmsAlertController.WARNING_LOW_BATTERY:
                return "НИЗКИЙ ЗАРЯД";
            default:
                return "НОРМА";
        }
    }

    private void setFittingTextSize(String text, float maxWidth, float preferredSize, float minSize) {
        paint.setTextSize(preferredSize);
        float measured = paint.measureText(text == null ? "" : text);
        if (measured <= maxWidth || measured <= 0f) return;
        paint.setTextSize(Math.max(minSize, preferredSize * maxWidth / measured));
    }

    private void drawTextCenterY(Canvas canvas, String text, float x, float cy, Paint paint) {
        Paint.FontMetrics fm = paint.getFontMetrics();
        canvas.drawText(text == null ? "" : text, x, cy - (fm.ascent + fm.descent) / 2f, paint);
    }

    private void drawTextBottom(Canvas canvas, String text, float x, float bottom, Paint paint) {
        Paint.FontMetrics fm = paint.getFontMetrics();
        canvas.drawText(text == null ? "" : text, x, bottom - fm.descent, paint);
    }

    private void drawFit(Canvas canvas, int resId, float x, float y, float w, float h) {
        Bitmap bitmap = bitmap(resId);
        if (bitmap == null) return;
        paint.setShader(null);
        paint.setAlpha(255);
        paint.setColorFilter(null);
        paint.clearShadowLayer();
        dst.set(x, y, x + w, y + h);
        canvas.drawBitmap(bitmap, null, dst, paint);
    }

    private void drawFitAspect(Canvas canvas, int resId, float cx, float cy, float maxW, float maxH) {
        Bitmap bitmap = bitmap(resId);
        if (bitmap == null) return;
        float scale = Math.min(maxW / bitmap.getWidth(), maxH / bitmap.getHeight());
        float w = bitmap.getWidth() * scale;
        float h = bitmap.getHeight() * scale;
        drawFit(canvas, resId, cx - w / 2f, cy - h / 2f, w, h);
    }

    private void setTextShadow(float scale) {
        paint.setShadowLayer(3f * scale, 2f * scale, 2f * scale, 0xff000000);
    }

    private Bitmap bitmap(int resId) {
        Bitmap cached = cache.get(resId);
        if (cached != null) return cached;
        Bitmap decoded = BitmapFactory.decodeResource(getResources(), resId);
        if (decoded != null) cache.put(resId, decoded);
        return decoded;
    }
}
