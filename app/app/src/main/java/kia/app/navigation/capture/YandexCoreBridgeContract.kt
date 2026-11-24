package kia.app.navigation.capture

import android.net.Uri

object YandexCoreBridgeContract {
    const val AUTHORITY: String = "ru.yandex.yandexnavi.kia.bridge"
    @JvmField
    val STATE_URI: Uri? = Uri.parse("content://" + YandexCoreBridgeContract.AUTHORITY + "/state")
    const val METHOD_GET_STATE: String = "get_state"
    const val ACTION_V2_SNAPSHOT: String = "kia.yandex.v2.NAV_SNAPSHOT"
    const val SOURCE: String = "yandex_core_bridge"
    const val EXTRA_MAIN_MANEUVER_ROLLOVER: String = "main_maneuver_rollover"

    const val STATE_OFF: String = "off"
    const val STATE_LOADING: String = "loading"
    const val STATE_ACTIVE: String = "active"
    const val STATE_REROUTING: String = "rerouting"
    const val STATE_FINISHED: String = "finished"
}
