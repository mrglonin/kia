package kia.app.tpms;

import kia.app.core.model.TpmsState;

import org.junit.Test;

import static org.junit.Assert.assertEquals;

public final class TpmsAlertFreshnessTest {
    @Test
    public void staleWheelCannotKeepWarningOrCriticalPollingAlive() {
        TpmsState stale = TpmsState.empty()
                .withWheelAt(TpmsState.WHEEL_FL, 80, 120, 0x0f,
                        "old", 1L);

        // Null context is safe here specifically because freshness rejects
        // the wheel before settings or thresholds are consulted.
        assertEquals(TpmsAlertController.WARNING_NONE,
                TpmsAlertController.warningState(null, stale, TpmsState.WHEEL_FL));
        assertEquals(TpmsAlertController.SEVERITY_NONE,
                TpmsAlertController.warningSeverity(null, stale, TpmsState.WHEEL_FL));
    }
}
