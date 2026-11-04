package kia.app.sunroof.backgroundtest;

import android.app.Activity;
import android.app.Instrumentation;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.ParcelFileDescriptor;
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
import kia.app.sunroof.SunroofOverlayController;
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
                check(onMain(() -> field(field(controller, "sound"), "player") == null), "screen sleep releases the actual warning audio player");
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
        if (warning.getLong("frames") != 23552L)
            throw new AssertionError("mandatory rear ADAS warning must retain its original 23552 frames");
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
        scenario("rear_adas_pcm44100_plays_despite_both_sound_preferences_false", () -> {
            dismissRoof();
            nativeAcc(true);
            injectRoof(true);
            nativeAcc(false);
            check(!AppSettings.sunroofSoundEnabled(app) && !AppSettings.sunroofTransitionSoundEnabled(app),
                    "legacy warning and shared voice sound settings are both OFF");
            assertAudioCue("mandatory_rear_adas_warning", warning);
            assertRoofWindow(true, true);
            capture("mandatory_rear_adas_warning_before_tap");
            shell("input tap 640 400");
            waitUntil(() -> !windowShowing(), 1800L, "mandatory warning accepts actual input tap");
            check(onMain(() -> actualMediaPlayer() == null), "actual tap releases warning MediaPlayer");
        });
    }

    private void assertAudioCue(String role, JSONObject asset) throws Exception {
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
            check("0x76".equals(StateStore.vehicle().sunroofSource), role + " follows actual FrameRouter roof source");
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
            report.put("boundary", "Real Android app/service/router/state/policy/windows; USB hardware and native ACC input replaced; no QA policy/scenario used");
            report.put("usbHardwareReplaced", true);
            report.put("nativeAccInputReplaced", true);
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
                set(transport, "connectionEpoch", EPOCH);
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
            router.onFrame(AdapterProtocol.packet(AdapterProtocol.CMD_RAW_CAN, new byte[]{(byte) (open ? 1 : 0)}),
                    EPOCH, SystemClock.elapsedRealtime());
            return null;
        });
        waitForIdleSync();
    }

    private void injectTpms(int[] pressure) throws Exception {
        byte[] payload = new byte[8];
        for (int i = 0; i < 4; i++) { payload[i] = (byte) pressure[i]; payload[i + 4] = 91; }
        onMain(() -> { router.onFrame(AdapterProtocol.packet(AdapterProtocol.CMD_TPMS, payload), EPOCH, SystemClock.elapsedRealtime()); return null; });
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
                check("0x76".equals(roof.sunroofSource) && roof.sunroofConnectionEpoch == EPOCH, "actual protocol source and epoch retained");
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
