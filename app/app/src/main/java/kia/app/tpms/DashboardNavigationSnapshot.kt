package kia.app.tpms

import java.util.Locale
import java.util.regex.Matcher
import java.util.regex.Pattern
import kia.app.core.model.NavigationState

/**
 * Read-only projection of the maneuver that was actually saved for cluster TX. Provider/main values
 * are used only when neither clusterVisual nor clusterTx contains a maneuver frame.
 */
class DashboardNavigationSnapshot
private constructor(
    maneuverId: String?,
    distance: String?,
    grayRoadId: String?,
    clusterFrame: String?,
    clusterBacked: Boolean,
    presentation: DashboardManeuverPresentation,
) {
    @JvmField val maneuverId: String
    @JvmField val distance: String
    @JvmField val grayRoad: String
    @JvmField val clusterFrame: String
    @JvmField val clusterBacked: Boolean
    @JvmField val presentation: DashboardManeuverPresentation

    init {
        this.maneuverId = DashboardNavigationSnapshot.clean(maneuverId)
        this.distance = DashboardNavigationSnapshot.clean(distance)
        this.grayRoad = DashboardNavigationSnapshot.clean(grayRoadId)
        this.clusterFrame = DashboardNavigationSnapshot.clean(clusterFrame)
        this.clusterBacked = clusterBacked
        this.presentation = presentation
    }

    private class ParsedFrame(
        maneuverId: String?,
        distance: String?,
        grayRoadId: String?,
        raw: String?,
    ) {
        @JvmField val maneuverId: String
        @JvmField val distance: String
        @JvmField val grayRoadId: String
        @JvmField val raw: String

        init {
            this.maneuverId = DashboardNavigationSnapshot.clean(maneuverId)
            this.distance = DashboardNavigationSnapshot.clean(distance)
            this.grayRoadId = DashboardNavigationSnapshot.clean(grayRoadId)
            this.raw = DashboardNavigationSnapshot.clean(raw)
        }

        fun available(): Boolean {
            return !maneuverId!!.isEmpty()
        }

        fun sameManeuver(other: ParsedFrame?): Boolean {
            return other != null &&
                available() &&
                other.available() &&
                maneuverId.equals(other.maneuverId)
        }

        fun fillMissingFrom(fallback: ParsedFrame?): ParsedFrame {
            if (fallback == null) return this
            return ParsedFrame(
                maneuverId,
                if (distance!!.isEmpty()) fallback.distance else distance,
                if (grayRoadId!!.isEmpty()) fallback.grayRoadId else grayRoadId,
                raw,
            )
        }

        companion object {
            @JvmStatic
            fun empty(): ParsedFrame {
                return ParsedFrame("", "", "", "")
            }
        }
    }

    companion object {
        private val DISTANCE_TOKEN: Pattern? =
            Pattern.compile(
                "([-+]?\\d+(?:[\\.,]\\d+)?)\\s*(км|km|м|m)",
                Pattern.CASE_INSENSITIVE or Pattern.UNICODE_CASE,
            )

        @JvmStatic
        fun resolve(state: NavigationState?): DashboardNavigationSnapshot {
            val safe: NavigationState = if (state == null) NavigationState.empty() else state
            val visual: ParsedFrame =
                DashboardNavigationSnapshot.parseClusterVisual(safe.clusterVisual)
            val tx: ParsedFrame = DashboardNavigationSnapshot.parseLatestClusterTx(safe.clusterTx)
            val actual: ParsedFrame?
            if (visual.available()) {
                actual = if (visual.sameManeuver(tx)) visual.fillMissingFrom(tx) else visual
            } else {
                actual = tx
            }
            if (actual.available()) {
                val presentationId: String? =
                    if (actual.raw!!.lowercase(Locale.ROOT).contains("rerouting_text"))
                        "route_rerouting"
                    else actual.maneuverId
                val presentation: DashboardManeuverPresentation =
                    DashboardManeuverPresentation.resolve(presentationId, "", "", "")
                return DashboardNavigationSnapshot(
                    actual.maneuverId,
                    actual.distance,
                    actual.grayRoadId,
                    actual.raw,
                    true,
                    presentation,
                )
            }

            val providerManeuver: String =
                DashboardNavigationSnapshot.firstNonEmpty(
                    safe.maneuver,
                    safe.mainManeuverId,
                    safe.routeActionId,
                    safe.maneuverText,
                )
            val providerDistance: String =
                DashboardNavigationSnapshot.firstNonEmpty(
                    safe.maneuverDistance,
                    safe.distance,
                    safe.routeDistance,
                )
            val providerGray: String =
                DashboardNavigationSnapshot.firstNonEmpty(safe.grayRoadScheme, safe.grayRoadId)
            val presentation: DashboardManeuverPresentation =
                DashboardManeuverPresentation.resolve(
                    providerManeuver,
                    safe.mainManeuverId,
                    safe.routeActionId,
                    "",
                )
            return DashboardNavigationSnapshot(
                providerManeuver,
                providerDistance,
                providerGray,
                "",
                false,
                presentation,
            )
        }

        private fun parseClusterVisual(value: String?): ParsedFrame {
            val raw: String? = DashboardNavigationSnapshot.clean(value)
            if (raw!!.isEmpty()) return ParsedFrame.empty()
            val parts: kotlin.Array<out String?>? = java.util.regex.Pattern.compile("/").split(raw)
            val visual: String? =
                DashboardNavigationSnapshot.clean(if (parts!!.size == 0) raw else parts.get(0))
            val plus: Int = visual!!.indexOf(" + ")
            val maneuver: String? =
                DashboardNavigationSnapshot.clean(
                    if (plus >= 0) visual.substring(0, plus) else visual
                )
            val gray: String? =
                DashboardNavigationSnapshot.clean(if (plus >= 0) visual.substring(plus + 3) else "")
            var distance: String = ""
            var index: Int = 1
            while (index < parts.size && distance.isEmpty()) {
                distance = DashboardNavigationSnapshot.normalizeDistance(parts.get(index))
                index++
            }
            return if (DashboardNavigationSnapshot.isManeuverFrame(maneuver))
                ParsedFrame(maneuver, distance, gray, raw)
            else ParsedFrame.empty()
        }

        private fun parseLatestClusterTx(value: String?): ParsedFrame {
            val raw: String? = DashboardNavigationSnapshot.clean(value)
            if (raw!!.isEmpty()) return ParsedFrame.empty()
            val lines: kotlin.Array<out String?>? =
                java.util.regex.Pattern.compile("\\n").split(raw)
            for (index in lines!!.indices.reversed()) {
                val parsed: ParsedFrame =
                    DashboardNavigationSnapshot.parseClusterTxLine(lines.get(index))
                if (parsed.available()) return parsed
            }
            return ParsedFrame.empty()
        }

        private fun parseClusterTxLine(value: String?): ParsedFrame {
            val raw: String? = DashboardNavigationSnapshot.clean(value)
            if (raw!!.startsWith("maneuver+gray ")) {
                val maneuver: String? =
                    DashboardNavigationSnapshot.before(
                        DashboardNavigationSnapshot.tokenAfter(raw, "maneuver+gray "),
                        " gray=",
                    )
                val gray: String? =
                    DashboardNavigationSnapshot.before(
                        DashboardNavigationSnapshot.tokenAfter(raw, " gray="),
                        " dist=",
                    )
                val distance: String =
                    (DashboardNavigationSnapshot.normalizeDistance(
                        DashboardNavigationSnapshot.tokenAfter(raw, " dist=")
                    ))
                return ParsedFrame(maneuver, distance, gray, raw)
            }
            if (raw.startsWith("maneuver ")) {
                val maneuver: String? =
                    DashboardNavigationSnapshot.before(
                        DashboardNavigationSnapshot.tokenAfter(raw, "maneuver "),
                        " dist=",
                    )
                val distance: String =
                    (DashboardNavigationSnapshot.normalizeDistance(
                        DashboardNavigationSnapshot.tokenAfter(raw, " dist=")
                    ))
                return ParsedFrame(maneuver, distance, "", raw)
            }
            if (raw.startsWith("finish direction ")) {
                val distance: String =
                    (DashboardNavigationSnapshot.normalizeDistance(
                        DashboardNavigationSnapshot.tokenAfter(raw, " dist=")
                    ))
                return ParsedFrame("finish direction", distance, "", raw)
            }
            return ParsedFrame.empty()
        }

        private fun isManeuverFrame(value: String?): Boolean {
            val text: String? = DashboardNavigationSnapshot.clean(value).lowercase(Locale.ROOT)
            return text!!.startsWith("context_ra_") ||
                text.startsWith("finish direction") ||
                text.startsWith("direction_to_finish") ||
                text.startsWith("route_loading") ||
                text.startsWith("route_rerouting")
        }

        private fun normalizeDistance(value: String?): String {
            val matcher: Matcher? =
                DashboardNavigationSnapshot.DISTANCE_TOKEN!!.matcher(
                    DashboardNavigationSnapshot.clean(value)
                )
            if (!matcher!!.find()) return ""
            val unit: String? = matcher.group(2).lowercase(Locale.ROOT)
            return DashboardNavigationSnapshot.trimNumber(matcher.group(1)).toString() +
                (if (unit.equals("km") || unit.equals("км")) " км" else " м")
        }

        private fun trimNumber(value: String?): String? {
            var text: String? = DashboardNavigationSnapshot.clean(value).replace(',', '.')
            while (text!!.endsWith("0") && text.contains(".")) {
                text = text.substring(0, text.length - 1)
            }
            if (text.endsWith(".")) text = text.substring(0, text.length - 1)
            return text.replace('.', ',')
        }

        private fun tokenAfter(value: String?, marker: String): String? {
            val text: String? = DashboardNavigationSnapshot.clean(value)
            val start: Int = text!!.indexOf(marker)
            return if (start < 0) ""
            else DashboardNavigationSnapshot.clean(text.substring(start + marker.length))
        }

        private fun before(value: String?, marker: String?): String? {
            val text: String? = DashboardNavigationSnapshot.clean(value)
            val end: Int = text!!.indexOf(marker!!)
            return if (end < 0) text else DashboardNavigationSnapshot.clean(text.substring(0, end))
        }

        private fun firstNonEmpty(vararg values: String?): String {
            if (values == null) return ""
            for (value: String? in values) {
                val clean: String? = DashboardNavigationSnapshot.clean(value)
                if (!clean!!.isEmpty()) return clean
            }
            return ""
        }

        private fun clean(value: String?): String {
            return if (value == null) "" else value.trim()
        }
    }
}
