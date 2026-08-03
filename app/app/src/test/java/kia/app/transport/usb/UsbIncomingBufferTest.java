package kia.app.transport.usb;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.Arrays;
import java.util.concurrent.atomic.AtomicBoolean;

import org.junit.Test;

import kia.app.protocol.adapter.AdapterProtocol;

public final class UsbIncomingBufferTest {
    @Test
    public void fragmentedFrameIsReturnedOnlyWhenComplete() {
        UsbIncomingBuffer buffer = new UsbIncomingBuffer();
        byte[] frame = AdapterProtocol.speedLimit(60);

        UsbIncomingBuffer.ParseResult first = buffer.append(frame, 4);
        UsbIncomingBuffer.ParseResult second = buffer.append(
                Arrays.copyOfRange(frame, 4, frame.length), frame.length - 4);

        assertEquals(0, first.frames.size());
        assertEquals(1, second.frames.size());
        assertArrayEquals(frame, second.frames.get(0));
        assertEquals(0, buffer.bufferedBytes());
    }

    @Test
    public void splitHeaderPrefixSurvivesLeadingNoise() {
        UsbIncomingBuffer buffer = new UsbIncomingBuffer();
        byte[] frame = AdapterProtocol.speedLimit(80);
        byte[] first = new byte[]{0x01, 0x02, 0x03, 0x04, 0x05, frame[0], frame[1]};

        assertEquals(0, buffer.append(first, first.length).frames.size());
        UsbIncomingBuffer.ParseResult result = buffer.append(
                Arrays.copyOfRange(frame, 2, frame.length), frame.length - 2);

        assertEquals(1, result.frames.size());
        assertArrayEquals(frame, result.frames.get(0));
    }

    @Test
    public void badChecksumDoesNotHideFollowingValidFrame() {
        UsbIncomingBuffer buffer = new UsbIncomingBuffer();
        byte[] bad = AdapterProtocol.speedLimit(40);
        bad[bad.length - 1] ^= 0x01;
        byte[] valid = AdapterProtocol.speedLimit(100);
        byte[] batch = new byte[bad.length + valid.length];
        System.arraycopy(bad, 0, batch, 0, bad.length);
        System.arraycopy(valid, 0, batch, bad.length, valid.length);

        UsbIncomingBuffer.ParseResult result = buffer.append(batch, batch.length);

        assertEquals(1, result.badChecksumFrames.size());
        assertEquals(1, result.frames.size());
        assertArrayEquals(valid, result.frames.get(0));
    }

    @Test
    public void parseReturnsAfterReleasingAccumulatorMonitor() {
        UsbIncomingBuffer buffer = new UsbIncomingBuffer();

        UsbIncomingBuffer.ParseResult result = buffer.append(
                AdapterProtocol.speedLimit(60), AdapterProtocol.speedLimit(60).length);

        assertEquals(1, result.frames.size());
        assertFalse(Thread.holdsLock(buffer));
    }

    @Test
    public void reentrantListenerWorkCannotDeadlockAccumulator() throws Exception {
        UsbIncomingBuffer buffer = new UsbIncomingBuffer();
        byte[] frame = AdapterProtocol.speedLimit(60);
        UsbIncomingBuffer.ParseResult result = buffer.append(frame, frame.length);
        AtomicBoolean completed = new AtomicBoolean();

        Thread simulatedListener = new Thread(() -> {
            buffer.reset();
            completed.set(true);
        });
        simulatedListener.start();
        simulatedListener.join(1000L);

        assertEquals(1, result.frames.size());
        assertFalse(simulatedListener.isAlive());
        assertTrue(completed.get());
    }
}
