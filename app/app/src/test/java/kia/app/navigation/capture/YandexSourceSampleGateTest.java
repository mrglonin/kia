package kia.app.navigation.capture;

import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public final class YandexSourceSampleGateTest {
    @Test
    public void advancingLocationTimestampAuthorizesBothFieldsOnce() {
        YandexSourceSampleGate gate = new YandexSourceSampleGate();

        YandexSourceSampleGate.Decision first = gate.evaluate(
                "heartbeat", 99_000L, 999_000L,
                100_000L, 1_000_000L, 15_000L);
        YandexSourceSampleGate.Decision repeated = gate.evaluate(
                "heartbeat", 99_000L, 999_000L,
                101_000L, 1_001_000L, 15_000L);

        assertTrue(first.currentSpeedFresh);
        assertTrue(first.roadLimitFresh);
        assertFalse(repeated.currentSpeedFresh);
        assertFalse(repeated.roadLimitFresh);
    }

    @Test
    public void heartbeatWithoutSampleCannotRefreshOrReapplyRoadData() {
        YandexSourceSampleGate.Decision value = new YandexSourceSampleGate().evaluate(
                "heartbeat", -1L, -1L,
                100_000L, 1_000_000L, 15_000L);

        assertFalse(value.currentSpeedFresh);
        assertFalse(value.roadLimitFresh);
    }

    @Test
    public void staleOrRegressedHeartbeatIsRejected() {
        YandexSourceSampleGate gate = new YandexSourceSampleGate();
        YandexSourceSampleGate.Decision stale = gate.evaluate(
                "heartbeat", 50_000L, 950_000L,
                100_000L, 1_000_000L, 15_000L);
        YandexSourceSampleGate.Decision regressed = gate.evaluate(
                "heartbeat", 49_000L, 949_000L,
                101_000L, 1_001_000L, 15_000L);

        assertFalse(stale.anyFreshData());
        assertFalse(regressed.anyFreshData());
    }

    @Test
    public void speedLimitCallbackRefreshesOnlyLimitWhenLocationDidNotAdvance() {
        YandexSourceSampleGate gate = new YandexSourceSampleGate();
        gate.evaluate("heartbeat", 99_000L, 999_000L,
                100_000L, 1_000_000L, 15_000L);

        YandexSourceSampleGate.Decision value = gate.evaluate(
                "speed_limit", 99_000L, 999_000L,
                101_000L, 1_001_000L, 15_000L);

        assertFalse(value.currentSpeedFresh);
        assertTrue(value.roadLimitFresh);
    }

    @Test
    public void exceededCallbackNeverRenewsRoadLimitLease() {
        YandexSourceSampleGate.Decision value = new YandexSourceSampleGate().evaluate(
                "speed_limit_exceeded", -1L, -1L,
                101_000L, 1_001_000L, 15_000L);

        assertTrue(value.currentSpeedFresh);
        assertFalse(value.roadLimitFresh);
    }

    @Test
    public void exceededUpdatedCallbackAlsoRefreshesCurrentOnly() {
        YandexSourceSampleGate.Decision value = new YandexSourceSampleGate().evaluate(
                "speed_limit_exceeded_updated", -1L, -1L,
                101_000L, 1_001_000L, 15_000L);

        assertTrue(value.currentSpeedFresh);
        assertFalse(value.roadLimitFresh);
    }

    @Test
    public void similarlyNamedUpdatedCallbackIsNotAConfirmedRoadLimitChange() {
        YandexSourceSampleGate.Decision value = new YandexSourceSampleGate().evaluate(
                "speed_limit_updated", -1L, -1L,
                101_000L, 1_001_000L, 15_000L);

        assertFalse(value.currentSpeedFresh);
        assertFalse(value.roadLimitFresh);
    }

    @Test
    public void locationCallbackRemainsStrongEvidenceForLegacyProducer() {
        YandexSourceSampleGate.Decision value = new YandexSourceSampleGate().evaluate(
                "location", -1L, -1L,
                100_000L, 1_000_000L, 15_000L);

        assertTrue(value.currentSpeedFresh);
        assertTrue(value.roadLimitFresh);
    }

    @Test
    public void locationCallbackCannotRenewTheSameSourceToken() {
        YandexSourceSampleGate gate = new YandexSourceSampleGate();
        gate.evaluate("location", 99_000L, 999_000L,
                100_000L, 1_000_000L, 15_000L);

        YandexSourceSampleGate.Decision value = gate.evaluate(
                "location", 99_000L, 999_000L,
                101_000L, 1_001_000L, 15_000L);

        assertFalse(value.currentSpeedFresh);
        assertFalse(value.roadLimitFresh);
    }

    @Test
    public void locationCallbackRejectsStaleAndRegressedSourceTokens() {
        YandexSourceSampleGate gate = new YandexSourceSampleGate();
        YandexSourceSampleGate.Decision stale = gate.evaluate(
                "location", 50_000L, 950_000L,
                100_000L, 1_000_000L, 15_000L);
        YandexSourceSampleGate.Decision regressed = gate.evaluate(
                "location", 49_000L, 949_000L,
                101_000L, 1_001_000L, 15_000L);

        assertFalse(stale.anyFreshData());
        assertFalse(regressed.anyFreshData());
    }

    @Test
    public void resetAllowsFirstFreshSampleOfNewClientSession() {
        YandexSourceSampleGate gate = new YandexSourceSampleGate();
        gate.evaluate("heartbeat", 99_000L, 999_000L,
                100_000L, 1_000_000L, 15_000L);
        gate.reset();

        YandexSourceSampleGate.Decision value = gate.evaluate(
                "init", 99_000L, 999_000L,
                101_000L, 1_001_000L, 15_000L);

        assertTrue(value.currentSpeedFresh);
        assertTrue(value.roadLimitFresh);
    }
}
