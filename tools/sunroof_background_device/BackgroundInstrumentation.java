package kia.app.sunroof.backgroundtest;

import android.app.Activity;
import android.app.Instrumentation;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.os.Bundle;
import android.os.Binder;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.Parcel;
import android.os.ParcelFileDescriptor;
import android.os.RemoteException;
import android.os.SystemClock;
import android.util.DisplayMetrics;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.CompoundButton;
import android.widget.TextView;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Proxy;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.atomic.AtomicReference;

import org.json.JSONArray;
import org.json.JSONObject;

import kia.app.core.StateStore;
import kia.app.core.model.VehicleState;
import kia.app.core.settings.AppSettings;
import kia.app.entry.AppService;
import kia.app.entry.MainActivity;
import kia.app.protocol.adapter.AdapterGateway;
import kia.app.protocol.adapter.AdapterProtocol;
import kia.app.protocol.adapter.FrameRouter;
import kia.app.sunroof.SunroofAlertPolicy;
import kia.app.sunroof.SunroofOverlayController;
import kia.app.sunroof.SunroofStateMemory;
import kia.app.sunroof.TeyesSunroofObserver;
import kia.app.tpms.TpmsAlertController;
import kia.app.tpms.TpmsWarningOverlayController;

/** Real Android integration test. Only USB hardware and native ACC input are replaced. */
public final class BackgroundInstrumentation extends Instrumentation {
    private static final long EPOCH = 99L;
    private static final String[] TEST_SETTINGS = {
            "media_profile_configured", "sunroof_overlay", "sunroof_parking_warning",
            "sunroof_sound", "sunroof_transition_sound", "rcta_overlay", "tpms_alerts",
            "tpms_sound_alerts", "tpms_low_pressure", "tpms_high_pressure", "tpms_low_temp", "tpms_high_temp"
    };
    private final JSONArray results = new JSONArray();
    private final Handler main = new Handler(Looper.getMainLooper());
    private Context app;
    private Activity activity;
    private Object transport;
    private FrameRouter router;
    private SunroofOverlayController controller;
    private File output;
    private volatile boolean keepAlive;
    private int assertions;
    private int failures;
    private Map<String, ?> oldSettings;
    private String phase = "main";
    private int roofPacketsInjected;
    private JSONObject warningAudioProof;
    private final JSONArray audioProofs = new JSONArray();
    private final JSONObject audioAssets = new JSONObject();
    private final JSONArray warningPlaybackProbes = new JSONArray();
    private final JSONArray startupProbes = new JSONArray();
    private final JSONArray roofInputs = new JSONArray();
    private final JSONArray teyesInputs = new JSONArray();
    private final JSONArray teyesProbes = new JSONArray();
    private long usbEpoch = EPOCH;
    private TeyesServiceBoundary teyesService;

    private final Runnable heartbeat = new Runnable() {
        @Override public void run() {
            if (!keepAlive) return;
            try { synchronized (transport) { set(transport, "lastRxAtElapsed", SystemClock.elapsedRealtime()); } }
            catch (Exception error) { throw new RuntimeException(error); }
            main.postDelayed(this, 250L);
        }
    };

    @Override public void onCreate(Bundle arguments) {
        super.onCreate(arguments);
        if (arguments != null) phase = arguments.getString("phase", "main");
        start();
    }

    @Override public void onStart() {
        JSONObject report = new JSONObject();
        try {
            app = getTargetContext();
            output = new File(app.getExternalFilesDir(null), "sunroof-background-regression");
            output.mkdirs();
            oldSettings = app.getSharedPreferences("Kia", Context.MODE_PRIVATE).getAll();
            if ("startup".equals(phase) || "teyes".equals(phase) || "sleep_audio".equals(phase)) {
                // This phase runs in a force-stopped emulator process. Do not manufacture a
                // CLOSED roof packet: the first accepted source observation must be OPEN.
                check(app.getSharedPreferences("kia_sunroof_state", Context.MODE_PRIVATE)
                        .edit().clear().commit(), "cold-start roof memory cleared before service launch");
                check(!AppService.isRunning(), "cold-start phase begins before AppService launch");
                check(!StateStore.vehicle().sunroofKnown, "cold-start live roof begins unknown");
                check(!SunroofStateMemory.snapshot(app).known, "cold-start saved roof begins unknown");
            }
            onMain(() -> {
                AppSettings.applyDefaults(app);
                AppSettings.setMediaProfileConfigured(app, true);
                AppSettings.setSunroofOverlayEnabled(app, true);
                AppSettings.setSunroofParkingWarningEnabled(app, true);
                AppSettings.setSunroofSoundEnabled(app, false);
                AppSettings.setSunroofTransitionSoundEnabled(app, false);
                AppSettings.setRctaOverlayEnabled(app, false);
                AppSettings.setTpmsAlertsEnabled(app, true);
                AppSettings.setTpmsSoundAlertsEnabled(app, false);
                AppSettings.setTpmsLowPressureKpa(app, 200);
                AppSettings.setTpmsHighPressureKpa(app, 330);
                AppSettings.setTpmsLowTempC(app, -20);
                AppSettings.setTpmsHighTempC(app, 80);
                return null;
            });
            Intent launch = new Intent(app, MainActivity.class);
            launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
            activity = startActivitySync(launch);
            waitForIdleSync();
            waitUntil(() -> AppService.isRunning(), 4000L, "foreground service started");
            controller = onMain(() -> SunroofOverlayController.get(app));
            if ("sleep_audio".equals(phase)) {
                runSleepAudioPhase();
                completeReport(report);
                return;
            }
            if ("teyes".equals(phase)) {
                runTeyesPhase();
                completeReport(report);
                return;
            }
            if ("startup".equals(phase)) {
                runStartupPhase();
                completeReport(report);
                return;
            }
            if ("restart".equals(phase)) {
                runRestartPhase();
                completeReport(report);
                return;
            }
            if ("ui".equals(phase)) {
                scenario("actual_general_settings_have_one_transition_sound_no_previews", this::verifyGeneralSettings);
                completeReport(report);
                return;
            }
            if ("audio".equals(phase)) {
                runAudioPhase();
                completeReport(report);
                return;
            }
            scenario("actual_general_settings_have_one_transition_sound_no_previews", this::verifyGeneralSettings);
            installUsbBoundary();
            injectTpms(new int[]{128, 128, 128, 128});
            injectRoof(false);
            waitForIdleSync();
            shell("input keyevent KEYCODE_HOME");
            waitUntil(() -> !activityVisible(), 2500L, "MainActivity moved behind Home");
            SystemClock.sleep(250L);
            waitForIdleSync();

            scenario("background_normal_open_opaque_geometry", () -> {
                injectRoof(true);
                assertRoofWindow(true, false);
            });
            scenario("background_minor_tpms_open", () -> {
                dismissRoof();
                injectRoof(false);
                dismissRoof();
                injectTpms(new int[]{192, 193, 194, 192});
                injectRoof(true);
                assertRoofWindow(true, false);
            });
            scenario("background_minor_tpms_close", () -> {
                dismissRoof();
                injectRoof(false);
                assertRoofWindow(false, false);
            });
            scenario("finished_activity_real_open", () -> {
                dismissRoof();
                onMain(() -> { activity.finish(); return null; });
                waitUntil(() -> onMain(() -> activity.isDestroyed()), 2500L, "MainActivity destroyed");
                injectRoof(true);
                assertRoofWindow(true, false);
            });
            scenario("visible_critical_tpms_defers_then_ack_releases", () -> {
                dismissRoof();
                injectTpms(new int[]{209, 220, 215, 209});
                waitUntil(() -> tpmsShowing(), 1800L, "critical TPMS window visible");
                injectRoof(false);
                injectRoof(true);
                SystemClock.sleep(250L);
                check(!windowShowing(), "roof must defer while critical TPMS window is visible");
                onMain(() -> { TpmsWarningOverlayController.get(app).dismissCurrent(); return null; });
                waitUntil(() -> !tpmsShowing(), 1800L, "critical TPMS acknowledged");
                assertRoofWindow(true, false);
            });
            scenario("acknowledged_critical_state_does_not_block_close", () -> {
                dismissRoof();
                check(TpmsAlertController.hasWarnings(app, StateStore.tpms()), "critical data remains stored after acknowledgement");
                check(!tpmsShowing(), "TPMS window remains acknowledged");
                injectRoof(false);
                assertRoofWindow(false, false);
            });
            scenario("background_real_policy_acc_off_warning", () -> {
                dismissRoof();
                injectRoof(true);
                dismissRoof();
                nativeAcc(true);
                nativeAcc(false);
                assertActualWarningAudio();
                assertRoofWindow(true, true);
                capture("background_warning_before_tap");
                shell("input tap 640 400");
                waitUntil(() -> !windowShowing(), 2000L, "warning dismissed by real input tap");
            });
            scenario("cached_open_warns_after_disconnect_without_new_packet", () -> {
                dismissRoof();
                nativeAcc(true);
                injectRoof(true);
                dismissRoof();
                disconnectUsbBoundary();
                int beforeOff = roofPacketsInjected;
                SystemClock.sleep(4200L);
                nativeAcc(false);
                assertRoofWindow(true, true, false);
                check(roofPacketsInjected == beforeOff, "no roof packet arrived at ACC OFF");
            });
            scenario("screen_sleep_preserves_unacknowledged_cached_warning", () -> {
                int beforeSleep = roofPacketsInjected;
                shell("input keyevent KEYCODE_SLEEP");
                waitUntil(() -> !interactive(), 2000L, "Android screen actually sleeps");
                waitUntil(() -> !windowShowing(), 1800L, "roof window hides while screen sleeps");
                SystemClock.sleep(1100L);
                check(!windowShowing(), "one-second refresh cannot replay the sleeping window");
                waitUntil(() -> onMain(() -> actualMediaPlayer() == null), 3300L,
                        "finite warning finishes while the Android screen remains asleep");
                check(!app.getSharedPreferences("kia_sunroof_state", Context.MODE_PRIVATE)
                        .getBoolean("warning_acknowledged", false), "screen sleep does not acknowledge the real roof warning");
                shell("input keyevent KEYCODE_WAKEUP");
                shell("wm dismiss-keyguard");
                waitUntil(this::interactive, 2000L, "Android screen wakes");
                nativeAcc(false);
                assertRoofWindow(true, true, false);
                check(roofPacketsInjected == beforeSleep, "sleep/wake used no new roof packet");
            });
            scenario("real_closed_cache_prevents_warning_without_new_packet", () -> {
                dismissRoof();
                nativeAcc(true);
                installUsbBoundary();
                injectRoof(false);
                dismissRoof();
                disconnectUsbBoundary();
                int beforeOff = roofPacketsInjected;
                SystemClock.sleep(4200L);
                nativeAcc(false);
                SystemClock.sleep(400L);
                check(!windowShowing(), "real closed roof replaces the persisted open state");
                check(roofPacketsInjected == beforeOff, "closed-cache decision received no new roof packet");
            });
            scenario("seed_real_open_for_process_restart", () -> {
                dismissRoof();
                nativeAcc(true);
                installUsbBoundary();
                injectRoof(true);
                dismissRoof();
                SystemClock.sleep(500L);
                check(StateStore.vehicle().sunroofKnown && StateStore.vehicle().sunroofOpen,
                        "actual open packet seeds the restart cache");
                check("0x76".equals(StateStore.vehicle().sunroofSource), "restart seed has actual adapter source");
            });
            completeReport(report);
        } catch (Throwable error) {
            failures++;
            try { report.put("fatal", android.util.Log.getStackTraceString(error)); } catch (Exception ignored) { }
        } finally {
            try {
                shell("input keyevent KEYCODE_WAKEUP");
                shell("wm dismiss-keyguard");
                keepAlive = false;
                main.removeCallbacks(heartbeat);
                if (controller != null) onMain(() -> { controller.dismissCurrent(); return null; });
                if (app != null && oldSettings != null) restoreSettings();
                report.put("failures", failures);
                if (output != null) write(new File(output, "report.json"), report.toString(2));
            } catch (Throwable error) { failures++; }
            Bundle summary = new Bundle();
            summary.putString("stream", report.toString() + "\n");
            summary.putString("reportPath", output == null ? "" : new File(output, "report.json").getAbsolutePath());
            finish(failures == 0 ? Activity.RESULT_OK : Activity.RESULT_CANCELED, summary);
        }
    }

