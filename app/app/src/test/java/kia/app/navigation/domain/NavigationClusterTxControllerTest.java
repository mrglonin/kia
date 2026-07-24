package kia.app.navigation.domain;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public class NavigationClusterTxControllerTest {
    @Test
    public void meterDistanceUsesSameSingleRoundingAsWireFrameAndVisual() {
        NavigationTxKey key = NavigationClusterTxController.maneuverKey(
                "turn_right", "", 83f, false, 4);

        assertEquals(80f, key.distance, 0f);
        assertEquals("turn_right / 80 м / progress=4",
                NavigationClusterTxController.clusterVisualText(
                        key.maneuver, key.progress, key.distance, key.km));
    }

    @Test
    public void interceptedMainCountdownStaysNewerThanMicroFrameForReconnect() {
        NavigationTxKey oldMain = NavigationClusterTxController.maneuverKey(
                "context_ra_turn_right", "", 50f, false, 8);
        NavigationTxKey newerMain = NavigationClusterTxController.maneuverKey(
                "context_ra_turn_right", "", 990f, false, 1);
        NavigationTxKey microVisual = NavigationClusterTxController.maneuverKey(
                "context_ra_forward", "context_ra_road_straight_right",
                990f, false, 1);

        NavigationTxKey remembered = NavigationClusterTxController.updatedMainManeuverKey(
                oldMain, newerMain, true);
        remembered = NavigationClusterTxController.updatedMainManeuverKey(
                remembered, microVisual, false);

        assertEquals(newerMain, remembered);
    }
}
