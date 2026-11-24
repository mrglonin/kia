package kia.app.navigation.domain

/**
 * Selects only the presentation of a main maneuver.
 *
 * The caller remains responsible for keeping the actual main maneuver and its distance in state.
 * This policy never decides whether a micro maneuver should win; an active micro makes the policy
 * step aside.
 */
internal object MainManeuverPreviewPolicy {
    @JvmStatic
    /**
     * @return [Decision.HOLD] only when preview is otherwise applicable but no reliable main
     *   distance exists; callers must keep the current wire visual and must not send zero.
     */
    fun decide(
        enabled: Boolean,
        normalMode: Boolean,
        activeMicro: Boolean,
        usableMain: Boolean,
        actualMainStraight: Boolean,
        distanceMeters: Float,
        revealDistanceMeters: Int,
        revealLatched: Boolean,
    ): MainManeuverPreviewPolicy.Decision {
        if (!enabled || !normalMode || activeMicro || !usableMain || actualMainStraight) {
            return kia.app.navigation.domain.MainManeuverPreviewPolicy.Decision.ACTUAL
        }
        if (!java.lang.Float.isFinite(distanceMeters) || distanceMeters <= 0f) {
            return kia.app.navigation.domain.MainManeuverPreviewPolicy.Decision.HOLD
        }
        if (revealLatched || distanceMeters <= revealDistanceMeters) {
            return kia.app.navigation.domain.MainManeuverPreviewPolicy.Decision.ACTUAL
        }
        return kia.app.navigation.domain.MainManeuverPreviewPolicy.Decision.STRAIGHT
    }

    @JvmStatic
    fun grayRoadSupportsStraight(grayRoadMask: Int): Boolean {
        return (grayRoadMask and 1) != 0
    }

    internal enum class Decision {
        ACTUAL,
        STRAIGHT,
        HOLD,
    }
}
