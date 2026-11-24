package kia.app.transport.usb

import java.io.ByteArrayOutputStream
import java.util.ArrayList
import java.util.Arrays
import java.util.Collections
import kia.app.protocol.adapter.AdapterProtocol

/**
 * Thread-safe USB byte accumulator which only parses and returns complete frames.
 *
 * It deliberately has no listener callback. Calling application code while the accumulator is
 * locked creates an incoming-buffer -> transport lock order which can deadlock reconnect/close.
 */
internal class UsbIncomingBuffer {
    internal class ParseResult(frames: List<ByteArray?>?, badChecksumFrames: List<ByteArray?>?) {
        @JvmField val frames: List<ByteArray?>?
        @JvmField val badChecksumFrames: List<ByteArray?>?

        init {
            this.frames = frames
            this.badChecksumFrames = badChecksumFrames
        }

        companion object {
            @JvmStatic
            fun empty(): ParseResult {
                return ParseResult(Collections.emptyList(), Collections.emptyList())
            }
        }
    }

    private val incoming: ByteArrayOutputStream = ByteArrayOutputStream()

    @kotlin.jvm.Synchronized
    fun append(data: ByteArray?, count: Int): ParseResult {
        if (data == null || count <= 0) return ParseResult.empty()
        incoming.write(data, 0, Math.min(count, data.size))
        return parseLocked()
    }

    @kotlin.jvm.Synchronized
    fun reset() {
        incoming.reset()
    }

    @kotlin.jvm.Synchronized
    fun bufferedBytes(): Int {
        return incoming.size()
    }

    private fun parseLocked(): ParseResult {
        val data = incoming.toByteArray()
        val frames: MutableList<ByteArray?> = ArrayList()
        val badChecksums: MutableList<ByteArray?> = ArrayList()
        var index: Int = 0
        while (index < data.size) {
            val start: Int = UsbIncomingBuffer.findHeader(data, index)
            if (start < 0) {
                index = UsbIncomingBuffer.trailingHeaderPrefixStart(data, index)
                break
            }
            if (data.size - start < UsbIncomingBuffer.MIN_FRAME_BYTES) {
                index = start
                break
            }
            val len: Int = data.get(start + 3).toInt() and 0xff
            if (
                len < UsbIncomingBuffer.MIN_FRAME_BYTES || len > UsbIncomingBuffer.MAX_FRAME_BYTES
            ) {
                index = start + 1
                continue
            }
            if (data.size - start < len) {
                index = start
                break
            }
            val frame = Arrays.copyOfRange(data, start, start + len)
            if (AdapterProtocol.validChecksum(frame)) {
                frames.add(frame)
                index = start + len
            } else {
                badChecksums.add(frame)
                index = start + 1
            }
        }
        incoming.reset()
        if (index < data.size) incoming.write(data, index, data.size - index)
        return ParseResult(frames, badChecksums)
    }

    companion object {
        private const val MIN_FRAME_BYTES: Int = 6
        private const val MAX_FRAME_BYTES: Int = 128

        private fun findHeader(data: ByteArray, from: Int): Int {
            for (i in Math.max(0, from)..data.size - 3) {
                if ((data.get(i).toInt() and 0xff) != 0xBB) continue
                val second: Int = data.get(i + 1).toInt() and 0xff
                val third: Int = data.get(i + 2).toInt() and 0xff
                if ((second == 0xA1 && third == 0x41) || (second == 0x41 && third == 0xA1)) {
                    return i
                }
            }
            return -1
        }

        /** Retains a header prefix split across two USB reads instead of discarding it as noise. */
        private fun trailingHeaderPrefixStart(data: ByteArray?, from: Int): Int {
            val length: Int = if (data == null) 0 else data.size
            if (
                length >= 2 &&
                    (data!!.get(length - 2).toInt() and 0xff) == 0xBB &&
                    ((data.get(length - 1).toInt() and 0xff) == 0xA1 ||
                        (data.get(length - 1).toInt() and 0xff) == 0x41)
            ) {
                return Math.max(Math.max(0, from), length - 2)
            }
            if (length >= 1 && (data!!.get(length - 1).toInt() and 0xff) == 0xBB) {
                return Math.max(Math.max(0, from), length - 1)
            }
            return length
        }
    }
}