    private void runStartupPhase() throws Exception {
        JSONObject opening = readPcmWav("sunroof_open_voice", 24000, 1);
        JSONObject closing = readPcmWav("sunroof_close_voice", 24000, 1);
        audioAssets.put("opening_voice", opening);
        audioAssets.put("closing_voice", closing);
        installUsbBoundary();
        injectTpms(new int[]{128, 128, 128, 128});
        onMain(() -> {
            AppSettings.setSunroofTransitionSoundEnabled(app, true);
            controller.apply();
            return null;
        });
        shell("input keyevent KEYCODE_HOME");
        waitUntil(() -> !activityVisible(), 2500L, "cold-start Activity moved behind Home");
        check(roofPacketsInjected == 0 && roofInputs.length() == 0,
                "no synthetic CLOSED baseline or any earlier roof packet was injected");
        check(!StateStore.vehicle().sunroofKnown && !SunroofStateMemory.snapshot(app).known,
                "both live and persisted roof are unknown before first OPEN");
        check(!windowShowing() && onMain(() -> actualMediaPlayer() == null),
                "unknown roof has no window or player before first OPEN");
        startupProbe("before_first_packet");

        scenario("cold_start_first_real_open_with_no_closed_baseline", () -> {
            injectRoof(true);
            check(roofPacketsInjected == 1 && roofInputs.getJSONObject(0).getBoolean("open"),
                    "the first and only initial roof packet is real OPEN through FrameRouter");
            startupProbe("first_open_delivered");
            waitUntil(() -> onMain(() -> roofDecision().kind == SunroofAlertPolicy.Kind.OPENING),
                    1500L, "first real OPEN creates an OPENING episode");
            assertRoofWindow(true, false);
            assertAudioCue("cold_start_first_opening_voice", opening);
            android.media.MediaPlayer firstPlayer = onMain(this::actualMediaPlayer);
            long firstSequence = onMain(() -> roofDecision().sequence);
            for (int duplicate = 0; duplicate < 3; duplicate++) {
                injectRoof(true);
                onMain(() -> { controller.apply(); return null; });
                check(onMain(() -> roofDecision().sequence == firstSequence),
                        "duplicate OPEN does not create another presentation sequence");
                check(onMain(() -> actualMediaPlayer() == firstPlayer),
                        "duplicate OPEN retains the same actual MediaPlayer");
                startupProbe("first_open_duplicate_" + (duplicate + 1));
                SystemClock.sleep(60L);
            }
            capture("cold_start_first_open_without_closed_baseline");
            waitUntil(() -> onMain(() -> actualMediaPlayer() == null),
                    opening.getInt("expectedDurationMs") + 900L,
                    "first-opening voice completes and releases MediaPlayer");
            injectRoof(true);
            onMain(() -> { controller.apply(); return null; });
            SystemClock.sleep(150L);
            check(onMain(() -> actualMediaPlayer() == null),
                    "duplicate OPEN after natural completion cannot replay the opening voice");
            check(onMain(() -> roofDecision().sequence == firstSequence
                            || roofDecision().kind == SunroofAlertPolicy.Kind.NONE),
                    "duplicate after completion does not start a new opening episode");
            startupProbe("duplicate_after_first_voice_completed");
        });
        scenario("cold_start_real_close_after_first_open", () -> {
            injectRoof(false);
            waitUntil(() -> onMain(() -> roofDecision().kind == SunroofAlertPolicy.Kind.CLOSING),
                    1500L, "real CLOSE after the first OPEN creates a CLOSING episode");
            assertRoofWindow(false, false);
            assertAudioCue("cold_start_closing_voice", closing);
            startupProbe("real_close_playing");
            capture("cold_start_close_after_first_open");
            waitUntil(() -> onMain(() -> actualMediaPlayer() == null),
                    closing.getInt("expectedDurationMs") + 900L,
                    "closing voice completes and releases MediaPlayer");
        });
        scenario("cold_start_real_reopen_after_close", () -> {
            injectRoof(true);
            waitUntil(() -> onMain(() -> roofDecision().kind == SunroofAlertPolicy.Kind.OPENING),
                    1500L, "real reopening still creates an OPENING episode");
            assertRoofWindow(true, false);
            assertAudioCue("cold_start_reopening_voice", opening);
            startupProbe("real_reopen_playing");
            capture("cold_start_reopen_after_close");
            waitUntil(() -> onMain(() -> actualMediaPlayer() == null),
                    opening.getInt("expectedDurationMs") + 900L,
                    "reopening voice completes and releases MediaPlayer");
        });
        scenario("warm_reconnect_closed_to_first_open_without_closed_reseed", () -> {
            injectRoof(false);
            waitUntil(() -> onMain(() -> roofDecision().kind == SunroofAlertPolicy.Kind.CLOSING),
                    1500L, "real CLOSED is accepted before warm reconnect");
            dismissRoof();
            check(!SunroofStateMemory.snapshot(app).open, "warm reconnect starts from a real saved CLOSED");
            disconnectUsbBoundary();
            onMain(() -> { controller.apply(); return null; });
            usbEpoch++;
            installUsbBoundary();
            int beforeOpen = roofPacketsInjected;
            injectRoof(true);
            check(roofPacketsInjected == beforeOpen + 1,
                    "warm reconnect receives only OPEN, without another CLOSED baseline");
            waitUntil(() -> onMain(() -> roofDecision().kind == SunroofAlertPolicy.Kind.OPENING),
                    1500L, "first OPEN after reconnect preserves the earlier real CLOSED transition");
            assertRoofWindow(true, false);
            assertAudioCue("warm_reconnect_first_opening_voice", opening);
            startupProbe("warm_reconnect_closed_to_open");
            waitUntil(() -> onMain(() -> actualMediaPlayer() == null),
                    opening.getInt("expectedDurationMs") + 900L,
                    "warm opening voice completes");
        });
        scenario("warm_reconnect_same_open_does_not_repeat", () -> {
            dismissRoof();
            check(SunroofStateMemory.snapshot(app).open, "repeat reconnect starts from accepted OPEN");
            disconnectUsbBoundary();
            onMain(() -> { controller.apply(); return null; });
            usbEpoch++;
            installUsbBoundary();
            injectRoof(true);
            onMain(() -> { controller.apply(); return null; });
            SystemClock.sleep(150L);
            check(onMain(() -> roofDecision().kind == SunroofAlertPolicy.Kind.NONE),
                    "same OPEN after reconnect does not become another opening episode");
            check(!windowShowing() && onMain(() -> actualMediaPlayer() == null),
                    "same OPEN after reconnect has no repeated window or voice");
            startupProbe("warm_reconnect_same_open_silent");
        });
    }

