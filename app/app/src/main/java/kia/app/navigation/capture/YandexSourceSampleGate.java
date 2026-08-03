package kia.app.navigation.capture;

import java.util.Locale;

/**
 * Separates a fresh MapKit location sample from the bridge transport heartbeat.
 *
 * <p>The bridge envelope sequence and timestamp describe when the snapshot was sent. They do not
 * prove that {@code Guide.getLocation()} advanced. This gate remembers the producer's location
 * timestamps and only authorizes speed/road-limit data when the underlying sample advanced or a
 * matching semantic callback proves that the individual field was updated.</p>
 */
final class YandexSourceSampleGate {
    private static final long CLOCK_FUTURE_TOLERANCE_MS = 60_000L;

    static final class Decision {
        final boolean currentSpeedFresh;
        final boolean roadLimitFresh;
        final String reason;

        Decision(boolean currentSpeedFresh, boolean roadLimitFresh, String reason) {
            this.currentSpeedFresh = currentSpeedFresh;
            this.roadLimitFresh = roadLimitFresh;
            this.reason = reason == null ? "" : reason;
        }

        boolean anyFreshData() {
            return currentSpeedFresh || roadLimitFresh;
        }
    }

    private enum Relation {
        MISSING,
        NEW,
        SAME,
        REGRESSION
    }

    private long lastRelativeTimestamp = -1L;
    private long lastAbsoluteTimestamp = -1L;

    Decision evaluate(String callback,
                      long relativeTimestamp,
                      long absoluteTimestamp,
                      long nowElapsed,
                      long nowWall,
                      long maxSampleAgeMs) {
        String event = clean(callback);
        boolean locationEvent = event.equals("location");
        boolean speedLimitEvent = event.equals("speed_limit");
        boolean exceededEvent = event.equals("speed_limit_exceeded")
                || event.equals("speed_limit_exceeded_update")
                || event.equals("speed_limit_exceeded_updated");
        Relation relation = relation(relativeTimestamp, absoluteTimestamp);
        boolean recent = sampleRecent(relativeTimestamp, absoluteTimestamp,
                nowElapsed, nowWall, maxSampleAgeMs);

        if (relation == Relation.NEW) {
            remember(relativeTimestamp, absoluteTimestamp);
        }

        // With the freshness-enabled producer even a location callback must advance its source
        // token. Missing tokens are accepted only as a compatibility fallback for the legacy
        // producer; a SAME/regressed/stale token must never renew cached road data.
        if (locationEvent) {
            if (relation == Relation.MISSING) {
                return new Decision(true, true, "legacy_location_callback");
            }
            if (relation == Relation.NEW && recent) {
                return new Decision(true, true, "fresh_location_callback");
            }
            String rejectedReason = relation == Relation.NEW
                    ? "stale_location_callback"
                    : relation == Relation.SAME
                    ? "repeated_location_callback"
                    : "regressed_location_callback";
            return new Decision(false, false, rejectedReason);
        }
        if (speedLimitEvent) {
            boolean currentFresh = relation == Relation.NEW && recent;
            return new Decision(currentFresh, true, "speed_limit_callback");
        }
        if (exceededEvent) {
            return new Decision(true, false, "speed_limit_exceeded_callback");
        }

        if (relation == Relation.NEW && recent) {
            return new Decision(true, true, "new_location_sample");
        }
        if (relation == Relation.MISSING) {
            return new Decision(false, false, "missing_location_sample");
        }
        if (relation == Relation.SAME) {
            return new Decision(false, false, "repeated_location_sample");
        }
        if (relation == Relation.REGRESSION) {
            return new Decision(false, false, "regressed_location_sample");
        }
        return new Decision(false, false, "stale_location_sample");
    }

    void reset() {
        lastRelativeTimestamp = -1L;
        lastAbsoluteTimestamp = -1L;
    }

    private Relation relation(long relativeTimestamp, long absoluteTimestamp) {
        if (relativeTimestamp > 0L) {
            if (lastRelativeTimestamp <= 0L) return Relation.NEW;
            if (relativeTimestamp > lastRelativeTimestamp) return Relation.NEW;
            if (relativeTimestamp < lastRelativeTimestamp) return Relation.REGRESSION;
            return Relation.SAME;
        }
        if (absoluteTimestamp > 0L) {
            if (lastAbsoluteTimestamp <= 0L) return Relation.NEW;
            if (absoluteTimestamp > lastAbsoluteTimestamp) return Relation.NEW;
            if (absoluteTimestamp < lastAbsoluteTimestamp) return Relation.REGRESSION;
            return Relation.SAME;
        }
        return Relation.MISSING;
    }

    private void remember(long relativeTimestamp, long absoluteTimestamp) {
        if (relativeTimestamp > 0L) lastRelativeTimestamp = relativeTimestamp;
        if (absoluteTimestamp > 0L) lastAbsoluteTimestamp = absoluteTimestamp;
    }

    private static boolean sampleRecent(long relativeTimestamp,
                                        long absoluteTimestamp,
                                        long nowElapsed,
                                        long nowWall,
                                        long maxSampleAgeMs) {
        long maxAge = Math.max(0L, maxSampleAgeMs);
        boolean checked = false;
        boolean recent = false;
        if (relativeTimestamp > 0L
                && relativeTimestamp <= nowElapsed + CLOCK_FUTURE_TOLERANCE_MS) {
            checked = true;
            long age = nowElapsed - relativeTimestamp;
            recent |= age >= -CLOCK_FUTURE_TOLERANCE_MS && age <= maxAge;
        }
        if (absoluteTimestamp > 0L
                && absoluteTimestamp <= nowWall + CLOCK_FUTURE_TOLERANCE_MS) {
            checked = true;
            long age = nowWall - absoluteTimestamp;
            recent |= age >= -CLOCK_FUTURE_TOLERANCE_MS && age <= maxAge;
        }
        return checked && recent;
    }

    private static String clean(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.US);
    }
}
