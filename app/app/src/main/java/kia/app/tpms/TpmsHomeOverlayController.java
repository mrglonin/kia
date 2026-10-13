package kia.app.tpms;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.Point;
import android.graphics.Typeface;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.provider.Settings;
import android.view.Display;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.WindowManager;

import java.util.concurrent.atomic.AtomicLong;

import kia.app.core.AppLog;

/** KIA's own card above Home; this does not modify the native Home/3D widget. */
public final class TpmsHomeOverlayController {
    private static final String POSITION_PREFS = "kia_tpms_home_overlay_position";
    private static final int WIDTH_DP = 286;
    private static final int HEIGHT_DP = 180;
    private static TpmsHomeOverlayController instance;

    private final Context app;
    private final Context windowContext;
    private final WindowManager windowManager;
    private final SharedPreferences positions;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final AtomicLong updateGeneration = new AtomicLong();
    private TpmsHomeOverlayPolicy.Snapshot snapshot;
    private CardView overlay;
    private WindowManager.LayoutParams params;
    private float xFraction;
    private float yFraction;
    private boolean failureLogged;

    private final Runnable snapshotTimeout = () -> {
        if (snapshot != null && !snapshot.visible(SystemClock.elapsedRealtime(), true)) hide();
    };

    private TpmsHomeOverlayController(Context context) {
        app = context.getApplicationContext();
        windowContext = createWindowContext(app);
        windowManager = (WindowManager) windowContext.getSystemService(Context.WINDOW_SERVICE);
        positions = app.getSharedPreferences(POSITION_PREFS, Context.MODE_PRIVATE);
        xFraction = positions.getFloat("x_fraction", 1f);
        yFraction = positions.getFloat("y_fraction", 0f);
    }

    public static synchronized TpmsHomeOverlayController get(Context context) {
        if (instance == null) instance = new TpmsHomeOverlayController(context);
        return instance;
    }

    /**
     * Refresh at least once per second while enabled, using the USB-only projector.
     * The caller verifies Home is foreground and suppresses camera/alert surfaces.
     * Unknown foreground state, disabled setting, or camera must pass homeVisible=false.
     */
    public void update(TeyesTpmsWidgetPolicy.WheelValue[] wheels,
                       boolean connected, boolean homeVisible) {
        long generation = updateGeneration.get();
        TpmsHomeOverlayPolicy.Snapshot next = TpmsHomeOverlayPolicy.capture(
                wheels, connected, homeVisible, SystemClock.elapsedRealtime());
        runOnMain(() -> {
            if (generation != updateGeneration.get()) return;
            snapshot = next;
            handler.removeCallbacks(snapshotTimeout);
            boolean allowed = Build.VERSION.SDK_INT < 23 || Settings.canDrawOverlays(app);
            if (!next.visible(SystemClock.elapsedRealtime(), allowed)) {
                hide();
                return;
            }
            show(next);
            long remaining = TpmsHomeOverlayPolicy.SNAPSHOT_TIMEOUT_MS
                    - (SystemClock.elapsedRealtime() - next.capturedAtElapsed);
            handler.postDelayed(snapshotTimeout, Math.max(1L, remaining));
        });
    }

    public void stop() {
        // A previously queued worker-thread update must not reopen a stopped card.
        updateGeneration.incrementAndGet();
        runOnMain(() -> {
            handler.removeCallbacks(snapshotTimeout);
            snapshot = null;
            hide();
        });
    }

    private void show(TpmsHomeOverlayPolicy.Snapshot value) {
        if (windowManager == null) return;
        WindowManager.LayoutParams next = createLayoutParams();
        if (next == null) {
            hide();
            return;
        }
        if (overlay == null) {
            CardView card = new CardView(windowContext);
            params = next;
            card.bind(value);
            try {
                windowManager.addView(card, params);
                overlay = card;
                failureLogged = false;
            } catch (RuntimeException e) {
                params = null;
                logFailure(e);
            }
        } else {
            overlay.bind(value);
            if (params.width != next.width || params.height != next.height
                    || params.x != next.x || params.y != next.y) {
                params = next;
                updatePosition();
            }
        }
    }

    private void hide() {
        CardView current = overlay;
        overlay = null;
        params = null;
        if (current == null || windowManager == null) return;
        try {
            windowManager.removeView(current);
        } catch (RuntimeException e) {
            logFailure(e);
        }
    }

    private WindowManager.LayoutParams createLayoutParams() {
        Point display = displaySize();
        int margin = dp(12);
        int safeTop = dp(132);
        float scale = Math.min(1f, Math.min((display.x - margin * 2) / (float) dp(WIDTH_DP),
                (display.y - safeTop - margin) / (float) dp(HEIGHT_DP)));
        // Prefer hiding over covering the existing warning strip or drawing an
        // unreadable card when a transient/rotated display is exceptionally small.
        if (scale < 0.65f) return null;
        int width = Math.round(dp(WIDTH_DP) * scale);
        int height = Math.round(dp(HEIGHT_DP) * scale);
        int flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN;
        WindowManager.LayoutParams out = new WindowManager.LayoutParams(
                width, height, overlayWindowType(), flags, PixelFormat.TRANSLUCENT);
        out.gravity = Gravity.TOP | Gravity.LEFT;
        out.setTitle("KIA TPMS Home");
        int minX = Math.min(margin, Math.max(0, display.x - width));
        int maxX = Math.max(minX, display.x - width - margin);
        int maxY = Math.max(0, display.y - height - margin);
        int minY = Math.min(safeTop, maxY); // Existing warning strip occupies 124 dp.
        out.x = TpmsHomeOverlayPolicy.position(xFraction, minX, maxX);
        out.y = TpmsHomeOverlayPolicy.position(yFraction, minY, maxY);
        return out;
    }

