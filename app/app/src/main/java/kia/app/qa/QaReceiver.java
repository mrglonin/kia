package kia.app.qa;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.location.Location;
import android.os.Handler;
import android.os.Looper;

import kia.app.core.AppIds;
import kia.app.core.AppLog;
import kia.app.core.StateStore;
import kia.app.core.model.MediaState;
import kia.app.core.model.NavigationState;
import kia.app.core.model.TpmsState;
import kia.app.media.capture.MediaCaptureManager;
import kia.app.media.cluster.MediaClusterSender;
import kia.app.media.domain.CallFeature;
import kia.app.media.domain.MediaFeature;
import kia.app.media.overlay.MediaOverlayController;
import kia.app.core.settings.AppSettings;
import kia.app.entry.AppService;
import kia.app.navigation.capture.DgisNotificationParser;
import kia.app.navigation.domain.NavigationFeature;
import kia.app.navigation.domain.NavigationOutputMode;
import kia.app.navigation.overlay.NavigationOverlayController;
import kia.app.protocol.adapter.AdapterCommand;
import kia.app.protocol.adapter.AdapterGateway;
import kia.app.protocol.adapter.AdapterProtocol;
import kia.app.rcta.RctaController;
import kia.app.tpms.TpmsController;
import kia.app.update.AppUpdateController;
import kia.app.update.FirmwareUpdateController;