    private void runTeyesPhase() throws Exception {
        JSONObject warning = readPcmWav("sunroof_warning_adas", 44100, 2);
        audioAssets.put("mandatory_rear_adas_warning", warning);
        installTeyesServiceBoundary();
        shell("input keyevent KEYCODE_HOME");
        waitUntil(() -> !activityVisible(), 2500L, "TEYES phase Activity moved behind Home");
        installUsbBoundary();
        injectTpms(new int[]{128, 128, 128, 128});
        injectRoof(true);
        waitUntil(() -> SunroofStateMemory.snapshot(app).known && SunroofStateMemory.snapshot(app).open,
                1500L, "real OPEN is persisted before USB loss");
        dismissRoof();
        disconnectUsbBoundary();
        SystemClock.sleep(3500L); // Exceed the old disconnect grace without sending another roof packet.

        scenario("teyes_null_aggregate_retains_actual_registered_callback", () -> {
            waitUntil(() -> teyesService.registers == 1 && teyesService.polls >= 2
                            && teyesService.callback != null,
                    2000L, "actual observer registers once and polls a null aggregate repeatedly");
            check(teyesService.unregisters == 0, "null aggregate does not unregister the real callback");
            check(TeyesServiceBoundary.CALLBACK.equals(teyesService.callback.getInterfaceDescriptor()),
                    "actual observer exposes the retained TEYES callback descriptor");
            check(!onMain(() -> (Boolean) field(controller, "accKnown")), "null aggregate leaves ACC unknown");
            check(!windowShowing(), "unknown ACC does not invent a parking warning");
            teyesProbe("registered_null_aggregate");
        });
        scenario("teyes_malformed_callbacks_never_become_acc_off", () -> {
            IBinder callback = requireTeyesCallback();
            teyesCallback(callback, 13, 5, 2, 0);
            teyesCallback(callback, 13, 5, 1, 1);
            boolean rejected = false;
            try { teyesCallback(callback, 13, 5, 1, 0, 99); }
            catch (RuntimeException | RemoteException expected) { rejected = true; }
            check(rejected, "actual CallbackBinder rejects trailing malformed payload");
            rejected = false;
            try { teyesCallback(callback, 13, 5); }
            catch (RuntimeException | RemoteException expected) { rejected = true; }
            check(rejected, "actual CallbackBinder rejects truncated numeric payload");
            onMain(() -> null);
            check(!onMain(() -> (Boolean) field(controller, "accKnown")),
                    "invalid enum, second argument and trailing data cannot produce known ACC OFF");
            check(!windowShowing() && onMain(() -> actualMediaPlayer() == null),
                    "malformed callbacks do not warn despite a saved open roof");
            teyesProbe("malformed_callbacks_ignored");
        });
        scenario("teyes_cmd5_off_warns_from_saved_open_after_usb_loss_once", () -> {
            IBinder callback = requireTeyesCallback();
            int packets = roofPacketsInjected;
            teyesCallback(callback, 13, 5, 1, 0);
            waitUntil(() -> onMain(() -> roofDecision().kind == SunroofAlertPolicy.Kind.PARKING_WARNING),
                    1500L, "actual cmd5 OFF enters PARKING_WARNING");
            assertAudioCue("teyes_cmd5_mandatory_warning", warning, false);
            assertRoofWindow(true, true, false);
            check(roofPacketsInjected == packets && !AdapterGateway.get(app).usbReady(),
                    "warning uses saved real OPEN with no USB and no fresh roof packet");
            android.media.MediaPlayer player = onMain(this::actualMediaPlayer);
            long sequence = onMain(() -> roofDecision().sequence);
            for (int i = 0; i < 3; i++) {
                teyesCallback(callback, 13, 5, 1, 0);
                check(onMain(() -> actualMediaPlayer() == player && roofDecision().sequence == sequence),
                        "duplicate cmd5 OFF does not restart the active warning player or sequence");
            }
            teyesProbe("cmd5_warning_playing");
            waitUntil(() -> onMain(() -> actualMediaPlayer() == null),
                    warning.getInt("expectedDurationMs") + 900L, "actual mandatory warning completes once");
            for (int i = 0; i < 3; i++) {
                teyesCallback(callback, 13, 5, 1, 0);
                SystemClock.sleep(150L);
                check(onMain(() -> actualMediaPlayer() == null && roofDecision().sequence == sequence),
                        "duplicate cmd5 OFF after completion cannot replay the warning");
            }
            check(windowShowing(), "warning window persists after natural completion and duplicates");
            teyesProbe("cmd5_duplicates_after_completion");
        });
        scenario("teyes_cmd8_sleep_wake_preserves_unacknowledged_warning", () -> {
            IBinder callback = requireTeyesCallback();
            teyesCallback(callback, 13, 8, 6, 0);
            waitUntil(() -> !windowShowing(), 1500L, "actual cmd8 sleep hides the warning");
            check(onMain(() -> actualMediaPlayer() == null), "already completed warning stays silent during cmd8 sleep");
            check(!SunroofStateMemory.snapshot(app).acknowledged, "cmd8 sleep does not acknowledge roof warning");
            teyesProbe("cmd8_sleep");
            teyesCallback(callback, 13, 8, 0, 0);
            assertRoofWindow(true, true, false);
            check(!SunroofStateMemory.snapshot(app).acknowledged, "cmd8 wake restores without acknowledgement");
            check(onMain(() -> actualMediaPlayer() == null), "cmd8 wake does not replay a completed warning");
            SystemClock.sleep(1100L);
            onMain(() -> { controller.apply(); return null; });
            check(onMain(() -> actualMediaPlayer() == null), "one-second refresh after wake does not replay warning");
            teyesProbe("cmd8_wake_restored");
        });
        scenario("teyes_cmd14_acknowledges_and_stopped_callback_is_fenced", () -> {
            IBinder callback = requireTeyesCallback();
            teyesCallback(callback, 14, 24, 0, 0);
            waitUntil(() -> !windowShowing(), 1500L, "actual hardware callback dismisses the warning");
            check(SunroofStateMemory.snapshot(app).acknowledged,
                    "actual callback key acknowledgement is persisted");
            onMain(() -> { controller.stop(); return null; });
            waitUntil(() -> teyesService.unregisters == 1 && teyesService.callback == null,
                    1500L, "stopping actual observer unregisters through transaction 3");
            teyesCallback(callback, 13, 5, 0, 0);
            teyesCallback(callback, 13, 5, 1, 0);
            teyesCallback(callback, 13, 8, 0, 0);
            SystemClock.sleep(250L);
            check(!windowShowing() && onMain(() -> actualMediaPlayer() == null),
                    "old callback after observer stop cannot show a window or start audio");
            check(!onMain(() -> (Boolean) field(controller, "accKnown")),
                    "old callback cannot alter the stopped controller ACC state");
            check(SunroofStateMemory.snapshot(app).acknowledged,
                    "old ACC ON cannot reset persisted acknowledgement after stop");
            teyesProbe("stopped_callback_ignored");
        });
        scenario("teyes_android_aggregate_prefix_reads_acc_after_platform_id", () -> {
            IBinder stale = teyesService.retiredCallback;
            teyesService.aggregateAcc = 1;
            onMain(() -> { controller.start(); return null; });
            waitUntil(() -> onMain(() -> (Boolean) field(controller, "accKnown")
                            && (Boolean) field(controller, "accOn")),
                    2000L, "actual Android Parcel class/platform/ACC prefix decodes known ON");
            check(teyesService.registers == 2, "restart registers a fresh actual CallbackBinder");
            check(!SunroofStateMemory.snapshot(app).acknowledged,
                    "aggregate ON begins the next ignition cycle through the actual observer");
            teyesCallback(stale, 13, 5, 1, 0);
            check(onMain(() -> (Boolean) field(controller, "accKnown")
                            && (Boolean) field(controller, "accOn")) && !windowShowing(),
                    "prior-run callback cannot replace newer aggregate ON or display a warning");
            teyesProbe("aggregate_on_platform_73");
            teyesService.aggregateAcc = 0;
            waitUntil(() -> onMain(() -> (Boolean) field(controller, "accKnown")
                            && !(Boolean) field(controller, "accOn")
                            && roofDecision().kind == SunroofAlertPolicy.Kind.PARKING_WARNING),
                    2000L, "actual Android aggregate OFF reaches the warning policy");
            assertAudioCue("teyes_aggregate_mandatory_warning", warning, false);
            assertRoofWindow(true, true, false);
            teyesProbe("aggregate_off_platform_73");
            onMain(() -> { controller.stop(); return null; });
            waitUntil(() -> teyesService.unregisters == 2, 1500L,
                    "aggregate scenario releases its actual observer registration");
        });
    }

