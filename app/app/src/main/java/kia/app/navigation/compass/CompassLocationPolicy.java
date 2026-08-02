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
}
