package kia.app.media.capture

import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import kia.app.core.AppLog
import kia.app.core.settings.AppSettings
import kia.app.media.domain.CallFeature
import kia.app.navigation.capture.DgisNotificationParser
import kia.app.navigation.capture.NavigationSourcePolicy
import kia.app.navigation.domain.NavigationFeature

class MediaNotificationListener : NotificationListenerService() {
    override fun onListenerConnected() {
        super.onListenerConnected()
        MediaNotificationListener.scanActive(this)
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        if (MediaNotificationListener.handleCallNotification(this, sbn)) return
        MediaNotificationListener.handleNavigationNotification(this, sbn)
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification?) {
        if (sbn != null && CallNotificationParser.isCallLikePackage(sbn.getPackageName())) {
            CallFeature.get(this).reportEnded("notification removed " + sbn.getPackageName())
        }
        if (
            sbn != null &&
                DgisNotificationParser.isDgisPackage(sbn.getPackageName()) &&
                MediaNotificationListener.dgisIngressAllowed(this)
        ) {
            if (!MediaNotificationListener.scanActiveDgis(this)) {
                NavigationFeature.get(this).handleDgisNotificationRemoved(sbn.getPackageName())
            }
        }
    }

    companion object {
        private const val IGNORED_LOG_MS: Long = 10000L
        private var lastIgnoredDgis: String = ""
        private var lastIgnoredDgisAt: Long = 0

        @JvmStatic
        fun scanActive(listener: MediaNotificationListener?) {
            if (listener == null) return
            MediaNotificationListener.scanActiveDgis(listener)
        }

        private fun scanActiveDgis(listener: MediaNotificationListener): Boolean {
            try {
                val active: kotlin.Array<StatusBarNotification?>? =
                    listener.getActiveNotifications()
                if (active == null) return false
                var found: Boolean = false
                for (sbn: StatusBarNotification? in active) {
                    found = found or MediaNotificationListener.handleCallNotification(listener, sbn)
                    found =
                        found or
                            MediaNotificationListener.handleNavigationNotification(listener, sbn)
                }
                return found
            } catch (e: Exception) {
                AppLog.line(listener, "Notification scan failed: " + e.javaClass.getSimpleName())
                return false
            }
        }

        private fun handleCallNotification(
            listener: MediaNotificationListener?,
            sbn: StatusBarNotification?,
        ): Boolean {
            val parsed: CallNotificationParser.Parsed? = CallNotificationParser.parse(sbn)
            if (parsed == null) return false
            AppLog.line(
                listener,
                ("Call notification: " + parsed.source + " | " + parsed.name + " | " + parsed.phone),
            )
            CallFeature.get(listener!!).reportActive(parsed.name, parsed.phone, parsed.source)
            return true
        }

        private fun handleNavigationNotification(
            listener: MediaNotificationListener?,
            sbn: StatusBarNotification?,
        ): Boolean {
            if (listener == null || !MediaNotificationListener.dgisIngressAllowed(listener)) return false
            val parsed: DgisNotificationParser.Parsed? = DgisNotificationParser.parse(sbn)
            if (parsed == null) {
                MediaNotificationListener.logIgnoredDgis(listener, sbn)
                return false
            }
            AppLog.line(listener, "2GIS notification: " + parsed.raw)
            NavigationFeature.get(listener)
                .handleDgisNotification(
                    parsed.maneuver,
                    parsed.distance,
                    parsed.unit,
                    parsed.street,
                    parsed.raw,
                )
            return true
        }

        private fun dgisIngressAllowed(listener: MediaNotificationListener?): Boolean {
            return listener != null &&
                NavigationSourcePolicy.ingressAllowed(
                    AppSettings.navigationEnabled(listener),
                    AppSettings.navSourceMode(listener),
                    NavigationSourcePolicy.SOURCE_DGIS,
                )
        }

        private fun logIgnoredDgis(
            listener: MediaNotificationListener?,
            sbn: StatusBarNotification?,
        ) {
            if (
                listener == null ||
                    sbn == null ||
                    !DgisNotificationParser.isDgisPackage(sbn.getPackageName())
            ) {
                return
            }
            val raw: String? = DgisNotificationParser.rawSummary(sbn)
            if (raw!!.isEmpty()) return
            val now: Long = System.currentTimeMillis()
            if (
                raw.equals(MediaNotificationListener.lastIgnoredDgis) &&
                    now - MediaNotificationListener.lastIgnoredDgisAt <
                        MediaNotificationListener.IGNORED_LOG_MS
            )
                return
            MediaNotificationListener.lastIgnoredDgis = raw
            MediaNotificationListener.lastIgnoredDgisAt = now
            AppLog.line(listener, "2GIS notification ignored: " + raw)
        }
    }
}
