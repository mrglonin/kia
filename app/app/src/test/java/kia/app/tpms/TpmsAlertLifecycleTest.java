package kia.app.tpms;

import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public final class TpmsAlertLifecycleTest {
    private final TpmsAlertLifecycle lifecycle = new TpmsAlertLifecycle(4);

    private void observe(long now, int... levels) {
        lifecycle.observe(levels, now);
    }

    @Test
    public void firstWarningShowsAndNotifies() {
        observe(100, 1);
        assertTrue(lifecycle.shouldShow());
        assertTrue(lifecycle.shouldNotify(100));
        assertFalse(lifecycle.shouldNotify(101));
    }

    @Test
    public void unchangedCauseStaysDismissedAndThrottled() {
        observe(100, 1);
        lifecycle.shouldNotify(100);
        lifecycle.dismiss();
        observe(200, 1);
        assertFalse(lifecycle.shouldShow());
        assertFalse(lifecycle.shouldNotify(200));
    }

    @Test
    public void criticalEscalationRearmsImmediately() {
        observe(100, 1);
        lifecycle.shouldNotify(100);
        lifecycle.dismiss();
        observe(101, 2);
        assertTrue(lifecycle.shouldShow());
        assertTrue(lifecycle.shouldNotify(101));
    }

    @Test
    public void criticalToWarningDoesNotRearm() {
        observe(100, 2);
        lifecycle.shouldNotify(100);
        lifecycle.dismiss();
        observe(101, 1);
        assertFalse(lifecycle.shouldShow());
        assertFalse(lifecycle.shouldNotify(101));
    }

    @Test
    public void primaryCauseChangeDoesNotRearmExistingLesserCause() {
        observe(100, 2, 1);
        lifecycle.shouldNotify(100);
        lifecycle.dismiss();
        observe(101, 0, 1);
        assertFalse(lifecycle.shouldShow());
        assertFalse(lifecycle.shouldNotify(101));
    }

    @Test
    public void newCauseOrWheelRearms() {
        observe(100, 1);
        lifecycle.shouldNotify(100);
        lifecycle.dismiss();
        observe(101, 1, 1);
        assertTrue(lifecycle.shouldShow());
        assertTrue(lifecycle.shouldNotify(101));
    }

    @Test
    public void briefNormalJitterDoesNotRearm() {
        observe(100, 1);
        lifecycle.shouldNotify(100);
        lifecycle.dismiss();
        observe(200, 0);
        assertFalse(lifecycle.shouldShow());
        observe(30_199, 1);
        assertFalse(lifecycle.shouldShow());
        assertFalse(lifecycle.shouldNotify(30_199));
    }

    @Test
    public void sustainedRecoveryRearmsAtDwellBoundary() {
        observe(100, 1);
        lifecycle.shouldNotify(100);
        lifecycle.dismiss();
        observe(200, 0);
        observe(30_200, 1);
        assertTrue(lifecycle.shouldShow());
        assertTrue(lifecycle.shouldNotify(30_200));
    }

    @Test
    public void recoveryIsTrackedPerCauseWhileAnotherCauseRemains() {
        observe(100, 1, 1);
        lifecycle.dismiss();
        observe(200, 0, 1);
        observe(30_200, 1, 1);
        assertTrue(lifecycle.shouldShow());
    }

    @Test
    public void escalationDuringShortRecoveryStillRearms() {
        observe(100, 1);
        lifecycle.dismiss();
        observe(200, 0);
        observe(201, 2);
        assertTrue(lifecycle.shouldShow());
    }

    @Test
    public void notificationRepeatUsesMonotonicSixtySecondBoundary() {
        observe(100, 1);
        assertTrue(lifecycle.shouldNotify(100));
        assertFalse(lifecycle.shouldNotify(60_099));
        assertTrue(lifecycle.shouldNotify(60_100));
    }

    @Test
    public void backwardTimestampCannotRearmOrBypassRepeat() {
        observe(1000, 1);
        lifecycle.shouldNotify(1000);
        lifecycle.dismiss();
        observe(1001, 0);
        observe(1, 1);
        assertFalse(lifecycle.shouldShow());
        assertFalse(lifecycle.shouldNotify(1));
    }

    @Test
    public void resetAllowsFreshAlarmAndDiscardsOldDismissal() {
        observe(100, 1);
        lifecycle.shouldNotify(100);
        lifecycle.dismiss();
        lifecycle.reset();
        assertFalse(lifecycle.shouldShow());
        assertFalse(lifecycle.shouldNotify(101));
        observe(102, 1);
        assertTrue(lifecycle.shouldShow());
        assertTrue(lifecycle.shouldNotify(102));
    }
}
