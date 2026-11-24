package kia.app.core.model

class VehicleState
private constructor(
    engineTempKnown: Boolean,
    engineTempC: Int,
    engineTempSource: String?,
    outsideTempKnown: Boolean,
    outsideTempC: Int,
    outsideTempSource: String?,
    cabinLeftKnown: Boolean,
    cabinLeftTempC: Int,
    cabinRightKnown: Boolean,
    cabinRightTempC: Int,
    cabinTempSource: String?,
    sunroofKnown: Boolean,
    sunroofOpen: Boolean,
    sunroofSource: String?,
    sunroofReceivedElapsed: Long,
    sunroofConnectionEpoch: Long,
    updatedAt: Long,
) {
    @JvmField val engineTempKnown: Boolean
    @JvmField val engineTempC: Int
    @JvmField val engineTempSource: String
    @JvmField val outsideTempKnown: Boolean
    @JvmField val outsideTempC: Int
    @JvmField val outsideTempSource: String
    @JvmField val cabinLeftKnown: Boolean
    @JvmField val cabinLeftTempC: Int
    @JvmField val cabinRightKnown: Boolean
    @JvmField val cabinRightTempC: Int
    @JvmField val cabinTempSource: String
    @JvmField val sunroofKnown: Boolean
    @JvmField val sunroofOpen: Boolean
    @JvmField val sunroofSource: String
    @JvmField val sunroofReceivedElapsed: Long
    @JvmField val sunroofConnectionEpoch: Long
    @JvmField val updatedAt: Long

    constructor(
        engineTempKnown: Boolean,
        engineTempC: Int,
        engineTempSource: String?,
        outsideTempKnown: Boolean,
        outsideTempC: Int,
        outsideTempSource: String?,
        cabinLeftKnown: Boolean,
        cabinLeftTempC: Int,
        cabinRightKnown: Boolean,
        cabinRightTempC: Int,
        cabinTempSource: String?,
        updatedAt: Long,
    ) : this(
        engineTempKnown,
        engineTempC,
        engineTempSource,
        outsideTempKnown,
        outsideTempC,
        outsideTempSource,
        cabinLeftKnown,
        cabinLeftTempC,
        cabinRightKnown,
        cabinRightTempC,
        cabinTempSource,
        false,
        false,
        "",
        0L,
        0L,
        VehicleState.longTime(updatedAt),
    )

    init {
        this.engineTempKnown = engineTempKnown
        this.engineTempC = engineTempC
        this.engineTempSource = VehicleState.safe(engineTempSource)
        this.outsideTempKnown = outsideTempKnown
        this.outsideTempC = outsideTempC
        this.outsideTempSource = VehicleState.safe(outsideTempSource)
        this.cabinLeftKnown = cabinLeftKnown
        this.cabinLeftTempC = cabinLeftTempC
        this.cabinRightKnown = cabinRightKnown
        this.cabinRightTempC = cabinRightTempC
        this.cabinTempSource = VehicleState.safe(cabinTempSource)
        this.sunroofKnown = sunroofKnown
        this.sunroofOpen = sunroofOpen
        this.sunroofSource = VehicleState.safe(sunroofSource)
        this.sunroofReceivedElapsed = Math.max(0L, sunroofReceivedElapsed)
        this.sunroofConnectionEpoch = Math.max(0L, sunroofConnectionEpoch)
        this.updatedAt = updatedAt
    }

    fun withEngineTemp(value: Int, source: String?): VehicleState {
        return VehicleState(
            true,
            VehicleState.clampTemp(value),
            source,
            outsideTempKnown,
            outsideTempC,
            outsideTempSource,
            cabinLeftKnown,
            cabinLeftTempC,
            cabinRightKnown,
            cabinRightTempC,
            cabinTempSource,
            sunroofKnown,
            sunroofOpen,
            sunroofSource,
            sunroofReceivedElapsed,
            sunroofConnectionEpoch,
            System.currentTimeMillis(),
        )
    }

    fun withOutsideTemp(value: Int, source: String?): VehicleState {
        return VehicleState(
            engineTempKnown,
            engineTempC,
            engineTempSource,
            true,
            VehicleState.clampTemp(value),
            source,
            cabinLeftKnown,
            cabinLeftTempC,
            cabinRightKnown,
            cabinRightTempC,
            cabinTempSource,
            sunroofKnown,
            sunroofOpen,
            sunroofSource,
            sunroofReceivedElapsed,
            sunroofConnectionEpoch,
            System.currentTimeMillis(),
        )
    }

    fun withCabinTemp(
        leftKnown: Boolean,
        leftValue: Int,
        rightKnown: Boolean,
        rightValue: Int,
        source: String?,
    ): VehicleState {
        return VehicleState(
            engineTempKnown,
            engineTempC,
            engineTempSource,
            outsideTempKnown,
            outsideTempC,
            outsideTempSource,
            leftKnown,
            VehicleState.clampTemp(leftValue),
            rightKnown,
            VehicleState.clampTemp(rightValue),
            source,
            sunroofKnown,
            sunroofOpen,
            sunroofSource,
            sunroofReceivedElapsed,
            sunroofConnectionEpoch,
            System.currentTimeMillis(),
        )
    }

    fun withSunroof(open: Boolean, source: String?): VehicleState {
        return withSunroof(open, source, 0L, 0L)
    }

    fun withSunroof(
        open: Boolean,
        source: String?,
        receivedElapsed: Long,
        connectionEpoch: Long,
    ): VehicleState {
        return VehicleState(
            engineTempKnown,
            engineTempC,
            engineTempSource,
            outsideTempKnown,
            outsideTempC,
            outsideTempSource,
            cabinLeftKnown,
            cabinLeftTempC,
            cabinRightKnown,
            cabinRightTempC,
            cabinTempSource,
            true,
            open,
            source,
            receivedElapsed,
            connectionEpoch,
            System.currentTimeMillis(),
        )
    }

    fun withoutSunroof(): VehicleState {
        if (!sunroofKnown) return this
        return VehicleState(
            engineTempKnown,
            engineTempC,
            engineTempSource,
            outsideTempKnown,
            outsideTempC,
            outsideTempSource,
            cabinLeftKnown,
            cabinLeftTempC,
            cabinRightKnown,
            cabinRightTempC,
            cabinTempSource,
            false,
            false,
            "",
            0L,
            0L,
            System.currentTimeMillis(),
        )
    }

    fun summary(): String {
        return ("Температура: улица " +
            VehicleState.temp(outsideTempKnown, outsideTempC, outsideTempSource) +
            " | салон " +
            cabinText() +
            " | двигатель " +
            VehicleState.temp(engineTempKnown, engineTempC, engineTempSource) +
            (if (sunroofKnown) " | люк " + (if (sunroofOpen) "открыт" else "закрыт") else ""))
    }

    private fun cabinText(): String {
        if (!cabinLeftKnown && !cabinRightKnown) return "-"
        val left: String = if (cabinLeftKnown) cabinLeftTempC.toString() + "C" else "-"
        val right: String = if (cabinRightKnown) cabinRightTempC.toString() + "C" else "-"
        return "L " + left + " / R " + right + VehicleState.source(cabinTempSource)
    }

    companion object {
        @JvmStatic
        fun empty(): VehicleState {
            return VehicleState(false, 0, "", false, 0, "", false, 0, false, 0, "", 0L)
        }

        private fun temp(known: Boolean, value: Int, source: String?): String {
            return if (known) value.toString() + "C" + VehicleState.source(source) else "-"
        }

        private fun source(value: String?): String {
            val text: String = VehicleState.safe(value)
            return if (text.isEmpty()) "" else " (" + text + ")"
        }

        private fun clampTemp(value: Int): Int {
            return Math.max(-60, Math.min(160, value))
        }

        private fun safe(value: String?): String {
            return if (value == null) "" else value.trim()
        }

        private fun longTime(value: Long): Long {
            return Math.max(0L, value)
        }
    }
}
