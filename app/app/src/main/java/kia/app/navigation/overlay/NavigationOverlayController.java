package kia.app.navigation.overlay;

import android.annotation.SuppressLint;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.style.StyleSpan;
import android.util.DisplayMetrics;
import android.util.TypedValue;
import android.view.Display;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.LinearLayout;
import android.widget.TextView;

import kia.app.core.AppIds;
import kia.app.core.AppLog;
import kia.app.core.StateStore;
import kia.app.core.settings.AppSettings;
import kia.app.navigation.domain.NavigationFeature;
import kia.app.navigation.domain.NavigationModeSettings;

public final class NavigationOverlayController {
    private static NavigationOverlayController instance;

    private final Context app;
    private final Context windowContext;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final WindowManager windowManager;
    private LinearLayout overlay;
    private TextView closeButton;
    private TextView modeText;
    private TextView statusText;
    private TextView txText;
    private CompassMiniView compassView;
    private BroadcastReceiver receiver;
    private boolean receiverRegistered;
    private boolean permissionLogged;

    private final Runnable refreshTick = new Runnable() {
        @Override
        public void run() {
            updateText();
            if (overlay != null) handler.postDelayed(this, 1000L);
        }
    };

    private NavigationOverlayController(Context context) {
        this.app = context.getApplicationContext();
        this.windowContext = createWindowContext(app);
        this.windowManager = (WindowManager) windowContext.getSystemService(Context.WINDOW_SERVICE);
    }

    public static synchronized NavigationOverlayController get(Context context) {
        if (instance == null) instance = new NavigationOverlayController(context);
        return instance;
    }

    public synchronized void start() {
        registerReceiver();
        apply();
    }

    public synchronized void apply() {
        if (!AppSettings.navOverlayEnabled(app)) {
            hide();
            return;
        }
        if (!canDrawOverlays(app)) {
            hide();
            if (!permissionLogged) {
                permissionLogged = true;
                AppLog.line(app, "Nav overlay: нужно разрешение поверх окон");
            }
            return;
        }
        permissionLogged = false;
        show();
    }

    public synchronized void stop() {
        hide();
        unregisterReceiver();
    }

    public static boolean canDrawOverlays(Context context) {
        return Build.VERSION.SDK_INT < 23 || Settings.canDrawOverlays(context);
    }

    private void show() {
        if (windowManager == null) return;
        if (overlay == null) {
            overlay = createOverlay();
            try {
                windowManager.addView(overlay, layoutParams());
                showCloseButton();
            } catch (Exception e) {
                hideCloseButton();
                overlay = null;
                AppLog.line(app, "Nav overlay: add failed " + e.getClass().getSimpleName());
                return;
            }
            handler.removeCallbacks(refreshTick);
            handler.post(refreshTick);
        } else {
            try {
                windowManager.updateViewLayout(overlay, layoutParams());
                showCloseButton();
            } catch (Exception ignored) {
            }
        }
        updateText();
    }

    private void hide() {
        handler.removeCallbacks(refreshTick);
        if (overlay == null || windowManager == null) {
            hideCloseButton();
            overlay = null;
            statusText = null;
            return;
        }
        try {
            windowManager.removeView(overlay);
        } catch (Exception ignored) {
        }
        hideCloseButton();
        overlay = null;
        modeText = null;
        statusText = null;
        txText = null;
        compassView = null;
    }

    private void showCloseButton() {
        if (windowManager == null) return;
        if (closeButton == null) {
            closeButton = closeButton("Отключить", Color.argb(220, 18, 23, 31));
            try {
                windowManager.addView(closeButton, closeButtonLayoutParams());
            } catch (Exception e) {
                closeButton = null;
                AppLog.line(app, "Nav overlay close: add failed " + e.getClass().getSimpleName());
            }
            return;
        }
        try {
            windowManager.updateViewLayout(closeButton, closeButtonLayoutParams());
        } catch (Exception ignored) {
        }
    }

    private void hideCloseButton() {
        if (closeButton == null || windowManager == null) {
            closeButton = null;
            return;
        }
        try {
            windowManager.removeView(closeButton);
        } catch (Exception ignored) {
        }
        closeButton = null;
    }

