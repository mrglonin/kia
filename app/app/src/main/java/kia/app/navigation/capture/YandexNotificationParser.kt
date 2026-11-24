package kia.app.navigation.capture

import android.app.Notification
import android.os.Bundle
import android.service.notification.StatusBarNotification
import android.text.TextUtils
import java.util.ArrayList
import java.util.LinkedHashSet
import java.util.Locale
import java.util.regex.Pattern

object YandexNotificationParser {
    private val DISTANCE: Pattern? =
        Pattern.compile(
            "(?iu)(\\d+(?:[\\.,]\\d+)?)\\s*(км|km|м|m|метр(?:ов|а)?|meters?|meter)(?=$|\\s|[,.!?;:|])"
        )
    private val DISTANCE_ONLY: Pattern? =
        Pattern.compile(
            "(?iu)^\\s*\\d+(?:[\\.,]\\d+)?\\s*(?:км|km|м|m|метр(?:ов|а)?|meters?|meter)\\s*$"
        )
    private const val RAW_LIMIT: Int = 180

    @JvmStatic
    fun isYandexNaviPackage(packageName: String?): Boolean {
        val value = if (packageName == null) "" else packageName.lowercase(Locale.US)
        return "ru.yandex.yandexnavi".equals(value) || value.contains("yandexnavi")
    }

    @JvmStatic
    fun parse(sbn: StatusBarNotification?): YandexNotificationParser.Parsed? {
        if (sbn == null || !YandexNotificationParser.isYandexNaviPackage(sbn.getPackageName()))
            return null
        val notification: Notification? = sbn.getNotification()
        if (notification == null) return null
        return YandexNotificationParser.parseLines(
            sbn.getPackageName(),
            YandexNotificationParser.lines(notification.extras),
        )
    }

    @JvmStatic
    fun rawSummary(sbn: StatusBarNotification?): String {
        if (sbn == null || !YandexNotificationParser.isYandexNaviPackage(sbn.getPackageName()))
            return ""
        val notification: Notification? = sbn.getNotification()
        if (notification == null) return ""
        return YandexNotificationParser.shortRaw(
            YandexNotificationParser.join(
                (YandexNotificationParser.unique(
                    YandexNotificationParser.lines(notification.extras)
                ))!!
            )
        )
    }

    private fun lines(extras: Bundle?): ArrayList<String?>? {
        val lines: ArrayList<String?> = ArrayList()
        if (extras == null) return lines
        YandexNotificationParser.addLine(
            lines,
            YandexNotificationParser.charSequence(extras, Notification.EXTRA_TITLE),
        )
        YandexNotificationParser.addLine(
            lines,
            YandexNotificationParser.charSequence(extras, "android.title.big"),
        )
        YandexNotificationParser.addLine(
            lines,
            YandexNotificationParser.charSequence(extras, Notification.EXTRA_TEXT),
        )
        YandexNotificationParser.addLine(
            lines,
            YandexNotificationParser.charSequence(extras, Notification.EXTRA_SUB_TEXT),
        )
        YandexNotificationParser.addLine(
            lines,
            YandexNotificationParser.charSequence(extras, Notification.EXTRA_BIG_TEXT),
        )
        YandexNotificationParser.addLine(
            lines,
            YandexNotificationParser.charSequence(extras, Notification.EXTRA_SUMMARY_TEXT),
        )
        YandexNotificationParser.addLine(
            lines,
            YandexNotificationParser.charSequence(extras, Notification.EXTRA_INFO_TEXT),
        )
        val textLines: kotlin.Array<CharSequence?>? =
            extras.getCharSequenceArray(Notification.EXTRA_TEXT_LINES)
        if (textLines != null) {
            for (line: CharSequence? in textLines) YandexNotificationParser.addLine(lines, line)
        }
        return lines
    }

    private fun parseLines(
        packageName: String?,
        rawLines: ArrayList<String?>?,
    ): YandexNotificationParser.Parsed? {
        val lines: ArrayList<String?> = YandexNotificationParser.unique(rawLines)!!
        if (lines.isEmpty()) return null
        val combined = YandexNotificationParser.join(lines)
        if (!YandexNotificationParser.looksLikeNavigation(combined)) return null
        val distance: YandexNotificationParser.Distance? =
            YandexNotificationParser.distance(lines, combined)
        val maneuver: String = YandexNotificationParser.maneuverFromText(combined)
        val street = YandexNotificationParser.street(lines)
        if (TextUtils.isEmpty(maneuver) && distance == null && TextUtils.isEmpty(street))
            return null
        val distValue = if (distance == null) "" else distance.value
        val distUnit = if (distance == null) "" else distance.unit
        return YandexNotificationParser.Parsed(
            packageName,
            maneuver,
            distValue,
            distUnit,
            street,
            YandexNotificationParser.shortRaw(combined),
        )
    }

