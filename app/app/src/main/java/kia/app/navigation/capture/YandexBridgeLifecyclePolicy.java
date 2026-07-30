package kia.app.navigation.capture;

import java.util.Locale;

/**
 * Interprets lifecycle callbacks emitted by the Yandex bridge.
 *
 * <p>Yandex reports that its background guidance task is being suspended with
 * a one-shot {@code off} snapshot even while the route is still present. That
 * snapshot is not a user-confirmed route removal and must not terminate the
 * cluster session.</p>
 */
public final class YandexBridgeLifecyclePolicy {
    private YandexBridgeLifecyclePolicy() {
    }

    public static boolean shouldPreserveActiveRoute(String state, String callback,
                                                    boolean hadLiveRoute,
                                                    boolean hasRouteMetrics,
                                                    boolean routePresentKnown,
                                                    boolean routePresent) {
        if (!YandexCoreBridgeContract.STATE_OFF.equals(clean(state))) return false;
        String marker = clean(callback);
        if (marker.contains("route_removed")
                || marker.contains("route_cancel")
                || marker.contains("route_reset")
                || marker.contains("user_stop")
                || marker.contains("navigation_finished")
                || marker.contains("destination_reached")) {
            return false;
        }
        if (routePresentKnown && !routePresent && !hasRouteMetrics) return false;
        if (!routePresent && !hadLiveRoute && !hasRouteMetrics) return false;
        return marker.contains("background_suspended")
                || marker.contains("backgroundguidancewillbesuspended")
                || marker.contains("background_guidance_will_be_suspended")
                || marker.contains("guidance_suspended")
                || marker.equals("task_removed")
                || marker.contains("backgroundguidancetaskremoved")
                || marker.contains("background_guidance_task_removed");
    }

    private static String clean(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.US);
    }
}