    /** Reproduces the physical ACC/sleep ordering without replacing the player or listener. */
    private void runSleepAudioPhase() throws Exception {
        JSONObject warning = readPcmWav("sunroof_warning_adas", 44100, 2);
        audioAssets.put("mandatory_rear_adas_warning", warning);
        installTeyesServiceBoundary();
        shell("input keyevent KEYCODE_HOME");
        waitUntil(() -> !activityVisible(), 2500L, "sleep-audio Activity moved behind Home");
        installUsbBoundary();
        injectTpms(new int[]{128, 128, 128, 128});
        injectRoof(true);
        dismissRoof();
        disconnectUsbBoundary();
        IBinder callback = requireTeyesCallback();

        scenario("acc_off_then_vendor_sleep_51ms_preserves_finite_warning", () -> {
            teyesCallback(callback, 13, 8, 0, 0);
            teyesCallback(callback, 13, 5, 0, 0);
            teyesCallback(callback, 13, 5, 1, 0);
            long started = SystemClock.elapsedRealtime();
            android.media.MediaPlayer playing = onMain(this::actualMediaPlayer);
            check(playing != null && onMain(playing::isPlaying),
                    "actual cmd5 OFF starts the production MediaPlayer before sleep");
            long untilSleep = started + 51L - SystemClock.elapsedRealtime();
            if (untilSleep > 0L) SystemClock.sleep(untilSleep);
            teyesCallback(callback, 13, 8, 6, 0);
            JSONObject probe = warningPlaybackProbe("vendor_sleep_requested_after_51ms", started);
            probe.put("requestedDelayMs", 51L);
            warningPlaybackProbes.put(probe);
            check(onMain(() -> actualMediaPlayer() == playing && playing.isPlaying()),
                    "vendor sleep after 51 ms does not replace or stop the actual warning player");
            check(!windowShowing(), "vendor sleep hides the window while finite warning audio continues");
            assertAudioCue("warning_survives_vendor_sleep_51ms", warning, false);
            observeSleepingWarningUntilCompletion(playing, started, warning.getInt("expectedDurationMs"));
            assertWakeAndRefreshStaySilent(callback);
        });
        scenario("vendor_sleep_before_acc_off_starts_audio_without_window", () -> {
            teyesCallback(callback, 13, 5, 0, 0);
            teyesCallback(callback, 13, 8, 6, 0);
            check(!windowShowing() && onMain(() -> actualMediaPlayer() == null),
                    "sleep with ACC ON does not invent a warning");
            teyesCallback(callback, 13, 5, 1, 0);
            long started = SystemClock.elapsedRealtime();
            android.media.MediaPlayer playing = onMain(this::actualMediaPlayer);
            check(playing != null && onMain(playing::isPlaying),
                    "ACC OFF received while already asleep starts the actual finite warning");
            check(!windowShowing(), "sleep-before-ACC warning starts without adding a window");
            assertAudioCue("warning_starts_after_prior_vendor_sleep", warning, false);
            observeSleepingWarningUntilCompletion(playing, started, warning.getInt("expectedDurationMs"));
            assertWakeAndRefreshStaySilent(callback);
        });
    }

    private void observeSleepingWarningUntilCompletion(android.media.MediaPlayer playing,
                                                       long started, int durationMs) throws Exception {
        while (SystemClock.elapsedRealtime() - started < durationMs - 180L) {
            check(onMain(() -> actualMediaPlayer() == playing && playing.isPlaying()),
                    "sleeping warning keeps its original player through the finite cue");
            check(!windowShowing(), "sleeping warning never recreates its overlay");
            warningPlaybackProbes.put(warningPlaybackProbe("sleeping_finite_warning", started));
            onMain(() -> { controller.apply(); return null; });
            SystemClock.sleep(100L);
        }
        waitUntil(() -> onMain(() -> actualMediaPlayer() == null), 1100L,
                "sleeping warning completes and releases the real MediaPlayer near its PCM duration");
        check(SystemClock.elapsedRealtime() - started >= durationMs - 180L,
                "warning was not truncated before its spoken phrase completed");
        check(!SunroofStateMemory.snapshot(app).acknowledged,
                "audio completion while asleep does not acknowledge the open roof");
        warningPlaybackProbes.put(warningPlaybackProbe("sleeping_warning_completed", started));
    }

    private void assertWakeAndRefreshStaySilent(IBinder callback) throws Exception {
        teyesCallback(callback, 13, 8, 0, 0);
        assertRoofWindow(true, true, false);
        check(onMain(() -> actualMediaPlayer() == null), "wake restores only the warning window without audio replay");
        teyesCallback(callback, 13, 5, 1, 0);
        SystemClock.sleep(1100L);
        onMain(() -> { controller.apply(); return null; });
        check(onMain(() -> actualMediaPlayer() == null),
                "duplicate ACC OFF and one-second refresh do not replay the completed warning");
        check(!SunroofStateMemory.snapshot(app).acknowledged, "wake keeps roof warning unacknowledged");
        teyesProbe("wake_and_refresh_without_audio_replay");
    }

    private void installTeyesServiceBoundary() throws Exception {
        onMain(() -> {
            controller.stop();
            TeyesSunroofObserver original = (TeyesSunroofObserver) field(controller, "observer");
            TeyesSunroofObserver.Listener listener = (TeyesSunroofObserver.Listener) field(original, "listener");
            Class<?> locatorType = Class.forName(TeyesSunroofObserver.class.getName() + "$ServiceLocator");
            teyesService = new TeyesServiceBoundary();
            Object locator = Proxy.newProxyInstance(locatorType.getClassLoader(), new Class<?>[]{locatorType},
                    (proxy, method, args) -> {
                        if ("findService".equals(method.getName())) return teyesService;
                        if ("toString".equals(method.getName())) return "Emulator TEYES Binder service locator";
                        if ("hashCode".equals(method.getName())) return System.identityHashCode(proxy);
                        if ("equals".equals(method.getName())) return proxy == args[0];
                        throw new UnsupportedOperationException(method.getName());
                    });
            java.lang.reflect.Constructor<?> constructor = TeyesSunroofObserver.class.getDeclaredConstructor(
                    Context.class, TeyesSunroofObserver.Listener.class, locatorType);
            constructor.setAccessible(true);
            set(controller, "observer", constructor.newInstance(app, listener, locator));
            controller.start();
            return null;
        });
    }

    private IBinder requireTeyesCallback() throws Exception {
        waitUntil(() -> teyesService != null && teyesService.callback != null, 1500L,
                "actual TEYES callback is still registered");
        return teyesService.callback;
    }

    private void teyesCallback(IBinder callback, int transaction, int... values) throws Exception {
        Parcel data = Parcel.obtain();
        Parcel reply = transaction == 14 ? Parcel.obtain() : null;
        JSONObject input = new JSONObject();
        input.put("transaction", transaction);
        JSONArray arguments = new JSONArray();
        for (int value : values) arguments.put(value);
        input.put("values", arguments);
        input.put("elapsedRealtimeMs", SystemClock.elapsedRealtime());
        teyesInputs.put(input);
        try {
            data.writeInterfaceToken(TeyesServiceBoundary.CALLBACK);
            for (int value : values) data.writeInt(value);
            check(callback.transact(transaction, data, reply, reply == null ? IBinder.FLAG_ONEWAY : 0),
                    "actual CallbackBinder accepts transaction " + transaction);
            if (reply != null) {
                reply.readException();
                check(reply.readInt() == -1 && reply.dataAvail() == 0,
                        "hardware callback reply preserves the native key action");
            }
            input.put("accepted", true);
        } catch (RuntimeException | RemoteException error) {
            input.put("accepted", false);
            input.put("exception", error.getClass().getSimpleName());
            throw error;
        } finally {
            data.recycle();
            if (reply != null) reply.recycle();
        }
        onMain(() -> null); // Drain the actual observer's posted callback delivery.
    }

    private void teyesProbe(String event) throws Exception {
        teyesProbes.put(onMain(() -> {
            JSONObject value = warningPlaybackProbe(event, 0L);
            value.put("accKnown", field(controller, "accKnown"));
            value.put("accOn", field(controller, "accOn"));
            value.put("asleep", field(controller, "asleep"));
            value.put("kind", roofDecision().kind.name());
            value.put("sequence", roofDecision().sequence);
            value.put("cachedAcknowledged", SunroofStateMemory.snapshot(app).acknowledged);
            value.put("observerStatus", ((TeyesSunroofObserver) field(controller, "observer")).statusSummary());
            value.put("registers", teyesService.registers);
            value.put("unregisters", teyesService.unregisters);
            value.put("aggregateReads", teyesService.polls);
            value.put("nullAggregateReads", teyesService.nullPolls);
            return value;
        }));
    }

    /** Only the system service is simulated; all observer and callback code is production. */
    private final class TeyesServiceBoundary extends Binder {
        static final String SERVICE = "com.spd.system.aidl.ISpdService";
        static final String CALLBACK = "com.spd.system.aidl.ISpdServiceCallback";
        volatile IBinder callback;
        volatile IBinder retiredCallback;
        volatile int registers, unregisters, polls, nullPolls;
        volatile int aggregateAcc = -1;

        TeyesServiceBoundary() { attachInterface(null, SERVICE); }

        @Override protected boolean onTransact(int code, Parcel data, Parcel reply, int flags)
                throws RemoteException {
            if (code == IBinder.INTERFACE_TRANSACTION) { reply.writeString(SERVICE); return true; }
            data.enforceInterface(SERVICE);
            String source = data.readString();
            if (code == 1) {
                if (!app.getPackageName().equals(source)) throw new IllegalArgumentException("registration package");
                IBinder incoming = data.readStrongBinder();
                if (incoming == null || data.dataAvail() != 0) throw new IllegalArgumentException("registration payload");
                callback = incoming;
                registers++;
                reply.writeNoException();
                return true;
            }
            if (code == 3) {
                if (!app.getPackageName().equals(source) || data.dataAvail() != 0)
                    throw new IllegalArgumentException("unregistration payload");
                retiredCallback = callback;
                callback = null;
                unregisters++;
                reply.writeNoException();
                return true;
            }
            if (code == 9) {
                if (!"android".equals(source) || data.dataAvail() != 0)
                    throw new IllegalArgumentException("aggregate source payload");
                polls++;
                reply.writeNoException();
                int acc = aggregateAcc;
                reply.writeInt(acc < 0 ? 0 : 1);
                if (acc < 0) {
                    nullPolls++;
                } else {
                    reply.writeString("com.spd.system.entity.aidl.DeviceStatusInfo");
                    reply.writeInt(73); // Platform ID must not be mistaken for ACC.
                    reply.writeInt(acc);
                    reply.writeInt(999); // A retained vendor tail must not become part of ACC.
                }
                return true;
            }
            return super.onTransact(code, data, reply, flags);
        }
    }

