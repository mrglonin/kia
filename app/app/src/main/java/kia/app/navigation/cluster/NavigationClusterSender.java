package kia.app.navigation.cluster;

import android.content.Context;

import kia.app.protocol.adapter.AdapterCommand;
import kia.app.protocol.adapter.AdapterGateway;
import kia.app.protocol.adapter.AdapterProtocol;
import kia.app.core.AppLog;
import kia.app.core.StateStore;
import kia.app.navigation.domain.NavigationModeSettings;

public final class NavigationClusterSender {
    private final Context app;
    private final NormalNavigationOutput normalOutput = new NormalNavigationOutput();
    private final TbtNavigationOutput tbtOutput = new TbtNavigationOutput();
    private final FinishDirectionNavigationOutput finishDirectionOutput = new FinishDirectionNavigationOutput();

    public NavigationClusterSender(Context context) {
        this.app = context.getApplicationContext();
    }

    public void sendActive(boolean active) {
        byte[] frame = AdapterProtocol.navOn(active);
        AdapterGateway.get(app).send(AdapterCommand.loud("nav active", frame));
    }

    public void sendManeuver(String imageId, float distance, boolean km) {
        sendManeuver(imageId, distance, km, -1);
    }

    public void sendManeuver(String imageId, float distance, boolean km, int progressBucket) {
        int logicalProgress = normalizeProgress(progressBucket);
        int txProgress = AdapterProtocol.txProgressBucket(logicalProgress);
        boolean tbt = NavigationModeSettings.isTbt(app);
        byte[] frame = tbt
                ? tbtOutput.maneuver(imageId, distance, km, logicalProgress)
                : normalOutput.maneuver(imageId, distance, km, logicalProgress);
        sendActive(true);
        AppLog.line(app, "Navigation maneuver TX: " + imageId + " dist=" + distance
                + (km ? "km" : "m") + " progress=" + logicalProgress
                + " txProgress=" + txProgress
                + " mode=" + NavigationModeSettings.label(app)
                + " bytes=" + AdapterProtocol.hex(frame));
        recordTx("maneuver " + imageId + " dist=" + distance + (km ? "km" : "m")
                + " progress=" + logicalProgress + " txProgress=" + txProgress
                + " bytes=" + AdapterProtocol.hex(frame));
        AdapterGateway.get(app).send(AdapterCommand.loud("nav maneuver", frame));
    }

    public void sendManeuverWithGrayRoad(String imageId, String grayRoadId, float distance,
                                         boolean km, int progressBucket) {
        int logicalProgress = normalizeProgress(progressBucket);
        int txProgress = AdapterProtocol.txProgressBucket(logicalProgress);
        boolean tbt = NavigationModeSettings.isTbt(app);
        byte[] frame = tbt
                ? tbtOutput.maneuverWithGrayRoad(imageId, grayRoadId, distance, km, logicalProgress)
                : normalOutput.maneuverWithGrayRoad(imageId, grayRoadId, distance, km, logicalProgress);
        sendActive(true);
        AppLog.line(app, "Navigation maneuver+gray TX: " + imageId + " gray=" + grayRoadId
                + " dist=" + distance + (km ? "km" : "m") + " progress=" + logicalProgress
                + " txProgress=" + txProgress
                + " mode=" + NavigationModeSettings.label(app)
                + " bytes=" + AdapterProtocol.hex(frame));
        recordTx("maneuver+gray " + imageId + " gray=" + grayRoadId
                + " dist=" + distance + (km ? "km" : "m")
                + " progress=" + logicalProgress + " txProgress=" + txProgress
                + " bytes=" + AdapterProtocol.hex(frame));
        AdapterGateway.get(app).send(AdapterCommand.loud("nav maneuver gray", frame));
    }

    public void sendEta(float distance, boolean km) {
        byte[] frame = AdapterProtocol.etaDistance(distance, km);
        recordTx("eta distance=" + distance + (km ? "km" : "m")
                + " bytes=" + AdapterProtocol.hex(frame));
        AdapterGateway.get(app).send(AdapterCommand.loud("nav eta", frame));
    }

    public void sendEtaTime(int hour, int minute) {
        byte[] frame = AdapterProtocol.etaTime(hour, minute);
        AppLog.line(app, "Navigation eta time TX: " + hour + ":" + (minute < 10 ? "0" : "") + minute);
        recordTx("eta time=" + hour + ":" + (minute < 10 ? "0" : "") + minute
                + " bytes=" + AdapterProtocol.hex(frame));
        AdapterGateway.get(app).send(AdapterCommand.loud("nav eta time", frame));
    }

    public void sendText(String text) {
        byte[] frame = AdapterProtocol.navText(text);
        AppLog.line(app, "Navigation text TX: " + text);
        recordTx("text=" + clean(text) + " bytes=" + AdapterProtocol.hex(frame));
        AdapterGateway.get(app).send(AdapterCommand.loud("nav text", frame));
    }

    public void sendSpeedLimit(int kmh) {
        byte[] frame = AdapterProtocol.speedLimit(kmh);
        recordTx("speedLimit=" + kmh + " bytes=" + AdapterProtocol.hex(frame));
        AdapterGateway.get(app).send(AdapterCommand.loud("nav speed", frame));
    }

    public void sendCompassStep(int uiStep) {
        byte[] frame = AdapterProtocol.compassStep(uiStep);
        AppLog.line(app, "Compass TX: step=" + uiStep);
        recordTx("compass step=" + uiStep + " bytes=" + AdapterProtocol.hex(frame));
        AdapterGateway.get(app).send(AdapterCommand.quiet("compass", frame));
    }

    public void sendDirectionToFinish(int uiStep, float distance, boolean km) {
        sendActive(true);
        byte[] frame = finishDirectionOutput.directionToFinish(uiStep, distance, km);
        AppLog.line(app, "Navigation finish direction TX: step=" + uiStep + " dist=" + distance
                + (km ? "km" : "m"));
        recordTx("finish direction step=" + uiStep + " dist=" + distance + (km ? "km" : "m")
                + " bytes=" + AdapterProtocol.hex(frame));
        AdapterGateway.get(app).send(AdapterCommand.loud("nav finish direction", frame));
    }

    private void recordTx(String line) {
        StateStore.appendNavigationTx(app, clean(line));
    }

    private static int normalizeProgress(int progressBucket) {
        if (progressBucket < 0) return 0;
        return Math.max(0, Math.min(9, progressBucket));
    }

    private static String clean(String value) {
        return value == null ? "" : value.trim();
    }
}
