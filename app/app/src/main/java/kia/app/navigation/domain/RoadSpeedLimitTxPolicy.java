package kia.app.navigation.domain;

/** Positive road-sign events, with deduplication based on successful delivery. */
public final class RoadSpeedLimitTxPolicy {
    private int lastWritten;
    private boolean allowed;
    private boolean firstInScope = true;

    public void setAllowed(boolean nextAllowed) {
        if (allowed != nextAllowed) firstInScope = true;
        allowed = nextAllowed;
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
    }
}
