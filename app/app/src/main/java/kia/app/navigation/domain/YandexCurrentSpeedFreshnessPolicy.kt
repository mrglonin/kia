package kia.app.navigation.domain

/** Pure lease/fallback rule for volatile Yandex current-speed samples. */
object YandexCurrentSpeedFreshnessPolicy {
    @JvmStatic
    fun isFresh(lastFreshAt: Long, nowElapsed: Long, holdMs: Long): Boolean {
        if (lastFreshAt <= 0L || nowElapsed < lastFreshAt) return false
        return nowElapsed - lastFreshAt <= Math.max(0L, holdMs)
    }

    @JvmStatic
    fun expiryAction(
        lastYandexAt: Long,
        lastGpsAt: Long,
        nowElapsed: Long,
        yandexHoldMs: Long,
        gpsMaxAgeMs: Long,
    ): YandexCurrentSpeedFreshnessPolicy.Action {
        if (YandexCurrentSpeedFreshnessPolicy.isFresh(lastYandexAt, nowElapsed, yandexHoldMs)) {
            return kia.app.navigation.domain.YandexCurrentSpeedFreshnessPolicy.Action.KEEP_YANDEX
        }
        if (YandexCurrentSpeedFreshnessPolicy.isFresh(lastGpsAt, nowElapsed, gpsMaxAgeMs)) {
            return kia.app.navigation.domain.YandexCurrentSpeedFreshnessPolicy.Action.USE_GPS
        }
        return kia.app.navigation.domain.YandexCurrentSpeedFreshnessPolicy.Action.CLEAR
    }

    enum class Action {
        KEEP_YANDEX,
        USE_GPS,
        CLEAR,
    }
}
