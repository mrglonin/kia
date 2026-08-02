package kia.app.navigation.domain;

/** Pure semantic policy for cheap, repeated Yandex free-drive speed snapshots. */
public final class YandexSpeedSnapshotPolicy {
    private YandexSpeedSnapshotPolicy() {
    }

    public static boolean meaningfulPayload(int currentSpeedKmh, boolean roadLimitPresent,
                                            String cameraLimit) {
        return currentSpeedKmh >= 0
                || roadLimitPresent
                || (cameraLimit != null && !cameraLimit.trim().isEmpty());
    }

    public static boolean matchesStoredState(boolean yandexSource,
                                             int storedCurrentSpeedKmh,
                                             int incomingCurrentSpeedKmh,
                                             int storedRoadLimitKmh,
                                             boolean incomingRoadLimitPresent,
                                             int incomingRoadLimitKmh,
                                             boolean storedExceeded,
                                             boolean incomingExceededPresent,
                                             boolean incomingExceeded) {
        if (!yandexSource) return false;
        if (incomingCurrentSpeedKmh >= 0
                && storedCurrentSpeedKmh != incomingCurrentSpeedKmh) {
            return false;
        }
        if (incomingRoadLimitPresent) {
            int expectedLimit = Math.max(0, incomingRoadLimitKmh);
            if (Math.max(0, storedRoadLimitKmh) != expectedLimit) return false;
        }
        return !incomingExceededPresent || storedExceeded == incomingExceeded;
    }
}
