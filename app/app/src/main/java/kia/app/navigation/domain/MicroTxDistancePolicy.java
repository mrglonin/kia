package kia.app.navigation.domain;

/**
 * Selects the distance sent with a micro-maneuver visual.
 *
 * <p>The micro maneuver owns only the icon/topology. Its own distance is
 * intentionally not accepted by this policy: the cluster counter must keep
 * showing the distance to the main maneuver. A fresh main distance wins over a
 * previously held main distance, and only strictly positive values are usable.</p>
 */
final class MicroTxDistancePolicy {
    static final class MainSnapshot {
        final String maneuver;
        final String distance;
        final boolean preferred;

        MainSnapshot(String maneuver, String distance, boolean preferred) {
            this.maneuver = clean(maneuver);
            this.distance = clean(distance);
            this.preferred = preferred;
        }

        boolean available() {
            return !maneuver.isEmpty() && !distance.isEmpty();
        }
    }

    private MicroTxDistancePolicy() {
    }

    static String selectMainDistance(String preferredMainDistance, String heldMainDistance) {
        String preferred = clean(preferredMainDistance);
        if (isPositive(preferred)) {
            return preferred;
        }

        String held = clean(heldMainDistance);
        return isPositive(held) ? held : "";
    }

    /**
     * Chooses maneuver identity and distance atomically so a fresh identity can never
     * be paired with the previous maneuver's held counter.
     */
    static MainSnapshot selectMainSnapshot(String preferredMainManeuver,
                                           String preferredMainDistance,
                                           String heldMainManeuver,
                                           String heldMainDistance) {
        String preferredManeuver = clean(preferredMainManeuver);
        String preferredDistance = clean(preferredMainDistance);
        if (!preferredManeuver.isEmpty() && isPositive(preferredDistance)) {
            return new MainSnapshot(preferredManeuver, preferredDistance, true);
        }

        String heldManeuver = clean(heldMainManeuver);
        String heldDistance = clean(heldMainDistance);
        if (!heldManeuver.isEmpty() && isPositive(heldDistance)) {
            return new MainSnapshot(heldManeuver, heldDistance, false);
        }
        return new MainSnapshot("", "", false);
    }

    private static boolean isPositive(String distance) {
        return ManeuverArbiter.distanceMeters(distance) > 0f;
    }

    private static String clean(String value) {
        return value == null ? "" : value.trim();
    }
}
