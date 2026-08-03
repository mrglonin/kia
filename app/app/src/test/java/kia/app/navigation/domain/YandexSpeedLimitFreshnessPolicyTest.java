package kia.app.navigation.domain;

import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public final class YandexSpeedLimitFreshnessPolicyTest {
    @Test
    public void freshLimitIsKeptAndExpiredLimitIsCleared() {
        assertFalse(YandexSpeedLimitFreshnessPolicy.shouldClear(
                "90", 10_000L, 20_000L, 12_000L));
        assertTrue(YandexSpeedLimitFreshnessPolicy.shouldClear(
                "90", 10_000L, 22_001L, 12_000L));
    }

    @Test
    public void missingTimestampCannotAuthorizeStoredYandexLimit() {
        assertTrue(YandexSpeedLimitFreshnessPolicy.shouldClear(
                "60", 0L, 20_000L, 12_000L));
        assertFalse(YandexSpeedLimitFreshnessPolicy.shouldClear(
                "", 0L, 20_000L, 12_000L));
    }

    @Test
    public void confirmedLimitSurvivesWhileFreshGpsSaysStationary() {
        assertFalse(YandexSpeedLimitFreshnessPolicy.shouldClear(
                "60", 10_000L, 40_000L, 12_000L, true));
        assertTrue(YandexSpeedLimitFreshnessPolicy.shouldClear(
                "60", 10_000L, 40_000L, 12_000L, false));
    }

    @Test
    public void stationaryCannotRescueLimitThatWasNeverConfirmed() {
        assertTrue(YandexSpeedLimitFreshnessPolicy.shouldClear(
                "60", 0L, 40_000L, 12_000L, true));
    }
}
