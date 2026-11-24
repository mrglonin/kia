package kia.app.update

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.text.TextUtils
import kia.app.R
import kia.app.core.AppIds
import kia.app.core.AppLog
import kia.app.core.StateStore
import kia.app.core.model.UpdateState
import kia.app.entry.MainActivity

/** Posts non-modal OTA alerts, including when only the foreground service is visible. */
object UpdateNotificationController {
    private const val CHANNEL: String = "kia_updates"
    private const val CHANNEL_NAME: String = "Обновления KIA"
    private const val NOTIFICATION_ID: Int = 23091

    @JvmStatic
    fun prepareChannel(context: Context?) {
        val app = if (context == null) null else context.getApplicationContext()
        if (app == null) return
        try {
            val manager: NotificationManager? =
                app.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager?
            if (manager == null) return
            val channel: NotificationChannel =
                NotificationChannel(
                    UpdateNotificationController.CHANNEL,
                    UpdateNotificationController.CHANNEL_NAME,
                    NotificationManager.IMPORTANCE_DEFAULT,
                )
            channel.setDescription("Новые версии KIA и совместимого Yandex Navigator")
            manager.createNotificationChannel(channel)
        } catch (error: Exception) {
            AppLog.line(
                app,
                "Update notification channel failed: " + error.javaClass.getSimpleName(),
            )
        }
    }

    @JvmStatic
    fun refresh(context: Context?) {
        val app = if (context == null) null else context.getApplicationContext()
        if (app == null) return
        UpdateNotificationController.prepareChannel(app)
        val state = StateStore.updates()
        val token: String = UpdateNotificationController.notificationToken(state)!!
        val action =
            UpdateNotificationPolicy.action(
                state.appAvailable,
                state.navigatorAvailable,
                state.appChecking,
                state.navigatorChecking,
                token,
                UpdateRuntimeStore.lastNotifiedToken(app),
            )
        if (action == kia.app.update.UpdateNotificationPolicy.Action.KEEP) return
        if (action == kia.app.update.UpdateNotificationPolicy.Action.CANCEL) {
            UpdateNotificationController.cancel(app, UpdateNotificationController.NOTIFICATION_ID)
            UpdateRuntimeStore.resetPostedNotification(app)
            return
        }
        val title: String?
        val text: String?
        if (state.appAvailable && state.navigatorAvailable) {
            title = "Доступны обновления KIA и Yandex"
            text = state.appAsset + " · " + state.navigatorVersion
        } else if (state.appAvailable) {
            title = "Доступно обновление KIA"
            text = state.appAsset
        } else {
            title = "Доступно обновление Yandex Navigator"
            text =
                if (TextUtils.isEmpty(state.navigatorVersion)) state.navigatorAsset
                else state.navigatorVersion
        }
        if (
            UpdateNotificationController.show(
                app,
                UpdateNotificationController.NOTIFICATION_ID,
                91,
                title,
                text + " — откройте KIA для установки",
            )
        ) {
            UpdateRuntimeStore.setLastNotifiedToken(app, token)
        }
    }

    private fun show(
        context: Context?,
        notificationId: Int,
        requestCode: Int,
        title: String?,
        text: String?,
    ): Boolean {
        val app = if (context == null) null else context.getApplicationContext()
        if (app == null) return false
        if (
            Build.VERSION.SDK_INT >= 33 &&
                (app.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) !=
                    PackageManager.PERMISSION_GRANTED)
        ) {
            AppLog.line(app, "Update notification skipped: POST_NOTIFICATIONS denied")
            return false
        }
        try {
            val manager: NotificationManager? =
                app.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager?
            if (manager == null) return false
            val intent: Intent =
                Intent(AppIds.ACTION_OPEN_UPDATES, null, app, MainActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            val open =
                PendingIntent.getActivity(
                    app,
                    requestCode,
                    intent,
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
                )
            val notification =
                Notification.Builder(app, UpdateNotificationController.CHANNEL)
                    .setSmallIcon(R.drawable.ic_stat_kia)
                    .setContentTitle(title)
                    .setContentText(text)
                    .setStyle(Notification.BigTextStyle().bigText(text))
                    .setContentIntent(open)
                    .setAutoCancel(true)
                    .setCategory(Notification.CATEGORY_STATUS)
                    .setPriority(Notification.PRIORITY_DEFAULT)
                    .build()
            manager.notify(notificationId, notification)
            return true
        } catch (error: Exception) {
            AppLog.line(app, "Update notification failed: " + error.javaClass.getSimpleName())
            return false
        }
    }

    private fun notificationToken(state: UpdateState?): String? {
        if (state == null) return ""
        val app: String = if (state.appAvailable) state.appAsset + ":" + state.appSha256 else ""
        val navigator: String =
            if (state.navigatorAvailable)
                (state.navigatorAsset +
                    ":" +
                    state.navigatorVersion +
                    ":" +
                    state.navigatorFingerprint)
            else ""
        return app + "|" + navigator
    }

    private fun cancel(context: Context?, notificationId: Int) {
        val app = if (context == null) null else context.getApplicationContext()
        if (app == null) return
        try {
            val manager: NotificationManager? =
                app.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager?
            if (manager != null) manager.cancel(notificationId)
        } catch (ignored: Exception) {}
    }
}
