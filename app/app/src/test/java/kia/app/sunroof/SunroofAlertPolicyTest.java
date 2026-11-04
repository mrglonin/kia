package kia.app.sunroof;

import org.junit.Test;

import static kia.app.sunroof.SunroofAlertPolicy.Kind.*;
import static org.junit.Assert.*;

public final class SunroofAlertPolicyTest {
    private final SunroofAlertPolicy policy = new SunroofAlertPolicy();

    private SunroofAlertPolicy.Input input(boolean enabled, boolean parking,
            boolean connected, long epoch, boolean known, boolean open, long roofEpoch,
            long roofAt, boolean accKnown, boolean on, long now) {
        return new SunroofAlertPolicy.Input(enabled, parking, connected, epoch,
                known, open, roofEpoch, roofAt, accKnown, on, now);
    }

    private SunroofAlertPolicy.Decision sample(boolean open, long at, boolean on) {
        return policy.update(input(true, true, true, 1, true, open, 1, at, true, on, at));
    }

    private SunroofAlertPolicy.Decision unknownAcc(boolean open, long at) {
        return policy.update(input(true, true, true, 1, true, open, 1, at, false, false, at));
    }

    @Test public void firstOpenAndFirstClosedAreSilentBaselines() {
        assertEquals(NONE, sample(true, 100, true).kind);
        policy.reset();
        assertEquals(NONE, sample(false, 200, true).kind);
    }

    @Test public void realTransitionsHaveDistinctSequencesAndCompletionHidesOnlyOnce() {
        sample(false, 100, true);
        SunroofAlertPolicy.Decision opening = sample(true, 200, true);
        assertEquals(OPENING, opening.kind);
        assertTrue(opening.roofOpen);
        SunroofAlertPolicy.Decision duplicate = sample(true, 201, true);
        assertEquals(opening.sequence, duplicate.sequence);
        SunroofAlertPolicy.Decision closing = sample(false, 202, true);
        assertEquals(CLOSING, closing.kind);
        assertFalse(closing.roofOpen);
        assertTrue(closing.sequence > opening.sequence);
        assertEquals(closing.sequence, policy.finish(opening.sequence).sequence);
        SunroofAlertPolicy.Decision done = policy.finish(closing.sequence);
        assertEquals(NONE, done.kind);
        assertTrue(done.sequence > closing.sequence);
        assertEquals(done.sequence, policy.finish(closing.sequence).sequence);
        assertEquals(done.sequence, sample(false, 203, true).sequence);
    }

    @Test public void unknownRoofDoesNotTurnCachedOpenIntoAnOpening() {
        sample(false, 100, true);
        policy.update(input(true, true, true, 1, false, false, -1, -1, true, true, 110));
        assertEquals(NONE, sample(true, 120, true).kind);
    }

    @Test public void knownOpenAndInitialAccOffCanWarnWithoutAnimating() {
        SunroofAlertPolicy.Decision warning = sample(true, 100, false);
        assertEquals(PARKING_WARNING, warning.kind);
        assertTrue(warning.roofOpen);
        assertTrue(warning.warningEpisode > 0);
        assertEquals(warning.sequence, sample(true, 101, false).sequence);
    }

    @Test public void accUnknownNeverMeansOffAndLaterFreshOffMayWarn() {
        assertEquals(NONE, unknownAcc(true, 100).kind);
        SunroofAlertPolicy.Decision warning = sample(true, 200, false);
        assertEquals(PARKING_WARNING, warning.kind);
    }

    @Test public void accOffCannotWarnWithoutKnownRoof() {
        SunroofAlertPolicy.Decision none = policy.update(input(true, true, true, 1,
                false, true, 1, 100, true, false, 100));
        assertEquals(NONE, none.kind);
    }

    @Test public void oldButCurrentConnectionRoofCanWarnWithoutAnArbitraryTtl() {
        sample(true, 100, true);
        SunroofAlertPolicy.Decision warning = policy.update(input(true, true, true, 1,
                true, true, 1, 100, true, false, 10_000_000));
        assertEquals(PARKING_WARNING, warning.kind);
    }

