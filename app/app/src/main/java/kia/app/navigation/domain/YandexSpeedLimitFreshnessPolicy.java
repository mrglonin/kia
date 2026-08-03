package kia.app.navigation.domain;

/** Keeps a Yandex road-speed sign from remaining on the cluster indefinitely. */
public final class YandexSpeedLimitFreshnessPolicy {
    private YandexSpeedLimitFreshnessPolicy() {
    }

    public static boolean shouldClear(String storedLimit, long lastFreshLimitAt,
                                      long now, long holdMs) {
        return shouldClear(storedLimit, lastFreshLimitAt, now, holdMs, false);
    }

    public static boolean shouldClear(String storedLimit, long lastFreshLimitAt,
                                      long now, long holdMs, boolean stationary) {
        if (storedLimit == null || storedLimit.trim().isEmpty()) return false;
        if (lastFreshLimitAt <= 0L || now < lastFreshLimitAt) return true;
        return now - lastFreshLimitAt > Math.max(0L, holdMs) && !stationary;
    }
}
