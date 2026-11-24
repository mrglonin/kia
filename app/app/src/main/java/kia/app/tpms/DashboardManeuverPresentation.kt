package kia.app.tpms

import java.util.Locale
import java.util.regex.Matcher
import java.util.regex.Pattern

/**
 * Read-only maneuver presentation for the in-app dashboard.
 *
 * This class intentionally does not participate in cluster TX. It only chooses a faithful
 * glyph/label from already canonical navigation data.
 */
class DashboardManeuverPresentation
private constructor(glyph: String, fallbackLabel: String, roundaboutExit: Int) {
    @JvmField val glyph: String
    @JvmField val fallbackLabel: String
    @JvmField val roundaboutExit: Int

    init {
        this.glyph = glyph
        this.fallbackLabel = fallbackLabel
        this.roundaboutExit = roundaboutExit
    }

    companion object {
        private val ROUNDABOUT_EXIT: Pattern? =
            Pattern.compile(
                "(?:roundabout|circular)[^0-9]{0,32}(?:exit[^0-9]{0,8})?([1-9])",
                Pattern.CASE_INSENSITIVE,
            )

        @JvmStatic
        fun resolve(
            primary: String?,
            mainManeuverId: String?,
            routeActionId: String?,
            clusterVisual: String?,
        ): DashboardManeuverPresentation {
            val selected: String =
                DashboardManeuverPresentation.firstNonEmpty(
                    primary,
                    mainManeuverId,
                    routeActionId,
                    clusterVisual,
                )!!
            val normalized: String? = DashboardManeuverPresentation.normalize(selected)

            if (
                DashboardManeuverPresentation.hasAny(
                    normalized!!,
                    "route_rerouting",
                    "rerouting_text",
                )
            ) {
                return DashboardManeuverPresentation("↺", "Перестроение", 0)
            }
            if (DashboardManeuverPresentation.hasAny(normalized, "route_loading")) {
                return DashboardManeuverPresentation("…", "Загрузка маршрута", 0)
            }
            if (DashboardManeuverPresentation.isRoundabout(normalized)) {
                var exit: Int = DashboardManeuverPresentation.roundaboutExit(selected)
                if (exit == 0) exit = DashboardManeuverPresentation.roundaboutExit(mainManeuverId)
                if (exit == 0) exit = DashboardManeuverPresentation.roundaboutExit(routeActionId)
                if (exit == 0) exit = DashboardManeuverPresentation.roundaboutExit(clusterVisual)
                if (exit > 0) {
                    return DashboardManeuverPresentation(
                        "↻" + exit,
                        exit.toString() + "-й съезд",
                        exit,
                    )
                }
                return DashboardManeuverPresentation("↻", "Круговое движение", 0)
            }

            val left: Boolean =
                DashboardManeuverPresentation.hasAny(normalized, "left", "налев", "лево", "левее")
            val right: Boolean =
                DashboardManeuverPresentation.hasAny(
                    normalized,
                    "right",
                    "направ",
                    "право",
                    "правее",
                )

            // Specific shapes must win before the generic left/right tokens they contain.
            if (
                DashboardManeuverPresentation.hasAny(
                    normalized,
                    "turn_back",
                    "u_turn",
                    "uturn",
                    "left180",
                    "right180",
                    "развор",
                )
            ) {
                return DashboardManeuverPresentation(
                    if (right) "↷" else "↶",
                    if (right) "Разворот направо"
                    else (if (left) "Разворот налево" else "Разворот"),
                    0,
                )
            }
            if (
                DashboardManeuverPresentation.hasAny(
                    normalized,
                    "hard_turn",
                    "sharp",
                    "left135",
                    "right135",
                    "резк",
                )
            ) {
                return DashboardManeuverPresentation(
                    if (right) "↘" else "↙",
                    if (right) "Резко направо" else "Резко налево",
                    0,
                )
            }
            if (
                DashboardManeuverPresentation.hasAny(
                    normalized,
                    "_take_",
                    "slight",
                    "keep",
                    "fork",
                    "left45",
                    "right45",
                    "shift",
                    "_from_",
                )
            ) {
                return DashboardManeuverPresentation(
                    if (right) "↗" else "↖",
                    if (right) "Держитесь правее" else "Держитесь левее",
                    0,
                )
            }
            if (
                DashboardManeuverPresentation.hasAny(
                    normalized,
                    "_exit_",
                    "ramp",
                    "slip",
                    "съезд",
                    "выезд",
                )
            ) {
                return DashboardManeuverPresentation(
                    if (right) "↗" else "↖",
                    if (right) "Съезд направо" else "Съезд налево",
                    0,
                )
            }
            if (
                DashboardManeuverPresentation.hasAny(normalized, "finish", "финиш", "destination")
            ) {
                return DashboardManeuverPresentation("✓", "Финиш", 0)
            }
            if (right) {
                return DashboardManeuverPresentation("↱", "Поворот направо", 0)
            }
            if (left) {
                return DashboardManeuverPresentation("↰", "Поворот налево", 0)
            }
            return DashboardManeuverPresentation("↑", "Двигайтесь прямо", 0)
        }

        @JvmStatic
        fun roundaboutExit(value: String?): Int {
            val matcher: Matcher? =
                DashboardManeuverPresentation.ROUNDABOUT_EXIT!!.matcher(
                    if (value == null) "" else value
                )
            if (!matcher!!.find()) return 0
            try {
                return Integer.parseInt(matcher.group(1))
            } catch (ignored: RuntimeException) {
                return 0
            }
        }

        private fun isRoundabout(value: String): Boolean {
            return DashboardManeuverPresentation.hasAny(value, "roundabout", "circular", "кругов")
        }

        private fun hasAny(value: String, vararg needles: String?): Boolean {
            for (needle: String? in needles) {
                if (value.contains(needle!!)) return true
            }
            return false
        }

        private fun firstNonEmpty(vararg values: String?): String? {
            for (value: String? in values) {
                if (value != null && !value.trim().isEmpty()) return value
            }
            return ""
        }

        private fun normalize(value: String?): String? {
            return if (value == null) ""
            else value.trim().lowercase(Locale.US).replace('-', '_').replace(' ', '_')
        }
    }
}
