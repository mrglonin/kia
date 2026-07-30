package kia.app.navigation.capture;

/**
 * Central source-activation policy for background navigation clients.
 */
public final class NavigationSourceGate {
    private NavigationSourceGate() {
    }

    public static boolean yandexEnabled(boolean navigationEnabled, boolean yandexSelected) {
        return navigationEnabled && yandexSelected;
    }

    public static boolean dgisEnabled(boolean navigationEnabled, boolean dgisSelected) {
        return navigationEnabled && dgisSelected;
    }
}
