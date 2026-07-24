package kia.app.protocol.adapter;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public final class AdapterProtocolNavigationTest {
    @Test
    public void numberedRoundaboutsKeepYellowExitSelectorInTbtMode() {
        int[] exitSelectors = {0x0C, 0x00, 0x24, 0x18};
        for (int exit = 1; exit <= 4; exit++) {
            byte[] frame = AdapterProtocol.maneuver(
                    "context_ra_roundabout_exit_" + exit, 120f, false, true, 3);

            assertEquals(AdapterProtocol.CMD_MANEUVER, frame[4] & 0xff);
            assertEquals(0x20, frame[5] & 0xff);
            assertEquals(0x08, frame[6] & 0xff);
            assertEquals(0x11, frame[7] & 0xff);
            assertEquals(exitSelectors[exit - 1], frame[8] & 0xff);
            assertTrue(AdapterProtocol.validChecksum(frame));
        }
    }

    @Test
    public void genericRoundaboutExitKeepsVerifiedTbtGlyph() {
        byte[] frame = AdapterProtocol.maneuver(
                "context_ra_out_circular_movement", 120f, false, true, 3);

        assertEquals(AdapterProtocol.CMD_MANEUVER, frame[4] & 0xff);
        assertEquals(0x61, frame[5] & 0xff);
        assertEquals(0x00, frame[6] & 0xff);
        assertEquals(0x00, frame[7] & 0xff);
        assertEquals(0x00, frame[8] & 0xff);
        assertTrue(AdapterProtocol.validChecksum(frame));
    }

    @Test
    public void microIconCanCarryMainDistanceAndProgressAsOneCounter() {
        byte[] frame = AdapterProtocol.maneuver(
                "context_ra_turn_right", 990f, false, false, 3);

        assertEquals(0x03, frame[9] & 0xff);
        assertEquals(0xde, frame[10] & 0xff);
        assertEquals(0x00, frame[11] & 0xff);
        assertEquals(0x30, frame[12] & 0xf0);
        assertTrue(AdapterProtocol.validChecksum(frame));
    }
}
