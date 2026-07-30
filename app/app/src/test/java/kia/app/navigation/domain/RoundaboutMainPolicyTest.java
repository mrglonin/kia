package kia.app.navigation.domain;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public final class RoundaboutMainPolicyTest {
    @Test
    public void genericRoundaboutCanBeRefinedToEveryNumberedExit() {
        String[] genericManeuvers = {
                "context_ra_in_circular_movement",
                "context_ra_out_circular_movement"
        };
        for (String generic : genericManeuvers) {
            for (int exit = 1; exit <= 4; exit++) {
                assertTrue(RoundaboutMainPolicy.shouldRefineMain(
                        generic, "context_ra_roundabout_exit_" + exit));
            }
        }
    }

    @Test
    public void explicitNewExitCanCorrectOrAdvanceTheRoundaboutMain() {
        assertTrue(RoundaboutMainPolicy.shouldRefineMain(
                "context_ra_roundabout_exit_2",
                "context_ra_roundabout_exit_3"));
        assertFalse(RoundaboutMainPolicy.shouldRefineMain(
                "context_ra_roundabout_exit_2",
                "context_ra_roundabout_exit_2"));
    }

    @Test
    public void missingExitMetadataNeverDowngradesNumberedMain() {
        assertFalse(RoundaboutMainPolicy.shouldRefineMain(
                "context_ra_roundabout_exit_2",
                "context_ra_in_circular_movement"));
        assertFalse(RoundaboutMainPolicy.shouldRefineMain(
                "context_ra_turn_right",
                "context_ra_roundabout_exit_2"));
        assertTrue(RoundaboutMainPolicy.shouldKeepNumberedMain(
                "context_ra_roundabout_exit_2",
                "context_ra_in_circular_movement", 990f, 985f));
        assertTrue(RoundaboutMainPolicy.shouldKeepNumberedMain(
                "context_ra_roundabout_exit_2",
                "context_ra_out_circular_movement", 990f, 0f));
        assertFalse(RoundaboutMainPolicy.shouldKeepNumberedMain(
                "context_ra_roundabout_exit_2",
                "context_ra_roundabout_exit_3", 990f, 985f));
        assertFalse(RoundaboutMainPolicy.shouldKeepNumberedMain(
                "context_ra_turn_right",
                "context_ra_in_circular_movement", 990f, 985f));
        assertFalse(RoundaboutMainPolicy.shouldKeepNumberedMain(
                "context_ra_roundabout_exit_2",
                "context_ra_in_circular_movement", 120f, 1000f));
    }

    @Test
    public void genericAndNumberedRoundaboutShareEtaDistanceIdentity() {
        assertTrue(RoundaboutMainPolicy.distanceIdentityMatches(
                "context_ra_roundabout_exit_2",
                "context_ra_in_circular_movement"));
        assertTrue(RoundaboutMainPolicy.distanceIdentityMatches(
                "context_ra_roundabout_exit_2",
                "context_ra_roundabout_exit_2"));
        assertFalse(RoundaboutMainPolicy.distanceIdentityMatches(
                "context_ra_roundabout_exit_2",
                "context_ra_turn_right"));
    }
}
