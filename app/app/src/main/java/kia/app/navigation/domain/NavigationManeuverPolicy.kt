package kia.app.navigation.domain

import android.content.Context
import android.text.TextUtils
import kia.app.core.settings.AppSettings

internal object NavigationManeuverPolicy {
    @JvmStatic
    fun mainBlocksMicro(
        mainManeuver: String?,
        mainDistance: String?,
        microManeuver: String?,
        microDistance: String?,
        trustedMicro: Boolean,
        sameFamily: Boolean,
    ): Boolean {
        if (
            !NavigationManeuverPolicy.isUsable(mainManeuver) ||
                !NavigationManeuverPolicy.isUsable(microManeuver)
        )
            return false
        return !ManeuverArbiter.decide(
                mainManeuver,
                mainDistance,
                microManeuver,
                microDistance,
                trustedMicro,
                sameFamily,
            )
            .microWins()
    }

    @JvmStatic
    fun decide(
        mainManeuver: String?,
        mainDistance: String?,
        microManeuver: String?,
        microDistance: String?,
        trustedMicro: Boolean,
        sameFamily: Boolean,
    ): ManeuverArbiter.Decision {
        return ManeuverArbiter.decide(
            mainManeuver,
            mainDistance,
            microManeuver,
            microDistance,
            trustedMicro,
            sameFamily,
        )
    }

    @JvmStatic
    fun microDistanceAllowed(context: Context?, distanceText: String?): Boolean {
        val maxMeters: Int = AppSettings.navMicroMaxDistanceMeters(context!!)
        if (maxMeters <= 0) return true
        val meters: Float = NavigationManeuverPolicy.distanceMeters(distanceText)
        return meters > 0f && meters <= maxMeters
    }

    private fun isUsable(maneuver: String?): Boolean {
        return !TextUtils.isEmpty(NavigationManeuverPolicy.clean(maneuver))
    }

    private fun distanceMeters(value: String?): Float {
        return ManeuverArbiter.distanceMeters(value)
    }

    private fun clean(value: String?): String {
        return if (value == null) "" else value.trim()
    }
}
