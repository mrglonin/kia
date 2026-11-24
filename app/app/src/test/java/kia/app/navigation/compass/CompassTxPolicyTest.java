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

        assertEquals(15, CompassTxPolicy.storedStep(tx, false));
        assertEquals(0, CompassTxPolicy.storedStep("compass step=35 outcome=WRITTEN", false));
        assertEquals(-1, CompassTxPolicy.storedStep("maneuver right", false));
        assertEquals(-1, CompassTxPolicy.storedStep("compass step=bad outcome=WRITTEN", false));
    }

    @Test
    public void restoreRequiresLastDeliveredDirectionInSelectedFormat() {
        String legacy = "compass step=27 bytes=legacy outcome=WRITTEN";
        String kiaNavi = "compass step=36 format=kia-navi bytes=kia-navi outcome=WRITTEN";
        String blocked = "compass step=12 bytes=legacy outcome=BLOCKED";

        assertEquals(-1, CompassTxPolicy.storedStep(legacy, true));
        assertEquals(36, CompassTxPolicy.storedStep(legacy + "\n" + kiaNavi + "\n" + blocked, true));
        assertEquals(-1, CompassTxPolicy.storedStep(legacy + "\n" + kiaNavi + "\n" + blocked, false));
        assertEquals(27, CompassTxPolicy.storedStep(kiaNavi + "\n" + legacy, false));
        assertEquals(-1, CompassTxPolicy.storedStep(kiaNavi + "\n" + legacy, true));
    }

    @Test
    public void sectorRoundingUsesTheOriginalHeadingInBothFormats() {
        assertEquals(0, CompassTxPolicy.headingStep(12f, false));
        assertEquals(3, CompassTxPolicy.headingStep(12f, true));
        assertEquals(0, CompassTxPolicy.headingStep(11.24f, true));
        assertEquals(3, CompassTxPolicy.headingStep(11.25f, true));
        assertEquals(45, CompassTxPolicy.headingStep(-22.5f, true));
        assertEquals(0, CompassTxPolicy.headingStep(359f, true));
        for (int sector = 0; sector < 16; sector++) {
            assertEquals(sector * 3, CompassTxPolicy.headingStep(sector * 22.5f, true));
        }
    }
}
