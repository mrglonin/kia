package kia.app.navigation.domain

/** Positive road-sign transitions, with duplicate samples suppressed after successful delivery. */
class RoadSpeedLimitTxPolicy {
    private var lastObserved: Int = 0
    private var lastWritten: Int = 0
    private var writtenForObservedSign: Boolean = false
    private var allowed: Boolean = false
    private var firstInScope: Boolean = true
    private var pending: Int = 0
    private var pendingSince: Long = 0

    fun setAllowed(nextAllowed: Boolean) {
        if (allowed != nextAllowed) firstInScope = true
        allowed = nextAllowed
        if (!allowed) cancelPending()
    }

    /** A changed positive value starts a new sign event, even when an earlier write failed. */
    fun shouldSend(kmh: Int, outputAllowed: Boolean): Boolean {
        setAllowed(outputAllowed)
        if (kmh <= 0 || kmh > 255) return false
        if (kmh != lastObserved) {
            lastObserved = kmh
            writtenForObservedSign = false
        }
        return allowed && (firstInScope || !writtenForObservedSign)
    }

    fun written(kmh: Int) {
        if (kmh <= 0 || kmh > 255) return
        lastWritten = kmh
        if (lastObserved == 0) lastObserved = kmh
        if (kmh == lastObserved) writtenForObservedSign = true
        firstInScope = false
        cancelPending()
    }

    /** Only a fresh source sample may create or refresh this short delivery lease. */
    fun acceptFreshSample(kmh: Int, outputAllowed: Boolean, nowElapsed: Long): Boolean {
        cancelPending()
        if (!shouldSend(kmh, outputAllowed)) return false
        pending = kmh
        pendingSince = nowElapsed
        return true
    }

    /** A scheduled repeat reopens delivery only for the currently observed positive sign. */
    fun beginRepeat(kmh: Int, outputAllowed: Boolean, nowElapsed: Long): Boolean {
        setAllowed(outputAllowed)
        if (!allowed || kmh <= 0 || kmh > 255 || kmh != lastObserved) return false
        writtenForObservedSign = false
        pending = kmh
        pendingSince = nowElapsed
        return true
    }

    /** A retry never refreshes the lease, even if the transport remains blocked. */
    fun pendingValue(nowElapsed: Long, outputAllowed: Boolean): Int {
        setAllowed(outputAllowed)
        if (
            pending <= 0 ||
                nowElapsed < pendingSince ||
                nowElapsed - pendingSince >= RoadSpeedLimitTxPolicy.RETRY_WINDOW_MS
        ) {
            cancelPending()
        }
        return pending
    }

    fun cancelPending() {
        pending = 0
        pendingSince = 0L
    }

    fun lastObservedValue(): Int {
        return lastObserved
    }

    fun lastWrittenValue(): Int {
        return lastWritten
    }

    companion object {
        const val RETRY_WINDOW_MS: Long = 3000L
    }
}