    private SunroofAlertPolicy.Decision roofDecision() throws Exception {
        return ((SunroofAlertPolicy) field(controller, "policy")).current();
    }

    private void startupProbe(String event) throws Exception {
        startupProbes.put(onMain(() -> {
            JSONObject probe = new JSONObject();
            SunroofAlertPolicy.Decision decision = roofDecision();
            android.media.MediaPlayer player = actualMediaPlayer();
            probe.put("event", event);
            probe.put("elapsedRealtimeMs", SystemClock.elapsedRealtime());
            probe.put("roofPacketsInjected", roofPacketsInjected);
            probe.put("roofKnown", StateStore.vehicle().sunroofKnown);
            probe.put("roofOpen", StateStore.vehicle().sunroofOpen);
            probe.put("kind", decision.kind.name());
            probe.put("sequence", decision.sequence);
            probe.put("windowShowing", windowShowing());
            probe.put("playerPresent", player != null);
            if (player != null) {
                probe.put("playerIdentity", System.identityHashCode(player));
                probe.put("isPlaying", player.isPlaying());
                probe.put("positionMs", player.getCurrentPosition());
            }
            return probe;
        }));
    }

    private void runRestartPhase() throws Exception {
        shell("input keyevent KEYCODE_HOME");
        waitUntil(() -> !activityVisible(), 2500L, "restarted MainActivity moved behind Home");
        SystemClock.sleep(250L);
        scenario("restart_unknown_acc_does_not_invent_warning", () -> {
            check(roofPacketsInjected == 0, "restart phase injects no roof packets or USB port");
            check(!AdapterGateway.get(app).usbReady(), "restart phase has no USB connection");
            check(!windowShowing(), "unknown ACC alone cannot trigger persisted-roof warning");
        });
        scenario("restart_cached_open_known_off_without_roof_packet", () -> {
            nativeAcc(false);
            assertRoofWindow(true, true, false);
            check(roofPacketsInjected == 0, "known ACC OFF used no new roof packet after process restart");
            capture("restart_cached_warning_before_tap");
            shell("input tap 640 400");
            waitUntil(() -> !windowShowing(), 2000L, "restarted cached warning accepts real input tap");
        });
    }

    private void runAudioPhase() throws Exception {
        // Read the installed target's resources, independently of the production
        // cue selection. A stale fixture must not expect an earlier voice take.
        JSONObject opening = readPcmWav("sunroof_open_voice", 24000, 1);
        JSONObject closing = readPcmWav("sunroof_close_voice", 24000, 1);
        JSONObject warning = readPcmWav("sunroof_warning_adas", 44100, 2);
        audioAssets.put("opening_voice", opening);
        audioAssets.put("closing_voice", closing);
        audioAssets.put("mandatory_rear_adas_warning", warning);
        scenario("actual_single_voice_toggle_and_no_preview_buttons", this::verifyGeneralSettings);
        installUsbBoundary();
        injectTpms(new int[]{128, 128, 128, 128});
        injectRoof(false);
        onMain(() -> {
            AppSettings.setSunroofTransitionSoundEnabled(app, true);
            controller.apply();
            return null;
        });
        shell("input keyevent KEYCODE_HOME");
        waitUntil(() -> !activityVisible(), 2500L, "audio test moves actual MainActivity behind Home");
        SystemClock.sleep(250L);
        scenario("actual_opening_voice_pcm24k", () -> {
            injectRoof(true);
            assertAudioCue("opening_voice", opening);
            waitUntil(() -> onMain(() -> actualMediaPlayer() == null), opening.getInt("expectedDurationMs") + 900L,
                    "opening voice completes and releases real MediaPlayer");
        });
        scenario("actual_closing_voice_pcm24k", () -> {
            dismissRoof();
            injectRoof(false);
            assertAudioCue("closing_voice", closing);
            waitUntil(() -> onMain(() -> actualMediaPlayer() == null), closing.getInt("expectedDurationMs") + 900L,
                    "closing voice completes and releases real MediaPlayer");
        });
        scenario("shared_toggle_off_silences_both_real_transitions", () -> {
            onMain(() -> {
                AppSettings.setSunroofTransitionSoundEnabled(app, false);
                AppSettings.setSunroofSoundEnabled(app, false);
                controller.apply();
                return null;
            });
            dismissRoof();
            injectRoof(true);
            SystemClock.sleep(100L);
            check(onMain(() -> actualMediaPlayer() == null), "shared toggle OFF silences real opening");
            check(windowShowing(), "silent opening still shows its real roof window");
            dismissRoof();
            injectRoof(false);
            SystemClock.sleep(100L);
            check(onMain(() -> actualMediaPlayer() == null), "shared toggle OFF silences real closing");
            check(windowShowing(), "silent closing still shows its real roof window");
        });
        scenario("combined_warning_plays_despite_flags_off_and_tap_cancels_during_voice", () -> {
            dismissRoof();
            nativeAcc(true);
            injectRoof(true);
            long started = SystemClock.elapsedRealtime();
            nativeAcc(false);
            check(!AppSettings.sunroofSoundEnabled(app) && !AppSettings.sunroofTransitionSoundEnabled(app),
                    "legacy warning and shared voice sound settings are both OFF");
            assertAudioCue("mandatory_rear_adas_warning", warning);
            assertRoofWindow(true, true);
            capture("mandatory_rear_adas_warning_before_tap");
            waitForSpokenPart(started, "tap_during_spoken_warning");
            shell("input tap 640 400");
            waitUntil(() -> !windowShowing(), 1800L, "mandatory warning accepts actual input tap");
            check(onMain(() -> actualMediaPlayer() == null), "actual tap releases warning MediaPlayer");
        });
        scenario("combined_warning_completes_once_with_overlay_and_no_three_six_second_replay", () -> {
            dismissRoof();
            nativeAcc(true);
            injectRoof(true);
            long started = SystemClock.elapsedRealtime();
            nativeAcc(false);
            assertAudioCue("mandatory_warning_natural_completion", warning);
            waitUntil(() -> onMain(() -> actualMediaPlayer() == null), warning.getInt("expectedDurationMs") + 900L,
                    "combined ADAS and voice complete and release the real MediaPlayer naturally");
            check(windowShowing(), "warning overlay persists after natural audio completion");
            boolean refreshedAtThree = false, refreshedAtSix = false;
            int samples = 0;
            long nextProbeAt = SystemClock.elapsedRealtime();
            while (SystemClock.elapsedRealtime() - started < 9800L) {
                long elapsed = SystemClock.elapsedRealtime() - started;
                if ((!refreshedAtThree && elapsed >= 3000L) || (!refreshedAtSix && elapsed >= 6000L)) {
                    injectRoof(true); // Actual duplicate 0x76 packet; no synthetic policy call.
                    onMain(() -> { controller.apply(); return null; });
                    if (elapsed >= 6000L) refreshedAtSix = true;
                    else refreshedAtThree = true;
                }
                JSONObject probe = warningPlaybackProbe("post_completion_replay_watch", started);
                warningPlaybackProbes.put(probe);
                if (probe.getBoolean("playerPresent"))
                    throw new AssertionError("warning audio replayed after natural completion at " + elapsed + " ms");
                if (!probe.getBoolean("windowShowing"))
                    throw new AssertionError("warning overlay disappeared after natural completion at " + elapsed + " ms");
                samples++;
                nextProbeAt += 100L;
                long remaining = nextProbeAt - SystemClock.elapsedRealtime();
                if (remaining > 0) SystemClock.sleep(remaining);
            }
            JSONObject finalProbe = warningPlaybackProbe("post_completion_replay_watch_final", started);
            warningPlaybackProbes.put(finalProbe);
            check(refreshedAtThree && refreshedAtSix, "real duplicate packets and service apply exercised the 3 s and 6 s points");
            check(samples >= 60, "MediaPlayer inspected every ~100 ms through at least 9.8 s without replay");
            check(finalProbe.getLong("elapsedMs") >= 9800L && !finalProbe.getBoolean("playerPresent")
                            && finalProbe.getBoolean("windowShowing"),
                    "persistent warning remains silent past 9.8 s and the 9.5 s audio hard guard");
            check(!activityVisible() && AppService.isRunning(), "once-only warning remained over Home with real service alive");
            capture("combined_warning_persists_after_9800ms_without_audio_replay");
        });
        scenario("real_roof_close_cancels_combined_warning_during_voice", () -> {
            dismissRoof();
            nativeAcc(true);
            injectRoof(true);
            long started = SystemClock.elapsedRealtime();
            nativeAcc(false);
            assertAudioCue("mandatory_warning_roof_close_cancellation", warning);
            waitForSpokenPart(started, "real_roof_close_during_spoken_warning");
            injectRoof(false);
            waitUntil(() -> onMain(() -> controller.statusSummary().contains("kind=CLOSING")), 1800L,
                    "real roof closed packet replaces parking warning with the ordinary closing animation");
            check(onMain(() -> actualMediaPlayer() == null), "real roof closed packet releases the warning MediaPlayer");
            assertRoofWindow(false, false);
            check(StateStore.vehicle().sunroofKnown && !StateStore.vehicle().sunroofOpen,
                    "roof close cancellation came from the actual FrameRouter state");
            SystemClock.sleep(1500L);
            check(onMain(() -> actualMediaPlayer() == null), "roof close leaves no delayed spoken phrase or audio retry");
            waitUntil(() -> !windowShowing(), 3500L, "ordinary closing animation fades after warning cancellation");
        });
    }

