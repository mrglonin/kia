package kia.app.rcta;

/**
 * Tracks whether the active RCTA state is still backed by a live stream of adapter frames.
 *
 * <p>The caller supplies a monotonic time source (normally
 * {@code SystemClock.elapsedRealtime()}). Repeated frames are observations even when their
 * left/right payload is unchanged, so they intentionally refresh the deadline.
 */
final class RctaFreshnessPolicy {
    private static final long NO_ACTIVE_FRAME = -1L;

    private final long timeoutMs;
    private long lastActiveFrameAtMs = NO_ACTIVE_FRAME;

    RctaFreshnessPolicy(long timeoutMs) {
        if (timeoutMs <= 0L) throw new IllegalArgumentException("timeoutMs must be positive");
        this.timeoutMs = timeoutMs;
    }

    void observeFrame(boolean active, long nowMs) {
        lastActiveFrameAtMs = active ? Math.max(0L, nowMs) : NO_ACTIVE_FRAME;
    }

    long remainingMs(long nowMs) {
        if (lastActiveFrameAtMs == NO_ACTIVE_FRAME) return 0L;
        long safeNow = Math.max(0L, nowMs);
        if (safeNow < lastActiveFrameAtMs) return timeoutMs;
        long ageMs = safeNow - lastActiveFrameAtMs;
        return ageMs >= timeoutMs ? 0L : timeoutMs - ageMs;
    }

    boolean hasActiveObservation() {
        return lastActiveFrameAtMs != NO_ACTIVE_FRAME;
    }

    void clear() {
        lastActiveFrameAtMs = NO_ACTIVE_FRAME;
    }
}
