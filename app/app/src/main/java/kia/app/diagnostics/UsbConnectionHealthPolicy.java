package kia.app.diagnostics;

/** Pure liveness decision for detecting a logically open but silent USB adapter connection. */
final class UsbConnectionHealthPolicy {
    private UsbConnectionHealthPolicy() {
    }

    static boolean shouldReconnect(boolean ready, long connectedAtElapsed,
                                   long lastRxAtElapsed, long nowElapsed,
                                   long startupGraceMs, long staleRxMs,
                                   int completedProbeBursts, int requiredProbeBursts) {
        if (!ready || connectedAtElapsed <= 0L || nowElapsed < connectedAtElapsed) return false;
        if (completedProbeBursts < Math.max(1, requiredProbeBursts)) return false;
        if (lastRxAtElapsed <= 0L) {
            return nowElapsed - connectedAtElapsed >= Math.max(0L, startupGraceMs);
        }
        if (nowElapsed < lastRxAtElapsed) return true;
        return nowElapsed - lastRxAtElapsed >= Math.max(0L, staleRxMs);
    }
}
