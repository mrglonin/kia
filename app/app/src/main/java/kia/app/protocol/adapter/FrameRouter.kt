package kia.app.protocol.adapter

import android.content.Context
import java.util.Locale
import kia.app.amp.AmpController
import kia.app.core.AppLog
import kia.app.core.StateStore
import kia.app.core.model.AdapterState
import kia.app.core.model.VehicleState
import kia.app.media.domain.MediaFeature
import kia.app.rcta.RctaController
import kia.app.sunroof.SunroofStateMemory
import kia.app.tpms.TeyesTpmsWidgetForwarder
import kia.app.tpms.TpmsController
import kia.app.update.FirmwareUpdateController

class FrameRouter(context: Context) {
    private val app: Context?
    // This fence survives a USB disconnect clearing the live VehicleState. Old reader
    // callbacks must not overwrite a newer persisted roof position after reconnect.
    private var lastSunroofEpoch: Long = -1L
    private var lastSunroofReceivedElapsed: Long = -1L

    init {
        this.app = context.getApplicationContext()
    }

    fun onFrame(frame: ByteArray?, usbConnectionEpoch: Long, receivedElapsed: Long) {
        if (frame == null || frame.size < 6) return
        val id: Int = frame.get(4).toInt() and 0xff
        StateStore.setAdapter(app, StateStore.adapter()!!.withFrameTime(System.currentTimeMillis()))
        when (id) {
            AdapterProtocol.CMD_VERSION -> handleVersion(frame)
            AdapterProtocol.CMD_HEALTH -> handleHealth(frame)
            AdapterProtocol.CMD_AMP -> AmpController.get(app!!).handleFrame(frame)
            AdapterProtocol.CMD_RAW_CAN ->
                if (!handleSunroof(frame, usbConnectionEpoch, receivedElapsed)) handleRawCan(frame)
            AdapterProtocol.CMD_RAW_CAN_TX ->
                AppLog.line(app, "CAN TX ack: " + AdapterProtocol.hex(frame))
            AdapterProtocol.CMD_SETTINGS ->
                AppLog.line(app, "Settings ack: " + AdapterProtocol.hex(frame))
            AdapterProtocol.CMD_SOURCE_STATUS ->
                MediaFeature.get(app!!).handleRealMediaStatus(frame)
            AdapterProtocol.CMD_RCTA -> RctaController.get(app!!).handleFrame(frame)
            AdapterProtocol.CMD_FIRMWARE -> {
                FirmwareUpdateController.get(app!!).handleAck(frame)
                AppLog.line(app, "Firmware ack: " + AdapterProtocol.hex(frame))
            }
            AdapterProtocol.CMD_VEHICLE_SNAPSHOT -> handleSnapshot(frame)
            AdapterProtocol.CMD_TPMS,
            AdapterProtocol.CMD_TPMS_LEGACY ->
                if (TpmsController.get(app!!).handleAdapterFrame(frame)) {
                    TeyesTpmsWidgetForwarder.get(app)
                        .onUsbFrame(frame, usbConnectionEpoch, receivedElapsed)
                }
            else -> {}
        }
    }

    private fun handleVersion(frame: ByteArray) {
        if (frame.size < 9) return
        val len: Int = frame.get(3).toInt() and 0xff
        val current: AdapterState? = StateStore.adapter()
        if (len == 18 && frame.size >= 17) {
            val uid: StringBuilder = StringBuilder()
            for (i in 5..16) uid.append(FrameRouter.byteHex(frame.get(i)))
            StateStore.setAdapter(app, current!!.withInfo(uid.toString(), null))
            return
        }
        if (len == 10 && frame.size >= 9) {
            val family: String = if ((frame.get(6).toInt() and 0xff) == 0x35) "2CAN35" else "CAN"
            val prefix: String = if ((frame.get(5).toInt() and 0xff) == 0xAA) "BASE " else ""
            val version: String =
                (prefix + family) +
                    " " +
                    FrameRouter.byteHex(frame.get(7)) +
                    "." +
                    FrameRouter.byteHex(frame.get(8))
            StateStore.setAdapter(app, current!!.withInfo(null, version))
        }
    }