    private TextView closeButton(String text, int color) {
        TextView button = new TextView(windowContext);
        button.setText(text);
        button.setTextColor(Color.WHITE);
        button.setTextSize(12f);
        button.setTypeface(Typeface.DEFAULT_BOLD);
        button.setIncludeFontPadding(false);
        button.setGravity(Gravity.CENTER);
        button.setPadding(dp(10), dp(7), dp(10), dp(7));
        button.setBackground(closeButtonBackground(color));
        button.setClickable(true);
        button.setFocusable(false);
        button.setOnClickListener(v -> disableOverlayFromButton());
        return button;
    }

    private void disableOverlayFromButton() {
        AppSettings.setNavDebugVisible(app, false);
        AppSettings.setNavOverlayEnabled(app, false);
        AppLog.line(app, "Nav overlay: disabled from overlay button");
        app.sendBroadcast(new Intent(AppIds.ACTION_STATE_CHANGED).setPackage(AppIds.PACKAGE));
        hide();
    }

    private LinearLayout createOverlay() {
        DisplayMetrics metrics = displayMetrics();
        int panelWidth = Math.max(1, Math.round(overlayWidth(metrics) * 0.5f));
        int padX = Math.max(dp(6), Math.round(panelWidth * 0.025f));
        int padY = Math.max(dp(5), Math.round(metrics.heightPixels * 0.012f));
        float modePx = responsiveTextPx(metrics, 0.042f, 17, 25);
        float bodyPx = responsiveTextPx(metrics, 0.038f, 15, 22);

        LinearLayout root = new LinearLayout(windowContext);
        root.setOrientation(LinearLayout.HORIZONTAL);
        root.setGravity(Gravity.START);

        LinearLayout left = panel(redBackground(), padX, padY);
        LinearLayout right = panel(greenBackground(), padX, padY);

        LinearLayout modeRow = new LinearLayout(windowContext);
        modeRow.setOrientation(LinearLayout.HORIZONTAL);
        modeRow.setGravity(Gravity.CENTER_VERTICAL);

        modeText = new TextView(windowContext);
        modeText.setTextColor(Color.WHITE);
        modeText.setTextSize(TypedValue.COMPLEX_UNIT_PX, modePx);
        modeText.setIncludeFontPadding(false);
        modeText.setGravity(Gravity.START | Gravity.CENTER_VERTICAL);
        modeRow.addView(modeText, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        compassView = new CompassMiniView(windowContext);
        int compassSize = Math.max(dp(24), Math.min(dp(42), Math.round(panelWidth * 0.075f)));
        LinearLayout.LayoutParams compassLp = new LinearLayout.LayoutParams(compassSize, compassSize);
        compassLp.setMargins(dp(8), 0, 0, 0);
        modeRow.addView(compassView, compassLp);
        left.addView(modeRow, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        statusText = new TextView(windowContext);
        statusText.setTextColor(Color.WHITE);
        statusText.setTextSize(TypedValue.COMPLEX_UNIT_PX, bodyPx);
        statusText.setLineSpacing(0f, 0.95f);
        statusText.setIncludeFontPadding(false);
        statusText.setGravity(Gravity.START);
        statusText.setSingleLine(false);
        if (Build.VERSION.SDK_INT >= 26) {
            statusText.setAutoSizeTextTypeUniformWithConfiguration(
                    Math.max(1, Math.round(bodyPx * 0.72f)),
                    Math.max(2, Math.round(bodyPx)),
                    1,
                    TypedValue.COMPLEX_UNIT_PX);
        }
        LinearLayout.LayoutParams textLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        textLp.setMargins(0, Math.max(dp(4), Math.round(metrics.heightPixels * 0.006f)), 0, 0);
        left.addView(statusText, textLp);

        TextView txHeader = new TextView(windowContext);
        txHeader.setTextColor(Color.WHITE);
        txHeader.setTextSize(TypedValue.COMPLEX_UNIT_PX, modePx);
        txHeader.setIncludeFontPadding(false);
        txHeader.setGravity(Gravity.START | Gravity.CENTER_VERTICAL);
        txHeader.setText("Приборка");
        right.addView(txHeader, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        txText = new TextView(windowContext);
        txText.setTextColor(Color.WHITE);
        txText.setTextSize(TypedValue.COMPLEX_UNIT_PX, bodyPx);
        txText.setLineSpacing(0f, 0.95f);
        txText.setIncludeFontPadding(false);
        txText.setGravity(Gravity.START);
        txText.setSingleLine(false);
        if (Build.VERSION.SDK_INT >= 26) {
            txText.setAutoSizeTextTypeUniformWithConfiguration(
                    Math.max(1, Math.round(bodyPx * 0.66f)),
                    Math.max(2, Math.round(bodyPx)),
                    1,
                    TypedValue.COMPLEX_UNIT_PX);
        }
        LinearLayout.LayoutParams txLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        txLp.setMargins(0, Math.max(dp(4), Math.round(metrics.heightPixels * 0.006f)), 0, 0);
        right.addView(txText, txLp);

        root.addView(left, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.MATCH_PARENT, 1f));
        root.addView(right, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.MATCH_PARENT, 1f));
        return root;
    }

    private WindowManager.LayoutParams layoutParams() {
        DisplayMetrics metrics = displayMetrics();
        int flags = WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                | WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN;
        WindowManager.LayoutParams params = new WindowManager.LayoutParams(
                overlayWidth(metrics),
                ViewGroup.LayoutParams.MATCH_PARENT,
                overlayWindowType(),
                flags,
                PixelFormat.TRANSLUCENT);
        params.gravity = Gravity.TOP | Gravity.START;
        params.x = 0;
        params.y = 0;
        return params;
    }

    private WindowManager.LayoutParams closeButtonLayoutParams() {
        int flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN;
        WindowManager.LayoutParams params = new WindowManager.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                dp(34),
                overlayWindowType(),
                flags,
                PixelFormat.TRANSLUCENT);
        params.gravity = Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL;
        params.x = 0;
        params.y = dp(12);
        return params;
    }

