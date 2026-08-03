package kia.app.navigation.domain;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public class NavigationMotionPolicyTest {
    @Test
    public void freshPrimarySpeedIsAuthoritative() {
        assertEquals(NavigationMotionPolicy.State.MOVING,
                NavigationMotionPolicy.evaluate(30, 9_000L, 0, 9_900L,
                        10_000L, 3_500L, 5_000L, 5));
    }

    @Test
    public void stalePrimaryFallsBackToFreshGps() {
        assertEquals(NavigationMotionPolicy.State.STATIONARY,
                NavigationMotionPolicy.evaluate(30, 1_000L, 0, 9_900L,
                        10_000L, 3_500L, 5_000L, 5));
    }

    @Test
    public void unrelatedFreshStateCannotReviveStaleSpeed() {
        assertEquals(NavigationMotionPolicy.State.UNKNOWN,
                NavigationMotionPolicy.evaluate(30, 1_000L, -1, 0L,
                        10_000L, 3_500L, 5_000L, 5));
    }

    @Test
    public void clockRollbackMakesMotionUnknown() {
        assertEquals(NavigationMotionPolicy.State.UNKNOWN,
                NavigationMotionPolicy.evaluate(30, 11_000L, 0, 11_000L,
                        10_000L, 3_500L, 5_000L, 5));
    }
}
