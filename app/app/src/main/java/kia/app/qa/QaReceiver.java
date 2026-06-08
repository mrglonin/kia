package kia.app.qa;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

import kia.app.core.AppIds;
import kia.app.core.AppLog;
import kia.app.core.StateStore;
import kia.app.core.model.MediaState;
import kia.app.core.model.NavigationState;
import kia.app.core.model.TpmsState;
import kia.app.media.capture.MediaCaptureManager;
import kia.app.media.cluster.MediaClusterSender;
import kia.app.media.domain.CallFeature;
import kia.app.core.settings.AppSettings;
import kia.app.navigation.capture.DgisNotificationParser;
import kia.app.navigation.domain.NavigationFeature;
import kia.app.navigation.domain.NavigationOutputMode;
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

    private static void sendMediaSource(Context context, String source, String packageName) {
        MediaState state = new MediaState(source, packageName, "", "", -1L, false,
                System.currentTimeMillis());
        new MediaClusterSender(context).sendSourceOnly(state);
        AppLog.line(context, "QA media source: " + source + " / " + packageName);
    }

    private static void sendMediaText(Context context, String source, String packageName,
                                      String artist, String title) {
        MediaState state = new MediaState(source, packageName, artist, title, -1L, true,
                System.currentTimeMillis());
        new MediaClusterSender(context).send(state);
        AppLog.line(context, "QA media text: " + source + " / " + packageName
                + " / " + artist + " / " + title);
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
