package kia.app.navigation.domain;

import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public final class YandexRoadContextPolicyTest {
    @Test
    public void freshYandexLimitSurvivesRouteOff() {
        assertTrue(YandexRoadContextPolicy.shouldPreserve(
                "60", true, 10_000L, 20_000L, 12_000L, false));
    }

    @Test
    public void staleOrUnconfirmedLimitDoesNotSurvive() {
        assertFalse(YandexRoadContextPolicy.shouldPreserve(
                "60", true, 10_000L, 22_001L, 12_000L, false));
        assertFalse(YandexRoadContextPolicy.shouldPreserve(
                "60", true, 0L, 10_000L, 12_000L, false));
    }

    @Test
    public void nonYandexLimitDoesNotLeakAcrossRouteOff() {
        assertFalse(YandexRoadContextPolicy.shouldPreserve(
                "80", false, 10_000L, 11_000L, 12_000L, false));
    }

    @Test
    public void navigationDisableAndSourceHandoffAreHardResets() {
        assertFalse(YandexRoadContextPolicy.shouldPreserve(
                "90", true, 10_000L, 11_000L, 12_000L, true));
    }

    @Test
    public void positiveLimitIsReassertedExactlyOncePerUsbConnectionEpoch() {
        assertTrue(YandexRoadContextPolicy.shouldReassertTx(60, true, 2L, 1L));
        assertFalse(YandexRoadContextPolicy.shouldReassertTx(60, true, 2L, 2L));
        assertFalse(YandexRoadContextPolicy.shouldReassertTx(60, false, 2L, 1L));
        assertFalse(YandexRoadContextPolicy.shouldReassertTx(0, true, 2L, 1L));
    }
}
