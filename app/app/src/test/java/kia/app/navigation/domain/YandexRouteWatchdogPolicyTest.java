package kia.app.navigation.domain;

import org.junit.Test;

import static org.junit.Assert.assertEquals;

public final class YandexRouteWatchdogPolicyTest {
    private static final long STALE = 25_000L;
    private static final long GPS_FRESH = 15_000L;
    private static final long NO_GPS_GRACE = 300_000L;

    @Test
    public void recentPacketWaits() {
        assertEquals(YandexRouteWatchdogPolicy.Decision.WAIT,
                YandexRouteWatchdogPolicy.decide(
                        10_000L, 2_000L, true, STALE, GPS_FRESH, NO_GPS_GRACE));
    }

    @Test
    public void freshLocalGpsKeepsLongRouteActive() {
        assertEquals(YandexRouteWatchdogPolicy.Decision.HOLD_ACTIVE,
                YandexRouteWatchdogPolicy.decide(
                        90_000L, 1_000L, true, STALE, GPS_FRESH, NO_GPS_GRACE));
        assertEquals(YandexRouteWatchdogPolicy.Decision.HOLD_ACTIVE,
                YandexRouteWatchdogPolicy.decide(
                        900_000L, 1_000L, true, STALE, GPS_FRESH, NO_GPS_GRACE));
    }

    @Test
    public void routeGetsGraceWhenGpsTemporarilyUnavailable() {
        assertEquals(YandexRouteWatchdogPolicy.Decision.HOLD_ACTIVE,
                YandexRouteWatchdogPolicy.decide(
                        60_000L, 40_000L, true, STALE, GPS_FRESH, NO_GPS_GRACE));
    }

    @Test
    public void routeWithoutAnyFreshEvidenceEventuallyStops() {
        assertEquals(YandexRouteWatchdogPolicy.Decision.FORCE_INACTIVE,
                YandexRouteWatchdogPolicy.decide(
                        300_000L, 40_000L, true, STALE, GPS_FRESH, NO_GPS_GRACE));
        assertEquals(YandexRouteWatchdogPolicy.Decision.FORCE_INACTIVE,
                YandexRouteWatchdogPolicy.decide(
                        30_000L, 1_000L, false, STALE, GPS_FRESH, NO_GPS_GRACE));
    }
}
