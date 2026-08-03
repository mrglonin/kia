package kia.app.diagnostics;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public final class UsbConnectionHealthPolicyTest {
    @Test
    public void disconnectedTransportIsHandledByNormalConnectPath() {
        assertFalse(UsbConnectionHealthPolicy.shouldReconnect(
                false, 1000L, 0L, 20000L, 12000L, 10000L, 3, 3));
    }

    @Test
    public void silentNewConnectionGetsStartupGraceThenReconnects() {
        assertFalse(UsbConnectionHealthPolicy.shouldReconnect(
                true, 1000L, 0L, 12999L, 12000L, 10000L, 3, 3));
        assertTrue(UsbConnectionHealthPolicy.shouldReconnect(
                true, 1000L, 0L, 13000L, 12000L, 10000L, 3, 3));
    }

    @Test
    public void freshRxKeepsConnectionAndStaleRxReconnects() {
        assertFalse(UsbConnectionHealthPolicy.shouldReconnect(
                true, 1000L, 9000L, 18999L, 12000L, 10000L, 3, 3));
        assertTrue(UsbConnectionHealthPolicy.shouldReconnect(
                true, 1000L, 9000L, 19000L, 12000L, 10000L, 3, 3));
    }

    @Test
    public void elapsedClockRollbackDoesNotTrustOldRxTimestamp() {
        assertTrue(UsbConnectionHealthPolicy.shouldReconnect(
                true, 1000L, 9000L, 8000L, 12000L, 10000L, 3, 3));
    }

    @Test
    public void reconnectWaitsForSeveralCompletedProbeBursts() {
        assertFalse(UsbConnectionHealthPolicy.shouldReconnect(
                true, 1000L, 0L, 30000L, 12000L, 10000L, 2, 3));
        assertTrue(UsbConnectionHealthPolicy.shouldReconnect(
                true, 1000L, 0L, 30000L, 12000L, 10000L, 3, 3));
    }
}
