package kia.app.tpms;

import java.util.Arrays;

/** Stable alarm episodes: measurements can change without creating a new alarm. */
final class TpmsAlertLifecycle {
    static final long RECOVERY_DWELL_MS = 30_000L;
    static final long REPEAT_MS = 60_000L;

    private final int[] peakLevels;
    private final long[] normalSince;
    private boolean active;
    private long generation;
    private long dismissedGeneration = -1L;
    private long notifiedGeneration = -1L;
    private long notifiedAt = -1L;
    private long lastObservedAt = -1L;

    TpmsAlertLifecycle(int causeSlots) {
        peakLevels = new int[causeSlots];
        normalSince = new long[causeSlots];
        reset();
    }

    void observe(int[] levels, long nowElapsed) {
        long now = monotonic(nowElapsed);
        boolean nextActive = false;
        boolean escalated = false;
        for (int slot = 0; slot < peakLevels.length; slot++) {
            int level = levels != null && slot < levels.length
                    ? Math.max(0, Math.min(2, levels[slot])) : 0;
            if (normalSince[slot] >= 0L && now - normalSince[slot] >= RECOVERY_DWELL_MS) {
                peakLevels[slot] = 0;
                normalSince[slot] = -1L;
            }
            if (level > 0) {
                nextActive = true;
                normalSince[slot] = -1L;
                if (level > peakLevels[slot]) {
                    peakLevels[slot] = level;
                    escalated = true;
                }
            } else if (peakLevels[slot] > 0 && normalSince[slot] < 0L) {
                normalSince[slot] = now;
            }
        }
        if (escalated) generation++;
        active = nextActive;
    }

    boolean shouldShow() {
        return active && dismissedGeneration != generation;
    }

    void dismiss() {
        if (active) dismissedGeneration = generation;
    }

    boolean shouldNotify(long nowElapsed) {
        long now = monotonic(nowElapsed);
        if (!active) return false;
        if (notifiedGeneration == generation && notifiedAt >= 0L
                && now - notifiedAt < REPEAT_MS) return false;
        notifiedGeneration = generation;
        notifiedAt = now;
        return true;
    }

    void reset() {
        Arrays.fill(peakLevels, 0);
        Arrays.fill(normalSince, -1L);
        active = false;
        generation = 0L;
        dismissedGeneration = -1L;
        notifiedGeneration = -1L;
        notifiedAt = -1L;
        lastObservedAt = -1L;
    }

    private long monotonic(long value) {
        lastObservedAt = Math.max(lastObservedAt, Math.max(0L, value));
        return lastObservedAt;
    }
}
