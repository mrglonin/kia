package kia.app.diagnostics;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public final class HealthMonitorSessionPolicyTest {
    @Test
    public void onlyCurrentHandlerAndTaskMayReschedule() {
        assertTrue(HealthMonitorSessionPolicy.current(true, 4L, 4L, true, true));
        assertFalse(HealthMonitorSessionPolicy.current(true, 5L, 4L, true, true));
        assertFalse(HealthMonitorSessionPolicy.current(true, 4L, 4L, false, true));
        assertFalse(HealthMonitorSessionPolicy.current(true, 4L, 4L, true, false));
        assertFalse(HealthMonitorSessionPolicy.current(false, 4L, 4L, true, true));
    }
}