    private void waitForSpokenPart(long started, String event) throws Exception {
        long remaining = 1100L - (SystemClock.elapsedRealtime() - started);
        if (remaining > 0) SystemClock.sleep(remaining);
        JSONObject probe = warningPlaybackProbe(event, started);
        warningPlaybackProbes.put(probe);
        check(probe.getBoolean("playerPresent") && probe.optBoolean("isPlaying")
                        && probe.optInt("positionMs") >= 674 && probe.optInt("positionMs") < 2394,
                event + " occurs during the spoken portion of the actual combined MediaPlayer cue");
    }

    private JSONObject warningPlaybackProbe(String event, long started) throws Exception {
        return onMain(() -> {
            JSONObject probe = new JSONObject();
            android.media.MediaPlayer player = actualMediaPlayer();
            View view = (View) field(controller, "overlay");
            probe.put("event", event);
            probe.put("elapsedMs", SystemClock.elapsedRealtime() - started);
            probe.put("playerPresent", player != null);
            if (player != null) {
                probe.put("isPlaying", player.isPlaying());
                probe.put("positionMs", player.getCurrentPosition());
            }
            probe.put("windowShowing", view != null && view.isAttachedToWindow());
            return probe;
        });
    }

    private void assertAudioCue(String role, JSONObject asset) throws Exception {
        assertAudioCue(role, asset, true);
    }

    private void assertAudioCue(String role, JSONObject asset, boolean freshRoof) throws Exception {
        int expectedDurationMs = asset.getInt("expectedDurationMs");
        SystemClock.sleep(100L);
        JSONObject proof = onMain(() -> {
            android.media.MediaPlayer player = actualMediaPlayer();
            check(player != null, role + " uses a production Android MediaPlayer");
            check(player.isPlaying(), role + " is playing about 100 ms after actual input");
            int duration = player.getDuration();
            check(Math.abs(duration - expectedDurationMs) <= 12,
                    role + " decoded duration " + duration + " ms matches expected " + expectedDurationMs + " ms");
            check(!activityVisible() && AppService.isRunning(), role + " plays with Activity behind Home and real service alive");
            if (freshRoof) {
                check("0x76".equals(StateStore.vehicle().sunroofSource), role + " follows actual FrameRouter roof source");
            } else {
                SunroofStateMemory.Snapshot saved = SunroofStateMemory.snapshot(app);
                check(saved.known && saved.open && "0x76".equals(saved.source)
                                && !StateStore.vehicle().sunroofKnown,
                        role + " follows persisted actual FrameRouter OPEN after USB loss");
            }
            JSONObject value = new JSONObject();
            value.put("role", role);
            value.put("playerClass", player.getClass().getName());
            value.put("clipClass", field(field(controller, "sound"), "player").getClass().getName());
            value.put("isPlaying", player.isPlaying());
            value.put("durationMs", duration);
            value.put("expectedDurationMs", expectedDurationMs);
            value.put("expectedFromInstalledPcmWav", asset);
            value.put("transitionSoundPreference", AppSettings.sunroofTransitionSoundEnabled(app));
            value.put("legacyWarningSoundPreference", AppSettings.sunroofSoundEnabled(app));
            value.put("physicalAudibilityVerified", false);
            return value;
        });
        audioProofs.put(proof);
        if (role.contains("warning")) warningAudioProof = proof;
    }

    private JSONObject readPcmWav(String name, int expectedRate, int expectedChannels) throws Exception {
        int resource = app.getResources().getIdentifier(name, "raw", app.getPackageName());
        if (resource == 0) throw new AssertionError("missing target PCM WAV resource " + name);
        java.io.ByteArrayOutputStream bytes = new java.io.ByteArrayOutputStream();
        try (java.io.InputStream input = app.getResources().openRawResource(resource)) {
            byte[] buffer = new byte[8192];
            int count;
            while ((count = input.read(buffer)) != -1) bytes.write(buffer, 0, count);
        }
        byte[] wav = bytes.toByteArray();
        if (wav.length < 12 || !asciiAt(wav, 0, "RIFF") || !asciiAt(wav, 8, "WAVE"))
            throw new AssertionError(name + " is not a RIFF/WAVE resource");
        long riffEnd = 8L + unsignedLe(wav, 4, 4);
        if (riffEnd != wav.length) throw new AssertionError(name + " has inconsistent RIFF length");
        int format = -1, channels = -1, rate = -1, bits = -1, blockAlign = -1;
        long dataBytes = -1;
        int dataOffset = -1;
        for (long position = 12; position + 8 <= riffEnd;) {
            int offset = (int) position;
            long chunkLength = unsignedLe(wav, offset + 4, 4);
            long next = position + 8L + chunkLength;
            if (next > riffEnd) throw new AssertionError(name + " has a truncated WAV chunk");
            if (asciiAt(wav, offset, "fmt ")) {
                if (chunkLength < 16) throw new AssertionError(name + " has a truncated PCM format");
                format = (int) unsignedLe(wav, offset + 8, 2);
                channels = (int) unsignedLe(wav, offset + 10, 2);
                rate = (int) unsignedLe(wav, offset + 12, 4);
                blockAlign = (int) unsignedLe(wav, offset + 20, 2);
                bits = (int) unsignedLe(wav, offset + 22, 2);
            } else if (asciiAt(wav, offset, "data")) {
                if (dataBytes != -1) throw new AssertionError(name + " has multiple data chunks");
                dataBytes = chunkLength;
                dataOffset = offset + 8;
            }
            position = next + (chunkLength & 1L);
        }
        if (format != 1 || channels != expectedChannels || rate != expectedRate || bits != 16
                || blockAlign != channels * 2 || dataBytes <= 0 || dataBytes % blockAlign != 0)
            throw new AssertionError(name + " must be PCM16 at " + expectedRate + " Hz / " + expectedChannels + " channels");
        long frames = dataBytes / blockAlign;
        double durationMs = frames * 1000.0 / rate;
        if (durationMs < 200 || durationMs > 10000)
            throw new AssertionError(name + " has an implausible cue duration " + durationMs);
        StringBuilder hash = new StringBuilder();
        for (byte value : java.security.MessageDigest.getInstance("SHA-256").digest(wav))
            hash.append(String.format(java.util.Locale.ROOT, "%02x", value & 255));
        JSONObject value = new JSONObject();
        value.put("resourceName", name);
        value.put("sha256", hash.toString());
        value.put("encoding", "PCM16");
        value.put("channels", channels);
        value.put("sampleRateHz", rate);
        value.put("frames", frames);
        value.put("durationExactMs", durationMs);
        value.put("expectedDurationMs", Math.round(durationMs));
        if ("sunroof_warning_adas".equals(name)) {
            if (frames != 105578L || !"493b13c3c1ef22ad08695e80892047a80e366ea2da115757fd1bded684e46a70".equals(hash.toString()))
                throw new AssertionError("combined warning differs from the selected 105578-frame master");
            java.security.MessageDigest prefix = java.security.MessageDigest.getInstance("SHA-256");
            prefix.update(wav, dataOffset, 23552 * blockAlign);
            StringBuilder prefixHash = new StringBuilder();
            for (byte item : prefix.digest()) prefixHash.append(String.format(java.util.Locale.ROOT, "%02x", item & 255));
            if (!"ae91888830362a7b262b3f431fa14adf9ca090eaaf8297c60a997a45adc7b141".equals(prefixHash.toString()))
                throw new AssertionError("combined warning changed the original rear ADAS PCM prefix");
            for (int index = dataOffset + 23552 * blockAlign; index < dataOffset + 29726 * blockAlign; index++)
                if (wav[index] != 0) throw new AssertionError("combined warning gap must contain 6174 silent frames");
            for (int index = dataOffset; index < dataOffset + dataBytes; index += blockAlign)
                if (wav[index] != wav[index + 2] || wav[index + 1] != wav[index + 3])
                    throw new AssertionError("combined warning must send identical PCM to both speaker channels");
            value.put("originalBeepFrames", 23552);
            value.put("originalBeepPcmSha256", prefixHash.toString());
            value.put("silentGapFrames", 6174);
            value.put("silentGapAllZero", true);
            value.put("voiceStartFrame", 29726);
            value.put("voiceFrames", 75852);
            value.put("channelsIdentical", true);
        }
        return value;
    }

    private static boolean asciiAt(byte[] bytes, int offset, String expected) {
        if (offset < 0 || offset + expected.length() > bytes.length) return false;
        for (int i = 0; i < expected.length(); i++)
            if (bytes[offset + i] != expected.charAt(i)) return false;
        return true;
    }

    private static long unsignedLe(byte[] bytes, int offset, int length) {
        if (offset < 0 || offset + length > bytes.length)
            throw new AssertionError("truncated little-endian WAV field");
        long value = 0;
        for (int i = 0; i < length; i++) value |= ((long) bytes[offset + i] & 255L) << (i * 8);
        return value;
    }

    private android.media.MediaPlayer actualMediaPlayer() throws Exception {
        Object clip = field(field(controller, "sound"), "player");
        if (clip == null) return null;
        for (Field item : clip.getClass().getDeclaredFields()) {
            if (android.media.MediaPlayer.class.isAssignableFrom(item.getType())) {
                item.setAccessible(true);
                return (android.media.MediaPlayer) item.get(clip);
            }
        }
        return null;
    }

