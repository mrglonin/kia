package kia.app.navigation.domain;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public final class ManeuverProgressRolloverMarkerTest {
    @Test
    public void earlyHandlerReturnDoesNotLoseArmedMarker() {
        ManeuverProgressRolloverMarker marker =
                new ManeuverProgressRolloverMarker(5000L);

        marker.arm("route-1", "right", 1000L);

        assertTrue(marker.pending());
        assertTrue(marker.matches("route-1", "right", 290f, 1250L));
        marker.clear();
        assertFalse(marker.pending());
    }

    @Test
    public void invalidDistanceDoesNotConsumeMarker() {
        ManeuverProgressRolloverMarker marker =
                new ManeuverProgressRolloverMarker(5000L);

        marker.arm("route-1", "right", 1000L);

        assertFalse(marker.matches("route-1", "right", 0f, 1100L));
        assertFalse(marker.matches("route-1", "right", 1f, 1200L));
        assertFalse(marker.matches("route-1", "right", Float.NaN, 1300L));
        assertTrue(marker.pending());
        assertTrue(marker.matches("route-1", "right", 280f, 1400L));
    }

    @Test
    public void directionalRefinementMatchesButDifferentFamilyOrRouteCancels() {
        ManeuverProgressRolloverMarker marker =
                new ManeuverProgressRolloverMarker(5000L);

        marker.arm("route-1", "hard_right", 1000L);
        assertTrue(marker.matches("route-1", "exit_right", 280f, 1200L));

        marker.arm("route-1", "right", 2000L);
        assertFalse(marker.matches("route-1", "left", 280f, 2200L));
        assertFalse(marker.pending());

        marker.arm("route-1", "right", 3000L);
        assertFalse(marker.matches("route-2", "right", 280f, 3200L));
        assertFalse(marker.pending());
    }

    @Test
    public void staleMarkerExpiresFailClosed() {
        ManeuverProgressRolloverMarker marker =
                new ManeuverProgressRolloverMarker(3000L);

        marker.arm("route-1", "right", 1000L);

        assertFalse(marker.matches("route-1", "right", 280f, 4001L));
        assertFalse(marker.pending());
    }
}
