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

    @Test
    public void failedChangeRetriesWithoutAnotherSampleAndStopsAfterWrite() {
        RoadSpeedLimitTxPolicy policy = new RoadSpeedLimitTxPolicy();
        policy.written(40);
        assertTrue(policy.acceptFreshSample(60, true, 1000L));
        assertEquals(60, policy.pendingValue(1250L, true));
        assertEquals(60, policy.pendingValue(2000L, true));
        policy.written(60);
        assertEquals(0, policy.pendingValue(2250L, true));
        assertFalse(policy.acceptFreshSample(60, true, 2500L));
    }

    @Test
    public void newestSampleReplacesPendingIncludingReturnToAnEarlierWrittenValue() {
        RoadSpeedLimitTxPolicy policy = new RoadSpeedLimitTxPolicy();
        assertTrue(policy.acceptFreshSample(40, true, 1000L));
        policy.written(40);
        assertTrue(policy.acceptFreshSample(60, true, 1100L));
        assertTrue(policy.acceptFreshSample(80, true, 1200L));
        assertEquals(80, policy.pendingValue(1300L, true));
        assertTrue(policy.acceptFreshSample(40, true, 1400L));
        assertEquals(40, policy.pendingValue(1500L, true));
        policy.written(40);
        assertFalse(policy.acceptFreshSample(40, true, 1600L));
    }

    @Test
    public void returnToSixtyAfterFailedFortyIsANewSignEvent() {
        RoadSpeedLimitTxPolicy policy = new RoadSpeedLimitTxPolicy();
        assertTrue(policy.acceptFreshSample(60, true, 1000L));
        policy.written(60);
        assertFalse(policy.acceptFreshSample(60, true, 1100L));
        assertTrue(policy.acceptFreshSample(40, true, 1200L));
        // The USB result was BLOCKED; the receiver's actual state is unknown.
        assertEquals(40, policy.pendingValue(1300L, true));
        assertTrue(policy.acceptFreshSample(60, true, 1400L));
        assertEquals(60, policy.pendingValue(1500L, true));
        policy.written(60);
        assertEquals(0, policy.pendingValue(1600L, true));
        assertFalse(policy.acceptFreshSample(60, true, 1700L));
    }

    @Test
    public void missingDataPreservesTheLastObservedPositiveTransition() {
        RoadSpeedLimitTxPolicy policy = new RoadSpeedLimitTxPolicy();
        assertTrue(policy.acceptFreshSample(60, true, 1000L));
        policy.written(60);
        assertFalse(policy.acceptFreshSample(0, true, 1100L));
        assertFalse(policy.acceptFreshSample(-1, true, 1200L));
        assertFalse(policy.acceptFreshSample(60, true, 1300L));

        assertTrue(policy.acceptFreshSample(40, true, 1400L));
        assertFalse(policy.acceptFreshSample(0, true, 1500L));
        assertFalse(policy.acceptFreshSample(-1, true, 1600L));
        assertEquals(0, policy.pendingValue(1700L, true));
        assertTrue(policy.acceptFreshSample(60, true, 1800L));
        assertEquals(60, policy.pendingValue(1900L, true));
        policy.written(60);
        assertFalse(policy.acceptFreshSample(60, true, 2000L));
    }

    @Test
    public void scheduledRepeatReopensWrittenSignAndSuccessRestoresFreshSampleDeduplication() {
        RoadSpeedLimitTxPolicy policy = new RoadSpeedLimitTxPolicy();
        assertTrue(policy.acceptFreshSample(60, true, 1000L));
        policy.written(60);
        assertFalse(policy.acceptFreshSample(60, true, 1500L));
        assertTrue(policy.beginRepeat(60, true, 16000L));
        assertEquals(60, policy.pendingValue(16250L, true));
        assertEquals(60, policy.lastObservedValue());
        assertEquals(60, policy.lastWrittenValue());
        policy.written(60);
        assertEquals(0, policy.pendingValue(16500L, true));
        assertFalse(policy.acceptFreshSample(60, true, 17000L));
    }

    @Test
    public void failedScheduledRepeatRemainsRetryableWhenTheSameFreshSampleArrives() {
        RoadSpeedLimitTxPolicy policy = new RoadSpeedLimitTxPolicy();
        assertTrue(policy.acceptFreshSample(60, true, 1000L));
        policy.written(60);
        assertTrue(policy.beginRepeat(60, true, 16000L));
        assertEquals(60, policy.pendingValue(16250L, true));
        assertTrue(policy.acceptFreshSample(60, true, 16500L));
        assertEquals(60, policy.pendingValue(16750L, true));
        policy.written(60);
        assertFalse(policy.acceptFreshSample(60, true, 17000L));
    }

    @Test
    public void scheduledRepeatRequiresTheKnownCurrentSignAndDoesNotExtendItByPolling() {
        RoadSpeedLimitTxPolicy policy = new RoadSpeedLimitTxPolicy();
        assertFalse(policy.beginRepeat(60, true, 1000L));
        assertEquals(0, policy.lastObservedValue());
        assertTrue(policy.acceptFreshSample(40, true, 2000L));
        assertFalse(policy.beginRepeat(60, true, 2100L));
        assertEquals(40, policy.pendingValue(2200L, true));
        policy.written(40);
        assertFalse(policy.beginRepeat(0, true, 2300L));
        assertTrue(policy.beginRepeat(40, true, 17000L));
        assertEquals(40, policy.pendingValue(19999L, true));
        assertEquals(0, policy.pendingValue(20000L, true));
        assertFalse(policy.beginRepeat(40, false, 21000L));
        assertEquals(0, policy.pendingValue(21250L, false));
    }

    @Test
    public void retryLeaseExpiresAndCannotBeExtendedByPolling() {
        RoadSpeedLimitTxPolicy policy = new RoadSpeedLimitTxPolicy();
        assertTrue(policy.acceptFreshSample(60, true, 1000L));
        assertEquals(60, policy.pendingValue(3999L, true));
        assertEquals(0, policy.pendingValue(4000L, true));
        assertEquals(0, policy.pendingValue(4100L, true));
        assertTrue(policy.acceptFreshSample(60, true, 5000L));
        assertEquals(60, policy.pendingValue(5250L, true));
    }

    @Test
    public void missingDisabledAndClockRollbackCancelWithoutReplay() {
        RoadSpeedLimitTxPolicy policy = new RoadSpeedLimitTxPolicy();
        assertTrue(policy.acceptFreshSample(60, true, 1000L));
        assertFalse(policy.acceptFreshSample(0, true, 1200L));
        assertEquals(0, policy.pendingValue(1250L, true));
        assertTrue(policy.acceptFreshSample(60, true, 2000L));
        assertEquals(0, policy.pendingValue(2250L, false));
        assertEquals(0, policy.pendingValue(2500L, true));
        assertTrue(policy.acceptFreshSample(60, true, 3000L));
        assertEquals(0, policy.pendingValue(2999L, true));
        assertTrue(policy.acceptFreshSample(60, true, 4000L));
        policy.cancelPending();
        assertEquals(0, policy.pendingValue(4250L, true));
    }
}
