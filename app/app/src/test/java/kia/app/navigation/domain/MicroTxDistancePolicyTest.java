package kia.app.navigation.domain;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public class MicroTxDistancePolicyTest {
    @Test
    public void freshPositiveMainDistanceWins() {
        assertEquals("990 м", MicroTxDistancePolicy.selectMainDistance(
                " 990 м ", "1 км"));
    }

    @Test
    public void heldMainDistanceIsUsedWhenFreshValueIsMissingOrInvalid() {
        assertEquals("1 км", MicroTxDistancePolicy.selectMainDistance(
                "", "1 км"));
        assertEquals("850 м", MicroTxDistancePolicy.selectMainDistance(
                "unknown", "850 м"));
    }

    @Test
    public void zeroMainDistanceFallsBackButIsNeverSelected() {
        assertEquals("720 м", MicroTxDistancePolicy.selectMainDistance(
                "0 м", "720 м"));
        assertEquals("", MicroTxDistancePolicy.selectMainDistance(
                "0 км", "0 м"));
    }

    @Test
    public void negativeAndUnparseableCandidatesAreRejected() {
        assertEquals("", MicroTxDistancePolicy.selectMainDistance(
                "-1 м", null));
        assertEquals("", MicroTxDistancePolicy.selectMainDistance(
                "—", "unknown"));
    }

    @Test
    public void positiveMetricAndKilometerFormatsArePreserved() {
        assertEquals("3 000 м", MicroTxDistancePolicy.selectMainDistance(
                "3 000 м", ""));
        assertEquals("3,5 км", MicroTxDistancePolicy.selectMainDistance(
                "3,5 км", ""));
    }

    @Test
    public void maneuverAndDistanceAreSelectedAsOneSnapshot() {
        MicroTxDistancePolicy.MainSnapshot fresh =
                MicroTxDistancePolicy.selectMainSnapshot(
                        "context_ra_turn_right", "990 м",
                        "context_ra_turn_left", "1 км");
        assertEquals("context_ra_turn_right", fresh.maneuver);
        assertEquals("990 м", fresh.distance);
        assertEquals(true, fresh.preferred);

        MicroTxDistancePolicy.MainSnapshot held =
                MicroTxDistancePolicy.selectMainSnapshot(
                        "context_ra_turn_right", "0 м",
                        "context_ra_turn_left", "1 км");
        assertEquals("context_ra_turn_left", held.maneuver);
        assertEquals("1 км", held.distance);
        assertEquals(false, held.preferred);
    }

    @Test
    public void invalidFreshAndHeldSnapshotsProduceNoTxPair() {
        MicroTxDistancePolicy.MainSnapshot selected =
                MicroTxDistancePolicy.selectMainSnapshot(
                        "context_ra_turn_right", "0 м",
                        "context_ra_turn_left", "unknown");
        assertEquals(false, selected.available());
        assertEquals("", selected.maneuver);
        assertEquals("", selected.distance);
    }
}
