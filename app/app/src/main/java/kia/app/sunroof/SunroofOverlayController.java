package kia.app.sunroof;

import android.annotation.SuppressLint;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.PixelFormat;
import android.hardware.input.InputManager;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.provider.Settings;
import android.view.Display;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.ViewGroup;
import android.view.WindowManager;

import kia.app.core.AppIds;
import kia.app.core.AppLog;
import kia.app.core.StateStore;
import kia.app.core.model.VehicleState;
import kia.app.core.settings.AppSettings;
import kia.app.protocol.adapter.AdapterGateway;
import kia.app.tpms.TpmsAlertController;

/** Owns roof windows and audio; all vehicle decisions and Android mutations run on main. */
public final class SunroofOverlayController {
    private static SunroofOverlayController instance;
    private final Context app;
    private final Context windowContext;
    private final WindowManager windowManager;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final SunroofAlertPolicy policy = new SunroofAlertPolicy();
    private final SunroofAlertPolicy qaPolicy = new SunroofAlertPolicy();
    private final SunroofSoundPlayer sound;
    private final TeyesSunroofObserver observer;
    private volatile long generation;
    private volatile String status = "Люк: наблюдение выключено";
    private boolean running;
    private boolean observerRunning;
    private boolean accKnown;
    private boolean accOn;
    private boolean asleep;
    private boolean permissionLogged;
    private boolean windowFailureLogged;
    private boolean qaMode;
    private boolean qaRoofKnown;
    private boolean qaRoofOpen;
    private boolean qaAccKnown;
    private boolean qaAccOn = true;
    private long qaClock;
    private boolean realSnapshotTaken;
    private boolean lastEnabled;
    private boolean lastRealRoofKnown;
    private boolean lastRealRoofOpen;
    private long lastRealEpoch;
    private SunroofAlertPolicy.Decision demo;
    private SunroofOverlayView overlay;
    private long shownSequence = Long.MIN_VALUE;
    private SunroofAlertPolicy.Kind shownKind = SunroofAlertPolicy.Kind.NONE;
    private boolean shownDemo;
    private boolean audioEnabled;
    private BroadcastReceiver stateReceiver;
    private BroadcastReceiver systemReceiver;
    private Runnable finishTransient;
    private final Runnable refresh = new Runnable() {
        @Override public void run() {
            if (!running) return;
            applyOnMain();
            if (running) main.postDelayed(this, 1_000L);
        }
    };

    private SunroofOverlayController(Context context) {
        Context application = context.getApplicationContext();
        app = application == null ? context : application;
        windowContext = createWindowContext(app);
        windowManager = (WindowManager) windowContext.getSystemService(Context.WINDOW_SERVICE);
        sound = new SunroofSoundPlayer(app);
        observer = new TeyesSunroofObserver(app, new TeyesSunroofObserver.Listener() {
            @Override public void onAcc(boolean known, boolean on, long elapsed, String source) {
                postCurrent(() -> {
                    if (!running || !observerRunning) return;
                    if (known && accKnown && on != accOn) cancelDemo();
                    accKnown = known;
                    accOn = on;
                    applyOnMain();
                });
            }

            @Override public void onHardwareAction(int keyCode, int action) {
                if (action == KeyEvent.ACTION_DOWN) dismissCurrent();
            }

            @Override public void onSleep(boolean sleeping) {
                postCurrent(() -> {
                    if (!running || !observerRunning) return;
                    asleep = sleeping;
                    if (sleeping) dismissOnMain();
                    applyOnMain();
                });
            }
        });
    }

    public static synchronized SunroofOverlayController get(Context context) {
        if (instance == null) instance = new SunroofOverlayController(context);
        return instance;
    }

    public void start() {
        postCurrent(() -> {
            ensureStarted();
            applyOnMain();
        });
    }

    public void apply() {
        postCurrent(() -> {
            if (running) applyOnMain();
        });
    }

    public synchronized void stop() {
        long stopped = ++generation;
        main.removeCallbacksAndMessages(null);
        Runnable cleanup = () -> {
            if (generation != stopped) return;
            running = false;
            observerRunning = false;
            observer.stop();
            unregisterReceivers();
            cancelDemo();
            policy.reset();
            hide();
            accKnown = false;
            asleep = false;
            realSnapshotTaken = false;
            status = "Люк: наблюдение выключено";
        };
        if (Looper.myLooper() == Looper.getMainLooper()) cleanup.run();
        else main.post(cleanup);
    }

    public void dismissCurrent() {
        postCurrent(this::dismissOnMain);
    }