public final class QaReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        if (context == null || intent == null) return;
        String scenario = intent.getStringExtra("scenario");
        if ("media_scan".equals(scenario)) {
            MediaCaptureManager.scanOnce(context);
        } else if ("media_profile_teyes".equals(scenario)) {
            setMediaProfile(context, AppSettings.MEDIA_PROFILE_TEYES);
        } else if ("media_profile_universal".equals(scenario)) {
            setMediaProfile(context, AppSettings.MEDIA_PROFILE_UNIVERSAL_ANDROID);
        } else if ("media_profile_uart_real".equals(scenario)) {
            setMediaProfile(context, AppSettings.MEDIA_PROFILE_UART_REAL);
        } else if ("media_profile_off".equals(scenario)) {
            setMediaProfile(context, AppSettings.MEDIA_PROFILE_OFF);
        } else if ("nav_overlay_on".equals(scenario)) {
            AppSettings.setNavDebugVisible(context, true);
            AppSettings.setNavOverlayEnabled(context, true);
            AppService.start(context);
            NavigationOverlayController.get(context).apply();
            AppLog.line(context, "QA nav overlay: on");
        } else if ("nav_overlay_off".equals(scenario)) {
            AppSettings.setNavDebugVisible(context, false);
            AppSettings.setNavOverlayEnabled(context, false);
            NavigationOverlayController.get(context).apply();
            AppLog.line(context, "QA nav overlay: off");
        } else if ("media_overlay_on".equals(scenario)) {
            AppSettings.setMediaOverlayEnabled(context, true);
            AppService.start(context);
            MediaOverlayController.get(context).apply();
            AppLog.line(context, "QA media overlay: on");
        } else if ("media_overlay_off".equals(scenario)) {
            AppSettings.setMediaOverlayEnabled(context, false);
            MediaOverlayController.get(context).apply();
            AppLog.line(context, "QA media overlay: off");
        } else if ("nav_turn".equals(scenario)) {
            NavigationFeature.get(context).sendManeuver("context_ra_turn_right", "120", "м", "ул. Абая");
        } else if ("nav_2gis".equals(scenario)) {
            DgisNotificationParser.Parsed parsed = DgisNotificationParser.parse("ru.dublgis.dgismobile",
                    "Через 120 м направо", "ул. Абая");
            if (parsed != null) {
                NavigationFeature.get(context).handleDgisNotification(parsed.maneuver, parsed.distance,
                        parsed.unit, parsed.street, parsed.raw);
            }
        } else if ("nav_off".equals(scenario)) {
            NavigationFeature.get(context).setActive(false);
        } else if ("adapter_info".equals(scenario)) {
            AdapterGateway.get(context).requestAdapterInfo();
        } else if ("media_source_usb".equals(scenario)) {
            sendMediaSource(context, "USB", "com.spd.media");
        } else if ("media_source_bt".equals(scenario)) {
            sendMediaSource(context, "Bluetooth", "com.spd.bluetooth");
        } else if ("media_source_fm".equals(scenario)) {
            sendMediaSource(context, "FM", "com.spd.radio");
        } else if ("media_source_off".equals(scenario)) {
            AdapterGateway.get(context).send(AdapterCommand.loud("qa media off",
                    AdapterProtocol.mediaOffStatus()));
            AppLog.line(context, "QA media source: off");
        } else if ("media_text_usb".equals(scenario)) {
            sendMediaText(context, "USB", "com.spd.media", "USB", "KIA TEST");
        } else if ("media_text_bt".equals(scenario)) {
            sendMediaText(context, "Bluetooth", "com.spd.bluetooth", "BT", "KIA TEST");
        } else if ("media_text_fm".equals(scenario)) {
            sendMediaText(context, "FM", "com.spd.radio", "101.0", "FM TEST");
        } else if ("media_text_am".equals(scenario)) {
            sendMediaText(context, "AM", "com.spd.radio", "700", "AM TEST");
        } else if ("app_update_check".equals(scenario)) {
            new AppUpdateController(context).checkAsync();
        } else if ("firmware_check".equals(scenario)) {
            FirmwareUpdateController.get(context).checkAsync();
        } else if ("nav_tbt_on".equals(scenario)) {
            NavigationFeature.get(context).setTbtMode(true);
        } else if ("nav_tbt_off".equals(scenario)) {
            NavigationFeature.get(context).setTbtMode(false);
        } else if ("nav_mode_normal".equals(scenario)) {
            NavigationFeature.get(context).setOutputMode(NavigationOutputMode.NORMAL);
        } else if ("nav_mode_tbt".equals(scenario)) {
            NavigationFeature.get(context).setOutputMode(NavigationOutputMode.TBT);
        } else if ("nav_mode_finish_direction".equals(scenario)) {
            NavigationFeature.get(context).setOutputMode(NavigationOutputMode.FINISH_DIRECTION);
        } else if ("nav_finish_direction_step".equals(scenario)) {
            sendQaFinishDirection(context,
                    intent.getIntExtra("step", 0),
                    intent.getIntExtra("meters", 120));
        } else if ("nav_finish_direction_sweep".equals(scenario)) {
            startQaFinishDirectionSweep(context,
                    intent.getIntExtra("from", 0),
                    intent.getIntExtra("to", 45),
                    intent.getIntExtra("stepSize", 3),
                    intent.getIntExtra("delayMs", 900),
                    intent.getIntExtra("loops", 1),
                    intent.getIntExtra("meters", 120));
        } else if ("nav_text_mode".equals(scenario)) {
            NavigationFeature.get(context).setTextMode(intent.getIntExtra("mode", 0));
        } else if ("nav_maneuver_text_seconds".equals(scenario)) {
            AppSettings.setNavManeuverTextSeconds(context, intent.getIntExtra("seconds", 0));
            NavigationFeature.get(context).setTextMode(AppSettings.navTextMode(context));
            AppLog.line(context, "QA nav maneuver text seconds: "
                    + AppSettings.navManeuverTextSeconds(context));
        } else if ("nav_micro_hold_seconds".equals(scenario)) {
            AppSettings.setNavMicroHoldSeconds(context, intent.getIntExtra("seconds", 5));
            AppLog.line(context, "QA nav micro hold seconds: "
                    + AppSettings.navMicroHoldSeconds(context));
        } else if ("nav_micro_max_distance".equals(scenario)) {
            AppSettings.setNavMicroMaxDistanceMeters(context, intent.getIntExtra("meters", 100));
            AppLog.line(context, "QA nav micro max distance: "
                    + AppSettings.navMicroMaxDistanceMeters(context));
        } else if ("nav_micro_maneuvers".equals(scenario)) {
            boolean enabled = intent.getBooleanExtra("enabled", false);
            AppSettings.setNavMicroManeuvers(context, enabled);
            AppLog.line(context, "QA nav micro maneuvers: " + enabled);
        } else if ("nav_micro_post_pass_sample".equals(scenario)) {
            int seconds = intent.getIntExtra("seconds", AppSettings.navMicroHoldSeconds(context));
            AppSettings.setNavMicroManeuvers(context, true);
            AppSettings.setNavMicroHoldSeconds(context, seconds);
            NavigationFeature feature = NavigationFeature.get(context);
            feature.setActive(false, "qa_nav_micro_post_pass_reset");
            feature.setActive(true, "qa_nav_micro_post_pass");
            feature.handle(qaNavMainIntent());
            feature.handle(qaNavLaneIntent(20));
            feature.handle(qaNavLaneIntent(0));
            AppLog.line(context, "QA nav micro post-pass sample sent: hold="
                    + AppSettings.navMicroHoldSeconds(context));
        } else if ("nav_micro_main_counter_sample".equals(scenario)) {
            AppSettings.setNavMicroManeuvers(context, true);
            NavigationFeature feature = NavigationFeature.get(context);
            feature.setActive(false, "qa_nav_micro_main_counter_reset");
            feature.setActive(true, "qa_nav_micro_main_counter");
            feature.handle(qaNavCounterMainIntent(1000));
            feature.handle(qaNavCounterLaneIntent(1000, 150));
            feature.handle(qaNavCounterLaneIntent(990, 140));
            feature.handle(qaNavCounterLaneIntent(980, 130));
            feature.handle(qaNavCounterLaneIntent(0, 120));
            feature.handle(qaNavCounterLaneIntent(970, 110));
            feature.handle(qaNavCounterLaneIntent(960, 0));
            AppLog.line(context, "QA nav micro main counter sample sent:"
                    + " main=1000,990,980,hold,970,960"
                    + " micro=150,140,130,120,110,0");
        } else if ("nav_main_preview_sample".equals(scenario)) {
            boolean previousStraight = AppSettings.navStraightUntilMain(context);
            int previousReveal = AppSettings.navMainRevealDistanceMeters(context);
            int previousMicroMax = AppSettings.navMicroMaxDistanceMeters(context);
            boolean previousMicro = AppSettings.navMicroManeuvers(context);
            AppSettings.setNavStraightUntilMain(context, true);
            AppSettings.setNavMainRevealDistanceMeters(context,
                    intent.getIntExtra("reveal", 300));
            AppSettings.setNavMicroMaxDistanceMeters(context, 500);
            AppSettings.setNavMicroManeuvers(context, true);
            NavigationFeature feature = NavigationFeature.get(context);
            feature.setOutputMode(NavigationOutputMode.NORMAL);
            feature.setActive(false, "qa_nav_main_preview_reset");
            feature.setActive(true, "qa_nav_main_preview");
            feature.handle(qaNavPreviewMainIntent(2000));
            feature.handle(qaNavPreviewLaneIntent(500, 150));
            feature.handle(qaNavPreviewMainIntent(0));
            feature.resendAfterTransportRecovery("qa_main_preview_transient_zero");
            feature.handle(qaNavPreviewLaneIntent(450, 0));
            feature.handle(qaNavPreviewMainIntent(300));
            feature.handle(qaNavPreviewMainIntent(320));
            feature.handle(qaNavPreviewMainIntent(0));
            AppSettings.setNavStraightUntilMain(context, previousStraight);
            AppSettings.setNavMainRevealDistanceMeters(context, previousReveal);
            AppSettings.setNavMicroMaxDistanceMeters(context, previousMicroMax);
            AppSettings.setNavMicroManeuvers(context, previousMicro);
            AppLog.line(context, "QA nav main preview sample sent:"
                    + " main=2000 forward, micro@500/150, transient0+resend,"
                    + " restore@450,"
                    + " actual=300, latch=320, hold=0");
        } else if ("nav_speed_limit_clear_sample".equals(scenario)) {
            Context appContext = context.getApplicationContext();
            NavigationFeature feature = NavigationFeature.get(appContext);
            feature.setActive(false, "qa_nav_speed_limit_reset");
            feature.setActive(true, "yandex_core_bridge");
            feature.handle(qaNavSpeedIntent(80, false));
            feature.handle(qaNavSpeedIntent(0, true));
            new Handler(Looper.getMainLooper()).postDelayed(() ->
                    AppLog.line(appContext, "QA nav speed clear state: "
                            + StateStore.navigation().summary()), 1800L);
            AppLog.line(context, "QA nav speed limit one-shot clear scheduled");
        } else if ("nav_roundabout_exit_continuity_sample".equals(scenario)) {
            int exit = Math.max(1, Math.min(4, intent.getIntExtra("exit", 2)));
            boolean tbt = intent.getBooleanExtra("tbt", false);
            boolean finishCompassAuto = AppSettings.navFinishCompassAuto(context);
            AppSettings.setNavMicroManeuvers(context, true);
            AppSettings.setNavFinishCompassAuto(context, false);
            NavigationFeature feature = NavigationFeature.get(context);
            feature.setOutputMode(tbt ? NavigationOutputMode.TBT : NavigationOutputMode.NORMAL);
            feature.setActive(false, "qa_nav_roundabout_exit_reset");
            feature.setActive(true, "qa_nav_roundabout_exit");
            feature.handle(qaNavRoundaboutMainIntent(1000));
            feature.handle(qaNavRoundaboutLaneIntent(exit, 990, 150));
            feature.handle(qaNavRoundaboutMainIntent(985));
            feature.handle(qaNavRoundaboutEtaIntent(980));
            feature.handle(qaNavRoundaboutLaneIntent(exit, 0, 120));
            feature.handle(qaNavRoundaboutLaneIntent(exit, 970, 0));
            AppSettings.setNavFinishCompassAuto(context, finishCompassAuto);
            AppLog.line(context, "QA nav roundabout exit continuity sample sent:"
                    + " mode=" + (tbt ? "TBT" : "NORMAL")
                    + " exit=" + exit
                    + " main=1000,990,generic985,980,hold,970"
                    + " micro=150,120,0");
        } else if ("nav_micro_post_pass_refresh_sample".equals(scenario)) {
            int seconds = intent.getIntExtra("seconds", AppSettings.navMicroHoldSeconds(context));
            AppSettings.setNavMicroManeuvers(context, true);
            AppSettings.setNavMicroHoldSeconds(context, seconds);
            Context appContext = context.getApplicationContext();
            NavigationFeature feature = NavigationFeature.get(appContext);
            feature.setActive(false, "qa_nav_micro_post_pass_refresh_reset");
            feature.setActive(true, "qa_nav_micro_post_pass_refresh");
            feature.handle(qaNavMainIntent());
            feature.handle(qaNavLaneIntent(20));
            feature.handle(qaNavLaneIntent(0));
            new Handler(Looper.getMainLooper()).postDelayed(() -> {
                NavigationFeature.get(appContext).handle(qaNavLaneRefreshWithoutDistanceIntent());
                AppLog.line(appContext, "QA nav micro post-pass refresh tick sent: hold="
                        + AppSettings.navMicroHoldSeconds(appContext));
            }, 1000L);
            AppLog.line(appContext, "QA nav micro post-pass refresh sample sent: hold="
                    + AppSettings.navMicroHoldSeconds(appContext));
        } else if ("nav_route_rerouting_sample".equals(scenario)) {
            NavigationFeature feature = NavigationFeature.get(context);
            feature.setActive(false, "qa_nav_route_rerouting_reset");
            feature.setActive(true, "qa_nav_route_rerouting");
            feature.handle(qaNavOldRouteIntent());
            feature.handle(qaNavRerouteIntent());
            AppLog.line(context, "QA nav route rerouting sample sent");
        } else if ("nav_route_rerouting_left_sample".equals(scenario)) {
            NavigationFeature feature = NavigationFeature.get(context);
            feature.setActive(false, "qa_nav_route_rerouting_left_reset");
            feature.handle(qaNavOldRouteIntent());
            feature.handle(qaNavLeftRerouteIntent());
            feature.handle(qaNavLeftRerouteLaneIntent());
            AppLog.line(context, "QA nav route rerouting left sample sent");
        } else if ("nav_gray_uturn_right_sample".equals(scenario)) {
            NavigationFeature feature = NavigationFeature.get(context);
            feature.setActive(false, "qa_nav_gray_uturn_right_reset");
            feature.setActive(true, "qa_nav_gray_uturn_right");
            feature.handle(qaNavGrayUturnRightIntent());
            AppLog.line(context, "QA nav gray uturn right sample sent");
        } else if ("nav_stale_lane_conflict_sample".equals(scenario)) {
            NavigationFeature feature = NavigationFeature.get(context);
            feature.setActive(false, "qa_nav_stale_lane_conflict_reset");
            feature.setActive(true, "qa_nav_stale_lane_conflict");
            feature.handle(qaNavGrayStraightRightIntent(12));
            feature.handle(qaNavStaleRightLaneLeftMainIntent());
            AppLog.line(context, "QA nav stale lane conflict sample sent");
        } else if ("nav_future_lane_gray_conflict_sample".equals(scenario)) {
            NavigationFeature feature = NavigationFeature.get(context);
            feature.setActive(false, "qa_nav_future_lane_gray_conflict_reset");
            feature.setActive(true, "qa_nav_future_lane_gray_conflict");
            feature.handle(qaNavFutureLaneGrayConflictIntent());
            AppLog.line(context, "QA nav future lane gray conflict sample sent");
        } else if ("nav_final_segment_finish_direction_sample".equals(scenario)) {
            NavigationFeature feature = NavigationFeature.get(context);
            feature.setActive(false, "qa_nav_final_segment_reset");
            feature.setActive(true, "qa_nav_final_segment");
            feature.updateGpsLocation(qaNavFinalSegmentLocation());
            feature.updateDeviceHeading(0f, 5f, "qa_nav_final_segment");
            feature.handle(qaNavFinalSegmentBeforeIntent());
            feature.handle(qaNavFinalSegmentAfterIntent());
            AppLog.line(context, "QA nav final segment finish direction sample sent");
        } else if ("nav_final_segment_stale_distance_sample".equals(scenario)) {
            NavigationFeature feature = NavigationFeature.get(context);
            feature.setActive(false, "qa_nav_final_segment_stale_reset");
            feature.setActive(true, "qa_nav_final_segment_stale");
            feature.updateGpsLocation(qaNavFinalSegmentLocation());
            feature.updateDeviceHeading(0f, 5f, "qa_nav_final_segment_stale");
            feature.handle(qaNavFinalSegmentStaleBeforeIntent());
            feature.handle(qaNavFinalSegmentStaleAfterIntent());
            AppLog.line(context, "QA nav final segment stale distance sample sent");
        } else if ("nav_final_segment_active_stale_sample".equals(scenario)) {
            NavigationFeature feature = NavigationFeature.get(context);
            feature.setActive(false, "qa_nav_final_segment_active_stale_reset");
            feature.setActive(true, "qa_nav_final_segment_active_stale");
            feature.updateGpsLocation(qaNavFinalSegmentLocation());
            feature.updateDeviceHeading(0f, 5f, "qa_nav_final_segment_active_stale");
            feature.handle(qaNavFinalSegmentActiveStaleIntent());
            AppLog.line(context, "QA nav final segment active stale sample sent");
        } else if ("nav_finish_hold_stale_route_gps_near_sample".equals(scenario)) {
            NavigationFeature feature = NavigationFeature.get(context);
            feature.setActive(false, "qa_nav_finish_hold_stale_route_gps_near_reset");
            feature.setActive(true, "qa_nav_finish_hold_stale_route_gps_near");
            feature.updateGpsLocation(qaNavFinishHoldGpsNearLocation());
            feature.updateDeviceHeading(0f, 5f, "qa_nav_finish_hold_stale_route_gps_near");
            feature.handle(qaNavFinishHoldActiveIntent());
            feature.handle(qaNavFinishHoldFinishedIntent());
            feature.updateGpsLocation(qaNavFinishHoldGpsNearLocation());
            AppLog.line(context, "QA nav finish hold stale route gps near sample sent");
        } else if ("tpms_sample".equals(scenario)) {
            TpmsController.get(context).handleAdapterFrame(AdapterProtocol.packet(AdapterProtocol.CMD_TPMS,
                    new byte[]{(byte) 148, (byte) 149, (byte) 150, (byte) 147,
                            91, 90, 89, 90}));
        } else if ("tpms_high_pressure_warning".equals(scenario)) {
            TpmsController.get(context).handleAdapterFrame(AdapterProtocol.packet(AdapterProtocol.CMD_TPMS,
                    new byte[]{(byte) 192, (byte) 193, (byte) 194, (byte) 192,
                            91, 90, 89, 90}));
        } else if ("tpms_low_pressure".equals(scenario)) {
            StateStore.setTpms(context, TpmsState.empty());
            TpmsController.get(context).handleAdapterFrame(AdapterProtocol.packet(AdapterProtocol.CMD_TPMS,
                    new byte[]{0, 64, 0, 0, 0, 82, 0, 0}));
        } else if ("tpms_high_pressure".equals(scenario)) {
            TpmsController.get(context).handleAdapterFrame(AdapterProtocol.packet(AdapterProtocol.CMD_TPMS,
                    new byte[]{(byte) 209, (byte) 220, (byte) 215, (byte) 209,
                            91, 90, 89, 90}));
        } else if ("tpms_drive_start".equals(scenario)) {
            StateStore.setNavigation(context, navigationWithSpeed("46"));
            AppLog.line(context, "QA TPMS road: moving");
        } else if ("tpms_widget_nav".equals(scenario)) {
            StateStore.setNavigation(context, navigationWidgetState());
            AppLog.line(context, "QA TPMS widget: navigation badge");
        } else if ("sunroof_open".equals(scenario)) {
            StateStore.setVehicle(context, StateStore.vehicle().withSunroof(true, "qa"));
            AppLog.line(context, "QA sunroof: open");
        } else if ("sunroof_closed".equals(scenario)) {
            StateStore.setVehicle(context, StateStore.vehicle().withSunroof(false, "qa"));
            AppLog.line(context, "QA sunroof: closed");
        } else if ("tpms_drive_stop".equals(scenario)) {
            StateStore.setNavigation(context, navigationWithSpeed("0"));
            AppLog.line(context, "QA TPMS road: stopped");
        } else if ("rcta_demo".equals(scenario)) {
            AppSettings.setRctaOverlayEnabled(context, true);
            AppSettings.setRctaSoundEnabled(context, true);
            Intent demo = new Intent(AppIds.ACTION_RCTA_DEMO);
            demo.setPackage(context.getPackageName());
            context.sendBroadcast(demo);
            AppLog.line(context, "QA RCTA demo: left/right/both");
        } else if ("rcta_left".equals(scenario)) {
            AppSettings.setRctaOverlayEnabled(context, true);
            RctaController.get(context).handleFrame(AdapterProtocol.packet(AdapterProtocol.CMD_RCTA,
                    new byte[]{0x01, 0x00}));
        } else if ("rcta_right".equals(scenario)) {
            AppSettings.setRctaOverlayEnabled(context, true);
            RctaController.get(context).handleFrame(AdapterProtocol.packet(AdapterProtocol.CMD_RCTA,
                    new byte[]{0x00, 0x01}));
        } else if ("rcta_both".equals(scenario)) {
            AppSettings.setRctaOverlayEnabled(context, true);
            RctaController.get(context).handleFrame(AdapterProtocol.packet(AdapterProtocol.CMD_RCTA,
                    new byte[]{0x01, 0x01}));
        } else if ("rcta_clear".equals(scenario)) {
            RctaController.get(context).handleFrame(AdapterProtocol.packet(AdapterProtocol.CMD_RCTA,
                    new byte[]{0x00, 0x00}));
        } else if ("call_start".equals(scenario)) {
            CallFeature.get(context).reportActive(
                    intent.getStringExtra("name"),
                    intent.getStringExtra("phone"),
                    "qa");
        } else if ("call_end".equals(scenario)) {
            CallFeature.get(context).reportEnded("qa");
        } else if ("call_source".equals(scenario)) {
            AppSettings.setCallSourceMode(context, intent.getIntExtra("mode", AppSettings.CALL_SOURCE_CARPLAY));
            CallFeature.get(context).tick();
        }
    }

    private static void startQaFinishDirectionSweep(Context context, int from, int to,
                                                    int stepSize, int delayMs, int loops,
                                                    int meters) {
        Context app = context.getApplicationContext();
        int cleanStep = Math.max(1, Math.abs(stepSize));
        int cleanDelay = Math.max(250, delayMs);
        int cleanLoops = Math.max(1, Math.min(loops, 5));
        int cleanFrom = normalizeFinishStep(from);
        int cleanTo = normalizeFinishStep(to);
        int count = sweepCount(cleanFrom, cleanTo, cleanStep);
        int total = Math.max(1, count * cleanLoops);
        AppLog.line(app, "QA nav finish direction sweep: from=" + cleanFrom
                + " to=" + cleanTo + " step=" + cleanStep + " total=" + total);
        Handler handler = new Handler(Looper.getMainLooper());
        for (int i = 0; i < total; i++) {
            final int index = i;
            handler.postDelayed(() -> {
                int step = normalizeFinishStep(cleanFrom + (index % count) * cleanStep);
                sendQaFinishDirection(app, step, meters);
            }, (long) i * cleanDelay);
        }
    }

    private static int sweepCount(int from, int to, int stepSize) {
        int delta = (to - from + 48) % 48;
        return Math.max(1, delta / stepSize + 1);
    }

    private static int normalizeFinishStep(int step) {
        int out = ((step % 48) + 48) % 48;
        out = Math.round(out / 3f) * 3;
        return out == 48 ? 0 : out;
    }

    private static void sendQaFinishDirection(Context context, int step, int meters) {
        Context app = context.getApplicationContext();
        int cleanStep = normalizeFinishStep(step);
        int cleanMeters = Math.max(1, meters);
        AdapterGateway gateway = AdapterGateway.get(app);
        gateway.send(AdapterCommand.loud("qa nav active", AdapterProtocol.navOn(true)));
        byte[] frame = AdapterProtocol.directionToFinish(cleanStep, cleanMeters, false);
        gateway.send(AdapterCommand.loud("qa nav finish direction step=" + cleanStep, frame));
        AppLog.line(app, "QA nav finish direction step=" + cleanStep
                + " bytes=" + AdapterProtocol.hex(frame));
    }

    private static void sendMediaSource(Context context, String source, String packageName) {
        MediaState state = new MediaState(source, packageName, "", "", -1L, false,
                System.currentTimeMillis());
        mediaSender(context).sendSourceOnly(state);
        AppLog.line(context, "QA media source: " + source + " / " + packageName);
    }

    private static void sendMediaText(Context context, String source, String packageName,
                                      String artist, String title) {
        MediaState state = new MediaState(source, packageName, artist, title, -1L, true,
                System.currentTimeMillis());
        mediaSender(context).send(state);
        AppLog.line(context, "QA media text: " + source + " / " + packageName
                + " / " + artist + " / " + title);
    }

    private static MediaClusterSender mediaSender(Context context) {
        return AppSettings.universalMediaProfile(context)
                ? MediaClusterSender.get(context)
                : new MediaClusterSender(context);
    }

    private static void setMediaProfile(Context context, int profile) {
        AppSettings.setMediaProfile(context, profile);
        MediaFeature.get(context).onProfileChanged();
        AppService.start(context);
        AppLog.line(context, "QA media profile: " + AppSettings.mediaProfileLabel(context));
        MediaCaptureManager.scanOnce(context);
    }

    private static Intent qaNavMainIntent() {
        Intent intent = new Intent(NavigationFeature.KIA_ACTION_MANEUVER);
        intent.putExtra("source", "yandex_core_bridge");
        intent.putExtra("active", true);
        intent.putExtra("route_id", "qa_micro_post_pass");
        intent.putExtra("maneuver", "RIGHT");
        intent.putExtra("direction", "RIGHT");
        intent.putExtra("maneuver_text", "right");
        intent.putExtra("distance", "2700 m");
        intent.putExtra("current_maneuver_distance", "2700 m");
        intent.putExtra("current_maneuver_distance_meters", 2700);
        intent.putExtra("remaining_distance", "5.3 km");
        intent.putExtra("route_time", "13 min");
        intent.putExtra("arrival_time", "11:30");
        return intent;
    }

    private static Intent qaNavLaneIntent(int laneMeters) {
        Intent intent = qaNavMainIntent();
        intent.putExtra("lane_guidance", true);
        intent.putExtra("lane_maneuver", "STRAIGHT_AHEAD");
        intent.putExtra("highlighted_direction", "STRAIGHT_AHEAD");
        intent.putExtra("highlighted_directions", "STRAIGHT_AHEAD");
        intent.putExtra("lane_highlight", "STRAIGHT_AHEAD");
        intent.putExtra("recommended_lanes", "STRAIGHT_AHEAD");
        intent.putExtra("lane_distance", laneMeters + " m");
        intent.putExtra("lane_distance_meters", laneMeters);
        intent.putExtra("micro_distance", laneMeters + " m");
        intent.putExtra("micro_distance_meters", laneMeters);
        intent.putExtra("route_road_options", "straight,left");
        intent.putExtra("gray_road_options", "straight,left");
        intent.putExtra("allowed_directions", "straight,left");
        intent.putExtra("lane_topology", "straight,left highlight=STRAIGHT_AHEAD");
        return intent;
    }

    private static Intent qaNavLaneRefreshWithoutDistanceIntent() {
        Intent intent = qaNavMainIntent();
        intent.putExtra("lane_guidance", true);
        intent.putExtra("lane_maneuver", "STRAIGHT_AHEAD");
        intent.putExtra("highlighted_direction", "STRAIGHT_AHEAD");
        intent.putExtra("highlighted_directions", "STRAIGHT_AHEAD");
        intent.putExtra("lane_highlight", "STRAIGHT_AHEAD");
        intent.putExtra("recommended_lanes", "STRAIGHT_AHEAD");
        intent.putExtra("route_road_options", "straight,left");
        intent.putExtra("gray_road_options", "straight,left");
        intent.putExtra("allowed_directions", "straight,left");
        intent.putExtra("lane_topology", "straight,left highlight=STRAIGHT_AHEAD");
        return intent;
    }

    private static Intent qaNavCounterMainIntent(int mainMeters) {
        Intent intent = qaNavMainIntent();
        intent.putExtra("route_id", "qa_micro_main_counter");
        putQaMainDistance(intent, mainMeters);
        return intent;
    }

    private static Intent qaNavCounterLaneIntent(int mainMeters, int laneMeters) {
        Intent intent = qaNavLaneIntent(laneMeters);
        intent.putExtra("route_id", "qa_micro_main_counter");
        putQaMainDistance(intent, mainMeters);
        return intent;
    }

    private static Intent qaNavPreviewMainIntent(int mainMeters) {
        Intent intent = qaNavCounterMainIntent(mainMeters);
        intent.putExtra("route_id", "qa_main_preview");
        intent.putExtra("next_street", "Preview road");
        return intent;
    }

    private static Intent qaNavPreviewLaneIntent(int mainMeters, int laneMeters) {
        Intent intent = qaNavCounterLaneIntent(mainMeters, laneMeters);
        intent.putExtra("route_id", "qa_main_preview");
        intent.putExtra("next_street", "Preview road");
        return intent;
    }

    private static Intent qaNavSpeedIntent(int limit, boolean clear) {
        Intent intent = new Intent(NavigationFeature.KIA_ACTION_SPEED);
        intent.putExtra("source", "yandex_core_bridge");
        intent.putExtra("current_speed", "60");
        intent.putExtra("road_speed_limit_present", true);
        if (limit > 0) {
            intent.putExtra("road_speed_limit", String.valueOf(limit));
            intent.putExtra("speed_limit", String.valueOf(limit));
        }
        if (clear) intent.putExtra("speed_limit_clear", true);
        return intent;
    }

    private static void putQaMainDistance(Intent intent, int meters) {
        String distance = meters + " m";
        intent.putExtra("distance", distance);
        intent.putExtra("maneuver_distance", distance);
        intent.putExtra("current_maneuver_distance", distance);
        intent.putExtra("distance_to_maneuver", distance);
        intent.putExtra("maneuver_distance_meters", meters);
        intent.putExtra("current_maneuver_distance_meters", meters);
        intent.putExtra("distance_to_maneuver_meters", meters);
    }

    private static Intent qaNavRoundaboutMainIntent(int mainMeters) {
        Intent intent = new Intent(NavigationFeature.KIA_ACTION_MANEUVER);
        intent.putExtra("source", "yandex_core_bridge");
        intent.putExtra("active", true);
        intent.putExtra("route_id", "qa_circle_refinement");
        intent.putExtra("maneuver", "ROUNDABOUT");
        intent.putExtra("direction", "ROUNDABOUT");
        intent.putExtra("maneuver_text", "roundabout");
        intent.putExtra("route_distance", "5.0 km");
        intent.putExtra("route_remaining", "5.0 km");
        intent.putExtra("remaining_distance", "5.0 km");
        intent.putExtra("route_time", "12 min");
        intent.putExtra("arrival_time", "11:30");
        putQaMainDistance(intent, mainMeters);
        return intent;
    }

    private static Intent qaNavRoundaboutLaneIntent(int exit, int mainMeters, int laneMeters) {
        Intent intent = qaNavRoundaboutMainIntent(mainMeters);
        intent.putExtra("exit_number", exit);
        intent.putExtra("roundabout_exit_number", exit);
        intent.putExtra("lane_guidance", true);
        intent.putExtra("lane_maneuver", "RIGHT");
        intent.putExtra("highlighted_direction", "RIGHT");
        intent.putExtra("highlighted_directions", "RIGHT");
        intent.putExtra("lane_highlight", "RIGHT");
        intent.putExtra("recommended_lanes", "RIGHT");
        intent.putExtra("lane_distance", laneMeters + " m");
        intent.putExtra("lane_distance_meters", laneMeters);
        intent.putExtra("micro_distance", laneMeters + " m");
        intent.putExtra("micro_distance_meters", laneMeters);
        return intent;
    }

    private static Intent qaNavRoundaboutEtaIntent(int mainMeters) {
        Intent intent = qaNavRoundaboutMainIntent(mainMeters);
        intent.setAction(NavigationFeature.KIA_ACTION_ETA);
        intent.putExtra("maneuver_distance_identity", "ROUNDABOUT");
        intent.putExtra("main_maneuver_identity", "ROUNDABOUT");
        intent.putExtra("maneuver_distance_provenance", "annotation");
        return intent;
    }

    private static Intent qaNavOldRouteIntent() {
        Intent intent = new Intent(NavigationFeature.KIA_ACTION_NAVI_ON);
        intent.putExtra("source", "yandex_core_bridge");
        intent.putExtra("active", true);
        intent.putExtra("navi_on", true);
        intent.putExtra("bridge_state", "active");
        intent.putExtra("route_id", "qa_route_old");
        intent.putExtra("maneuver", "RIGHT");
        intent.putExtra("direction", "RIGHT");
        intent.putExtra("maneuver_text", "направо");
        intent.putExtra("current_maneuver_distance", "530 м");
        intent.putExtra("current_maneuver_distance_meters", 530);
        intent.putExtra("distance_to_maneuver", "530 м");
        intent.putExtra("distance_to_maneuver_meters", 530);
        intent.putExtra("lane_guidance", true);
        intent.putExtra("lane_distance", "523 м");
        intent.putExtra("lane_distance_meters", 523);
        intent.putExtra("micro_distance", "523 м");
        intent.putExtra("micro_distance_meters", 523);
        intent.putExtra("highlighted_direction", "RIGHT90");
        intent.putExtra("highlighted_directions", "RIGHT90");
        intent.putExtra("route_road_options", "left,right");
        intent.putExtra("gray_road_options", "left,right");
        intent.putExtra("lane_topology", "left,right highlight=RIGHT90");
        intent.putExtra("remaining_distance", "3.2 км");
        intent.putExtra("remaining_distance_meters", 3154);
        intent.putExtra("route_time", "9 мин");
        intent.putExtra("arrival_time", "11:40");
        intent.putExtra("route_total_len", "6.4 км");
        intent.putExtra("current_street", "улица Махамбета Утемисова");
        return intent;
    }

    private static Intent qaNavRerouteIntent() {
        Intent intent = new Intent(NavigationFeature.KIA_ACTION_NAVI_ON);
        intent.putExtra("source", "yandex_core_bridge");
        intent.putExtra("active", true);
        intent.putExtra("navi_on", true);
        intent.putExtra("bridge_state", "active");
        intent.putExtra("route_id", "qa_route_new");
        intent.putExtra("maneuver", "RIGHT");
        intent.putExtra("direction", "RIGHT");
        intent.putExtra("maneuver_text", "направо");
        intent.putExtra("current_maneuver_distance", "188 м");
        intent.putExtra("current_maneuver_distance_meters", 188);
        intent.putExtra("distance_to_maneuver", "188 м");
        intent.putExtra("distance_to_maneuver_meters", 188);
        intent.putExtra("lane_guidance", true);
        intent.putExtra("lane_distance", "189 м");
        intent.putExtra("lane_distance_meters", 189);
        intent.putExtra("micro_distance", "189 м");
        intent.putExtra("micro_distance_meters", 189);
        intent.putExtra("highlighted_direction", "RIGHT90");
        intent.putExtra("highlighted_directions", "RIGHT90");
        intent.putExtra("route_road_options", "straight,left,right");
        intent.putExtra("gray_road_options", "straight,left,right");
        intent.putExtra("lane_topology", "straight,left,right highlight=RIGHT90");
        intent.putExtra("remaining_distance", "3.3 км");
        intent.putExtra("remaining_distance_meters", 3314);
        intent.putExtra("route_time", "9 мин");
        intent.putExtra("arrival_time", "11:40");
        intent.putExtra("route_total_len", "3.3 км");
        intent.putExtra("current_street", "улица Махамбета Утемисова");
        intent.putExtra("street_after_maneuver", "улица Гизата Алипова");
        return intent;
    }

    private static Intent qaNavLeftRerouteIntent() {
        Intent intent = new Intent(NavigationFeature.KIA_ACTION_NAVI_ON);
        intent.putExtra("source", "yandex_core_bridge");
        intent.putExtra("active", true);
        intent.putExtra("navi_on", true);
        intent.putExtra("bridge_state", "active");
        intent.putExtra("route_id", "qa_route_left");
        intent.putExtra("maneuver", "LEFT");
        intent.putExtra("direction", "LEFT");
        intent.putExtra("maneuver_text", "налево");
        intent.putExtra("current_maneuver_distance", "160 м");
        intent.putExtra("current_maneuver_distance_meters", 160);
        intent.putExtra("distance_to_maneuver", "160 м");
        intent.putExtra("distance_to_maneuver_meters", 160);
        intent.putExtra("remaining_distance", "2.8 км");
        intent.putExtra("remaining_distance_meters", 2800);
        intent.putExtra("route_time", "8 мин");
        intent.putExtra("arrival_time", "11:44");
        intent.putExtra("route_total_len", "2.8 км");
        intent.putExtra("current_street", "улица Махамбета Утемисова");
        intent.putExtra("street_after_maneuver", "улица Сырым Датова");
        return intent;
    }

    private static Intent qaNavLeftRerouteLaneIntent() {
        Intent intent = new Intent(NavigationFeature.KIA_ACTION_MANEUVER);
        intent.putExtra("source", "yandex_core_bridge");
        intent.putExtra("active", true);
        intent.putExtra("bridge_state", "active");
        intent.putExtra("route_id", "qa_route_left");
        intent.putExtra("maneuver", "LEFT");
        intent.putExtra("direction", "LEFT");
        intent.putExtra("route_action", "LEFT");
        intent.putExtra("distance", "160 м");
        intent.putExtra("current_maneuver_distance", "160 м");
        intent.putExtra("current_maneuver_distance_meters", 160);
        intent.putExtra("lane_guidance", true);
        intent.putExtra("lane_distance", "160 м");
        intent.putExtra("lane_distance_meters", 160);
        intent.putExtra("micro_distance", "160 м");
        intent.putExtra("micro_distance_meters", 160);
        intent.putExtra("highlighted_direction", "LEFT90");
        intent.putExtra("highlighted_directions", "LEFT90");
        intent.putExtra("route_road_options", "left");
        intent.putExtra("gray_road_options", "left");
        intent.putExtra("lane_topology", "left highlight=LEFT90");
        intent.putExtra("remaining_distance", "2.8 км");
        intent.putExtra("route_time", "8 мин");
        intent.putExtra("arrival_time", "11:44");
        intent.putExtra("current_street", "улица Махамбета Утемисова");
        intent.putExtra("street_after_maneuver", "улица Сырым Датова");
        return intent;
    }

    private static Intent qaNavGrayUturnRightIntent() {
        Intent intent = new Intent(NavigationFeature.KIA_ACTION_MANEUVER);
        intent.putExtra("source", "yandex_core_bridge");
        intent.putExtra("active", true);
        intent.putExtra("bridge_state", "active");
        intent.putExtra("route_id", "qa_gray_uturn_right");
        intent.putExtra("maneuver", "RIGHT");
        intent.putExtra("direction", "RIGHT");
        intent.putExtra("route_action", "RIGHT");
        intent.putExtra("maneuver_text", "направо");
        intent.putExtra("voice_hint", "направо");
        intent.putExtra("distance", "47 м");
        intent.putExtra("current_maneuver_distance", "47 м");
        intent.putExtra("current_maneuver_distance_meters", 47);
        intent.putExtra("distance_to_maneuver", "47 м");
        intent.putExtra("distance_to_maneuver_meters", 47);
        intent.putExtra("lane_guidance", true);
        intent.putExtra("lane_distance", "47 м");
        intent.putExtra("lane_distance_meters", 47);
        intent.putExtra("micro_distance", "47 м");
        intent.putExtra("micro_distance_meters", 47);
        intent.putExtra("highlighted_direction", "RIGHT90");
        intent.putExtra("highlighted_directions", "RIGHT90");
        intent.putExtra("lane_highlight", "RIGHT90");
        intent.putExtra("recommended_lanes", "RIGHT90");
        intent.putExtra("route_road_options", "straight,left,right");
        intent.putExtra("gray_road_options", "straight,left,right");
        intent.putExtra("allowed_directions", "straight,left,right");
        intent.putExtra("raw_lane_items",
                "0:PLAIN_LANE:LEFT180,STRAIGHT_AHEAD | 1:PLAIN_LANE:STRAIGHT_AHEAD,RIGHT90*RIGHT90");
        intent.putExtra("ignored_raw_lane_items",
                "0:PLAIN_LANE:LEFT180,STRAIGHT_AHEAD | 1:PLAIN_LANE:STRAIGHT_AHEAD,RIGHT90*RIGHT90");
        intent.putExtra("lane_topology_json",
                "{\"lanes\":[{\"index\":0,\"kind\":\"PLAIN_LANE\",\"directions\":[\"LEFT180\",\"STRAIGHT_AHEAD\"],\"highlight\":\"\"},{\"index\":1,\"kind\":\"PLAIN_LANE\",\"directions\":[\"STRAIGHT_AHEAD\",\"RIGHT90\"],\"highlight\":\"RIGHT90\"}]}");
        intent.putExtra("lane_topology",
                "straight,left,right,uturn_left highlight=RIGHT90 lanes=0:PLAIN_LANE:LEFT180,STRAIGHT_AHEAD | 1:PLAIN_LANE:STRAIGHT_AHEAD,RIGHT90*RIGHT90");
        intent.putExtra("remaining_distance", "3.0 км");
        intent.putExtra("route_time", "7 мин");
        intent.putExtra("arrival_time", "12:10");
        intent.putExtra("current_street", "QA current street");
        intent.putExtra("street_after_maneuver", "QA next street");
        return intent;
    }

    private static Intent qaNavGrayStraightRightIntent(int laneMeters) {
        Intent intent = new Intent(NavigationFeature.KIA_ACTION_MANEUVER);
        intent.putExtra("source", "yandex_core_bridge");
        intent.putExtra("active", true);
        intent.putExtra("bridge_state", "active");
        intent.putExtra("route_id", "qa_stale_lane_conflict");
        intent.putExtra("maneuver", "RIGHT");
        intent.putExtra("direction", "RIGHT");
        intent.putExtra("maneuver_text", "направо");
        intent.putExtra("distance", "12 м");
        intent.putExtra("current_maneuver_distance", "12 м");
        intent.putExtra("current_maneuver_distance_meters", 12);
        intent.putExtra("lane_guidance", true);
        intent.putExtra("lane_distance", laneMeters + " м");
        intent.putExtra("lane_distance_meters", laneMeters);
        intent.putExtra("micro_distance", laneMeters + " м");
        intent.putExtra("micro_distance_meters", laneMeters);
        intent.putExtra("highlighted_direction", "RIGHT90");
        intent.putExtra("highlighted_directions", "RIGHT90");
        intent.putExtra("lane_highlight", "RIGHT90");
        intent.putExtra("recommended_lanes", "RIGHT90");
        intent.putExtra("route_road_options", "straight,right");
        intent.putExtra("gray_road_options", "straight,right");
        intent.putExtra("allowed_directions", "straight,right");
        intent.putExtra("raw_lane_items",
                "0:PLAIN_LANE:STRAIGHT_AHEAD | 1:PLAIN_LANE:STRAIGHT_AHEAD,RIGHT90*RIGHT90");
        intent.putExtra("lane_topology",
                "straight,right highlight=RIGHT90 lanes=0:PLAIN_LANE:STRAIGHT_AHEAD | 1:PLAIN_LANE:STRAIGHT_AHEAD,RIGHT90*RIGHT90");
        intent.putExtra("current_street", "QA first street");
        intent.putExtra("street_after_maneuver", "QA second street");
        return intent;
    }

    private static Intent qaNavStaleRightLaneLeftMainIntent() {
        Intent intent = new Intent(NavigationFeature.KIA_ACTION_MANEUVER);
        intent.putExtra("source", "yandex_core_bridge");
        intent.putExtra("active", true);
        intent.putExtra("bridge_state", "active");
        intent.putExtra("route_id", "qa_stale_lane_conflict");
        intent.putExtra("maneuver", "LEFT");
        intent.putExtra("direction", "LEFT");
        intent.putExtra("maneuver_text", "налево");
        intent.putExtra("voice_hint", "налево");
        intent.putExtra("distance", "191 м");
        intent.putExtra("current_maneuver_distance", "191 м");
        intent.putExtra("current_maneuver_distance_meters", 191);
        intent.putExtra("lane_guidance", true);
        intent.putExtra("highlighted_direction", "RIGHT90");
        intent.putExtra("highlighted_directions", "RIGHT90");
        intent.putExtra("lane_highlight", "RIGHT90");
        intent.putExtra("recommended_lanes", "RIGHT90");
        intent.putExtra("route_road_options", "straight,right");
        intent.putExtra("gray_road_options", "straight,right");
        intent.putExtra("allowed_directions", "straight,right");
        intent.putExtra("raw_lane_items",
                "0:PLAIN_LANE:STRAIGHT_AHEAD | 1:PLAIN_LANE:STRAIGHT_AHEAD,RIGHT90*RIGHT90");
        intent.putExtra("lane_topology",
                "straight,right highlight=RIGHT90 lanes=0:PLAIN_LANE:STRAIGHT_AHEAD | 1:PLAIN_LANE:STRAIGHT_AHEAD,RIGHT90*RIGHT90");
        intent.putExtra("current_street", "QA second street");
        intent.putExtra("street_after_maneuver", "QA third street");
        return intent;
    }

    private static Intent qaNavFutureLaneGrayConflictIntent() {
        Intent intent = new Intent(NavigationFeature.KIA_ACTION_MANEUVER);
        intent.putExtra("source", "yandex_core_bridge");
        intent.putExtra("active", true);
        intent.putExtra("bridge_state", "active");
        intent.putExtra("route_id", "qa_future_lane_gray_conflict");
        intent.putExtra("maneuver", "LEFT");
        intent.putExtra("direction", "LEFT");
        intent.putExtra("route_action", "LEFT");
        intent.putExtra("maneuver_text", "налево");
        intent.putExtra("voice_hint", "налево");
        intent.putExtra("distance", "102 м");
        intent.putExtra("current_maneuver_distance", "102 м");
        intent.putExtra("current_maneuver_distance_meters", 102);
        intent.putExtra("distance_to_maneuver", "102 м");
        intent.putExtra("distance_to_maneuver_meters", 102);
        intent.putExtra("remaining_distance", "590 м");
        intent.putExtra("remaining_distance_meters", 590);
        intent.putExtra("route_time", "2 мин");
        intent.putExtra("arrival_time", "13:10");
        intent.putExtra("route_total_len", "590 м");
        intent.putExtra("lane_guidance", true);
        intent.putExtra("lane_distance", "168 м");
        intent.putExtra("lane_distance_meters", 168);
        intent.putExtra("micro_distance", "168 м");
        intent.putExtra("micro_distance_meters", 168);
        intent.putExtra("highlighted_direction", "RIGHT90");
        intent.putExtra("highlighted_directions", "RIGHT90");
        intent.putExtra("lane_highlight", "RIGHT90");
        intent.putExtra("recommended_lanes", "RIGHT90");
        intent.putExtra("route_road_options", "straight,right");
        intent.putExtra("gray_road_options", "straight,right");
        intent.putExtra("allowed_directions", "straight,right");
        intent.putExtra("raw_lane_items",
                "0:PLAIN_LANE:STRAIGHT_AHEAD | 1:PLAIN_LANE:STRAIGHT_AHEAD,RIGHT90*RIGHT90");
        intent.putExtra("lane_topology_json",
                "{\"lanes\":[{\"index\":0,\"kind\":\"PLAIN_LANE\",\"directions\":[\"STRAIGHT_AHEAD\"],\"highlight\":\"\"},{\"index\":1,\"kind\":\"PLAIN_LANE\",\"directions\":[\"STRAIGHT_AHEAD\",\"RIGHT90\"],\"highlight\":\"RIGHT90\"}]}");
        intent.putExtra("lane_topology",
                "straight,right highlight=RIGHT90 lanes=0:PLAIN_LANE:STRAIGHT_AHEAD | 1:PLAIN_LANE:STRAIGHT_AHEAD,RIGHT90*RIGHT90");
        intent.putExtra("current_street", "улица Бимаганова");
        intent.putExtra("street_after_maneuver", "проспект Мухтара Ауэзова");
        return intent;
    }

    private static Location qaNavFinalSegmentLocation() {
        Location location = new Location("qa_nav_final_segment");
        location.setLatitude(47.000000);
        location.setLongitude(51.000000);
        location.setBearing(0f);
        location.setSpeed(8f);
        location.setTime(System.currentTimeMillis());
        return location;
    }

    private static Intent qaNavFinalSegmentBeforeIntent() {
        Intent intent = new Intent(NavigationFeature.KIA_ACTION_MANEUVER);
        intent.putExtra("source", "yandex_core_bridge");
        intent.putExtra("active", true);
        intent.putExtra("bridge_state", "active");
        intent.putExtra("route_id", "qa_final_segment");
        intent.putExtra("maneuver", "RIGHT");
        intent.putExtra("direction", "RIGHT");
        intent.putExtra("maneuver_text", "направо");
        intent.putExtra("distance", "40 м");
        intent.putExtra("current_maneuver_distance", "40 м");
        intent.putExtra("current_maneuver_distance_meters", 40);
        intent.putExtra("distance_to_maneuver", "40 м");
        intent.putExtra("distance_to_maneuver_meters", 40);
        intent.putExtra("remaining_distance", "190 м");
        intent.putExtra("remaining_distance_meters", 190);
        intent.putExtra("route_time", "1 мин");
        intent.putExtra("arrival_time", "12:30");
        intent.putExtra("route_total_len", "2.4 км");
        intent.putExtra("current_street", "QA final current");
        intent.putExtra("street_after_maneuver", "QA final tail");
        intent.putExtra("finish_point", "47.001350,51.000000");
        return intent;
    }

    private static Intent qaNavFinalSegmentAfterIntent() {
        Intent intent = qaNavFinalSegmentBeforeIntent();
        intent.putExtra("distance", "0 м");
        intent.putExtra("current_maneuver_distance", "0 м");
        intent.putExtra("current_maneuver_distance_meters", 0);
        intent.putExtra("distance_to_maneuver", "0 м");
        intent.putExtra("distance_to_maneuver_meters", 0);
        intent.putExtra("remaining_distance", "150 м");
        intent.putExtra("remaining_distance_meters", 150);
        return intent;
    }

    private static Intent qaNavFinalSegmentStaleBeforeIntent() {
        Intent intent = qaNavFinalSegmentBeforeIntent();
        intent.putExtra("distance", "120 м");
        intent.putExtra("current_maneuver_distance", "120 м");
        intent.putExtra("current_maneuver_distance_meters", 120);
        intent.putExtra("distance_to_maneuver", "120 м");
        intent.putExtra("distance_to_maneuver_meters", 120);
        intent.putExtra("remaining_distance", "180 м");
        intent.putExtra("remaining_distance_meters", 180);
        return intent;
    }

    private static Intent qaNavFinalSegmentStaleAfterIntent() {
        Intent intent = qaNavFinalSegmentBeforeIntent();
        intent.putExtra("distance", "118 м");
        intent.putExtra("current_maneuver_distance", "118 м");
        intent.putExtra("current_maneuver_distance_meters", 118);
        intent.putExtra("distance_to_maneuver", "118 м");
        intent.putExtra("distance_to_maneuver_meters", 118);
        intent.putExtra("remaining_distance", "116 м");
        intent.putExtra("remaining_distance_meters", 116);
        return intent;
    }

    private static Intent qaNavFinalSegmentActiveStaleIntent() {
        Intent intent = qaNavFinalSegmentStaleAfterIntent();
        intent.setAction(NavigationFeature.KIA_ACTION_NAVI_ON);
        intent.putExtra("navi_on", true);
        intent.putExtra("route_distance", "116 м");
        intent.putExtra("edistance", "116 м");
        return intent;
    }

    private static Location qaNavFinishHoldGpsNearLocation() {
        Location location = new Location("qa_nav_finish_hold");
        location.setLatitude(47.001350);
        location.setLongitude(51.000000);
        location.setBearing(0f);
        location.setSpeed(1f);
        location.setTime(System.currentTimeMillis());
        return location;
    }

    private static Intent qaNavFinishHoldActiveIntent() {
        Intent intent = new Intent(NavigationFeature.KIA_ACTION_NAVI_ON);
        intent.putExtra("source", "yandex_core_bridge");
        intent.putExtra("active", true);
        intent.putExtra("navi_on", true);
        intent.putExtra("bridge_state", "active");
        intent.putExtra("route_id", "qa_finish_hold_stale_route_gps_near");
        intent.putExtra("route_distance", "40 м");
        intent.putExtra("edistance", "40 м");
        intent.putExtra("remaining_distance", "40 м");
        intent.putExtra("remaining_distance_meters", 40);
        intent.putExtra("route_time", "1 мин");
        intent.putExtra("arrival_time", "12:40");
        intent.putExtra("maneuver", "FINISH");
        intent.putExtra("direction", "FINISH");
        intent.putExtra("current_maneuver_distance", "40 м");
        intent.putExtra("current_maneuver_distance_meters", 40);
        intent.putExtra("distance_to_maneuver", "40 м");
        intent.putExtra("distance_to_maneuver_meters", 40);
        intent.putExtra("current_street", "QA finish street");
        intent.putExtra("finish_point", "47.001350,51.000000");
        return intent;
    }

    private static Intent qaNavFinishHoldFinishedIntent() {
        Intent intent = new Intent(NavigationFeature.KIA_ACTION_NAVI_ON);
        intent.putExtra("source", "yandex_core_bridge");
        intent.putExtra("active", false);
        intent.putExtra("navi_on", false);
        intent.putExtra("bridge_state", "finished");
        intent.putExtra("route_id", "qa_finish_hold_stale_route_gps_near");
        intent.putExtra("finish_reached", true);
        intent.putExtra("route_finished", true);
        return intent;
    }

    private static NavigationState navigationWithSpeed(String speed) {
        return new NavigationState(true, false, false,
                "", "", "", "", "", "",
                "", "", "", "", speed, "qa_tpms_motion",
                System.currentTimeMillis());
    }

    private static NavigationState navigationWidgetState() {
        return new NavigationState(true, false, false,
                "context_ra_turn_right", "Направо", "120 м", "3.2 км", "8 мин",
                "18:40", "ул. Гагарина", "ул. Абая", "", "60", "46",
                "qa_tpms_widget", System.currentTimeMillis());
    }
}
