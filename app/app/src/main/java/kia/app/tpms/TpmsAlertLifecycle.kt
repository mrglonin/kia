package kia.app.tpms

import java.util.Arrays

/** Stable alarm episodes: measurements can change without creating a new alarm. */
internal class TpmsAlertLifecycle(causeSlots: Int) {
    private val peakLevels: IntArray
    private val normalSince: LongArray
    private var active: Boolean = false
    private var generation: Long = 0
    private var dismissedGeneration: Long = -1L
    private var notifiedGeneration: Long = -1L
    private var notifiedAt: Long = -1L
    private var lastObservedAt: Long = -1L

    init {
        peakLevels = IntArray(causeSlots)
        normalSince = LongArray(causeSlots)
        reset()
    }

    fun observe(levels: IntArray?, nowElapsed: Long) {
        val now: Long = monotonic(nowElapsed)
        var nextActive: Boolean = false
        var escalated: Boolean = false
        for (slot in peakLevels.indices) {
            val level: Int =
                if (levels != null && slot < levels.size) Math.max(0, Math.min(2, levels.get(slot)))
                else 0
            if (
                normalSince.get(slot) >= 0L &&
                    now - normalSince.get(slot) >= TpmsAlertLifecycle.RECOVERY_DWELL_MS
            ) {
                peakLevels[slot] = 0
                normalSince[slot] = -1L
            }
            if (level > 0) {
                nextActive = true
                normalSince[slot] = -1L
                if (level > peakLevels.get(slot)) {
                    peakLevels[slot] = level
                    escalated = true
                }
            } else if (peakLevels.get(slot) > 0 && normalSince.get(slot) < 0L) {
                normalSince[slot] = now
            }
        }
        if (escalated) generation++
        active = nextActive
    }

    fun shouldShow(): Boolean {
        return active && dismissedGeneration != generation
    }

    fun dismiss() {
        if (active) dismissedGeneration = generation
    }

    fun shouldNotify(nowElapsed: Long): Boolean {
        val now: Long = monotonic(nowElapsed)
        if (!active) return false
        if (
            notifiedGeneration == generation &&
                notifiedAt >= 0L &&
                now - notifiedAt < TpmsAlertLifecycle.REPEAT_MS
        )
            return false
        notifiedGeneration = generation
        notifiedAt = now
        return true
    }

    fun reset() {
        Arrays.fill(peakLevels, 0)
        Arrays.fill(normalSince, -1L)
        active = false
        generation = 0L
        dismissedGeneration = -1L
        notifiedGeneration = -1L
        notifiedAt = -1L
        lastObservedAt = -1L
    }

    private fun monotonic(value: Long): Long {
        lastObservedAt = Math.max(lastObservedAt, Math.max(0L, value))
        return lastObservedAt
    }

    companion object {
        const val RECOVERY_DWELL_MS: Long = 30000L
        const val REPEAT_MS: Long = 60000L
    }
}
