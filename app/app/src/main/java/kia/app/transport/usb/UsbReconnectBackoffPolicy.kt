package kia.app.transport.usb

/**
 * Monotonic reconnect delay used to prevent a persistent queued-write failure from busy-looping.
 */
internal object UsbReconnectBackoffPolicy {
    @JvmStatic
    fun extendNotBefore(currentNotBefore: Long, nowElapsed: Long, backoffMs: Long): Long {
        val delay: Long = Math.max(0L, backoffMs)
        val candidate: Long =
            if (nowElapsed > Long.MAX_VALUE - delay) Long.MAX_VALUE else nowElapsed + delay
        return Math.max(currentNotBefore, candidate)
    }

    @JvmStatic
    fun effectiveDelay(requestedDelayMs: Long, nowElapsed: Long, notBeforeElapsed: Long): Long {
        val remaining: Long =
            if (notBeforeElapsed > nowElapsed) notBeforeElapsed - nowElapsed else 0L
        return Math.max(Math.max(0L, requestedDelayMs), remaining)
    }
}
