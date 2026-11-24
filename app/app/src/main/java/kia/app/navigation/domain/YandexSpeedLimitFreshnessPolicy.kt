package kia.app.navigation.domain

/** Keeps a Yandex road-speed sign from remaining on the cluster indefinitely. */
object YandexSpeedLimitFreshnessPolicy {
    @JvmStatic
    fun shouldClear(
        storedLimit: String?,
        lastFreshLimitAt: Long,
        now: Long,
        holdMs: Long,
    ): Boolean {
        return YandexSpeedLimitFreshnessPolicy.shouldClear(
            storedLimit,
            lastFreshLimitAt,
            now,
            holdMs,
            false,
        )
    }

    @JvmStatic
    fun shouldClear(
        storedLimit: String?,
        lastFreshLimitAt: Long,
        now: Long,
        holdMs: Long,
        stationary: Boolean,
    ): Boolean {
        if (storedLimit == null || storedLimit.trim().isEmpty()) return false
        if (lastFreshLimitAt <= 0L || now < lastFreshLimitAt) return true
        return now - lastFreshLimitAt > Math.max(0L, holdMs) && !stationary
    }
}
