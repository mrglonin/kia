package kia.app.navigation.capture

import java.util.Locale
import kia.app.navigation.domain.ManeuverProgressRolloverPolicy

/** Pure continuity rule for transient zero main-distance samples during micro guidance. */
internal object YandexMainDistanceContinuityPolicy {
    @JvmStatic
    fun identity(routeId: String?, provenance: String?, maneuver: String?): String {
        val route = YandexMainDistanceContinuityPolicy.lower(routeId)
        var main: String? = YandexMainDistanceContinuityPolicy.lower(maneuver).replace('-', '_')
        if (route.isEmpty() || main!!.isEmpty()) return ""
        if (
            main.contains("roundabout") ||
                main.contains("circular") ||
                main.contains("круг") ||
                main.contains("кольц")
        ) {
            main = "roundabout"
        } else if (
            main.contains("turn_back") || main.contains("uturn") || main.contains("развор")
        ) {
            main = "uturn"
        } else if (main.contains("right_from_left") || main.contains("rightfromleft")) {
            main = "right"
        } else if (main.contains("left_from_right") || main.contains("leftfromright")) {
            main = "left"
        } else if (main.contains("left") || main.contains("лев")) {
            main = "left"
        } else if (main.contains("right") || main.contains("прав")) {
            main = "right"
        } else if (main.contains("forward") || main.contains("straight") || main.contains("прям")) {
            main = "forward"
        }
        // Provenance can switch between annotation/notification/bridge_main while Yandex is
        // describing the same semantic maneuver. It must not split the distance event.
        return route + "|" + main
    }

    @JvmStatic
    fun selectProvenanceDistance(
        sameMainIdentity: Boolean,
        provenanceDistance: String?,
        genericDistance: String?,
    ): String {
        val specific = YandexMainDistanceContinuityPolicy.clean(provenanceDistance)
        val generic = YandexMainDistanceContinuityPolicy.clean(genericDistance)
        val specificMeters: Long = YandexSnapshotSemantics.distanceMeters(specific)
        val genericMeters: Long = YandexSnapshotSemantics.distanceMeters(generic)
        if (specificMeters > 1L) return specific
        if (sameMainIdentity && genericMeters > 1L) return generic
        if (!specific.isEmpty()) return specific
        if (sameMainIdentity && !generic.isEmpty()) return generic
        return "0 м"
    }

    @JvmStatic
    fun select(
        previousIdentity: String?,
        currentIdentity: String?,
        previousMeters: Long,
        incomingMeters: Long,
        previousRouteRemaining: Long,
        currentRouteRemaining: Long,
        semanticMicro: Boolean,
        maxDeltaMeters: Long,
        zeroMeters: Long,
    ): Long {
        val result: YandexMainDistanceContinuityPolicy.ZeroContinuity =
            YandexMainDistanceContinuityPolicy.resolveTransientZero(
                previousIdentity,
                currentIdentity,
                previousMeters,
                incomingMeters,
                previousRouteRemaining,
                currentRouteRemaining,
                semanticMicro,
                maxDeltaMeters,
                0L,
                zeroMeters,
            )
        return if (result.handled) result.outputMeters else incomingMeters
    }

    @JvmStatic
    fun preservePreviousAfterRejectedZero(
        previousIdentity: String?,
        currentIdentity: String?,
        previousMeters: Long,
        incomingMeters: Long,
        previousRouteRemaining: Long,
        currentRouteRemaining: Long,
        semanticMicro: Boolean,
        maxDeltaMeters: Long,
        maxRouteGrowthMeters: Long,
        zeroMeters: Long,
    ): Boolean {
        val result: YandexMainDistanceContinuityPolicy.ZeroContinuity =
            YandexMainDistanceContinuityPolicy.resolveTransientZero(
                previousIdentity,
                currentIdentity,
                previousMeters,
                incomingMeters,
                previousRouteRemaining,
                currentRouteRemaining,
                semanticMicro,
                maxDeltaMeters,
                maxRouteGrowthMeters,
                zeroMeters,
            )
        return result.handled && result.outputMeters == incomingMeters
    }

