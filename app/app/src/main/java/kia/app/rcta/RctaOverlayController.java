package kia.app.rcta;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.PixelFormat;
import android.media.MediaPlayer;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.view.Display;
import android.view.Gravity;
import android.view.ViewGroup;
import android.view.WindowManager;

import kia.app.R;
import kia.app.core.AppIds;
import kia.app.core.AppLog;
import kia.app.core.StateStore;
import kia.app.core.model.RctaState;
import kia.app.core.settings.AppSettings;

public final class RctaOverlayController {
    private static RctaOverlayController instance;

    private final Context app;
    private final Context windowContext;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final WindowManager windowManager;
    private BlindSpotOverlayView overlay;
    private MediaPlayer player;
    private BroadcastReceiver receiver;
    private boolean receiverRegistered;
    private boolean permissionLogged;

    private RctaOverlayController(Context context) {
        this.app = context.getApplicationContext();
        this.windowContext = createWindowContext(app);
        this.windowManager = (WindowManager) windowContext.getSystemService(Context.WINDOW_SERVICE);
    }

    public static synchronized RctaOverlayController get(Context context) {
        if (instance == null) instance = new RctaOverlayController(context);
        return instance;
    }

    public synchronized void start() {
        registerReceiver();
        apply();
    }

    public void apply() {
        handler.post(this::applyOnMain);
    }

    public synchronized void stop() {
        handler.post(() -> {
            hide();
            stopSound();
            unregisterReceiver();
        });
    }

    public static boolean canDrawOverlays(Context context) {
        return Build.VERSION.SDK_INT < 23 || Settings.canDrawOverlays(context);
    }

    private void applyOnMain() {
        RctaState state = StateStore.rcta();
        boolean active = state != null && state.active();
        if (active && AppSettings.rctaSoundEnabled(app)) startSound();
        else stopSound();

        if (!active || !AppSettings.rctaOverlayEnabled(app)) {
            hide();
            return;
        }
        if (!canDrawOverlays(app)) {
            hide();
            if (!permissionLogged) {
                permissionLogged = true;
                AppLog.line(app, "RCTA overlay: нужно разрешение поверх окон");
            }
            return;
        }
        permissionLogged = false;
        show(state.left, state.right);
    }

    private void show(boolean left, boolean right) {
        if (windowManager == null) return;
        if (overlay == null) {
            overlay = new BlindSpotOverlayView(windowContext);
            overlay.setClickable(false);
            overlay.setBottomLiftDp(0);
            applyVisualSettings(overlay);
            overlay.setPreview(left, right, false);
            try {
                windowManager.addView(overlay, layoutParams());
            } catch (Exception e) {
                overlay = null;
                AppLog.line(app, "RCTA overlay: add failed " + e.getClass().getSimpleName());
                return;
            }
        } else {
            applyVisualSettings(overlay);
            overlay.setPreview(left, right, false);
            try {
                windowManager.updateViewLayout(overlay, layoutParams());
            } catch (Exception ignored) {
            }
        }
        overlay.postInvalidateOnAnimation();
    }

    private void applyVisualSettings(BlindSpotOverlayView view) {
        view.setStyleType(AppSettings.rctaStyle(app));
        view.setAlertColor(AppSettings.rctaColor(app));
        view.setBackgroundAlpha(AppSettings.rctaBackgroundAlpha(app));
        view.setArrowCount(AppSettings.rctaArrowCount(app));
    }

    private void hide() {
        if (overlay == null || windowManager == null) {
            overlay = null;
            return;
        }
        try {
            windowManager.removeView(overlay);
        } catch (Exception ignored) {
        }
        overlay = null;
    }

    private void startSound() {
        if (player != null) {
            if (!player.isPlaying()) {
                try {
                    player.start();
                } catch (Exception ignored) {
                }
            }
            return;
        }
        try {
            player = MediaPlayer.create(app, R.raw.rcta_warning);
            if (player == null) return;
            player.setLooping(true);
            player.start();
        } catch (Exception e) {
            AppLog.line(app, "RCTA sound failed " + e.getClass().getSimpleName());
            stopSound();
        }
    }

    private void stopSound() {
        if (player == null) return;
        try {
            player.stop();
        } catch (Exception ignored) {
        }
        player.release();
        player = null;
    }

    private WindowManager.LayoutParams layoutParams() {
        int flags = WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                | WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN;
        WindowManager.LayoutParams params = new WindowManager.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
                overlayWindowType(),
                flags,
                PixelFormat.TRANSLUCENT);
        params.gravity = Gravity.TOP | Gravity.START;
        params.x = 0;
        params.y = 0;
        return params;
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
}
