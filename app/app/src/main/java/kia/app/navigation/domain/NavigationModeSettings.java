package kia.app.navigation.domain;

import android.content.Context;

import kia.app.core.settings.AppSettings;

public final class NavigationModeSettings {
    private NavigationModeSettings() {
    }

    public static int mode(Context context) {
        return AppSettings.navOutputMode(context);
    }

    public static void setMode(Context context, int mode) {
        AppSettings.setNavOutputMode(context, mode);
    }

    public static boolean isNormal(Context context) {
        return NavigationOutputMode.isNormal(mode(context));
    }

    public static boolean isTbt(Context context) {
        return NavigationOutputMode.isTbt(mode(context));
    }

    public static boolean isFinishDirection(Context context) {
        return NavigationOutputMode.isFinishDirection(mode(context));
    }

    public static String label(Context context) {
        return NavigationOutputMode.label(mode(context));
    }
}
