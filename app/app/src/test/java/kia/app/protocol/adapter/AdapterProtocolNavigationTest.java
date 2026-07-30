package kia.app.protocol.adapter;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public final class AdapterProtocolNavigationTest {
    @Test
    public void numberedRoundaboutsKeepYellowExitSelectorInNormalMode() {
        assertNumberedRoundaboutSelectors(false);
    }

    @Test
    public void numberedRoundaboutsKeepYellowExitSelectorInTbtMode() {
        assertNumberedRoundaboutSelectors(true);
    }

    @Test
    public void genericRoundaboutUsesModeSpecificEncoding() {
        byte[] normal = AdapterProtocol.maneuver(
                "context_ra_in_circular_movement", 120f, false, false, 3);
        byte[] tbt = AdapterProtocol.maneuver(
                "context_ra_in_circular_movement", 120f, false, true, 3);

        assertEquals(0x20, normal[5] & 0xff);
        assertEquals(0x08, normal[6] & 0xff);
        assertEquals(0x11, normal[7] & 0xff);
        assertEquals(0xff, normal[8] & 0xff);
        assertEquals(0x60, tbt[5] & 0xff);
        assertEquals(0x00, tbt[6] & 0xff);
        assertEquals(0x00, tbt[7] & 0xff);
        assertEquals(0x00, tbt[8] & 0xff);
        assertTrue(AdapterProtocol.validChecksum(normal));
        assertTrue(AdapterProtocol.validChecksum(tbt));
    }

    @Test
    public void normalModeDistinguishesAngledTakeFromRegularTurn() {
        byte[] takeRight = maneuver("context_ra_take_right", false);
        byte[] turnRight = maneuver("context_ra_turn_right", false);
        byte[] exitRight = maneuver("context_ra_exit_right", false);
        byte[] takeLeft = maneuver("context_ra_take_left", false);
        byte[] turnLeft = maneuver("context_ra_turn_left", false);
        byte[] exitLeft = maneuver("context_ra_exit_left", false);

        assertEquals(0x13, takeRight[5] & 0xff);
        assertEquals(0x00, takeRight[8] & 0xff);
        assertEquals(0x0d, turnRight[5] & 0xff);
        assertEquals(0x0c, turnRight[8] & 0xff);
        assertEquals(0x1f, exitRight[5] & 0xff);
        assertEquals(0x00, exitRight[6] & 0xff);
        assertEquals(0x03, exitRight[7] & 0xff);
        assertEquals(0x0c, exitRight[8] & 0xff);

        assertEquals(0x14, takeLeft[5] & 0xff);
        assertEquals(0x00, takeLeft[8] & 0xff);
        assertEquals(0x0d, turnLeft[5] & 0xff);
        assertEquals(0x24, turnLeft[8] & 0xff);
        assertEquals(0x1f, exitLeft[5] & 0xff);
        assertEquals(0x20, exitLeft[6] & 0xff);
        assertEquals(0x00, exitLeft[7] & 0xff);
        assertEquals(0x24, exitLeft[8] & 0xff);
    }

    @Test
    public void tbtModeKeepsTakeTurnAndExitAsDistinctGlyphs() {
        assertEquals(0x42, maneuver("context_ra_take_right", true)[5] & 0xff);
        assertEquals(0x43, maneuver("context_ra_turn_right", true)[5] & 0xff);
        assertEquals(0x93, maneuver("context_ra_exit_right", true)[5] & 0xff);

        assertEquals(0x47, maneuver("context_ra_take_left", true)[5] & 0xff);
        assertEquals(0x46, maneuver("context_ra_turn_left", true)[5] & 0xff);
        assertEquals(0x95, maneuver("context_ra_exit_left", true)[5] & 0xff);
    }

    private static byte[] maneuver(String id, boolean tbt) {
        byte[] frame = AdapterProtocol.maneuver(id, 120f, false, tbt, 3);
        assertTrue(AdapterProtocol.validChecksum(frame));
        return frame;
    }

    private static void assertNumberedRoundaboutSelectors(boolean tbt) {
        int[] exitSelectors = {0x0C, 0x00, 0x24, 0x18};
        for (int exit = 1; exit <= 4; exit++) {
            byte[] frame = AdapterProtocol.maneuver(
                    "context_ra_roundabout_exit_" + exit, 120f, false, tbt, 3);

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

    @Test
    public void fullProgressUsesSameHighNibbleInNormalAndTbtModes() {
        byte[] normal = AdapterProtocol.maneuver(
                "context_ra_turn_right", 300f, false, false, 9);
        byte[] tbt = AdapterProtocol.maneuver(
                "context_ra_turn_right", 300f, false, true, 9);
        byte[] normalGray = AdapterProtocol.maneuverWithGrayRoad(
                "context_ra_turn_right", "context_ra_gray_straight_right",
                300f, false, false, 9);
        byte[] tbtGray = AdapterProtocol.maneuverWithGrayRoad(
                "context_ra_turn_right", "context_ra_gray_straight_right",
                300f, false, true, 9);

        assertEquals(0x90, normal[12] & 0xf0);
        assertEquals(0x90, tbt[12] & 0xf0);
        assertEquals(0x90, normalGray[12] & 0xf0);
        assertEquals(0x90, tbtGray[12] & 0xf0);
        assertTrue(AdapterProtocol.validChecksum(normal));
        assertTrue(AdapterProtocol.validChecksum(tbt));
        assertTrue(AdapterProtocol.validChecksum(normalGray));
        assertTrue(AdapterProtocol.validChecksum(tbtGray));
    }
}