    private void moveTo(int x, int y) {
        if (params == null) return;
        Point display = displaySize();
        int margin = dp(12);
        int minX = Math.min(margin, Math.max(0, display.x - params.width));
        int maxX = Math.max(minX, display.x - params.width - margin);
        int maxY = Math.max(0, display.y - params.height - margin);
        int minY = Math.min(dp(132), maxY);
        xFraction = TpmsHomeOverlayPolicy.fraction(x, minX, maxX);
        yFraction = TpmsHomeOverlayPolicy.fraction(y, minY, maxY);
        params.x = TpmsHomeOverlayPolicy.position(xFraction, minX, maxX);
        params.y = TpmsHomeOverlayPolicy.position(yFraction, minY, maxY);
        updatePosition();
    }

    private void updatePosition() {
        if (overlay == null || params == null || windowManager == null) return;
        try {
            windowManager.updateViewLayout(overlay, params);
        } catch (RuntimeException e) {
            logFailure(e);
            hide();
        }
    }

    private void savePosition() {
        positions.edit().putFloat("x_fraction", xFraction).putFloat("y_fraction", yFraction).apply();
    }

    private Point displaySize() {
        if (Build.VERSION.SDK_INT >= 30 && windowManager != null) {
            // Maximum metrics describe the display area, independent of this
            // overlay's own bounds after it has been attached.
            android.graphics.Rect bounds = windowManager.getMaximumWindowMetrics().getBounds();
            return new Point(bounds.width(), bounds.height());
        }
        Point size = new Point();
        Display display = windowManager == null ? null : windowManager.getDefaultDisplay();
        if (display != null) display.getRealSize(size);
        if (size.x <= 0 || size.y <= 0) {
            size.x = app.getResources().getDisplayMetrics().widthPixels;
            size.y = app.getResources().getDisplayMetrics().heightPixels;
        }
        return size;
    }

    private void logFailure(RuntimeException e) {
        if (failureLogged) return;
        failureLogged = true;
        AppLog.line(app, "TPMS Home card: " + e.getClass().getSimpleName());
    }

    private void runOnMain(Runnable action) {
        if (Looper.myLooper() == Looper.getMainLooper()) action.run();
        else handler.post(action);
    }

    private static Context createWindowContext(Context context) {
        if (Build.VERSION.SDK_INT < 30) return context;
        try {
            WindowManager wm = (WindowManager) context.getSystemService(Context.WINDOW_SERVICE);
            Display display = wm == null ? null : wm.getDefaultDisplay();
            Context displayContext = display == null ? context : context.createDisplayContext(display);
            return displayContext.createWindowContext(overlayWindowType(), null);
        } catch (RuntimeException ignored) {
            return context;
        }
    }

    private static int overlayWindowType() {
        return Build.VERSION.SDK_INT >= 26
                ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                : WindowManager.LayoutParams.TYPE_PHONE;
    }

    private int dp(int value) {
        return Math.round(value * windowContext.getResources().getDisplayMetrics().density);
    }

    private final class CardView extends View {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final String[] labels = {"ПЕРЕДНЕЕ ЛЕВОЕ", "ПЕРЕДНЕЕ ПРАВОЕ",
                "ЗАДНЕЕ ЛЕВОЕ", "ЗАДНЕЕ ПРАВОЕ"};
        private TpmsHomeOverlayPolicy.Snapshot value;
        private float downX;
        private float downY;
        private int initialX;
        private int initialY;
        private boolean dragging;
        private final int touchSlop;

        CardView(Context context) {
            super(context);
            touchSlop = ViewConfiguration.get(context).getScaledTouchSlop();
            setFocusable(false);
            setClickable(true);
            setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_YES);
        }

        void bind(TpmsHomeOverlayPolicy.Snapshot next) {
            value = next;
            StringBuilder description = new StringBuilder("KIA. Давление и температура шин. ");
            for (int i = 0; i < labels.length; i++) {
                TpmsHomeOverlayPolicy.Wheel wheel = next.wheels[i];
                description.append(labels[i]).append(": ").append(wheel.pressure).append(' ')
                        .append(wheel.pressureUnit).append(", ").append(wheel.temperature)
                        .append(' ').append(wheel.temperatureUnit).append(". ");
            }
            description.append("Перетащите карточку, чтобы изменить положение.");
            setContentDescription(description);
            invalidate();
        }

