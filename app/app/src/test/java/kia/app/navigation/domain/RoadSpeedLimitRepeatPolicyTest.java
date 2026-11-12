package kia.app.navigation.domain;

import org.junit.Test;

import static org.junit.Assert.assertEquals;

public final class RoadSpeedLimitRepeatPolicyTest {
    @Test
    public void repeatsAtFifteenSecondsWhileFreshSamplesContinue() {
        RoadSpeedLimitRepeatPolicy policy = new RoadSpeedLimitRepeatPolicy();
        policy.observeFresh(60, 0L);
        assertEquals(15000L, policy.delayUntilNext(0L));
        policy.observeFresh(60, 10000L);
        assertEquals(0, policy.dueValue(14999L));
        assertEquals(60, policy.dueValue(15000L));
        assertEquals(60, policy.dueValue(15000L)); // A read does not consume the event.
        assertEquals(1L, policy.delayUntilNext(15000L));

        policy.scheduleNext(60, 15000L);
        policy.observeFresh(60, 25000L);
        assertEquals(0, policy.dueValue(29999L));
        assertEquals(60, policy.dueValue(30000L));
    }

    @Test
    public void frequentIdenticalSamplesDoNotPostponeTheDeadline() {
        RoadSpeedLimitRepeatPolicy policy = new RoadSpeedLimitRepeatPolicy();
        policy.observeFresh(60, 1000L);
        for (long now = 2000L; now <= 15000L; now += 1000L) {
            policy.observeFresh(60, now);
            assertEquals(16000L - now, policy.delayUntilNext(now));
        }
        assertEquals(60, policy.dueValue(16000L));
    }

    @Test
    public void changedPositiveStartsItsOwnDeadline() {
        RoadSpeedLimitRepeatPolicy policy = new RoadSpeedLimitRepeatPolicy();
        policy.observeFresh(60, 1000L);
        policy.observeFresh(40, 5000L);
        assertEquals(40, policy.currentValue());
        policy.observeFresh(40, 15000L);
        assertEquals(0, policy.dueValue(16000L));
        assertEquals(4000L, policy.delayUntilNext(16000L));
        assertEquals(40, policy.dueValue(20000L));
    }

    @Test
    public void invalidOrMissingSamplesCancelWithoutSendingZero() {
        for (int missing : new int[]{0, -1, 256}) {
            RoadSpeedLimitRepeatPolicy policy = new RoadSpeedLimitRepeatPolicy();
            policy.observeFresh(60, 1000L);
            policy.observeFresh(missing, 2000L);
            assertEquals(0, policy.currentValue());
            assertEquals(0, policy.dueValue(16000L));
            assertEquals(0L, policy.delayUntilNext(16000L));
        }
    }

    @Test
    public void aSingleSampleExpiresBeforeItsFirstRepeat() {
        RoadSpeedLimitRepeatPolicy policy = new RoadSpeedLimitRepeatPolicy();
        policy.observeFresh(60, 1000L);
        assertEquals(0, policy.dueValue(16000L));
        assertEquals(0, policy.currentValue());
        assertEquals(0L, policy.delayUntilNext(16000L));
    }

    @Test
    public void freshnessBoundaryAllowsExactlyTwelveSeconds() {
        RoadSpeedLimitRepeatPolicy policy = new RoadSpeedLimitRepeatPolicy();
        policy.observeFresh(60, 0L);
        policy.observeFresh(60, 3000L);
        assertEquals(60, policy.dueValue(15000L));
        assertEquals(0, policy.dueValue(15001L));
        assertEquals(0, policy.currentValue());
    }

    @Test
    public void checkingDelayAlsoRevokesExpiredEligibility() {
        RoadSpeedLimitRepeatPolicy policy = new RoadSpeedLimitRepeatPolicy();
        policy.observeFresh(60, 1000L);
        assertEquals(0L, policy.delayUntilNext(13001L));
        assertEquals(0, policy.currentValue());
    }

    @Test
    public void schedulingAnAttemptDoesNotRenewSourceFreshness() {
        RoadSpeedLimitRepeatPolicy policy = new RoadSpeedLimitRepeatPolicy();
        policy.observeFresh(60, 1000L);
        policy.observeFresh(60, 11000L);
        policy.scheduleNext(60, 16000L);
        assertEquals(15000L, policy.delayUntilNext(16000L));
        assertEquals(0, policy.dueValue(31000L));
        assertEquals(0, policy.currentValue());
    }

    @Test
    public void aSuccessfulRetrySchedulesFromItsWriteTime() {
        RoadSpeedLimitRepeatPolicy policy = new RoadSpeedLimitRepeatPolicy();
        policy.observeFresh(60, 0L);
        policy.observeFresh(60, 10000L);
        policy.scheduleNext(60, 15000L); // Periodic attempt failed.
        policy.scheduleNext(60, 15250L); // A retry was written.
        policy.observeFresh(60, 25000L);
        assertEquals(0, policy.dueValue(30000L));
        assertEquals(60, policy.dueValue(30250L));
    }

    @Test
    public void restoredOrWrongValuesCannotArmOrMoveARepeat() {
        RoadSpeedLimitRepeatPolicy policy = new RoadSpeedLimitRepeatPolicy();
        policy.scheduleNext(60, 1000L);
        assertEquals(0, policy.currentValue());
        assertEquals(0, policy.dueValue(16000L));
        assertEquals(0L, policy.delayUntilNext(16000L));

        policy.observeFresh(40, 20000L);
        policy.scheduleNext(60, 25000L);
        assertEquals(10000L, policy.delayUntilNext(25000L));
        assertEquals(40, policy.currentValue());
    }

    @Test
    public void clockRollbackRevokesEligibilityUntilAnotherFreshSample() {
        RoadSpeedLimitRepeatPolicy policy = new RoadSpeedLimitRepeatPolicy();
        policy.observeFresh(60, 10000L);
        assertEquals(0, policy.dueValue(9999L));
        assertEquals(0, policy.currentValue());
        policy.scheduleNext(60, 10000L);
        assertEquals(0L, policy.delayUntilNext(10000L));
        policy.observeFresh(60, 11000L);
        assertEquals(15000L, policy.delayUntilNext(11000L));

        policy.observeFresh(60, 10999L);
        assertEquals(0, policy.currentValue());
    }

    @Test
    public void cancelRequiresANewFreshObservationToRearm() {
        RoadSpeedLimitRepeatPolicy policy = new RoadSpeedLimitRepeatPolicy();
        policy.observeFresh(60, 1000L);
        policy.cancel();
        policy.scheduleNext(60, 2000L);
        assertEquals(0, policy.currentValue());
        policy.observeFresh(60, 3000L);
        assertEquals(15000L, policy.delayUntilNext(3000L));
    }
}
