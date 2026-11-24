package kia.app.navigation.domain

/**
 * Stateful 0..9 countdown for the main navigation maneuver.
 *
 * The first valid distance of every semantic event is its own baseline, so a maneuver first
 * observed at 180 or 300 metres still starts with a full bar. Micro/gray-road visuals must only
 * read the resulting main progress and must not create a separate tracker event.
 */
internal class ManeuverProgressTracker {
    internal class Result(
        hasValue: Boolean,
        hold: Boolean,
        newEvent: Boolean,
        bucket: Int,
        reason: String?,
    ) {
        @JvmField val hasValue: Boolean
        @JvmField val hold: Boolean
        @JvmField val newEvent: Boolean
        @JvmField val bucket: Int
        @JvmField val reason: String?

        init {
            this.hasValue = hasValue
            this.hold = hold
            this.newEvent = newEvent
            this.bucket = ManeuverProgressTracker.clamp(bucket)
            this.reason = ManeuverProgressTracker.clean(reason)
        }
    }

    private var active: Boolean = false
    private var scopeId: String? = ""
    private var maneuverFamily: String? = ""
    private var baseMeters: Float = 0f
    private var closestMeters: Float = 0f
    private var lastBucket: Int = 0

    fun observeMain(
        incomingScopeId: String?,
        incomingFamily: String?,
        meters: Float,
    ): kia.app.navigation.domain.ManeuverProgressTracker.Result {
        return observeMain(incomingScopeId, incomingFamily, meters, false, true)
    }

    fun observeMain(
        incomingScopeId: String?,
        incomingFamily: String?,
        meters: Float,
        explicitRollover: Boolean,
    ): kia.app.navigation.domain.ManeuverProgressTracker.Result {
        return observeMain(incomingScopeId, incomingFamily, meters, explicitRollover, true)
    }

    fun observeMain(
        incomingScopeId: String?,
        incomingFamily: String?,
        meters: Float,
        explicitRollover: Boolean,
        inferredRolloverAllowed: Boolean,
    ): kia.app.navigation.domain.ManeuverProgressTracker.Result {
        val scope = ManeuverProgressTracker.clean(incomingScopeId)
        val family: String = ManeuverProgressTracker.normalizedFamily(incomingFamily)
        if (!ManeuverProgressTracker.usableDistance(meters)) {
            return if (active)
                kia.app.navigation.domain.ManeuverProgressTracker.Result(
                    true,
                    true,
                    false,
                    lastBucket,
                    "invalid_distance_hold",
                )
            else
                kia.app.navigation.domain.ManeuverProgressTracker.Result(
                    false,
                    true,
                    false,
                    ManeuverProgressTracker.EMPTY_BUCKET,
                    "no_distance",
                )
        }

        val scopeChanged: Boolean =
            active && !scopeId!!.isEmpty() && !scope.isEmpty() && !scopeId.equals(scope)
        val familyChanged: Boolean =
            active &&
                !maneuverFamily!!.isEmpty() &&
                !family.isEmpty() &&
                !maneuverFamily.equals(family)
        if (!active || scopeChanged || familyChanged || explicitRollover) {
            return startEvent(
                scope,
                family,
                meters,
                if (!active) "initial"
                else if (scopeChanged) "scope_changed"
                else if (familyChanged) "family_changed" else "explicit_rollover",
            )
        }

        if (ManeuverProgressRolloverPolicy.shouldStartNewEvent(baseMeters, closestMeters, meters)) {
            if (inferredRolloverAllowed) {
                return startEvent(scope, family, meters, "distance_rollover")
            }
            return kia.app.navigation.domain.ManeuverProgressTracker.Result(
                true,
                true,
                false,
                lastBucket,
                "upstream_rollover_confirmation_required",
            )
        }

        if (scopeId!!.isEmpty() && !scope.isEmpty()) scopeId = scope
        if (!family.isEmpty()) maneuverFamily = family
        closestMeters = Math.min(closestMeters, meters)
        val candidate: Int = ManeuverProgressTracker.bucketFor(baseMeters, closestMeters)
        lastBucket = Math.min(lastBucket, candidate)
        return kia.app.navigation.domain.ManeuverProgressTracker.Result(
            true,
            false,
            false,
            lastBucket,
            "countdown",
        )
    }

