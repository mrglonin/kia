package kia.app.transport.usb;

import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import kia.app.protocol.adapter.AdapterProtocol;

/**
 * Thread-safe USB byte accumulator which only parses and returns complete frames.
 *
 * <p>It deliberately has no listener callback. Calling application code while the accumulator is
 * locked creates an incoming-buffer -> transport lock order which can deadlock reconnect/close.
 */
final class UsbIncomingBuffer {
    private static final int MIN_FRAME_BYTES = 6;
    private static final int MAX_FRAME_BYTES = 128;

    static final class ParseResult {
        final List<byte[]> frames;
        final List<byte[]> badChecksumFrames;

        ParseResult(List<byte[]> frames, List<byte[]> badChecksumFrames) {
            this.frames = frames;
            this.badChecksumFrames = badChecksumFrames;
        }

        static ParseResult empty() {
            return new ParseResult(Collections.emptyList(), Collections.emptyList());
        }
    }

    private final ByteArrayOutputStream incoming = new ByteArrayOutputStream();

    synchronized ParseResult append(byte[] data, int count) {
        if (data == null || count <= 0) return ParseResult.empty();
        incoming.write(data, 0, Math.min(count, data.length));
        return parseLocked();
    }

    synchronized void reset() {
        incoming.reset();
    }

    synchronized int bufferedBytes() {
        return incoming.size();
    }

    private ParseResult parseLocked() {
        byte[] data = incoming.toByteArray();
        List<byte[]> frames = new ArrayList<>();
        List<byte[]> badChecksums = new ArrayList<>();
        int index = 0;
        while (index < data.length) {
            int start = findHeader(data, index);
            if (start < 0) {
                index = trailingHeaderPrefixStart(data, index);
                break;
            }
            if (data.length - start < MIN_FRAME_BYTES) {
                index = start;
                break;
            }
            int len = data[start + 3] & 0xff;
            if (len < MIN_FRAME_BYTES || len > MAX_FRAME_BYTES) {
                index = start + 1;
                continue;
            }
            if (data.length - start < len) {
                index = start;
                break;
            }
            byte[] frame = Arrays.copyOfRange(data, start, start + len);
            if (AdapterProtocol.validChecksum(frame)) {
                frames.add(frame);
                index = start + len;
            } else {
                badChecksums.add(frame);
                index = start + 1;
            }
        }
        incoming.reset();
        if (index < data.length) incoming.write(data, index, data.length - index);
        return new ParseResult(frames, badChecksums);
    }

    private static int findHeader(byte[] data, int from) {
        for (int i = Math.max(0, from); i <= data.length - 3; i++) {
            if ((data[i] & 0xff) != 0xBB) continue;
            int second = data[i + 1] & 0xff;
            int third = data[i + 2] & 0xff;
            if ((second == 0xA1 && third == 0x41)
                    || (second == 0x41 && third == 0xA1)) {
                return i;
            }
        }
        return -1;
    }

    /** Retains a header prefix split across two USB reads instead of discarding it as noise. */
    private static int trailingHeaderPrefixStart(byte[] data, int from) {
        int length = data == null ? 0 : data.length;
        if (length >= 2
                && (data[length - 2] & 0xff) == 0xBB
                && ((data[length - 1] & 0xff) == 0xA1
                || (data[length - 1] & 0xff) == 0x41)) {
            return Math.max(Math.max(0, from), length - 2);
        }
        if (length >= 1 && (data[length - 1] & 0xff) == 0xBB) {
            return Math.max(Math.max(0, from), length - 1);
        }
        return length;
    }
}
