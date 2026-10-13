package kia.app.tpms;

import android.annotation.SuppressLint;
import android.app.KeyguardManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.database.ContentObserver;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.os.PowerManager;
import android.provider.Settings;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.lang.reflect.Method;
import java.util.concurrent.TimeUnit;

/** Read-only vendor visibility signals. No Accessibility or usage-access permission. */
final class TeyesHomeVisibilityMonitor {
    interface Listener { void onVisibility(boolean visible); }
    interface PropertyReader { String get(String key); }

    private static final long INTERVAL_MS = 500L;
    private final Context app;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final PropertyReader properties;
    private HandlerThread thread;
    private Handler worker;
    private Listener listener;
    private BroadcastReceiver screenReceiver;
    private ContentObserver cameraObserver;
    private long generation;

    TeyesHomeVisibilityMonitor(Context context) {
        this(context, new VendorProperties());
    }

    TeyesHomeVisibilityMonitor(Context context, PropertyReader reader) {
        app = context.getApplicationContext();
        properties = reader;
    }

    void start(Listener callback) {
        if (thread != null) return;
        listener = callback;
        long currentGeneration = ++generation;
        thread = new HandlerThread("KiaHomeVisibility");
        thread.start();
        worker = new Handler(thread.getLooper());
        Handler currentWorker = worker;
        registerScreenReceiver();
        registerCameraObserver();
        currentWorker.post(new Runnable() {
            @Override public void run() {
                String home = properties.get("com.teyes.home.show");
                String camera = properties.get("ReserveCamera");
                main.post(() -> {
                    if (generation != currentGeneration || listener == null) return;
                    listener.onVisibility(TeyesHomeVisibilityPolicy.shouldShow(
                            home, camera, isInteractive(), isLocked()) && !cameraShown());
                });
                currentWorker.postDelayed(this, INTERVAL_MS);
            }
        });
    }

    void stop() {
        generation++;
        listener = null;
        if (cameraObserver != null) {
            try { app.getContentResolver().unregisterContentObserver(cameraObserver); }
            catch (RuntimeException ignored) {}
            cameraObserver = null;
        }
        if (screenReceiver != null) {
            try { app.unregisterReceiver(screenReceiver); } catch (RuntimeException ignored) {}
            screenReceiver = null;
        }
        if (worker != null) worker.removeCallbacksAndMessages(null);
        if (thread != null) thread.quitSafely();
        worker = null;
        thread = null;
    }

    private boolean isInteractive() {
        PowerManager power = (PowerManager) app.getSystemService(Context.POWER_SERVICE);
        return power != null && power.isInteractive();
    }

    private boolean isLocked() {
        KeyguardManager keyguard = (KeyguardManager) app.getSystemService(Context.KEYGUARD_SERVICE);
        return keyguard == null || keyguard.isKeyguardLocked();
    }

    private boolean cameraShown() {
        try { return Settings.System.getInt(app.getContentResolver(), "com.360.ui.show", 0) != 0; }
        catch (RuntimeException ignored) { return true; }
    }

    private void registerCameraObserver() {
        cameraObserver = new ContentObserver(main) {
            @Override public void onChange(boolean selfChange) {
                if (listener != null && cameraShown()) listener.onVisibility(false);
            }
        };
        try {
            app.getContentResolver().registerContentObserver(
                    Settings.System.getUriFor("com.360.ui.show"), false, cameraObserver);
        } catch (RuntimeException ignored) { cameraObserver = null; }
    }

    @SuppressLint("UnspecifiedRegisterReceiverFlag")
    private void registerScreenReceiver() {
        screenReceiver = new BroadcastReceiver() {
            @Override public void onReceive(Context context, Intent intent) {
                if (listener != null && (!isInteractive() || isLocked())) listener.onVisibility(false);
            }
        };
        IntentFilter filter = new IntentFilter(Intent.ACTION_SCREEN_OFF);
        filter.addAction(Intent.ACTION_SCREEN_ON);
        try {
            if (Build.VERSION.SDK_INT >= 33) {
                app.registerReceiver(screenReceiver, filter, Context.RECEIVER_NOT_EXPORTED);
            } else {
                app.registerReceiver(screenReceiver, filter);
            }
        } catch (RuntimeException ignored) { screenReceiver = null; }
    }

    private static final class VendorProperties implements PropertyReader {
        private Method getter;
        private boolean resolved;

        @Override public String get(String key) {
            if (!resolved) {
                resolved = true;
                try {
                    getter = Class.forName("android.os.SystemProperties")
                            .getMethod("get", String.class, String.class);
                } catch (ReflectiveOperationException | RuntimeException ignored) {}
            }
            if (getter != null) {
                try { return (String) getter.invoke(null, key, ""); }
                catch (ReflectiveOperationException | RuntimeException ignored) { getter = null; }
            }
            // Some firmware restricts the hidden Java API. Use the public executable
            // with fixed arguments on this worker, without shell commands or writes.
            Process process = null;
            try {
                process = new ProcessBuilder("/system/bin/getprop", key).start();
                if (!process.waitFor(150L, TimeUnit.MILLISECONDS) || process.exitValue() != 0) return "";
                try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                    String line = reader.readLine();
                    return line == null ? "" : line.trim();
                }
            } catch (Exception ignored) {
                return "";
            } finally {
                if (process != null) process.destroy();
            }
        }
    }
}
