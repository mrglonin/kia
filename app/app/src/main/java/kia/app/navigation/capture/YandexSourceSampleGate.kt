package kia.app.navigation.capture

import java.util.Locale

/**
 * Separates a fresh MapKit location sample from the bridge transport heartbeat.
 *
 * The bridge envelope sequence and timestamp describe when the snapshot was sent. They do not prove
 * that `Guide.getLocation()` advanced. This gate remembers the producer's location timestamps and
 * only authorizes speed/road-limit data when the underlying sample advanced or a matching semantic
 * callback proves that the individual field was updated.
 */
internal class YandexSourceSampleGate {
    internal class Decision(currentSpeedFresh: Boolean, roadLimitFresh: Boolean, reason: String?) {
        @JvmField val currentSpeedFresh: Boolean
        @JvmField val roadLimitFresh: Boolean
        @JvmField val reason: String

        init {
            this.currentSpeedFresh = currentSpeedFresh
            this.roadLimitFresh = roadLimitFresh
            this.reason = if (reason == null) "" else reason
        }

        fun anyFreshData(): Boolean {
            return currentSpeedFresh || roadLimitFresh
        }
    }

    private enum class Relation {
        MISSING,
        NEW,
        SAME,
        REGRESSION,
    }

    private var lastRelativeTimestamp: Long = -1L
    private var lastAbsoluteTimestamp: Long = -1L

    fun evaluate(
        callback: String?,
        relativeTimestamp: Long,
        absoluteTimestamp: Long,
        nowElapsed: Long,
        nowWall: Long,
        maxSampleAgeMs: Long,
    ): kia.app.navigation.capture.YandexSourceSampleGate.Decision {
        val event = YandexSourceSampleGate.clean(callback)
        val locationEvent: Boolean = event.equals("location")
        val speedLimitEvent: Boolean = event.equals("speed_limit")
        val exceededEvent: Boolean =
            event.equals("speed_limit_exceeded") ||
                event.equals("speed_limit_exceeded_update") ||
                event.equals("speed_limit_exceeded_updated")
        val relation: Relation = relation(relativeTimestamp, absoluteTimestamp)
        val recent: Boolean =
            YandexSourceSampleGate.sampleRecent(
                relativeTimestamp,
                absoluteTimestamp,
                nowElapsed,
                nowWall,
                maxSampleAgeMs,
            )

        if (relation == Relation.NEW) {
            remember(relativeTimestamp, absoluteTimestamp)
        }

        // With the freshness-enabled producer even a location callback must advance its source
        // token. Missing tokens are accepted only as a compatibility fallback for the legacy
        // producer; a SAME/regressed/stale token must never renew cached road data.
        if (locationEvent) {
            if (relation == Relation.MISSING) {
                return kia.app.navigation.capture.YandexSourceSampleGate.Decision(
                    true,
                    true,
                    "legacy_location_callback",
                )
            }
            if (relation == Relation.NEW && recent) {
                return kia.app.navigation.capture.YandexSourceSampleGate.Decision(
                    true,
                    true,
                    "fresh_location_callback",
                )
            }
            val rejectedReason: String =
                if (relation == Relation.NEW) "stale_location_callback"
                else if (relation == Relation.SAME) "repeated_location_callback"
                else "regressed_location_callback"
            return kia.app.navigation.capture.YandexSourceSampleGate.Decision(
                false,
                false,
                rejectedReason,
            )
        }
        if (speedLimitEvent) {
            val currentFresh: Boolean = relation == Relation.NEW && recent
            return kia.app.navigation.capture.YandexSourceSampleGate.Decision(
                currentFresh,
                true,
                "speed_limit_callback",
            )
        }
        if (exceededEvent) {
            return kia.app.navigation.capture.YandexSourceSampleGate.Decision(
                true,
                false,
                "speed_limit_exceeded_callback",
            )
        }

        if (relation == Relation.NEW && recent) {
            return kia.app.navigation.capture.YandexSourceSampleGate.Decision(
                true,
                true,
                "new_location_sample",
            )
        }
        if (relation == Relation.MISSING) {
            return kia.app.navigation.capture.YandexSourceSampleGate.Decision(
                false,
                false,
                "missing_location_sample",
            )
        }
        if (relation == Relation.SAME) {
            return kia.app.navigation.capture.YandexSourceSampleGate.Decision(
                false,
                false,
                "repeated_location_sample",
            )
        }
        if (relation == Relation.REGRESSION) {
            return kia.app.navigation.capture.YandexSourceSampleGate.Decision(
                false,
                false,
                "regressed_location_sample",
            )
        }
        return kia.app.navigation.capture.YandexSourceSampleGate.Decision(
            false,
            false,
            "stale_location_sample",
        )
    }

    fun reset() {
        lastRelativeTimestamp = -1L
        lastAbsoluteTimestamp = -1L
    }

    private fun relation(relativeTimestamp: Long, absoluteTimestamp: Long): Relation {
        if (relativeTimestamp > 0L) {
            if (lastRelativeTimestamp <= 0L) return Relation.NEW
            if (relativeTimestamp > lastRelativeTimestamp) return Relation.NEW
            if (relativeTimestamp < lastRelativeTimestamp) return Relation.REGRESSION
            return Relation.SAME
        }
        if (absoluteTimestamp > 0L) {
            if (lastAbsoluteTimestamp <= 0L) return Relation.NEW
            if (absoluteTimestamp > lastAbsoluteTimestamp) return Relation.NEW
            if (absoluteTimestamp < lastAbsoluteTimestamp) return Relation.REGRESSION
            return Relation.SAME
        }
        return Relation.MISSING
    }

    private fun remember(relativeTimestamp: Long, absoluteTimestamp: Long) {
        if (relativeTimestamp > 0L) lastRelativeTimestamp = relativeTimestamp
        if (absoluteTimestamp > 0L) lastAbsoluteTimestamp = absoluteTimestamp
    }

    companion object {
        private const val CLOCK_FUTURE_TOLERANCE_MS: Long = 60000L

        private fun sampleRecent(
            relativeTimestamp: Long,
            absoluteTimestamp: Long,
            nowElapsed: Long,
            nowWall: Long,
            maxSampleAgeMs: Long,
        ): Boolean {
            val maxAge: Long = Math.max(0L, maxSampleAgeMs)
            var checked: Boolean = false
            var recent: Boolean = false
            if (
                relativeTimestamp > 0L &&
                    relativeTimestamp <=
                        nowElapsed + YandexSourceSampleGate.CLOCK_FUTURE_TOLERANCE_MS
            ) {
                checked = true
                val age: Long = nowElapsed - relativeTimestamp
                recent =
                    recent or
                        (age >= -YandexSourceSampleGate.CLOCK_FUTURE_TOLERANCE_MS && age <= maxAge)
            }
            if (
                absoluteTimestamp > 0L &&
                    absoluteTimestamp <= nowWall + YandexSourceSampleGate.CLOCK_FUTURE_TOLERANCE_MS
            ) {
                checked = true
                val age: Long = nowWall - absoluteTimestamp
                recent =
                    recent or
                        (age >= -YandexSourceSampleGate.CLOCK_FUTURE_TOLERANCE_MS && age <= maxAge)
            }
            return checked && recent
        }

        private fun clean(value: String?): String {
            return if (value == null) "" else value.trim().lowercase(Locale.US)
        }
    }
}