    public void preview(boolean open) {
        postCurrent(() -> previewOnMain(open, false));
    }

    public void previewParkingWarning() {
        postCurrent(() -> previewOnMain(true, true));
    }

    /** Shell-secured QaReceiver entry; samples stay in a separate policy and never alter CAN state. */
    public void runQaScenario(String scenario) {
        postCurrent(() -> {
            ensureStarted();
            if ("sunroof_qa_reset".equals(scenario)) {
                cancelDemo();
                hide();
                applyOnMain();
                return;
            }
            if ("sunroof_qa_dismiss".equals(scenario)) {
                dismissOnMain();
                return;
            }
            if (!"sunroof_qa_open".equals(scenario) && !"sunroof_qa_closed".equals(scenario)
                    && !"sunroof_qa_acc_on".equals(scenario)
                    && !"sunroof_qa_acc_off".equals(scenario)) return;
            if (!qaMode) {
                cancelDemo();
                qaMode = true;
                qaRoofKnown = true;
                qaRoofOpen = false;
                qaAccKnown = true;
                qaAccOn = true;
                qaPolicy.update(qaInput());
            }
            if ("sunroof_qa_open".equals(scenario) || "sunroof_qa_closed".equals(scenario)) {
                qaRoofKnown = true;
                qaRoofOpen = "sunroof_qa_open".equals(scenario);
            } else {
                qaAccKnown = true;
                qaAccOn = "sunroof_qa_acc_on".equals(scenario);
            }
            demo = qaPolicy.update(qaInput());
            applyOnMain();
        });
    }

    public String statusSummary() {
        return status + "\n" + observer.statusSummary();
    }

    public static boolean canDrawOverlays(Context context) {
        return Build.VERSION.SDK_INT < 23 || Settings.canDrawOverlays(context);
    }

    private void ensureStarted() {
        if (running) return;
        running = true;
        registerReceivers();
        main.removeCallbacks(refresh);
        main.postDelayed(refresh, 1_000L);
    }

    private void applyOnMain() {
        if (!running) return;
        boolean enabled = AppSettings.sunroofOverlayEnabled(app);
        if (realSnapshotTaken && lastEnabled && !enabled) cancelDemo();
        lastEnabled = enabled;
        if (enabled && !observerRunning) {
            observerRunning = true;
            observer.start();
        } else if (!enabled && observerRunning) {
            observerRunning = false;
            observer.stop();
            accKnown = false;
            asleep = false;
        }
        AdapterGateway gateway = AdapterGateway.get(app);
        VehicleState vehicle = StateStore.vehicle();
        boolean connected = gateway.usbReady();
        long epoch = gateway.connectionEpoch();
        boolean realChanged = realSnapshotTaken && (lastRealRoofKnown != vehicle.sunroofKnown
                || (vehicle.sunroofKnown && lastRealRoofOpen != vehicle.sunroofOpen)
                || lastRealEpoch != epoch);
        realSnapshotTaken = true;
        lastRealRoofKnown = vehicle.sunroofKnown;
        lastRealRoofOpen = vehicle.sunroofOpen;
        lastRealEpoch = epoch;
        if (realChanged) cancelDemo();
        SunroofAlertPolicy.Decision real = policy.update(new SunroofAlertPolicy.Input(
                enabled, AppSettings.sunroofParkingWarningEnabled(app), connected, epoch,
                vehicle.sunroofKnown, vehicle.sunroofOpen, vehicle.sunroofConnectionEpoch,
                vehicle.sunroofReceivedElapsed, accKnown, accOn, SystemClock.elapsedRealtime()));
        boolean hazard = higherPriorityHazard();
        if (hazard) cancelDemo();
        if (hazard || asleep) {
            if (real.kind == SunroofAlertPolicy.Kind.OPENING
                    || real.kind == SunroofAlertPolicy.Kind.CLOSING) policy.finish(real.sequence);
            hide();
            status = hazard ? "Люк: приоритет предупреждения TPMS/RCTA" : "Люк: магнитола засыпает";
            return;
        }
        if (real.kind == SunroofAlertPolicy.Kind.PARKING_WARNING) cancelDemo();
        SunroofAlertPolicy.Decision next = demo == null ? real : demo;
        status = demo != null ? "Люк: демонстрация, данные автомобиля не изменены"
                : !enabled ? "Люк: оповещения выключены"
                : !connected ? "Люк: ожидается USB CAN"
                : !vehicle.sunroofKnown ? "Люк: ожидается состояние"
                : "Люк: " + (vehicle.sunroofOpen ? "открыт" : "закрыт")
                + (next.kind == SunroofAlertPolicy.Kind.PARKING_WARNING ? " · предупреждение" : "");
        if (next.kind == SunroofAlertPolicy.Kind.NONE) {
            hide();
            return;
        }
        if (!canDrawOverlays(app)) {
            hide();
            finishUnavailableTransient(next, demo != null);
            if (!permissionLogged) {
                permissionLogged = true;
                AppLog.line(app, "Sunroof overlay: нужно разрешение поверх окон");
            }
            return;
        }
        permissionLogged = false;
        show(next, demo != null);
    }