        @Override
        protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            if (value == null) return;
            // A fixed logical canvas keeps the complete card readable and unclipped
            // if Android constrains the window on a smaller display.
            canvas.save();
            canvas.scale(getWidth() / (float) WIDTH_DP, getHeight() / (float) HEIGHT_DP);
            paint.setStyle(Paint.Style.FILL);
            paint.setColor(Color.argb(240, 15, 24, 35));
            canvas.drawRoundRect(1f, 1f, WIDTH_DP - 1f, HEIGHT_DP - 1f, 14f, 14f, paint);
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(1f);
            paint.setColor(Color.argb(110, 119, 160, 183));
            canvas.drawRoundRect(1f, 1f, WIDTH_DP - 1f, HEIGHT_DP - 1f, 14f, 14f, paint);
            paint.setStyle(Paint.Style.FILL);
            text(canvas, "KIA · ШИНЫ", 13f, 22f, 12f, Color.rgb(218, 234, 242), true,
                    Paint.Align.LEFT);
            String status = !value.connected ? "USB отключён"
                    : value.hasValues() ? "USB" : "Нет данных";
            text(canvas, status, WIDTH_DP - 28f, 22f, 9f, Color.rgb(151, 177, 191), false,
                    Paint.Align.RIGHT);
            paint.setColor(Color.rgb(151, 177, 191));
            for (int row = 0; row < 3; row++) {
                canvas.drawCircle(WIDTH_DP - 15f, 13f + row * 4f, 1f, paint);
                canvas.drawCircle(WIDTH_DP - 11f, 13f + row * 4f, 1f, paint);
            }
            paint.setColor(Color.argb(62, 140, 171, 187));
            paint.setStrokeWidth(1f);
            canvas.drawLine(12f, 32f, WIDTH_DP - 12f, 32f, paint);
            canvas.drawLine(WIDTH_DP / 2f, 41f, WIDTH_DP / 2f, HEIGHT_DP - 12f, paint);
            canvas.drawLine(12f, 105f, WIDTH_DP - 12f, 105f, paint);
            for (int i = 0; i < labels.length; i++) {
                float center = (i % 2 == 0 ? 0.25f : 0.75f) * WIDTH_DP;
                float top = 36f + (i / 2) * 73f;
                TpmsHomeOverlayPolicy.Wheel wheel = value.wheels[i];
                int mainColor = wheel.alert ? Color.rgb(255, 130, 124)
                        : Color.rgb(112, 225, 210);
                if (TpmsHomeOverlayPolicy.UNKNOWN.equals(wheel.pressure)) {
                    mainColor = Color.rgb(150, 169, 181);
                }
                text(canvas, labels[i], center, top + 12f, 9f,
                        Color.rgb(158, 183, 197), false, Paint.Align.CENTER);
                paint.setTypeface(Typeface.DEFAULT_BOLD);
                paint.setTextSize(23f);
                float numberWidth = paint.measureText(wheel.pressure);
                paint.setTypeface(Typeface.DEFAULT);
                paint.setTextSize(10f);
                float unitWidth = paint.measureText(wheel.pressureUnit);
                float left = center - (numberWidth + unitWidth + 5f) / 2f;
                text(canvas, wheel.pressure, left, top + 40f, 23f, mainColor, true,
                        Paint.Align.LEFT);
                text(canvas, wheel.pressureUnit, left + numberWidth + 5f, top + 40f,
                        10f, Color.rgb(159, 184, 197), false, Paint.Align.LEFT);
                text(canvas, wheel.temperature + " " + wheel.temperatureUnit, center,
                        top + 59f, 13f, Color.rgb(217, 230, 237), false, Paint.Align.CENTER);
            }
            canvas.restore();
        }

        private void text(Canvas canvas, String text, float x, float y, float size, int color,
                          boolean bold, Paint.Align align) {
            paint.setStyle(Paint.Style.FILL);
            paint.setColor(color);
            paint.setTextSize(size);
            paint.setTypeface(bold ? Typeface.DEFAULT_BOLD : Typeface.DEFAULT);
            paint.setTextAlign(align);
            canvas.drawText(text, x, y, paint);
        }

        @Override
        public boolean onTouchEvent(MotionEvent event) {
            if (params == null) return false;
            switch (event.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    downX = event.getRawX();
                    downY = event.getRawY();
                    initialX = params.x;
                    initialY = params.y;
                    dragging = false;
                    return true;
                case MotionEvent.ACTION_MOVE:
                    float dx = event.getRawX() - downX;
                    float dy = event.getRawY() - downY;
                    if (!dragging && Math.hypot(dx, dy) > touchSlop) dragging = true;
                    if (dragging) moveTo(initialX + Math.round(dx), initialY + Math.round(dy));
                    return true;
                case MotionEvent.ACTION_UP:
                    if (dragging) savePosition();
                    else performClick();
                    dragging = false;
                    return true;
                case MotionEvent.ACTION_CANCEL:
                    if (dragging) savePosition();
                    dragging = false;
                    return true;
                default:
                    return true;
            }
        }

        @Override
        public boolean performClick() {
            super.performClick();
            return true;
        }
    }
}
