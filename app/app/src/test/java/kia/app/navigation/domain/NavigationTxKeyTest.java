package kia.app.navigation.domain;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;

import org.junit.Test;

public class NavigationTxKeyTest {
    @Test
    public void identicalSemanticFrameHasSameKey() {
        NavigationTxKey first = new NavigationTxKey(
                "context_ra_turn_right", "", 80f, false, 9);
        NavigationTxKey second = new NavigationTxKey(
                "context_ra_turn_right", "", 80f, false, 9);

        assertEquals(first, second);
        assertEquals(first.hashCode(), second.hashCode());
    }

    @Test
    public void distanceProgressAndGrayRoadParticipateInKey() {
        NavigationTxKey base = new NavigationTxKey(
                "context_ra_turn_right", "", 80f, false, 9);

        assertNotEquals(base, new NavigationTxKey(
                "context_ra_turn_right", "", 70f, false, 9));
        assertNotEquals(base, new NavigationTxKey(
                "context_ra_turn_right", "", 80f, false, 8));
        assertNotEquals(new NavigationTxKey(
                        "context_ra_turn_right", "", 80f, false, 5),
                new NavigationTxKey(
                        "context_ra_turn_right", "", 80f, false, 9));
        assertNotEquals(base, new NavigationTxKey(
                "context_ra_turn_right", "context_ra_gray_right", 80f, false, 9));
    }

    @Test
    public void sameMicroVisualStillChangesKeyForEachMainDistanceStep() {
        NavigationTxKey oneKilometer = NavigationClusterTxController.maneuverKey(
                "context_ra_turn_right", "context_ra_gray_straight_right",
                1000f, false, 3);
        NavigationTxKey nineHundredNinetyMeters = NavigationClusterTxController.maneuverKey(
                "context_ra_turn_right", "context_ra_gray_straight_right",
                990f, false, 3);

        assertNotEquals(oneKilometer, nineHundredNinetyMeters);
    }
}