    @Test public void parkingWarningWinsOverOpeningAndSurvivesTransientCompletion() {
        sample(false, 100, true);
        SunroofAlertPolicy.Decision opening = sample(true, 200, true);
        SunroofAlertPolicy.Decision warning = sample(true, 300, false);
        assertEquals(PARKING_WARNING, warning.kind);
        assertTrue(warning.sequence > opening.sequence);
        assertEquals(warning.sequence, policy.finish(opening.sequence).sequence);
        assertEquals(warning.sequence, policy.finish(warning.sequence).sequence);
    }

    @Test public void dismissSuppressesAllDuplicateHeartbeatsAndRepeatedOff() {
        sample(true, 100, false);
        SunroofAlertPolicy.Decision dismissed = policy.dismiss();
        assertEquals(NONE, dismissed.kind);
        for (long time = 101; time < 200; time++) {
            assertEquals(dismissed.sequence, sample(true, time, false).sequence);
        }
    }

    @Test public void knownAccOnClearsAndNextOffIsANewWarningEpisode() {
        SunroofAlertPolicy.Decision first = sample(true, 100, false);
        policy.dismiss();
        assertEquals(NONE, sample(true, 200, true).kind);
        SunroofAlertPolicy.Decision second = sample(true, 300, false);
        assertEquals(PARKING_WARNING, second.kind);
        assertTrue(second.warningEpisode > first.warningEpisode);
    }

    @Test public void accOnClearsAnUndismissedWarning() {
        SunroofAlertPolicy.Decision first = sample(true, 100, false);
        SunroofAlertPolicy.Decision clear = sample(true, 200, true);
        assertEquals(NONE, clear.kind);
        assertTrue(clear.sequence > first.sequence);
    }

    @Test public void unknownAccDoesNotRearmAcknowledgement() {
        sample(true, 100, false);
        policy.dismiss();
        unknownAcc(true, 200);
        assertEquals(NONE, sample(true, 300, false).kind);
        unknownAcc(true, 400);
        sample(true, 500, true);
        assertEquals(PARKING_WARNING, sample(true, 600, false).kind);
    }

    @Test public void closingClearsWarningAndReopeningWhileParkedStartsNewEpisode() {
        SunroofAlertPolicy.Decision first = sample(true, 100, false);
        policy.dismiss();
        assertEquals(CLOSING, sample(false, 200, false).kind);
        SunroofAlertPolicy.Decision second = sample(true, 300, false);
        assertEquals(PARKING_WARNING, second.kind);
        assertTrue(second.warningEpisode > first.warningEpisode);
    }

    @Test public void closingClearsAnUndismissedWarning() {
        SunroofAlertPolicy.Decision first = sample(true, 100, false);
        SunroofAlertPolicy.Decision close = sample(false, 200, false);
        assertEquals(CLOSING, close.kind);
        assertTrue(close.sequence > first.sequence);
        assertEquals(close.sequence, policy.finish(first.sequence).sequence);
    }

    @Test public void disconnectCannotCreateAParkingWarning() {
        sample(true, 100, true);
        SunroofAlertPolicy.Decision lost = policy.update(input(true, true, false, 2,
                false, false, -1, -1, true, true, 200));
        assertEquals(NONE, lost.kind);
        assertEquals(NONE, policy.update(input(true, true, false, 2,
                false, true, -1, -1, false, false, 201)).kind);
    }

    @Test public void latchedWarningSurvivesDisconnectAndUnknownAccUntilAcknowledged() {
        SunroofAlertPolicy.Decision first = sample(true, 100, false);
        SunroofAlertPolicy.Decision lost = policy.update(input(true, true, false, 2,
                false, false, -1, -1, false, false, 200));
        assertEquals(first.sequence, lost.sequence);
        assertEquals(PARKING_WARNING, lost.kind);
        assertTrue(lost.roofOpen);
        assertEquals(NONE, policy.dismiss().kind);
    }

