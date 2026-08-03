package kia.app.transport.usb;

/** Monotonic reconnect delay used to prevent a persistent queued-write failure from busy-looping. */
final class UsbReconnectBackoffPolicy {
    private UsbReconnectBackoffPolicy() {
    }

    static long extendNotBefore(long currentNotBefore, long nowElapsed, long backoffMs) {
        long delay = Math.max(0L, backoffMs);
        long candidate = nowElapsed > Long.MAX_VALUE - delay
                ? Long.MAX_VALUE : nowElapsed + delay;
        return Math.max(currentNotBefore, candidate);
    }

    static long effectiveDelay(long requestedDelayMs, long nowElapsed, long notBeforeElapsed) {
        long remaining = notBeforeElapsed > nowElapsed
                ? notBeforeElapsed - nowElapsed : 0L;
        return Math.max(Math.max(0L, requestedDelayMs), remaining);
    }
}
