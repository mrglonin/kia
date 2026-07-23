package kia.app.navigation.domain;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class RoundaboutHoldPolicyTest {
    @Test
    public void futureRoundaboutDoesNotStartHold() {
        assertFalse(RoundaboutHoldPolicy.shouldStart(
                "", 0L, "route|exit2", "3000 м", 1000L, 250f));
    }

    @Test
    public void identicalSnapshotDoesNotExtendActiveHold() {
        assertFalse(RoundaboutHoldPolicy.shouldStart(
                "route|exit2", 46000L, "route|exit2", "80 м", 2000L, 250f));
    }

    @Test
    public void identicalSnapshotDoesNotRestartExpiredHold() {
        assertFalse(RoundaboutHoldPolicy.shouldStart(
                "route|exit2", 1000L, "route|exit2", "80 м", 2000L, 250f));
    }

    @Test
    public void actualNewRoundaboutCanStartHold() {
        assertTrue(RoundaboutHoldPolicy.shouldStart(
                "", 0L, "route|exit2", "80 м", 2000L, 250f));
    }

    @Test
    public void differentRouteGetsIndependentNearRoundaboutHold() {
        assertTrue(RoundaboutHoldPolicy.shouldStart(
                "route-a|exit2", 46000L,
                "route-b|exit2", "80 м", 2000L, 250f));
    }
}