    @Test public void reconnectSameOpenNeverRearmsAcknowledgement() {
        sample(true, 100, false);
        SunroofAlertPolicy.Decision dismissed = policy.dismiss();
        policy.update(input(true, true, false, 2, false, false, -1, -1, true, false, 200));
        assertEquals(NONE, policy.update(input(true, true, true, 3,
                true, true, 3, 300, true, false, 300)).kind);
        assertEquals(dismissed.sequence, policy.update(input(true, true, true, 3,
                true, true, 3, 301, true, false, 301)).sequence);
    }

    @Test public void reconnectRebaselinesWithoutOpeningOrClosingAnimations() {
        sample(false, 100, true);
        policy.update(input(true, true, false, 2, false, false, -1, -1, true, true, 200));
        assertEquals(NONE, policy.update(input(true, true, true, 3,
                true, true, 3, 300, true, true, 300)).kind);
        assertEquals(CLOSING, policy.update(input(true, true, true, 3,
                true, false, 3, 400, true, true, 400)).kind);
    }

    @Test public void oldEpochCannotSupplyRoofForInitialWarningAfterReconnect() {
        sample(true, 100, true);
        policy.update(input(true, true, false, 2, false, false, -1, -1, true, true, 200));
        assertEquals(NONE, policy.update(input(true, true, true, 3,
                true, true, 1, 100, true, false, 300)).kind);
        assertEquals(PARKING_WARNING, policy.update(input(true, true, true, 3,
                true, true, 3, 400, true, false, 400)).kind);
    }

    @Test public void firstClosedSampleAfterReconnectClearsWarningWithoutAnimation() {
        sample(true, 100, false);
        policy.update(input(true, true, false, 2, false, false, -1, -1, true, false, 200));
        assertEquals(NONE, policy.update(input(true, true, true, 3,
                true, false, 3, 300, true, false, 300)).kind);
    }

    @Test public void actualAccOffWithinDisconnectGraceWarnsIncludingBoundary() {
        for (long delay : new long[]{0, 1, 2_999, 3_000}) {
            policy.reset();
            sample(true, 100, true);
            if (delay > 0) policy.update(input(true, true, false, 2,
                    false, false, -1, -1, true, true, 200));
            assertEquals("delay=" + delay, PARKING_WARNING,
                    policy.update(input(true, true, false, 2,
                            false, false, -1, -1, true, false, 200 + delay)).kind);
        }
    }

    @Test public void actualAccOffOutsideGraceOrAfterUnknownCannotUseDisconnectedRoof() {
        sample(true, 100, true);
        policy.update(input(true, true, false, 2, false, false, -1, -1, true, true, 200));
        assertEquals(NONE, policy.update(input(true, true, false, 2,
                false, false, -1, -1, true, false, 3_201)).kind);
        policy.reset();
        sample(true, 100, true);
        policy.update(input(true, true, false, 2, false, false, -1, -1, false, false, 200));
        assertEquals(NONE, policy.update(input(true, true, false, 2,
                false, false, -1, -1, true, false, 201)).kind);
    }

    @Test public void disconnectGraceAlsoWorksWhenEpochAdvancesOnlyAtConnect() {
        sample(true, 100, true);
        policy.update(input(true, true, false, 1, false, false, -1, -1, true, true, 200));
        assertEquals(PARKING_WARNING, policy.update(input(true, true, false, 1,
                false, false, -1, -1, true, false, 201)).kind);
    }

    @Test public void disablingInvalidatesDisconnectGraceAndItsOldAccBaseline() {
        sample(true, 100, true);
        policy.update(input(false, true, true, 1, true, true, 1, 100, true, true, 200));
        assertEquals(NONE, policy.update(input(true, true, false, 1,
                false, false, -1, -1, true, false, 201)).kind);
    }

    @Test public void disconnectGraceRequiresRoofValidImmediatelyBeforeLoss() {
        sample(true, 100, true);
        policy.update(input(true, true, true, 1, false, true, -1, -1, true, true, 150));
        assertEquals(NONE, policy.update(input(true, true, false, 2,
                false, true, -1, -1, true, false, 200)).kind);
    }

    @Test public void disconnectedStartupOffNeverUsesCachedRoofOrPretendsOnToOff() {
        assertEquals(NONE, policy.update(input(true, true, false, 2,
                true, true, 2, 100, true, false, 100)).kind);
        assertEquals(NONE, policy.update(input(true, true, false, 2,
                true, true, 2, 200, true, false, 200)).kind);
    }

