package kia.app.amp;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RadialGradient;
import android.graphics.RectF;
import android.graphics.Shader;
import android.util.AttributeSet;
import android.view.View;

import kia.app.core.model.AmpState;

public final class AmpVisualizerView extends View {
    private static final int COLOR_BG = Color.rgb(27, 32, 40);
    private static final int COLOR_PANEL = Color.rgb(15, 18, 24);
    private static final int COLOR_LINE = Color.argb(78, 255, 255, 255);
    private static final int COLOR_TEXT = Color.rgb(245, 248, 252);
    private static final int COLOR_MUTED = Color.rgb(157, 172, 190);
    private static final int COLOR_ACCENT = Color.rgb(57, 211, 190);
    private static final int COLOR_WARNING = Color.rgb(245, 176, 78);

    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path path = new Path();
    private final RectF rect = new RectF();
    private AmpState state = AmpState.empty();
    private boolean enabled = true;

    public AmpVisualizerView(Context context) {
        super(context);
        init();
    }

    public AmpVisualizerView(Context context, AttributeSet attrs) {
        super(context, attrs);
        init();
    }

    private void init() {
        setMinimumHeight(dp(210));
    }

    public void setState(AmpState nextState, boolean nextEnabled) {
        state = nextState == null ? AmpState.empty() : nextState;
        enabled = nextEnabled;
        invalidate();
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        int width = MeasureSpec.getSize(widthMeasureSpec);
        int target = width < dp(760) ? dp(230) : dp(255);
        int height = resolveSize(target, heightMeasureSpec);
        setMeasuredDimension(width, height);
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        float w = getWidth();
        float h = getHeight();
        float alpha = enabled ? 1f : 0.38f;

        drawStage(canvas, w, h, alpha);
        drawSignalGlow(canvas, w, h, alpha);
        drawSpeakerMap(canvas, w, h, alpha);
        drawAmpCore(canvas, w, h, alpha);
        drawToneBars(canvas, w, h, alpha);
        drawBalanceFader(canvas, w, h, alpha);
        drawFooter(canvas, w, h, alpha);
    }

    private void drawStage(Canvas canvas, float w, float h, float alpha) {
        float radius = dp(8);
        rect.set(0, 0, w, h);
        paint.setStyle(Paint.Style.FILL);
        paint.setShader(new LinearGradient(0, 0, w, h,
                new int[]{COLOR_BG, Color.rgb(20, 25, 33), COLOR_BG},
                new float[]{0f, 0.55f, 1f}, Shader.TileMode.CLAMP));
        canvas.drawRoundRect(rect, radius, radius, paint);
        paint.setShader(null);

        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(dp(1));
        paint.setColor(applyAlpha(Color.WHITE, Math.round(16 * alpha)));
        canvas.drawRoundRect(rect, radius, radius, paint);
    }

    private void drawSignalGlow(Canvas canvas, float w, float h, float alpha) {
        float cx = w * 0.5f;
        float cy = h * 0.48f;
        float radius = Math.min(w, h) * 0.44f;
        paint.setStyle(Paint.Style.FILL);
        paint.setShader(new RadialGradient(cx, cy, radius,
                new int[]{applyAlpha(COLOR_ACCENT, Math.round(62 * alpha)),
                        applyAlpha(COLOR_ACCENT, Math.round(18 * alpha)),
                        Color.TRANSPARENT},
                new float[]{0f, 0.45f, 1f}, Shader.TileMode.CLAMP));
        canvas.drawCircle(cx, cy, radius, paint);
        paint.setShader(null);
    }

    private void drawSpeakerMap(Canvas canvas, float w, float h, float alpha) {
        float pad = Math.max(dp(18), w * 0.04f);
        float top = h * 0.18f;
        float bottom = h * 0.72f;
        float left = pad + dp(20);
        float right = w - pad - dp(20);
        float coreLeft = w * 0.39f;
        float coreRight = w * 0.61f;
        float coreTop = h * 0.31f;
        float coreBottom = h * 0.61f;

        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(dp(2));
        paint.setColor(applyAlpha(COLOR_LINE, Math.round(180 * alpha)));
        drawCable(canvas, left + dp(18), top + dp(13), coreLeft, coreTop + dp(12));
        drawCable(canvas, right - dp(18), top + dp(13), coreRight, coreTop + dp(12));
        drawCable(canvas, left + dp(18), bottom + dp(13), coreLeft, coreBottom - dp(12));
        drawCable(canvas, right - dp(18), bottom + dp(13), coreRight, coreBottom - dp(12));

        drawSpeaker(canvas, left, top, "FL", alpha);
        drawSpeaker(canvas, right, top, "FR", alpha);
        drawSpeaker(canvas, left, bottom, "RL", alpha);
        drawSpeaker(canvas, right, bottom, "RR", alpha);
    }

