package kia.app.protocol.adapter;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public final class AdapterProtocolNavigationTest {
    @Test
    public void numberedRoundaboutsUseVerifiedTbtExitGlyph() {
        for (int exit = 1; exit <= 4; exit++) {
            byte[] frame = AdapterProtocol.maneuver(
                    "context_ra_roundabout_exit_" + exit, 120f, false, true, 3);

            assertEquals(AdapterProtocol.CMD_MANEUVER, frame[4] & 0xff);
            assertEquals(0x61, frame[5] & 0xff);
            assertTrue(AdapterProtocol.validChecksum(frame));
        }
    }
}
