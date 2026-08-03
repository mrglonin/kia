package kia.app.navigation.compass;

/** Pure source/freshness policy for normal compass heading updates. */
final class CompassLocationPolicy {
    static final float MIN_GPS_BEARING_SPEED_MPS = 1.4f;

    private CompassLocationPolicy() {
    }

    static boolean usableGpsBearing(boolean hasBearing, float bearingDegrees,
                                    boolean hasSpeed, float speedMetersPerSecond) {
        if (!hasBearing || Float.isNaN(bearingDegrees) || Float.isInfinite(bearingDegrees)) {
            return false;
        }
        // GPS bearing is a course-over-ground value, not a stationary heading. Without a
        // trustworthy speed we cannot prove that the vehicle is moving, so keeping the last
        // confirmed compass step is safer than accepting the common idle bearing of 0 degrees.
        if (!hasSpeed) return false;
        return !Float.isNaN(speedMetersPerSecond)
                && !Float.isInfinite(speedMetersPerSecond)
                && speedMetersPerSecond >= MIN_GPS_BEARING_SPEED_MPS;
    }

    static boolean freshLastKnown(long nowElapsedNanos, long fixElapsedNanos, long maxAgeMs) {
        if (maxAgeMs < 0L || nowElapsedNanos <= 0L || fixElapsedNanos <= 0L
                || fixElapsedNanos > nowElapsedNanos) {
            return false;
        }
        long maxAgeNanos = maxAgeMs > Long.MAX_VALUE / 1_000_000L
                ? Long.MAX_VALUE : maxAgeMs * 1_000_000L;
        return nowElapsedNanos - fixElapsedNanos <= maxAgeNanos;
    }

    static boolean sameLastKnownSample(long fixElapsedNanos, long previousElapsedNanos) {
        return fixElapsedNanos > 0L && fixElapsedNanos == previousElapsedNanos;
    }

    static int effectiveSensorAccuracy(int callbackAccuracy, int eventAccuracy) {
        // Some sensor stacks omit the initial onAccuracyChanged callback but put a valid
        // accuracy directly on SensorEvent. Conversely, a few stacks leave event accuracy at
        // zero after a reliable callback, so do not downgrade on that missing value alone.
        if (eventAccuracy > 0 || callbackAccuracy <= 0) return eventAccuracy;
        return callbackAccuracy;
    }

    static boolean usableSensorHeading(float headingDegrees, int sensorAccuracy) {
        // SENSOR_STATUS_UNRELIABLE also means "accuracy unknown" on a number of vendor
        // rotation-vector implementations. A finite heading is still the only useful
        // stationary source; callers keep it low-confidence instead of treating it as a
        // reason to permanently suppress the compass.
        return !Float.isNaN(headingDegrees) && !Float.isInfinite(headingDegrees);
    }

    static boolean sensorStreamNeedsRecovery(boolean registered,
                                             long nowElapsedMs,
                                             long registeredAtElapsedMs,
                                             long lastUsableEventElapsedMs,
                                             long staleAfterMs,
                                             long lastRecoveryElapsedMs,
                                             long recoveryCooldownMs) {
        if (!registered || nowElapsedMs <= 0L || registeredAtElapsedMs <= 0L
                || staleAfterMs < 0L || recoveryCooldownMs < 0L) {
            return false;
        }
        long freshnessAnchor = lastUsableEventElapsedMs > 0L
                ? lastUsableEventElapsedMs : registeredAtElapsedMs;
        if (freshnessAnchor > nowElapsedMs
                || nowElapsedMs - freshnessAnchor < staleAfterMs) {
            return false;
        }
        return lastRecoveryElapsedMs <= 0L
                || (lastRecoveryElapsedMs <= nowElapsedMs
                && nowElapsedMs - lastRecoveryElapsedMs >= recoveryCooldownMs);
    }

    static boolean sensorMayDriveCluster(int sensorAccuracy,
                                         long nowElapsedMs,
                                         long lastGpsBearingElapsedMs,
                                         long gpsBearingPriorityMs) {
        if (sensorAccuracy > 0) return true;
        if (lastGpsBearingElapsedMs <= 0L || gpsBearingPriorityMs < 0L) return true;
        if (lastGpsBearingElapsedMs > nowElapsedMs) return true;
        return nowElapsedMs - lastGpsBearingElapsedMs > gpsBearingPriorityMs;
    }
}
