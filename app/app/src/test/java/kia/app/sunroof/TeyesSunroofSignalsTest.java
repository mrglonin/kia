package kia.app.sunroof;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public final class TeyesSunroofSignalsTest {
    @Test public void aggregateOnlyAcceptsNativeBinaryInteger() {
        assertEquals(TeyesSunroofSignals.OFF, TeyesSunroofSignals.aggregateAcc(0));
        assertEquals(TeyesSunroofSignals.ON, TeyesSunroofSignals.aggregateAcc(1));
        Object[] invalid = {null, -1, 2, 6, 7, 255, true, false, "1", 1L, 1.0f};
        for (Object value : invalid) {
            assertEquals(String.valueOf(value), TeyesSunroofSignals.UNKNOWN,
                    TeyesSunroofSignals.aggregateAcc(value));
        }
    }

    @Test public void earlyCallbackInvertsWillPowerOff() {
        assertEquals(TeyesSunroofSignals.OFF, TeyesSunroofSignals.callbackAcc(5, 1, 0));
        assertEquals(TeyesSunroofSignals.ON, TeyesSunroofSignals.callbackAcc(5, 0, 0));
    }

    @Test public void sleepAndUnrelatedMetadataNeverBecomeAccOn() {
        for (int command : new int[]{-1, 0, 1, 3, 4, 6, 8, 13, 14}) {
            for (int value : new int[]{-1, 0, 1, 6, 7, 255}) {
                assertEquals(TeyesSunroofSignals.UNKNOWN, TeyesSunroofSignals.callbackAcc(command, value, 0));
            }
        }
    }

    @Test public void unknownAccEnumsAndWrongMetadataAreRejected() {
        for (int value : new int[]{Integer.MIN_VALUE, -1, 2, 6, 7, 255, Integer.MAX_VALUE}) {
            assertEquals(TeyesSunroofSignals.UNKNOWN, TeyesSunroofSignals.callbackAcc(5, value, 0));
        }
        for (int metadata : new int[]{-1, 1, 6, Integer.MAX_VALUE}) {
            assertEquals(TeyesSunroofSignals.UNKNOWN, TeyesSunroofSignals.callbackAcc(5, 0, metadata));
            assertEquals(TeyesSunroofSignals.UNKNOWN, TeyesSunroofSignals.callbackAcc(5, 1, metadata));
        }
    }

    @Test public void sleepIsSeparateAndOnlyAcceptsVerifiedStates() {
        assertEquals(0, TeyesSunroofSignals.callbackSleep(8, 0, 0));
        assertEquals(1, TeyesSunroofSignals.callbackSleep(8, 6, 0));
        assertEquals(1, TeyesSunroofSignals.callbackSleep(8, 7, 0));
        for (int state : new int[]{-1, 1, 2, 5, 8, 255}) {
            assertEquals(TeyesSunroofSignals.UNKNOWN, TeyesSunroofSignals.callbackSleep(8, state, 0));
        }
        assertEquals(TeyesSunroofSignals.UNKNOWN, TeyesSunroofSignals.callbackSleep(5, 0, 0));
        assertEquals(TeyesSunroofSignals.UNKNOWN, TeyesSunroofSignals.callbackSleep(8, 6, 1));
    }

    @Test public void hardwareRequiresRealKeyAndVerifiedAction() {
        assertTrue(TeyesSunroofSignals.hardwareAction(24, 0, 0));
        assertTrue(TeyesSunroofSignals.hardwareAction(25, 2, 1));
        assertFalse(TeyesSunroofSignals.hardwareAction(0, 0, 0));
        assertFalse(TeyesSunroofSignals.hardwareAction(-1, 0, 0));
        assertFalse(TeyesSunroofSignals.hardwareAction(24, -1, 0));
        assertFalse(TeyesSunroofSignals.hardwareAction(24, 0, 2));
    }
}
