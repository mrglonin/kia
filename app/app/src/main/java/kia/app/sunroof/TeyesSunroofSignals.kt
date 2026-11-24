package kia.app.sunroof

/** Decodes only the ACC contract verified in the retained TEYES framework. */
object TeyesSunroofSignals {
    const val UNKNOWN: Int = -1
    const val OFF: Int = 0
    const val ON: Int = 1
    const val ACC_COMMAND: Int = 5
    const val COMMON_TRANSACTION: Int = 13
    const val KEY_TRANSACTION: Int = 14

    @JvmStatic
    fun aggregateAcc(value: Any?): Int {
        // A missing value, Boolean, float, or unexpected vendor enum is not ACC on/off.
        if (value !is Int) return TeyesSunroofSignals.UNKNOWN
        val state: Int = value
        return if (state == TeyesSunroofSignals.OFF || state == TeyesSunroofSignals.ON) state
        else TeyesSunroofSignals.UNKNOWN
    }

    @JvmStatic
    fun callbackAcc(command: Int, arg1: Int, arg2: Int): Int {
        // Command 8 describes sleep. Power callbacks and screen/USB state are unrelated.
        if (command != TeyesSunroofSignals.ACC_COMMAND || arg2 != 0)
            return TeyesSunroofSignals.UNKNOWN
        return if (arg1 == 1) TeyesSunroofSignals.OFF
        else if (arg1 == 0) TeyesSunroofSignals.ON else TeyesSunroofSignals.UNKNOWN
    }

    @JvmStatic
    /** 0 = awake, 1 = asleep; this result must never be interpreted as ACC. */
    fun callbackSleep(command: Int, arg1: Int, arg2: Int): Int {
        if (command != 8 || arg2 != 0) return TeyesSunroofSignals.UNKNOWN
        return if (arg1 == 0) 0 else if (arg1 == 6 || arg1 == 7) 1 else TeyesSunroofSignals.UNKNOWN
    }

    @JvmStatic
    fun hardwareAction(keyCode: Int, repeatCount: Int, action: Int): Boolean {
        return keyCode > 0 && repeatCount >= 0 && (action == 0 || action == 1)
    }
}
