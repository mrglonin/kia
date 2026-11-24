package kia.app.update

/** Shared wall-clock policy for launch and foreground-service OTA checks. */
object AppUpdateCheckPolicy {
    const val SUCCESS_INTERVAL_MS: Long = 60L * 60L * 1000L
    const val BACKGROUND_POLL_MS: Long = 15L * 60L * 1000L

    @JvmStatic
    fun shouldCheck(nowMillis: Long, lastSuccessfulCheckMillis: Long): Boolean {
        if (nowMillis <= 0L || lastSuccessfulCheckMillis <= 0L) return true
        if (nowMillis < lastSuccessfulCheckMillis) return true
        return nowMillis - lastSuccessfulCheckMillis >= AppUpdateCheckPolicy.SUCCESS_INTERVAL_MS
    }
}
