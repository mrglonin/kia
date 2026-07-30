package kia.app.tpms;

import android.content.Context;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.media.MediaPlayer;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.view.Display;
import android.view.Gravity;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.LinearLayout;
import android.widget.TextView;

import kia.app.R;
import kia.app.core.AppLog;
import kia.app.core.model.TpmsState;
import kia.app.core.settings.AppSettings;

public final class TpmsWarningOverlayController {
    private static TpmsWarningOverlayController instance;

    private final Context app;
    private final Context windowContext;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final WindowManager windowManager;
    private LinearLayout overlay;
    private TextView detailsText;
    private MediaPlayer player;
    private String currentKey = "";
    private String dismissedKey = "";
    private boolean permissionLogged;

    private TpmsWarningOverlayController(Context context) {
        this.app = context.getApplicationContext();
        this.windowContext = createWindowContext(app);
        this.windowManager = (WindowManager) windowContext.getSystemService(Context.WINDOW_SERVICE);
    }

    public static synchronized TpmsWarningOverlayController get(Context context) {
        if (instance == null) instance = new TpmsWarningOverlayController(context);
        return instance;
    }

    public void apply(TpmsState state, String key) {
        String safeKey = key == null ? "" : key;
        String details = TpmsAlertController.warningDetails(app, state);
        handler.post(() -> applyOnMain(safeKey, details));
    }

    public void stopSound() {
        handler.post(this::stopSoundOnMain);
    }

    public void dismissCurrent() {
        handler.post(this::dismiss);
    }

    public void setActivityVisible(boolean visible) {
        // TPMS warnings must stay in the real overlay layer, even while Kia is open.
    }

    private synchronized void applyOnMain(String key, String details) {
        if (key.length() == 0) {
            currentKey = "";
            dismissedKey = "";
            hide();
            stopSoundOnMain();
            return;
        }
        if (!key.equals(currentKey)) {
            currentKey = key;
            dismissedKey = "";
        }
        if (key.equals(dismissedKey)) {
            hide();
            stopSoundOnMain();
            return;
        }
        if (canDrawOverlays(app)) {
            show(details);
        } else if (!permissionLogged) {
            permissionLogged = true;
            AppLog.line(app, "TPMS overlay: нужно разрешение поверх окон");
        }
        if (AppSettings.tpmsSoundAlertsEnabled(app)) {
            startSoundOnMain();
        } else {
            stopSoundOnMain();
        }
    }

    private void dismiss() {
        dismissedKey = currentKey;
        hide();
        stopSoundOnMain();
        AppLog.line(app, "TPMS overlay dismissed");
    }

    private void show(String details) {
        permissionLogged = false;
        if (windowManager == null) return;
        if (overlay == null) {
            overlay = createOverlay();
            try {
                windowManager.addView(overlay, layoutParams());
            } catch (Exception e) {
                overlay = null;
                detailsText = null;
                AppLog.line(app, "TPMS overlay add failed " + e.getClass().getSimpleName());
                return;
            }
        } else {
            try {
                windowManager.updateViewLayout(overlay, layoutParams());
            } catch (Exception ignored) {
            }
        }
        if (detailsText != null) {
            detailsText.setText(details == null || details.length() == 0
                    ? "TPMS warning"
                    : details);
        }
    }

    private void hide() {
        if (overlay == null || windowManager == null) {
            overlay = null;
            detailsText = null;
            return;
        }
        try {
            windowManager.removeView(overlay);
        } catch (Exception ignored) {
        }
        overlay = null;
        detailsText = null;
    }

    private LinearLayout createOverlay() {
        LinearLayout box = new LinearLayout(windowContext);
        box.setOrientation(LinearLayout.HORIZONTAL);
        box.setGravity(Gravity.CENTER_VERTICAL);
        box.setPadding(dp(28), dp(14), dp(28), dp(14));
        box.setBackground(redBackground());
        box.setClickable(false);
        box.setFocusable(false);

        LinearLayout texts = new LinearLayout(windowContext);
        texts.setOrientation(LinearLayout.VERTICAL);
        TextView title = new TextView(windowContext);
        title.setText("Предупреждение TPMS");
        title.setTextColor(Color.WHITE);
        title.setTextSize(21f);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        texts.addView(title, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        detailsText = new TextView(windowContext);
        detailsText.setTextColor(Color.rgb(255, 229, 229));
        detailsText.setTextSize(15f);
        detailsText.setSingleLine(true);
        LinearLayout.LayoutParams detailsLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        detailsLp.setMargins(0, dp(4), 0, 0);
        texts.addView(detailsText, detailsLp);

        box.addView(texts, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        TextView close = new TextView(windowContext);
        close.setText("×");
        close.setTextColor(Color.WHITE);
        close.setTextSize(36f);
        close.setGravity(Gravity.CENTER);
        close.setTypeface(Typeface.DEFAULT_BOLD);
        close.setClickable(true);
        close.setFocusable(true);
        close.setContentDescription("Закрыть предупреждение TPMS");
        close.setOnClickListener(v -> dismiss());
        box.addView(close, new LinearLayout.LayoutParams(dp(74), dp(74)));
        return box;
    }

    private WindowManager.LayoutParams layoutParams() {
        int flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN;
        WindowManager.LayoutParams params = new WindowManager.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                dp(124),
                overlayWindowType(),
                flags,
                PixelFormat.TRANSLUCENT);
        params.gravity = Gravity.TOP | Gravity.START;
        params.x = 0;
        params.y = 0;
        return params;
    }

    private void startSoundOnMain() {
        if (player != null) {
            if (!player.isPlaying()) {
                try {
                    player.start();
                } catch (Exception ignored) {
                    stopSoundOnMain();
                }
            }
            return;
        }
        try {
            player = MediaPlayer.create(app, R.raw.tpms6_error);
            if (player == null) return;
            player.setLooping(true);
            player.start();
        } catch (Exception e) {
            stopSoundOnMain();
            AppLog.line(app, "TPMS sound failed " + e.getClass().getSimpleName());
        }
    }

    private void stopSoundOnMain() {
        if (player == null) return;
        try {
            player.stop();
        } catch (Exception ignored) {
        }
        try {
            player.release();
        } catch (Exception ignored) {
        }
        player = null;
    }

    public static boolean canDrawOverlays(Context context) {
        return Build.VERSION.SDK_INT < 23 || Settings.canDrawOverlays(context);
    }

    private GradientDrawable redBackground() {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(Color.argb(225, 174, 20, 30));
        return drawable;
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
