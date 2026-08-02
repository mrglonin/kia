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
}
