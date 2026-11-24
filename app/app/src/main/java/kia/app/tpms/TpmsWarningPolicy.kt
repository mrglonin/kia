package kia.app.tpms

/** Evaluates every active cause before selecting the most severe warning. */
internal object TpmsWarningPolicy {
    const val CAUSE_COUNT: Int = TpmsAlertController.WARNING_LOW_TEMP + 1

    @JvmStatic
    fun evaluate(
        pressureKpa: Int,
        tempC: Int,
        flags: Int,
        lowPressure: Int,
        highPressure: Int,
        lowTemp: Int,
        highTemp: Int,
    ): TpmsWarningPolicy.Result {
        var lowPressure: Int = lowPressure
        var highPressure: Int = highPressure
        var lowTemp: Int = lowTemp
        var highTemp: Int = highTemp
        lowPressure = Math.min(lowPressure, highPressure - 1)
        highPressure = Math.max(highPressure, lowPressure + 1)
        lowTemp = Math.min(lowTemp, highTemp - 1)
        highTemp = Math.max(highTemp, lowTemp + 1)
        val result: TpmsWarningPolicy.Result = TpmsWarningPolicy.Result()
        if ((flags and 0x01) != 0) result.add(TpmsAlertController.WARNING_FAST_LEAKAGE, 2)
        if ((flags and 0x02) != 0) result.add(TpmsAlertController.WARNING_HIGH_PRESSURE, 2)
        if ((flags and 0x04) != 0) result.add(TpmsAlertController.WARNING_HIGH_TEMP, 2)
        if ((flags and 0x08) != 0) result.add(TpmsAlertController.WARNING_LOW_PRESSURE, 2)
        if ((flags and 0x10) != 0) result.add(TpmsAlertController.WARNING_LOW_BATTERY, 1)
        if (pressureKpa > 0 && pressureKpa < lowPressure) {
            result.add(
                TpmsAlertController.WARNING_LOW_PRESSURE,
                if (pressureKpa <= lowPressure - 30) 2 else 1,
            )
        }
        if (pressureKpa > highPressure) {
            result.add(
                TpmsAlertController.WARNING_HIGH_PRESSURE,
                if (pressureKpa >= highPressure + 30) 2 else 1,
            )
        }
        if (tempC < lowTemp) {
            result.add(TpmsAlertController.WARNING_LOW_TEMP, if (tempC <= lowTemp - 10) 2 else 1)
        }
        if (tempC >= highTemp) {
            result.add(TpmsAlertController.WARNING_HIGH_TEMP, if (tempC >= highTemp + 10) 2 else 1)
        }
        return result
    }

    internal class Result {
        @JvmField val levels: IntArray = IntArray(TpmsWarningPolicy.CAUSE_COUNT)

        internal fun add(cause: Int, severity: Int) {
            levels[cause] = Math.max(levels.get(cause), severity)
        }

        fun warning(): Int {
            var selected: Int = TpmsAlertController.WARNING_NONE
            for (cause in 1..<levels.size) {
                if (levels.get(cause) > levels.get(selected)) selected = cause
            }
            return selected
        }

        fun severity(): Int {
            return levels.get(warning())
        }
    }
}
