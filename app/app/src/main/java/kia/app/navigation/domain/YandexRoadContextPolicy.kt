package kia.app.navigation.domain

/** Separates a fresh Yandex road-speed limit from the route active/off lifecycle. */
object YandexRoadContextPolicy {
    @JvmStatic
    fun shouldPreserve(
        storedLimit: String?,
        yandexSource: Boolean,
        lastFreshLimitAt: Long,
        now: Long,
        holdMs: Long,
        hardReset: Boolean,
    ): Boolean {
        return YandexRoadContextPolicy.shouldPreserve(
            storedLimit,
            yandexSource,
            lastFreshLimitAt,
            now,
            holdMs,
            hardReset,
            false,
        )
    }

    @JvmStatic
    fun shouldPreserve(
        storedLimit: String?,
        yandexSource: Boolean,
        lastFreshLimitAt: Long,
        now: Long,
        holdMs: Long,
        hardReset: Boolean,
        stationary: Boolean,
    ): Boolean {
        if (hardReset || !yandexSource) return false
        if (storedLimit == null || storedLimit.trim().isEmpty()) return false
        if (lastFreshLimitAt <= 0L || now < lastFreshLimitAt) return false
        return stationary || now - lastFreshLimitAt <= Math.max(0L, holdMs)
    }
}
