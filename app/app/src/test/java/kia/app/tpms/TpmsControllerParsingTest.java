package kia.app.tpms;

import kia.app.core.model.TpmsState;
import kia.app.protocol.adapter.AdapterProtocol;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

public final class TpmsControllerParsingTest {
    @Test
    public void nativeZeroSlotStalesOnlyThatWheel() {
        TpmsState current = TpmsState.empty()
                .withWheelAt(TpmsState.WHEEL_FL, 240, 20, 0, "old", 1_000L)
                .withWheelAt(TpmsState.WHEEL_FR, 241, 21, 0, "old", 1_000L)
                .withWheelAt(TpmsState.WHEEL_RL, 242, 22, 0, "old", 1_000L)
                .withWheelAt(TpmsState.WHEEL_RR, 243, 23, 0, "old", 1_000L);

        // Native payload order: FR, FL, RR, RL.
        byte[] frame = nativeFrame(
                new int[]{40, 0, 42, 0},
                new int[]{80, 81, 82, 83});
        TpmsState next = TpmsController.parseAdapterFrame(frame, current, 50_000L);

        assertNotNull(next);
        assertTrue(next.isWheelFresh(TpmsState.WHEEL_FR, 50_000L));
        assertTrue(next.isWheelFresh(TpmsState.WHEEL_RR, 50_000L));
        assertFalse(next.isWheelFresh(TpmsState.WHEEL_FL, 50_000L));
        assertFalse(next.isWheelFresh(TpmsState.WHEEL_RL, 50_000L));
        assertEquals(1_000L, next.wheelUpdatedAt[TpmsState.WHEEL_FL]);
        assertEquals(1_000L, next.wheelUpdatedAt[TpmsState.WHEEL_RL]);
        assertTrue(next.explicitlyStale[TpmsState.WHEEL_FL]);
        assertTrue(next.explicitlyStale[TpmsState.WHEEL_RL]);
        assertEquals(50_000L, next.wheelUpdatedAt[TpmsState.WHEEL_FR]);
        assertEquals(25, next.temperatureC[TpmsState.WHEEL_FR]);
        assertTrue(next.isTemperatureFresh(TpmsState.WHEEL_FR, 50_000L));
        assertFalse(next.isTemperatureFresh(TpmsState.WHEEL_FL, 50_000L));
    }

    @Test
    public void nativeUnknownAndOutOfRangeTemperaturesDoNotHideFreshPressure() {
        byte[] frame = nativeFrame(new int[]{148, 149, 150, 147},
                new int[]{0, 0xff, 216, 55});
        TpmsState next = TpmsController.parseAdapterFrame(frame, null, 10_000L);

        assertNotNull(next);
        for (int wheel = 0; wheel < 4; wheel++) assertTrue(next.isWheelFresh(wheel, 10_000L));
        assertFalse(next.temperatureKnown[TpmsState.WHEEL_FR]);
        assertFalse(next.temperatureKnown[TpmsState.WHEEL_FL]);
        assertFalse(next.temperatureKnown[TpmsState.WHEEL_RR]);
        assertTrue(next.temperatureKnown[TpmsState.WHEEL_RL]);
        assertEquals(0, next.temperatureC[TpmsState.WHEEL_RL]);
    }

    @Test
    public void nativeInvalidTemperatureClearsPreviousValueAndCanRecover() {
        TpmsState current = TpmsState.empty()
                .withWheelAt(TpmsState.WHEEL_FL, 250, 30, 0, "old", 1_000L);
        byte[] missing = nativeFrame(new int[]{148, 149, 150, 147}, new int[4]);
        TpmsState next = TpmsController.parseAdapterFrame(missing, current, 10_000L);
        assertFalse(next.temperatureKnown[TpmsState.WHEEL_FL]);
        assertTrue(next.isWheelFresh(TpmsState.WHEEL_FL, 10_000L));

        byte[] valid = nativeFrame(new int[]{148, 149, 150, 147}, new int[]{80, 81, 82, 83});
        next = TpmsController.parseAdapterFrame(valid, next, 20_000L);
        assertTrue(next.isTemperatureFresh(TpmsState.WHEEL_FL, 20_000L));
        assertEquals(26, next.temperatureC[TpmsState.WHEEL_FL]);
    }

