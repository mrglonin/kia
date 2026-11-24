package kia.app.navigation.compass

/** Pure source/freshness policy for normal compass heading updates. */
internal object CompassLocationPolicy {
    const val MIN_GPS_BEARING_SPEED_MPS: Float = 1.4f

    @JvmStatic
    fun usableGpsBearing(
        hasBearing: Boolean,
        bearingDegrees: Float,
        hasSpeed: Boolean,
        speedMetersPerSecond: Float,
    ): Boolean {
        if (
            !hasBearing ||
                java.lang.Float.isNaN(bearingDegrees) ||
                java.lang.Float.isInfinite(bearingDegrees)
        ) {
            return false
        }
        // GPS bearing is a course-over-ground value, not a stationary heading. Without a
        // trustworthy speed we cannot prove that the vehicle is moving, so keeping the last
        // confirmed compass step is safer than accepting the common idle bearing of 0 degrees.
        if (!hasSpeed) return false
        return !java.lang.Float.isNaN(speedMetersPerSecond) &&
            !java.lang.Float.isInfinite(speedMetersPerSecond) &&
            speedMetersPerSecond >= CompassLocationPolicy.MIN_GPS_BEARING_SPEED_MPS
    }

    @JvmStatic
    fun freshSample(nowElapsedNanos: Long, fixElapsedNanos: Long, maxAgeMs: Long): Boolean {
        if (
            maxAgeMs < 0L ||
                nowElapsedNanos <= 0L ||
                fixElapsedNanos <= 0L ||
                fixElapsedNanos > nowElapsedNanos
        ) {
            return false
        }
        val maxAgeNanos: Long =
            if (maxAgeMs > Long.MAX_VALUE / 1000000L) Long.MAX_VALUE else maxAgeMs * 1000000L
        return nowElapsedNanos - fixElapsedNanos <= maxAgeNanos
    }

    @JvmStatic
    fun newerSample(fixElapsedNanos: Long, previousElapsedNanos: Long): Boolean {
        return fixElapsedNanos > 0L && fixElapsedNanos > previousElapsedNanos
    }

    @JvmStatic
    fun stationarySpeed(hasSpeed: Boolean, speedMetersPerSecond: Float): Boolean {
        return hasSpeed &&
            !java.lang.Float.isNaN(speedMetersPerSecond) &&
            !java.lang.Float.isInfinite(speedMetersPerSecond) &&
            speedMetersPerSecond >= 0f &&
            speedMetersPerSecond < CompassLocationPolicy.MIN_GPS_BEARING_SPEED_MPS
    }

    @JvmStatic
    fun newerSpeedSample(
        hasSpeed: Boolean,
        speedMetersPerSecond: Float,
        fixElapsedNanos: Long,
        previousElapsedNanos: Long,
        previousSpeedMetersPerSecond: Float,
    ): Boolean {
        if (
            !hasSpeed ||
                !java.lang.Float.isFinite(speedMetersPerSecond) ||
                speedMetersPerSecond < 0f
        ) {
            return false
        }
        return CompassLocationPolicy.newerSample(fixElapsedNanos, previousElapsedNanos) ||
            (fixElapsedNanos > 0L &&
                fixElapsedNanos == previousElapsedNanos &&
                CompassLocationPolicy.stationarySpeed(true, speedMetersPerSecond) &&
                previousSpeedMetersPerSecond >= CompassLocationPolicy.MIN_GPS_BEARING_SPEED_MPS)
    }

    @JvmStatic
    fun effectiveSensorAccuracy(callbackAccuracy: Int, eventAccuracy: Int): Int {
        // Some sensor stacks omit the initial onAccuracyChanged callback but put a valid
        // accuracy directly on SensorEvent. Conversely, a few stacks leave event accuracy at
        // zero after a reliable callback, so do not downgrade on that missing value alone.
        if (eventAccuracy > 0 || callbackAccuracy <= 0) return eventAccuracy
        return callbackAccuracy
    }

    @JvmStatic
    fun usableSensorHeading(headingDegrees: Float, sensorAccuracy: Int): Boolean {
        // SENSOR_STATUS_UNRELIABLE also means "accuracy unknown" on a number of vendor
        // rotation-vector implementations. A finite heading is still the only useful
        // stationary source; callers keep it low-confidence instead of treating it as a
        // reason to permanently suppress the compass.
        return !java.lang.Float.isNaN(headingDegrees) && !java.lang.Float.isInfinite(headingDegrees)
    }

    @JvmStatic
    fun sensorStreamNeedsRecovery(
        registered: Boolean,
        nowElapsedMs: Long,
        registeredAtElapsedMs: Long,
        lastUsableEventElapsedMs: Long,
        staleAfterMs: Long,
        lastRecoveryElapsedMs: Long,
        recoveryCooldownMs: Long,
    ): Boolean {
        if (
            !registered ||
                nowElapsedMs <= 0L ||
                registeredAtElapsedMs <= 0L ||
                staleAfterMs < 0L ||
                recoveryCooldownMs < 0L
        ) {
            return false
        }
        val freshnessAnchor: Long =
            if (lastUsableEventElapsedMs > 0L) lastUsableEventElapsedMs else registeredAtElapsedMs
        if (freshnessAnchor > nowElapsedMs || nowElapsedMs - freshnessAnchor < staleAfterMs) {
            return false
        }
        return lastRecoveryElapsedMs <= 0L ||
            (lastRecoveryElapsedMs <= nowElapsedMs &&
                nowElapsedMs - lastRecoveryElapsedMs >= recoveryCooldownMs)
    }

    @JvmStatic
    fun sensorMayDriveCluster(
        sensorAccuracy: Int,
        nowElapsedMs: Long,
        lastGpsBearingElapsedMs: Long,
        gpsBearingPriorityMs: Long,
    ): Boolean {
        if (sensorAccuracy > 0) return true
        if (lastGpsBearingElapsedMs <= 0L || gpsBearingPriorityMs < 0L) return true
        if (lastGpsBearingElapsedMs > nowElapsedMs) return true
        return nowElapsedMs - lastGpsBearingElapsedMs > gpsBearingPriorityMs
    }
}
