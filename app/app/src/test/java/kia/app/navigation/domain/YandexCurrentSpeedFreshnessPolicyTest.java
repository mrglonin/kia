package kia.app.navigation.domain;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public final class YandexCurrentSpeedFreshnessPolicyTest {
    @Test
    public void freshYandexSampleKeepsOwnership() {
        assertTrue(YandexCurrentSpeedFreshnessPolicy.isFresh(
                10_000L, 13_500L, 3_500L));
        assertEquals(YandexCurrentSpeedFreshnessPolicy.Action.KEEP_YANDEX,
                YandexCurrentSpeedFreshnessPolicy.expiryAction(
                        10_000L, 13_000L, 13_500L,
                        3_500L, 5_000L));
    }

    @Test
    public void staleYandexUsesFreshGpsIncludingStandstill() {
        assertEquals(YandexCurrentSpeedFreshnessPolicy.Action.USE_GPS,
                YandexCurrentSpeedFreshnessPolicy.expiryAction(
                        10_000L, 14_000L, 14_001L,
                        3_500L, 5_000L));
    }

    @Test
    public void staleYandexAndGpsClearCurrentSpeed() {
        assertEquals(YandexCurrentSpeedFreshnessPolicy.Action.CLEAR,
                YandexCurrentSpeedFreshnessPolicy.expiryAction(
                        10_000L, 8_000L, 14_001L,
                        3_500L, 5_000L));
    }

    @Test
    public void clockRollbackCannotMakeLeaseFresh() {
        assertFalse(YandexCurrentSpeedFreshnessPolicy.isFresh(
                20_000L, 19_999L, 3_500L));
    }
}
