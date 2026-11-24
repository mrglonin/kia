package kia.app.core.model

class AdapterState(
    usbConnected: Boolean,
    usbText: String?,
    uid: String?,
    firmware: String?,
    health: String?,
    lastFrameAt: Long,
) {
    @JvmField val usbConnected: Boolean
    @JvmField val usbText: String
    @JvmField val uid: String
    @JvmField val firmware: String
    @JvmField val health: String
    @JvmField val lastFrameAt: Long

    init {
        this.usbConnected = usbConnected
        this.usbText = AdapterState.safe(usbText)
        this.uid = AdapterState.safe(uid)
        this.firmware = AdapterState.safe(firmware)
        this.health = AdapterState.safe(health)
        this.lastFrameAt = lastFrameAt
    }

    fun withUsb(connected: Boolean, text: String?): AdapterState {
        return AdapterState(connected, text, uid, firmware, health, lastFrameAt)
    }

    fun withInfo(nextUid: String?, nextFirmware: String?): AdapterState {
        return AdapterState(
            usbConnected,
            usbText,
            if (nextUid == null) uid else nextUid,
            if (nextFirmware == null) firmware else nextFirmware,
            health,
            lastFrameAt,
        )
    }

    fun withHealth(value: String?): AdapterState {
        return AdapterState(usbConnected, usbText, uid, firmware, value, lastFrameAt)
    }

    fun withFrameTime(value: Long): AdapterState {
        return AdapterState(usbConnected, usbText, uid, firmware, health, value)
    }

    companion object {
        @JvmStatic
        fun empty(): AdapterState {
            return AdapterState(
                false,
                "USB не подключен",
                "не получен",
                "не получена",
                "не проверено",
                0L,
            )
        }

        private fun safe(value: String?): String {
            return if (value == null) "" else value
        }
    }
}
