package kia.app.tpms;

/** Evaluates every active cause before selecting the most severe warning. */
final class TpmsWarningPolicy {
    static final int CAUSE_COUNT = TpmsAlertController.WARNING_LOW_TEMP + 1;

    private TpmsWarningPolicy() {
    }

    static Result evaluate(int pressureKpa, int tempC, int flags,
                           int lowPressure, int highPressure, int lowTemp, int highTemp) {
        lowPressure = Math.min(lowPressure, highPressure - 1);
        highPressure = Math.max(highPressure, lowPressure + 1);
        lowTemp = Math.min(lowTemp, highTemp - 1);
        highTemp = Math.max(highTemp, lowTemp + 1);
        Result result = new Result();
        if ((flags & 0x01) != 0) result.add(TpmsAlertController.WARNING_FAST_LEAKAGE, 2);
        if ((flags & 0x02) != 0) result.add(TpmsAlertController.WARNING_HIGH_PRESSURE, 2);
        if ((flags & 0x04) != 0) result.add(TpmsAlertController.WARNING_HIGH_TEMP, 2);
        if ((flags & 0x08) != 0) result.add(TpmsAlertController.WARNING_LOW_PRESSURE, 2);
        if ((flags & 0x10) != 0) result.add(TpmsAlertController.WARNING_LOW_BATTERY, 1);
        if (pressureKpa > 0 && pressureKpa < lowPressure) {
            result.add(TpmsAlertController.WARNING_LOW_PRESSURE,
                    pressureKpa <= lowPressure - 30 ? 2 : 1);
        }
        if (pressureKpa > highPressure) {
            result.add(TpmsAlertController.WARNING_HIGH_PRESSURE,
                    pressureKpa >= highPressure + 30 ? 2 : 1);
        }
        if (tempC < lowTemp) {
            result.add(TpmsAlertController.WARNING_LOW_TEMP, tempC <= lowTemp - 10 ? 2 : 1);
        }
        if (tempC >= highTemp) {
            result.add(TpmsAlertController.WARNING_HIGH_TEMP, tempC >= highTemp + 10 ? 2 : 1);
        }
        return result;
    }

    static final class Result {
        final int[] levels = new int[CAUSE_COUNT];

        private void add(int cause, int severity) {
            levels[cause] = Math.max(levels[cause], severity);
        }

        int warning() {
            int selected = TpmsAlertController.WARNING_NONE;
            for (int cause = 1; cause < levels.length; cause++) {
                if (levels[cause] > levels[selected]) selected = cause;
            }
            return selected;
        }

        int severity() {
            return levels[warning()];
        }
    }
}
