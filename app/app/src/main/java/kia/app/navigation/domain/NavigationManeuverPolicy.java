package kia.app.navigation.domain;

import android.content.Context;
import android.text.TextUtils;

import kia.app.core.settings.AppSettings;

final class NavigationManeuverPolicy {
    private NavigationManeuverPolicy() {
    }

    static boolean mainBlocksMicro(String mainManeuver, String mainDistance,
                                   String microManeuver, String microDistance,
                                   boolean trustedMicro, boolean sameFamily) {
        if (!isUsable(mainManeuver) || !isUsable(microManeuver)) return false;
        return !ManeuverArbiter.decide(mainManeuver, mainDistance,
                microManeuver, microDistance, trustedMicro, sameFamily).microWins();
    }

    static ManeuverArbiter.Decision decide(String mainManeuver, String mainDistance,
                                           String microManeuver, String microDistance,
                                           boolean trustedMicro, boolean sameFamily) {
        return ManeuverArbiter.decide(mainManeuver, mainDistance,
                microManeuver, microDistance, trustedMicro, sameFamily);
    }

    static boolean microDistanceAllowed(Context context, String distanceText) {
        int maxMeters = AppSettings.navMicroMaxDistanceMeters(context);
        if (maxMeters <= 0) return true;
        float meters = distanceMeters(distanceText);
        return meters > 0f && meters <= maxMeters;
    }

    private static boolean isUsable(String maneuver) {
        return !TextUtils.isEmpty(clean(maneuver));
    }

    private static float distanceMeters(String value) {
        return ManeuverArbiter.distanceMeters(value);
    }

    private static String clean(String value) {
        return value == null ? "" : value.trim();
    }
}
