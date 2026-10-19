package kia.app.tpms;

import org.junit.Test;

import static org.junit.Assert.assertEquals;

public final class TpmsWarningPolicyTest {
    private TpmsWarningPolicy.Result evaluate(int pressure, int temperature, int flags) {
        return TpmsWarningPolicy.evaluate(pressure, temperature, flags, 220, 280, -20, 70);
    }

    @Test
    public void normalReadingsHaveNoWarning() {
        assertEquals(TpmsAlertController.WARNING_NONE, evaluate(240, 25, 0).warning());
        assertEquals(TpmsAlertController.SEVERITY_NONE, evaluate(240, 25, 0).severity());
    }

    @Test
    public void criticalTemperatureWinsOverMildLowPressure() {
        TpmsWarningPolicy.Result result = evaluate(210, 90, 0);
        assertEquals(TpmsAlertController.WARNING_HIGH_TEMP, result.warning());
        assertEquals(2, result.severity());
        assertEquals(1, result.levels[TpmsAlertController.WARNING_LOW_PRESSURE]);
        assertEquals(2, result.levels[TpmsAlertController.WARNING_HIGH_TEMP]);
    }

    @Test
    public void criticalColdWinsOverMildHighPressure() {
        TpmsWarningPolicy.Result result = evaluate(290, -40, 0);
        assertEquals(TpmsAlertController.WARNING_LOW_TEMP, result.warning());
        assertEquals(2, result.severity());
    }

    @Test
    public void lowBatteryCannotMaskAnyCriticalMeasurement() {
        assertEquals(2, evaluate(180, 25, 0x10).severity());
        assertEquals(TpmsAlertController.WARNING_LOW_PRESSURE, evaluate(180, 25, 0x10).warning());
        assertEquals(2, evaluate(320, 25, 0x10).severity());
        assertEquals(TpmsAlertController.WARNING_HIGH_PRESSURE, evaluate(320, 25, 0x10).warning());
        assertEquals(2, evaluate(240, 90, 0x10).severity());
        assertEquals(TpmsAlertController.WARNING_HIGH_TEMP, evaluate(240, 90, 0x10).warning());
        assertEquals(2, evaluate(240, -40, 0x10).severity());
    }

    @Test
    public void lowBatteryAloneIsAnOrdinaryWarning() {
        assertEquals(1, evaluate(240, 25, 0x10).severity());
        assertEquals(TpmsAlertController.WARNING_LOW_BATTERY, evaluate(240, 25, 0x10).warning());
    }

    @Test
    public void hardwareCriticalFlagsRemainCritical() {
        for (int flag : new int[]{1, 2, 4, 8}) {
            assertEquals(2, evaluate(240, 25, flag | 0x10).severity());
        }
        assertEquals(TpmsAlertController.WARNING_FAST_LEAKAGE, evaluate(180, 90, 0x1f).warning());
    }

    @Test
    public void criticalMarginsAreInclusive() {
        assertEquals(1, evaluate(191, 25, 0).severity());
        assertEquals(2, evaluate(190, 25, 0).severity());
        assertEquals(1, evaluate(309, 25, 0).severity());
        assertEquals(2, evaluate(310, 25, 0).severity());
        assertEquals(1, evaluate(240, 79, 0).severity());
        assertEquals(2, evaluate(240, 80, 0).severity());
        assertEquals(1, evaluate(240, -29, 0).severity());
        assertEquals(2, evaluate(240, -30, 0).severity());
    }

    @Test
    public void normalThresholdEdgesRetainExistingMeaning() {
        assertEquals(0, evaluate(220, 25, 0).severity());
        assertEquals(0, evaluate(280, 25, 0).severity());
        assertEquals(0, evaluate(240, -20, 0).severity());
        assertEquals(1, evaluate(240, 70, 0).severity());
    }

    @Test
    public void criticalPressureIsNotDowngradedByAnotherCause() {
        assertEquals(2, evaluate(180, 75, 0).severity());
        assertEquals(TpmsAlertController.WARNING_LOW_PRESSURE, evaluate(180, 75, 0).warning());
    }
}
