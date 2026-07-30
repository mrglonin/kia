package kia.app.tpms;

import org.junit.Test;

import kia.app.core.model.NavigationState;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public final class DashboardNavigationSnapshotTest {
    @Test
    public void straightPreviewVisualWinsProviderTurnAndKeepsActualDistance() {
        NavigationState state = providerState(
                "context_ra_turn_right", "Поворот направо", "2 км")
                .withNavigationDebug(
                        "context_ra_turn_right", "", "", "",
                        "", "", "", 2L)
                .withClusterVisualText(
                        "context_ra_forward / 2 км / progress=1", 3L)
                .withClusterTxText(
                        "maneuver context_ra_forward dist=2.0km progress=1\n"
                                + "speedLimit=60", 4L);

        DashboardNavigationSnapshot value = DashboardNavigationSnapshot.resolve(state);

        assertTrue(value.clusterBacked);
        assertEquals("context_ra_forward", value.maneuverId);
        assertEquals("2 км", value.distance);
        assertEquals("↑", value.presentation.glyph);
    }

    @Test
    public void microVisualAndGrayRoadWinProviderRoundabout() {
        NavigationState state = providerState(
                "context_ra_roundabout_exit_3", "3-й съезд", "3 км")
                .withNavigationDebug(
                        "context_ra_roundabout_exit_3", "", "context_ra_turn_right",
                        "80 м", "", "context_ra_gray_straight_right",
                        "Прямо и направо", 2L)
                .withClusterVisualText(
                        "context_ra_turn_right + context_ra_gray_straight_right"
                                + " / 80 м / progress=7",
                        3L);

        DashboardNavigationSnapshot value = DashboardNavigationSnapshot.resolve(state);

        assertTrue(value.clusterBacked);
        assertEquals("context_ra_turn_right", value.maneuverId);
        assertEquals("80 м", value.distance);
        assertEquals("context_ra_gray_straight_right", value.grayRoad);
        assertEquals("↱", value.presentation.glyph);
        assertEquals(0, value.presentation.roundaboutExit);
    }

    @Test
    public void latestManeuverTxIsUsedWhenVisualIsMissing() {
        NavigationState state = providerState(
                "context_ra_turn_left", "Поворот налево", "900 м")
                .withClusterTxText(
                        "maneuver context_ra_turn_left dist=900.0m progress=1\n"
                                + "maneuver+gray context_ra_exit_right"
                                + " gray=context_ra_gray_straight_right"
                                + " dist=120.0m progress=8\n"
                                + "eta distance=4.0km\n"
                                + "speedLimit=80",
                        2L);

        DashboardNavigationSnapshot value = DashboardNavigationSnapshot.resolve(state);

        assertTrue(value.clusterBacked);
        assertEquals("context_ra_exit_right", value.maneuverId);
        assertEquals("120 м", value.distance);
        assertEquals("context_ra_gray_straight_right", value.grayRoad);
        assertEquals("↗", value.presentation.glyph);
    }

    @Test
    public void providerIsFallbackWhenNoClusterManeuverExists() {
        NavigationState state = providerState(
                "context_ra_roundabout_exit_4", "4-й съезд", "700 м")
                .withNavigationDebug(
                        "context_ra_roundabout_exit_4", "", "", "",
                        "", "context_ra_gray_left", "Налево", 2L)
                .withClusterTxText("eta distance=7.0km\nspeedLimit=60", 3L);

        DashboardNavigationSnapshot value = DashboardNavigationSnapshot.resolve(state);

        assertFalse(value.clusterBacked);
        assertEquals("context_ra_roundabout_exit_4", value.maneuverId);
        assertEquals("700 м", value.distance);
        assertEquals("Налево", value.grayRoad);
        assertEquals(4, value.presentation.roundaboutExit);
    }

    @Test
    public void actualGenericRoundaboutIsNotRefinedByStaleProviderExit() {
        NavigationState state = providerState(
                "context_ra_roundabout_exit_4", "4-й съезд", "400 м")
                .withNavigationDebug(
                        "context_ra_roundabout_exit_4", "", "", "",
                        "", "", "", 2L)
                .withClusterVisualText(
                        "context_ra_in_circular_movement / 90 м / progress=8", 3L);

        DashboardNavigationSnapshot value = DashboardNavigationSnapshot.resolve(state);

        assertTrue(value.clusterBacked);
        assertEquals("context_ra_in_circular_movement", value.maneuverId);
        assertEquals(0, value.presentation.roundaboutExit);
        assertEquals("Круговое движение", value.presentation.fallbackLabel);
    }

    @Test
    public void visualWinsOlderDifferentManeuverTx() {
        NavigationState state = providerState(
                "context_ra_turn_left", "Поворот налево", "600 м")
                .withClusterTxText(
                        "maneuver context_ra_turn_left dist=600.0m progress=2", 2L)
                .withClusterVisualText(
                        "context_ra_take_right / 300 м / progress=4", 3L);

        DashboardNavigationSnapshot value = DashboardNavigationSnapshot.resolve(state);

        assertEquals("context_ra_take_right", value.maneuverId);
        assertEquals("300 м", value.distance);
        assertEquals("↗", value.presentation.glyph);
    }

    @Test
    public void finishDirectionVisualReadsDistanceAfterStepMetadata() {
        NavigationState state = providerState(
                "context_ra_turn_left", "Поворот налево", "600 м")
                .withClusterVisualText(
                        "context_ra_direction_to_finish / step=3 target=4 / 1.5 км",
                        2L);

        DashboardNavigationSnapshot value = DashboardNavigationSnapshot.resolve(state);

        assertTrue(value.clusterBacked);
        assertEquals("context_ra_direction_to_finish", value.maneuverId);
        assertEquals("1,5 км", value.distance);
    }

    @Test
    public void reroutingVisualIsNotPresentedAsStraightManeuver() {
        NavigationState state = providerState("", "", "")
                .withClusterVisualText(
                        "route_loading / rerouting_text", 2L);

        DashboardNavigationSnapshot value = DashboardNavigationSnapshot.resolve(state);

        assertTrue(value.clusterBacked);
        assertEquals("route_loading", value.maneuverId);
        assertEquals("↺", value.presentation.glyph);
        assertEquals("Перестроение", value.presentation.fallbackLabel);
    }

    private static NavigationState providerState(String maneuver, String text,
                                                 String distance) {
        return new NavigationState(
                true, false, false,
                maneuver, text, distance,
                "10 км", "20 мин", "12:30",
                "Сейчас", "После", "Финиш",
                "60", "40", "yandex", 1L);
    }
}