    private boolean higherPriorityHazard() {
        return (StateStore.rcta().active() && AppSettings.rctaOverlayEnabled(app))
                || TpmsAlertController.hasWarnings(app, StateStore.tpms());
    }

    private void previewOnMain(boolean open, boolean parking) {
        ensureStarted();
        applyOnMain();
        if (higherPriorityHazard() || asleep) return;
        cancelDemo();
        qaMode = true;
        qaAccKnown = true;
        qaAccOn = true;
        qaRoofKnown = true;
        qaRoofOpen = !open;
        qaPolicy.update(qaInput());
        qaRoofOpen = open;
        demo = qaPolicy.update(qaInput());
        if (parking) {
            qaAccOn = false;
            demo = qaPolicy.update(qaInput());
        }
        applyOnMain();
    }

    private SunroofAlertPolicy.Input qaInput() {
        // The independent QA clock also permits two opposite samples in the same UI tick.
        long now = qaClock = Math.max(SystemClock.elapsedRealtime(), qaClock + 1L);
        return new SunroofAlertPolicy.Input(true, true, true, 1L,
                qaRoofKnown, qaRoofOpen, 1L, now, qaAccKnown, qaAccOn, now);
    }

    private void cancelDemo() {
        demo = null;
        qaMode = false;
        qaRoofKnown = false;
        qaRoofOpen = false;
        qaAccKnown = false;
        qaAccOn = true;
        qaPolicy.reset();
    }

    private void dismissOnMain() {
        if (demo != null) {
            demo = qaPolicy.dismiss();
            if (demo.kind == SunroofAlertPolicy.Kind.NONE) demo = null;
        } else {
            policy.dismiss();
        }
        hide();
    }

    private void show(SunroofAlertPolicy.Decision decision, boolean demonstration) {
        boolean warning = decision.kind == SunroofAlertPolicy.Kind.PARKING_WARNING;
        boolean allowAudio = warning ? AppSettings.sunroofSoundEnabled(app)
                : AppSettings.sunroofTransitionSoundEnabled(app);
        sound.setEnabled(allowAudio);
        if (overlay != null && !overlay.isAttachedToWindow()) hide();
        if (overlay != null && shownSequence == decision.sequence
                && shownKind == decision.kind && shownDemo == demonstration) {
            if (allowAudio && !audioEnabled) sound.play(warning);
            audioEnabled = allowAudio;
            return;
        }
        hide();
        if (windowManager == null) return;
        SunroofOverlayView next = new SunroofOverlayView(windowContext);
        next.setSystemUiVisibility(android.view.View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                | android.view.View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                | android.view.View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION);
        try {
            windowManager.addView(next, layoutParams(warning));
            overlay = next;
            shownSequence = decision.sequence;
            shownKind = decision.kind;
            shownDemo = demonstration;
            next.present(decision.kind, this::dismissOnMain);
        } catch (RuntimeException error) {
            next.stopAnimations();
            try { windowManager.removeViewImmediate(next); } catch (RuntimeException ignored) { }
            sound.stop();
            finishUnavailableTransient(decision, demonstration);
            status = "Люк: окно недоступно";
            if (!windowFailureLogged) {
                windowFailureLogged = true;
                AppLog.line(app, "Sunroof overlay add failed " + error.getClass().getSimpleName());
            }
            return;
        }
        windowFailureLogged = false;
        sound.setEnabled(allowAudio);
        audioEnabled = allowAudio;
        if (allowAudio) sound.play(warning);
        if (!warning) {
            long sequence = decision.sequence;
            long current = generation;
            finishTransient = () -> {
                if (generation != current || shownSequence != sequence
                        || shownDemo != demonstration) return;
                if (demonstration) demo = qaPolicy.finish(sequence);
                else policy.finish(sequence);
                hide();
                applyOnMain();
            };
            main.postDelayed(finishTransient, SunroofOverlayView.TRANSIENT_DURATION_MS);
        }
    }

