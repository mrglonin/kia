package kia.app.navigation.domain

/**
 * Detects a new maneuver event when the provider reuses the same visual identity.
 *
 * A small distance increase is ordinary GPS/provider jitter and must never refill the cluster bar.
 * A rollover is accepted only after a real countdown and a sufficiently large forward jump from the
 * closest accepted distance.
 */
object ManeuverProgressRolloverPolicy {
    private const val STRICT_PASS_METERS: Float = 50f
    private const val SPARSE_PASS_METERS: Float = 150f

    @JvmStatic
    fun shouldStartNewEvent(
        baseMeters: Float,
        closestMeters: Float,
        incomingMeters: Float,
    ): Boolean {
        if (
            !ManeuverProgressRolloverPolicy.finitePositive(baseMeters) ||
                !ManeuverProgressRolloverPolicy.finitePositive(closestMeters) ||
                !ManeuverProgressRolloverPolicy.finitePositive(incomingMeters) ||
                incomingMeters <= closestMeters
        ) {
            return false
        }

        val forwardJump: Float = incomingMeters - closestMeters
        if (closestMeters <= ManeuverProgressRolloverPolicy.STRICT_PASS_METERS) {
            return forwardJump >= Math.max(50f, closestMeters * 0.5f)
        }

        val requiredCountdown: Float = Math.max(50f, baseMeters * 0.25f)
        val countdownSeen: Boolean = baseMeters - closestMeters >= requiredCountdown
        if (closestMeters <= ManeuverProgressRolloverPolicy.SPARSE_PASS_METERS && countdownSeen) {
            return forwardJump >= Math.max(80f, closestMeters * 0.5f)
        }
        return false
    }

    @JvmStatic
    /**
     * Confirms that a second provider sample belongs to an already detected rollover candidate.
     *
     * The first large jump is deliberately not enough: it can be a one-frame GPS/provider bounce.
     * The next sample must remain near the candidate after accounting for travelled route distance
     * and must still be clearly beyond the closest point of the old event.
     */
    fun confirmsPendingEvent(
        previousClosestMeters: Float,
        pendingMeters: Float,
        incomingMeters: Float,
        travelledMeters: Float,
    ): Boolean {
        if (
            !ManeuverProgressRolloverPolicy.finitePositive(previousClosestMeters) ||
                !ManeuverProgressRolloverPolicy.finitePositive(pendingMeters) ||
                !ManeuverProgressRolloverPolicy.finitePositive(incomingMeters) ||
                !java.lang.Float.isFinite(travelledMeters) ||
                travelledMeters < 0f ||
                pendingMeters <= previousClosestMeters
        ) {
            return false
        }
        val projectedPending: Float = Math.max(0f, pendingMeters - travelledMeters)
        val tolerance: Float = Math.max(25f, Math.min(100f, pendingMeters * 0.25f))
        if (Math.abs(incomingMeters - projectedPending) > tolerance) return false

        val separation: Float = pendingMeters - previousClosestMeters
        val retainedJump: Float = Math.max(25f, separation * 0.35f)
        return incomingMeters >= previousClosestMeters + retainedJump
    }

    private fun finitePositive(value: Float): Boolean {
        return java.lang.Float.isFinite(value) && value > 0f
    }
}
