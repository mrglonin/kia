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
import android.util.TypedValue;
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
import kia.app.media.domain.RadioStationStore;

public final class MediaOverlayController {
    private static MediaOverlayController instance;

    private final Context app;
    private final Context windowContext;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final WindowManager windowManager;
    private LinearLayout overlay;
    private TextView closeButton;
    private TextView modeText;
    private TextView sourceText;
    private TextView txText;
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
            modeText = null;
            sourceText = null;
            txText = null;
            return;
        }
        try {
            windowManager.removeView(overlay);
        } catch (Exception ignored) {
        }
        hideCloseButton();
        overlay = null;
        modeText = null;
        sourceText = null;
        txText = null;
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
        DisplayMetrics metrics = displayMetrics();
        int panelWidth = Math.max(1, Math.round(overlayWidth(metrics) * 0.5f));
        int padX = Math.max(dp(6), Math.round(panelWidth * 0.025f));
        int padY = Math.max(dp(5), Math.round(metrics.heightPixels * 0.012f));
        float modePx = responsiveTextPx(metrics, 0.042f, 17, 25);
        float bodyPx = responsiveTextPx(metrics, 0.038f, 15, 22);
        float txPx = responsiveTextPx(metrics, 0.034f, 13, 19);

        LinearLayout root = new LinearLayout(windowContext);
        root.setOrientation(LinearLayout.HORIZONTAL);
        root.setGravity(Gravity.START);

        LinearLayout left = panel(redBackground(), padX, padY);
        LinearLayout right = panel(greenBackground(), padX, padY);

        modeText = headerText(modePx);
        left.addView(modeText, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        sourceText = bodyText(bodyPx, false);
        LinearLayout.LayoutParams sourceLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        sourceLp.setMargins(0, Math.max(dp(4), Math.round(metrics.heightPixels * 0.006f)), 0, 0);
        left.addView(sourceText, sourceLp);

        TextView txHeader = headerText(modePx);
        txHeader.setText("Приборка");
        right.addView(txHeader, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        txText = bodyText(txPx, true);
        LinearLayout.LayoutParams txLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f);
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
        if (modeText == null || sourceText == null || txText == null) return;
        modeText.setText("Режим: " + AppSettings.mediaProfileLabel(app));
        sourceText.setText(mediaDetails() + "\n\n" + callDetails());
        txText.setText(txDetails());
    }

    private String mediaDetails() {
        if (!AppSettings.mediaEnabled(app)) {
            return "Профиль: " + AppSettings.mediaProfileLabel(app)
                    + "\nРежим: Kia media выключен"
                    + "\nЗахват: остановлен"
                    + "\nОтправка: нет";
        }
        MediaState media = StateStore.media();
        StringBuilder out = new StringBuilder();
        out.append("Профиль: ").append(AppSettings.mediaProfileLabel(app));
        out.append("\nРежим: ").append(profileModeText());
        out.append("\nЗахват: ").append(captureText());
        out.append("\nТекст: ").append(AppSettings.mediaTextModeLabel(app));
        out.append("\nOther: ").append(AppSettings.otherMediaSourceLabel(app));
        if (media == null || (media.title.isEmpty() && media.artist.isEmpty() && media.source.isEmpty())) {
            out.append("\n\nМузыка: нет данных");
            appendRadioDetails(out, media);
            return out.toString();
        }
        String artist = media.artist.isEmpty() || "<unknown>".equalsIgnoreCase(media.artist)
                ? "-" : media.artist;
        String title = media.title.isEmpty() ? "-" : media.title;
        String source = media.source.isEmpty() ? "-" : media.source;
        out.append("\n\nМузыка: ").append(media.playing ? "играет" : "пауза");
        out.append("\nИсточник: ").append(source);
        if (!media.packageName.isEmpty()) out.append("\nПакет: ").append(media.packageName);
        out.append("\nТрек: ").append(title);
        out.append("\nИсполнитель: ").append(artist);
        if (media.durationMs >= 0L) out.append("\nДлительность: ").append(formatDuration(media.durationMs));
        appendRadioDetails(out, media);
        return out.toString();
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

    private String txDetails() {
        if (!AppSettings.mediaEnabled(app)) {
            return "Media выключено\nTX не отправляется";
        }
        MediaState media = StateStore.media();
        String tx = media == null ? "" : media.clusterTx;
        if (tx == null || tx.trim().isEmpty()) {
            return "Профиль: " + AppSettings.mediaProfileLabel(app)
                    + "\nTX: ожидание"
                    + "\n\nКоманды появятся после смены источника, трека, радио или звонка.";
        }
        return formatTxFrames(tx);
    }

    private String profileModeText() {
        int profile = AppSettings.mediaProfile(app);
        if (profile == AppSettings.MEDIA_PROFILE_TEYES) return "TEYES / CC4 Pro";
        if (profile == AppSettings.MEDIA_PROFILE_UNIVERSAL_ANDROID) return "Universal Android";
        if (profile == AppSettings.MEDIA_PROFILE_UART_REAL) return "UART real + Android";
        return "выключен";
    }

    private String captureText() {
        int profile = AppSettings.mediaProfile(app);
        if (profile == AppSettings.MEDIA_PROFILE_TEYES) return "TEYES widget + SPD media/radio/bt";
        if (profile == AppSettings.MEDIA_PROFILE_UNIVERSAL_ANDROID) return "MediaSession + радио по базе Kia";
        if (profile == AppSettings.MEDIA_PROFILE_UART_REAL) return "MediaSession, source 0x7A не трогаем";
        return "нет";
    }

    private void appendRadioDetails(StringBuilder out, MediaState media) {
        String frequency = RadioStationStore.currentFrequency(media);
        if (frequency == null || frequency.isEmpty()) return;
        String band = RadioStationStore.currentBand(media);
        String station = RadioStationStore.resolve(app, band, frequency, "");
        out.append("\nРадио: ").append(band).append(' ').append(frequency);
        if (station != null && !station.isEmpty()) out.append(" -> ").append(station);
        out.append("\nБаза станций: ").append(RadioStationStore.summary(app, 3));
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

    private LinearLayout panel(GradientDrawable background, int padX, int padY) {
        LinearLayout panel = new LinearLayout(windowContext);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setGravity(Gravity.START);
        panel.setPadding(padX, padY, padX, padY);
        panel.setBackground(background);
        return panel;
    }

    private TextView headerText(float sizePx) {
        TextView view = new TextView(windowContext);
        view.setTextColor(Color.WHITE);
        view.setTextSize(TypedValue.COMPLEX_UNIT_PX, sizePx);
        view.setIncludeFontPadding(false);
        view.setGravity(Gravity.START | Gravity.CENTER_VERTICAL);
        return view;
    }

    private TextView bodyText(float sizePx, boolean mono) {
        TextView view = new TextView(windowContext);
        view.setTextColor(Color.WHITE);
        view.setTextSize(TypedValue.COMPLEX_UNIT_PX, sizePx);
        view.setLineSpacing(0f, 0.95f);
        view.setIncludeFontPadding(false);
        view.setGravity(mono ? (Gravity.START | Gravity.BOTTOM) : Gravity.START);
        view.setSingleLine(false);
        if (mono) view.setTypeface(Typeface.MONOSPACE);
        if (Build.VERSION.SDK_INT >= 26) {
            view.setAutoSizeTextTypeUniformWithConfiguration(
                    Math.max(1, Math.round(sizePx * (mono ? 0.58f : 0.66f))),
                    Math.max(2, Math.round(sizePx)),
                    1,
                    TypedValue.COMPLEX_UNIT_PX);
        }
        return view;
    }

    private GradientDrawable closeButtonBackground(int color) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(color);
        drawable.setStroke(dp(1), Color.argb(150, 255, 255, 255));
        drawable.setCornerRadius(dp(10));
        return drawable;
    }

    private String formatTxFrames(String tx) {
        String clean = tx == null ? "" : tx.trim();
        if (clean.isEmpty()) return "TX: ожидание";
        String[] lines = clean.split("\\n");
        StringBuilder out = new StringBuilder();
        int start = Math.max(0, lines.length - 5);
        int visibleIndex = 1;
        for (int i = start; i < lines.length; i++) {
            String line = lines[i] == null ? "" : lines[i].trim();
            if (line.isEmpty()) continue;
            int bytes = line.indexOf(" bytes=");
            String label = bytes >= 0 ? line.substring(0, bytes).trim() : line;
            String frame = bytes >= 0 ? line.substring(bytes + " bytes=".length()).trim() : "";
            if (out.length() > 0) out.append("\n\n");
            out.append("Кадр ").append(visibleIndex++).append(": ").append(label);
            if (!frame.isEmpty()) {
                out.append('\n').append(frame);
            }
        }
        return out.toString();
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

    private static String formatDuration(long value) {
        if (value < 0L) return "-";
        long seconds = value / 1000L;
        long minutes = seconds / 60L;
        long rest = seconds % 60L;
        return minutes + ":" + (rest < 10 ? "0" : "") + rest;
    }
}
