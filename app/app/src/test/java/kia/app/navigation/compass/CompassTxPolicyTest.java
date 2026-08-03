package kia.app.navigation.compass;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public final class CompassTxPolicyTest {
    @Test
    public void startupGraceExitReassertsStoredStep() {
        assertFalse(CompassTxPolicy.shouldReassert(false, false, true, 4L, 4L));
        assertTrue(CompassTxPolicy.shouldReassert(true, false, true, 4L, 4L));
    }

    @Test
    public void navigationActiveToInactiveReassertsEvenWithSameHeading() {
        assertFalse(CompassTxPolicy.shouldReassert(false, true, true, 4L, 4L));
        assertTrue(CompassTxPolicy.shouldReassert(true, false, true, 4L, 4L));
    }

    @Test
    public void newUsbConnectionEpochReassertsWithoutHeadingChange() {
        assertTrue(CompassTxPolicy.shouldReassert(true, true, true, 5L, 4L));
        assertFalse(CompassTxPolicy.shouldReassert(true, true, true, 5L, 5L));
        assertFalse(CompassTxPolicy.shouldReassert(true, true, false, 5L, 4L));
    }

    @Test
    public void keepAliveIsBoundedAndRequiresCurrentCompassOwnership() {
        assertFalse(CompassTxPolicy.shouldKeepAlive(
                true, true, 14_999L, 10_000L, 5000L));
        assertTrue(CompassTxPolicy.shouldKeepAlive(
                true, true, 15_000L, 10_000L, 5000L));
        assertFalse(CompassTxPolicy.shouldKeepAlive(
                false, true, 20_000L, 10_000L, 5000L));
        assertFalse(CompassTxPolicy.shouldKeepAlive(
                true, false, 20_000L, 10_000L, 5000L));
        assertFalse(CompassTxPolicy.shouldKeepAlive(
                true, true, 20_000L, 0L, 5000L));
    }

    @Test
    public void failedSameStepRetryIsRateLimitedButNewStepIsImmediate() {
        assertFalse(CompassTxPolicy.retryAllowed(
                12, 12, 10_999L, 10_000L, 1000L));
        assertTrue(CompassTxPolicy.retryAllowed(
                12, 12, 11_000L, 10_000L, 1000L));
        assertTrue(CompassTxPolicy.retryAllowed(
                15, 12, 10_001L, 10_000L, 1000L));
    }

    @Test
    public void startupCanRestoreLatestPersistedCompassStepWithoutNewHeading() {
        String tx = "compass step=9 bytes=old outcome=WRITTEN\n"
                + "speedLimit=60 bytes=speed outcome=WRITTEN\n"
                + "compass step=15 bytes=new outcome=WRITTEN";

        assertEquals(15, CompassTxPolicy.storedStep(tx));
        assertEquals(0, CompassTxPolicy.storedStep("compass step=35 outcome=WRITTEN"));
        assertEquals(-1, CompassTxPolicy.storedStep("maneuver right"));
        assertEquals(-1, CompassTxPolicy.storedStep("compass step=bad"));
    }
}
