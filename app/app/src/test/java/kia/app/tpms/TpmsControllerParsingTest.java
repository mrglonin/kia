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
}
