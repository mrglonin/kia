package kia.app.navigation.domain;

/** Separates a fresh Yandex road-speed limit from the route active/off lifecycle. */
public final class YandexRoadContextPolicy {
    private YandexRoadContextPolicy() {
    }

    public static boolean shouldPreserve(String storedLimit, boolean yandexSource,
                                         long lastFreshLimitAt, long now, long holdMs,
                                         boolean hardReset) {
        if (hardReset || !yandexSource) return false;
        if (storedLimit == null || storedLimit.trim().isEmpty()) return false;
        if (lastFreshLimitAt <= 0L || now < lastFreshLimitAt) return false;
        return now - lastFreshLimitAt <= Math.max(0L, holdMs);
    }

    public static boolean shouldReassertTx(int speedLimitKmh, boolean usbReady,
                                           long connectionEpoch, long lastWrittenEpoch) {
        return speedLimitKmh > 0 && usbReady && connectionEpoch != lastWrittenEpoch;
    }
}
