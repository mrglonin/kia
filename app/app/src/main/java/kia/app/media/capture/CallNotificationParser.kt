package kia.app.media.capture

import android.app.Notification
import android.os.Bundle
import android.service.notification.StatusBarNotification
import android.text.TextUtils
import java.util.Locale

internal object CallNotificationParser {
    @JvmStatic
    fun parse(sbn: StatusBarNotification?): CallNotificationParser.Parsed? {
        if (sbn == null || sbn.getNotification() == null) return null
        val pkg: String? = CallNotificationParser.clean(sbn.getPackageName())
        val notification: Notification? = sbn.getNotification()
        if (
            !CallNotificationParser.looksLikeCallPackage(pkg!!) &&
                !Notification.CATEGORY_CALL.equals(notification!!.category)
        ) {
            return null
        }
        val extras: Bundle? = notification!!.extras
        val title: String? =
            if (extras == null) ""
            else
                CallNotificationParser.clean(
                    java.lang.String.valueOf(extras.getCharSequence(Notification.EXTRA_TITLE, ""))
                )
        val text: String? =
            if (extras == null) ""
            else
                CallNotificationParser.clean(
                    java.lang.String.valueOf(extras.getCharSequence(Notification.EXTRA_TEXT, ""))
                )
        val bigText: String? =
            if (extras == null) ""
            else
                CallNotificationParser.clean(
                    java.lang.String.valueOf(
                        extras.getCharSequence(Notification.EXTRA_BIG_TEXT, "")
                    )
                )
        if (
            CallNotificationParser.looksLikeMissedCall(title!!) ||
                CallNotificationParser.looksLikeMissedCall(text!!) ||
                CallNotificationParser.looksLikeMissedCall(bigText!!) ||
                CallNotificationParser.looksLikeMissedCall(sbn.getTag())
        ) {
            return null
        }
        var name: String? = CallNotificationParser.firstUseful(title, text, bigText)
        val phone: String? = CallNotificationParser.firstPhone(text, bigText, title)
        if (TextUtils.isEmpty(name) && TextUtils.isEmpty(phone)) return null
        if (TextUtils.equals(name, phone)) name = ""
        return CallNotificationParser.Parsed(name, phone, pkg)
    }

    @JvmStatic
    fun isCallLikePackage(packageName: String?): Boolean {
        return CallNotificationParser.looksLikeCallPackage(
            CallNotificationParser.clean(packageName)!!
        )
    }

    private fun looksLikeCallPackage(packageName: String): Boolean {
        val p = packageName.lowercase(Locale.ROOT)
        return p.contains("phone") ||
            p.contains("dialer") ||
            p.contains("telecom") ||
            p.contains("incall") ||
            p.contains("bluetooth") ||
            p.contains("btphone")
    }

    private fun firstUseful(first: String, second: String, third: String): String? {
        if (CallNotificationParser.usefulName(first)) return first
        if (CallNotificationParser.usefulName(second)) return second
        if (CallNotificationParser.usefulName(third)) return third
        return ""
    }

    private fun usefulName(value: String?): Boolean {
        if (value.isNullOrEmpty()) return false
        val v = value.lowercase(Locale.ROOT)
        return !v.contains("звон") &&
            !v.contains("call") &&
            !v.contains("bluetooth") &&
            !v.contains("phone")
    }

    private fun firstPhone(first: String, second: String, third: String): String? {
        if (CallNotificationParser.looksLikePhone(first)) return first
        if (CallNotificationParser.looksLikePhone(second)) return second
        if (CallNotificationParser.looksLikePhone(third)) return third
        return ""
    }

    private fun looksLikePhone(value: String?): Boolean {
        if (value.isNullOrEmpty()) return false
        var digits: Int = 0
        for (i in 0..<value.length) {
            if (Character.isDigit(value.get(i))) digits++
        }
        return digits >= 5
    }

    private fun looksLikeMissedCall(value: String?): Boolean {
        if (value.isNullOrEmpty()) return false
        val v = value.lowercase(Locale.ROOT)
        return v.contains("пропущ") || v.contains("missed call") || v.contains("missedcall")
    }

    private fun clean(value: String?): String? {
        if (value == null) return ""
        return value.replace('\n', ' ').replace('\r', ' ').trim().replace(Regex("\\s+"), " ")
    }

    internal class Parsed(name: String?, phone: String?, source: String?) {
        @JvmField val name: String?
        @JvmField val phone: String?
        @JvmField val source: String?

        init {
            this.name = name
            this.phone = phone
            this.source = source
        }
    }
}
