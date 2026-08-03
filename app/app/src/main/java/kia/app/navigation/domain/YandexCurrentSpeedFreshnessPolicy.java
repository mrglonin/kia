package kia.app.navigation.domain;

/** Pure lease/fallback rule for volatile Yandex current-speed samples. */
public final class YandexCurrentSpeedFreshnessPolicy {
    private YandexCurrentSpeedFreshnessPolicy() {
    }

    public enum Action {
        KEEP_YANDEX,
        USE_GPS,
        CLEAR
    }

    public static boolean isFresh(long lastFreshAt, long nowElapsed, long holdMs) {
        if (lastFreshAt <= 0L || nowElapsed < lastFreshAt) return false;
        return nowElapsed - lastFreshAt <= Math.max(0L, holdMs);
    }

    public static Action expiryAction(long lastYandexAt,
                                      long lastGpsAt,
                                      long nowElapsed,
                                      long yandexHoldMs,
                                      long gpsMaxAgeMs) {
        if (isFresh(lastYandexAt, nowElapsed, yandexHoldMs)) {
            return Action.KEEP_YANDEX;
        }
        if (isFresh(lastGpsAt, nowElapsed, gpsMaxAgeMs)) {
            return Action.USE_GPS;
        }
        return Action.CLEAR;
    }
}
