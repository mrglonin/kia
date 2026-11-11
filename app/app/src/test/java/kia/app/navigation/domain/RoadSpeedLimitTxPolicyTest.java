package kia.app.navigation.domain;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public final class RoadSpeedLimitTxPolicyTest {
    @Test
    public void everyPositiveByteValueIsAllowedWithoutAWhitelist() {
        RoadSpeedLimitTxPolicy policy = new RoadSpeedLimitTxPolicy();
        for (int speedKmh = 1; speedKmh <= 255; speedKmh++) {
            assertTrue("First delivery of " + speedKmh, policy.shouldSend(speedKmh, true));
            policy.written(speedKmh);
            assertFalse("Duplicate delivery of " + speedKmh, policy.shouldSend(speedKmh, true));
        }
    }

    @Test
    public void changingSignsSendOnceAndMissingDataDoesNotSendOrResetDeduplication() {
        RoadSpeedLimitTxPolicy policy = new RoadSpeedLimitTxPolicy();
        List<Integer> delivered = new ArrayList<>();
        for (int sample : new int[]{20, 20, 0, -1, 20, 40, 40, 0, 40}) {
            if (policy.shouldSend(sample, true)) {
                delivered.add(sample);
                policy.written(sample);
            }
        }
        assertEquals(Arrays.asList(20, 40), delivered);
    }

    @Test
    public void blockedWriteCanRetryOnFreshSample() {
        RoadSpeedLimitTxPolicy policy = new RoadSpeedLimitTxPolicy();
        assertTrue(policy.shouldSend(20, true)); // Transport blocked; no acknowledgement.
        assertTrue(policy.shouldSend(20, true));
        policy.written(20);
        assertFalse(policy.shouldSend(20, true));
        assertTrue(policy.shouldSend(40, true)); // A failed change must remain retryable.
        assertTrue(policy.shouldSend(40, true));
        policy.written(40);
        assertFalse(policy.shouldSend(40, true));
    }

    @Test
    public void routeOnlyBlocksInactiveSamplesAndStartsEachAllowedScopeOnce() {
        RoadSpeedLimitTxPolicy policy = new RoadSpeedLimitTxPolicy();
        assertFalse(policy.shouldSend(20, false));
        policy.setAllowed(true);
        assertTrue(policy.shouldSend(20, true));
        policy.written(20);
        assertFalse(policy.shouldSend(20, true));
        policy.setAllowed(false); // Route finished: no clearing frame.
        assertFalse(policy.shouldSend(40, false));
        policy.setAllowed(true); // A new route may show its first fresh sign.
        assertTrue(policy.shouldSend(20, true));
        policy.written(20);
        assertFalse(policy.shouldSend(20, true));
    }

    @Test
    public void alwaysModeKeepsDeliveryAcrossRouteAndTransportRefreshes() {
        RoadSpeedLimitTxPolicy policy = new RoadSpeedLimitTxPolicy();
        assertTrue(policy.shouldSend(60, true));
        policy.written(60);
        for (int tick = 0; tick < 20; tick++) {
            policy.setAllowed(true); // Publishing route/UI state does not reset this gate.
            assertFalse(policy.shouldSend(60, true));
        }
    }

    @Test
    public void invalidValuesNeverBecomeSignEvents() {
        RoadSpeedLimitTxPolicy policy = new RoadSpeedLimitTxPolicy();
        for (int invalid : new int[]{0, -1, 256, Integer.MAX_VALUE}) {
            assertFalse(policy.shouldSend(invalid, true));
        }
        assertTrue(policy.shouldSend(255, true));
    }
}
