package kia.app.tpms;

import java.util.Locale;

import kia.app.core.model.TpmsState;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public final class TeyesTpmsWidgetPolicyTest {
    @Test
    public void emptyProjectionClearsEachWheelWithByteIndexAndUnknownTemperature() {
        TeyesTpmsWidgetPolicy policy = new TeyesTpmsWidgetPolicy();
        for (int wheel = 0; wheel < 4; wheel++) {
            TeyesTpmsWidgetPolicy.WheelValue value = policy.project(wheel, 1000, true, 220, 300);
            assertEquals((byte) (wheel + 1), value.which);
            assertEquals("__", value.pressure);
            assertEquals("__", value.temperature);
            assertFalse(value.alert);
            assertEquals(0, value.warningState);
            assertEquals(1, value.pressureUnit);
            assertEquals(1, value.temperatureUnit);
        }
    }

    @Test
    public void nativeHardwareOrderProjectsToCorrectWidgetWheels() {
        byte[] frame = nativeFrame(new int[]{148, 149, 150, 147}, new int[]{91, 90, 89, 90});
        TpmsState state = TpmsController.parseAdapterFrame(frame, null, 1_000_000L);
        TeyesTpmsWidgetPolicy policy = new TeyesTpmsWidgetPolicy();
        policy.observe(state, TpmsController.observedWheelMask(frame), 5000);
        String[] expected = {"2.57", "2.55", "2.53", "2.59"};
        String[] expectedTemperature = {"35", "36", "35", "34"};
        for (int wheel = 0; wheel < 4; wheel++) {
            TeyesTpmsWidgetPolicy.WheelValue value = policy.project(wheel, 5000, true, 220, 300);
            assertEquals(expected[wheel], value.pressure);
            assertEquals(expectedTemperature[wheel], value.temperature);
            assertFalse(value.alert);
        }
    }

    @Test
    public void barFormattingIsIndependentOfDeviceLocale() {
        Locale previous = Locale.getDefault();
        try {
            Locale.setDefault(Locale.GERMANY);
            TeyesTpmsWidgetPolicy policy = new TeyesTpmsWidgetPolicy();
            policy.observe(wheel(0, 257, 35, 0), 1, 1000);
            assertEquals("2.57", policy.project(0, 1000, false, 220, 300).pressure);
        } finally {
            Locale.setDefault(previous);
        }
    }

    @Test
    public void freshnessExpiresAtOneMillisecondBeyond180Seconds() {
        TeyesTpmsWidgetPolicy policy = new TeyesTpmsWidgetPolicy();
        policy.observe(wheel(0, 257, 35, 0), 1, 1000);
        assertTrue(policy.isFresh(0, 181000));
        assertEquals(1, policy.nextExpiryDelayMs(181000));
        assertFalse(policy.isFresh(0, 181001));
        assertEquals(-1, policy.nextExpiryDelayMs(181001));
        assertEquals("__", policy.project(0, 181001, true, 220, 300).pressure);
        assertEquals("__", policy.project(0, 181001, true, 220, 300).temperature);
    }

    @Test
    public void repeatedProjectionDoesNotExtendSourceAge() {
        TeyesTpmsWidgetPolicy policy = new TeyesTpmsWidgetPolicy();
        policy.observe(wheel(0, 257, 35, 0), 1, 1000);
        for (long time = 1000; time <= 181000; time += 5000) {
            assertEquals("2.57", policy.project(0, time, false, 220, 300).pressure);
        }
        assertEquals("__", policy.project(0, 181001, false, 220, 300).pressure);
    }

    @Test
    public void partialHardwareMaskDoesNotImportUnobservedStateOrRefreshOtherWheels() {
        TeyesTpmsWidgetPolicy policy = new TeyesTpmsWidgetPolicy();
        policy.observe(wheel(0, 240, 20, 0), 1, 1000);
        TpmsState mixed = TpmsState.empty()
                .withWheelAt(0, 590, 100, 2, "qa", 200000)
                .withWheelAt(1, 255, 35, 0, "hardware", 200000);
        policy.observe(mixed, 2, 150000);
        assertEquals("2.40", policy.project(0, 150000, false, 220, 300).pressure);
        assertEquals("20", policy.project(0, 150000, false, 220, 300).temperature);
        assertEquals("__", policy.project(0, 181001, false, 220, 300).pressure);
        assertEquals("__", policy.project(0, 181001, false, 220, 300).temperature);
        assertEquals("2.55", policy.project(1, 181001, false, 220, 300).pressure);
        assertEquals("35", policy.project(1, 181001, false, 220, 300).temperature);
    }

    @Test
    public void nativeZeroSlotImmediatelyClearsOnlyThatWheel() {
        byte[] full = nativeFrame(new int[]{148, 149, 150, 147}, new int[4]);
        TpmsState state = TpmsController.parseAdapterFrame(full, null, 1000);
        TeyesTpmsWidgetPolicy policy = new TeyesTpmsWidgetPolicy();
        policy.observe(state, 15, 1000);
        byte[] partial = nativeFrame(new int[]{148, 0, 150, 147}, new int[4]);
        state = TpmsController.parseAdapterFrame(partial, state, 2000);
        policy.observe(state, 15, 2000);
        assertEquals("__", policy.project(0, 2000, true, 220, 300).pressure);
        assertFalse(policy.project(0, 2000, true, 220, 300).alert);
        assertEquals("2.55", policy.project(1, 2000, true, 220, 300).pressure);
    }

    @Test
    public void hardwareTemperatureWarningSurvivesMissingNumericTemperature() {
        TeyesTpmsWidgetPolicy policy = new TeyesTpmsWidgetPolicy();
        TpmsState unknownTemperature = TpmsState.empty()
                .withWheelAt(0, 250, 160, false, 0x04, "hardware", 123456);
        policy.observe(unknownTemperature, 1, 1000);
        TeyesTpmsWidgetPolicy.WheelValue value = policy.project(0, 1000, true, 220, 300);
        assertEquals("__", value.temperature);
        assertTrue(value.alert);
    }

    @Test
    public void verifiedTemperatureAndItsWarningAreForwarded() {
        TeyesTpmsWidgetPolicy policy = new TeyesTpmsWidgetPolicy();
        policy.observe(wheel(0, 250, 91, 0x04), 1, 1000);
        assertEquals("91", policy.project(0, 1000, true, 220, 300).temperature);
        assertTrue(policy.project(0, 1000, true, 220, 300).alert);
        assertFalse(policy.project(0, 1000, false, 220, 300).alert);
    }

    @Test
    public void validPressureWithMissingTemperatureClearsPreviousTemperature() {
        TeyesTpmsWidgetPolicy policy = new TeyesTpmsWidgetPolicy();
        policy.observe(wheel(0, 250, 31, 0), 1, 1000);
        TpmsState pressureOnly = TpmsState.empty()
                .withWheelAt(0, 255, 0, false, 0, "hardware", 123456);
        policy.observe(pressureOnly, 1, 2000);
        TeyesTpmsWidgetPolicy.WheelValue value = policy.project(0, 2000, true, 220, 300);
        assertEquals("2.55", value.pressure);
        assertEquals("__", value.temperature);
    }

    @Test
    public void nativePlaceholdersNeverProjectAsMinus55OrClamped160() {
        byte[] frame = nativeFrame(new int[]{148, 149, 150, 147}, new int[]{0, 0xff, 216, 55});
        TpmsState state = TpmsController.parseAdapterFrame(frame, null, 1000);
        TeyesTpmsWidgetPolicy policy = new TeyesTpmsWidgetPolicy();
        policy.observe(state, 15, 1000);
        assertEquals("__", policy.project(TpmsState.WHEEL_FR, 1000, true, 220, 300).temperature);
        assertEquals("__", policy.project(TpmsState.WHEEL_FL, 1000, true, 220, 300).temperature);
        assertEquals("__", policy.project(TpmsState.WHEEL_RR, 1000, true, 220, 300).temperature);
        assertEquals("0", policy.project(TpmsState.WHEEL_RL, 1000, true, 220, 300).temperature);
    }

    @Test
    public void pressureWarningsUsePressureOnlyAndRespectExistingAlertSwitch() {
        TeyesTpmsWidgetPolicy policy = new TeyesTpmsWidgetPolicy();
        policy.observe(wheel(0, 180, 20, 0), 1, 1000);
        assertTrue(policy.project(0, 1000, true, 220, 300).alert);
        assertFalse(policy.project(0, 1000, false, 220, 300).alert);
        policy.observe(wheel(0, 250, 20, 0x01), 1, 2000);
        assertTrue(policy.project(0, 2000, true, 220, 300).alert);
        policy.observe(wheel(0, 250, 20, 0x10), 1, 3000);
        assertFalse(policy.project(0, 3000, true, 220, 300).alert);
    }

    @Test
    public void expiredPressureWarningBecomesUnknownWithoutAlert() {
        TeyesTpmsWidgetPolicy policy = new TeyesTpmsWidgetPolicy();
        policy.observe(wheel(0, 180, 20, 0x08), 1, 1000);
        TeyesTpmsWidgetPolicy.WheelValue value = policy.project(0, 181001, true, 220, 300);
        assertEquals("__", value.pressure);
        assertFalse(value.alert);
    }

    @Test
    public void usbObservationRefreshesAgeButWallClockTimestampDoesNotControlTtl() {
        TeyesTpmsWidgetPolicy policy = new TeyesTpmsWidgetPolicy();
        TpmsState state = wheel(0, 257, 35, 0);
        policy.observe(state, 1, 1000);
        policy.observe(state, 1, 150000);
        assertTrue(policy.isFresh(0, 330000));
        assertFalse(policy.isFresh(0, 330001));
    }

    @Test
    public void elapsedZeroIsValidButFutureOrNegativeObservationIsNotFresh() {
        TeyesTpmsWidgetPolicy policy = new TeyesTpmsWidgetPolicy();
        policy.observe(wheel(0, 257, 35, 0), 1, 0);
        assertTrue(policy.isFresh(0, 0));
        assertTrue(policy.isFresh(0, 180000));
        policy.reset();
        policy.observe(wheel(0, 257, 35, 0), 1, -1);
        assertFalse(policy.hasFreshData(0));
        policy.observe(wheel(0, 257, 35, 0), 1, 1000);
        assertFalse(policy.isFresh(0, 999));
    }

    @Test
    public void resetRequiresNewHardwareObservation() {
        TeyesTpmsWidgetPolicy policy = new TeyesTpmsWidgetPolicy();
        policy.observe(wheel(0, 257, 35, 0), 1, 1000);
        policy.reset();
        assertFalse(policy.hasFreshData(1001));
        assertEquals("__", policy.project(0, 1001, true, 220, 300).pressure);
    }

    @Test
    public void parserMaskAcceptsNativeEmptySlotsButRejectsTruncatedFrames() {
        assertEquals(15, TpmsController.observedWheelMask(nativeFrame(new int[4], new int[4])));
        byte[] truncated = new byte[]{(byte) 0xbb, 0x41, (byte) 0xa1, 13, 0x51, 1};
        assertEquals(0, TpmsController.observedWheelMask(truncated));
        byte[] legacy = new byte[23];
        legacy[3] = 23;
        legacy[4] = 0x7b;
        legacy[5] = 10;
        assertEquals(10, TpmsController.observedWheelMask(legacy));
    }

    private static TpmsState wheel(int wheel, int pressure, int temperature, int flags) {
        return TpmsState.empty().withWheelAt(wheel, pressure, temperature, flags, "hardware", 123456);
    }

    private static byte[] nativeFrame(int[] pressure, int[] temperature) {
        byte[] frame = new byte[14];
        frame[0] = (byte) 0xbb;
        frame[1] = 0x41;
        frame[2] = (byte) 0xa1;
        frame[3] = 14;
        frame[4] = 0x51;
        for (int index = 0; index < 4; index++) {
            frame[5 + index] = (byte) pressure[index];
            frame[9 + index] = (byte) temperature[index];
        }
        return frame;
    }
}