    private void completeReport(JSONObject report) throws Exception {
            report.put("boundary", ("teyes".equals(phase) || "sleep_audio".equals(phase))
                    ? "Real Android observer registration/poll/callback Binder, controller/window/audio; only system service locator and USB hardware replaced"
                    : "Real Android app/service/router/state/policy/windows; USB hardware and native ACC input replaced; no QA policy/scenario used");
            report.put("usbHardwareReplaced", true);
            report.put("nativeAccInputReplaced", true);
            report.put("teyesServiceLocatorReplaced", "teyes".equals(phase) || "sleep_audio".equals(phase));
            report.put("accListenerInjected", !"teyes".equals(phase)
                    && !"sleep_audio".equals(phase) && !"startup".equals(phase));
            report.put("qaPolicyUsed", false);
            report.put("physicalHeadunitTested", false);
            report.put("appVersion", app.getPackageManager().getPackageInfo(app.getPackageName(), 0).versionName);
            report.put("appVersionCode", app.getPackageManager().getPackageInfo(app.getPackageName(), 0).getLongVersionCode());
            report.put("targetApkSha256", sha256(new File(app.getApplicationInfo().sourceDir)));
            report.put("processId", android.os.Process.myPid());
            report.put("releasedCarWidthFraction", .161);
            report.put("testedCarWidthFraction", .1932);
            report.put("carWidthGrowthPercent", 20);
            report.put("phase", phase);
            report.put("roofPacketsInjected", roofPacketsInjected);
            report.put("warningAudioProof", warningAudioProof);
            report.put("audioProofs", audioProofs);
            report.put("audioAssets", audioAssets);
            report.put("warningPlaybackProbes", warningPlaybackProbes);
            report.put("startupProbes", startupProbes);
            report.put("roofInputs", roofInputs);
            report.put("teyesInputs", teyesInputs);
            report.put("teyesProbes", teyesProbes);
            report.put("results", results);
            report.put("assertions", assertions);
            report.put("failures", failures);
    }

    private void installUsbBoundary() throws Exception {
        onMain(() -> {
            AdapterGateway gateway = AdapterGateway.get(app);
            transport = field(gateway, "transport");
            router = (FrameRouter) field(gateway, "router");
            Class<?> portType = Class.forName("com.hoho.android.usbserial.driver.UsbSerialPort");
            Object port = Proxy.newProxyInstance(portType.getClassLoader(), new Class<?>[]{portType}, (proxy, method, arguments) -> {
                if (method.getName().equals("equals")) return proxy == arguments[0];
                if (method.getName().equals("hashCode")) return System.identityHashCode(proxy);
                if (method.getName().equals("toString")) return "Instrumentation USB boundary";
                if (method.getReturnType() == boolean.class) return false;
                if (method.getReturnType() == int.class) return 0;
                if (method.getReturnType() == long.class) return 0L;
                return null;
            });
            synchronized (transport) {
                set(transport, "port", port);
                set(transport, "readRunning", true);
                set(transport, "desiredConnection", true);
                set(transport, "connectionEpoch", usbEpoch);
                set(transport, "connectedAtElapsed", SystemClock.elapsedRealtime());
                set(transport, "lastRxAtElapsed", SystemClock.elapsedRealtime());
            }
            StateStore.setAdapter(app, StateStore.adapter().withUsb(true, "instrumentation USB boundary"));
            keepAlive = true;
            main.post(heartbeat);
            return null;
        });
    }

    private void injectRoof(boolean open) throws Exception {
        SystemClock.sleep(2L);
        roofPacketsInjected++;
        onMain(() -> {
            long receivedElapsed = SystemClock.elapsedRealtime();
            router.onFrame(AdapterProtocol.packet(AdapterProtocol.CMD_RAW_CAN, new byte[]{(byte) (open ? 1 : 0)}),
                    usbEpoch, receivedElapsed);
            JSONObject input = new JSONObject();
            input.put("packet", roofPacketsInjected);
            input.put("open", open);
            input.put("connectionEpoch", usbEpoch);
            input.put("receivedElapsedMs", receivedElapsed);
            roofInputs.put(input);
            return null;
        });
        // A persistent warning intentionally animates continuously. Waiting for
        // queue idleness here can skip whole playback/replay observation periods.
        onMain(() -> null);
    }

    private void injectTpms(int[] pressure) throws Exception {
        byte[] payload = new byte[8];
        for (int i = 0; i < 4; i++) { payload[i] = (byte) pressure[i]; payload[i + 4] = 91; }
        onMain(() -> { router.onFrame(AdapterProtocol.packet(AdapterProtocol.CMD_TPMS, payload), usbEpoch, SystemClock.elapsedRealtime()); return null; });
        waitForIdleSync();
    }

    private void nativeAcc(boolean on) throws Exception {
        onMain(() -> {
            Object observer = field(controller, "observer");
            TeyesSunroofObserver.Listener listener = (TeyesSunroofObserver.Listener) field(observer, "listener");
            listener.onAcc(true, on, SystemClock.elapsedRealtime(), "instrumentation:native-ACC-boundary");
            return null;
        });
    }

    private void assertActualWarningAudio() throws Exception {
        SystemClock.sleep(100L);
        waitUntil(() -> onMain(() -> {
            check(!AppSettings.sunroofSoundEnabled(app) && !AppSettings.sunroofTransitionSoundEnabled(app),
                    "both legacy warning and transition sound preferences are false");
            Object clip = field(field(controller, "sound"), "player");
            if (clip == null) return false;
            for (Field item : clip.getClass().getDeclaredFields()) {
                if (!android.media.MediaPlayer.class.isAssignableFrom(item.getType())) continue;
                item.setAccessible(true);
                android.media.MediaPlayer player = (android.media.MediaPlayer) item.get(clip);
                if (player == null || !player.isPlaying()) return false;
                warningAudioProof = new JSONObject();
                warningAudioProof.put("playerClass", player.getClass().getName());
                warningAudioProof.put("clipClass", clip.getClass().getName());
                warningAudioProof.put("isPlaying", true);
                warningAudioProof.put("legacyWarningSoundPreference", false);
                warningAudioProof.put("transitionSoundPreference", false);
                warningAudioProof.put("observedElapsed", SystemClock.elapsedRealtime());
                warningAudioProof.put("physicalAudibilityVerified", false);
                return true;
            }
            return false;
        }), 350L, "actual production MediaPlayer plays mandatory warning with both sound preferences false");
    }

    private void verifyGeneralSettings() throws Exception {
        onMain(() -> {
            View root = activity.getWindow().getDecorView();
            View gear = findDescription(root, "Открыть настройки");
            if (gear != null) check(gear.performClick(), "actual dashboard settings image control works");
            else clickText(root, "⚙");
            return null;
        });
        waitForIdleSync();
        onMain(() -> {
            View general = findDescription(activity.getWindow().getDecorView(), "Раздел настроек: Общее");
            check(general != null && general.performClick(), "actual General settings navigation works");
            return null;
        });
        waitForIdleSync();
        onMain(() -> {
            View root = activity.getWindow().getDecorView();
            View transition = findText(root, "Звук открытия и закрытия");
            check(transition != null, "actual General screen has the shared transition sound label");
            View roof = findText(root, "Люк");
            check(roof != null, "actual General screen has the roof panel");
            View panel = roof;
            while (panel != null && !descendant(panel, transition)) {
                panel = panel.getParent() instanceof View ? (View) panel.getParent() : null;
            }
            check(panel != null, "roof controls share their actual panel");
            check(countText(panel, "Звук открытия и закрытия") == 1, "exactly one shared transition sound control");
            check(countText(panel, "Звук предупреждения TEYES") == 0, "warning sound switch removed from actual UI");
            check(countText(panel, "Открытие") == 0 && countText(panel, "Закрытие") == 0
                    && countText(panel, "Предупреждение") == 0, "all three preview buttons removed from actual UI");
            check(countSwitches(panel) == 3, "roof panel has only master, parking warning and shared transition switches");
            StringBuilder labels = new StringBuilder(); collectText(panel, labels);
            write(new File(output, "actual-general-roof-ui.txt"), labels.toString());
            transition.requestRectangleOnScreen(new android.graphics.Rect(0, 0,
                    Math.max(1, transition.getWidth()), Math.max(1, transition.getHeight())), true);
            return null;
        });
        SystemClock.sleep(250L);
        capture("actual_general_roof_settings");
        onMain(() -> {
            View close = findDescription(activity.getWindow().getDecorView(), "Закрыть настройки");
            check(close != null && close.performClick(), "actual settings close control works");
            return null;
        });
        waitForIdleSync();
    }

    private static boolean descendant(View parent, View child) {
        for (View current = child; current != null;
             current = current.getParent() instanceof View ? (View) current.getParent() : null) {
            if (current == parent) return true;
        }
        return false;
    }