    @JvmStatic
    fun isSameIdentityPostPassJump(
        previousIdentity: String?,
        currentIdentity: String?,
        eventBaseMeters: Long,
        eventClosestMeters: Long,
        incomingMeters: Long,
        previousRouteRemaining: Long,
        currentRouteRemaining: Long,
        maxRouteDeltaMeters: Long,
    ): Boolean {
        val previousKey = YandexMainDistanceContinuityPolicy.clean(previousIdentity)
        val currentKey = YandexMainDistanceContinuityPolicy.clean(currentIdentity)
        if (
            previousKey.isEmpty() ||
                !previousKey.equals(currentKey) ||
                eventBaseMeters <= 0L ||
                eventClosestMeters <= 0L ||
                incomingMeters <= 0L ||
                previousRouteRemaining < 0L ||
                currentRouteRemaining < 0L ||
                maxRouteDeltaMeters < 0L
        ) {
            return false
        }
        val routeDelta: Long = previousRouteRemaining - currentRouteRemaining
        if (routeDelta < 0L || routeDelta > maxRouteDeltaMeters) return false
        val projectedClosest: Long = Math.max(0L, eventClosestMeters - routeDelta)
        if (
            ManeuverProgressRolloverPolicy.shouldStartNewEvent(
                eventBaseMeters.toFloat(),
                projectedClosest.toFloat(),
                incomingMeters.toFloat(),
            )
        ) {
            return true
        }

        // The bridge can miss several near-maneuver samples while Yandex is backgrounded.
        // Treat a far handoff as a candidate only after a strong real countdown; the caller
        // still requires a second consistent snapshot before it emits a rollover marker.
        val countdown: Long = eventBaseMeters - projectedClosest
        val forwardJump: Long = incomingMeters - projectedClosest
        val requiredCountdown: Long = Math.max(250L, Math.round(eventBaseMeters * 0.50))
        val requiredJump: Long = Math.max(200L, projectedClosest)
        return countdown >= requiredCountdown && forwardJump >= requiredJump
    }

    @JvmStatic
    fun confirmsPendingSameIdentityPostPass(
        pendingIdentity: String?,
        currentIdentity: String?,
        pendingClosestMeters: Long,
        pendingMeters: Long,
        incomingMeters: Long,
        pendingRouteRemaining: Long,
        currentRouteRemaining: Long,
        maxRouteDeltaMeters: Long,
    ): Boolean {
        val pendingKey = YandexMainDistanceContinuityPolicy.clean(pendingIdentity)
        val currentKey = YandexMainDistanceContinuityPolicy.clean(currentIdentity)
        if (
            pendingKey.isEmpty() ||
                !pendingKey.equals(currentKey) ||
                pendingClosestMeters <= 0L ||
                pendingMeters <= 0L ||
                incomingMeters <= 0L ||
                pendingRouteRemaining < 0L ||
                currentRouteRemaining < 0L ||
                maxRouteDeltaMeters < 0L
        ) {
            return false
        }
        val routeDelta: Long = pendingRouteRemaining - currentRouteRemaining
        if (routeDelta < 0L || routeDelta > maxRouteDeltaMeters) return false
        return ManeuverProgressRolloverPolicy.confirmsPendingEvent(
            pendingClosestMeters.toFloat(),
            pendingMeters.toFloat(),
            incomingMeters.toFloat(),
            routeDelta.toFloat(),
        )
    }

    @JvmStatic
    /**
     * Resolves only transient 0/1-metre samples. A held result intentionally keeps the last
     * non-zero cluster frame on screen; a projected result supplies a fresh main distance.
     */
    fun resolveTransientZero(
        previousIdentity: String?,
        currentIdentity: String?,
        previousMeters: Long,
        incomingMeters: Long,
        previousRouteRemaining: Long,
        currentRouteRemaining: Long,
        semanticMicro: Boolean,
        maxDeltaMeters: Long,
        maxRouteGrowthMeters: Long,
        zeroMeters: Long,
    ): ZeroContinuity {
        val unhandled: YandexMainDistanceContinuityPolicy.ZeroContinuity =
            ZeroContinuity(false, incomingMeters, previousMeters, previousRouteRemaining, "")
        if (
            incomingMeters < 0L ||
                incomingMeters > zeroMeters ||
                !semanticMicro ||
                previousMeters <= zeroMeters
        ) {
            return unhandled
        }
        val previousKey = YandexMainDistanceContinuityPolicy.clean(previousIdentity)
        val currentKey = YandexMainDistanceContinuityPolicy.clean(currentIdentity)
        if (previousKey.isEmpty() || !previousKey.equals(currentKey)) return unhandled

        if (previousRouteRemaining < 0L || currentRouteRemaining < 0L) {
            val anchoredRoute: Long =
                if (previousRouteRemaining < 0L && currentRouteRemaining >= 0L)
                    currentRouteRemaining
                else previousRouteRemaining
            val mode: String =
                if (previousRouteRemaining < 0L && currentRouteRemaining >= 0L) "route_anchored"
                else "route_unknown_preserved"
            return ZeroContinuity(true, incomingMeters, previousMeters, anchoredRoute, mode)
        }
        if (maxDeltaMeters < 0L) return unhandled

        val routeDelta: Long = previousRouteRemaining - currentRouteRemaining
        if (routeDelta >= 0L && routeDelta <= maxDeltaMeters) {
            val projected: Long = previousMeters - routeDelta
            if (projected > zeroMeters) {
                return ZeroContinuity(
                    true,
                    projected,
                    projected,
                    currentRouteRemaining,
                    "projected",
                )
            }
            return unhandled
        }
        if (routeDelta < 0L && -routeDelta <= Math.max(0L, maxRouteGrowthMeters)) {
            return ZeroContinuity(
                true,
                incomingMeters,
                previousMeters,
                previousRouteRemaining,
                "route_jitter_preserved",
            )
        }
        return unhandled
    }

