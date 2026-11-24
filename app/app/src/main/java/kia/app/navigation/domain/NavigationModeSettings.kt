package kia.app.navigation.domain

import android.content.Context
import kia.app.core.settings.AppSettings

object NavigationModeSettings {
    @JvmStatic
    fun mode(context: Context?): Int {
        return AppSettings.navOutputMode(context!!)
    }

    @JvmStatic
    fun setMode(context: Context?, mode: Int) {
        AppSettings.setNavOutputMode(context!!, mode)
    }

    @JvmStatic
    fun isNormal(context: Context?): Boolean {
        return NavigationOutputMode.isNormal(NavigationModeSettings.mode(context))
    }

    @JvmStatic
    fun isTbt(context: Context?): Boolean {
        return NavigationOutputMode.isTbt(NavigationModeSettings.mode(context))
    }

    @JvmStatic
    fun isFinishDirection(context: Context?): Boolean {
        return NavigationOutputMode.isFinishDirection(NavigationModeSettings.mode(context))
    }

    @JvmStatic
    fun label(context: Context?): String? {
        return NavigationOutputMode.label(NavigationModeSettings.mode(context))
    }
}
