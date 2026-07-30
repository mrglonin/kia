package kia.app.navigation.domain;

/**
 * Decides whether missing Yandex bridge packets are enough to end navigation.
 *
 * <p>An explicit route-stop packet is handled elsewhere. The watchdog is only
 * a last-resort recovery mechanism, so a stored route and fresh local GPS are
 * stronger evidence than a temporarily silent background bridge.</p>
 */
public final class YandexRouteWatchdogPolicy {
    public enum Decision {
        WAIT,
        HOLD_ACTIVE,
        FORCE_INACTIVE
    }

    private YandexRouteWatchdogPolicy() {
    }

    public static Decision decide(long packetAgeMs, long gpsAgeMs,
                                  boolean hasRouteSnapshot,
                                  long packetStaleMs, long gpsFreshMs,
                                  long maxSilentMs) {
        if (packetAgeMs < packetStaleMs) return Decision.WAIT;
        if (!hasRouteSnapshot) return Decision.FORCE_INACTIVE;
        if (gpsAgeMs >= 0L && gpsAgeMs <= gpsFreshMs) return Decision.HOLD_ACTIVE;
        if (packetAgeMs >= maxSilentMs) return Decision.FORCE_INACTIVE;
        return Decision.HOLD_ACTIVE;
    }
}
