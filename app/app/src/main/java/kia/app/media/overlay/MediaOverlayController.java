package kia.app.media.overlay;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.util.DisplayMetrics;
import android.view.Display;
import android.view.Gravity;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.LinearLayout;
import android.widget.TextView;

import kia.app.core.AppIds;
import kia.app.core.AppLog;
import kia.app.core.StateStore;
import kia.app.core.model.CallState;
import kia.app.core.model.MediaState;
import kia.app.core.settings.AppSettings;

public final class MediaOverlayController {
    private static MediaOverlayController instance;

    private final Context app;
    private final Context windowContext;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final WindowManager windowManager;
    private LinearLayout overlay;
    private TextView closeButton;
    private TextView statusText;
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

    private MediaOverlayController(Context context) {
        this.app = context.getApplicationContext();
        this.windowContext = createWindowContext(app);
        this.windowManager = (WindowManager) windowContext.getSystemService(Context.WINDOW_SERVICE);
    }

    public static synchronized MediaOverlayController get(Context context) {
        if (instance == null) instance = new MediaOverlayController(context);
        return instance;
    }

    public synchronized void start() {
        registerReceiver();
        apply();
    }

    public synchronized void apply() {
        if (!AppSettings.mediaOverlayEnabled(app)) {
            hide();
            return;
        }
        if (!canDrawOverlays(app)) {
            hide();
            if (!permissionLogged) {
                permissionLogged = true;
                AppLog.line(app, "Media overlay: нужно разрешение поверх окон");
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
                AppLog.line(app, "Media overlay: add failed " + e.getClass().getSimpleName());
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
        statusText = null;
    }

    private void showCloseButton() {
        if (windowManager == null) return;
        if (closeButton == null) {
            closeButton = closeButton("Отключить", Color.argb(220, 18, 23, 31));
            try {
                windowManager.addView(closeButton, closeButtonLayoutParams());
            } catch (Exception e) {
                closeButton = null;
                AppLog.line(app, "Media overlay close: add failed " + e.getClass().getSimpleName());
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
        AppSettings.setMediaOverlayEnabled(app, false);
        AppLog.line(app, "Media overlay: disabled from overlay button");
        app.sendBroadcast(new Intent(AppIds.ACTION_STATE_CHANGED).setPackage(AppIds.PACKAGE));
        hide();
    }

    private LinearLayout createOverlay() {
        LinearLayout box = new LinearLayout(windowContext);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setGravity(Gravity.START);
        box.setPadding(dp(24), dp(22), dp(24), dp(22));
        box.setBackground(background());

        TextView title = new TextView(windowContext);
        title.setText("Медиа / BT");
        title.setTextColor(Color.WHITE);
        title.setTextSize(24f);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        title.setGravity(Gravity.START);
        box.addView(title, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        statusText = new TextView(windowContext);
        statusText.setTextColor(Color.WHITE);
        statusText.setTextSize(19f);
        statusText.setLineSpacing(dp(3), 1.0f);
        statusText.setGravity(Gravity.START);
        LinearLayout.LayoutParams textLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        textLp.setMargins(0, dp(12), 0, 0);
        box.addView(statusText, textLp);
        return box;
    }

    private WindowManager.LayoutParams layoutParams() {
        DisplayMetrics metrics = new DisplayMetrics();
        windowManager.getDefaultDisplay().getMetrics(metrics);
        int flags = WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                | WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN;
        WindowManager.LayoutParams params = new WindowManager.LayoutParams(
                Math.max(dp(540), metrics.widthPixels / 2),
                ViewGroup.LayoutParams.MATCH_PARENT,
                overlayWindowType(),
                flags,
                PixelFormat.TRANSLUCENT);
        params.gravity = Gravity.TOP | Gravity.END;
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
        if (statusText == null) return;
        statusText.setText(mediaDetails() + "\n\n" + callDetails());
    }

    private String mediaDetails() {
        if (!AppSettings.mediaEnabled(app)) return "Музыка: выключена";
        MediaState media = StateStore.media();
        if (media == null || (media.title.isEmpty() && media.artist.isEmpty() && media.source.isEmpty())) {
            return "Музыка: нет данных"
                    + "\nШильдик other: " + AppSettings.otherMediaSourceLabel(app)
                    + "\nСтрока: " + AppSettings.mediaTextModeLabel(app);
        }
        String artist = media.artist.isEmpty() || "<unknown>".equalsIgnoreCase(media.artist)
                ? "-" : media.artist;
        String title = media.title.isEmpty() ? "-" : media.title;
        String source = media.source.isEmpty() ? "-" : media.source;
        return "Музыка: " + (media.playing ? "играет" : "пауза")
                + "\nИсточник: " + source
                + "\nТрек: " + title
                + "\nИсполнитель: " + artist
                + "\nШильдик other: " + AppSettings.otherMediaSourceLabel(app)
                + "\nСтрока: " + AppSettings.mediaTextModeLabel(app);
    }

    private String callDetails() {
        if (!AppSettings.callEnabled(app)) return "BT звонок: выключен";
        CallState call = StateStore.call();
        if (call == null || !call.active) {
            return "BT звонок: нет активного звонка"
                    + "\nШильдик: " + AppSettings.callSourceLabel(app)
                    + "\nTEYES CC4 Pro";
        }
        long now = System.currentTimeMillis();
        return "BT звонок: активен"
                + "\nИмя: " + call.displayName()
                + "\nИсточник: " + call.subtitle()
                + "\nВремя: " + call.elapsedText(now)
                + "\nШильдик: " + AppSettings.callSourceLabel(app);
    }

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

    private GradientDrawable background() {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(Color.argb(190, 13, 15, 19));
        return drawable;
    }

    private GradientDrawable closeButtonBackground(int color) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(color);
        drawable.setStroke(dp(1), Color.argb(150, 255, 255, 255));
        drawable.setCornerRadius(dp(10));
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
