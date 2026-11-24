package kia.app.navigation.capture

import android.app.Notification
import android.os.Bundle
import android.service.notification.StatusBarNotification
import android.text.TextUtils
import java.util.ArrayList
import java.util.LinkedHashSet
import java.util.Locale
import java.util.regex.Pattern

object DgisNotificationParser {
    private val DISTANCE: Pattern? =
        Pattern.compile(
            "(?iu)(\\d+(?:[\\.,]\\d+)?)\\s*(км|km|м|m|метр(?:ов|а)?|meters?|meter)(?=$|\\s|[,.!?;:])"
        )
    private const val RAW_LIMIT: Int = 180

    @JvmStatic
    fun isDgisPackage(packageName: String?): Boolean {
        val value = if (packageName == null) "" else packageName.lowercase(Locale.US)
        return "ru.dublgis.dgismobile".equals(value) ||
            "ru.dublgis.dgismobile4preview".equals(value) ||
            value.contains("dublgis") ||
            value.contains("2gis")
    }

    @JvmStatic
    fun parse(sbn: StatusBarNotification?): DgisNotificationParser.Parsed? {
        if (sbn == null || !DgisNotificationParser.isDgisPackage(sbn.getPackageName())) return null
        val notification: Notification? = sbn.getNotification()
        if (notification == null) return null
        return parse(sbn.getPackageName(), notification.extras)
    }

    @JvmStatic
    fun rawSummary(sbn: StatusBarNotification?): String {
        if (sbn == null || !DgisNotificationParser.isDgisPackage(sbn.getPackageName())) return ""
        val notification: Notification? = sbn.getNotification()
        if (notification == null) return ""
        val lines: ArrayList<String?> = DgisNotificationParser.lines(notification.extras)!!
        return DgisNotificationParser.shortRaw(
            DgisNotificationParser.join(DgisNotificationParser.unique(lines)!!)
        )
    }

    @JvmStatic
    fun parse(packageName: String?, vararg values: String?): DgisNotificationParser.Parsed? {
        if (!DgisNotificationParser.isDgisPackage(packageName)) return null
        val lines: ArrayList<String?> = ArrayList()
        if (values != null) {
            for (value: String? in values) DgisNotificationParser.addLine(lines, value)
        }
        return DgisNotificationParser.parseLines(packageName, lines)
    }

    private fun parse(packageName: String?, extras: Bundle?): DgisNotificationParser.Parsed? {
        if (extras == null) return null
        return DgisNotificationParser.parseLines(packageName, DgisNotificationParser.lines(extras))
    }

    private fun lines(extras: Bundle?): ArrayList<String?>? {
        val lines: ArrayList<String?> = ArrayList()
        if (extras == null) return lines
        DgisNotificationParser.addLine(
            lines,
            DgisNotificationParser.charSequence(extras, Notification.EXTRA_TITLE),
        )
        DgisNotificationParser.addLine(
            lines,
            DgisNotificationParser.charSequence(extras, Notification.EXTRA_TEXT),
        )
        DgisNotificationParser.addLine(
            lines,
            DgisNotificationParser.charSequence(extras, Notification.EXTRA_SUB_TEXT),
        )
        DgisNotificationParser.addLine(
            lines,
            DgisNotificationParser.charSequence(extras, Notification.EXTRA_BIG_TEXT),
        )
        val textLines: kotlin.Array<CharSequence?>? =
            extras.getCharSequenceArray(Notification.EXTRA_TEXT_LINES)
        if (textLines != null) {
            for (line: CharSequence? in textLines) DgisNotificationParser.addLine(lines, line)
        }
        return lines
    }

    private fun parseLines(
        packageName: String?,
        rawLines: ArrayList<String?>?,
    ): DgisNotificationParser.Parsed? {
        val lines: ArrayList<String?> = DgisNotificationParser.unique(rawLines)!!
        if (lines.isEmpty()) return null
        val combined = DgisNotificationParser.join(lines)
        val maneuver: String = DgisNotificationParser.maneuverFromText(combined)
        val distance: DgisNotificationParser.Distance? = DgisNotificationParser.distance(combined)
        val street = DgisNotificationParser.street(lines)
        if (!DgisNotificationParser.looksLikeNavigation(combined)) return null
        if (TextUtils.isEmpty(maneuver) && distance == null) return null
        val distValue = if (distance == null) "" else distance.value
        val distUnit = if (distance == null) "" else distance.unit
        return DgisNotificationParser.Parsed(
            packageName,
            maneuver,
            distValue,
            distUnit,
            street,
            DgisNotificationParser.shortRaw(combined),
        )
    }

