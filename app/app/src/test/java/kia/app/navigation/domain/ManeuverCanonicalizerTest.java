package kia.app.navigation.domain;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public final class ManeuverCanonicalizerTest {
    @Test
    public void angle180IsUturn() {
        assertManeuver("context_ra_turn_back_left", "LEFT180");
        assertManeuver("context_ra_turn_back_right", "RIGHT180");
        assertManeuver("context_ra_turn_back_left", "UTURN_LEFT");
        assertManeuver("context_ra_turn_back_right", "context_ra_turn_back_right");
        assertManeuver("context_ra_turn_back", "UTURN");
    }

    @Test
    public void angle135IsHardTurn() {
        assertManeuver("context_ra_hard_turn_left", "LEFT135");
        assertManeuver("context_ra_hard_turn_right", "RIGHT135");
        assertManeuver("context_ra_hard_turn_left", "SHARP_LEFT");
        assertManeuver("context_ra_hard_turn_right", "context_ra_hard_turn_right");
    }

    @Test
    public void angle45AndSoftBranchesAreTakeManeuvers() {
        String[] right = {
                "RIGHT45", "SLIGHT_RIGHT", "KEEP_RIGHT", "TAKE_RIGHT",
                "FORK_RIGHT", "RIGHT_SHIFT", "RIGHT_FROM_LEFT"
        };
        String[] left = {
                "LEFT45", "SLIGHT_LEFT", "KEEP_LEFT", "TAKE_LEFT",
                "FORK_LEFT", "LEFT_SHIFT", "LEFT_FROM_RIGHT"
        };
        for (String value : right) {
            assertManeuver("context_ra_take_right", value);
        }
        for (String value : left) {
            assertManeuver("context_ra_take_left", value);
        }
    }

    @Test
    public void explicitExitRampAndSlipRemainExits() {
        assertManeuver("context_ra_exit_right", "EXIT_RIGHT");
        assertManeuver("context_ra_exit_left", "RAMP_LEFT");
        assertManeuver("context_ra_exit_right", "SLIP_RIGHT");
        assertManeuver("context_ra_exit_left", "context_ra_exit_left");
    }

    @Test
    public void angle90AndPlainSidesRemainTurns() {
        assertManeuver("context_ra_turn_right", "RIGHT90");
        assertManeuver("context_ra_turn_left", "LEFT90");
        assertManeuver("context_ra_turn_right", "RIGHT");
        assertManeuver("context_ra_turn_left", "context_ra_turn_left");
    }

    @Test
    public void straightAndDirectionLrFallbackAreSupported() {
        assertManeuver("context_ra_forward", "STRAIGHT_AHEAD");
        assertEquals("context_ra_take_left",
                ManeuverCanonicalizer.canonicalize("SLIGHT", 1));
        assertEquals("context_ra_exit_right",
                ManeuverCanonicalizer.canonicalize("RAMP", 2));
    }

    @Test
    public void topologyAndNonDirectionalIdsAreNotCollapsed() {
        assertManeuver("", "context_ra_gray_straight_right");
        assertManeuver("", "context_ra_roundabout_exit_2");
        assertManeuver("", "LEFT45 STRAIGHT_AHEAD");
        assertManeuver("", "LEFT45 RIGHT45");
        assertManeuver("", "context_ra_finish");
    }

    private static void assertManeuver(String expected, String input) {
        assertEquals(input, expected, ManeuverCanonicalizer.canonicalize(input));
    }
}
