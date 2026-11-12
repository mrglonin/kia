package kia.app.navigation.domain;

/** Repeats a known positive road sign while fresh source samples keep its lease alive. */
public final class RoadSpeedLimitRepeatPolicy {
    public static final long INTERVAL_MS = 15000L;
    public static final long FRESH_MS = 12000L;

    private int value;
    private long lastFreshAt;
    private long nextAt;

    /** A same-value sample renews freshness without postponing the scheduled repeat. */
    public void observeFresh(int kmh, long now) {
        if (kmh <= 0 || kmh > 255 || now < 0L
                || (value > 0 && now < lastFreshAt)) {
            cancel();
            return;
        }
        if (value != kmh) {
            value = kmh;
            nextAt = nextDeadline(now);
        }
        lastFreshAt = now;
    }

    /** Reading a due value does not consume it; the caller schedules the next attempt. */
    public int dueValue(long now) {
        return fresh(now) && now >= nextAt ? value : 0;
    }

    /** Zero means disarmed; an overdue active repeat returns one millisecond. */
    public long delayUntilNext(long now) {
        if (!fresh(now)) return 0L;
        return Math.max(1L, nextAt - now);
    }

    public int currentValue() {
        return value;
    }

    /** An attempt or successful retry moves the deadline, but never renews source freshness. */
    public void scheduleNext(int kmh, long now) {
        if (value <= 0 || kmh != value || !fresh(now)) return;
        nextAt = nextDeadline(now);
    }

    public void cancel() {
        value = 0;
        lastFreshAt = 0L;
        nextAt = 0L;
    }

    private boolean fresh(long now) {
        if (value <= 0) return false;
        if (now < lastFreshAt || now - lastFreshAt > FRESH_MS) {
            cancel();
            return false;
        }
        return true;
    }

    private static long nextDeadline(long now) {
        return now > Long.MAX_VALUE - INTERVAL_MS ? Long.MAX_VALUE : now + INTERVAL_MS;
    }
}