    fun current(
        incomingScopeId: String?,
        incomingFamily: String?,
    ): kia.app.navigation.domain.ManeuverProgressTracker.Result {
        if (!active) {
            return kia.app.navigation.domain.ManeuverProgressTracker.Result(
                false,
                true,
                false,
                ManeuverProgressTracker.EMPTY_BUCKET,
                "empty",
            )
        }
        val scope = ManeuverProgressTracker.clean(incomingScopeId)
        if (!scopeId!!.isEmpty() && !scope.isEmpty() && !scopeId.equals(scope)) {
            return kia.app.navigation.domain.ManeuverProgressTracker.Result(
                false,
                true,
                false,
                ManeuverProgressTracker.EMPTY_BUCKET,
                "scope_mismatch",
            )
        }
        val family: String = ManeuverProgressTracker.normalizedFamily(incomingFamily)
        if (!maneuverFamily!!.isEmpty() && !family.isEmpty() && !maneuverFamily.equals(family)) {
            return kia.app.navigation.domain.ManeuverProgressTracker.Result(
                false,
                true,
                false,
                ManeuverProgressTracker.EMPTY_BUCKET,
                "family_mismatch",
            )
        }
        return kia.app.navigation.domain.ManeuverProgressTracker.Result(
            true,
            true,
            false,
            lastBucket,
            "current",
        )
    }

    fun reset() {
        active = false
        scopeId = ""
        maneuverFamily = ""
        baseMeters = 0f
        closestMeters = 0f
        lastBucket = ManeuverProgressTracker.EMPTY_BUCKET
    }

    private fun startEvent(
        scope: String?,
        family: String?,
        meters: Float,
        reason: String?,
    ): kia.app.navigation.domain.ManeuverProgressTracker.Result {
        active = true
        scopeId = scope
        maneuverFamily = family
        baseMeters = meters
        closestMeters = meters
        lastBucket = ManeuverProgressTracker.FULL_BUCKET
        return kia.app.navigation.domain.ManeuverProgressTracker.Result(
            true,
            false,
            true,
            lastBucket,
            reason,
        )
    }

    companion object {
        const val EMPTY_BUCKET: Int = 0
        const val FULL_BUCKET: Int = 9
        private const val MIN_VALID_DISTANCE_METERS: Float = 1f
        private const val EMPTY_DISTANCE_METERS: Float = 50f

        private fun bucketFor(baseMeters: Float, remainingMeters: Float): Int {
            if (
                !ManeuverProgressTracker.usableDistance(baseMeters) ||
                    !ManeuverProgressTracker.usableDistance(remainingMeters)
            ) {
                return ManeuverProgressTracker.EMPTY_BUCKET
            }
            if (
                baseMeters > ManeuverProgressTracker.EMPTY_DISTANCE_METERS &&
                    remainingMeters <= ManeuverProgressTracker.EMPTY_DISTANCE_METERS
            ) {
                return ManeuverProgressTracker.EMPTY_BUCKET
            }
            val ratio: Float = Math.max(0f, Math.min(1f, remainingMeters / baseMeters))
            return ManeuverProgressTracker.clamp(
                Math.round(ratio * ManeuverProgressTracker.FULL_BUCKET)
            )
        }

        private fun clamp(value: Int): Int {
            return Math.max(
                ManeuverProgressTracker.EMPTY_BUCKET,
                Math.min(ManeuverProgressTracker.FULL_BUCKET, value),
            )
        }

        private fun usableDistance(value: Float): Boolean {
            return java.lang.Float.isFinite(value) &&
                value > ManeuverProgressTracker.MIN_VALID_DISTANCE_METERS
        }

        private fun clean(value: String?): String {
            return if (value == null) "" else value.trim()
        }

        @JvmStatic
        fun normalizedFamily(value: String?): String {
            val family = ManeuverProgressTracker.clean(value).lowercase(java.util.Locale.US)
            if (family.endsWith("_left") && !"uturn_left".equals(family)) return "left"
            if (family.endsWith("_right") && !"uturn_right".equals(family)) return "right"
            return family
        }
    }
}
