package kia.app.tpms

/** Pure visibility rule for the speed badges drawn by the TPMS dashboard. */
object DashboardSpeedVisibilityPolicy {
    @JvmStatic
    fun resolve(
        moving: Boolean,
        currentSpeedKmh: Int,
        speedLimitKmh: Int,
    ): DashboardSpeedVisibilityPolicy.Decision {
        val showLimit: Boolean = speedLimitKmh > 0
        val showCurrent: Boolean = moving && currentSpeedKmh > 0
        return DashboardSpeedVisibilityPolicy.Decision(showCurrent, showLimit)
    }

    class Decision internal constructor(showCurrentSpeed: Boolean, showSpeedLimit: Boolean) {
        @JvmField val showCurrentSpeed: Boolean
        @JvmField val showSpeedLimit: Boolean

        init {
            this.showCurrentSpeed = showCurrentSpeed
            this.showSpeedLimit = showSpeedLimit
        }
    }
}
