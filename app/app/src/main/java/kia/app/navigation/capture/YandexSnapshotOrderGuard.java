package kia.app.navigation.capture;

import java.util.HashSet;
import java.util.Set;

/**
 * Rejects stale bridge envelopes before they are allowed to mutate route or
 * maneuver state.  A trusted stream has a sequence or elapsed-realtime stamp.
 */
final class YandexSnapshotOrderGuard {
    enum Result {
        ACCEPT,
        ACCEPT_UNTRUSTED_INITIAL,
        REJECT_STALE_SEQUENCE,
        REJECT_STALE_TIMESTAMP,
        REJECT_RETIRED_ROUTE,
        REJECT_UNTRUSTED_AFTER_TRUSTED
    }

    private long lastSequence = Long.MIN_VALUE;
    private long lastTimestamp = Long.MIN_VALUE;
    private String routeId = "";
    private boolean trustedSeen;
    private final Set<String> retiredRouteIds = new HashSet<>();

    Result evaluate(String incomingRouteId, long sequence, long timestamp) {
        String cleanRouteId = clean(incomingRouteId);
        boolean hasSequence = sequence >= 0L;
        boolean hasTimestamp = timestamp > 0L;
        if (hasTimestamp && lastTimestamp != Long.MIN_VALUE
                && timestamp < lastTimestamp) {
            return Result.REJECT_STALE_TIMESTAMP;
        }
        boolean routeChange = !cleanRouteId.isEmpty()
                && !routeId.isEmpty()
                && !cleanRouteId.equals(routeId);
        if (routeChange) {
            if (retiredRouteIds.contains(cleanRouteId)) {
                return Result.REJECT_RETIRED_ROUTE;
            }
            if (!hasSequence && hasTimestamp && lastTimestamp != Long.MIN_VALUE
                    && timestamp <= lastTimestamp) {
                return Result.REJECT_STALE_TIMESTAMP;
            }
            if (!hasSequence && !hasTimestamp && trustedSeen) {
                return Result.REJECT_UNTRUSTED_AFTER_TRUSTED;
            }
            retiredRouteIds.add(routeId);
            routeId = cleanRouteId;
            lastSequence = Long.MIN_VALUE;
        }

        if (!hasSequence && !hasTimestamp) {
            return trustedSeen ? Result.REJECT_UNTRUSTED_AFTER_TRUSTED
                    : Result.ACCEPT_UNTRUSTED_INITIAL;
        }

        if (hasSequence && lastSequence != Long.MIN_VALUE) {
            if (sequence < lastSequence) {
                boolean producerRestart = hasTimestamp
                        && lastTimestamp != Long.MIN_VALUE
                        && timestamp > lastTimestamp;
                if (!producerRestart) return Result.REJECT_STALE_SEQUENCE;
                lastSequence = Long.MIN_VALUE;
            }
            if (sequence == lastSequence) {
                return Result.REJECT_STALE_SEQUENCE;
            }
        } else if (!hasSequence && hasTimestamp && lastTimestamp != Long.MIN_VALUE
                && timestamp <= lastTimestamp) {
            return Result.REJECT_STALE_TIMESTAMP;
        }

        trustedSeen = true;
        if (hasSequence) lastSequence = sequence;
        if (hasTimestamp) lastTimestamp = timestamp;
        if (!cleanRouteId.isEmpty()) routeId = cleanRouteId;
        return Result.ACCEPT;
    }

    void reset() {
        lastSequence = Long.MIN_VALUE;
        lastTimestamp = Long.MIN_VALUE;
        routeId = "";
        trustedSeen = false;
        retiredRouteIds.clear();
    }

    private static String clean(String value) {
        return value == null ? "" : value.trim();
    }
}
