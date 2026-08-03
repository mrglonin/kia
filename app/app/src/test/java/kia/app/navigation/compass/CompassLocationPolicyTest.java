package kia.app.navigation.compass;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public final class CompassLocationPolicyTest {
    @Test
    public void stationaryGpsBearingIsRejectedButMovingNorthIsValid() {
        assertFalse(CompassLocationPolicy.usableGpsBearing(true, 0f, true, 0f));
        assertFalse(CompassLocationPolicy.usableGpsBearing(true, 275f, true, 1.39f));
        assertTrue(CompassLocationPolicy.usableGpsBearing(true, 0f, true, 1.4f));
        assertFalse(CompassLocationPolicy.usableGpsBearing(true, 275f, false, 0f));
    }

    @Test
    public void invalidOrMissingBearingIsRejected() {
        assertFalse(CompassLocationPolicy.usableGpsBearing(false, 90f, true, 10f));
        assertFalse(CompassLocationPolicy.usableGpsBearing(true, Float.NaN, true, 10f));
        assertFalse(CompassLocationPolicy.usableGpsBearing(
                true, Float.POSITIVE_INFINITY, true, 10f));
    }

    @Test
    public void lastKnownUsesMonotonicAgeWhenAvailable() {
        long now = 20_000_000_000L;
        assertTrue(CompassLocationPolicy.freshLastKnown(
                now, now - 2_500_000_000L, 2500L));
        assertFalse(CompassLocationPolicy.freshLastKnown(
                now, now - 2_500_000_001L, 2500L));
        assertFalse(CompassLocationPolicy.freshLastKnown(
                now, now + 1L, 2500L));
    }

    @Test
    public void lastKnownWithoutMonotonicTimestampIsNotTrusted() {
        assertFalse(CompassLocationPolicy.freshLastKnown(
                20_000_000_000L, 0L, 2500L));
        assertFalse(CompassLocationPolicy.freshLastKnown(
                0L, 10_000_000_000L, 2500L));
    }

    @Test
    public void identicalCachedFixIsConsumedOnlyOnce() {
        assertTrue(CompassLocationPolicy.sameLastKnownSample(123L, 123L));
        assertFalse(CompassLocationPolicy.sameLastKnownSample(0L, 0L));
        assertFalse(CompassLocationPolicy.sameLastKnownSample(124L, 123L));
    }

    @Test
    public void sensorEventCanSupplyMissingInitialAccuracyWithoutFalseDowngrade() {
        assertTrue(CompassLocationPolicy.effectiveSensorAccuracy(0, 2) > 0);
        assertTrue(CompassLocationPolicy.effectiveSensorAccuracy(3, 0) > 0);
        assertFalse(CompassLocationPolicy.effectiveSensorAccuracy(0, 0) > 0);
    }

    @Test
    public void finiteSensorHeadingRemainsUsableWhenVendorAccuracyIsUnknown() {
        assertTrue(CompassLocationPolicy.usableSensorHeading(127.5f, 0));
        assertFalse(CompassLocationPolicy.usableSensorHeading(Float.NaN, 3));
        assertFalse(CompassLocationPolicy.usableSensorHeading(
                Float.POSITIVE_INFINITY, 0));
    }

    @Test
    public void registeredSensorWithoutUsableHeadingIsRecoveredAfterCooldown() {
        assertFalse(CompassLocationPolicy.sensorStreamNeedsRecovery(
                true, 12_999L, 10_000L, 0L,
                3000L, 0L, 15_000L));
        assertTrue(CompassLocationPolicy.sensorStreamNeedsRecovery(
                true, 13_000L, 10_000L, 0L,
                3000L, 0L, 15_000L));
        assertFalse(CompassLocationPolicy.sensorStreamNeedsRecovery(
                true, 20_000L, 10_000L, 12_500L,
                3000L, 13_000L, 15_000L));
        assertTrue(CompassLocationPolicy.sensorStreamNeedsRecovery(
                true, 28_000L, 10_000L, 12_500L,
                3000L, 13_000L, 15_000L));
        assertFalse(CompassLocationPolicy.sensorStreamNeedsRecovery(
                false, 28_000L, 10_000L, 12_500L,
                3000L, 0L, 15_000L));
    }

    @Test
    public void unknownAccuracySensorYieldsBrieflyToFreshMovingGpsCourse() {
        assertFalse(CompassLocationPolicy.sensorMayDriveCluster(
                0, 12_500L, 10_000L, 2500L));
        assertTrue(CompassLocationPolicy.sensorMayDriveCluster(
                0, 12_501L, 10_000L, 2500L));
        assertTrue(CompassLocationPolicy.sensorMayDriveCluster(
                2, 10_001L, 10_000L, 2500L));
        assertTrue(CompassLocationPolicy.sensorMayDriveCluster(
                0, 10_001L, 0L, 2500L));
    }
}
