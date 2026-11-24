package kia.app.update

/** Pure decision rule for the shared KIA/Yandex OTA notification. */
internal object UpdateNotificationPolicy {
    @JvmStatic
    fun action(
        appAvailable: Boolean,
        navigatorAvailable: Boolean,
        appChecking: Boolean,
        navigatorChecking: Boolean,
        token: String?,
        lastNotifiedToken: String?,
    ): UpdateNotificationPolicy.Action {
        if (appChecking || navigatorChecking)
            return kia.app.update.UpdateNotificationPolicy.Action.KEEP
        if (!appAvailable && !navigatorAvailable) {
            return kia.app.update.UpdateNotificationPolicy.Action.CANCEL
        }
        val current: String = if (token == null) "" else token
        val previous: String = if (lastNotifiedToken == null) "" else lastNotifiedToken
        return if (!current.isEmpty() && current.equals(previous))
            kia.app.update.UpdateNotificationPolicy.Action.KEEP
        else kia.app.update.UpdateNotificationPolicy.Action.NOTIFY
    }

    internal enum class Action {
        KEEP,
        CANCEL,
        NOTIFY,
    }
}
