package kia.app.navigation.domain;

/** Keeps a Yandex road-speed sign from remaining on the cluster indefinitely. */
public final class YandexSpeedLimitFreshnessPolicy {
    private YandexSpeedLimitFreshnessPolicy() {
    }

    public static boolean shouldClear(String storedLimit, long lastFreshLimitAt,
                                      long now, long holdMs) {
        if (storedLimit == null || storedLimit.trim().isEmpty()) return false;
        if (lastFreshLimitAt <= 0L || now < lastFreshLimitAt) return true;
        return now - lastFreshLimitAt > holdMs;
    }
}