    private void drawCable(Canvas canvas, float x1, float y1, float x2, float y2) {
        path.reset();
        path.moveTo(x1, y1);
        path.cubicTo((x1 + x2) * 0.5f, y1, (x1 + x2) * 0.5f, y2, x2, y2);
        canvas.drawPath(path, paint);
    }

    private void drawSpeaker(Canvas canvas, float x, float y, String label, float alpha) {
        float size = dp(44);
        rect.set(x - size * 0.5f, y - size * 0.5f, x + size * 0.5f, y + size * 0.5f);
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(applyAlpha(COLOR_PANEL, Math.round(235 * alpha)));
        canvas.drawRoundRect(rect, dp(7), dp(7), paint);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(dp(2));
        paint.setColor(applyAlpha(COLOR_ACCENT, Math.round(135 * alpha)));
        canvas.drawRoundRect(rect, dp(7), dp(7), paint);

        paint.setStyle(Paint.Style.FILL);
        paint.setColor(applyAlpha(COLOR_ACCENT, Math.round(225 * alpha)));
        canvas.drawCircle(x, y, dp(10), paint);
        paint.setColor(applyAlpha(COLOR_BG, Math.round(255 * alpha)));
        canvas.drawCircle(x, y, dp(5), paint);
        drawText(canvas, label, x, y + size * 0.5f + dp(17), dp(12), COLOR_MUTED,
                Paint.Align.CENTER, alpha, true);
    }

    private void drawAmpCore(Canvas canvas, float w, float h, float alpha) {
        float coreW = Math.min(dp(360), w * 0.34f);
        float coreH = Math.min(dp(112), h * 0.42f);
        float cx = w * 0.5f;
        float cy = h * 0.46f;
        rect.set(cx - coreW * 0.5f, cy - coreH * 0.5f, cx + coreW * 0.5f, cy + coreH * 0.5f);

        paint.setStyle(Paint.Style.FILL);
        paint.setShader(new LinearGradient(rect.left, rect.top, rect.right, rect.bottom,
                new int[]{Color.rgb(13, 16, 22), Color.rgb(27, 34, 43), Color.rgb(12, 15, 20)},
                null, Shader.TileMode.CLAMP));
        canvas.drawRoundRect(rect, dp(12), dp(12), paint);
        paint.setShader(null);

        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(dp(1));
        paint.setColor(applyAlpha(COLOR_ACCENT, Math.round(115 * alpha)));
        canvas.drawRoundRect(rect, dp(12), dp(12), paint);

        float volume = clamp01(state.volume / 40f);
        float knobCx = rect.left + coreW * 0.22f;
        float knobCy = cy;
        float knobRadius = Math.min(dp(36), coreH * 0.32f);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeCap(Paint.Cap.ROUND);
        paint.setStrokeWidth(dp(7));
        paint.setColor(applyAlpha(Color.WHITE, Math.round(30 * alpha)));
        canvas.drawArc(knobCx - knobRadius, knobCy - knobRadius,
                knobCx + knobRadius, knobCy + knobRadius,
                135, 270, false, paint);
        paint.setColor(applyAlpha(COLOR_ACCENT, Math.round(230 * alpha)));
        canvas.drawArc(knobCx - knobRadius, knobCy - knobRadius,
                knobCx + knobRadius, knobCy + knobRadius,
                135, 270 * volume, false, paint);
        paint.setStrokeCap(Paint.Cap.BUTT);
        drawText(canvas, String.valueOf(state.volume), knobCx, knobCy + dp(5), dp(18),
                COLOR_TEXT, Paint.Align.CENTER, alpha, true);
        drawText(canvas, "VOL", knobCx, knobCy + dp(27), dp(10),
                COLOR_MUTED, Paint.Align.CENTER, alpha, true);

        float textX = rect.left + coreW * 0.42f;
        drawText(canvas, "AMP", textX, cy - dp(12), dp(22), COLOR_TEXT,
                Paint.Align.LEFT, alpha, true);
        drawText(canvas, enabled ? "баланс / фейдер" : "выключен",
                textX, cy + dp(16), dp(12), enabled ? COLOR_MUTED : COLOR_WARNING,
                Paint.Align.LEFT, alpha, false);

        float ledX = rect.right - dp(20);
        float ledY = rect.top + dp(18);
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(applyAlpha(enabled ? COLOR_ACCENT : COLOR_WARNING,
                Math.round(215 * alpha)));
        canvas.drawCircle(ledX, ledY, dp(5), paint);
    }

