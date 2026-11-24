package kia.app.tpms

import java.util.Arrays
import java.util.Locale
import kia.app.core.model.TpmsState

/** Pressure-only projection of USB observations; replay never refreshes their age. */
class TeyesTpmsWidgetPolicy {
    private val available: BooleanArray = BooleanArray(TpmsState.WHEEL_COUNT)
    private val pressureKpa: IntArray = IntArray(TpmsState.WHEEL_COUNT)
    private val flags: IntArray = IntArray(TpmsState.WHEEL_COUNT)
    private val observedAtElapsed: LongArray = LongArray(TpmsState.WHEEL_COUNT)

    fun reset() {
        Arrays.fill(available, false)
        Arrays.fill(pressureKpa, 0)
        Arrays.fill(flags, 0)
        Arrays.fill(observedAtElapsed, 0L)
    }

    /** Only mask-selected wheels were observed in this hardware frame. */
    fun observe(state: TpmsState?, wheelMask: Int, observedElapsed: Long) {
        if (state == null || observedElapsed < 0L) return
        for (wheel in 0..<TpmsState.WHEEL_COUNT) {
            if ((wheelMask and (1 shl wheel)) == 0) continue
            available[wheel] = state.known.get(wheel) && !state.explicitlyStale.get(wheel)
            pressureKpa[wheel] = state.pressureKpa.get(wheel)
            flags[wheel] = state.flags.get(wheel)
            observedAtElapsed[wheel] = observedElapsed
        }
    }

    fun isFresh(wheel: Int, nowElapsed: Long): Boolean {
        if (wheel < 0 || wheel >= TpmsState.WHEEL_COUNT || !available.get(wheel)) return false
        val age: Long = nowElapsed - observedAtElapsed.get(wheel)
        return age >= 0L && age <= TeyesTpmsWidgetPolicy.STALE_AFTER_MS
    }

    fun hasFreshData(nowElapsed: Long): Boolean {
        for (wheel in 0..<TpmsState.WHEEL_COUNT) {
            if (isFresh(wheel, nowElapsed)) return true
        }
        return false
    }

    /** Delay to the first still-fresh wheel expiring; -1 means no expiry remains. */
    fun nextExpiryDelayMs(nowElapsed: Long): Long {
        var delay: Long = Long.MAX_VALUE
        for (wheel in 0..<TpmsState.WHEEL_COUNT) {
            if (!isFresh(wheel, nowElapsed)) continue
            val age: Long = nowElapsed - observedAtElapsed.get(wheel)
            delay = Math.min(delay, TeyesTpmsWidgetPolicy.STALE_AFTER_MS - age + 1L)
        }
        return if (delay == Long.MAX_VALUE) -1L else delay
    }

    fun project(
        wheel: Int,
        nowElapsed: Long,
        alertsEnabled: Boolean,
        lowPressureKpa: Int,
        highPressureKpa: Int,
    ): WheelValue {
        require(!(wheel < 0 || wheel >= TpmsState.WHEEL_COUNT)) { "wheel" }
        if (!isFresh(wheel, nowElapsed)) return WheelValue.unknown(wheel)
        val pressure: Int = pressureKpa.get(wheel)
        val low: Int = Math.min(lowPressureKpa, highPressureKpa - 1)
        val high: Int = Math.max(highPressureKpa, low + 1)
        val pressureWarning: Boolean =
            alertsEnabled &&
                ((flags.get(wheel) and TeyesTpmsWidgetPolicy.PRESSURE_WARNING_FLAGS) != 0 ||
                    (pressure > 0 && pressure < low) ||
                    pressure > high)
        return WheelValue(
            (wheel + 1).toByte(),
            String.format(Locale.US, "%.2f", pressure / 100.0),
            pressureWarning,
        )
    }

    class WheelValue internal constructor(which: Byte, pressure: String?, alert: Boolean) {
        @JvmField val which: Byte
        @JvmField val pressure: String?
        @JvmField // TpmsState has no independent temperature-valid flag. Do not invent a value.
        val temperature: String = TeyesTpmsWidgetPolicy.UNKNOWN
        @JvmField val alert: Boolean
        @JvmField val pressureUnit: Int = 1 // Bar
        @JvmField val temperatureUnit: Int = 1 // Celsius
        @JvmField val warningState: Int = 0 // Receiver renders alert, not this vendor enum.

        init {
            this.which = which
            this.pressure = pressure
            this.alert = alert
        }

        companion object {
            @JvmStatic
            fun unknown(wheel: Int): WheelValue {
                return WheelValue((wheel + 1).toByte(), TeyesTpmsWidgetPolicy.UNKNOWN, false)
            }
        }
    }

    companion object {
        const val STALE_AFTER_MS: Long = 180000L
        const val UNKNOWN: String = "__"
        private val PRESSURE_WARNING_FLAGS: Int = 0x01 or 0x02 or 0x08
    }
}
