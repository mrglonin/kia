package kia.app.sunroof;

import android.annotation.SuppressLint;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.PixelFormat;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.os.SystemClock;
import android.provider.Settings;
import android.util.DisplayMetrics;
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
import kia.app.tpms.TpmsWarningOverlayController;

/** Owns roof windows and audio; all vehicle decisions and Android mutations run on main. */
public final class SunroofOverlayController {
    private static SunroofOverlayController instance;
    static final long DEFERRED_TRANSITION_TTL_MS = 10_000L;
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
    private volatile String diagnostics = "running=false window=false";
    private String lastDiagnosticKey = "";
    private boolean running;
    private boolean observerRunning;
    private boolean accKnown;
    private boolean accOn;
    private boolean asleep;
    private boolean screenOff;
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
    private boolean overlayWasAttached;
    private long shownSequence = Long.MIN_VALUE;
    private SunroofAlertPolicy.Kind shownKind = SunroofAlertPolicy.Kind.NONE;
    private boolean shownDemo;
    private boolean audioEnabled;
    // Audio belongs to a real warning episode, independently of its window attachment.
    private long realWarningAudioSequence = Long.MIN_VALUE;
    private long realSequence = Long.MIN_VALUE;
    private long realSequenceStartedAt;
    private boolean realSequenceDeferred;
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
                    SunroofStateMemory.observeAcc(app, known, on);
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
                    if (sleeping) {
                        cancelDemo();
                    }
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
            screenOff = false;
            realSnapshotTaken = false;
            realSequence = Long.MIN_VALUE;
            realWarningAudioSequence = Long.MIN_VALUE;
            realSequenceDeferred = false;
            status = "Люк: наблюдение выключено";
            reportDecision("stopped", null, AppSettings.sunroofOverlayEnabled(app), false,
                    lastRealEpoch, StateStore.vehicle());
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
        return status + "\n" + diagnostics + "\n" + observer.statusSummary();
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
        SunroofStateMemory.Snapshot remembered = SunroofStateMemory.snapshot(app);
        PowerManager power = (PowerManager) app.getSystemService(Context.POWER_SERVICE);
        if (power != null) screenOff = !power.isInteractive();
        boolean connected = gateway.usbReady();
        long epoch = gateway.connectionEpoch();
        boolean realChanged = realSnapshotTaken && (lastRealRoofKnown != vehicle.sunroofKnown
                || (vehicle.sunroofKnown && lastRealRoofOpen != vehicle.sunroofOpen)
                || lastRealEpoch != epoch);
        realSnapshotTaken = true;
        lastRealRoofKnown = vehicle.sunroofKnown;
        lastRealRoofOpen = vehicle.sunroofOpen;
        lastRealEpoch = epoch;
        if (realChanged) {
            cancelDemo();
            AppLog.line(app, "Sunroof source: known=" + vehicle.sunroofKnown
                    + " open=" + vehicle.sunroofOpen + " usbEpoch=" + epoch
                    + " roofEpoch=" + vehicle.sunroofConnectionEpoch
                    + " roofAt=" + vehicle.sunroofReceivedElapsed);
        }
        long now = SystemClock.elapsedRealtime();
        SunroofAlertPolicy.Decision real = policy.update(new SunroofAlertPolicy.Input(
                enabled, AppSettings.sunroofParkingWarningEnabled(app), connected, epoch,
                vehicle.sunroofKnown, vehicle.sunroofOpen, vehicle.sunroofConnectionEpoch,
                vehicle.sunroofReceivedElapsed, accKnown, accOn, now,
                remembered.known, remembered.open, remembered.acknowledged));
        if (real.sequence != realSequence) {
            realSequence = real.sequence;
            realSequenceStartedAt = now;
            realSequenceDeferred = false;
        }
        boolean hazard = higherPriorityHazard();
        if (hazard) cancelDemo();
        if (asleep || screenOff) {
            if (real.kind == SunroofAlertPolicy.Kind.OPENING
                    || real.kind == SunroofAlertPolicy.Kind.CLOSING) policy.finish(real.sequence);
            if (real.kind == SunroofAlertPolicy.Kind.PARKING_WARNING
                    && !hazard && canDrawOverlays(app)) {
                // CC4PRO delivers ACC OFF and cmd8 sleep almost together. Hide the window
                // without truncating the finite warning, including sleep-before-ACC order.
                hideWindow();
                sound.setEnabled(true);
                playDecisionAudio(real, false);
            } else {
                hide();
            }
            status = screenOff ? "Люк: экран выключен" : "Люк: магнитола засыпает";
            reportDecision(screenOff ? "screen_off" : "sleep", real, enabled, connected, epoch, vehicle);
            return;
        }
        if (hazard) {
            if (real.kind == SunroofAlertPolicy.Kind.OPENING
                    || real.kind == SunroofAlertPolicy.Kind.CLOSING) {
                realSequenceDeferred = true;
                if (now - realSequenceStartedAt >= DEFERRED_TRANSITION_TTL_MS) {
                    policy.finish(real.sequence);
                }
            }
            hide();
            status = "Люк: ожидает закрытия критического предупреждения TPMS/RCTA";
            reportDecision("critical_defer", real, enabled, connected, epoch, vehicle);
            return;
        }
        if (realSequenceDeferred) {
            if (now - realSequenceStartedAt >= DEFERRED_TRANSITION_TTL_MS
                    && (real.kind == SunroofAlertPolicy.Kind.OPENING
                    || real.kind == SunroofAlertPolicy.Kind.CLOSING)) {
                real = policy.finish(real.sequence);
            }
            // Once an eligible deferred event is shown, let its full animation complete.
            realSequenceDeferred = false;
        }
        if (real.kind == SunroofAlertPolicy.Kind.PARKING_WARNING) cancelDemo();
        SunroofAlertPolicy.Decision next = demo == null ? real : demo;
        boolean usingMemory = remembered.known && (!connected || !vehicle.sunroofKnown
                || vehicle.sunroofConnectionEpoch != epoch);
        status = demo != null ? "Люк: демонстрация, данные автомобиля не изменены"
                : !enabled ? "Люк: оповещения выключены"
                : usingMemory ? "Люк: последнее сохранённое — "
                + (remembered.open ? "открыт" : "закрыт")
                + (next.kind == SunroofAlertPolicy.Kind.PARKING_WARNING ? " · предупреждение" : "")
                : !connected ? "Люк: ожидается USB CAN"
                : !vehicle.sunroofKnown ? "Люк: ожидается состояние"
                : "Люк: " + (vehicle.sunroofOpen ? "открыт" : "закрыт")
                + (next.kind == SunroofAlertPolicy.Kind.PARKING_WARNING ? " · предупреждение" : "");
        if (next.kind == SunroofAlertPolicy.Kind.NONE) {
            hide();
            reportDecision("none", next, enabled, connected, epoch, vehicle);
            return;
        }
        if (!canDrawOverlays(app)) {
            hide();
            finishUnavailableTransient(next, demo != null);
            if (!permissionLogged) {
                permissionLogged = true;
                AppLog.line(app, "Sunroof overlay: нужно разрешение поверх окон");
            }
            reportDecision("permission", next, enabled, connected, epoch, vehicle);
            return;
        }
        permissionLogged = false;
        show(next, demo != null);
        reportDecision(overlay == null ? "window_failed" : "shown", next,
                enabled, connected, epoch, vehicle);
    }

    private void reportDecision(String reason, SunroofAlertPolicy.Decision decision,
                                boolean enabled, boolean connected, long epoch,
                                VehicleState vehicle) {
        boolean permission = canDrawOverlays(app);
        boolean shown = overlay != null && overlay.isAttachedToWindow();
        SunroofStateMemory.Snapshot remembered = SunroofStateMemory.snapshot(app);
        String facts = "reason=" + reason + " running=" + running + " master=" + enabled
                + " permission=" + permission + " window=" + shown
                + " usb=" + connected + " usbEpoch=" + epoch
                + " roofKnown=" + vehicle.sunroofKnown + " roofOpen=" + vehicle.sunroofOpen
                + " roofEpoch=" + vehicle.sunroofConnectionEpoch
                + " cacheKnown=" + remembered.known + " cacheOpen=" + remembered.open
                + " cacheAck=" + remembered.acknowledged
                + " accKnown=" + accKnown + " accOn=" + accOn + " asleep=" + asleep
                + " screenOff=" + screenOff
                + " kind=" + (decision == null ? SunroofAlertPolicy.Kind.NONE : decision.kind)
                + " sequence=" + (decision == null
                ? realSequence == Long.MIN_VALUE ? -1L : realSequence : decision.sequence)
                + " demo=" + (demo != null);
        diagnostics = facts + " roofAt=" + vehicle.sunroofReceivedElapsed
                + " savedAt=" + remembered.recordedAt;
        // Timestamp-only CAN repeats and the 1-second service tick must not flood the log.
        if (!facts.equals(lastDiagnosticKey)) {
            lastDiagnosticKey = facts;
            AppLog.line(app, "Sunroof decision: " + diagnostics);
        }
    }

    private boolean higherPriorityHazard() {
        return (StateStore.rcta().active() && AppSettings.rctaOverlayEnabled(app))
                || (TpmsWarningOverlayController.get(app).isShowing()
                && TpmsAlertController.hasCriticalWarnings(app, StateStore.tpms()));
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
            if (policy.current().kind == SunroofAlertPolicy.Kind.PARKING_WARNING) {
                SunroofStateMemory.acknowledge(app);
            }
            policy.dismiss();
        }
        hide();
    }

    private void show(SunroofAlertPolicy.Decision decision, boolean demonstration) {
        boolean warning = decision.kind == SunroofAlertPolicy.Kind.PARKING_WARNING;
        boolean allowAudio = warning || AppSettings.sunroofTransitionSoundEnabled(app);
        sound.setEnabled(allowAudio);
        boolean preserveWarningAudio = warning && !demonstration
                && realWarningAudioSequence == decision.sequence;
        // addView can return before the first attachment callback. A second state
        // broadcast in that gap must keep the pending window and its one-shot audio.
        if (overlay != null && overlayWasAttached && !overlay.isAttachedToWindow()) {
            if (preserveWarningAudio) hideWindow();
            else hide();
        }
        if (overlay != null && shownSequence == decision.sequence
                && shownKind == decision.kind && shownDemo == demonstration) {
            if (allowAudio && !audioEnabled) playDecisionAudio(decision, demonstration);
            audioEnabled = allowAudio;
            return;
        }
        if (preserveWarningAudio) hideWindow();
        else hide();
        if (windowManager == null) return;
        SunroofOverlayView next = new SunroofOverlayView(windowContext);
        next.addOnAttachStateChangeListener(new android.view.View.OnAttachStateChangeListener() {
            @Override public void onViewAttachedToWindow(android.view.View view) {
                if (overlay == view) overlayWasAttached = true;
            }
            @Override public void onViewDetachedFromWindow(android.view.View view) {
                // The next apply distinguishes a detached old window from a pending new one.
            }
        });
        float carWidthPx = SunroofOverlayGeometry.carWidthPx(displayWidthPx());
        next.setCompactCarWidthPx(warning ? 0f : carWidthPx);
        next.setSystemUiVisibility(android.view.View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                | android.view.View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                | android.view.View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION);
        try {
            windowManager.addView(next, layoutParams(warning, carWidthPx));
            overlay = next;
            overlayWasAttached = next.isAttachedToWindow();
            shownSequence = decision.sequence;
            shownKind = decision.kind;
            shownDemo = demonstration;
            next.present(decision.kind, this::dismissOnMain);
        } catch (RuntimeException error) {
            next.stopAnimations();
            try { windowManager.removeViewImmediate(next); } catch (RuntimeException ignored) { }
            if (!preserveWarningAudio) sound.stop();
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
        if (allowAudio) playDecisionAudio(decision, demonstration);
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
        sound.stop();
        hideWindow();
    }

    private void playDecisionAudio(SunroofAlertPolicy.Decision decision, boolean demonstration) {
        if (!demonstration && decision.kind == SunroofAlertPolicy.Kind.PARKING_WARNING) {
            if (realWarningAudioSequence == decision.sequence) return;
            // Consume before attempting playback: failure, focus loss and later wake must
            // not retry the same warning. Closing/ACK/ACC ON still cancel through hide().
            realWarningAudioSequence = decision.sequence;
        }
        sound.play(decision.kind);
    }

    private void hideWindow() {
        if (finishTransient != null) main.removeCallbacks(finishTransient);
        finishTransient = null;
        audioEnabled = false;
        SunroofOverlayView previous = overlay;
        overlay = null;
        overlayWasAttached = false;
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

    private WindowManager.LayoutParams layoutParams(boolean warning, float carWidthPx) {
        int flags = WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS;
        if (!warning) flags |= WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                | WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE;
        WindowManager.LayoutParams params = new WindowManager.LayoutParams(
                warning ? ViewGroup.LayoutParams.MATCH_PARENT
                        : SunroofOverlayGeometry.compactWidthPx(carWidthPx),
                warning ? ViewGroup.LayoutParams.MATCH_PARENT
                        : SunroofOverlayGeometry.compactHeightPx(carWidthPx),
                overlayWindowType(), flags, PixelFormat.TRANSLUCENT);
        params.gravity = warning ? Gravity.TOP | Gravity.START : Gravity.CENTER;
        params.setTitle("Kia sunroof");
        params.alpha = 1f;
        if (Build.VERSION.SDK_INT >= 28) {
            params.layoutInDisplayCutoutMode =
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES;
        }
        return params;
    }

    private int displayWidthPx() {
        if (Build.VERSION.SDK_INT >= 30 && windowManager != null) {
            try {
                int width = windowManager.getCurrentWindowMetrics().getBounds().width();
                if (width > 0) return width;
            } catch (RuntimeException ignored) {
                // Some vendor firmware does not supply metrics for an overlay window context.
            }
        }
        DisplayMetrics metrics = new DisplayMetrics();
        Display display = windowManager == null ? null : windowManager.getDefaultDisplay();
        if (display != null) display.getRealMetrics(metrics);
        else metrics.setTo(app.getResources().getDisplayMetrics());
        return metrics.widthPixels;
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
                if (Intent.ACTION_SCREEN_OFF.equals(action)) {
                    screenOff = true;
                    cancelDemo();
                    applyOnMain();
                } else if (Intent.ACTION_SCREEN_ON.equals(action)) {
                    screenOff = false;
                    asleep = false;
                    apply();
                } else if (Intent.ACTION_CLOSE_SYSTEM_DIALOGS.equals(action)) {
                    String reason = intent.getStringExtra("reason");
                    if ("homekey".equals(reason) || "recentapps".equals(reason)) {
                        dismissCurrent();
                    }
                }
            }
        };
        IntentFilter states = new IntentFilter(AppIds.ACTION_STATE_CHANGED);
        IntentFilter system = new IntentFilter(Intent.ACTION_SCREEN_OFF);
        system.addAction(Intent.ACTION_SCREEN_ON);
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
