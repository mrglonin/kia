package kia.app.tpms;

import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public final class DashboardSpeedVisibilityPolicyTest {
    @Test
    public void standstillKeepsFreshLimitButHidesCurrentSpeed() {
        DashboardSpeedVisibilityPolicy.Decision value =
                DashboardSpeedVisibilityPolicy.resolve(false, 52, 60);

        assertFalse(value.showCurrentSpeed);
        assertTrue(value.showSpeedLimit);
    }

    @Test
    public void movingWithLimitShowsPositiveCurrentSpeedAndLimit() {
        DashboardSpeedVisibilityPolicy.Decision value =
                DashboardSpeedVisibilityPolicy.resolve(true, 52, 60);

        assertTrue(value.showCurrentSpeed);
        assertTrue(value.showSpeedLimit);
    }

    @Test
    public void movingWithZeroSpeedShowsLimitButNeverZeroCircle() {
        DashboardSpeedVisibilityPolicy.Decision value =
                DashboardSpeedVisibilityPolicy.resolve(true, 0, 60);

        assertFalse(value.showCurrentSpeed);
        assertTrue(value.showSpeedLimit);
    }

    @Test
    public void movingCurrentSpeedDoesNotDependOnLimitAvailability() {
        DashboardSpeedVisibilityPolicy.Decision value =
                DashboardSpeedVisibilityPolicy.resolve(true, 52, -1);

        assertTrue(value.showCurrentSpeed);
        assertFalse(value.showSpeedLimit);
    }

    @Test
    public void standstillWithZeroSpeedStillKeepsFreshLimit() {
        DashboardSpeedVisibilityPolicy.Decision value =
                DashboardSpeedVisibilityPolicy.resolve(false, 0, 60);

        assertFalse(value.showCurrentSpeed);
        assertTrue(value.showSpeedLimit);
    }
}