    private fun handleHealth(frame: ByteArray) {
        val status: Int = if (frame.size > 5) frame.get(5).toInt() and 0xff else -1
        val apiMajor: Int = if (frame.size > 6) frame.get(6).toInt() and 0xff else -1
        val apiMinor: Int = if (frame.size > 7) frame.get(7).toInt() and 0xff else -1
        val rawTx: Int = if (frame.size > 8) frame.get(8).toInt() and 0xff else -1
        val caps: Int = if (frame.size > 9) frame.get(9).toInt() and 0xff else -1
        val statusText: String =
            if (status == 0x20) "V20 online"
            else if (status == 0x21) "V21 online"
            else "ответ 0x" + FrameRouter.byteHex(status.toByte())
        val health: String =
            (statusText +
                " API " +
                Math.max(0, apiMajor) +
                "." +
                Math.max(0, apiMinor) +
                " rawTX=" +
                (if (rawTx == 0) "нет" else "да") +
                " caps=0x" +
                FrameRouter.byteHex(Math.max(0, caps).toByte()))
        StateStore.setAdapter(app, StateStore.adapter()!!.withHealth(health))
    }

    private fun handleRawCan(frame: ByteArray?) {
        val parsed: ParsedCan? = parseRawCan(frame)
        if (parsed != null) applyCanFrame(parsed.canId, parsed.data)
    }

    @kotlin.jvm.Synchronized
    private fun handleSunroof(
        frame: ByteArray?,
        connectionEpoch: Long,
        receivedElapsed: Long,
    ): Boolean {
        val len: Int =
            if (frame == null || frame.size < 6) 0
            else Math.min(frame.size, frame.get(3).toInt() and 0xff)
        if (len != 7) return false
        if (
            connectionEpoch < lastSunroofEpoch ||
                (connectionEpoch == lastSunroofEpoch &&
                    receivedElapsed < lastSunroofReceivedElapsed)
        )
            return true
        val open: Boolean = (frame!!.get(5).toInt() and 0xff) != 0
        val current: VehicleState? = StateStore.vehicle()
        if (
            current!!.sunroofKnown &&
                (connectionEpoch < current.sunroofConnectionEpoch ||
                    (connectionEpoch == current.sunroofConnectionEpoch &&
                        receivedElapsed < current.sunroofReceivedElapsed))
        )
            return true
        val changed: Boolean = !current.sunroofKnown || current.sunroofOpen != open
        // The adapter may emit roof position only on a change. Keep the last actual
        // position before notifying consumers; ACC-off need not bring another roof frame.
        SunroofStateMemory.record(app!!, open, "0x76")
        StateStore.setVehicle(
            app,
            current.withSunroof(open, "0x76", receivedElapsed, connectionEpoch),
        )
        lastSunroofEpoch = connectionEpoch
        lastSunroofReceivedElapsed = receivedElapsed
        if (changed) AppLog.line(app, "Sunroof: " + (if (open) "open" else "closed"))
        return true
    }

    private fun handleSnapshot(frame: ByteArray?) {
        val frameLen: Int =
            if (frame == null || frame.size < 6) 0
            else Math.min(frame.size, frame.get(3).toInt() and 0xff)
        val payloadLen: Int = if (frameLen >= 6) frameLen - 6 else 0
        if (payloadLen < 22) return
        val base: Int = 5
        val status: Int = FrameRouter.u8(frame, base)
        if (status == 0) return
        val known: Int =
            FrameRouter.u8(frame, base + 1) or
                (FrameRouter.u8(frame, base + 2) shl 8) or
                (FrameRouter.u8(frame, base + 3) shl 16)
        val current: VehicleState? = StateStore.vehicle()
        var next: VehicleState? = current
        if ((known and 0x004) != 0)
            next = next!!.withEngineTemp(FrameRouter.s16(frame, base + 12), "snapshot")
        if ((known and 0x400) != 0)
            next = next!!.withOutsideTemp(FrameRouter.s16(frame, base + 20), "snapshot")
        if (next != current) StateStore.setVehicle(app, next)
    }

    private fun parseRawCan(frame: ByteArray?): ParsedCan? {
        if (frame == null || frame.size < 23) return null
        val len: Int = Math.min(frame.size, frame.get(3).toInt() and 0xff)
        if (len < 23 || (frame.get(4).toInt() and 0xff) != AdapterProtocol.CMD_RAW_CAN) return null
        if ((frame.get(5).toInt() and 0xff) == 0) return null
        val dlc: Int = Math.min(frame.get(8).toInt() and 0xff, 8)
        if (dlc <= 0 || len < 14 + dlc) return null
        val canId: Int = FrameRouter.le32(frame, 10)
        if (canId == 0) return null
        val data: ByteArray = ByteArray(dlc)
        System.arraycopy(frame, 14, data, 0, dlc)
        return ParsedCan(canId, data)
    }

