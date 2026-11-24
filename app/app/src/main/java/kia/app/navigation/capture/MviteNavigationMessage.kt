package kia.app.navigation.capture

import java.util.Locale
import java.util.regex.Pattern
import org.json.JSONException
import org.json.JSONObject
import org.json.JSONTokener

/** One event from the YNavi MVite navigation broadcast. */
@kotlin.jvm.JvmRecord
data class MviteNavigationMessage(
    val kind: Kind?,
    val maneuver: String?,
    val distanceMeters: Double,
    val text: String?,
    val value: Int,
    val active: Boolean,
) {
    enum class Kind {
        ROUTE_ACTIVE,
        MANEUVER,
        EXIT_NUMBER,
        ROUTE_DISTANCE,
        REMAINING_TIME,
        ARRIVAL_TIME,
        SPEED_LIMIT,
        OVERSPEED,
        CAMERA,
    }

    companion object {
        const val ACTION: String = "ssa.labs.monjaro.YN_HOOK"

        private const val MAX_PAYLOAD_CHARS: Int = 4096
        private const val MAX_JSON_DEPTH: Int = 8
        private const val MAX_ROAD_CODE_POINTS: Int = 160
        private const val MAX_DISTANCE_METERS: Double = 100000000.0
        private val MANEUVER_PREFIX: String = "context_ra_"
        private val ROAD_CONTROLS: Pattern? = Pattern.compile("[\\p{Cc}\\p{Cf}\\p{Zl}\\p{Zp}]+")
        private val CAMERA_DISTANCE: Pattern? =
            Pattern.compile("([0-9]{1,7}(?:[.,][0-9]{1,4})?)[ \\p{Zs}]*(km|км|m|м)")

        @JvmStatic
        fun parse(payload: String?): MviteNavigationMessage? {
            if (
                payload == null ||
                    payload.length > MviteNavigationMessage.MAX_PAYLOAD_CHARS ||
                    payload.indexOf('\u0000') >= 0
            )
                return null
            val input = payload.trim()
            if (
                !input.startsWith("{") ||
                    !input.endsWith("}") ||
                    !MviteNavigationMessage.hasBoundedNesting(input)
            )
                return null

            try {
                val tokens: JSONTokener = JSONTokener(input)
                val event = tokens.nextValue() as? JSONObject ?: return null
                if (tokens.nextClean() != '\u0000') {
                    return null
                }
                val name = event.opt("event") as? String ?: return null

                return when (name) {
                    "RouteActive" -> MviteNavigationMessage.booleanEvent(Kind.ROUTE_ACTIVE, event)
                    "Maneuver" -> MviteNavigationMessage.maneuverEvent(event)
                    "Maneuver.ExitNumber" ->
                        MviteNavigationMessage.integerEvent(Kind.EXIT_NUMBER, event, -1, 99)
                    "Eta.DistanceLeft" -> MviteNavigationMessage.routeDistance(event)
                    "Eta.TimeLeft" ->
                        MviteNavigationMessage.timeEvent(Kind.REMAINING_TIME, event, 999)
                    "Eta.ArrivalTime" ->
                        MviteNavigationMessage.timeEvent(Kind.ARRIVAL_TIME, event, 23)
                    "SpeedLimit" ->
                        MviteNavigationMessage.integerEvent(Kind.SPEED_LIMIT, event, 0, 255)
                    "SpeedLimit.Alarm" -> MviteNavigationMessage.booleanEvent(Kind.OVERSPEED, event)
                    "Camera" -> MviteNavigationMessage.cameraEvent(event)
                    else -> null
                }
            } catch (ignored: JSONException) {
                return null
            }
        }

        private fun maneuverEvent(event: JSONObject): MviteNavigationMessage? {
            val sign = event.opt("sign") as? String ?: return null
            val name =
                if (sign.startsWith(MviteNavigationMessage.MANEUVER_PREFIX))
                    sign.substring(MviteNavigationMessage.MANEUVER_PREFIX.length)
                else sign
            val maneuver =
                when (name) {
                    "forward",
                    "finish",
                    "boardferry",
                    "turn_left",
                    "turn_right",
                    "hard_turn_left",
                    "hard_turn_right",
                    "take_left",
                    "take_right",
                    "exit_left",
                    "exit_right",
                    "turn_back_left",
                    "turn_back_right",
                    "in_circular_movement",
                    "out_circular_movement" -> MviteNavigationMessage.MANEUVER_PREFIX + name
                    else -> ""
                }
            val distance: Double =
                MviteNavigationMessage.distanceInMeters(event.opt("distance"), event.opt("metric"))
            if (maneuver.isEmpty() || !java.lang.Double.isFinite(distance)) return null

            return MviteNavigationMessage(
                Kind.MANEUVER,
                maneuver,
                distance,
                MviteNavigationMessage.roadText(event.opt("nextRoad")),
                0,
                false,
            )
        }

        private fun routeDistance(event: JSONObject): MviteNavigationMessage? {
            val distance: Double =
                MviteNavigationMessage.distanceInMeters(event.opt("value"), event.opt("metric"))
            return if (java.lang.Double.isFinite(distance))
                MviteNavigationMessage(Kind.ROUTE_DISTANCE, "", distance, "", 0, false)
            else null
        }

        private fun booleanEvent(kind: Kind?, event: JSONObject): MviteNavigationMessage? {
            val active = event.opt("value") as? Boolean ?: return null
            return MviteNavigationMessage(kind, "", 0.0, "", 0, active)
        }

        private fun integerEvent(
            kind: Kind?,
            event: JSONObject,
            min: Int,
            max: Int,
        ): MviteNavigationMessage? {
            val value: Int? = MviteNavigationMessage.integerInRange(event.opt("value"), min, max)
            if (value == null) return null
            return MviteNavigationMessage(kind, "", 0.0, "", value, false)
        }

        private fun timeEvent(
            kind: Kind?,
            event: JSONObject,
            maxHour: Int,
        ): MviteNavigationMessage? {
            val hour: Int? = MviteNavigationMessage.integerInRange(event.opt("hour"), 0, maxHour)
            val minute: Int? = MviteNavigationMessage.integerInRange(event.opt("minute"), 0, 59)
            if (hour == null || minute == null) return null
            val time = String.format(Locale.ROOT, "%02d:%02d", hour, minute)
            return MviteNavigationMessage(kind, "", 0.0, time, hour * 60 + minute, false)
        }

        private fun cameraEvent(event: JSONObject): MviteNavigationMessage? {
            val action = event.opt("action") as? String ?: return null
            if (action.equals("hide"))
                return MviteNavigationMessage(Kind.CAMERA, "", 0.0, "", 0, false)
            if (action != "show" && action != "update") return null
            val rawDistance = event.opt("dist") as? String ?: return null

            val match = MviteNavigationMessage.CAMERA_DISTANCE!!.matcher(rawDistance.trim())
            if (!match.matches()) return null
            val number: Double = java.lang.Double.parseDouble(match.group(1).replace(',', '.'))
            val unit = match.group(2)
            val meters: Double = MviteNavigationMessage.distanceInMeters(number, unit)
            if (!java.lang.Double.isFinite(meters)) return null

            return MviteNavigationMessage(
                Kind.CAMERA,
                "",
                meters,
                match.group(1) + " " + unit,
                0,
                true,
            )
        }

        private fun integerInRange(raw: Any?, min: Int, max: Int): Int? {
            if (raw !is Number) return null
            val value: Double = raw.toDouble()
            if (
                !java.lang.Double.isFinite(value) ||
                    value < min ||
                    value > max ||
                    value != Math.rint(value)
            )
                return null
            return value.toInt()
        }

        private fun distanceInMeters(raw: Any?, metric: Any?): Double {
            if (raw !is Number || metric !is String) return Double.NaN
            val distance: Double =
                when (metric) {
                    "m",
                    "м" -> raw.toDouble()
                    "km",
                    "км" -> raw.toDouble() * 1000
                    else -> Double.NaN
                }
            return if (distance >= 0 && distance <= MviteNavigationMessage.MAX_DISTANCE_METERS)
                distance
            else Double.NaN
        }

        private fun roadText(raw: Any?): String {
            if (raw !is String) return ""
            val cleaned = MviteNavigationMessage.ROAD_CONTROLS!!.matcher(raw).replaceAll(" ").trim()
            if (cleaned.equals("null")) return ""
            if (
                cleaned.codePointCount(0, cleaned.length) <=
                    MviteNavigationMessage.MAX_ROAD_CODE_POINTS
            )
                return cleaned
            return cleaned
                .substring(
                    0,
                    cleaned.offsetByCodePoints(0, MviteNavigationMessage.MAX_ROAD_CODE_POINTS),
                )
                .trim()
        }

        // Bound recursion in the platform JSON parser before it processes unknown fields.
        private fun hasBoundedNesting(input: String): Boolean {
            var depth: Int = 0
            var inString: Boolean = false
            var i: Int = 0
            while (i < input.length) {
                val character: Char = input.get(i)
                if (inString && character == '\\') {
                    i++
                } else if (character == '"') {
                    inString = !inString
                } else if (!inString && (character == '[' || character == '{')) {
                    if (++depth > MviteNavigationMessage.MAX_JSON_DEPTH) return false
                } else if (!inString && (character == ']' || character == '}')) {
                    if (--depth < 0) return false
                }
                i++
            }
            return !inString && depth == 0
        }
    }
}