    @JvmStatic
    fun selectDistanceForProvenance(
        provenance: String?,
        annotationMatchesRaw: Boolean,
        annotationDistance: String?,
        notificationDistance: String?,
        genericDistance: String?,
    ): String {
        val source = YandexMainDistanceContinuityPolicy.lower(provenance)
        if ("annotation".equals(source)) {
            return YandexMainDistanceContinuityPolicy.selectProvenanceDistance(
                annotationMatchesRaw,
                annotationDistance,
                genericDistance,
            )
        }
        if ("notification".equals(source)) {
            if (
                YandexMainDistanceContinuityPolicy.clean(notificationDistance).isEmpty() &&
                    YandexMainDistanceContinuityPolicy.clean(genericDistance).isEmpty()
            ) {
                return ""
            }
            return YandexMainDistanceContinuityPolicy.selectProvenanceDistance(
                true,
                notificationDistance,
                genericDistance,
            )
        }
        return YandexMainDistanceContinuityPolicy.clean(genericDistance)
    }

    @JvmStatic
    fun synchronizedDistanceMeterKeys(provenance: String?): kotlin.Array<String?> {
        val source = YandexMainDistanceContinuityPolicy.lower(provenance)
        if ("annotation".equals(source)) {
            return arrayOf<String?>(
                "maneuver_distance_meters",
                "current_maneuver_distance_meters",
                "distance_to_maneuver_meters",
                "annotation_maneuver_distance_meters",
                "annotation_distance_to_maneuver_meters",
                "guide_maneuver_distance_meters",
                "guide_distance_to_maneuver_meters",
                "displayed_annotation_maneuver_distance_meters",
                "displayed_annotation_distance_to_maneuver_meters",
                "current_annotation_maneuver_distance_meters",
                "current_annotation_distance_to_maneuver_meters",
            )
        }
        if ("notification".equals(source)) {
            return arrayOf<String?>(
                "maneuver_distance_meters",
                "current_maneuver_distance_meters",
                "distance_to_maneuver_meters",
                "notification_maneuver_distance_meters",
                "notification_distance_to_maneuver_meters",
            )
        }
        return arrayOf<String?>(
            "maneuver_distance_meters",
            "current_maneuver_distance_meters",
            "distance_to_maneuver_meters",
        )
    }

    private fun clean(value: String?): String {
        return if (value == null) "" else value.trim()
    }

    private fun lower(value: String?): String {
        return YandexMainDistanceContinuityPolicy.clean(value).lowercase(Locale.US)
    }

    internal class ZeroContinuity(
        handled: Boolean,
        outputMeters: Long,
        stateMeters: Long,
        stateRouteRemaining: Long,
        mode: String?,
    ) {
        @JvmField val handled: Boolean
        @JvmField val outputMeters: Long
        @JvmField val stateMeters: Long
        @JvmField val stateRouteRemaining: Long
        @JvmField val mode: String?

        init {
            this.handled = handled
            this.outputMeters = outputMeters
            this.stateMeters = stateMeters
            this.stateRouteRemaining = stateRouteRemaining
            this.mode = YandexMainDistanceContinuityPolicy.clean(mode)
        }
    }
}
