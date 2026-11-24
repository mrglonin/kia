package kia.app.diagnostics

/** Pure liveness decision for detecting a logically open but silent USB adapter connection. */
internal object UsbConnectionHealthPolicy {
    @JvmStatic
    fun shouldReconnect(
        ready: Boolean,
        connectedAtElapsed: Long,
        lastRxAtElapsed: Long,
        nowElapsed: Long,
        startupGraceMs: Long,
        staleRxMs: Long,
        completedProbeBursts: Int,
        requiredProbeBursts: Int,
    ): Boolean {
        if (!ready || connectedAtElapsed <= 0L || nowElapsed < connectedAtElapsed) return false
        if (completedProbeBursts < Math.max(1, requiredProbeBursts)) return false
        if (lastRxAtElapsed <= 0L) {
            return nowElapsed - connectedAtElapsed >= Math.max(0L, startupGraceMs)
        }
        if (nowElapsed < lastRxAtElapsed) return true
        return nowElapsed - lastRxAtElapsed >= Math.max(0L, staleRxMs)
    }
}
