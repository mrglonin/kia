package kia.app.core.model;

public final class VehicleState {
    public final boolean engineTempKnown;
    public final int engineTempC;
    public final String engineTempSource;
    public final boolean outsideTempKnown;
    public final int outsideTempC;
    public final String outsideTempSource;
    public final boolean cabinLeftKnown;
    public final int cabinLeftTempC;
    public final boolean cabinRightKnown;
    public final int cabinRightTempC;
    public final String cabinTempSource;
    public final boolean sunroofKnown;
    public final boolean sunroofOpen;
    public final String sunroofSource;
    public final long updatedAt;

    public VehicleState(boolean engineTempKnown, int engineTempC, String engineTempSource,
                        boolean outsideTempKnown, int outsideTempC, String outsideTempSource,
                        boolean cabinLeftKnown, int cabinLeftTempC,
                        boolean cabinRightKnown, int cabinRightTempC,
                        String cabinTempSource, long updatedAt) {
        this(engineTempKnown, engineTempC, engineTempSource,
                outsideTempKnown, outsideTempC, outsideTempSource,
                cabinLeftKnown, cabinLeftTempC, cabinRightKnown, cabinRightTempC,
                cabinTempSource, false, false, "", longTime(updatedAt));
    }

    private VehicleState(boolean engineTempKnown, int engineTempC, String engineTempSource,
                         boolean outsideTempKnown, int outsideTempC, String outsideTempSource,
                         boolean cabinLeftKnown, int cabinLeftTempC,
                         boolean cabinRightKnown, int cabinRightTempC,
                         String cabinTempSource,
                         boolean sunroofKnown, boolean sunroofOpen, String sunroofSource,
                         long updatedAt) {
        this.engineTempKnown = engineTempKnown;
        this.engineTempC = engineTempC;
        this.engineTempSource = safe(engineTempSource);
        this.outsideTempKnown = outsideTempKnown;
        this.outsideTempC = outsideTempC;
        this.outsideTempSource = safe(outsideTempSource);
        this.cabinLeftKnown = cabinLeftKnown;
        this.cabinLeftTempC = cabinLeftTempC;
        this.cabinRightKnown = cabinRightKnown;
        this.cabinRightTempC = cabinRightTempC;
        this.cabinTempSource = safe(cabinTempSource);
        this.sunroofKnown = sunroofKnown;
        this.sunroofOpen = sunroofOpen;
        this.sunroofSource = safe(sunroofSource);
        this.updatedAt = updatedAt;
    }

    public static VehicleState empty() {
        return new VehicleState(false, 0, "", false, 0, "",
                false, 0, false, 0, "", 0L);
    }

    public VehicleState withEngineTemp(int value, String source) {
        return new VehicleState(true, clampTemp(value), source,
                outsideTempKnown, outsideTempC, outsideTempSource,
                cabinLeftKnown, cabinLeftTempC, cabinRightKnown, cabinRightTempC,
                cabinTempSource, sunroofKnown, sunroofOpen, sunroofSource,
                System.currentTimeMillis());
    }

    public VehicleState withOutsideTemp(int value, String source) {
        return new VehicleState(engineTempKnown, engineTempC, engineTempSource,
                true, clampTemp(value), source,
                cabinLeftKnown, cabinLeftTempC, cabinRightKnown, cabinRightTempC,
                cabinTempSource, sunroofKnown, sunroofOpen, sunroofSource,
                System.currentTimeMillis());
    }

    public VehicleState withCabinTemp(boolean leftKnown, int leftValue,
                                      boolean rightKnown, int rightValue,
                                      String source) {
        return new VehicleState(engineTempKnown, engineTempC, engineTempSource,
                outsideTempKnown, outsideTempC, outsideTempSource,
                leftKnown, clampTemp(leftValue), rightKnown, clampTemp(rightValue),
                source, sunroofKnown, sunroofOpen, sunroofSource,
                System.currentTimeMillis());
    }

    public VehicleState withSunroof(boolean open, String source) {
        return new VehicleState(engineTempKnown, engineTempC, engineTempSource,
                outsideTempKnown, outsideTempC, outsideTempSource,
                cabinLeftKnown, cabinLeftTempC, cabinRightKnown, cabinRightTempC,
                cabinTempSource, true, open, source, System.currentTimeMillis());
    }

    public String summary() {
        return "Температура: улица " + temp(outsideTempKnown, outsideTempC, outsideTempSource)
                + " | салон " + cabinText()
                + " | двигатель " + temp(engineTempKnown, engineTempC, engineTempSource)
                + (sunroofKnown ? " | люк " + (sunroofOpen ? "открыт" : "закрыт") : "");
    }

    private String cabinText() {
        if (!cabinLeftKnown && !cabinRightKnown) return "-";
        String left = cabinLeftKnown ? cabinLeftTempC + "C" : "-";
        String right = cabinRightKnown ? cabinRightTempC + "C" : "-";
        return "L " + left + " / R " + right + source(cabinTempSource);
    }

    private static String temp(boolean known, int value, String source) {
        return known ? value + "C" + source(source) : "-";
    }

    private static String source(String value) {
        String text = safe(value);
        return text.isEmpty() ? "" : " (" + text + ")";
    }

    private static int clampTemp(int value) {
        return Math.max(-60, Math.min(160, value));
    }

    private static String safe(String value) {
        return value == null ? "" : value.trim();
    }

    private static long longTime(long value) {
        return Math.max(0L, value);
    }
}
