package kia.app.navigation.domain

import java.util.Locale

/**
 * Keeps a numbered roundabout exit as a refinement of the current main event.
 *
 * Missing exit metadata must not downgrade an already numbered event, while an explicit exit number
 * may refine a generic roundabout or correct a previous explicit number.
 */
internal object RoundaboutMainPolicy {
    private val NUMBERED_EXIT_PREFIX: String = "context_ra_roundabout_exit_"

    @JvmStatic
    fun shouldRefineMain(currentManeuver: String?, incomingManeuver: String?): Boolean {
        val current = RoundaboutMainPolicy.normalize(currentManeuver)
        val incoming = RoundaboutMainPolicy.normalize(incomingManeuver)
        return RoundaboutMainPolicy.isRoundabout(current) &&
            RoundaboutMainPolicy.isNumberedExit(incoming) &&
            !incoming.equals(current)
    }

    @JvmStatic
    fun shouldKeepNumberedMain(
        currentManeuver: String?,
        incomingManeuver: String?,
        currentDistanceMeters: Float,
        incomingDistanceMeters: Float,
    ): Boolean {
        val current = RoundaboutMainPolicy.normalize(currentManeuver)
        val incoming = RoundaboutMainPolicy.normalize(incomingManeuver)
        return RoundaboutMainPolicy.isNumberedExit(current) &&
            RoundaboutMainPolicy.isRoundabout(incoming) &&
            !RoundaboutMainPolicy.isNumberedExit(incoming) &&
            !RoundaboutMainPolicy.looksLikeNextEvent(currentDistanceMeters, incomingDistanceMeters)
    }

    @JvmStatic
    fun distanceIdentityMatches(stateManeuver: String?, incomingIdentity: String?): Boolean {
        val state = RoundaboutMainPolicy.normalize(stateManeuver)
        val incoming = RoundaboutMainPolicy.normalize(incomingIdentity)
        if (state.isEmpty() || incoming.isEmpty()) return false
        return state.equals(incoming) ||
            (RoundaboutMainPolicy.isRoundabout(state) &&
                RoundaboutMainPolicy.isRoundabout(incoming))
    }

    @JvmStatic
    fun isNumberedExit(maneuver: String?): Boolean {
        val value = RoundaboutMainPolicy.normalize(maneuver)
        if (
            !value.startsWith(RoundaboutMainPolicy.NUMBERED_EXIT_PREFIX) ||
                value.length != RoundaboutMainPolicy.NUMBERED_EXIT_PREFIX.length + 1
        ) {
            return false
        }
        val exit: Char = value.get(RoundaboutMainPolicy.NUMBERED_EXIT_PREFIX.length)
        return exit >= '1' && exit <= '4'
    }

    private fun looksLikeNextEvent(
        currentDistanceMeters: Float,
        incomingDistanceMeters: Float,
    ): Boolean {
        if (
            !java.lang.Float.isFinite(currentDistanceMeters) ||
                !java.lang.Float.isFinite(incomingDistanceMeters) ||
                currentDistanceMeters <= 0f ||
                incomingDistanceMeters <= 0f
        ) {
            return false
        }
        val minimumJump: Float = Math.max(100f, currentDistanceMeters * 0.5f)
        return incomingDistanceMeters - currentDistanceMeters >= minimumJump
    }

    private fun isRoundabout(maneuver: String?): Boolean {
        val value = RoundaboutMainPolicy.normalize(maneuver)
        return value.contains("roundabout") || value.contains("circular")
    }

    private fun normalize(value: String?): String {
        return if (value == null) "" else value.trim().lowercase(Locale.US)
    }
}
