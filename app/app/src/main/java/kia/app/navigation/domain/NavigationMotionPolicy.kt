package kia.app.navigation.domain

/** Tri-state motion decision for actions that must never trust a stale speed string. */
object NavigationMotionPolicy {
    @JvmStatic
    fun evaluate(
        primarySpeedKmh: Int,
        primaryObservedAt: Long,
        gpsSpeedKmh: Int,
        gpsObservedAt: Long,
        nowElapsed: Long,
        primaryMaxAgeMs: Long,
        gpsMaxAgeMs: Long,
        movingThresholdKmh: Int,
    ): State {
        if (
            NavigationMotionPolicy.fresh(primaryObservedAt, nowElapsed, primaryMaxAgeMs) &&
                primarySpeedKmh >= 0
        ) {
            return if (primarySpeedKmh > movingThresholdKmh) State.MOVING else State.STATIONARY
        }
        if (
            NavigationMotionPolicy.fresh(gpsObservedAt, nowElapsed, gpsMaxAgeMs) && gpsSpeedKmh >= 0
        ) {
            return if (gpsSpeedKmh > movingThresholdKmh) State.MOVING else State.STATIONARY
        }
        return State.UNKNOWN
    }

    private fun fresh(observedAt: Long, nowElapsed: Long, maxAgeMs: Long): Boolean {
        return observedAt > 0L &&
            nowElapsed >= observedAt &&
            nowElapsed - observedAt <= Math.max(0L, maxAgeMs)
    }

    enum class State {
        UNKNOWN,
        STATIONARY,
        MOVING,
    }
}
