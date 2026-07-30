package kia.app.tpms;

import org.junit.Test;

import static org.junit.Assert.assertEquals;

public final class DashboardManeuverPresentationTest {
    @Test
    public void numberedRoundaboutIsVisibleInGlyphAndLabel() {
        DashboardManeuverPresentation value = DashboardManeuverPresentation.resolve(
                "context_ra_roundabout_exit_3", "", "", "");

        assertEquals("↻3", value.glyph);
        assertEquals("3-й съезд", value.fallbackLabel);
        assertEquals(3, value.roundaboutExit);
    }

    @Test
    public void genericRoundaboutIsRefinedByCanonicalMainMetadata() {
        DashboardManeuverPresentation value = DashboardManeuverPresentation.resolve(
                "context_ra_in_circular_movement",
                "context_ra_roundabout_exit_2",
                "",
                "");

        assertEquals("↻2", value.glyph);
        assertEquals(2, value.roundaboutExit);
    }

    @Test
    public void uturnWinsBeforeGenericLeftAndRightTokens() {
        assertEquals("↶", DashboardManeuverPresentation.resolve(
                "context_ra_turn_back_left", "", "", "").glyph);
        assertEquals("↷", DashboardManeuverPresentation.resolve(
                "context_ra_turn_back_right", "", "", "").glyph);
    }

    @Test
    public void forkAndAngledExitStayDistinctFromNinetyDegreeTurn() {
        assertEquals("↖", DashboardManeuverPresentation.resolve(
                "context_ra_take_left", "", "", "").glyph);
        assertEquals("↗", DashboardManeuverPresentation.resolve(
                "context_ra_exit_right", "", "", "").glyph);
        assertEquals("↰", DashboardManeuverPresentation.resolve(
                "context_ra_turn_left", "", "", "").glyph);
        assertEquals("↱", DashboardManeuverPresentation.resolve(
                "context_ra_turn_right", "", "", "").glyph);
    }

    @Test
    public void explicitPrimaryManeuverIsNotReplacedByStaleMainMetadata() {
        DashboardManeuverPresentation value = DashboardManeuverPresentation.resolve(
                "context_ra_turn_right",
                "context_ra_roundabout_exit_4",
                "",
                "");

        assertEquals("↱", value.glyph);
        assertEquals(0, value.roundaboutExit);
    }
}
