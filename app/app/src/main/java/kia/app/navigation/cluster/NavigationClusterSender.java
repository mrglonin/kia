package kia.app.navigation.cluster;

import android.content.Context;
import android.os.SystemClock;

import kia.app.protocol.adapter.AdapterCommand;
import kia.app.protocol.adapter.AdapterGateway;
import kia.app.protocol.adapter.AdapterProtocol;
import kia.app.protocol.adapter.AdapterTxOutcome;
import kia.app.core.AppLog;
import kia.app.core.StateStore;
import kia.app.navigation.domain.NavigationModeSettings;

public final class NavigationClusterSender {
    private static final long FINISH_DIRECTION_DUPLICATE_TX_MS = 5000L;

    private final Context app;
    private final NormalNavigationOutput normalOutput = new NormalNavigationOutput();
    private final TbtNavigationOutput tbtOutput = new TbtNavigationOutput();
    private final FinishDirectionNavigationOutput finishDirectionOutput = new FinishDirectionNavigationOutput();
    private String lastFinishDirectionVisualKey = "";
    private long lastFinishDirectionFrameAt;
    private String lastRouteManeuverId = "";
    private Boolean lastNavigationActive;

    public NavigationClusterSender(Context context) {
        this.app = context.getApplicationContext();
    }

    public AdapterTxOutcome sendActive(boolean active) {
        if (lastNavigationActive == null || lastNavigationActive != active) {
            resetNavigationRoute();
        }
        lastNavigationActive = active;
        byte[] frame = AdapterProtocol.navOn(active);
        return AdapterGateway.get(app).send(AdapterCommand.loud(
                active ? "nav active on" : "nav active off", frame));
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
        resetFinishDirectionOnManeuverChange(imageId);
        AdapterTxOutcome outcome = AdapterGateway.get(app).send(
                AdapterCommand.loud("nav maneuver", frame));
        String summary = "maneuver " + imageId + " dist=" + distance
                + (km ? "km" : "m") + " progress=" + logicalProgress
                + " txProgress=" + txProgress
                + " mode=" + NavigationModeSettings.label(app)
                + " bytes=" + AdapterProtocol.hex(frame);
        recordTx(summary, outcome);
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
        resetFinishDirectionOnManeuverChange(imageId);
        AdapterTxOutcome outcome = AdapterGateway.get(app).send(
                AdapterCommand.loud("nav maneuver gray", frame));
        String summary = "maneuver+gray " + imageId + " gray=" + grayRoadId
                + " dist=" + distance + (km ? "km" : "m") + " progress=" + logicalProgress
                + " txProgress=" + txProgress
                + " mode=" + NavigationModeSettings.label(app)
                + " bytes=" + AdapterProtocol.hex(frame);
        recordTx(summary, outcome);
    }

    public void sendEta(float distance, boolean km) {
        byte[] frame = AdapterProtocol.etaDistance(distance, km);
        AdapterTxOutcome outcome = AdapterGateway.get(app).send(
                AdapterCommand.loud("nav eta", frame));
        recordTx("eta distance=" + distance + (km ? "km" : "m")
                + " bytes=" + AdapterProtocol.hex(frame), outcome);
    }

    public void sendEtaTime(int hour, int minute) {
        byte[] frame = AdapterProtocol.etaTime(hour, minute);
        AdapterTxOutcome outcome = AdapterGateway.get(app).send(
                AdapterCommand.loud("nav eta time", frame));
        recordTx("eta time=" + hour + ":" + (minute < 10 ? "0" : "") + minute
                + " bytes=" + AdapterProtocol.hex(frame), outcome);
    }

    public void sendText(String text) {
        byte[] frame = AdapterProtocol.navText(text);
        AdapterTxOutcome outcome = AdapterGateway.get(app).send(
                AdapterCommand.loud("nav text", frame));
        recordTx("text=" + clean(text) + " bytes=" + AdapterProtocol.hex(frame), outcome);
    }

    public AdapterTxOutcome sendSpeedLimit(int kmh) {
        byte[] frame = AdapterProtocol.speedLimit(kmh);
        AdapterTxOutcome outcome = AdapterGateway.get(app).send(
                AdapterCommand.loud("nav speed", frame));
        recordTx("speedLimit=" + kmh + " bytes=" + AdapterProtocol.hex(frame), outcome);
        return outcome;
    }

    public AdapterTxOutcome sendCompassStep(int uiStep) {
        byte[] frame = AdapterProtocol.compassStep(uiStep);
        AdapterTxOutcome outcome = AdapterGateway.get(app).send(
                AdapterCommand.quiet("nav compass", frame));
        recordTx("compass step=" + uiStep + " bytes=" + AdapterProtocol.hex(frame), outcome);
        return outcome;
    }

    public void sendDirectionToFinish(int uiStep, float distance, boolean km) {
        byte[] frame = finishDirectionOutput.directionToFinish(uiStep, distance, km);
        String hex = AdapterProtocol.hex(frame);
        String visualKey = finishDirectionVisualKey(frame);
        long now = SystemClock.elapsedRealtime();
        if (visualKey.equals(lastFinishDirectionVisualKey)
                && now - lastFinishDirectionFrameAt < FINISH_DIRECTION_DUPLICATE_TX_MS) {
            return;
        }
        sendActive(true);
        lastFinishDirectionVisualKey = visualKey;
        lastFinishDirectionFrameAt = now;
        AdapterTxOutcome outcome = AdapterGateway.get(app).send(
                AdapterCommand.loud("nav finish direction", frame));
        recordTx("finish direction step=" + uiStep + " dist=" + distance + (km ? "km" : "m")
                + " bytes=" + hex, outcome);
    }

    public void resetNavigationRoute() {
        lastFinishDirectionVisualKey = "";
        lastFinishDirectionFrameAt = 0L;
        lastRouteManeuverId = "";
    }

    private void resetFinishDirectionOnManeuverChange(String imageId) {
        String cleanId = clean(imageId);
        if (!lastRouteManeuverId.isEmpty() && !lastRouteManeuverId.equals(cleanId)) {
            lastFinishDirectionVisualKey = "";
            lastFinishDirectionFrameAt = 0L;
        }
        lastRouteManeuverId = cleanId;
    }

    static String finishDirectionVisualKey(byte[] frame) {
        if (frame == null || frame.length < 13) return AdapterProtocol.hex(frame);
        return (frame[5] & 0xff) + "|"
                + (frame[6] & 0xff) + "|"
                + (frame[7] & 0xff) + "|"
                + (frame[8] & 0xff) + "|"
                + (frame[9] & 0xff) + "|"
                + (frame[10] & 0xff) + "|"
                + (frame[11] & 0xff) + "|"
                + (frame[12] & 0xff);
    }

    private void recordTx(String line, AdapterTxOutcome outcome) {
        String recorded = clean(line) + " outcome=" + outcome;
        AppLog.navigation(app, "Navigation TX " + outcome + ": " + clean(line));
        StateStore.appendNavigationTx(app, recorded);
    }

    private static int normalizeProgress(int progressBucket) {
        if (progressBucket < 0) return 0;
        return Math.max(0, Math.min(9, progressBucket));
    }

    private static String clean(String value) {
        return value == null ? "" : value.trim();
    }
}
