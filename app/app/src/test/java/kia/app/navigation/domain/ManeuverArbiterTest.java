package kia.app.navigation.domain;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class ManeuverArbiterTest {
    @Test
    public void roundaboutThreeKilometersDoesNotHideMicroAtEightyMeters() {
        ManeuverArbiter.Decision decision = ManeuverArbiter.decide(
                "context_ra_roundabout_exit_2", "3000 м",
                "context_ra_turn_right", "80 м", true, false);

        assertTrue(decision.microWins());
        assertEquals("micro_before_main", decision.reason);
    }

    @Test
    public void microStillWinsInsideLegacyTwoHundredFiftyMeterWindowWhenItIsEarlier() {
        ManeuverArbiter.Decision decision = ManeuverArbiter.decide(
                "context_ra_roundabout_exit_1", "200 м",
                "context_ra_turn_left", "20 м", true, false);

        assertTrue(decision.microWins());
    }

    @Test
    public void nearMainWinsWhenThereIsNoMicro() {
        ManeuverArbiter.Decision decision = ManeuverArbiter.decide(
                "context_ra_roundabout_exit_3", "30 м",
                "", "", false, false);

        assertFalse(decision.microWins());
    }

    @Test
    public void explicitMicroWinsWhenMainDistanceIsUnknown() {
        ManeuverArbiter.Decision decision = ManeuverArbiter.decide(
                "context_ra_in_circular_movement", "",
                "context_ra_forward", "40 м", true, false);

        assertTrue(decision.microWins());
        assertEquals("explicit_micro_before_unknown_main", decision.reason);
    }

    @Test
    public void mainWinsWhenItIsPhysicallyBeforeMicro() {
        ManeuverArbiter.Decision decision = ManeuverArbiter.decide(
                "context_ra_turn_left", "30 м",
                "context_ra_turn_right", "80 м", true, false);

        assertFalse(decision.microWins());
        assertEquals("main_is_next", decision.reason);
    }

    @Test
    public void sequentialMicroManeuversAreArbitratedIndependently() {
        assertTrue(ManeuverArbiter.decide(
                "context_ra_roundabout_exit_2", "3000 м",
                "context_ra_turn_left", "120 м", true, false).microWins());
        assertTrue(ManeuverArbiter.decide(
                "context_ra_roundabout_exit_2", "2800 м",
                "context_ra_turn_right", "60 м", true, false).microWins());
    }

    @Test
    public void earlierMicroWinsEvenWhenLaterMainHasSameDirectionFamily() {
        ManeuverArbiter.Decision decision = ManeuverArbiter.decide(
                "context_ra_turn_right", "3000 м",
                "context_ra_turn_right", "80 м", true, true);

        assertTrue(decision.microWins());
        assertEquals("micro_before_same_family_main", decision.reason);
    }

    @Test
    public void parsesGroupedMetricDistance() {
        assertEquals(3000f, ManeuverArbiter.distanceMeters("3 000 м"), 0.01f);
        assertEquals(3500f, ManeuverArbiter.distanceMeters("3,5 км"), 0.01f);
    }
}
