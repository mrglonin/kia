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
}
