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
 * coalesced by command id, and navigation-off invalidates every older route frame. The road speed
 * limit is an independent latest-only state and survives route shutdown for replay after reconnect.
 */
final class PendingFrameQueue {
    private final int maxSize;
    private final ArrayDeque<byte[]> frames = new ArrayDeque<>();

    PendingFrameQueue(int maxSize) {
        this.maxSize = Math.max(1, maxSize);
    }

    boolean offer(byte[] frame) {
        if (frame == null || frame.length == 0) return false;
        if (!isReplaySafe(frame)) return false;
        if (isNavigationFrame(frame)) {
            if (isNavigationOff(frame)) {
                invalidateNavigation();
            } else {
                removeSemantic(frame);
            }
        }
        while (frames.size() >= maxSize) frames.pollFirst();
        frames.offerLast(frame);
        return true;
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
            byte[] queued = iterator.next();
            if (isNavigationFrame(queued) && !isSpeedLimitFrame(queued)) {
                iterator.remove();
            }
        }
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

    static boolean isSpeedLimitFrame(byte[] frame) {
        return command(frame) == AdapterProtocol.CMD_SPEED_LIMIT;
    }

    /** Firmware blocks and raw CAN actions have their own acknowledgement/retry owners. */
    static boolean isReplaySafe(byte[] frame) {
        int command = command(frame);
        return command != AdapterProtocol.CMD_FIRMWARE
                && command != AdapterProtocol.CMD_RAW_CAN_TX;
    }

    private static int command(byte[] frame) {
        return frame == null || frame.length < 5 ? -1 : frame[4] & 0xff;
    }
}
