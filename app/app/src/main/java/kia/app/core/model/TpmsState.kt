package kia.app.core.model

import android.os.SystemClock
import java.util.Locale

class TpmsState
private constructor(
    known: BooleanArray?,
    pressureKpa: IntArray?,
    temperatureC: IntArray?,
    flags: IntArray?,
    wheelUpdatedAt: LongArray?,
    explicitlyStale: BooleanArray?,
    source: String?,
    updatedAt: Long,
    capturedAges: LongArray?,
    capturedElapsed: LongArray?,
) {
    interface Clock {
        fun wallMillis(): Long

        fun elapsedMillis(): Long
    }

    // Wall timestamps remain available for diagnostics; live freshness uses these per-wheel
    // anchors.
    private val wheelAgeAtCaptureMs: LongArray
    private val wheelCapturedAtElapsedMs: LongArray

    @JvmField val known: BooleanArray
    @JvmField val pressureKpa: IntArray
    @JvmField val temperatureC: IntArray
    @JvmField val flags: IntArray
    @JvmField val wheelUpdatedAt: LongArray
    @JvmField val explicitlyStale: BooleanArray
    @JvmField val source: String
    @JvmField val updatedAt: Long

    constructor(
        known: BooleanArray?,
        pressureKpa: IntArray?,
        temperatureC: IntArray?,
        flags: IntArray?,
        source: String?,
        updatedAt: Long,
    ) : this(
        known,
        pressureKpa,
        temperatureC,
        flags,
        TpmsState.timestampsForKnownWheels(known, updatedAt),
        BooleanArray(TpmsState.WHEEL_COUNT),
        source,
        updatedAt,
    )

    constructor(
        known: BooleanArray?,
        pressureKpa: IntArray?,
        temperatureC: IntArray?,
        flags: IntArray?,
        wheelUpdatedAt: LongArray?,
        source: String?,
        updatedAt: Long,
    ) : this(
        known,
        pressureKpa,
        temperatureC,
        flags,
        wheelUpdatedAt,
        BooleanArray(TpmsState.WHEEL_COUNT),
        source,
        updatedAt,
    )

    constructor(
        known: BooleanArray?,
        pressureKpa: IntArray?,
        temperatureC: IntArray?,
        flags: IntArray?,
        wheelUpdatedAt: LongArray?,
        explicitlyStale: BooleanArray?,
        source: String?,
        updatedAt: Long,
    ) : this(
        known,
        pressureKpa,
        temperatureC,
        flags,
        wheelUpdatedAt,
        explicitlyStale,
        source,
        updatedAt,
        null,
        null,
    )

    init {
        this.known = TpmsState.copyBool(known)
        this.pressureKpa = TpmsState.copyInt(pressureKpa)
        this.temperatureC = TpmsState.copyInt(temperatureC)
        this.flags = TpmsState.copyInt(flags)
        this.wheelUpdatedAt = TpmsState.copyLong(wheelUpdatedAt)
        this.explicitlyStale = TpmsState.copyBool(explicitlyStale)
        this.source = TpmsState.safe(source)
        this.updatedAt = updatedAt
        this.wheelAgeAtCaptureMs = TpmsState.copyLong(capturedAges)
        this.wheelCapturedAtElapsedMs = TpmsState.copyLong(capturedElapsed)
        if (capturedAges == null || capturedElapsed == null) {
            val wallNow: Long = TpmsState.clock!!.wallMillis()
            val elapsedNow: Long = TpmsState.clock!!.elapsedMillis()
            for (wheel in 0..<TpmsState.WHEEL_COUNT) {
                this.wheelAgeAtCaptureMs[wheel] =
                    TpmsState.initialAge(
                        this.known.get(wheel),
                        this.wheelUpdatedAt.get(wheel),
                        wallNow,
                    )
                this.wheelCapturedAtElapsedMs[wheel] = elapsedNow
            }
        }
    }

    fun withWheel(
        wheel: Int,
        pressure: Int,
        temp: Int,
        flag: Int,
        valueSource: String?,
    ): TpmsState {
        return withWheelAt(wheel, pressure, temp, flag, valueSource, TpmsState.clock!!.wallMillis())
    }

    fun withWheelAt(
        wheel: Int,
        pressure: Int,
        temp: Int,
        flag: Int,
        valueSource: String?,
        observedAt: Long,
    ): TpmsState {
        if (wheel < 0 || wheel >= TpmsState.WHEEL_COUNT) return this
        val nextKnown: BooleanArray = TpmsState.copyBool(known)
        val nextPressure: IntArray = TpmsState.copyInt(pressureKpa)
        val nextTemp: IntArray = TpmsState.copyInt(temperatureC)
        val nextFlags: IntArray = TpmsState.copyInt(flags)
        val nextWheelUpdatedAt: LongArray = TpmsState.copyLong(wheelUpdatedAt)
        val nextExplicitlyStale: BooleanArray = TpmsState.copyBool(explicitlyStale)
        val nextAges: LongArray = TpmsState.copyLong(wheelAgeAtCaptureMs)
        val nextElapsed: LongArray = TpmsState.copyLong(wheelCapturedAtElapsedMs)
        val cleanObservedAt: Long = Math.max(0L, observedAt)
        nextKnown[wheel] = true
        nextPressure[wheel] = TpmsState.clampPressure(pressure)
        nextTemp[wheel] = TpmsState.clampTemp(temp)
        nextFlags[wheel] = flag and 0xff
        nextWheelUpdatedAt[wheel] = cleanObservedAt
        nextExplicitlyStale[wheel] = false
        nextAges[wheel] =
            TpmsState.initialAge(true, cleanObservedAt, TpmsState.clock!!.wallMillis())
        nextElapsed[wheel] = TpmsState.clock!!.elapsedMillis()
        return TpmsState(
            nextKnown,
            nextPressure,
            nextTemp,
            nextFlags,
            nextWheelUpdatedAt,
            nextExplicitlyStale,
            valueSource,
            Math.max(updatedAt, cleanObservedAt),
            nextAges,
            nextElapsed,
        )
    }

    /**
     * Keeps the last value for diagnostics, but makes it visibly stale and ineligible for warnings.
     * Used when the adapter explicitly reports an empty wheel slot.
     */
    fun withWheelStale(wheel: Int, valueSource: String?, observedAt: Long): TpmsState {
        if (wheel < 0 || wheel >= TpmsState.WHEEL_COUNT || !known.get(wheel)) return this
        if (explicitlyStale.get(wheel)) return this
        val nextExplicitlyStale: BooleanArray = TpmsState.copyBool(explicitlyStale)
        nextExplicitlyStale[wheel] = true
        val cleanObservedAt: Long = Math.max(0L, observedAt)
        return TpmsState(
            known,
            pressureKpa,
            temperatureC,
            flags,
            wheelUpdatedAt,
            nextExplicitlyStale,
            valueSource,
            Math.max(updatedAt, cleanObservedAt),
            wheelAgeAtCaptureMs,
            wheelCapturedAtElapsedMs,
        )
    }

    fun hasData(): Boolean {
        for (i in 0..<TpmsState.WHEEL_COUNT) {
            if (known.get(i)) return true
        }
        return false
    }

    fun hasFreshData(): Boolean {
        val elapsedNow: Long = TpmsState.clock!!.elapsedMillis()
        for (wheel in 0..<TpmsState.WHEEL_COUNT) {
            if (isWheelFreshAtElapsed(wheel, elapsedNow)) return true
        }
        return false
    }

    fun isWheelFresh(wheel: Int): Boolean {
        return isWheelFreshAtElapsed(wheel, TpmsState.clock!!.elapsedMillis())
    }

    fun wheelAgeMs(wheel: Int): Long {
        return wheelAgeAtElapsed(wheel, TpmsState.clock!!.elapsedMillis())
    }

    private fun isWheelFreshAtElapsed(wheel: Int, elapsedNow: Long): Boolean {
        return wheel >= 0 &&
            wheel < TpmsState.WHEEL_COUNT &&
            known.get(wheel) &&
            !explicitlyStale.get(wheel) &&
            wheelAgeAtElapsed(wheel, elapsedNow) <= TpmsState.WHEEL_STALE_AFTER_MS
    }

    private fun wheelAgeAtElapsed(wheel: Int, elapsedNow: Long): Long {
        if (wheel < 0 || wheel >= TpmsState.WHEEL_COUNT || !known.get(wheel)) return Long.MAX_VALUE
        val initial: Long = wheelAgeAtCaptureMs.get(wheel)
        val captured: Long = wheelCapturedAtElapsedMs.get(wheel)
        if (initial == Long.MAX_VALUE || elapsedNow < captured) return Long.MAX_VALUE
        val delta: Long = elapsedNow - captured
        return if (initial > Long.MAX_VALUE - delta) Long.MAX_VALUE else initial + delta
    }

    /**
     * Explicit wall-clock overload retained for diagnostic calculations and historical fixtures.
     */
    fun isWheelFresh(wheel: Int, now: Long): Boolean {
        return isWheelFresh(wheel, now, TpmsState.WHEEL_STALE_AFTER_MS)
    }

    fun isWheelFresh(wheel: Int, now: Long, staleAfterMs: Long): Boolean {
        if (wheel < 0 || wheel >= TpmsState.WHEEL_COUNT || !known.get(wheel)) return false
        if (explicitlyStale.get(wheel)) return false
        val observedAt: Long = wheelUpdatedAt.get(wheel)
        if (observedAt <= 0L || staleAfterMs < 0L) return false
        val age: Long = Math.max(0L, now - observedAt)
        return age <= staleAfterMs
    }

    fun isWheelStale(wheel: Int, now: Long): Boolean {
        return wheel >= 0 &&
            wheel < TpmsState.WHEEL_COUNT &&
            known.get(wheel) &&
            !isWheelFresh(wheel, now)
    }

    /** Explicit wall-clock age; runtime displays should use [.wheelAgeMs]. */
    fun wheelAgeMs(wheel: Int, now: Long): Long {
        if (
            wheel < 0 ||
                wheel >= TpmsState.WHEEL_COUNT ||
                !known.get(wheel) ||
                wheelUpdatedAt.get(wheel) <= 0L
        ) {
            return Long.MAX_VALUE
        }
        return Math.max(0L, now - wheelUpdatedAt.get(wheel))
    }

    fun summary(): String {
        if (!hasData()) return "TPMS: нет данных"
        return ("TPMS: FL " +
            wheelText(TpmsState.WHEEL_FL) +
            " | FR " +
            wheelText(TpmsState.WHEEL_FR) +
            " | RL " +
            wheelText(TpmsState.WHEEL_RL) +
            " | RR " +
            wheelText(TpmsState.WHEEL_RR))
    }

    private fun wheelText(wheel: Int): String {
        if (!known.get(wheel)) return "-"
        val value =
            String.format(
                Locale.US,
                "%.2f bar / %dC",
                pressureKpa.get(wheel) / 100f,
                temperatureC.get(wheel),
            )
        return if (isWheelFresh(wheel)) value else value + " (устар.)"
    }

    companion object {
        const val WHEEL_FL: Int = 0
        const val WHEEL_FR: Int = 1
        const val WHEEL_RL: Int = 2
        const val WHEEL_RR: Int = 3
        const val WHEEL_COUNT: Int = 4
        /**
         * A wheel is no longer treated as live after an adapter update has been missing for this
         * long. This is deliberately longer than the 120 second background poll so one delayed
         * response does not make all wheels stale.
         */
        const val WHEEL_STALE_AFTER_MS: Long = 180000L

        private val SYSTEM_CLOCK: Clock =
            object : Clock {
                override fun wallMillis(): Long {
                    return System.currentTimeMillis()
                }

                override fun elapsedMillis(): Long {
                    try {
                        // Includes head-unit sleep and is unaffected by GPS/network wall-clock
                        // corrections.
                        return SystemClock.elapsedRealtime()
                    } catch (error: RuntimeException) {
                        // Plain JVM parsing tests use Android's throwing SDK stub, not a device
                        // clock.
                        if (error.message == null || !error.message!!.contains("not mocked")) {
                            throw error
                        }
                        return System.nanoTime() / 1000000L
                    }
                }
            }
        @kotlin.concurrent.Volatile private var clock: Clock? = TpmsState.SYSTEM_CLOCK

        @JvmStatic
        fun setClockForTests(value: Clock?) {
            TpmsState.clock = if (value == null) TpmsState.SYSTEM_CLOCK else value
        }

        @JvmStatic
        fun empty(): TpmsState {
            return TpmsState(
                BooleanArray(TpmsState.WHEEL_COUNT),
                IntArray(TpmsState.WHEEL_COUNT),
                IntArray(TpmsState.WHEEL_COUNT),
                IntArray(TpmsState.WHEEL_COUNT),
                "",
                0L,
            )
        }

        private fun initialAge(known: Boolean, observedAt: Long, wallNow: Long): Long {
            if (!known || observedAt <= 0L || observedAt > wallNow) return Long.MAX_VALUE
            val age: Long = wallNow - observedAt
            return if (age < 0L) Long.MAX_VALUE else age
        }

        private fun copyBool(value: BooleanArray?): BooleanArray {
            val out: BooleanArray = BooleanArray(TpmsState.WHEEL_COUNT)
            if (value != null)
                System.arraycopy(value, 0, out, 0, Math.min(value.size, TpmsState.WHEEL_COUNT))
            return out
        }

        private fun copyInt(value: IntArray?): IntArray {
            val out: IntArray = IntArray(TpmsState.WHEEL_COUNT)
            if (value != null)
                System.arraycopy(value, 0, out, 0, Math.min(value.size, TpmsState.WHEEL_COUNT))
            return out
        }

        private fun copyLong(value: LongArray?): LongArray {
            val out: LongArray = LongArray(TpmsState.WHEEL_COUNT)
            if (value != null)
                System.arraycopy(value, 0, out, 0, Math.min(value.size, TpmsState.WHEEL_COUNT))
            return out
        }

        private fun timestampsForKnownWheels(known: BooleanArray?, updatedAt: Long): LongArray? {
            val out: LongArray = LongArray(TpmsState.WHEEL_COUNT)
            if (known == null || updatedAt <= 0L) return out
            for (wheel in 0..<Math.min(known.size, TpmsState.WHEEL_COUNT)) {
                if (known.get(wheel)) out[wheel] = updatedAt
            }
            return out
        }

        private fun clampPressure(value: Int): Int {
            return Math.max(0, Math.min(600, value))
        }

        private fun clampTemp(value: Int): Int {
            return Math.max(-60, Math.min(160, value))
        }

        private fun safe(value: String?): String {
            return if (value == null) "" else value.trim()
        }
    }
}
