package kia.app.update;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class AppUpdateCheckPolicyTest {
    @Test
    public void firstCheckIsAlwaysDue() {
        assertTrue(AppUpdateCheckPolicy.shouldCheck(1_000L, 0L));
    }

    @Test
    public void successfulCheckSuppressesOnlyItsInterval() {
        long last = 10_000L;
        assertFalse(AppUpdateCheckPolicy.shouldCheck(
                last + AppUpdateCheckPolicy.SUCCESS_INTERVAL_MS - 1L, last));
        assertTrue(AppUpdateCheckPolicy.shouldCheck(
                last + AppUpdateCheckPolicy.SUCCESS_INTERVAL_MS, last));
    }

    @Test
    public void clockRollbackCannotFreezeOtaChecks() {
        assertTrue(AppUpdateCheckPolicy.shouldCheck(1_000L, 5_000L));
    }
}
