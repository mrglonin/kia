package kia.app.tpms;

import android.annotation.SuppressLint;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;

import kia.app.core.AppIds;
import kia.app.core.AppLog;
import kia.app.core.model.TpmsState;
import kia.app.core.settings.AppSettings;
import kia.app.protocol.adapter.AdapterGateway;

/** Optional display bridge. It neither opens USB nor binds the stock TPMS service. */
public final class TeyesTpmsWidgetForwarder {
    private static final String WIDGET_PACKAGE = "com.teyes.tpms.widget";
    private static final String WIDGET_BOOT_RECEIVER = "com.teyes.tpms.receiver.BootReceiver";
    private static final String ACTION_START_WIDGET = "com.teyes.widget.action.START_SERVICE";
    private static final String ACTION_TIRE_DATA = "com.spd.tpms.teyes.service.TIREDATARECEIVER";
    private static final long REPLAY_MS = 5_000L;
    private static final long REACTIVE_REPLAY_MS = 200L;
    private static final long BOOTSTRAP_INTERVAL_MS = 30_000L;
    private static final long BOOTSTRAP_REPLAY_MS = 1_000L;

    private static TeyesTpmsWidgetForwarder instance;

    private final Context app;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final TeyesTpmsWidgetPolicy policy = new TeyesTpmsWidgetPolicy();
    // Separate from StateStore: QA samples must never leak into the stock widget.
    private TpmsState hardwareState = TpmsState.empty();
    private BroadcastReceiver stateReceiver;
    private BroadcastReceiver stockReceiver;
    private boolean started;
    private boolean enabled;
    private boolean usbConnected;
    private boolean reactiveQueued;
    private boolean failureLogged;
    private long connectionEpoch = -1L;
    private long lastBootstrapElapsed = -1L;
    private volatile long lifecycleGeneration;

    private final Runnable replayTick = () -> {
        if (!started || !enabled) return;
        refreshConnection();
        publishSnapshot();
        scheduleReplay();
    };

    private final Runnable reactiveReplay = () -> {
        reactiveQueued = false;
        if (!started || !enabled) return;
        refreshConnection();
        publishSnapshot();
    };

    private final Runnable bootstrapReplay = () -> {
        if (!started || !enabled) return;
        refreshConnection();
        publishSnapshot();
        scheduleReplay();
    };

    private TeyesTpmsWidgetForwarder(Context context) {
        app = context.getApplicationContext();
    }

    public static synchronized TeyesTpmsWidgetForwarder get(Context context) {
        if (instance == null) instance = new TeyesTpmsWidgetForwarder(context);
        return instance;
    }

    public void start() {
        runOnMain(() -> {
            if (!started) {
                lifecycleGeneration++;
                started = true;
                AdapterGateway gateway = AdapterGateway.get(app);
                usbConnected = gateway.usbReady();
                connectionEpoch = gateway.connectionEpoch();
                registerStateReceiver();
            }
            applySettingsOnMain();
        });
    }

    public void applySettings() {
        runOnMain(this::applySettingsOnMain);
    }

    public void stop() {
        runOnMain(() -> {
            if (enabled) clearAllWheels();
            enabled = false;
            started = false;
            lifecycleGeneration++;
            cancelReplay();
            unregisterStockReceiver();
            unregisterStateReceiver();
            resetHardware();
            usbConnected = false;
            connectionEpoch = -1L;
            lastBootstrapElapsed = -1L;
        });
    }

    /** Called only by FrameRouter after a checksum-validated USB TPMS payload. */
    public void onUsbFrame(byte[] frame, long epoch, long receivedElapsed) {
        if (frame == null) return;
        byte[] copy = frame.clone();
        long generation = lifecycleGeneration;
        long receivedWall = System.currentTimeMillis();
        handler.post(() -> {
            if (!started || generation != lifecycleGeneration) return;
            refreshConnection();
            if (!usbConnected || epoch != connectionEpoch) return;
            int mask = TpmsController.observedWheelMask(copy);
            if (mask == 0) return;
            TpmsState next = TpmsController.parseAdapterFrame(copy, hardwareState, receivedWall);
            if (next == null) return;
            hardwareState = next;
            policy.observe(next, mask, receivedElapsed);
            if (enabled) {
                publishSnapshot();
                scheduleReplay();
            }
        });
    }

    private void applySettingsOnMain() {
        if (!started) return;
        boolean nextEnabled = AppSettings.teyesTpmsWidgetForwardEnabled(app);
        refreshConnection();
        if (enabled != nextEnabled) {
            if (!nextEnabled) {
                clearAllWheels();
                enabled = false;
                cancelReplay();
                unregisterStockReceiver();
            } else {
                enabled = true;
                failureLogged = false;
                registerStockReceiver();
                bootstrapWidget(true);
                publishSnapshot();
                scheduleReplay();
            }
            AppLog.line(app, "TEYES TPMS widget pressure forwarding: " + enabled);
        } else if (enabled) {
            publishSnapshot();
            scheduleReplay();
        }
    }

    private void refreshConnection() {
        AdapterGateway gateway = AdapterGateway.get(app);
        boolean connected = gateway.usbReady();
        long epoch = gateway.connectionEpoch();
        if (connected == usbConnected && epoch == connectionEpoch) return;
        usbConnected = connected;
        connectionEpoch = epoch;
        resetHardware();
        handler.removeCallbacks(replayTick);
        handler.removeCallbacks(reactiveReplay);
        reactiveQueued = false;
        if (enabled) clearAllWheels();
    }

    private void resetHardware() {
        hardwareState = TpmsState.empty();
        policy.reset();
    }