    private void updateText() {
        if (statusText == null || modeText == null || txText == null) return;
        boolean finishDirection = NavigationModeSettings.isFinishDirection(app);
        modeText.setText("Режим: " + navigationModeLabel());
        int step = NavigationFeature.get(app).finishDirectionOverlayStep();
        if (compassView != null) {
            compassView.setVisibility(finishDirection ? View.VISIBLE : View.GONE);
            compassView.setStep(step);
        }
        statusText.setText(styledOverlayDetails(StateStore.navigation().overlayDetails()));
        txText.setText(StateStore.navigation().clusterTxDetails(
                NavigationModeSettings.isTbt(app), finishDirection));
    }

    private static CharSequence styledOverlayDetails(String text) {
        SpannableStringBuilder out = new SpannableStringBuilder(text == null ? "" : text);
        boldLine(out, "Манёвр");
        boldLine(out, "Микра");
        boldLine(out, "Серая дорога");
        boldLine(out, "События");
        return out;
    }

    private static void boldLine(SpannableStringBuilder text, String line) {
        String all = text.toString();
        int start = all.indexOf(line);
        while (start >= 0) {
            int end = start + line.length();
            boolean startsLine = start == 0 || all.charAt(start - 1) == '\n';
            boolean endsLine = end == all.length() || all.charAt(end) == '\n';
            if (startsLine && endsLine) {
                text.setSpan(new StyleSpan(Typeface.BOLD), start, end,
                        Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            }
            start = all.indexOf(line, end);
        }
    }

    @SuppressLint("UnspecifiedRegisterReceiverFlag")
    private void registerReceiver() {
        if (receiverRegistered) return;
        receiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                apply();
            }
        };
        IntentFilter filter = new IntentFilter(AppIds.ACTION_STATE_CHANGED);
        if (Build.VERSION.SDK_INT >= 33) {
            app.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED);
        } else {
            app.registerReceiver(receiver, filter);
        }
        receiverRegistered = true;
    }

    private void unregisterReceiver() {
        if (!receiverRegistered) return;
        try {
            app.unregisterReceiver(receiver);
        } catch (Exception ignored) {
        }
        receiverRegistered = false;
        receiver = null;
    }

    private GradientDrawable redBackground() {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(Color.argb(170, 170, 0, 0));
        return drawable;
    }

    private GradientDrawable greenBackground() {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(Color.argb(170, 0, 120, 0));
        return drawable;
    }

    private GradientDrawable closeButtonBackground(int color) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(color);
        drawable.setStroke(dp(1), Color.argb(150, 255, 255, 255));
        drawable.setCornerRadius(dp(10));
        return drawable;
    }

    private LinearLayout panel(GradientDrawable background, int padX, int padY) {
        LinearLayout panel = new LinearLayout(windowContext);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setGravity(Gravity.START);
        panel.setPadding(padX, padY, padX, padY);
        panel.setBackground(background);
        return panel;
    }

    private String navigationModeLabel() {
        return NavigationModeSettings.label(app);
    }

    private DisplayMetrics displayMetrics() {
        DisplayMetrics metrics = new DisplayMetrics();
        if (windowManager != null) {
            try {
                windowManager.getDefaultDisplay().getMetrics(metrics);
                return metrics;
            } catch (Exception ignored) {
            }
        }
        return app.getResources().getDisplayMetrics();
    }

    private int overlayWidth(DisplayMetrics metrics) {
        int width = metrics == null ? app.getResources().getDisplayMetrics().widthPixels : metrics.widthPixels;
        return Math.max(1, width);
    }

    private float responsiveTextPx(DisplayMetrics metrics, float heightRatio, int minDp, int maxDp) {
        int height = metrics == null ? app.getResources().getDisplayMetrics().heightPixels : metrics.heightPixels;
        float px = height * heightRatio;
        return Math.max(dp(minDp), Math.min(dp(maxDp), px));
    }

    private static final class CompassMiniView extends View {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF oval = new RectF();
        private int step = Integer.MIN_VALUE;

        CompassMiniView(Context context) {
            super(context);
        }

        void setStep(int value) {
            if (step == value) return;
            step = value;
            invalidate();
        }

        @Override
        protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            int w = getWidth();
            int h = getHeight();
            float size = Math.max(1f, Math.min(w, h));
            float cx = w / 2f;
            float cy = h / 2f;
            float radius = size * 0.42f;

            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(Math.max(2f, size * 0.055f));
            paint.setColor(Color.argb(190, 255, 255, 255));
            oval.set(cx - radius, cy - radius, cx + radius, cy + radius);
            canvas.drawOval(oval, paint);

            if (step == Integer.MIN_VALUE) {
                paint.setStyle(Paint.Style.FILL);
                paint.setColor(Color.argb(150, 255, 255, 255));
                canvas.drawCircle(cx, cy, Math.max(2f, size * 0.06f), paint);
                return;
            }

            canvas.save();
            canvas.rotate(step * 10f, cx, cy);
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeCap(Paint.Cap.ROUND);
            paint.setStrokeJoin(Paint.Join.ROUND);
            paint.setStrokeWidth(Math.max(2f, size * 0.07f));
            paint.setColor(Color.WHITE);
            float tipY = cy - radius * 0.74f;
            float tailY = cy + radius * 0.48f;
            canvas.drawLine(cx, tailY, cx, tipY, paint);
            float wing = radius * 0.28f;
            canvas.drawLine(cx, tipY, cx - wing, tipY + wing, paint);
            canvas.drawLine(cx, tipY, cx + wing, tipY + wing, paint);
            canvas.restore();
        }
    }

    private static Context createWindowContext(Context context) {
        if (Build.VERSION.SDK_INT < 30) return context;
        int type = overlayWindowType();
        try {
            WindowManager wm = (WindowManager) context.getSystemService(Context.WINDOW_SERVICE);
            Display display = wm == null ? null : wm.getDefaultDisplay();
            Context displayContext = display == null ? context : context.createDisplayContext(display);
            return displayContext.createWindowContext(type, null);
        } catch (Exception ignored) {
            return context;
        }
    }

    private static int overlayWindowType() {
        return Build.VERSION.SDK_INT >= 26
                ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                : WindowManager.LayoutParams.TYPE_PHONE;
    }

    private int dp(int value) {
        return Math.round(value * app.getResources().getDisplayMetrics().density);
    }
}
