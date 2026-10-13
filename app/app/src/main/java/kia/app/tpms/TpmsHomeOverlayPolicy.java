package kia.app.tpms;

import java.util.Locale;

import kia.app.core.model.TpmsState;

/** Display-only snapshot. USB provenance and per-wheel expiry belong to the projector. */
public final class TpmsHomeOverlayPolicy {
    // The owner refreshes the foreground decision every second. Never leave a
    // Home-only window visible indefinitely if that owner stops reporting.
    public static final long SNAPSHOT_TIMEOUT_MS = 2_500L;
    public static final String UNKNOWN = "—";

    private TpmsHomeOverlayPolicy() {
    }

    public static Snapshot capture(TeyesTpmsWidgetPolicy.WheelValue[] values,
                                   boolean connected, boolean homeVisible, long elapsed) {
        Wheel[] wheels = new Wheel[TpmsState.WHEEL_COUNT];
        for (int i = 0; i < wheels.length; i++) wheels[i] = Wheel.unknown();
        if (connected && values != null) {
            for (TeyesTpmsWidgetPolicy.WheelValue value : values) {
                if (value == null) continue;
                int wheel = value.which - 1;
                if (wheel < 0 || wheel >= wheels.length) continue;
                String pressure = number(value.pressure, true);
                String temperature = number(value.temperature, false);
                wheels[wheel] = new Wheel(pressure, temperature,
                        value.pressureUnit == 1 ? "бар" : "psi",
                        value.temperatureUnit == 1 ? "°C" : "°F",
                        value.alert && !UNKNOWN.equals(pressure));
            }
        }
        return new Snapshot(wheels, connected, homeVisible, elapsed);
    }

    private static String number(String value, boolean pressure) {
        if (value == null || value.trim().isEmpty()
                || TeyesTpmsWidgetPolicy.UNKNOWN.equals(value.trim())) return UNKNOWN;
        try {
            double parsed = Double.parseDouble(value.trim());
            if (!Double.isFinite(parsed) || (pressure && parsed < 0d)) return UNKNOWN;
            return pressure ? String.format(Locale.US, "%.2f", parsed)
                    : String.format(Locale.US, "%.0f", parsed);
        } catch (NumberFormatException ignored) {
            return UNKNOWN;
        }
    }

    /** Fractions survive display size/density changes; the caller supplies safe bounds. */
    public static int position(float fraction, int minimum, int maximum) {
        int end = Math.max(minimum, maximum);
        float safe = Float.isFinite(fraction) ? Math.max(0f, Math.min(1f, fraction)) : 0f;
        return minimum + Math.round(safe * (end - minimum));
    }

    public static float fraction(int position, int minimum, int maximum) {
        if (maximum <= minimum) return 0f;
        return Math.max(0f, Math.min(1f, (position - minimum) / (float) (maximum - minimum)));
    }

    public static final class Snapshot {
        public final Wheel[] wheels;
        public final boolean connected;
        public final boolean homeVisible;
        public final long capturedAtElapsed;

        private Snapshot(Wheel[] wheels, boolean connected, boolean homeVisible, long elapsed) {
            this.wheels = wheels;
            this.connected = connected;
            this.homeVisible = homeVisible;
            this.capturedAtElapsed = elapsed;
        }

        public boolean visible(long nowElapsed, boolean overlayAllowed) {
            long age = nowElapsed - capturedAtElapsed;
            return overlayAllowed && homeVisible && capturedAtElapsed >= 0L
                    && age >= 0L && age < SNAPSHOT_TIMEOUT_MS;
        }

        public boolean hasValues() {
            for (Wheel wheel : wheels) {
                if (!UNKNOWN.equals(wheel.pressure) || !UNKNOWN.equals(wheel.temperature)) return true;
            }
            return false;
        }
    }

    public static final class Wheel {
        public final String pressure;
        public final String temperature;
        public final String pressureUnit;
        public final String temperatureUnit;
        public final boolean alert;

        private Wheel(String pressure, String temperature, String pressureUnit,
                      String temperatureUnit, boolean alert) {
            this.pressure = pressure;
            this.temperature = temperature;
            this.pressureUnit = pressureUnit;
            this.temperatureUnit = temperatureUnit;
            this.alert = alert;
        }

        private static Wheel unknown() {
            return new Wheel(UNKNOWN, UNKNOWN, "бар", "°C", false);
        }
    }
}
