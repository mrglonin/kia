package kia.app.update

import android.content.Context
import android.content.SharedPreferences

/** Runtime-only OTA markers; deliberately excluded from settings export/import. */
object UpdateRuntimeStore {
    private const val PREFS: String = "KiaUpdateRuntime"
    private const val KEY_NOTIFICATION_PERMISSION_ASKED: String = "notification_permission_asked"
    private const val KEY_LAST_NOTIFIED_TOKEN: String = "last_notified_token"

    @JvmStatic
    fun notificationPermissionAsked(context: Context): Boolean {
        return UpdateRuntimeStore.prefs(context)!!.getBoolean(
            UpdateRuntimeStore.KEY_NOTIFICATION_PERMISSION_ASKED,
            false,
        )
    }

    @JvmStatic
    fun markNotificationPermissionAsked(context: Context) {
        UpdateRuntimeStore.prefs(context)!!.edit()
            .putBoolean(UpdateRuntimeStore.KEY_NOTIFICATION_PERMISSION_ASKED, true)
            .apply()
    }

    @JvmStatic
    fun lastNotifiedToken(context: Context): String? {
        val value =
            UpdateRuntimeStore.prefs(context)!!.getString(
                UpdateRuntimeStore.KEY_LAST_NOTIFIED_TOKEN,
                "",
            )
        return if (value == null) "" else value
    }

    @JvmStatic
    fun setLastNotifiedToken(context: Context, token: String?) {
        UpdateRuntimeStore.prefs(context)!!.edit()
            .putString(UpdateRuntimeStore.KEY_LAST_NOTIFIED_TOKEN, if (token == null) "" else token)
            .apply()
    }

    @JvmStatic
    fun resetPostedNotification(context: Context) {
        UpdateRuntimeStore.prefs(context)!!.edit()
            .remove(UpdateRuntimeStore.KEY_LAST_NOTIFIED_TOKEN)
            .apply()
    }

    private fun prefs(context: Context): SharedPreferences? {
        val app = context.getApplicationContext()
        return app!!.getSharedPreferences(UpdateRuntimeStore.PREFS, Context.MODE_PRIVATE)
    }
}
