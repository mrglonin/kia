package kia.app.sunroof;

/** Decodes only the ACC contract verified in the retained TEYES framework. */
public final class TeyesSunroofSignals {
    public static final int UNKNOWN = -1;
    public static final int OFF = 0;
    public static final int ON = 1;
    static final int ACC_COMMAND = 5;
    static final int COMMON_TRANSACTION = 13;
    static final int KEY_TRANSACTION = 14;

    private TeyesSunroofSignals() {
    }

    public static int aggregateAcc(Object value) {
        // A missing value, Boolean, float, or unexpected vendor enum is not ACC on/off.
        if (!(value instanceof Integer)) return UNKNOWN;
        int state = (Integer) value;
        return state == OFF || state == ON ? state : UNKNOWN;
    }

    public static int callbackAcc(int command, int arg1, int arg2) {
        // Command 8 describes sleep. Power callbacks and screen/USB state are unrelated.
        if (command != ACC_COMMAND || arg2 != 0) return UNKNOWN;
        return arg1 == 1 ? OFF : arg1 == 0 ? ON : UNKNOWN;
    }

    /** 0 = awake, 1 = asleep; this result must never be interpreted as ACC. */
    public static int callbackSleep(int command, int arg1, int arg2) {
        if (command != 8 || arg2 != 0) return UNKNOWN;
        return arg1 == 0 ? 0 : arg1 == 6 || arg1 == 7 ? 1 : UNKNOWN;
    }

    static boolean hardwareAction(int keyCode, int repeatCount, int action) {
        return keyCode > 0 && repeatCount >= 0 && (action == 0 || action == 1);
    }
}