    @Test public void dismissDuringDisconnectIsNotRearmedByGraceOrReconnect() {
        sample(true, 100, false);
        policy.update(input(true, true, false, 2, false, false, -1, -1, true, false, 200));
        policy.dismiss();
        assertEquals(NONE, policy.update(input(true, true, false, 2,
                false, false, -1, -1, true, false, 201)).kind);
        assertEquals(NONE, policy.update(input(true, true, true, 3,
                true, true, 3, 300, true, false, 300)).kind);
    }

    @Test public void disablingCancelsWarningEvenFromStaleQueuedSnapshot() {
        SunroofAlertPolicy.Decision first = sample(true, 100, false);
        SunroofAlertPolicy.Decision clear = policy.update(input(false, true, true, 0,
                true, true, 0, 1, true, false, 1));
        assertEquals(NONE, clear.kind);
        assertTrue(clear.sequence > first.sequence);
        assertEquals(clear.sequence, policy.finish(first.sequence).sequence);
    }

    @Test public void parkingSettingDoesNotDisableOrdinaryTransitions() {
        policy.update(input(true, false, true, 1, true, false, 1, 100, true, false, 100));
        assertEquals(OPENING, policy.update(input(true, false, true, 1,
                true, true, 1, 200, true, false, 200)).kind);
    }

    @Test public void disablingParkingSettingClearsLatchedWarning() {
        sample(true, 100, false);
        assertEquals(NONE, policy.update(input(true, false, true, 1,
                true, true, 1, 200, true, false, 200)).kind);
    }

    @Test public void olderConnectionOrInputTimeCannotClearNewWarning() {
        SunroofAlertPolicy.Decision first = sample(true, 100, false);
        assertEquals(first.sequence, policy.update(input(true, true, true, 0,
                true, false, 0, 200, true, true, 200)).sequence);
        assertEquals(first.sequence, policy.update(input(true, true, true, 1,
                true, false, 1, 99, true, true, 99)).sequence);
    }

    @Test public void backdatedRoofFramesCannotCancelTransitionOrWarning() {
        sample(false, 100, true);
        SunroofAlertPolicy.Decision opening = sample(true, 200, true);
        assertEquals(opening.sequence, policy.update(input(true, true, true, 1,
                true, false, 1, 199, true, true, 201)).sequence);
        SunroofAlertPolicy.Decision warning = policy.update(input(true, true, true, 1,
                true, true, 1, 200, true, false, 203));
        assertEquals(PARKING_WARNING, warning.kind);
        assertEquals(warning.sequence, policy.update(input(true, true, true, 1,
                true, false, 1, 199, true, false, 204)).sequence);
    }

    @Test public void orderedSameMillisecondCloseWinsAndCannotCreateFalseParkingHazard() {
        sample(false, 100, true);
        SunroofAlertPolicy.Decision opening = sample(true, 200, true);
        SunroofAlertPolicy.Decision closing = sample(false, 200, true);
        assertEquals(CLOSING, closing.kind);
        assertFalse(closing.roofOpen);
        assertTrue(closing.sequence > opening.sequence);
        SunroofAlertPolicy.Decision parked = policy.update(input(true, true, true, 1,
                true, false, 1, 200, true, false, 202));
        assertEquals(CLOSING, parked.kind);
        assertFalse(parked.roofOpen);
        assertEquals(closing.sequence, parked.sequence);
        assertEquals(NONE, policy.finish(closing.sequence).kind);
        assertEquals(NONE, policy.update(input(true, true, true, 1,
                true, false, 1, 200, true, false, 203)).kind);
    }

    @Test public void invalidFutureAndNegativeRoofSamplesCannotEstablishBaseline() {
        assertEquals(NONE, policy.update(input(true, true, true, 1,
                true, true, 1, 101, true, false, 100)).kind);
        assertEquals(NONE, policy.update(input(true, true, true, 1,
                true, true, 1, -1, true, false, 200)).kind);
        assertEquals(NONE, sample(false, 300, true).kind);
    }

