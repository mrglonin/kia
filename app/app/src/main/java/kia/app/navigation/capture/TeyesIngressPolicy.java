package kia.app.navigation.capture;

/**
 * Classifies legacy TEYES packets by the strongest state change they are allowed to make.
 *
 * <p>TEYES is only a visual fallback for an already active Yandex-family route. It must claim the
 * Auto owner before replacing a loading visual, but road events and ignored close packets must not
 * steal ownership from 2GIS.</p>
 */
public final class TeyesIngressPolicy {
    private TeyesIngressPolicy() {
    }

    public static int event(boolean offState, boolean passiveRoadEvent,
                            boolean activeRoute, boolean yandexRouteContext,
                            boolean waitingForManeuver, boolean usableManeuver) {
        if (offState || passiveRoadEvent) {
            return NavigationSourcePolicy.EVENT_PASSIVE;
        }
        if (activeRoute && yandexRouteContext && waitingForManeuver && usableManeuver) {
            return NavigationSourcePolicy.EVENT_ROUTE_ACTIVE;
        }
        return NavigationSourcePolicy.EVENT_PASSIVE;
    }
}
