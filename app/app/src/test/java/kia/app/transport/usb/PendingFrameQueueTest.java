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
    public void fullProgressResetReplacesQueuedHalfProgressManeuver() {
        PendingFrameQueue queue = new PendingFrameQueue(80);

        queue.offer(AdapterProtocol.maneuver(
                "context_ra_turn_right", 300f, false, false, 5));
        queue.offer(AdapterProtocol.maneuver(
                "context_ra_turn_right", 300f, false, false, 9));

        List<byte[]> frames = queue.snapshot();
        assertEquals(1, frames.size());
        assertEquals(AdapterProtocol.CMD_MANEUVER, command(frames.get(0)));
        assertEquals(0x90, frames.get(0)[12] & 0xf0);
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

    @Test
    public void navigationOffKeepsTerminalSpeedLimitClearForReconnect() {
        PendingFrameQueue queue = new PendingFrameQueue(80);

        queue.offer(speedLimitFrame(60));
        queue.offer(speedLimitFrame(0));
        queue.offer(frame(AdapterProtocol.CMD_NAV_ON, 0));

        assertEquals(2, queue.size());
        assertEquals(AdapterProtocol.CMD_NAV_ON, command(queue.poll()));
        byte[] clear = queue.poll();
        assertEquals(AdapterProtocol.CMD_SPEED_LIMIT, command(clear));
        assertEquals(0, clear[6] & 0xff);
    }

    @Test
    public void navigationOffKeepsPositiveSpeedLimitForReconnect() {
        PendingFrameQueue queue = new PendingFrameQueue(80);

        queue.offer(speedLimitFrame(60));
        queue.offer(frame(AdapterProtocol.CMD_MANEUVER, 4));
        queue.offer(frame(AdapterProtocol.CMD_NAV_ON, 0));

        assertEquals(2, queue.size());
        assertEquals(AdapterProtocol.CMD_NAV_ON, command(queue.poll()));
        byte[] limit = queue.poll();
        assertEquals(AdapterProtocol.CMD_SPEED_LIMIT, command(limit));
        assertEquals(60, limit[6] & 0xff);
    }

    @Test
    public void speedLimitRemainsLatestOnlyAcrossRouteInvalidation() {
        PendingFrameQueue queue = new PendingFrameQueue(80);

        queue.offer(speedLimitFrame(60));
        queue.offer(speedLimitFrame(80));
        queue.offer(frame(AdapterProtocol.CMD_MANEUVER, 4));
        queue.invalidateNavigation();

        assertEquals(1, queue.size());
        byte[] limit = queue.poll();
        assertEquals(AdapterProtocol.CMD_SPEED_LIMIT, command(limit));
        assertEquals(80, limit[6] & 0xff);
    }

    @Test
    public void acknowledgementOwnedActionsAreNeverReplayed() {
        PendingFrameQueue queue = new PendingFrameQueue(80);

        queue.offer(frame(AdapterProtocol.CMD_FIRMWARE, 1));
        queue.offer(frame(AdapterProtocol.CMD_RAW_CAN_TX, 2));
        queue.offer(frame(AdapterProtocol.CMD_NAV_ON, 1));

        assertEquals(1, queue.size());
        assertEquals(AdapterProtocol.CMD_NAV_ON, command(queue.poll()));
    }

    private static byte[] frame(int command, int value) {
        return new byte[]{(byte) 0xBB, 0x41, (byte) 0xA1, 7,
                (byte) command, (byte) value, 0};
    }

    private static byte[] speedLimitFrame(int value) {
        return new byte[]{(byte) 0xBB, 0x41, (byte) 0xA1, 8,
                (byte) AdapterProtocol.CMD_SPEED_LIMIT, 1, (byte) value, 0};
    }

    private static int command(byte[] frame) {
        return frame[4] & 0xff;
    }
}
