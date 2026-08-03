package kia.app.navigation.capture;

import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public final class NavServiceStartPolicyTest {
    @Test
    public void liveServiceIsNeverRestartedByHeartbeat() {
        assertFalse(NavServiceStartPolicy.shouldStart(true, 10_000L, 0L, 5_000L));
        assertFalse(NavServiceStartPolicy.shouldStart(true, 20_000L, 10_000L, 5_000L));
    }

    @Test
    public void coldProcessStartsImmediatelyThenDebouncesDuplicateBroadcasts() {
        assertTrue(NavServiceStartPolicy.shouldStart(false, 1_000L, 0L, 5_000L));
        assertFalse(NavServiceStartPolicy.shouldStart(false, 4_000L, 1_000L, 5_000L));
        assertTrue(NavServiceStartPolicy.shouldStart(false, 6_000L, 1_000L, 5_000L));
    }

    @Test
    public void elapsedClockResetDoesNotBlockRecovery() {
        assertTrue(NavServiceStartPolicy.shouldStart(false, 10L, 50_000L, 5_000L));
    }

    @Test
    public void receiverWakeLockIsOnlyForAnAcceptedColdStartAttempt() {
        assertTrue(NavServiceStartPolicy.shouldAcquireReceiverWakeLock(false, true));
        assertFalse(NavServiceStartPolicy.shouldAcquireReceiverWakeLock(false, false));
        assertFalse(NavServiceStartPolicy.shouldAcquireReceiverWakeLock(true, true));
    }
}