    private static View findText(View root, String text) {
        if (root instanceof TextView && text.contentEquals(((TextView) root).getText())) return root;
        if (root instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) root;
            for (int i = 0; i < group.getChildCount(); i++) {
                View found = findText(group.getChildAt(i), text); if (found != null) return found;
            }
        }
        return null;
    }

    private static View findDescription(View root, String text) {
        if (text.contentEquals(root.getContentDescription() == null ? "" : root.getContentDescription())) return root;
        if (root instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) root;
            for (int i = 0; i < group.getChildCount(); i++) {
                View found = findDescription(group.getChildAt(i), text); if (found != null) return found;
            }
        }
        return null;
    }

    private static void clickText(View root, String text) {
        View view = findText(root, text);
        if (view == null || !view.performClick()) throw new AssertionError("Actual UI control missing: " + text);
    }

    private static int countText(View root, String text) {
        int count = root instanceof TextView && text.contentEquals(((TextView) root).getText()) ? 1 : 0;
        if (root instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) root;
            for (int i = 0; i < group.getChildCount(); i++) count += countText(group.getChildAt(i), text);
        }
        return count;
    }

    private static int countSwitches(View root) {
        int count = root instanceof CompoundButton ? 1 : 0;
        if (root instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) root;
            for (int i = 0; i < group.getChildCount(); i++) count += countSwitches(group.getChildAt(i));
        }
        return count;
    }

    private static void collectText(View root, StringBuilder text) {
        if (root instanceof TextView) text.append(((TextView) root).getText()).append('\n');
        if (root instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) root;
            for (int i = 0; i < group.getChildCount(); i++) collectText(group.getChildAt(i), text);
        }
    }

    private void assertRoofWindow(boolean open, boolean warning) throws Exception {
        assertRoofWindow(open, warning, true);
    }

    private void assertRoofWindow(boolean open, boolean warning, boolean freshRoof) throws Exception {
        waitUntil(this::windowShowing, 1500L, "real roof WindowManager window attached");
        onMain(() -> {
            View view = (View) field(controller, "overlay");
            WindowManager.LayoutParams params = (WindowManager.LayoutParams) view.getLayoutParams();
            VehicleState roof = StateStore.vehicle();
            check(!activityVisible(), "MainActivity is not visible");
            check(AppService.isRunning(), "AppService remains alive");
            if (freshRoof) {
                check(roof.sunroofKnown && roof.sunroofOpen == open, "actual FrameRouter roof position persisted");
                check("0x76".equals(roof.sunroofSource) && roof.sunroofConnectionEpoch == usbEpoch, "actual protocol source and epoch retained");
            } else {
                check(!roof.sunroofKnown && roof.sunroofReceivedElapsed == 0L, "fresh USB roof state is absent; persisted cache drives warning");
            }
            check(!(Boolean) field(controller, "qaMode") && field(controller, "demo") == null, "real policy used without QA/demo");
            check(params.type == WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY, "actual application overlay type");
            check(params.alpha == 1f, "roof stays fully opaque");
            if (warning) {
                check(params.width == -1 && params.height == -1, "warning remains fullscreen");
            } else {
                DisplayMetrics metrics = new DisplayMetrics();
                ((WindowManager) app.getSystemService(Context.WINDOW_SERVICE)).getDefaultDisplay().getRealMetrics(metrics);
                check(params.width > 0 && params.width < metrics.widthPixels / 2, "transient window is bounded");
                check((params.flags & WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE) == 0, "bounded transient accepts its own touches");
                float carWidth = ((Number) field(view, "compactCarWidthPx")).floatValue();
                check(Math.abs(carWidth - metrics.widthPixels * .1932f) <= 2f, "car grows exactly 20 percent from released 0.161W");
            }
            return null;
        });
    }

    private boolean windowShowing() throws Exception {
        return onMain(() -> { View view = (View) field(controller, "overlay"); return view != null && view.isAttachedToWindow(); });
    }

    private boolean tpmsShowing() throws Exception {
        return onMain(() -> { View view = (View) field(TpmsWarningOverlayController.get(app), "overlay"); return view != null && view.isAttachedToWindow(); });
    }

    private boolean activityVisible() throws Exception {
        return onMain(() -> activity != null && (Boolean) field(activity, "activityVisible"));
    }

    private boolean interactive() {
        android.os.PowerManager power = (android.os.PowerManager) app.getSystemService(Context.POWER_SERVICE);
        return power != null && power.isInteractive();
    }

    private void dismissRoof() throws Exception {
        onMain(() -> { controller.dismissCurrent(); return null; });
        waitForIdleSync();
    }

    private void disconnectUsbBoundary() throws Exception {
        onMain(() -> {
            keepAlive = false;
            main.removeCallbacks(heartbeat);
            AdapterGateway.get(app).stop();
            return null;
        });
        waitForIdleSync();
        check(!AdapterGateway.get(app).usbReady(), "USB boundary disconnected");
        check(!StateStore.vehicle().sunroofKnown, "USB disconnect clears fresh StateStore roof");
    }

    private void scenario(String name, CheckedRunnable action) throws Exception {
        JSONObject result = new JSONObject();
        result.put("name", name);
        try { action.run(); result.put("passed", true); }
        catch (Throwable error) { failures++; result.put("passed", false); result.put("error", android.util.Log.getStackTraceString(error)); }
        result.put("status", onMain(() -> controller.statusSummary()));
        result.put("mainActivityVisible", activityVisible());
        result.put("serviceAlive", AppService.isRunning());
        result.put("roofSource", StateStore.vehicle().sunroofSource);
        result.put("roofOpen", StateStore.vehicle().sunroofOpen);
        result.put("persistedRoof", new JSONObject(app.getSharedPreferences("kia_sunroof_state", Context.MODE_PRIVATE).getAll()));
        result.put("windowShowing", windowShowing());
        result.put("tpmsShowing", tpmsShowing());
        results.put(result);
        capture(name);
        Bundle progress = new Bundle(); progress.putString("stream", name + ": " + result.optBoolean("passed") + "\n"); sendStatus(0, progress);
    }

    private void capture(String name) throws Exception {
        // Attachment precedes traversal/compositing. Let real Android draw before evidence.
        SystemClock.sleep(350L);
        JSONObject surface = onMain(() -> {
            JSONObject value = new JSONObject();
            View view = (View) field(controller, "overlay");
            value.put("attached", view != null && view.isAttachedToWindow());
            value.put("mainActivityVisible", activityVisible());
            value.put("serviceAlive", AppService.isRunning());
            if (view != null) {
                WindowManager.LayoutParams params = (WindowManager.LayoutParams) view.getLayoutParams();
                value.put("windowType", params.type);
                value.put("windowAlpha", params.alpha);
                value.put("viewAlpha", view.getAlpha());
                value.put("layoutWidth", params.width);
                value.put("layoutHeight", params.height);
                value.put("drawnWidth", view.getWidth());
                value.put("drawnHeight", view.getHeight());
            }
            return value;
        });
        write(new File(output, name + "-surface.json"), surface.toString(2));
        Bitmap bitmap = getUiAutomation().takeScreenshot();
        if (bitmap != null) try (FileOutputStream stream = new FileOutputStream(new File(output, name + ".png"))) { bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream); }
        try (ParcelFileDescriptor descriptor = getUiAutomation().executeShellCommand("dumpsys window windows");
             FileInputStream input = new FileInputStream(descriptor.getFileDescriptor());
             FileOutputStream out = new FileOutputStream(new File(output, name + "-windows.txt"))) {
            byte[] bytes = new byte[8192]; int count; while ((count = input.read(bytes)) >= 0) out.write(bytes, 0, count);
        }
    }

    private void shell(String command) throws Exception {
        try (ParcelFileDescriptor descriptor = getUiAutomation().executeShellCommand(command);
             FileInputStream input = new FileInputStream(descriptor.getFileDescriptor())) {
            byte[] bytes = new byte[1024]; while (input.read(bytes) >= 0) { }
        }
    }

    private void restoreSettings() {
        SharedPreferences.Editor editor = app.getSharedPreferences("Kia", Context.MODE_PRIVATE).edit();
        // Restore only fixture configuration; actual protocol cache writes must survive restart.
        for (String key : TEST_SETTINGS) {
            Object value = oldSettings.get(key);
            if (value == null) editor.remove(key);
            if (value instanceof Boolean) editor.putBoolean(key, (Boolean) value);
            else if (value instanceof Integer) editor.putInt(key, (Integer) value);
            else if (value instanceof Long) editor.putLong(key, (Long) value);
            else if (value instanceof Float) editor.putFloat(key, (Float) value);
            else if (value instanceof String) editor.putString(key, (String) value);
            else if (value instanceof java.util.Set) editor.putStringSet(key, (java.util.Set<String>) value);
        }
        editor.commit();
    }

    private <T> T onMain(Callable<T> action) throws Exception {
        if (Looper.myLooper() == Looper.getMainLooper()) return action.call();
        AtomicReference<T> value = new AtomicReference<>(); AtomicReference<Throwable> error = new AtomicReference<>();
        runOnMainSync(() -> { try { value.set(action.call()); } catch (Throwable caught) { error.set(caught); } });
        if (error.get() != null) throw new RuntimeException(error.get());
        return value.get();
    }

    private void waitUntil(CheckedBoolean condition, long timeout, String message) throws Exception {
        long deadline = SystemClock.elapsedRealtime() + timeout;
        do { if (condition.get()) { check(true, message); return; } SystemClock.sleep(30L); }
        while (SystemClock.elapsedRealtime() < deadline);
        throw new AssertionError(message + "; " + (controller == null ? "controller not initialized" : onMain(() -> controller.statusSummary())));
    }

    private void check(boolean condition, String message) { assertions++; if (!condition) throw new AssertionError(message); }
    private static Object field(Object object, String name) throws Exception { Field field = object.getClass().getDeclaredField(name); field.setAccessible(true); return field.get(object); }
    private static void set(Object object, String name, Object value) throws Exception { Field field = object.getClass().getDeclaredField(name); field.setAccessible(true); field.set(object, value); }
    private static void write(File file, String value) throws Exception { try (FileOutputStream out = new FileOutputStream(file)) { out.write(value.getBytes(java.nio.charset.StandardCharsets.UTF_8)); } }
    private static String sha256(File file) throws Exception {
        java.security.MessageDigest digest = java.security.MessageDigest.getInstance("SHA-256");
        try (FileInputStream input = new FileInputStream(file)) {
            byte[] bytes = new byte[8192]; int count; while ((count = input.read(bytes)) >= 0) digest.update(bytes, 0, count);
        }
        StringBuilder text = new StringBuilder();
        for (byte value : digest.digest()) text.append(String.format(java.util.Locale.ROOT, "%02x", value & 255));
        return text.toString();
    }
    private interface CheckedRunnable { void run() throws Exception; }
    private interface CheckedBoolean { boolean get() throws Exception; }
}
