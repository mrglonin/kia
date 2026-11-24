package kia.app.navigation.capture

/** Pure coalescing rules for ordered full-snapshot micro states. */
internal object YandexSnapshotCoalescingPolicy {
    private const val MANEUVER_FORWARD_TRANSITION_METERS: Long = 50L

    @JvmStatic
    fun isTransition(previousState: String?, incomingState: String?): Boolean {
        return !YandexSnapshotCoalescingPolicy.clean(previousState)
            .equals(YandexSnapshotCoalescingPolicy.clean(incomingState))
    }

    @JvmStatic
    fun canReplaceTail(
        tailLifecyclePriority: Boolean,
        tailState: String?,
        incomingState: String?,
    ): Boolean {
        return !tailLifecyclePriority &&
            YandexSnapshotCoalescingPolicy.clean(tailState)
                .equals(YandexSnapshotCoalescingPolicy.clean(incomingState))
    }

    @JvmStatic
    fun isForwardManeuverDistanceTransition(previousMeters: Long, incomingMeters: Long): Boolean {
        return previousMeters > 1L &&
            incomingMeters > previousMeters &&
            incomingMeters - previousMeters >=
                YandexSnapshotCoalescingPolicy.MANEUVER_FORWARD_TRANSITION_METERS
    }

    @JvmStatic
    /**
     * Selects an existing packet to evict from a full queue. A newer lifecycle packet is never
     * dropped; a semantic transition may displace only an ordinary packet, and a distance-only
     * sample never displaces history.
     */
    fun evictionIndex(
        incomingLifecyclePriority: Boolean,
        incomingSemanticTransition: Boolean,
        vararg queuedLifecyclePriority: Boolean,
    ): Int {
        if (queuedLifecyclePriority == null || queuedLifecyclePriority.size == 0) return -1
        if (!incomingLifecyclePriority && !incomingSemanticTransition) return -1
        for (i in queuedLifecyclePriority.indices) {
            if (!queuedLifecyclePriority.get(i)) return i
        }
        return if (incomingLifecyclePriority) 0 else -1
    }

    private fun clean(value: String?): String {
        return if (value == null) "" else value.trim()
    }
}
