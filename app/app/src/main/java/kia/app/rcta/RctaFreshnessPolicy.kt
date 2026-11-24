package kia.app.rcta

/**
 * Tracks whether the active RCTA state is still backed by a live stream of adapter frames.
 *
 * The caller supplies a monotonic time source (normally `SystemClock.elapsedRealtime()`). Repeated
 * frames are observations even when their left/right payload is unchanged, so they intentionally
 * refresh the deadline.
 */
internal class RctaFreshnessPolicy(timeoutMs: Long) {
    private val timeoutMs: Long
    private var lastActiveFrameAtMs: Long = RctaFreshnessPolicy.NO_ACTIVE_FRAME

    init {
        require(timeoutMs > 0L) { "timeoutMs must be positive" }
        this.timeoutMs = timeoutMs
    }

    fun observeFrame(active: Boolean, nowMs: Long) {
        lastActiveFrameAtMs =
            if (active) Math.max(0L, nowMs) else RctaFreshnessPolicy.NO_ACTIVE_FRAME
    }

    fun remainingMs(nowMs: Long): Long {
        if (lastActiveFrameAtMs == RctaFreshnessPolicy.NO_ACTIVE_FRAME) return 0L
        val safeNow: Long = Math.max(0L, nowMs)
        if (safeNow < lastActiveFrameAtMs) return timeoutMs
        val ageMs: Long = safeNow - lastActiveFrameAtMs
        return if (ageMs >= timeoutMs) 0L else timeoutMs - ageMs
    }

    fun hasActiveObservation(): Boolean {
        return lastActiveFrameAtMs != RctaFreshnessPolicy.NO_ACTIVE_FRAME
    }

    fun clear() {
        lastActiveFrameAtMs = RctaFreshnessPolicy.NO_ACTIVE_FRAME
    }

    companion object {
        private val NO_ACTIVE_FRAME: Long = -1L
    }
}
