package kia.app.navigation.capture

/** Pure gate preventing one-second navigator heartbeats from restarting an already live service. */
internal object NavServiceStartPolicy {
    @JvmStatic
    fun shouldStart(
        serviceRunning: Boolean,
        nowElapsed: Long,
        lastStartElapsed: Long,
        minIntervalMs: Long,
    ): Boolean {
        if (serviceRunning) return false
        if (lastStartElapsed <= 0L || nowElapsed < lastStartElapsed) return true
        return nowElapsed - lastStartElapsed >= Math.max(0L, minIntervalMs)
    }

    @JvmStatic
    fun shouldAcquireReceiverWakeLock(
        serviceWasRunning: Boolean,
        startRequestAccepted: Boolean,
    ): Boolean {
        return !serviceWasRunning && startRequestAccepted
    }
}
