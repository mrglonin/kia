package kia.app.navigation.domain

import java.util.Locale
import java.util.regex.Pattern

/**
 * Pure spatial arbitration for the yellow maneuver shown in the cluster.
 *
 * A maneuver category is deliberately not a priority. The next trustworthy route position wins.
 * This is important for a lane/micro maneuver located before a roundabout that Yandex has already
 * announced several kilometres ahead.
 */
internal object ManeuverArbiter {
    private const val POSITION_TOLERANCE_METERS: Float = 12f
    private val NUMBER: Pattern? =
        Pattern.compile("[-+]?\\d(?:[\\d\\s\\u00a0\\u202f]*\\d)?(?:[\\.,]\\d+)?")

    @JvmStatic
    fun decide(
        mainManeuver: String?,
        mainDistance: String?,
        microManeuver: String?,
        microDistance: String?,
        trustedMicro: Boolean,
        sameFamily: Boolean,
    ): ManeuverArbiter.Decision {
        val mainMeters: Float = ManeuverArbiter.distanceMeters(mainDistance)
        val microMeters: Float = ManeuverArbiter.distanceMeters(microDistance)
        if (!trustedMicro || !ManeuverArbiter.usable(microManeuver)) {
            return ManeuverArbiter.main("micro_missing_or_untrusted", mainMeters, microMeters)
        }
        if (microMeters <= 0f) {
            return ManeuverArbiter.main("micro_distance_unknown", mainMeters, microMeters)
        }
        if (!ManeuverArbiter.usable(mainManeuver)) {
            return ManeuverArbiter.micro("micro_only_candidate", mainMeters, microMeters)
        }
        if (mainMeters <= 0f) {
            return ManeuverArbiter.micro(
                "explicit_micro_before_unknown_main",
                mainMeters,
                microMeters,
            )
        }
        if (microMeters + ManeuverArbiter.POSITION_TOLERANCE_METERS < mainMeters) {
            return ManeuverArbiter.micro(
                if (sameFamily) "micro_before_same_family_main" else "micro_before_main",
                mainMeters,
                microMeters,
            )
        }
        if (sameFamily) {
            return ManeuverArbiter.main("same_route_event", mainMeters, microMeters)
        }
        return ManeuverArbiter.main("main_is_next", mainMeters, microMeters)
    }

    @JvmStatic
    fun distanceMeters(value: String?): Float {
        val number: Float = ManeuverArbiter.distanceValue(value)
        if (number < 0f) return -1f
        if (number == 0f) return 0f
        val text = ManeuverArbiter.clean(value).lowercase(Locale.US)
        return if (ManeuverArbiter.containsKm(text)) number * 1000f else number
    }

    @JvmStatic
    fun distanceValue(value: String?): Float {
        val text = ManeuverArbiter.clean(value)
        if (text.isEmpty()) return -1f
        val matcher = ManeuverArbiter.NUMBER!!.matcher(text)
        if (!matcher.find()) return -1f
        val numeric =
            matcher
                .group()
                .replace(" ", "")
                .replace("\u00a0", "")
                .replace("\u202f", "")
                .replace(',', '.')
        try {
            return java.lang.Float.parseFloat(numeric)
        } catch (ignored: RuntimeException) {
            return -1f
        }
    }

    private fun containsKm(value: String): Boolean {
        return value.contains("км") || value.contains("km")
    }

    private fun usable(value: String?): Boolean {
        return !ManeuverArbiter.clean(value).isEmpty()
    }

    private fun main(
        reason: String?,
        mainMeters: Float,
        microMeters: Float,
    ): ManeuverArbiter.Decision {
        return ManeuverArbiter.Decision(Choice.MAIN, reason, mainMeters, microMeters)
    }

    private fun micro(
        reason: String?,
        mainMeters: Float,
        microMeters: Float,
    ): ManeuverArbiter.Decision {
        return ManeuverArbiter.Decision(Choice.MICRO, reason, mainMeters, microMeters)
    }

    private fun clean(value: String?): String {
        return if (value == null) "" else value.trim()
    }

    internal enum class Choice {
        MAIN,
        MICRO,
    }

    internal class Decision(
        choice: Choice?,
        reason: String?,
        mainMeters: Float,
        microMeters: Float,
    ) {
        @JvmField val choice: Choice?
        @JvmField val reason: String?
        @JvmField val mainMeters: Float
        @JvmField val microMeters: Float

        init {
            this.choice = choice
            this.reason = ManeuverArbiter.clean(reason)
            this.mainMeters = mainMeters
            this.microMeters = microMeters
        }

        fun microWins(): Boolean {
            return choice == Choice.MICRO
        }
    }
}
