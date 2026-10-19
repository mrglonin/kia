package kia.app.core.model;

import android.os.SystemClock;

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

    interface Clock {
        long wallMillis();
        long elapsedMillis();
    }

    private static final Clock SYSTEM_CLOCK = new Clock() {
        public long wallMillis() { return System.currentTimeMillis(); }
        public long elapsedMillis() {
            try {
                // Includes head-unit sleep and is unaffected by GPS/network wall-clock corrections.
                return SystemClock.elapsedRealtime();
            } catch (RuntimeException error) {
                // Plain JVM parsing tests use Android's throwing SDK stub, not a device clock.
                if (error.getMessage() == null || !error.getMessage().contains("not mocked")) {
                    throw error;
                }
                return System.nanoTime() / 1_000_000L;
            }
        }
    };
    private static volatile Clock clock = SYSTEM_CLOCK;

    static void setClockForTests(Clock value) { clock = value == null ? SYSTEM_CLOCK : value; }

    // Wall timestamps remain available for diagnostics; live freshness uses these per-wheel anchors.
    private final long[] wheelAgeAtCaptureMs;
    private final long[] wheelCapturedAtElapsedMs;

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
        this(known, pressureKpa, temperatureC, flags, wheelUpdatedAt, explicitlyStale,
                source, updatedAt, null, null);
    }

    private TpmsState(boolean[] known, int[] pressureKpa, int[] temperatureC, int[] flags,
                      long[] wheelUpdatedAt, boolean[] explicitlyStale, String source,
                      long updatedAt, long[] capturedAges, long[] capturedElapsed) {
        this.known = copyBool(known);
        this.pressureKpa = copyInt(pressureKpa);
        this.temperatureC = copyInt(temperatureC);
        this.flags = copyInt(flags);
        this.wheelUpdatedAt = copyLong(wheelUpdatedAt);
        this.explicitlyStale = copyBool(explicitlyStale);
        this.source = safe(source);
        this.updatedAt = updatedAt;
        this.wheelAgeAtCaptureMs = copyLong(capturedAges);
        this.wheelCapturedAtElapsedMs = copyLong(capturedElapsed);
        if (capturedAges == null || capturedElapsed == null) {
            long wallNow = clock.wallMillis();
            long elapsedNow = clock.elapsedMillis();
            for (int wheel = 0; wheel < WHEEL_COUNT; wheel++) {
                this.wheelAgeAtCaptureMs[wheel] = initialAge(
                        this.known[wheel], this.wheelUpdatedAt[wheel], wallNow);
                this.wheelCapturedAtElapsedMs[wheel] = elapsedNow;
            }
        }
    }

    public static TpmsState empty() {
        return new TpmsState(new boolean[WHEEL_COUNT], new int[WHEEL_COUNT],
                new int[WHEEL_COUNT], new int[WHEEL_COUNT], "", 0L);
    }

    public TpmsState withWheel(int wheel, int pressure, int temp, int flag, String valueSource) {
        return withWheelAt(wheel, pressure, temp, flag, valueSource, clock.wallMillis());
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
        long[] nextAges = copyLong(wheelAgeAtCaptureMs);
        long[] nextElapsed = copyLong(wheelCapturedAtElapsedMs);
        long cleanObservedAt = Math.max(0L, observedAt);
        nextKnown[wheel] = true;
        nextPressure[wheel] = clampPressure(pressure);
        nextTemp[wheel] = clampTemp(temp);
        nextFlags[wheel] = flag & 0xff;
        nextWheelUpdatedAt[wheel] = cleanObservedAt;
        nextExplicitlyStale[wheel] = false;
        nextAges[wheel] = initialAge(true, cleanObservedAt, clock.wallMillis());
        nextElapsed[wheel] = clock.elapsedMillis();
        return new TpmsState(nextKnown, nextPressure, nextTemp, nextFlags,
                nextWheelUpdatedAt, nextExplicitlyStale,
                valueSource, Math.max(updatedAt, cleanObservedAt), nextAges, nextElapsed);
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
                valueSource, Math.max(updatedAt, cleanObservedAt),
                wheelAgeAtCaptureMs, wheelCapturedAtElapsedMs);
    }

    public boolean hasData() {
        for (int i = 0; i < WHEEL_COUNT; i++) {
            if (known[i]) return true;
        }
        return false;
    }

    public boolean hasFreshData() {
        long elapsedNow = clock.elapsedMillis();
        for (int wheel = 0; wheel < WHEEL_COUNT; wheel++) {
            if (isWheelFreshAtElapsed(wheel, elapsedNow)) return true;
        }
        return false;
    }

    public boolean isWheelFresh(int wheel) {
        return isWheelFreshAtElapsed(wheel, clock.elapsedMillis());
    }

    public long wheelAgeMs(int wheel) {
        return wheelAgeAtElapsed(wheel, clock.elapsedMillis());
    }

    private boolean isWheelFreshAtElapsed(int wheel, long elapsedNow) {
        return wheel >= 0 && wheel < WHEEL_COUNT && known[wheel] && !explicitlyStale[wheel]
                && wheelAgeAtElapsed(wheel, elapsedNow) <= WHEEL_STALE_AFTER_MS;
    }

    private long wheelAgeAtElapsed(int wheel, long elapsedNow) {
        if (wheel < 0 || wheel >= WHEEL_COUNT || !known[wheel]) return Long.MAX_VALUE;
        long initial = wheelAgeAtCaptureMs[wheel];
        long captured = wheelCapturedAtElapsedMs[wheel];
        if (initial == Long.MAX_VALUE || elapsedNow < captured) return Long.MAX_VALUE;
        long delta = elapsedNow - captured;
        return initial > Long.MAX_VALUE - delta ? Long.MAX_VALUE : initial + delta;
    }

    private static long initialAge(boolean known, long observedAt, long wallNow) {
        if (!known || observedAt <= 0L || observedAt > wallNow) return Long.MAX_VALUE;
        long age = wallNow - observedAt;
        return age < 0L ? Long.MAX_VALUE : age;
    }

    /** Explicit wall-clock overload retained for diagnostic calculations and historical fixtures. */
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

    /** Explicit wall-clock age; runtime displays should use {@link #wheelAgeMs(int)}. */
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
