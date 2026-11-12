package kia.app.navigation.domain;

/** Positive road-sign transitions, with duplicate samples suppressed after successful delivery. */
public final class RoadSpeedLimitTxPolicy {
    public static final long RETRY_WINDOW_MS = 3000L;
    private int lastObserved;
    private int lastWritten;
    private boolean writtenForObservedSign;
    private boolean allowed;
    private boolean firstInScope = true;
    private int pending;
    private long pendingSince;

    public void setAllowed(boolean nextAllowed) {
        if (allowed != nextAllowed) firstInScope = true;
        allowed = nextAllowed;
        if (!allowed) cancelPending();
    }

    /** A changed positive value starts a new sign event, even when an earlier write failed. */
    public boolean shouldSend(int kmh, boolean outputAllowed) {
        setAllowed(outputAllowed);
        if (kmh <= 0 || kmh > 255) return false;
        if (kmh != lastObserved) {
            lastObserved = kmh;
            writtenForObservedSign = false;
        }
        return allowed && (firstInScope || !writtenForObservedSign);
    }

    public void written(int kmh) {
        if (kmh <= 0 || kmh > 255) return;
        lastWritten = kmh;
        if (lastObserved == 0) lastObserved = kmh;
        if (kmh == lastObserved) writtenForObservedSign = true;
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

    /** A scheduled repeat reopens delivery only for the currently observed positive sign. */
    public boolean beginRepeat(int kmh, boolean outputAllowed, long nowElapsed) {
        setAllowed(outputAllowed);
        if (!allowed || kmh <= 0 || kmh > 255 || kmh != lastObserved) return false;
        writtenForObservedSign = false;
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

    public int lastObservedValue() {
        return lastObserved;
    }

    public int lastWrittenValue() {
        return lastWritten;
    }
}
