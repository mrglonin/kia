package kia.app.navigation.domain

/** A road limit received in this process, timestamped independently of other navigation data. */
class DashboardRoadSpeedLimit
private constructor(source: Int, kmh: Int, observedAt: Long, changedAt: Long) {
    @JvmField val source: Int
    private val kmh: Int
    private val observedAt: Long
    @JvmField val changedAt: Long

    init {
        this.source = source
        this.kmh = kmh
        this.observedAt = observedAt
        this.changedAt = changedAt
    }

    fun observe(source: Int, kmh: Int, now: Long): DashboardRoadSpeedLimit {
        if (kmh <= 0 || kmh > 255 || now < 0) return DashboardRoadSpeedLimit.NONE
        val change: Long = if (this.source == source && valueAt(now) == kmh) changedAt else now
        return DashboardRoadSpeedLimit(source, kmh, now, change)
    }

    fun observe(source: Int, rawKmh: String?, now: Long): DashboardRoadSpeedLimit {
        // The supported integrations supply km/h as a number. Do not extract a number
        // from an unrelated string or turn a malformed negative limit into a sign.
        if (rawKmh == null) return DashboardRoadSpeedLimit.NONE
        try {
            return observe(source, Integer.parseInt(rawKmh.trim()), now)
        } catch (e: NumberFormatException) {
            return DashboardRoadSpeedLimit.NONE
        }
    }

    fun remainingMs(now: Long): Long {
        if (kmh == 0 || now < observedAt) return 0L
        return Math.max(0L, RoadSpeedLimitRepeatPolicy.FRESH_MS - (now - observedAt))
    }

    fun valueAt(now: Long): Int {
        return if (remainingMs(now) > 0) kmh else 0
    }

    companion object {
        @JvmField val NONE: DashboardRoadSpeedLimit = DashboardRoadSpeedLimit(0, 0, 0L, 0L)
    }
}
