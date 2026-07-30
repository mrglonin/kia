package kia.app.navigation.domain;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public final class ManeuverProgressRolloverPolicyTest {
    @Test
    public void strictPostPassJumpStartsNextEvent() {
        assertTrue(ManeuverProgressRolloverPolicy.shouldStartNewEvent(
                300f, 40f, 300f));
        assertTrue(ManeuverProgressRolloverPolicy.shouldStartNewEvent(
                300f, 50f, 100f));
    }

    @Test
    public void sparseProviderHandoffRequiresRealCountdownAndLargeJump() {
        assertTrue(ManeuverProgressRolloverPolicy.shouldStartNewEvent(
                1000f, 120f, 300f));
        assertFalse(ManeuverProgressRolloverPolicy.shouldStartNewEvent(
                100f, 100f, 180f));
        assertFalse(ManeuverProgressRolloverPolicy.shouldStartNewEvent(
                300f, 140f, 180f));
    }

    @Test
    public void ordinaryNoiseAndFarJumpDoNotStartNewEvent() {
        assertFalse(ManeuverProgressRolloverPolicy.shouldStartNewEvent(
                300f, 300f, 310f));
        assertFalse(ManeuverProgressRolloverPolicy.shouldStartNewEvent(
                1000f, 700f, 1100f));
        assertFalse(ManeuverProgressRolloverPolicy.shouldStartNewEvent(
                300f, 100f, 95f));
    }

    @Test
    public void invalidDistancesFailClosed() {
        assertFalse(ManeuverProgressRolloverPolicy.shouldStartNewEvent(
                0f, 40f, 300f));
        assertFalse(ManeuverProgressRolloverPolicy.shouldStartNewEvent(
                300f, Float.NaN, 300f));
        assertFalse(ManeuverProgressRolloverPolicy.shouldStartNewEvent(
                300f, 40f, Float.POSITIVE_INFINITY));
    }

    @Test
    public void secondConsistentSampleConfirmsPendingEvent() {
        assertTrue(ManeuverProgressRolloverPolicy.confirmsPendingEvent(
                40f, 300f, 290f, 10f));
        assertTrue(ManeuverProgressRolloverPolicy.confirmsPendingEvent(
                50f, 100f, 95f, 5f));
        assertTrue(ManeuverProgressRolloverPolicy.confirmsPendingEvent(
                120f, 300f, 250f, 25f));
    }

    @Test
    public void returnedOldDistanceAndUnrelatedSecondBounceDoNotConfirm() {
        assertFalse(ManeuverProgressRolloverPolicy.confirmsPendingEvent(
                100f, 180f, 105f, 5f));
        assertFalse(ManeuverProgressRolloverPolicy.confirmsPendingEvent(
                40f, 300f, 500f, 10f));
        assertFalse(ManeuverProgressRolloverPolicy.confirmsPendingEvent(
                40f, 300f, 290f, -1f));
    }
}
