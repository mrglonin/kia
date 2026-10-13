package kia.app.tpms;

import java.util.Arrays;
import java.util.Locale;

import kia.app.core.model.TpmsState;

/** Pressure-only projection of USB observations; replay never refreshes their age. */
public final class TeyesTpmsWidgetPolicy {
    public static final long STALE_AFTER_MS = 180_000L;
    public static final String UNKNOWN = "__";
    private static final int PRESSURE_WARNING_FLAGS = 0x01 | 0x02 | 0x08;

    private final boolean[] available = new boolean[TpmsState.WHEEL_COUNT];
    private final int[] pressureKpa = new int[TpmsState.WHEEL_COUNT];
    private final int[] flags = new int[TpmsState.WHEEL_COUNT];
    private final long[] observedAtElapsed = new long[TpmsState.WHEEL_COUNT];

    public void reset() {
        Arrays.fill(available, false);
        Arrays.fill(pressureKpa, 0);
        Arrays.fill(flags, 0);
        Arrays.fill(observedAtElapsed, 0L);
    }

    /** Only mask-selected wheels were observed in this hardware frame. */
    public void observe(TpmsState state, int wheelMask, long observedElapsed) {
        if (state == null || observedElapsed < 0L) return;
        for (int wheel = 0; wheel < TpmsState.WHEEL_COUNT; wheel++) {
            if ((wheelMask & (1 << wheel)) == 0) continue;
            available[wheel] = state.known[wheel] && !state.explicitlyStale[wheel];
            pressureKpa[wheel] = state.pressureKpa[wheel];
            flags[wheel] = state.flags[wheel];
            observedAtElapsed[wheel] = observedElapsed;
        }
    }

    public boolean isFresh(int wheel, long nowElapsed) {
        if (wheel < 0 || wheel >= TpmsState.WHEEL_COUNT || !available[wheel]) return false;
        long age = nowElapsed - observedAtElapsed[wheel];
        return age >= 0L && age <= STALE_AFTER_MS;
    }

    public boolean hasFreshData(long nowElapsed) {
        for (int wheel = 0; wheel < TpmsState.WHEEL_COUNT; wheel++) {
            if (isFresh(wheel, nowElapsed)) return true;
        }
        return false;
    }

    /** Delay to the first still-fresh wheel expiring; -1 means no expiry remains. */
    public long nextExpiryDelayMs(long nowElapsed) {
        long delay = Long.MAX_VALUE;
        for (int wheel = 0; wheel < TpmsState.WHEEL_COUNT; wheel++) {
            if (!isFresh(wheel, nowElapsed)) continue;
            long age = nowElapsed - observedAtElapsed[wheel];
            delay = Math.min(delay, STALE_AFTER_MS - age + 1L);
        }
        return delay == Long.MAX_VALUE ? -1L : delay;
    }

    public WheelValue project(int wheel, long nowElapsed, boolean alertsEnabled,
                              int lowPressureKpa, int highPressureKpa) {
        if (wheel < 0 || wheel >= TpmsState.WHEEL_COUNT) {
            throw new IllegalArgumentException("wheel");
        }
        if (!isFresh(wheel, nowElapsed)) return WheelValue.unknown(wheel);
        int pressure = pressureKpa[wheel];
        int low = Math.min(lowPressureKpa, highPressureKpa - 1);
        int high = Math.max(highPressureKpa, low + 1);
        boolean pressureWarning = alertsEnabled
                && ((flags[wheel] & PRESSURE_WARNING_FLAGS) != 0
                || (pressure > 0 && pressure < low) || pressure > high);
        return new WheelValue((byte) (wheel + 1),
                String.format(Locale.US, "%.2f", pressure / 100d), pressureWarning);
    }

    public static final class WheelValue {
        public final byte which;
        public final String pressure;
        // TpmsState has no independent temperature-valid flag. Do not invent a value.
        public final String temperature = UNKNOWN;
        public final boolean alert;
        public final int pressureUnit = 1; // Bar
        public final int temperatureUnit = 1; // Celsius
        public final int warningState = 0; // Receiver renders alert, not this vendor enum.

        private WheelValue(byte which, String pressure, boolean alert) {
            this.which = which;
            this.pressure = pressure;
            this.alert = alert;
        }

        public static WheelValue unknown(int wheel) {
            return new WheelValue((byte) (wheel + 1), UNKNOWN, false);
        }
    }
}
