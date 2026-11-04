package kia.app.sunroof;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Shader;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.animation.AccelerateDecelerateInterpolator;

import kia.app.R;

/** The approved miniature, drawn in screen pixels without density-dependent scaling. */
public final class SunroofOverlayView extends View {
    static final float SCREEN_WIDTH_FRACTION = .161f;
    static final long TRANSIENT_HOLD_MS = 3_000L;
    static final long FADE_MS = 400L;
    static final long TRANSIENT_DURATION_MS = TRANSIENT_HOLD_MS + FADE_MS;
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private final RectF carBounds = new RectF();
    private final RectF roofBounds = new RectF();
    private final RectF glassBounds = new RectF();
    private final Matrix glassGradientMatrix = new Matrix();
    private final LinearGradient glassGradient = new LinearGradient(0f, 0f, 1f, 1f,
            new int[]{0xff273139, 0xff0f171d, 0xff202c35},
            new float[]{0f, .68f, 1f}, Shader.TileMode.CLAMP);
    private final Bitmap car;
    private ValueAnimator movement;
    private ValueAnimator appearance;
    private ValueAnimator pulse;
    private float roofOpen;
    private float warningPulse = 1f;
    private boolean warning;
    private Runnable dismissAction;

    public SunroofOverlayView(Context context) {
        super(context);
        BitmapFactory.Options options = new BitmapFactory.Options();
        options.inScaled = false;
        car = BitmapFactory.decodeResource(getResources(), R.drawable.sunroof_car_top, options);
        setWillNotDraw(false);
        setBackgroundColor(Color.TRANSPARENT);
        setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
    }

    public void present(SunroofAlertPolicy.Kind kind, Runnable dismiss) {
        stopAnimations();
        dismissAction = dismiss;
        warning = kind == SunroofAlertPolicy.Kind.PARKING_WARNING;
        setImportantForAccessibility(warning ? IMPORTANT_FOR_ACCESSIBILITY_YES
                : IMPORTANT_FOR_ACCESSIBILITY_NO);
        setContentDescription(warning ? "Люк открыт при выключенном зажигании. Нажмите, чтобы закрыть предупреждение"
                : kind == SunroofAlertPolicy.Kind.OPENING ? "Люк открывается" : "Люк закрывается");
        setFocusable(warning);
        setFocusableInTouchMode(warning);
        setClickable(warning);
        setAlpha(1f);
        warningPulse = 1f;
        roofOpen = kind == SunroofAlertPolicy.Kind.CLOSING || warning ? 1f : 0f;
        if (warning) {
            if (ValueAnimator.areAnimatorsEnabled()) {
                pulse = ValueAnimator.ofFloat(.72f, 1f);
                pulse.setDuration(1_200L);
                pulse.setRepeatCount(ValueAnimator.INFINITE);
                pulse.setRepeatMode(ValueAnimator.REVERSE);
                pulse.addUpdateListener(animation -> {
                    warningPulse = (float) animation.getAnimatedValue();
                    invalidate();
                });
                pulse.start();
            }
            requestFocus();
        } else {
            float target = kind == SunroofAlertPolicy.Kind.OPENING ? 1f : 0f;
            if (ValueAnimator.areAnimatorsEnabled()) {
                movement = ValueAnimator.ofFloat(roofOpen, target);
                movement.setStartDelay(150L);
                movement.setDuration(900L);
                movement.setInterpolator(new AccelerateDecelerateInterpolator());
                movement.addUpdateListener(animation -> {
                    roofOpen = (float) animation.getAnimatedValue();
                    invalidate();
                });
                movement.start();
                appearance = ValueAnimator.ofFloat(1f, 0f);
                appearance.setStartDelay(TRANSIENT_HOLD_MS);
                appearance.setDuration(FADE_MS);
                appearance.addUpdateListener(animation -> setAlpha((float) animation.getAnimatedValue()));
                appearance.start();
            } else {
                roofOpen = target;
            }
        }
        invalidate();
    }

    public void stopAnimations() {
        if (movement != null) movement.cancel();
        if (appearance != null) appearance.cancel();
        if (pulse != null) pulse.cancel();
        movement = null;
        appearance = null;
        pulse = null;
        animate().cancel();
    }

    @Override protected void onDetachedFromWindow() {
        stopAnimations();
        dismissAction = null;
        super.onDetachedFromWindow();
    }

    @Override public boolean onTouchEvent(MotionEvent event) {
        if (!warning) return false;
        if (event.getActionMasked() == MotionEvent.ACTION_DOWN) performClick();
        return true;
    }

    @Override public boolean performClick() {
        super.performClick();
        if (!warning) return false;
        acknowledge();
        return true;
    }

    @Override public boolean dispatchKeyEvent(KeyEvent event) {
        if (warning && event.getAction() == KeyEvent.ACTION_DOWN) acknowledge();
        // Unhandled keys retain Android's normal fallback (for example volume adjustment).
        return super.dispatchKeyEvent(event);
    }

    private void acknowledge() {
        Runnable action = dismissAction;
        if (action != null) action.run();
    }

    @Override protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        if (car == null || getWidth() <= 0 || getHeight() <= 0) return;
        float width = getWidth() * SCREEN_WIDTH_FRACTION;
        float height = width * 1.5f;
        float left = (getWidth() - width) * .5f;
        float top = (getHeight() - height) * .5f;
        carBounds.set(left, top, left + width, top + height);
        paint.setShader(null);
        paint.setStyle(Paint.Style.FILL);
        paint.setAlpha(255);
        canvas.drawBitmap(car, null, carBounds, paint);

        roofBounds.set(left + width * .353f, top + height * .456f,
                left + width * .653f, top + height * .574f);
        float corner = roofBounds.width() * .10f;
        paint.setColor(Color.argb(Math.round(255f * .24f * roofOpen * warningPulse), 255, 35, 55));
        canvas.drawRoundRect(roofBounds, corner, corner, paint);

        glassBounds.set(roofBounds);
        glassBounds.offset(0f, roofBounds.height() * 1.10f * roofOpen);
        glassGradientMatrix.setScale(glassBounds.width(), glassBounds.height());
        glassGradientMatrix.postTranslate(glassBounds.left, glassBounds.top);
        glassGradient.setLocalMatrix(glassGradientMatrix);
        paint.setShader(glassGradient);
        paint.setColor(Color.WHITE);
        canvas.drawRoundRect(glassBounds, corner, corner, paint);
        paint.setShader(null);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(Math.max(.6f, width / 230f));
        paint.setColor(0x8096a3aa);
        canvas.drawRoundRect(glassBounds, corner, corner, paint);
        paint.setStyle(Paint.Style.FILL);
    }
}
