package kia.app.navigation.domain;

/**
 * Detects a new maneuver event when the provider reuses the same visual identity.
 *
 * <p>A small distance increase is ordinary GPS/provider jitter and must never refill the
 * cluster bar. A rollover is accepted only after a real countdown and a sufficiently large
 * forward jump from the closest accepted distance.</p>
 */
public final class ManeuverProgressRolloverPolicy {
    private static final float STRICT_PASS_METERS = 50f;
    private static final float SPARSE_PASS_METERS = 150f;

    private ManeuverProgressRolloverPolicy() {
    }

    public static boolean shouldStartNewEvent(float baseMeters,
                                              float closestMeters,
                                              float incomingMeters) {
        if (!finitePositive(baseMeters)
                || !finitePositive(closestMeters)
                || !finitePositive(incomingMeters)
                || incomingMeters <= closestMeters) {
            return false;
        }

        float forwardJump = incomingMeters - closestMeters;
        if (closestMeters <= STRICT_PASS_METERS) {
            return forwardJump >= Math.max(50f, closestMeters * 0.5f);
        }

        float requiredCountdown = Math.max(50f, baseMeters * 0.25f);
        boolean countdownSeen = baseMeters - closestMeters >= requiredCountdown;
        if (closestMeters <= SPARSE_PASS_METERS && countdownSeen) {
            return forwardJump >= Math.max(80f, closestMeters * 0.5f);
        }
        return false;
    }

    /**
     * Confirms that a second provider sample belongs to an already detected rollover candidate.
     *
     * <p>The first large jump is deliberately not enough: it can be a one-frame GPS/provider
     * bounce. The next sample must remain near the candidate after accounting for travelled
     * route distance and must still be clearly beyond the closest point of the old event.</p>
     */
    public static boolean confirmsPendingEvent(float previousClosestMeters,
                                               float pendingMeters,
                                               float incomingMeters,
                                               float travelledMeters) {
        if (!finitePositive(previousClosestMeters)
                || !finitePositive(pendingMeters)
                || !finitePositive(incomingMeters)
                || !Float.isFinite(travelledMeters)
                || travelledMeters < 0f
                || pendingMeters <= previousClosestMeters) {
            return false;
        }
        float projectedPending = Math.max(0f, pendingMeters - travelledMeters);
        float tolerance = Math.max(25f, Math.min(100f, pendingMeters * 0.25f));
        if (Math.abs(incomingMeters - projectedPending) > tolerance) return false;

        float separation = pendingMeters - previousClosestMeters;
        float retainedJump = Math.max(25f, separation * 0.35f);
        return incomingMeters >= previousClosestMeters + retainedJump;
    }

    private static boolean finitePositive(float value) {
        return Float.isFinite(value) && value > 0f;
    }
}