    private void drawToneBars(Canvas canvas, float w, float h, float alpha) {
        float startX = w * 0.5f - dp(92);
        float y = h * 0.77f;
        drawToneBar(canvas, startX, y, "B", state.bass - 10, alpha);
        drawToneBar(canvas, startX + dp(92), y, "M", state.mid - 10, alpha);
        drawToneBar(canvas, startX + dp(184), y, "T", state.treble - 10, alpha);
    }

    private void drawToneBar(Canvas canvas, float x, float y, String label, int value, float alpha) {
        float barW = dp(58);
        float base = y + dp(20);
        float top = y - dp(36);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(dp(5));
        paint.setStrokeCap(Paint.Cap.ROUND);
        paint.setColor(applyAlpha(Color.WHITE, Math.round(38 * alpha)));
        canvas.drawLine(x, base, x, top, paint);
        float pos = base - (base - top) * ((value + 10) / 20f);
        paint.setColor(applyAlpha(value >= 0 ? COLOR_ACCENT : COLOR_WARNING, Math.round(215 * alpha)));
        canvas.drawLine(x, base, x, pos, paint);
        paint.setStrokeCap(Paint.Cap.BUTT);
        drawText(canvas, label, x, base + dp(18), dp(12), COLOR_MUTED, Paint.Align.CENTER, alpha, true);
        drawText(canvas, signed(value), x + barW * 0.38f, pos + dp(5), dp(11),
                COLOR_TEXT, Paint.Align.LEFT, alpha, true);
    }

    private void drawBalanceFader(Canvas canvas, float w, float h, float alpha) {
        float coreW = Math.min(dp(360), w * 0.34f);
        float coreH = Math.min(dp(112), h * 0.42f);
        float cx = w * 0.5f;
        float cy = h * 0.46f;
        float coreRight = cx + coreW * 0.5f;
        float box = Math.min(dp(52), coreH * 0.46f);
        float mapCx = coreRight - dp(62);
        float mapCy = cy;
        float balance = clampSigned(state.balance - 10);
        float fader = clampSigned(state.fader - 10);
        float dotX = mapCx + balance / 10f * box * 0.34f;
        float dotY = mapCy - fader / 10f * box * 0.34f;

        rect.set(mapCx - box * 0.5f, mapCy - box * 0.5f, mapCx + box * 0.5f, mapCy + box * 0.5f);
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(applyAlpha(Color.WHITE, Math.round(12 * alpha)));
        canvas.drawRoundRect(rect, dp(6), dp(6), paint);

        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(dp(1));
        paint.setColor(applyAlpha(Color.WHITE, Math.round(44 * alpha)));
        canvas.drawLine(rect.left + dp(7), mapCy, rect.right - dp(7), mapCy, paint);
        canvas.drawLine(mapCx, rect.top + dp(7), mapCx, rect.bottom - dp(7), paint);

        paint.setStyle(Paint.Style.FILL);
        paint.setColor(applyAlpha(COLOR_WARNING, Math.round(210 * alpha)));
        canvas.drawCircle(dotX, dotY, dp(5), paint);
    }

    private void drawFooter(Canvas canvas, float w, float h, float alpha) {
        String text = state.updatedAt == 0L ? "ожидание ответа усилителя" : "mode " + state.mode;
        drawText(canvas, text, w - dp(18), h - dp(16), dp(12), COLOR_MUTED,
                Paint.Align.RIGHT, alpha, false);
    }

    private void drawText(Canvas canvas, String text, float x, float y, float size, int color,
                          Paint.Align align, float alpha, boolean bold) {
        paint.setShader(null);
        paint.setStyle(Paint.Style.FILL);
        paint.setTextAlign(align);
        paint.setTextSize(size);
        paint.setFakeBoldText(bold);
        paint.setColor(applyAlpha(color, Math.round(Color.alpha(color) * alpha)));
        canvas.drawText(text, x, y, paint);
        paint.setFakeBoldText(false);
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private static String signed(int value) {
        return value > 0 ? "+" + value : String.valueOf(value);
    }

    private static float clamp01(float value) {
        return Math.max(0f, Math.min(1f, value));
    }

    private static float clampSigned(int value) {
        return Math.max(-10f, Math.min(10f, value));
    }

    private static int applyAlpha(int color, int alpha) {
        return Color.argb(Math.max(0, Math.min(255, alpha)),
                Color.red(color), Color.green(color), Color.blue(color));
    }
}
