package kia.app.navigation.domain;

import java.util.Locale;

import kia.app.navigation.cluster.NavigationClusterSender;

final class NavigationClusterTxController {
    interface VisualSink {
        void onClusterVisual(String visual);
    }

    private final NavigationClusterSender sender;
    private final VisualSink visualSink;
    private NavigationTxKey lastManeuverKey;
    private String lastCustomKey = "";
    private String lastCustomVisual = "";

    NavigationClusterTxController(NavigationClusterSender sender, VisualSink visualSink) {
        this.sender = sender;
        this.visualSink = visualSink;
    }

    boolean markCustomManeuverVisual(String key, String visual, boolean force) {
        String cleanKey = clean(key);
        String cleanVisual = clean(visual);
        if (!force && cleanKey.equals(lastCustomKey) && cleanVisual.equals(lastCustomVisual)) {
            return false;
        }
        lastCustomKey = cleanKey;
        lastCustomVisual = cleanVisual;
        lastManeuverKey = null;
        visualSink.onClusterVisual(cleanVisual);
        return true;
    }

    void sendManeuver(String imageId, float distance, boolean km, int progressBucket, boolean force) {
        NavigationTxKey key = maneuverKey(imageId, "", distance, km, progressBucket);
        if (!force && key.equals(lastManeuverKey)) return;
        send(key);
    }

    void sendManeuverWithGrayRoad(String imageId, String grayRoadId, float distance,
                                  boolean km, int progressBucket, boolean force) {
        NavigationTxKey key = maneuverKey(imageId, grayRoadId, distance, km, progressBucket);
        if (!force && key.equals(lastManeuverKey)) return;
        send(key);
    }

    boolean resendLastManeuver() {
        if (lastManeuverKey == null) return false;
        send(lastManeuverKey);
        return true;
    }

    void clear() {
        lastManeuverKey = null;
        lastCustomKey = "";
        lastCustomVisual = "";
    }

    private void send(NavigationTxKey key) {
        if (key.grayRoad.isEmpty()) {
            visualSink.onClusterVisual(clusterVisualText(
                    key.maneuver, key.progress, key.distance, key.km));
            sender.sendManeuver(key.maneuver, key.distance, key.km, key.progress);
        } else {
            visualSink.onClusterVisual(key.maneuver + " + " + key.grayRoad + " / "
                    + clusterDistanceText(key.distance, key.km)
                    + " / progress=" + key.progress);
            sender.sendManeuverWithGrayRoad(key.maneuver, key.grayRoad,
                    key.distance, key.km, key.progress);
        }
        lastManeuverKey = key;
        lastCustomKey = "";
        lastCustomVisual = "";
    }

    static String clusterVisualText(String imageId, int progressBucket, float distance, boolean km) {
        return clean(imageId) + " / " + clusterDistanceText(distance, km)
                + " / progress=" + normalizeProgressBucket(progressBucket);
    }

    static int normalizeProgressBucket(int progressBucket) {
        return progressBucket < 0 ? 0 : Math.max(0, Math.min(9, progressBucket));
    }

    static String clusterDistanceText(float distance, boolean km) {
        if (distance <= 0f) return "0 м";
        if (km) return trimDistance(distance) + " км";
        return trimDistance(roundMetersForDisplay(distance)) + " м";
    }

    static float clusterDistanceValue(float distance, boolean km) {
        if (distance <= 0f) return 0f;
        return km ? distance : roundMetersForDisplay(distance);
    }

    static NavigationTxKey maneuverKey(String imageId, String grayRoadId, float distance,
                                       boolean km, int progressBucket) {
        return new NavigationTxKey(clean(imageId), clean(grayRoadId),
                clusterDistanceValue(distance, km), km,
                normalizeProgressBucket(progressBucket));
    }

    private static String trimDistance(float distance) {
        if (distance == Math.round(distance)) return String.valueOf(Math.round(distance));
        return String.format(Locale.US, "%.1f", distance);
    }

    private static float roundMetersForDisplay(float meters) {
        if (meters <= 0f) return 0f;
        return Math.max(10f, Math.round(meters / 10f) * 10f);
    }

    private static String clean(String value) {
        return value == null ? "" : value.trim();
    }
}
