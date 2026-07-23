package kia.app.transport.usb;

import static org.junit.Assert.assertEquals;

import java.util.List;

import org.junit.Test;

import kia.app.protocol.adapter.AdapterProtocol;

public final class PendingFrameQueueTest {
    @Test
    public void queueNeverExceedsConfiguredLimit() {
        PendingFrameQueue queue = new PendingFrameQueue(3);

        queue.offer(frame(0x30, 1));
        queue.offer(frame(0x31, 2));
        queue.offer(frame(0x32, 3));
        queue.offer(frame(0x33, 4));

        assertEquals(3, queue.size());
        assertEquals(0x31, command(queue.poll()));
    }

    @Test
    public void navigationFramesAreLatestOnlyPerCommand() {
        PendingFrameQueue queue = new PendingFrameQueue(80);

        queue.offer(frame(AdapterProtocol.CMD_MANEUVER, 1));
        queue.offer(frame(AdapterProtocol.CMD_ETA, 2));
        queue.offer(frame(AdapterProtocol.CMD_MANEUVER, 3));

        List<byte[]> frames = queue.snapshot();
        assertEquals(2, frames.size());
        assertEquals(AdapterProtocol.CMD_ETA, command(frames.get(0)));
        assertEquals(AdapterProtocol.CMD_MANEUVER, command(frames.get(1)));
        assertEquals(3, frames.get(1)[5] & 0xff);
    }

    @Test
    public void navigationOffInvalidatesStaleNavigationButKeepsOtherTraffic() {
        PendingFrameQueue queue = new PendingFrameQueue(80);

        queue.offer(frame(0x30, 9));
        queue.offer(frame(AdapterProtocol.CMD_NAV_ON, 1));
        queue.offer(frame(AdapterProtocol.CMD_MANEUVER, 4));
        queue.offer(frame(AdapterProtocol.CMD_ETA, 5));
        queue.offer(frame(AdapterProtocol.CMD_NAV_ON, 0));

        List<byte[]> frames = queue.snapshot();
        assertEquals(2, frames.size());
        assertEquals(0x30, command(frames.get(0)));
        assertEquals(AdapterProtocol.CMD_NAV_ON, command(frames.get(1)));
        assertEquals(0, frames.get(1)[5] & 0xff);
    }

    @Test
    public void reconnectFlushStartsWithNavigationStateThenLatestVisual() {
        PendingFrameQueue queue = new PendingFrameQueue(80);

        queue.offer(frame(AdapterProtocol.CMD_ETA, 5));
        queue.offer(frame(AdapterProtocol.CMD_NAV_TEXT, 6));
        queue.offer(frame(AdapterProtocol.CMD_NAV_ON, 1));
        queue.offer(frame(AdapterProtocol.CMD_MANEUVER, 7));

        assertEquals(AdapterProtocol.CMD_NAV_ON, command(queue.poll()));
        assertEquals(AdapterProtocol.CMD_MANEUVER, command(queue.poll()));
        assertEquals(AdapterProtocol.CMD_ETA, command(queue.poll()));
        assertEquals(AdapterProtocol.CMD_NAV_TEXT, command(queue.poll()));
    }

    private static byte[] frame(int command, int value) {
        return new byte[]{(byte) 0xBB, 0x41, (byte) 0xA1, 7,
                (byte) command, (byte) value, 0};
    }

    private static int command(byte[] frame) {
        return frame[4] & 0xff;
    }
}
