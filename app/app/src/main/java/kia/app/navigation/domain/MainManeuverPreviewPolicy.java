package kia.app.navigation.domain;

/**
 * Selects only the presentation of a main maneuver.
 *
 * <p>The caller remains responsible for keeping the actual main maneuver and its distance in
 * state. This policy never decides whether a micro maneuver should win; an active micro makes
 * the policy step aside.</p>
 */
final class MainManeuverPreviewPolicy {
    enum Decision {
        ACTUAL,
        STRAIGHT,
        HOLD
    }

    private MainManeuverPreviewPolicy() {
    }

    /**
     * @return {@link Decision#HOLD} only when preview is otherwise applicable but no reliable
     * main distance exists; callers must keep the current wire visual and must not send zero.
     */
    static Decision decide(boolean enabled,
                           boolean normalMode,
                           boolean activeMicro,
                           boolean usableMain,
                           boolean actualMainStraight,
                           float distanceMeters,
                           int revealDistanceMeters,
                           boolean revealLatched) {
        if (!enabled || !normalMode || activeMicro || !usableMain || actualMainStraight) {
            return Decision.ACTUAL;
        }
        if (!Float.isFinite(distanceMeters) || distanceMeters <= 0f) {
            return Decision.HOLD;
        }
        if (revealLatched || distanceMeters <= revealDistanceMeters) {
            return Decision.ACTUAL;
        }
        return Decision.STRAIGHT;
    }

    static boolean grayRoadSupportsStraight(int grayRoadMask) {
        return (grayRoadMask & 1) != 0;
    }
}