    private void hide() {
        if (finishTransient != null) main.removeCallbacks(finishTransient);
        finishTransient = null;
        sound.stop();
        audioEnabled = false;
        SunroofOverlayView previous = overlay;
        overlay = null;
        shownSequence = Long.MIN_VALUE;
        shownKind = SunroofAlertPolicy.Kind.NONE;
        if (previous != null) {
            previous.stopAnimations();
            if (windowManager != null) {
                try { windowManager.removeViewImmediate(previous); } catch (RuntimeException ignored) { }
            }
        }
    }

    private void finishUnavailableTransient(SunroofAlertPolicy.Decision decision, boolean demonstration) {
        if (decision.kind != SunroofAlertPolicy.Kind.OPENING
                && decision.kind != SunroofAlertPolicy.Kind.CLOSING) return;
        if (demonstration) demo = qaPolicy.finish(decision.sequence);
        else policy.finish(decision.sequence);
    }

    private WindowManager.LayoutParams layoutParams(boolean warning) {
        int flags = WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS;
        if (!warning) flags |= WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                | WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE;
        WindowManager.LayoutParams params = new WindowManager.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT,
                overlayWindowType(), flags, PixelFormat.TRANSLUCENT);
        params.gravity = Gravity.TOP | Gravity.START;
        params.setTitle("Kia sunroof");
        if (!warning && Build.VERSION.SDK_INT >= 31) {
            // Android 12+ uses window alpha, rather than transparent canvas pixels, when
            // deciding whether touches may pass through an application overlay.
            params.alpha = .79f;
            try {
                InputManager input = app.getSystemService(InputManager.class);
                if (input != null) {
                    params.alpha = Math.max(0f, Math.min(params.alpha,
                            input.getMaximumObscuringOpacityForTouch()));
                }
            } catch (RuntimeException ignored) {
                // The platform default limit is 0.8 when the optional setting is unavailable.
            }
        }
        if (Build.VERSION.SDK_INT >= 28) {
            params.layoutInDisplayCutoutMode =
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES;
        }
        return params;
    }

    @SuppressLint("UnspecifiedRegisterReceiverFlag")
    private void registerReceivers() {
        if (stateReceiver != null) return;
        stateReceiver = new BroadcastReceiver() {
            @Override public void onReceive(Context context, Intent intent) { apply(); }
        };
        systemReceiver = new BroadcastReceiver() {
            @Override public void onReceive(Context context, Intent intent) {
                String action = intent.getAction();
                if (Intent.ACTION_SCREEN_OFF.equals(action)
                        || Intent.ACTION_CLOSE_SYSTEM_DIALOGS.equals(action)) dismissCurrent();
            }
        };
        IntentFilter states = new IntentFilter(AppIds.ACTION_STATE_CHANGED);
        IntentFilter system = new IntentFilter(Intent.ACTION_SCREEN_OFF);
        system.addAction(Intent.ACTION_CLOSE_SYSTEM_DIALOGS);
        if (Build.VERSION.SDK_INT >= 33) {
            app.registerReceiver(stateReceiver, states, Context.RECEIVER_NOT_EXPORTED);
            app.registerReceiver(systemReceiver, system, Context.RECEIVER_EXPORTED);
        } else {
            app.registerReceiver(stateReceiver, states);
            app.registerReceiver(systemReceiver, system);
        }
    }

    private void unregisterReceivers() {
        if (stateReceiver != null) {
            try { app.unregisterReceiver(stateReceiver); } catch (RuntimeException ignored) { }
        }
        if (systemReceiver != null) {
            try { app.unregisterReceiver(systemReceiver); } catch (RuntimeException ignored) { }
        }
        stateReceiver = null;
        systemReceiver = null;
    }

    private synchronized void postCurrent(Runnable action) {
        long current = generation;
        Runnable guarded = () -> {
            if (current == generation) action.run();
        };
        if (Looper.myLooper() == Looper.getMainLooper()) guarded.run();
        else main.post(guarded);
    }

    private static Context createWindowContext(Context context) {
        if (Build.VERSION.SDK_INT < 30) return context;
        try {
            WindowManager manager = (WindowManager) context.getSystemService(Context.WINDOW_SERVICE);
            Display display = manager == null ? null : manager.getDefaultDisplay();
            Context displayContext = display == null ? context : context.createDisplayContext(display);
            return displayContext.createWindowContext(overlayWindowType(), null);
        } catch (RuntimeException ignored) {
            return context;
        }
    }

    private static int overlayWindowType() {
        return Build.VERSION.SDK_INT >= 26 ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                : WindowManager.LayoutParams.TYPE_PHONE;
    }
}