    private void publishSnapshot() {
        if (!started || !enabled) return;
        long now = SystemClock.elapsedRealtime();
        if (policy.hasFreshData(now)) bootstrapWidget(false);
        boolean alerts = AppSettings.tpmsAlertsEnabled(app);
        int low = AppSettings.tpmsLowPressureKpa(app);
        int high = AppSettings.tpmsHighPressureKpa(app);
        for (int wheel = 0; wheel < TpmsState.WHEEL_COUNT; wheel++) {
            sendWheel(usbConnected
                    ? policy.project(wheel, now, alerts, low, high)
                    : TeyesTpmsWidgetPolicy.WheelValue.unknown(wheel));
        }
    }

    private void clearAllWheels() {
        for (int wheel = 0; wheel < TpmsState.WHEEL_COUNT; wheel++) {
            sendWheel(TeyesTpmsWidgetPolicy.WheelValue.unknown(wheel));
        }
    }

    private void sendWheel(TeyesTpmsWidgetPolicy.WheelValue value) {
        Intent intent = new Intent(ACTION_TIRE_DATA);
        // The receiver is dynamic. Package targeting also keeps our exported observer
        // from receiving our own writes and prevents a reactive replay feedback loop.
        intent.setPackage(WIDGET_PACKAGE);
        intent.putExtra("which", value.which);
        intent.putExtra("press", value.pressure);
        intent.putExtra("temp", value.temperature);
        intent.putExtra("press_unit", value.pressureUnit);
        intent.putExtra("temp_unit", value.temperatureUnit);
        intent.putExtra("alert_flag", value.alert);
        intent.putExtra("warning_state", value.warningState);
        try {
            app.sendBroadcast(intent);
        } catch (RuntimeException e) {
            logFailure(e);
        }
    }

    private void scheduleReplay() {
        handler.removeCallbacks(replayTick);
        if (!started || !enabled || !usbConnected) return;
        long expiry = policy.nextExpiryDelayMs(SystemClock.elapsedRealtime());
        if (expiry < 0L) return;
        handler.postDelayed(replayTick, Math.min(REPLAY_MS, expiry));
    }

    private void scheduleReactiveReplay() {
        if (!started || !enabled || reactiveQueued) return;
        reactiveQueued = true;
        handler.postDelayed(reactiveReplay, REACTIVE_REPLAY_MS);
    }

    private void cancelReplay() {
        handler.removeCallbacks(replayTick);
        handler.removeCallbacks(reactiveReplay);
        handler.removeCallbacks(bootstrapReplay);
        reactiveQueued = false;
    }

    private void bootstrapWidget(boolean force) {
        long now = SystemClock.elapsedRealtime();
        if (!force && lastBootstrapElapsed >= 0L
                && now - lastBootstrapElapsed < BOOTSTRAP_INTERVAL_MS) return;
        lastBootstrapElapsed = now;
        Intent intent = new Intent(ACTION_START_WIDGET);
        intent.setComponent(new ComponentName(WIDGET_PACKAGE, WIDGET_BOOT_RECEIVER));
        try {
            // Original BootReceiver checks that appWidgetIds already exist and that
            // its service is not running. Do not create widgets or start/bind TPMS here.
            app.sendBroadcast(intent);
            handler.removeCallbacks(bootstrapReplay);
            handler.postDelayed(bootstrapReplay, BOOTSTRAP_REPLAY_MS);
        } catch (RuntimeException e) {
            logFailure(e);
        }
    }

    @SuppressLint("UnspecifiedRegisterReceiverFlag")
    private void registerStateReceiver() {
        if (stateReceiver != null) return;
        stateReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                if (started) refreshConnection();
            }
        };
        IntentFilter filter = new IntentFilter(AppIds.ACTION_STATE_CHANGED);
        try {
            if (Build.VERSION.SDK_INT >= 33) {
                app.registerReceiver(stateReceiver, filter, Context.RECEIVER_NOT_EXPORTED);
            } else {
                app.registerReceiver(stateReceiver, filter);
            }
        } catch (RuntimeException e) {
            stateReceiver = null;
            logFailure(e);
        }
    }

    @SuppressLint("UnspecifiedRegisterReceiverFlag")
    private void registerStockReceiver() {
        if (stockReceiver != null) return;
        stockReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                // Stock status messages can overwrite the widget. Observe only their
                // occurrence: no vendor payload ever becomes a KIA source value.
                scheduleReactiveReplay();
            }
        };
        IntentFilter filter = new IntentFilter(ACTION_TIRE_DATA);
        try {
            if (Build.VERSION.SDK_INT >= 33) {
                app.registerReceiver(stockReceiver, filter, Context.RECEIVER_EXPORTED);
            } else {
                app.registerReceiver(stockReceiver, filter);
            }
        } catch (RuntimeException e) {
            stockReceiver = null;
            logFailure(e);
        }
    }

    private void unregisterStockReceiver() {
        if (stockReceiver == null) return;
        try {
            app.unregisterReceiver(stockReceiver);
        } catch (IllegalArgumentException ignored) {
        }
        stockReceiver = null;
    }

    private void unregisterStateReceiver() {
        if (stateReceiver == null) return;
        try {
            app.unregisterReceiver(stateReceiver);
        } catch (IllegalArgumentException ignored) {
        }
        stateReceiver = null;
    }

    private void logFailure(RuntimeException exception) {
        if (failureLogged) return;
        failureLogged = true;
        AppLog.line(app, "TEYES TPMS widget forwarding failed "
                + exception.getClass().getSimpleName());
    }

    private void runOnMain(Runnable action) {
        if (Looper.myLooper() == Looper.getMainLooper()) action.run();
        else handler.post(action);
    }
}
