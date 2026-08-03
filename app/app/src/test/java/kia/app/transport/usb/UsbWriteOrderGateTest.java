package kia.app.transport.usb;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import org.junit.Test;

public final class UsbWriteOrderGateTest {
    @Test
    public void queuedStateFlushCompletesBeforeLiveWriterAfterPortPublication() throws Exception {
        UsbWriteOrderGate gate = new UsbWriteOrderGate();
        List<String> wireOrder = new ArrayList<>();
        CountDownLatch portPublished = new CountDownLatch(1);
        CountDownLatch liveWriterReady = new CountDownLatch(1);
        CountDownLatch allowFlush = new CountDownLatch(1);

        Thread connection = new Thread(() -> gate.run(() -> {
            // UsbTransport publishes the new port while retaining the same gate.
            portPublished.countDown();
            await(liveWriterReady);
            await(allowFlush);
            wireOrder.add("queued-A");
            return null;
        }));
        Thread liveWriter = new Thread(() -> {
            await(portPublished);
            liveWriterReady.countDown();
            gate.run(() -> {
                wireOrder.add("live-B");
                return null;
            });
        });

        connection.start();
        liveWriter.start();
        assertTrue(liveWriterReady.await(1L, TimeUnit.SECONDS));
        allowFlush.countDown();
        connection.join(1000L);
        liveWriter.join(1000L);

        assertTrue(!connection.isAlive());
        assertTrue(!liveWriter.isAlive());
        assertEquals(Arrays.asList("queued-A", "live-B"), wireOrder);
    }

    @Test
    public void queueReplayMayReenterRegularWritePath() {
        UsbWriteOrderGate gate = new UsbWriteOrderGate();
        List<String> wireOrder = new ArrayList<>();

        gate.run(() -> {
            gate.run(() -> {
                wireOrder.add("queued-A");
                return null;
            });
            return null;
        });

        assertEquals(Arrays.asList("queued-A"), wireOrder);
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(1L, TimeUnit.SECONDS)) {
                throw new AssertionError("latch timeout");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AssertionError(e);
        }
    }
}
