package kia.app.transport.usb

import java.util.ArrayDeque
import java.util.ArrayList
import kia.app.protocol.adapter.AdapterProtocol

/**
 * Bounded offline queue with latest-only semantics for navigation state.
 *
 * Vehicle/media commands retain FIFO ordering. Navigation frames describe current UI state, so
 * replaying every historical distance or maneuver after reconnect is actively harmful. They are
 * coalesced by command id, and navigation-off invalidates every older route frame. Road speed
 * limits are live observations and are never queued: replay could restore a stale sign or bypass
 * the current route-only output setting after reconnect.
 */
internal class PendingFrameQueue(maxSize: Int) {
    private val maxSize: Int
    private val frames: ArrayDeque<ByteArray?> = ArrayDeque()

    init {
        this.maxSize = Math.max(1, maxSize)
    }

    fun offer(frame: ByteArray?): Boolean {
        if (frame == null || frame.size == 0) return false
        if (!PendingFrameQueue.isReplaySafe(frame)) return false
        if (PendingFrameQueue.isNavigationFrame(frame)) {
            if (PendingFrameQueue.isNavigationOff(frame)) {
                invalidateNavigation()
            } else {
                removeSemantic(frame)
            }
        }
        while (frames.size >= maxSize) frames.pollFirst()
        frames.offerLast(frame)
        return true
    }

    fun poll(): ByteArray? {
        val navigationOrder: IntArray =
            intArrayOf(
                AdapterProtocol.CMD_NAV_ON,
                AdapterProtocol.CMD_MANEUVER,
                AdapterProtocol.CMD_ETA,
                AdapterProtocol.CMD_ETA_TIME,
                AdapterProtocol.CMD_NAV_TEXT,
            )
        for (command: Int in navigationOrder) {
            val iterator: MutableIterator<ByteArray?>? = frames.iterator()
            while (iterator!!.hasNext()) {
                val frame = iterator.next()
                if (PendingFrameQueue.command(frame) != command) continue
                iterator.remove()
                return frame
            }
        }
        return frames.pollFirst()
    }

    fun size(): Int {
        return frames.size
    }

    fun snapshot(): List<ByteArray?>? {
        return ArrayList(frames)
    }

    fun invalidateNavigation() {
        val iterator: MutableIterator<ByteArray?>? = frames.iterator()
        while (iterator!!.hasNext()) {
            val queued = iterator.next()
            if (PendingFrameQueue.isNavigationFrame(queued)) {
                iterator.remove()
            }
        }
    }

    private fun removeSemantic(incoming: ByteArray?) {
        val command: Int = PendingFrameQueue.command(incoming)
        val iterator: MutableIterator<ByteArray?>? = frames.iterator()
        while (iterator!!.hasNext()) {
            val queued = iterator.next()
            if (
                PendingFrameQueue.isNavigationFrame(queued) &&
                    PendingFrameQueue.command(queued) == command
            )
                iterator.remove()
        }
    }

    companion object {
        @JvmStatic
        fun isNavigationFrame(frame: ByteArray?): Boolean {
            val command: Int = PendingFrameQueue.command(frame)
            return command == AdapterProtocol.CMD_SPEED_LIMIT ||
                command == AdapterProtocol.CMD_MANEUVER ||
                command == AdapterProtocol.CMD_ETA ||
                command == AdapterProtocol.CMD_NAV_ON ||
                command == AdapterProtocol.CMD_ETA_TIME ||
                command == AdapterProtocol.CMD_NAV_TEXT
        }

        @JvmStatic
        fun isNavigationOff(frame: ByteArray): Boolean {
            return PendingFrameQueue.command(frame) == AdapterProtocol.CMD_NAV_ON &&
                frame.size > 5 &&
                (frame.get(5).toInt() and 0xff) == 0
        }

        @JvmStatic
        /** Firmware/raw actions own their retries; speed signs require a new live observation. */
        fun isReplaySafe(frame: ByteArray?): Boolean {
            val command: Int = PendingFrameQueue.command(frame)
            return command != AdapterProtocol.CMD_SPEED_LIMIT &&
                command != AdapterProtocol.CMD_FIRMWARE &&
                command != AdapterProtocol.CMD_RAW_CAN_TX
        }

        private fun command(frame: ByteArray?): Int {
            return if (frame == null || frame.size < 5) -1 else frame.get(4).toInt() and 0xff
        }
    }
}
