package kia.app.navigation.domain;

/** Tri-state motion decision for actions that must never trust a stale speed string. */
public final class NavigationMotionPolicy {
    public enum State {
        UNKNOWN,
        STATIONARY,
        MOVING
    }

    private NavigationMotionPolicy() {
    }

    public static State evaluate(int primarySpeedKmh, long primaryObservedAt,
                                 int gpsSpeedKmh, long gpsObservedAt,
                                 long nowElapsed, long primaryMaxAgeMs,
                                 long gpsMaxAgeMs, int movingThresholdKmh) {
        if (fresh(primaryObservedAt, nowElapsed, primaryMaxAgeMs)
                && primarySpeedKmh >= 0) {
            return primarySpeedKmh > movingThresholdKmh ? State.MOVING : State.STATIONARY;
        }
        if (fresh(gpsObservedAt, nowElapsed, gpsMaxAgeMs) && gpsSpeedKmh >= 0) {
            return gpsSpeedKmh > movingThresholdKmh ? State.MOVING : State.STATIONARY;
        }
        return State.UNKNOWN;
    }

    private static boolean fresh(long observedAt, long nowElapsed, long maxAgeMs) {
        return observedAt > 0L && nowElapsed >= observedAt
                && nowElapsed - observedAt <= Math.max(0L, maxAgeMs);
    }
}
