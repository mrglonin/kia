package kia.app.navigation.domain;

/**
 * One-shot downstream marker for a rollover already confirmed by the capture layer.
 *
 * <p>The marker survives handler branches that do not render a main maneuver yet. It is consumed
 * only by a valid observation of the same route scope and canonical maneuver family.</p>
 */
final class ManeuverProgressRolloverMarker {
    private final long ttlMs;
    private boolean pending;
    private String routeId = "";
    private String family = "";
    private long armedAt;

    ManeuverProgressRolloverMarker(long ttlMs) {
        this.ttlMs = Math.max(0L, ttlMs);
    }

    void arm(String incomingRouteId, String incomingFamily, long now) {
        String normalizedFamily = ManeuverProgressTracker.normalizedFamily(incomingFamily);
        if (normalizedFamily.isEmpty()) return;
        pending = true;
        routeId = clean(incomingRouteId);
        family = normalizedFamily;
        armedAt = now;
    }

    boolean matches(String incomingRouteId, String incomingFamily, float meters, long now) {
        if (!pending) return false;
        long age = now - armedAt;
        if (age < 0L || age > ttlMs) {
            clear();
            return false;
        }
        if (!Float.isFinite(meters) || meters <= 1f) return false;

        String scope = clean(incomingRouteId);
        String normalizedFamily = ManeuverProgressTracker.normalizedFamily(incomingFamily);
        boolean scopeMatches = routeId.isEmpty() || scope.isEmpty() || routeId.equals(scope);
        boolean familyMatches = family.isEmpty() || family.equals(normalizedFamily);
        if (!scopeMatches || !familyMatches) {
            clear();
            return false;
        }
        return true;
    }

    boolean pending() {
        return pending;
    }

    void clear() {
        pending = false;
        routeId = "";
        family = "";
        armedAt = 0L;
    }

    private static String clean(String value) {
        return value == null ? "" : value.trim();
    }
}
