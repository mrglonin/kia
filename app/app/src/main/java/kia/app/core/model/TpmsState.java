package kia.app.core.model;

import java.util.Locale;

public final class TpmsState {
    public static final int WHEEL_FL = 0;
    public static final int WHEEL_FR = 1;
    public static final int WHEEL_RL = 2;
    public static final int WHEEL_RR = 3;
    public static final int WHEEL_COUNT = 4;
    /**
     * A wheel is no longer treated as live after an adapter update has been
     * missing for this long. This is deliberately longer than the 120 second
     * background poll so one delayed response does not make all wheels stale.
     */
    public static final long WHEEL_STALE_AFTER_MS = 180_000L;

    public final boolean[] known;
    public final int[] pressureKpa;
    public final int[] temperatureC;
    public final int[] flags;
    public final long[] wheelUpdatedAt;
    public final boolean[] explicitlyStale;
    public final String source;
    public final long updatedAt;

    public TpmsState(boolean[] known, int[] pressureKpa, int[] temperatureC, int[] flags,
                     String source, long updatedAt) {
        this(known, pressureKpa, temperatureC, flags,
                timestampsForKnownWheels(known, updatedAt), new boolean[WHEEL_COUNT],
                source, updatedAt);
    }

    public TpmsState(boolean[] known, int[] pressureKpa, int[] temperatureC, int[] flags,
                     long[] wheelUpdatedAt, String source, long updatedAt) {
        this(known, pressureKpa, temperatureC, flags, wheelUpdatedAt,
                new boolean[WHEEL_COUNT], source, updatedAt);
    }

    public TpmsState(boolean[] known, int[] pressureKpa, int[] temperatureC, int[] flags,
                     long[] wheelUpdatedAt, boolean[] explicitlyStale,
                     String source, long updatedAt) {
        this.known = copyBool(known);
        this.pressureKpa = copyInt(pressureKpa);
        this.temperatureC = copyInt(temperatureC);
        this.flags = copyInt(flags);
        this.wheelUpdatedAt = copyLong(wheelUpdatedAt);
        this.explicitlyStale = copyBool(explicitlyStale);
        this.source = safe(source);
        this.updatedAt = updatedAt;
    }

    public static TpmsState empty() {
        return new TpmsState(new boolean[WHEEL_COUNT], new int[WHEEL_COUNT],
                new int[WHEEL_COUNT], new int[WHEEL_COUNT], "", 0L);
    }

    public TpmsState withWheel(int wheel, int pressure, int temp, int flag, String valueSource) {
        return withWheelAt(wheel, pressure, temp, flag, valueSource, System.currentTimeMillis());
    }

    public TpmsState withWheelAt(int wheel, int pressure, int temp, int flag,
                                 String valueSource, long observedAt) {
        if (wheel < 0 || wheel >= WHEEL_COUNT) return this;
        boolean[] nextKnown = copyBool(known);
        int[] nextPressure = copyInt(pressureKpa);
        int[] nextTemp = copyInt(temperatureC);
        int[] nextFlags = copyInt(flags);
        long[] nextWheelUpdatedAt = copyLong(wheelUpdatedAt);
        boolean[] nextExplicitlyStale = copyBool(explicitlyStale);
        long cleanObservedAt = Math.max(0L, observedAt);
        nextKnown[wheel] = true;
        nextPressure[wheel] = clampPressure(pressure);
        nextTemp[wheel] = clampTemp(temp);
        nextFlags[wheel] = flag & 0xff;
        nextWheelUpdatedAt[wheel] = cleanObservedAt;
        nextExplicitlyStale[wheel] = false;
        return new TpmsState(nextKnown, nextPressure, nextTemp, nextFlags,
                nextWheelUpdatedAt, nextExplicitlyStale,
                valueSource, Math.max(updatedAt, cleanObservedAt));
    }

