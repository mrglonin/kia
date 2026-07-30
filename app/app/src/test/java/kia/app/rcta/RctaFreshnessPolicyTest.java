package kia.app.rcta;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public final class RctaFreshnessPolicyTest {
    private static final long TIMEOUT_MS = 5_000L;

    @Test
    public void activeFrameExpiresAtExactDeadline() {
        RctaFreshnessPolicy policy = new RctaFreshnessPolicy(TIMEOUT_MS);

        policy.observeFrame(true, 1_000L);

        assertTrue(policy.hasActiveObservation());
        assertEquals(1L, policy.remainingMs(5_999L));
        assertEquals(0L, policy.remainingMs(6_000L));
    }

    @Test
    public void repeatedIdenticalActiveFrameRefreshesDeadline() {
        RctaFreshnessPolicy policy = new RctaFreshnessPolicy(TIMEOUT_MS);

        policy.observeFrame(true, 1_000L);
        assertEquals(1_000L, policy.remainingMs(5_000L));

        policy.observeFrame(true, 5_000L);

        assertEquals(TIMEOUT_MS, policy.remainingMs(5_000L));
        assertEquals(1L, policy.remainingMs(9_999L));
        assertEquals(0L, policy.remainingMs(10_000L));
    }

    @Test
    public void clearFrameCancelsActiveDeadline() {
        RctaFreshnessPolicy policy = new RctaFreshnessPolicy(TIMEOUT_MS);

        policy.observeFrame(true, 1_000L);
        policy.observeFrame(false, 1_500L);

        assertFalse(policy.hasActiveObservation());
        assertEquals(0L, policy.remainingMs(1_500L));
        assertEquals(0L, policy.remainingMs(100_000L));
    }

    @Test
    public void monotonicClockRollbackDoesNotExpireWarning() {
        RctaFreshnessPolicy policy = new RctaFreshnessPolicy(TIMEOUT_MS);

        policy.observeFrame(true, 2_000L);

        assertEquals(TIMEOUT_MS, policy.remainingMs(1_900L));
    }

    @Test(expected = IllegalArgumentException.class)
    public void rejectsNonPositiveTimeout() {
        new RctaFreshnessPolicy(0L);
    }
}
