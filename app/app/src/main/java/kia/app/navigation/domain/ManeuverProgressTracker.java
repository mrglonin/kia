package kia.app.navigation.domain;

/**
 * Stateful 0..9 countdown for the main navigation maneuver.
 *
 * <p>The first valid distance of every semantic event is its own baseline, so a maneuver
 * first observed at 180 or 300 metres still starts with a full bar. Micro/gray-road visuals
 * must only read the resulting main progress and must not create a separate tracker event.</p>
 */
final class ManeuverProgressTracker {
    static final int EMPTY_BUCKET = 0;
    static final int FULL_BUCKET = 9;
    private static final float MIN_VALID_DISTANCE_METERS = 1f;
    private static final float EMPTY_DISTANCE_METERS = 50f;

    static final class Result {
        final boolean hasValue;
        final boolean hold;
        final boolean newEvent;
        final int bucket;
        final String reason;

        Result(boolean hasValue, boolean hold, boolean newEvent, int bucket, String reason) {
            this.hasValue = hasValue;
            this.hold = hold;
            this.newEvent = newEvent;
            this.bucket = clamp(bucket);
            this.reason = clean(reason);
        }
    }

    private boolean active;
    private String scopeId = "";
    private String maneuverFamily = "";
    private float baseMeters;
    private float closestMeters;
    private int lastBucket;

    Result observeMain(String incomingScopeId, String incomingFamily, float meters) {
        return observeMain(incomingScopeId, incomingFamily, meters, false, true);
    }

    Result observeMain(String incomingScopeId, String incomingFamily, float meters,
                       boolean explicitRollover) {
        return observeMain(incomingScopeId, incomingFamily, meters,
                explicitRollover, true);
    }

    Result observeMain(String incomingScopeId, String incomingFamily, float meters,
                       boolean explicitRollover, boolean inferredRolloverAllowed) {
        String scope = clean(incomingScopeId);
        String family = normalizedFamily(incomingFamily);
        if (!usableDistance(meters)) {
            return active
                    ? new Result(true, true, false, lastBucket, "invalid_distance_hold")
                    : new Result(false, true, false, EMPTY_BUCKET, "no_distance");
        }

        boolean scopeChanged = active
                && !scopeId.isEmpty()
                && !scope.isEmpty()
                && !scopeId.equals(scope);
        boolean familyChanged = active
                && !maneuverFamily.isEmpty()
                && !family.isEmpty()
                && !maneuverFamily.equals(family);
        if (!active || scopeChanged || familyChanged || explicitRollover) {
            return startEvent(scope, family, meters,
                    !active ? "initial"
                            : scopeChanged ? "scope_changed"
                            : familyChanged ? "family_changed"
                            : "explicit_rollover");
        }

        if (ManeuverProgressRolloverPolicy.shouldStartNewEvent(
                baseMeters, closestMeters, meters)) {
            if (inferredRolloverAllowed) {
                return startEvent(scope, family, meters, "distance_rollover");
            }
            return new Result(true, true, false, lastBucket,
                    "upstream_rollover_confirmation_required");
        }

        if (scopeId.isEmpty() && !scope.isEmpty()) scopeId = scope;
        if (!family.isEmpty()) maneuverFamily = family;
        closestMeters = Math.min(closestMeters, meters);
        int candidate = bucketFor(baseMeters, closestMeters);
        lastBucket = Math.min(lastBucket, candidate);
        return new Result(true, false, false, lastBucket, "countdown");
    }

    Result current(String incomingScopeId, String incomingFamily) {
        if (!active) {
            return new Result(false, true, false, EMPTY_BUCKET, "empty");
        }
        String scope = clean(incomingScopeId);
        if (!scopeId.isEmpty() && !scope.isEmpty() && !scopeId.equals(scope)) {
            return new Result(false, true, false, EMPTY_BUCKET, "scope_mismatch");
        }
        String family = normalizedFamily(incomingFamily);
        if (!maneuverFamily.isEmpty() && !family.isEmpty()
                && !maneuverFamily.equals(family)) {
            return new Result(false, true, false, EMPTY_BUCKET, "family_mismatch");
        }
        return new Result(true, true, false, lastBucket, "current");
    }

    void reset() {
        active = false;
        scopeId = "";
        maneuverFamily = "";
        baseMeters = 0f;
        closestMeters = 0f;
        lastBucket = EMPTY_BUCKET;
    }

    private Result startEvent(String scope, String family, float meters, String reason) {
        active = true;
        scopeId = scope;
        maneuverFamily = family;
        baseMeters = meters;
        closestMeters = meters;
        lastBucket = FULL_BUCKET;
        return new Result(true, false, true, lastBucket, reason);
    }

    private static int bucketFor(float baseMeters, float remainingMeters) {
        if (!usableDistance(baseMeters) || !usableDistance(remainingMeters)) {
            return EMPTY_BUCKET;
        }
        if (baseMeters > EMPTY_DISTANCE_METERS
                && remainingMeters <= EMPTY_DISTANCE_METERS) {
            return EMPTY_BUCKET;
        }
        float ratio = Math.max(0f, Math.min(1f, remainingMeters / baseMeters));
        return clamp(Math.round(ratio * FULL_BUCKET));
    }

    private static int clamp(int value) {
        return Math.max(EMPTY_BUCKET, Math.min(FULL_BUCKET, value));
    }

    private static boolean usableDistance(float value) {
        return Float.isFinite(value) && value > MIN_VALID_DISTANCE_METERS;
    }

    private static String clean(String value) {
        return value == null ? "" : value.trim();
    }

    static String normalizedFamily(String value) {
        String family = clean(value).toLowerCase(java.util.Locale.US);
        if (family.endsWith("_left") && !"uturn_left".equals(family)) return "left";
        if (family.endsWith("_right") && !"uturn_right".equals(family)) return "right";
        return family;
    }

}
