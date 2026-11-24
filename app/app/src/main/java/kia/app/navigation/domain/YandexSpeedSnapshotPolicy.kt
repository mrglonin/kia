package kia.app.navigation.domain

/** Pure semantic policy for cheap, repeated Yandex free-drive speed snapshots. */
object YandexSpeedSnapshotPolicy {
    @JvmStatic
    fun meaningfulPayload(
        currentSpeedKmh: Int,
        roadLimitPresent: Boolean,
        cameraLimit: String?,
    ): Boolean {
        return currentSpeedKmh >= 0 ||
            roadLimitPresent ||
            (cameraLimit != null && !cameraLimit.trim().isEmpty())
    }

    @JvmStatic
    fun matchesStoredState(
        yandexSource: Boolean,
        storedCurrentSpeedKmh: Int,
        incomingCurrentSpeedKmh: Int,
        storedRoadLimitKmh: Int,
        incomingRoadLimitPresent: Boolean,
        incomingRoadLimitKmh: Int,
        storedExceeded: Boolean,
        incomingExceededPresent: Boolean,
        incomingExceeded: Boolean,
    ): Boolean {
        if (!yandexSource) return false
        if (incomingCurrentSpeedKmh >= 0 && storedCurrentSpeedKmh != incomingCurrentSpeedKmh) {
            return false
        }
        if (incomingRoadLimitPresent) {
            val expectedLimit: Int = Math.max(0, incomingRoadLimitKmh)
            if (Math.max(0, storedRoadLimitKmh) != expectedLimit) return false
        }
        return !incomingExceededPresent || storedExceeded == incomingExceeded
    }
}
