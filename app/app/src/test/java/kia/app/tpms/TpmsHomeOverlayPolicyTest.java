package kia.app.tpms;

import java.util.Locale;

import kia.app.core.model.TpmsState;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public final class TpmsHomeOverlayPolicyTest {
    @Test
    public void disconnectedSnapshotClearsValuesEvenWhenCallerRetainsLastHardwareProjection() {
        TpmsHomeOverlayPolicy.Snapshot snapshot = capture(freshProjection(), false, true, 1000);
        for (TpmsHomeOverlayPolicy.Wheel wheel : snapshot.wheels) {
            assertEquals("—", wheel.pressure);
            assertEquals("—", wheel.temperature);
            assertFalse(wheel.alert);
        }
        assertFalse(snapshot.hasValues());
        assertTrue(snapshot.visible(1000, true));
    }

    @Test
    public void wheelIdentitySurvivesPartialAndReorderedInput() {
        TeyesTpmsWidgetPolicy.WheelValue[] values = freshProjection();
        TpmsHomeOverlayPolicy.Snapshot snapshot = capture(
                new TeyesTpmsWidgetPolicy.WheelValue[]{values[3], null, values[0]}, true, true, 1000);
        assertEquals("2.40", snapshot.wheels[0].pressure);
        assertEquals("20", snapshot.wheels[0].temperature);
        assertEquals("—", snapshot.wheels[1].pressure);
        assertEquals("—", snapshot.wheels[2].temperature);
        assertEquals("2.43", snapshot.wheels[3].pressure);
        assertEquals("23", snapshot.wheels[3].temperature);
    }

    @Test
    public void inputArrayMutationCannotChangeCapturedDisplay() {
        TeyesTpmsWidgetPolicy.WheelValue[] values = freshProjection();
        TpmsHomeOverlayPolicy.Snapshot snapshot = capture(values, true, true, 1000);
        values[0] = TeyesTpmsWidgetPolicy.WheelValue.unknown(0);
        assertEquals("2.40", snapshot.wheels[0].pressure);
    }

    @Test
    public void staleHardwareStaysUnknownEvenWithFreshHomeDecision() {
        TeyesTpmsWidgetPolicy policy = hardwarePolicy();
        TeyesTpmsWidgetPolicy.WheelValue[] expired = project(policy, 181001);
        TpmsHomeOverlayPolicy.Snapshot snapshot = capture(expired, true, true, 181001);
        assertTrue(snapshot.visible(181001, true));
        assertFalse(snapshot.hasValues());
        for (TpmsHomeOverlayPolicy.Wheel wheel : snapshot.wheels) {
            assertEquals("—", wheel.pressure);
            assertEquals("—", wheel.temperature);
        }
    }

    @Test
    public void pressureRemainsVisibleWhenOnlyTemperatureIsUnknown() {
        TeyesTpmsWidgetPolicy policy = new TeyesTpmsWidgetPolicy();
        policy.observe(TpmsState.empty().withWheelAt(0, 245, 0, false, 0, "USB", 1000),
                1, 1000);
        TpmsHomeOverlayPolicy.Snapshot snapshot = capture(project(policy, 1000), true, true, 1000);
        assertEquals("2.45", snapshot.wheels[0].pressure);
        assertEquals("—", snapshot.wheels[0].temperature);
        assertTrue(snapshot.hasValues());
    }

    @Test
    public void actualZeroAndNegativeTemperaturesAreNotMissingValues() {
        TeyesTpmsWidgetPolicy policy = new TeyesTpmsWidgetPolicy();
        TpmsState state = TpmsState.empty().withWheelAt(0, 245, 0, 0, "USB", 1000)
                .withWheelAt(1, 245, -12, 0, "USB", 1000);
        policy.observe(state, 3, 1000);
        TpmsHomeOverlayPolicy.Snapshot snapshot = capture(project(policy, 1000), true, true, 1000);
        assertEquals("0", snapshot.wheels[0].temperature);
        assertEquals("-12", snapshot.wheels[1].temperature);
    }

    @Test
    public void leavingHomeOrLosingPermissionHidesFreshValues() {
        assertFalse(capture(freshProjection(), true, false, 1000).visible(1000, true));
        assertFalse(capture(freshProjection(), true, true, 1000).visible(1000, false));
    }

    @Test
    public void missingForegroundRefreshFailsClosedAtWatchdogBoundary() {
        TpmsHomeOverlayPolicy.Snapshot snapshot = capture(freshProjection(), true, true, 1000);
        assertTrue(snapshot.visible(3499, true));
        assertFalse(snapshot.visible(3500, true));
        assertFalse(snapshot.visible(999, true));
        assertFalse(capture(freshProjection(), true, true, -1).visible(0, true));
    }

    @Test
    public void nullProjectionShowsExplicitUnknownsWithoutInventedValues() {
        TpmsHomeOverlayPolicy.Snapshot snapshot = capture(null, true, true, 1000);
        assertFalse(snapshot.hasValues());
        for (TpmsHomeOverlayPolicy.Wheel wheel : snapshot.wheels) {
            assertEquals("—", wheel.pressure);
            assertEquals("—", wheel.temperature);
            assertEquals("бар", wheel.pressureUnit);
            assertEquals("°C", wheel.temperatureUnit);
        }
    }

    @Test
    public void formattingRemainsStableWithCommaDecimalDeviceLocale() {
        Locale previous = Locale.getDefault();
        try {
            Locale.setDefault(Locale.GERMANY);
            assertEquals("2.40", capture(freshProjection(), true, true, 1000).wheels[0].pressure);
        } finally {
            Locale.setDefault(previous);
        }
    }

    @Test
    public void dragPositionResizesWithinSafeAreaAndClampsInvalidSavedValues() {
        float fraction = TpmsHomeOverlayPolicy.fraction(410, 10, 810);
        assertEquals(210, TpmsHomeOverlayPolicy.position(fraction, 10, 410));
        assertEquals(132, TpmsHomeOverlayPolicy.position(-1f, 132, 600));
        assertEquals(600, TpmsHomeOverlayPolicy.position(2f, 132, 600));
        assertEquals(132, TpmsHomeOverlayPolicy.position(Float.NaN, 132, 600));
        assertEquals(0f, TpmsHomeOverlayPolicy.fraction(100, 132, 132), 0f);
        assertEquals(132, TpmsHomeOverlayPolicy.position(0.5f, 132, 80));
    }

    private static TpmsHomeOverlayPolicy.Snapshot capture(TeyesTpmsWidgetPolicy.WheelValue[] values,
                                                          boolean connected, boolean home, long now) {
        return TpmsHomeOverlayPolicy.capture(values, connected, home, now);
    }

    private static TeyesTpmsWidgetPolicy hardwarePolicy() {
        TeyesTpmsWidgetPolicy policy = new TeyesTpmsWidgetPolicy();
        TpmsState state = TpmsState.empty();
        for (int wheel = 0; wheel < 4; wheel++) {
            state = state.withWheelAt(wheel, 240 + wheel, 20 + wheel, 0, "USB", 1000);
        }
        policy.observe(state, 15, 1000);
        return policy;
    }

    private static TeyesTpmsWidgetPolicy.WheelValue[] freshProjection() {
        return project(hardwarePolicy(), 1000);
    }

    private static TeyesTpmsWidgetPolicy.WheelValue[] project(TeyesTpmsWidgetPolicy policy, long now) {
        TeyesTpmsWidgetPolicy.WheelValue[] values = new TeyesTpmsWidgetPolicy.WheelValue[4];
        for (int wheel = 0; wheel < 4; wheel++) values[wheel] = policy.project(wheel, now, true, 220, 300);
        return values;
    }
}
