package kia.app.navigation.domain

/**
 * Decides whether missing Yandex bridge packets are enough to end navigation.
 *
 * An explicit route-stop packet is handled elsewhere. The watchdog is only a last-resort recovery
 * mechanism, so a stored route and fresh local GPS are stronger evidence than a temporarily silent
 * background bridge.
 */
object YandexRouteWatchdogPolicy {
    @JvmStatic
    fun decide(
        packetAgeMs: Long,
        gpsAgeMs: Long,
        hasRouteSnapshot: Boolean,
        packetStaleMs: Long,
        gpsFreshMs: Long,
        maxSilentMs: Long,
    ): YandexRouteWatchdogPolicy.Decision {
        if (packetAgeMs < packetStaleMs)
            return kia.app.navigation.domain.YandexRouteWatchdogPolicy.Decision.WAIT
        if (!hasRouteSnapshot)
            return kia.app.navigation.domain.YandexRouteWatchdogPolicy.Decision.FORCE_INACTIVE
        if (gpsAgeMs >= 0L && gpsAgeMs <= gpsFreshMs)
            return kia.app.navigation.domain.YandexRouteWatchdogPolicy.Decision.HOLD_ACTIVE
        if (packetAgeMs >= maxSilentMs)
            return kia.app.navigation.domain.YandexRouteWatchdogPolicy.Decision.FORCE_INACTIVE
        return kia.app.navigation.domain.YandexRouteWatchdogPolicy.Decision.HOLD_ACTIVE
    }

    enum class Decision {
        WAIT,
        HOLD_ACTIVE,
        FORCE_INACTIVE,
    }
}
