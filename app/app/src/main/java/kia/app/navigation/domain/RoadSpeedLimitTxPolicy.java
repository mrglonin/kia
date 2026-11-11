package kia.app.navigation.domain;

/** Positive road-sign events, with deduplication based on successful delivery. */
public final class RoadSpeedLimitTxPolicy {
    public static final long RETRY_WINDOW_MS = 3000L;
    private int lastWritten;
    private boolean allowed;
    private boolean firstInScope = true;
    private int pending;
    private long pendingSince;

    public void setAllowed(boolean nextAllowed) {
        if (allowed != nextAllowed) firstInScope = true;
        allowed = nextAllowed;
        if (!allowed) cancelPending();
    }

    public boolean shouldSend(int kmh, boolean outputAllowed) {
        setAllowed(outputAllowed);
        return allowed && kmh > 0 && kmh <= 255
                && (firstInScope || kmh != lastWritten);
    }

    public void written(int kmh) {
        if (kmh <= 0 || kmh > 255) return;
        lastWritten = kmh;
        firstInScope = false;
        cancelPending();
    }

    /** Only a fresh source sample may create or refresh this short delivery lease. */
    public boolean acceptFreshSample(int kmh, boolean outputAllowed, long nowElapsed) {
        cancelPending();
        if (!shouldSend(kmh, outputAllowed)) return false;
        pending = kmh;
        pendingSince = nowElapsed;
        return true;
    }

    /** A retry never refreshes the lease, even if the transport remains blocked. */
    public int pendingValue(long nowElapsed, boolean outputAllowed) {
        setAllowed(outputAllowed);
        if (pending <= 0 || nowElapsed < pendingSince
                || nowElapsed - pendingSince >= RETRY_WINDOW_MS) {
            cancelPending();
        }
        return pending;
    }

    public void cancelPending() {
        pending = 0;
        pendingSince = 0L;
    }
}