    private fun charSequence(extras: Bundle, key: String?): CharSequence? {
        val value: Any? = extras.get(key)
        return if (value is CharSequence) value as CharSequence else null
    }

    private fun addLine(out: ArrayList<String?>, value: CharSequence?) {
        if (value == null) return
        val text = YandexNotificationParser.clean(java.lang.String.valueOf(value))
        if (TextUtils.isEmpty(text)) return
        out.add(text)
    }

    private fun unique(values: ArrayList<String?>?): ArrayList<String?>? {
        val set: LinkedHashSet<String?> = LinkedHashSet()
        if (values != null) {
            for (value: String? in values) {
                val text = YandexNotificationParser.clean(value)
                if (!TextUtils.isEmpty(text)) set.add(text)
            }
        }
        return ArrayList(set)
    }

    private fun distance(
        lines: ArrayList<String?>,
        combined: String?,
    ): YandexNotificationParser.Distance? {
        for (line: String? in lines) {
            val exact = YandexNotificationParser.DISTANCE_ONLY!!.matcher(line)
            if (exact.matches()) return YandexNotificationParser.distanceFromText(line)
        }
        return YandexNotificationParser.distanceFromText(combined)
    }

    private fun distanceFromText(text: String?): YandexNotificationParser.Distance? {
        val matcher = YandexNotificationParser.DISTANCE!!.matcher(text)
        if (!matcher.find()) return null
        val unit = matcher.group(2).lowercase(Locale.US)
        val km: Boolean = unit.contains("км") || unit.contains("km")
        return YandexNotificationParser.Distance(
            matcher.group(1).replace(',', '.'),
            if (km) "км" else "м",
        )
    }

    private fun maneuverFromText(text: String?): String {
        val value = YandexNotificationParser.clean(text).lowercase(Locale.US)
        if (
            value.contains("до конца маршрута") ||
                value.contains("конца маршрута") ||
                value.contains("финиш") ||
                value.contains("место назначения") ||
                value.contains("destination") ||
                value.contains("arriv")
        ) {
            return "context_ra_finish"
        }
        if (
            value.contains("кругов") ||
                value.contains("круге") ||
                value.contains("кольц") ||
                value.contains("roundabout")
        ) {
            val exit: String = YandexNotificationParser.roundaboutExit(value)
            if (!TextUtils.isEmpty(exit)) return exit
            return "context_ra_in_circular_movement"
        }
        if (value.contains("развор") || value.contains("u-turn") || value.contains("uturn")) {
            if (value.contains("направо") || value.contains("right"))
                return "context_ra_turn_back_right"
            if (value.contains("налево") || value.contains("left"))
                return "context_ra_turn_back_left"
            return "context_ra_turn_back"
        }
        if (value.contains("резко направо") || value.contains("sharp right")) {
            return "context_ra_hard_turn_right"
        }
        if (value.contains("резко налево") || value.contains("sharp left")) {
            return "context_ra_hard_turn_left"
        }
        if (
            (value.contains("съезд") || value.contains("exit") || value.contains("ramp")) &&
                (value.contains("направо") || value.contains("right"))
        ) {
            return "context_ra_exit_right"
        }
        if (
            (value.contains("съезд") || value.contains("exit") || value.contains("ramp")) &&
                (value.contains("налево") || value.contains("left"))
        ) {
            return "context_ra_exit_left"
        }
        if (
            value.contains("держитесь правее") ||
                value.contains("правее") ||
                value.contains("take right") ||
                value.contains("keep right")
        ) {
            return "context_ra_exit_right"
        }
        if (
            value.contains("держитесь левее") ||
                value.contains("левее") ||
                value.contains("take left") ||
                value.contains("keep left")
        ) {
            return "context_ra_exit_left"
        }
        if (value.contains("направо") || value.contains("right")) return "context_ra_turn_right"
        if (value.contains("налево") || value.contains("left")) return "context_ra_turn_left"
        if (value.contains("прямо") || value.contains("straight") || value.contains("forward")) {
            return "context_ra_forward"
        }
        return ""
    }

