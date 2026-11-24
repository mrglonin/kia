package kia.app.navigation.domain;

/** A road limit received in this process, timestamped independently of other navigation data. */
public final class DashboardRoadSpeedLimit {
    public static final DashboardRoadSpeedLimit NONE = new DashboardRoadSpeedLimit(0, 0, 0L, 0L);

    final int source;
    private final int kmh;
    private final long observedAt;
    public final long changedAt;

    private DashboardRoadSpeedLimit(int source, int kmh, long observedAt, long changedAt) {
        this.source = source;
        this.kmh = kmh;
        this.observedAt = observedAt;
        this.changedAt = changedAt;
    }

    DashboardRoadSpeedLimit observe(int source, int kmh, long now) {
        if (kmh <= 0 || kmh > 255 || now < 0) return NONE;
        long change = this.source == source && valueAt(now) == kmh ? changedAt : now;
        return new DashboardRoadSpeedLimit(source, kmh, now, change);
    }

    DashboardRoadSpeedLimit observe(int source, String rawKmh, long now) {
        // The supported integrations supply km/h as a number. Do not extract a number
        // from an unrelated string or turn a malformed negative limit into a sign.
        if (rawKmh == null) return NONE;
        try {
            return observe(source, Integer.parseInt(rawKmh.trim()), now);
        } catch (NumberFormatException e) {
            return NONE;
        }
    }

    public long remainingMs(long now) {
        if (kmh == 0 || now < observedAt) return 0L;
        return Math.max(0L, RoadSpeedLimitRepeatPolicy.FRESH_MS - (now - observedAt));
    }

    public int valueAt(long now) {
        return remainingMs(now) > 0 ? kmh : 0;
    }
}