    /**
     * Keeps the last value for diagnostics, but makes it visibly stale and
     * ineligible for warnings. Used when the adapter explicitly reports an
     * empty wheel slot.
     */
    public TpmsState withWheelStale(int wheel, String valueSource, long observedAt) {
        if (wheel < 0 || wheel >= WHEEL_COUNT || !known[wheel]) return this;
        if (explicitlyStale[wheel]) return this;
        boolean[] nextExplicitlyStale = copyBool(explicitlyStale);
        nextExplicitlyStale[wheel] = true;
        long cleanObservedAt = Math.max(0L, observedAt);
        return new TpmsState(known, pressureKpa, temperatureC, flags,
                wheelUpdatedAt, nextExplicitlyStale,
                valueSource, Math.max(updatedAt, cleanObservedAt));
    }

    public boolean hasData() {
        for (int i = 0; i < WHEEL_COUNT; i++) {
            if (known[i]) return true;
        }
        return false;
    }

    public boolean hasFreshData() {
        long now = System.currentTimeMillis();
        for (int wheel = 0; wheel < WHEEL_COUNT; wheel++) {
            if (isWheelFresh(wheel, now)) return true;
        }
        return false;
    }

    public boolean isWheelFresh(int wheel) {
        return isWheelFresh(wheel, System.currentTimeMillis());
    }

    public boolean isWheelFresh(int wheel, long now) {
        return isWheelFresh(wheel, now, WHEEL_STALE_AFTER_MS);
    }

    public boolean isWheelFresh(int wheel, long now, long staleAfterMs) {
        if (wheel < 0 || wheel >= WHEEL_COUNT || !known[wheel]) return false;
        if (explicitlyStale[wheel]) return false;
        long observedAt = wheelUpdatedAt[wheel];
        if (observedAt <= 0L || staleAfterMs < 0L) return false;
        long age = Math.max(0L, now - observedAt);
        return age <= staleAfterMs;
    }

    public boolean isWheelStale(int wheel, long now) {
        return wheel >= 0 && wheel < WHEEL_COUNT && known[wheel] && !isWheelFresh(wheel, now);
    }

    public long wheelAgeMs(int wheel, long now) {
        if (wheel < 0 || wheel >= WHEEL_COUNT || !known[wheel] || wheelUpdatedAt[wheel] <= 0L) {
            return Long.MAX_VALUE;
        }
        return Math.max(0L, now - wheelUpdatedAt[wheel]);
    }

    public String summary() {
        if (!hasData()) return "TPMS: нет данных";
        return "TPMS: FL " + wheelText(WHEEL_FL)
                + " | FR " + wheelText(WHEEL_FR)
                + " | RL " + wheelText(WHEEL_RL)
                + " | RR " + wheelText(WHEEL_RR);
    }

    private String wheelText(int wheel) {
        if (!known[wheel]) return "-";
        String value = String.format(Locale.US, "%.2f bar / %dC",
                pressureKpa[wheel] / 100f, temperatureC[wheel]);
        return isWheelFresh(wheel) ? value : value + " (устар.)";
    }

    private static boolean[] copyBool(boolean[] value) {
        boolean[] out = new boolean[WHEEL_COUNT];
        if (value != null) System.arraycopy(value, 0, out, 0, Math.min(value.length, WHEEL_COUNT));
        return out;
    }

    private static int[] copyInt(int[] value) {
        int[] out = new int[WHEEL_COUNT];
        if (value != null) System.arraycopy(value, 0, out, 0, Math.min(value.length, WHEEL_COUNT));
        return out;
    }

    private static long[] copyLong(long[] value) {
        long[] out = new long[WHEEL_COUNT];
        if (value != null) System.arraycopy(value, 0, out, 0, Math.min(value.length, WHEEL_COUNT));
        return out;
    }

    private static long[] timestampsForKnownWheels(boolean[] known, long updatedAt) {
        long[] out = new long[WHEEL_COUNT];
        if (known == null || updatedAt <= 0L) return out;
        for (int wheel = 0; wheel < Math.min(known.length, WHEEL_COUNT); wheel++) {
            if (known[wheel]) out[wheel] = updatedAt;
        }
        return out;
    }

    private static int clampPressure(int value) {
        return Math.max(0, Math.min(600, value));
    }

    private static int clampTemp(int value) {
        return Math.max(-60, Math.min(160, value));
    }

    private static String safe(String value) {
        return value == null ? "" : value.trim();
    }
}
