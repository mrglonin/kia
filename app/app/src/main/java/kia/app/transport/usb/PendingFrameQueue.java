package kia.app.transport.usb;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

import kia.app.protocol.adapter.AdapterProtocol;

/**
 * Bounded offline queue with latest-only semantics for navigation state.
 *
 * <p>Vehicle/media commands retain FIFO ordering. Navigation frames describe current UI state, so
 * replaying every historical distance or maneuver after reconnect is actively harmful. They are
 * coalesced by command id, and navigation-off invalidates every older navigation frame.
 */
final class PendingFrameQueue {
    private final int maxSize;
    private final ArrayDeque<byte[]> frames = new ArrayDeque<>();

    PendingFrameQueue(int maxSize) {
        this.maxSize = Math.max(1, maxSize);
    }

    void offer(byte[] frame) {
        if (frame == null || frame.length == 0) return;
        if (isNavigationFrame(frame)) {
            if (isNavigationOff(frame)) {
                byte[] terminalSpeedClear = latestSpeedLimitClear();
                invalidateNavigation();
                if (terminalSpeedClear != null) frames.offerLast(terminalSpeedClear);
            } else {
                removeSemantic(frame);
            }
        }
        while (frames.size() >= maxSize) frames.pollFirst();
        frames.offerLast(frame);
    }

    byte[] poll() {
        int[] navigationOrder = new int[]{
                AdapterProtocol.CMD_NAV_ON,
                AdapterProtocol.CMD_MANEUVER,
                AdapterProtocol.CMD_ETA,
                AdapterProtocol.CMD_ETA_TIME,
                AdapterProtocol.CMD_NAV_TEXT,
                AdapterProtocol.CMD_SPEED_LIMIT
        };
        for (int command : navigationOrder) {
            Iterator<byte[]> iterator = frames.iterator();
            while (iterator.hasNext()) {
                byte[] frame = iterator.next();
                if (command(frame) != command) continue;
                iterator.remove();
                return frame;
            }
        }
        return frames.pollFirst();
    }

    int size() {
        return frames.size();
    }

    List<byte[]> snapshot() {
        return new ArrayList<>(frames);
    }

    void invalidateNavigation() {
        Iterator<byte[]> iterator = frames.iterator();
        while (iterator.hasNext()) {
            if (isNavigationFrame(iterator.next())) iterator.remove();
        }
    }

    private byte[] latestSpeedLimitClear() {
        byte[] latest = null;
        for (byte[] queued : frames) {
            if (isSpeedLimitClear(queued)) latest = queued;
        }
        return latest;
    }

    private void removeSemantic(byte[] incoming) {
        int command = command(incoming);
        Iterator<byte[]> iterator = frames.iterator();
        while (iterator.hasNext()) {
            byte[] queued = iterator.next();
            if (isNavigationFrame(queued) && command(queued) == command) iterator.remove();
        }
    }

    static boolean isNavigationFrame(byte[] frame) {
        int command = command(frame);
        return command == AdapterProtocol.CMD_SPEED_LIMIT
                || command == AdapterProtocol.CMD_MANEUVER
                || command == AdapterProtocol.CMD_ETA
                || command == AdapterProtocol.CMD_NAV_ON
                || command == AdapterProtocol.CMD_ETA_TIME
                || command == AdapterProtocol.CMD_NAV_TEXT;
    }

    static boolean isNavigationOff(byte[] frame) {
        return command(frame) == AdapterProtocol.CMD_NAV_ON
                && frame.length > 5
                && (frame[5] & 0xff) == 0;
    }

    static boolean isSpeedLimitClear(byte[] frame) {
        return command(frame) == AdapterProtocol.CMD_SPEED_LIMIT
                && frame.length > 6
                && (frame[6] & 0xff) == 0;
    }

    private static int command(byte[] frame) {
        return frame == null || frame.length < 5 ? -1 : frame[4] & 0xff;
    }
}