    private fun applyCanFrame(canId: Int, data: ByteArray?) {
        if (data == null) return
        val current: VehicleState? = StateStore.vehicle()
        var next: VehicleState? = current
        if (canId == 0x329 && data.size >= 2) {
            val temp: Int = Math.round(((data.get(1).toInt() and 0xff) - 0x40) * 0.75f)
            next = next!!.withEngineTemp(temp, "0x329")
        } else if (canId == 0x044 && data.size >= 4) {
            val outside: Int = Math.round(((data.get(3).toInt() and 0xff) - 0x52) / 2f)
            next = next!!.withOutsideTemp(outside, "0x044")
        } else if (canId == 0x042 && data.size >= 4) {
            next =
                FrameRouter.applyCabinClassic(
                    next!!,
                    data.get(1).toInt() and 0xff,
                    data.get(3).toInt() and 0xff,
                    "0x042",
                )
        } else if (canId == 0x131 && data.size >= 5) {
            next =
                FrameRouter.applyCabinV8(
                    next!!,
                    data.get(0).toInt() and 0xff,
                    data.get(4).toInt() and 0xff,
                    "0x131",
                )
        }
        if (next != current) StateStore.setVehicle(app, next)
    }

    private class ParsedCan(canId: Int, data: ByteArray?) {
        @JvmField val canId: Int
        @JvmField val data: ByteArray?

        init {
            this.canId = canId
            this.data = data
        }
    }

    companion object {
        private fun applyCabinClassic(
            current: VehicleState,
            leftRaw: Int,
            rightRaw: Int,
            source: String?,
        ): VehicleState {
            val left: Int = FrameRouter.classicClimateTemp(leftRaw)
            val right: Int = FrameRouter.classicClimateTemp(rightRaw)
            val leftKnown: Boolean = left != Integer.MIN_VALUE || current.cabinLeftKnown
            val rightKnown: Boolean = right != Integer.MIN_VALUE || current.cabinRightKnown
            return current.withCabinTemp(
                leftKnown,
                if (left != Integer.MIN_VALUE) left else current.cabinLeftTempC,
                rightKnown,
                if (right != Integer.MIN_VALUE) right else current.cabinRightTempC,
                source,
            )
        }

        private fun applyCabinV8(
            current: VehicleState,
            leftRaw: Int,
            rightRaw: Int,
            source: String?,
        ): VehicleState {
            val left: Int = FrameRouter.v8ClimateTemp(leftRaw)
            val right: Int = FrameRouter.v8ClimateTemp(rightRaw)
            val leftKnown: Boolean = left != Integer.MIN_VALUE || current.cabinLeftKnown
            val rightKnown: Boolean = right != Integer.MIN_VALUE || current.cabinRightKnown
            return current.withCabinTemp(
                leftKnown,
                if (left != Integer.MIN_VALUE) left else current.cabinLeftTempC,
                rightKnown,
                if (right != Integer.MIN_VALUE) right else current.cabinRightTempC,
                source,
            )
        }

        private fun classicClimateTemp(raw: Int): Int {
            if (raw == 0 || raw == 0xff || raw == 0x7f) return Integer.MIN_VALUE
            if (raw >= 0x10 && raw <= 0x30) return raw
            if (raw >= 0x32 && raw <= 0x64) return raw / 2
            return Integer.MIN_VALUE
        }

        private fun v8ClimateTemp(raw: Int): Int {
            if (raw == 0 || raw == 0xff || raw <= 6) return Integer.MIN_VALUE
            return raw - 6
        }

        private fun u8(data: ByteArray?, offset: Int): Int {
            return if (data == null || offset < 0 || offset >= data.size) 0
            else data.get(offset).toInt() and 0xff
        }

        private fun u16(data: ByteArray?, offset: Int): Int {
            return FrameRouter.u8(data, offset) or (FrameRouter.u8(data, offset + 1) shl 8)
        }

        private fun s16(data: ByteArray?, offset: Int): Int {
            val value: Int = FrameRouter.u16(data, offset)
            return if (value >= 0x8000) value - 0x10000 else value
        }

        private fun le32(data: ByteArray?, offset: Int): Int {
            return (FrameRouter.u8(data, offset) or
                (FrameRouter.u8(data, offset + 1) shl 8) or
                (FrameRouter.u8(data, offset + 2) shl 16) or
                (FrameRouter.u8(data, offset + 3) shl 24))
        }

        private fun byteHex(b: Byte): String {
            val v: Int = b.toInt() and 0xff
            return (if (v < 16) "0" else "") + Integer.toHexString(v).uppercase(Locale.US)
        }
    }
}
