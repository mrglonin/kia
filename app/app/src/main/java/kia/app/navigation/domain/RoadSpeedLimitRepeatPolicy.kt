package kia.app.navigation.domain

/** Repeats a known positive road sign while fresh source samples keep its lease alive. */
class RoadSpeedLimitRepeatPolicy {
    private var value: Int = 0
    private var lastFreshAt: Long = 0
    private var nextAt: Long = 0

    /** A same-value sample renews freshness without postponing the scheduled repeat. */
    fun observeFresh(kmh: Int, now: Long) {
        if (kmh <= 0 || kmh > 255 || now < 0L || (value > 0 && now < lastFreshAt)) {
            cancel()
            return
        }
        if (value != kmh) {
            value = kmh
            nextAt = RoadSpeedLimitRepeatPolicy.nextDeadline(now)
        }
        lastFreshAt = now
    }

    /** Reading a due value does not consume it; the caller schedules the next attempt. */
    fun dueValue(now: Long): Int {
        return if (fresh(now) && now >= nextAt) value else 0
    }

    /** Zero means disarmed; an overdue active repeat returns one millisecond. */
    fun delayUntilNext(now: Long): Long {
        if (!fresh(now)) return 0L
        return Math.max(1L, nextAt - now)
    }

    fun currentValue(): Int {
        return value
    }

    /** An attempt or successful retry moves the deadline, but never renews source freshness. */
    fun scheduleNext(kmh: Int, now: Long) {
        if (value <= 0 || kmh != value || !fresh(now)) return
        nextAt = RoadSpeedLimitRepeatPolicy.nextDeadline(now)
    }

    fun cancel() {
        value = 0
        lastFreshAt = 0L
        nextAt = 0L
    }

    private fun fresh(now: Long): Boolean {
        if (value <= 0) return false
        if (now < lastFreshAt || now - lastFreshAt > RoadSpeedLimitRepeatPolicy.FRESH_MS) {
            cancel()
            return false
        }
        return true
    }

    companion object {
        const val INTERVAL_MS: Long = 15000L
        const val FRESH_MS: Long = 12000L

        private fun nextDeadline(now: Long): Long {
            return if (now > Long.MAX_VALUE - RoadSpeedLimitRepeatPolicy.INTERVAL_MS) Long.MAX_VALUE
            else now + RoadSpeedLimitRepeatPolicy.INTERVAL_MS
        }
    }
}