    @Test
    public void legacySignedZeroAndMinusOneAreValidButOutOfRangeIsUnknown() {
        byte[] frame = legacyFrame(15, new int[]{250, 251, 252, 253},
                new int[]{0, -1, -128, -61});
        TpmsState next = TpmsController.parseAdapterFrame(frame, null, 10_000L);

        assertNotNull(next);
        assertTrue(next.temperatureKnown[0]);
        assertTrue(next.temperatureKnown[1]);
        assertEquals(0, next.temperatureC[0]);
        assertEquals(-1, next.temperatureC[1]);
        assertFalse(next.temperatureKnown[2]);
        assertFalse(next.temperatureKnown[3]);
        for (int wheel = 0; wheel < 4; wheel++) assertTrue(next.isWheelFresh(wheel, 10_000L));
    }

    @Test
    public void legacyPartialMaskDoesNotRefreshOtherTemperatures() {
        TpmsState current = TpmsState.empty()
                .withWheelAt(0, 250, 30, 0, "old", 1_000L)
                .withWheelAt(1, 250, 31, 0, "old", 1_000L);
        byte[] frame = legacyFrame(2, new int[]{300, 255, 0, 0}, new int[]{99, 32, 0, 0});
        TpmsState next = TpmsController.parseAdapterFrame(frame, current, 150_000L);

        assertEquals(30, next.temperatureC[0]);
        assertEquals(1_000L, next.wheelUpdatedAt[0]);
        assertEquals(32, next.temperatureC[1]);
        assertFalse(next.isTemperatureFresh(0, 181_001L));
        assertTrue(next.isTemperatureFresh(1, 181_001L));
    }

    @Test
    public void legacyInvalidPressureDoesNotBecomeClampedFreshWheel() {
        TpmsState current = TpmsState.empty()
                .withWheelAt(0, 250, 30, 0, "old", 1_000L);
        byte[] frame = legacyFrame(1, new int[]{0xffff, 0, 0, 0}, new int[]{31, 0, 0, 0});
        TpmsState next = TpmsController.parseAdapterFrame(frame, current, 10_000L);

        assertEquals(250, next.pressureKpa[0]);
        assertEquals(1_000L, next.wheelUpdatedAt[0]);
        assertFalse(next.isWheelFresh(0, 10_000L));
        assertFalse(next.isTemperatureFresh(0, 10_000L));
    }

    @Test
    public void legacyZeroPressureRemainsAnActualMeasurement() {
        TpmsState next = TpmsController.parseAdapterFrame(
                legacyFrame(1, new int[4], new int[]{20, 0, 0, 0}), null, 10_000L);
        assertTrue(next.isWheelFresh(0, 10_000L));
        assertEquals(0, next.pressureKpa[0]);
        assertTrue(next.isTemperatureFresh(0, 10_000L));
    }

    private static byte[] nativeFrame(int[] pressure, int[] temperature) {
        byte[] frame = new byte[14];
        frame[0] = (byte) 0xbb;
        frame[1] = 0x41;
        frame[2] = (byte) 0xa1;
        frame[3] = 14;
        frame[4] = (byte) AdapterProtocol.CMD_TPMS;
        for (int index = 0; index < 4; index++) {
            frame[5 + index] = (byte) pressure[index];
            frame[9 + index] = (byte) temperature[index];
        }
        return frame;
    }

    private static byte[] legacyFrame(int mask, int[] pressure, int[] temperature) {
        byte[] frame = new byte[23];
        frame[0] = (byte) 0xbb;
        frame[1] = 0x41;
        frame[2] = (byte) 0xa1;
        frame[3] = 23;
        frame[4] = (byte) AdapterProtocol.CMD_TPMS_LEGACY;
        frame[5] = (byte) mask;
        for (int wheel = 0; wheel < 4; wheel++) {
            int offset = 6 + wheel * 4;
            frame[offset] = (byte) pressure[wheel];
            frame[offset + 1] = (byte) (pressure[wheel] >>> 8);
            frame[offset + 2] = (byte) temperature[wheel];
        }
        return frame;
    }
}
