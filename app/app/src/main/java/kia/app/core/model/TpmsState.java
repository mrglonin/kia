package kia.app.core.model;

import java.util.Locale;

public final class TpmsState {
    public static final int WHEEL_FL = 0;
    public static final int WHEEL_FR = 1;
    public static final int WHEEL_RL = 2;
    public static final int WHEEL_RR = 3;
    public static final int WHEEL_COUNT = 4;

    public final boolean[] known;
    public final int[] pressureKpa;
    public final int[] temperatureC;
    public final int[] flags;
    public final String source;
    public final long updatedAt;

    public TpmsState(boolean[] known, int[] pressureKpa, int[] temperatureC, int[] flags,
                     String source, long updatedAt) {
        this.known = copyBool(known);
        this.pressureKpa = copyInt(pressureKpa);
        this.temperatureC = copyInt(temperatureC);
        this.flags = copyInt(flags);
        this.source = safe(source);
        this.updatedAt = updatedAt;
    }

    public static TpmsState empty() {
        return new TpmsState(new boolean[WHEEL_COUNT], new int[WHEEL_COUNT],
                new int[WHEEL_COUNT], new int[WHEEL_COUNT], "", 0L);
    }

    public TpmsState withWheel(int wheel, int pressure, int temp, int flag, String valueSource) {
        if (wheel < 0 || wheel >= WHEEL_COUNT) return this;
        boolean[] nextKnown = copyBool(known);
        int[] nextPressure = copyInt(pressureKpa);
        int[] nextTemp = copyInt(temperatureC);
        int[] nextFlags = copyInt(flags);
        nextKnown[wheel] = true;
        nextPressure[wheel] = clampPressure(pressure);
        nextTemp[wheel] = clampTemp(temp);
        nextFlags[wheel] = flag & 0xff;
        return new TpmsState(nextKnown, nextPressure, nextTemp, nextFlags,
                valueSource, System.currentTimeMillis());
    }

    public boolean hasData() {
        for (int i = 0; i < WHEEL_COUNT; i++) {
            if (known[i]) return true;
        }
        return false;
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
        return String.format(Locale.US, "%.2f bar / %dC", pressureKpa[wheel] / 100f, temperatureC[wheel]);
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
