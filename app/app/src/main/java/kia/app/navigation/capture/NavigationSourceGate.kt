package kia.app.navigation.capture

/** Central source-activation policy for background navigation clients. */
object NavigationSourceGate {
    @JvmStatic
    fun yandexEnabled(navigationEnabled: Boolean, yandexSelected: Boolean): Boolean {
        return navigationEnabled && yandexSelected
    }

    @JvmStatic
    fun dgisEnabled(navigationEnabled: Boolean, dgisSelected: Boolean): Boolean {
        return navigationEnabled && dgisSelected
    }
}
