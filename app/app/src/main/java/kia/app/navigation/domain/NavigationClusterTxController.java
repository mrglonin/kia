package kia.app.navigation.domain;

import java.util.Locale;

import kia.app.navigation.cluster.NavigationClusterSender;

final class NavigationClusterTxController {
    interface VisualSink {
        void onClusterVisual(String visual);
    }

    private final NavigationClusterSender sender;
    private final VisualSink visualSink;

    NavigationClusterTxController(NavigationClusterSender sender, VisualSink visualSink) {
        this.sender = sender;
        this.visualSink = visualSink;
    }

    boolean markCustomManeuverVisual(String key, String visual, boolean force) {
        visualSink.onClusterVisual(clean(visual));
        return true;
    }

    void sendManeuver(String imageId, float distance, boolean km, int progressBucket, boolean force) {
        String cleanImageId = clean(imageId);
        int progress = normalizeProgressBucket(progressBucket);
        float sendDistance = clusterDistanceValue(distance, km);
        visualSink.onClusterVisual(clusterVisualText(cleanImageId, progress, sendDistance, km));
        sender.sendManeuver(imageId, sendDistance, km, progress);
    }

    void sendManeuverWithGrayRoad(String imageId, String grayRoadId, float distance,
                                  boolean km, int progressBucket, boolean force) {
        String cleanImageId = clean(imageId);
        String cleanGrayRoad = clean(grayRoadId);
        int progress = normalizeProgressBucket(progressBucket);
        float sendDistance = clusterDistanceValue(distance, km);
        visualSink.onClusterVisual(cleanImageId + " + " + cleanGrayRoad + " / "
                + clusterDistanceText(sendDistance, km) + " / progress=" + progress);
        sender.sendManeuverWithGrayRoad(imageId, grayRoadId, sendDistance, km, progress);
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

    private static String trimDistance(float distance) {
        if (distance == Math.round(distance)) return String.valueOf(Math.round(distance));
        return String.format(Locale.US, "%.1f", distance);
    }

    private static float roundMetersForDisplay(float meters) {
        if (meters <= 0f) return 0f;
        if (meters < 10f) return Math.round(meters);
        if (meters < 100f) return Math.round(meters / 5f) * 5f;
        return Math.round(meters / 10f) * 10f;
    }

    private static String clean(String value) {
        return value == null ? "" : value.trim();
    }
}
