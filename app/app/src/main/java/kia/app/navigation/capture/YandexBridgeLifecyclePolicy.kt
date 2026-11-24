package kia.app.navigation.capture

import java.util.Locale

/**
 * Interprets lifecycle callbacks emitted by the Yandex bridge.
 *
 * Yandex reports that its background guidance task is being suspended with a one-shot `off`
 * snapshot even while the route is still present. That snapshot is not a user-confirmed route
 * removal and must not terminate the cluster session.
 */
object YandexBridgeLifecyclePolicy {
    @JvmStatic
    fun shouldPreserveActiveRoute(
        state: String?,
        callback: String?,
        hadLiveRoute: Boolean,
        hasRouteMetrics: Boolean,
        routePresentKnown: Boolean,
        routePresent: Boolean,
    ): Boolean {
        if (!YandexCoreBridgeContract.STATE_OFF.equals(YandexBridgeLifecyclePolicy.clean(state)))
            return false
        val marker = YandexBridgeLifecyclePolicy.clean(callback)
        if (
            marker.contains("route_removed") ||
                marker.contains("route_cancel") ||
                marker.contains("route_reset") ||
                marker.contains("user_stop") ||
                marker.contains("navigation_finished") ||
                marker.contains("destination_reached")
        ) {
            return false
        }
        if (routePresentKnown && !routePresent && !hasRouteMetrics) return false
        if (!routePresent && !hadLiveRoute && !hasRouteMetrics) return false
        return marker.contains("background_suspended") ||
            marker.contains("backgroundguidancewillbesuspended") ||
            marker.contains("background_guidance_will_be_suspended") ||
            marker.contains("guidance_suspended") ||
            marker.equals("task_removed") ||
            marker.contains("backgroundguidancetaskremoved") ||
            marker.contains("background_guidance_task_removed")
    }

    private fun clean(value: String?): String {
        return if (value == null) "" else value.trim().lowercase(Locale.US)
    }
}