    private fun charSequence(extras: Bundle, key: String?): CharSequence? {
        val value: Any? = extras.get(key)
        return if (value is CharSequence) value as CharSequence else null
    }

    private fun addLine(out: ArrayList<String?>, value: CharSequence?) {
        if (value == null) return
        val text = DgisNotificationParser.clean(java.lang.String.valueOf(value))
        if (TextUtils.isEmpty(text)) return
        out.add(text)
    }

    private fun unique(values: ArrayList<String?>?): ArrayList<String?>? {
        val set: LinkedHashSet<String?> = LinkedHashSet()
        if (values != null) {
            for (value: String? in values) {
                val text = DgisNotificationParser.clean(value)
                if (!TextUtils.isEmpty(text)) set.add(text)
            }
        }
        return ArrayList(set)
    }

    private fun distance(text: String?): DgisNotificationParser.Distance? {
        val matcher = DgisNotificationParser.DISTANCE!!.matcher(text)
        if (!matcher.find()) return null
        val unit = matcher.group(2).lowercase(Locale.US)
        val km: Boolean = unit.contains("км") || unit.contains("km")
        return DgisNotificationParser.Distance(
            matcher.group(1).replace(',', '.'),
            if (km) "км" else "м",
        )
    }

    private fun maneuverFromText(text: String?): String {
        val value = DgisNotificationParser.clean(text).lowercase(Locale.US)
        if (
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
            val exit: String = DgisNotificationParser.roundaboutExit(value)
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
        val p = DgisNotificationParser.clean(value).lowercase(Locale.US)
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
            val text = DgisNotificationParser.clean(line)
            if (DgisNotificationParser.looksLikeStreetLine(text)) return text
        }
        for (line: String? in lines) {
            val text = DgisNotificationParser.streetFromInstruction(line)
            if (DgisNotificationParser.looksLikeStreetLine(text)) return text
        }
        return ""
    }

    private fun streetFromInstruction(line: String?): String {
        val text = DgisNotificationParser.clean(line)
        val matcher =
            Pattern.compile(
                    "(?iu)\\b(?:на|по|к)\\s+((?:ул\\.?|улиц[ауеы]?|проспект|просп\\.?|пр-т|пр-кт|шоссе|бульвар|переулок|площадь|дорога|наб\\.?)[^,.;!?]+)"
                )
                .matcher(text)
        if (matcher.find()) return DgisNotificationParser.clean(matcher.group(1))
        return ""
    }

    private fun looksLikeStreetLine(value: String?): Boolean {
        val text = DgisNotificationParser.clean(value)
        if (TextUtils.isEmpty(text)) return false
        val lower = text.lowercase(Locale.US)
        if (lower.equals("2гис") || lower.equals("2gis") || lower.contains("навиг")) return false
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
                lower.contains("route") ||
                lower.contains("destination")
        )
            return false
        if (DgisNotificationParser.DISTANCE!!.matcher(lower).find()) return false
        if (lower.contains("мин") || lower.contains("час") || lower.contains("min")) return false
        return lower.matches(Regex(".*[a-zа-яё].*"))
    }

    private fun looksLikeNavigation(value: String?): Boolean {
        val text = DgisNotificationParser.clean(value).lowercase(Locale.US)
        return text.contains("через") ||
            text.contains("повер") ||
            text.contains("сверн") ||
            text.contains("держитесь") ||
            text.contains("направо") ||
            text.contains("налево") ||
            text.contains("прямо") ||
            text.contains("развор") ||
            text.contains("круг") ||
            text.contains("съезд") ||
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
        val text = DgisNotificationParser.clean(value)
        return if (text.length <= DgisNotificationParser.RAW_LIMIT) text
        else text.substring(0, DgisNotificationParser.RAW_LIMIT - 3) + "..."
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
