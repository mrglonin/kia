package kia.app.navigation.domain;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public final class ManeuverProgressTrackerTest {
    @Test
    public void everyFirstValidDistanceStartsFullWithoutFiveHundredMeterFloor() {
        assertNewFull(new ManeuverProgressTracker(), 1000f);
        assertNewFull(new ManeuverProgressTracker(), 300f);
        assertNewFull(new ManeuverProgressTracker(), 250f);
        assertNewFull(new ManeuverProgressTracker(), 180f);
        assertNewFull(new ManeuverProgressTracker(), 40f);
    }

    @Test
    public void countdownIsMonotonicAndEndsEmptyNearManeuver() {
        ManeuverProgressTracker tracker = new ManeuverProgressTracker();

        assertEquals(9, observe(tracker, 300f).bucket);
        assertEquals(8, observe(tracker, 250f).bucket);
        assertEquals(6, observe(tracker, 200f).bucket);
        assertEquals(5, observe(tracker, 150f).bucket);
        assertEquals(3, observe(tracker, 100f).bucket);
        assertEquals(2, observe(tracker, 60f).bucket);
        assertEquals(0, observe(tracker, 50f).bucket);
    }

    @Test
    public void distanceNoiseCannotRefillCurrentEvent() {
        ManeuverProgressTracker tracker = new ManeuverProgressTracker();

        observe(tracker, 300f);
        int atTwoHundred = observe(tracker, 200f).bucket;
        ManeuverProgressTracker.Result noisy = observe(tracker, 210f);

        assertEquals(atTwoHundred, noisy.bucket);
        assertFalse(noisy.newEvent);
        assertEquals(atTwoHundred, observe(tracker, 190f).bucket);
    }

    @Test
    public void sameFamilyPostPassJumpStartsSecondManeuverFull() {
        ManeuverProgressTracker tracker = new ManeuverProgressTracker();

        observe(tracker, 300f);
        observe(tracker, 150f);
        observe(tracker, 40f);
        ManeuverProgressTracker.Result second = observe(tracker, 300f);

        assertTrue(second.newEvent);
        assertEquals(9, second.bucket);
        assertEquals("distance_rollover", second.reason);
    }

    @Test
    public void sparseHandoffAfterRealCountdownStartsSecondManeuver() {
        ManeuverProgressTracker tracker = new ManeuverProgressTracker();

        observe(tracker, 1000f);
        observe(tracker, 120f);
        ManeuverProgressTracker.Result second = observe(tracker, 300f);

        assertTrue(second.newEvent);
        assertEquals(9, second.bucket);
    }

    @Test
    public void transientZeroOneAndUnknownDistanceHoldWithoutMutatingEvent() {
        ManeuverProgressTracker tracker = new ManeuverProgressTracker();

        observe(tracker, 300f);
        int before = observe(tracker, 150f).bucket;
        ManeuverProgressTracker.Result zero = observe(tracker, 0f);
        ManeuverProgressTracker.Result one = observe(tracker, 1f);
        ManeuverProgressTracker.Result unknown = observe(tracker, Float.NaN);

        assertTrue(zero.hold);
        assertEquals(before, zero.bucket);
        assertTrue(one.hold);
        assertEquals(before, one.bucket);
        assertTrue(unknown.hold);
        assertEquals(before, unknown.bucket);
        assertEquals(before, tracker.current("route-1", "right").bucket);
    }

    @Test
    public void microReadsAndTransientSamplesCannotPoisonSameFamilyHandoff() {
        ManeuverProgressTracker tracker = new ManeuverProgressTracker();

        observe(tracker, 300f);
        int beforeMicro = observe(tracker, 200f).bucket;
        assertEquals(beforeMicro, tracker.current("route-1", "right").bucket);
        assertEquals(beforeMicro, tracker.current("route-1", "right").bucket);
        assertEquals(beforeMicro, observe(tracker, 0f).bucket);
        assertEquals(beforeMicro, observe(tracker, 1f).bucket);
        assertEquals(0, observe(tracker, 40f).bucket);

        ManeuverProgressTracker.Result next = observe(tracker, 300f);
        assertTrue(next.newEvent);
        assertEquals(9, next.bucket);
    }

    @Test
    public void directionalGlyphRefinementDoesNotRefillWithoutDistanceRollover() {
        ManeuverProgressTracker tracker = new ManeuverProgressTracker();

        tracker.observeMain("route-1", "right", 300f);
        int before = tracker.observeMain("route-1", "right", 200f).bucket;
        ManeuverProgressTracker.Result refined =
                tracker.observeMain("route-1", "exit_right", 190f);

        assertFalse(refined.newEvent);
        assertTrue(refined.bucket <= before);
    }

    @Test
    public void trueDirectionChangeStartsAFullEvent() {
        ManeuverProgressTracker tracker = new ManeuverProgressTracker();

        tracker.observeMain("route-1", "right", 300f);
        tracker.observeMain("route-1", "right", 100f);
        ManeuverProgressTracker.Result changed =
                tracker.observeMain("route-1", "left", 80f);

        assertTrue(changed.newEvent);
        assertEquals("family_changed", changed.reason);
        assertEquals(9, changed.bucket);
    }

    @Test
    public void numberedRoundaboutEnrichmentKeepsSameCountdown() {
        ManeuverProgressTracker tracker = new ManeuverProgressTracker();

        tracker.observeMain("route-1", "roundabout", 600f);
        int before = tracker.observeMain("route-1", "roundabout", 400f).bucket;
        ManeuverProgressTracker.Result numbered =
                tracker.observeMain("route-1", "roundabout", 390f);

        assertFalse(numbered.newEvent);
        assertTrue(numbered.bucket <= before);
    }

    @Test
    public void routeScopeChangeStartsFullAndResetDropsHeldState() {
        ManeuverProgressTracker tracker = new ManeuverProgressTracker();

        tracker.observeMain("route-1", "right", 300f);
        tracker.observeMain("route-1", "right", 100f);
        ManeuverProgressTracker.Result nextRoute =
                tracker.observeMain("route-2", "right", 80f);

        assertTrue(nextRoute.newEvent);
        assertEquals(9, nextRoute.bucket);

        tracker.reset();
        assertFalse(tracker.current("route-2", "right").hasValue);
        assertNewFull(tracker, 70f);
    }

    @Test
    public void repeatedShortFirstDistanceDoesNotCollapseToZero() {
        ManeuverProgressTracker tracker = new ManeuverProgressTracker();

        assertEquals(9, observe(tracker, 40f).bucket);
        assertEquals(9, observe(tracker, 40f).bucket);
        int atThirty = observe(tracker, 30f).bucket;
        assertTrue(atThirty > 0);
        assertTrue(atThirty < 9);
        assertEquals(atThirty, observe(tracker, 0f).bucket);
    }

    @Test
    public void coreBridgeBounceWaitsForExplicitUpstreamConfirmation() {
        ManeuverProgressTracker tracker = new ManeuverProgressTracker();

        observe(tracker, 300f);
        int atOneHundred = observe(tracker, 100f).bucket;
        ManeuverProgressTracker.Result bounce =
                tracker.observeMain("route-1", "right", 180f,
                        false, false);
        ManeuverProgressTracker.Result recovered =
                tracker.observeMain("route-1", "right", 105f,
                        false, false);

        assertFalse(bounce.newEvent);
        assertTrue(bounce.hold);
        assertEquals(atOneHundred, bounce.bucket);
        assertFalse(recovered.newEvent);
        assertTrue(recovered.bucket <= atOneHundred);
    }

    @Test
    public void explicitBridgeRolloverStartsFullWithoutASecondTrackerCandidate() {
        ManeuverProgressTracker tracker = new ManeuverProgressTracker();

        observe(tracker, 300f);
        observe(tracker, 40f);
        ManeuverProgressTracker.Result next =
                tracker.observeMain("route-1", "right", 290f, true);

        assertTrue(next.newEvent);
        assertEquals("explicit_rollover", next.reason);
        assertEquals(9, next.bucket);
    }

    @Test
    public void currentRejectsDifferentManeuverFamily() {
        ManeuverProgressTracker tracker = new ManeuverProgressTracker();

        tracker.observeMain("route-1", "right", 300f);

        assertFalse(tracker.current("route-1", "left").hasValue);
        assertTrue(tracker.current("route-1", "exit_right").hasValue);
    }

    private static ManeuverProgressTracker.Result observe(ManeuverProgressTracker tracker,
                                                           float meters) {
        return tracker.observeMain("route-1", "right", meters);
    }

    private static void assertNewFull(ManeuverProgressTracker tracker, float meters) {
        ManeuverProgressTracker.Result result =
                tracker.observeMain("route-1", "right", meters);
        assertTrue(result.hasValue);
        assertTrue(result.newEvent);
        assertEquals(9, result.bucket);
    }
}
