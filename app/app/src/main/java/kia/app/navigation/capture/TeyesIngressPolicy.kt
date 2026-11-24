package kia.app.navigation.capture

/**
 * Classifies legacy TEYES packets by the strongest state change they are allowed to make.
 *
 * TEYES is only a visual fallback for an already active Yandex-family route. It must claim the Auto
 * owner before replacing a loading visual, but road events and ignored close packets must not steal
 * ownership from 2GIS.
 */
object TeyesIngressPolicy {
    @JvmStatic
    fun event(
        offState: Boolean,
        passiveRoadEvent: Boolean,
        activeRoute: Boolean,
        yandexRouteContext: Boolean,
        waitingForManeuver: Boolean,
        usableManeuver: Boolean,
    ): Int {
        if (offState || passiveRoadEvent) {
            return NavigationSourcePolicy.EVENT_PASSIVE
        }
        if (activeRoute && yandexRouteContext && waitingForManeuver && usableManeuver) {
            return NavigationSourcePolicy.EVENT_ROUTE_ACTIVE
        }
        return NavigationSourcePolicy.EVENT_PASSIVE
    }
}
