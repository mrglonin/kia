package kia.app.navigation.capture

import java.util.HashSet

/**
 * Rejects stale bridge envelopes before they are allowed to mutate route or maneuver state. A
 * trusted stream has a sequence or elapsed-realtime stamp.
 */
internal class YandexSnapshotOrderGuard {
    internal enum class Result {
        ACCEPT,
        ACCEPT_UNTRUSTED_INITIAL,
        REJECT_STALE_SEQUENCE,
        REJECT_STALE_TIMESTAMP,
        REJECT_RETIRED_ROUTE,
        REJECT_UNTRUSTED_AFTER_TRUSTED,
    }

    private var lastSequence: Long = Long.MIN_VALUE
    private var lastTimestamp: Long = Long.MIN_VALUE
    private var routeId: String = ""
    private var trustedSeen: Boolean = false
    private val retiredRouteIds: MutableSet<String?> = HashSet()

    fun evaluate(
        incomingRouteId: String?,
        sequence: Long,
        timestamp: Long,
    ): kia.app.navigation.capture.YandexSnapshotOrderGuard.Result {
        val cleanRouteId = YandexSnapshotOrderGuard.clean(incomingRouteId)
        val hasSequence: Boolean = sequence >= 0L
        val hasTimestamp: Boolean = timestamp > 0L
        if (hasTimestamp && lastTimestamp != Long.MIN_VALUE && timestamp < lastTimestamp) {
            return kia.app.navigation.capture.YandexSnapshotOrderGuard.Result.REJECT_STALE_TIMESTAMP
        }
        val routeChange: Boolean =
            !cleanRouteId.isEmpty() && !routeId.isEmpty() && !cleanRouteId.equals(routeId)
        if (routeChange) {
            if (retiredRouteIds.contains(cleanRouteId)) {
                return kia.app.navigation.capture.YandexSnapshotOrderGuard.Result
                    .REJECT_RETIRED_ROUTE
            }
            if (
                !hasSequence &&
                    hasTimestamp &&
                    lastTimestamp != Long.MIN_VALUE &&
                    timestamp <= lastTimestamp
            ) {
                return kia.app.navigation.capture.YandexSnapshotOrderGuard.Result
                    .REJECT_STALE_TIMESTAMP
            }
            if (!hasSequence && !hasTimestamp && trustedSeen) {
                return kia.app.navigation.capture.YandexSnapshotOrderGuard.Result
                    .REJECT_UNTRUSTED_AFTER_TRUSTED
            }
            retiredRouteIds.add(routeId)
            routeId = cleanRouteId
            lastSequence = Long.MIN_VALUE
        }

        if (!hasSequence && !hasTimestamp) {
            return if (trustedSeen)
                kia.app.navigation.capture.YandexSnapshotOrderGuard.Result
                    .REJECT_UNTRUSTED_AFTER_TRUSTED
            else kia.app.navigation.capture.YandexSnapshotOrderGuard.Result.ACCEPT_UNTRUSTED_INITIAL
        }

        if (hasSequence && lastSequence != Long.MIN_VALUE) {
            if (sequence < lastSequence) {
                val producerRestart: Boolean =
                    hasTimestamp && lastTimestamp != Long.MIN_VALUE && timestamp > lastTimestamp
                if (!producerRestart)
                    return kia.app.navigation.capture.YandexSnapshotOrderGuard.Result
                        .REJECT_STALE_SEQUENCE
                lastSequence = Long.MIN_VALUE
            }
            if (sequence == lastSequence) {
                return kia.app.navigation.capture.YandexSnapshotOrderGuard.Result
                    .REJECT_STALE_SEQUENCE
            }
        } else if (
            !hasSequence &&
                hasTimestamp &&
                lastTimestamp != Long.MIN_VALUE &&
                timestamp <= lastTimestamp
        ) {
            return kia.app.navigation.capture.YandexSnapshotOrderGuard.Result.REJECT_STALE_TIMESTAMP
        }

        trustedSeen = true
        if (hasSequence) lastSequence = sequence
        if (hasTimestamp) lastTimestamp = timestamp
        if (!cleanRouteId.isEmpty()) routeId = cleanRouteId
        return kia.app.navigation.capture.YandexSnapshotOrderGuard.Result.ACCEPT
    }

    fun reset() {
        lastSequence = Long.MIN_VALUE
        lastTimestamp = Long.MIN_VALUE
        routeId = ""
        trustedSeen = false
        retiredRouteIds.clear()
    }

    companion object {
        private fun clean(value: String?): String {
            return if (value == null) "" else value.trim()
        }
    }
}
