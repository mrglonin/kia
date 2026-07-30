package kia.app.navigation.capture;

/** Pure coalescing rules for ordered full-snapshot micro states. */
final class YandexSnapshotCoalescingPolicy {
    private static final long MANEUVER_FORWARD_TRANSITION_METERS = 50L;

    private YandexSnapshotCoalescingPolicy() {
    }

    static boolean isTransition(String previousState, String incomingState) {
        return !clean(previousState).equals(clean(incomingState));
    }

    static boolean canReplaceTail(boolean tailLifecyclePriority,
                                  String tailState, String incomingState) {
        return !tailLifecyclePriority
                && clean(tailState).equals(clean(incomingState));
    }

    static boolean isForwardManeuverDistanceTransition(long previousMeters,
                                                       long incomingMeters) {
        return previousMeters > 1L
                && incomingMeters > previousMeters
                && incomingMeters - previousMeters >= MANEUVER_FORWARD_TRANSITION_METERS;
    }

    /**
     * Selects an existing packet to evict from a full queue. A newer lifecycle
     * packet is never dropped; a semantic transition may displace only an
     * ordinary packet, and a distance-only sample never displaces history.
     */
    static int evictionIndex(boolean incomingLifecyclePriority,
                             boolean incomingSemanticTransition,
                             boolean... queuedLifecyclePriority) {
        if (queuedLifecyclePriority == null || queuedLifecyclePriority.length == 0) return -1;
        if (!incomingLifecyclePriority && !incomingSemanticTransition) return -1;
        for (int i = 0; i < queuedLifecyclePriority.length; i++) {
            if (!queuedLifecyclePriority[i]) return i;
        }
        return incomingLifecyclePriority ? 0 : -1;
    }

    private static String clean(String value) {
        return value == null ? "" : value.trim();
    }
}