    @Test public void newerEpochStillRejectsSourceTimeOlderThanPreviouslyAcceptedSource() {
        sample(false, 100, true);
        policy.update(input(true, true, false, 2, false, false, -1, -1, true, true, 200));
        assertEquals(NONE, policy.update(input(true, true, true, 3,
                true, true, 3, 99, true, false, 300)).kind);
        assertEquals(NONE, policy.update(input(true, true, true, 3,
                true, false, 3, 301, true, true, 301)).kind);
    }

    @Test public void resetClearsAcknowledgementAndFencesOldCompletions() {
        sample(false, 100, true);
        SunroofAlertPolicy.Decision oldOpening = sample(true, 200, true);
        long resetSequence = policy.reset().sequence;
        assertTrue(resetSequence > oldOpening.sequence);
        sample(false, 300, true);
        SunroofAlertPolicy.Decision newOpening = sample(true, 400, true);
        assertEquals(newOpening.sequence, policy.finish(oldOpening.sequence).sequence);
        sample(true, 500, false);
        policy.dismiss();
        policy.reset();
        assertEquals(PARKING_WARNING, sample(true, 600, false).kind);
    }

    @Test public void transientDismissalDoesNotAcknowledgeAFutureParkingWarning() {
        sample(false, 100, true);
        sample(true, 200, true);
        policy.dismiss();
        assertEquals(PARKING_WARNING, sample(true, 300, false).kind);
    }

    private SunroofAlertPolicy.Input remembered(boolean connected, boolean liveKnown,
            boolean liveOpen, boolean savedOpen, boolean acknowledged, boolean on, long now) {
        return new SunroofAlertPolicy.Input(true, true, connected, 1L, liveKnown, liveOpen,
                liveKnown ? 1L : 0L, liveKnown ? now : 0L, true, on, now,
                true, savedOpen, acknowledged);
    }

    @Test public void savedOpenWarnsOnDisconnectedStartupWithoutAnyNewRoofFrame() {
        assertEquals(PARKING_WARNING,
                policy.update(remembered(false, false, false, true, false, false, 900_000L)).kind);
    }

    @Test public void savedClosedNeverWarnsOnDisconnectedStartup() {
        assertEquals(NONE,
                policy.update(remembered(false, false, false, false, false, false, 900_000L)).kind);
    }

    @Test public void sparseRoofUpdatesRemainUsableLongAfterUsbDisconnect() {
        policy.update(remembered(true, true, true, true, false, true, 100L));
        policy.update(remembered(false, false, false, true, false, true, 200L));
        assertEquals(PARKING_WARNING,
                policy.update(remembered(false, false, false, true, false, false, 900_000L)).kind);
    }

    @Test public void savedCloseClearsWarningImmediatelyWithoutFreshUsbPosition() {
        policy.update(remembered(false, false, false, true, false, false, 100L));
        assertEquals(NONE,
                policy.update(remembered(false, false, false, false, false, false, 200L)).kind);
    }

    @Test public void savedClosedVetoesHistoricalOpenDisconnectGrace() {
        sample(true, 100, true);
        assertEquals(NONE,
                policy.update(remembered(false, false, false, false, false, false, 200L)).kind);
    }

    @Test public void restoredAcknowledgementSuppressesSameOffEpisodeAndOnRearms() {
        assertEquals(NONE,
                policy.update(remembered(false, false, false, true, true, false, 100L)).kind);
        policy.update(remembered(false, false, false, true, false, true, 200L));
        assertEquals(PARKING_WARNING,
                policy.update(remembered(false, false, false, true, false, false, 300L)).kind);
    }

    @Test public void savedPositionDoesNotInventAnOpeningAnimationOnReconnect() {
        assertEquals(NONE,
                policy.update(remembered(false, false, false, true, false, true, 100L)).kind);
        assertEquals(NONE,
                policy.update(remembered(true, true, true, true, false, true, 200L)).kind);
        assertEquals(CLOSING,
                policy.update(remembered(true, true, false, false, false, true, 300L)).kind);
    }
}