    private fun roundaboutExit(value: String?): String {
        val p = YandexNotificationParser.clean(value).lowercase(Locale.US)
        if (p.matches(Regex(".*\\b(?:1|1st|first|перв(?:ый|ого|ом)?)\\b.*"))) {
            return "context_ra_roundabout_exit_1"
        }
        if (p.matches(Regex(".*\\b(?:2|2nd|second|втор(?:ой|ого|ом)?)\\b.*"))) {
            return "context_ra_roundabout_exit_2"
        }
        if (p.matches(Regex(".*\\b(?:3|3rd|third|трет(?:ий|ьего|ьем)?)\\b.*"))) {
            return "context_ra_roundabout_exit_3"
        }
        if (
            p.matches(
                Regex(".*\\b(?:4|4th|fourth|четв(?:ертый|ертого|ертом|ёртый|ёртого|ёртом)?)\\b.*")
            )
        ) {
            return "context_ra_roundabout_exit_4"
        }
        return ""
    }

    private fun street(lines: ArrayList<String?>): String {
        for (line: String? in lines) {
            val text = YandexNotificationParser.clean(line)
            if (YandexNotificationParser.looksLikeStreetLine(text)) return text
        }
        return ""
    }

    private fun looksLikeStreetLine(value: String?): Boolean {
        val text = YandexNotificationParser.clean(value)
        if (TextUtils.isEmpty(text)) return false
        val lower = text.lowercase(Locale.US)
        if (lower.contains("яндекс") || lower.contains("навиг")) return false
        if (
            lower.contains("через") ||
                lower.contains("повер") ||
                lower.contains("сверн") ||
                lower.contains("держитесь") ||
                lower.contains("направо") ||
                lower.contains("налево") ||
                lower.contains("прямо") ||
                lower.contains("развор") ||
                lower.contains("круг") ||
                lower.contains("до конца") ||
                lower.contains("маршрут") ||
                lower.contains("route") ||
                lower.contains("destination")
        )
            return false
        if (YandexNotificationParser.DISTANCE!!.matcher(lower).find()) return false
        if (lower.contains("мин") || lower.contains("час") || lower.contains("min")) return false
        return lower.matches(Regex(".*[a-zа-яё].*"))
    }

    private fun looksLikeNavigation(value: String?): Boolean {
        val text = YandexNotificationParser.clean(value).lowercase(Locale.US)
        return text.contains("до конца маршрута") ||
            text.contains("конца маршрута") ||
            text.contains("через") ||
            text.contains("повер") ||
            text.contains("сверн") ||
            text.contains("держитесь") ||
            text.contains("направо") ||
            text.contains("налево") ||
            text.contains("прямо") ||
            text.contains("развор") ||
            text.contains("круг") ||
            text.contains("съезд") ||
            text.contains("финиш") ||
            text.contains("exit") ||
            text.contains("ramp") ||
            text.contains("route") ||
            text.contains("turn") ||
            text.contains("straight") ||
            text.contains("destination") ||
            text.contains("arriv")
    }

    private fun join(lines: ArrayList<String?>): String {
        val out: StringBuilder = StringBuilder()
        for (line: String? in lines) {
            if (out.length > 0) out.append(" | ")
            out.append(line)
        }
        return out.toString()
    }

    private fun shortRaw(value: String?): String {
        val text = YandexNotificationParser.clean(value)
        return if (text.length <= YandexNotificationParser.RAW_LIMIT) text
        else text.substring(0, YandexNotificationParser.RAW_LIMIT - 3) + "..."
    }

    private fun clean(value: String?): String {
        if (value == null) return ""
        return value.replace('\n', ' ').replace('\r', ' ').trim().replace(Regex("\\s+"), " ")
    }

    private class Distance(value: String?, unit: String?) {
        @JvmField val value: String?
        @JvmField val unit: String?

        init {
            this.value = value
            this.unit = unit
        }
    }

    class Parsed
    internal constructor(
        packageName: String?,
        maneuver: String?,
        distance: String?,
        unit: String?,
        street: String?,
        raw: String?,
    ) {
        @JvmField val packageName: String?
        @JvmField val maneuver: String?
        @JvmField val distance: String?
        @JvmField val unit: String?
        @JvmField val street: String?
        @JvmField val raw: String?

        init {
            this.packageName = packageName
            this.maneuver = maneuver
            this.distance = distance
            this.unit = unit
            this.street = street
            this.raw = raw
        }
    }
}
