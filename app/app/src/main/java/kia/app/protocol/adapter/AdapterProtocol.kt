package kia.app.protocol.adapter

import android.text.TextUtils
import java.nio.charset.StandardCharsets
import java.util.Arrays
import java.util.Locale

object AdapterProtocol {
    const val CMD_RADIO_TEXT: Int = 0x20
    const val CMD_MEDIA_TEXT: Int = 0x21
    const val CMD_USB_TEXT: Int = 0x22
    const val CMD_ANDROID_AUTO_TEXT: Int = 0x23
    const val CMD_MY_MUSIC_TEXT: Int = 0x24
    const val CMD_CARPLAY_TEXT: Int = 0x25
    const val CMD_AMP: Int = 0x30
    const val CMD_SPEED_LIMIT: Int = 0x44
    const val CMD_MANEUVER: Int = 0x45
    const val CMD_ETA: Int = 0x47
    const val CMD_NAV_ON: Int = 0x48
    const val CMD_ETA_TIME: Int = 0x49
    const val CMD_NAV_TEXT: Int = 0x4A
    const val CMD_FIRMWARE: Int = 0x55
    const val CMD_VERSION: Int = 0x56
    const val CMD_SETTINGS: Int = 0x60
    const val CMD_RAW_STREAM: Int = 0x70
    const val CMD_RCTA: Int = 0x75
    const val CMD_SUNROOF: Int = 0x76
    const val CMD_RAW_CAN: Int = 0x76
    const val CMD_VEHICLE_SNAPSHOT: Int = 0x77
    const val CMD_RAW_CAN_TX: Int = 0x78
    const val CMD_HEALTH: Int = 0x79
    const val CMD_TPMS: Int = 0x51
    const val CMD_SOURCE_STATUS: Int = 0x7A
    const val CMD_TPMS_LEGACY: Int = 0x7B
    const val SETTINGS_SUB_SAS_RATIO: Int = 0x01
    const val SETTINGS_SUB_TEMP_ENGINE: Int = 0x02

    private const val TEXT_MAX_CHARS: Int = 28
    private const val NAV_TEXT_MAX_UTF16_BYTES: Int = 56
    private var mediaSecond: Int = 1

    @JvmStatic
    fun packet(id: Int, payload: ByteArray?): ByteArray? {
        val size: Int = if (payload == null) 0 else payload.size
        val data: ByteArray = ByteArray(6 + size)
        data[0] = 0xBB.toByte()
        data[1] = 0x41
        data[2] = 0xA1.toByte()
        data[3] = data.size.toByte()
        data[4] = id.toByte()
        if (size > 0) System.arraycopy(payload, 0, data, 5, size)
        return AdapterProtocol.checksum(data)
    }

    @JvmStatic
    fun frame(id: Int, command: Int): ByteArray? {
        val data: ByteArray = ByteArray(7)
        data[0] = 0xBB.toByte()
        data[1] = 0x41
        data[2] = 0xA1.toByte()
        data[3] = 7
        data[4] = id.toByte()
        data[5] = command.toByte()
        return AdapterProtocol.checksum(data)
    }

    @JvmStatic
    fun frame(id: Int, command: Int, value: Int): ByteArray? {
        val data: ByteArray = ByteArray(8)
        data[0] = 0xBB.toByte()
        data[1] = 0x41
        data[2] = 0xA1.toByte()
        data[3] = 8
        data[4] = id.toByte()
        data[5] = command.toByte()
        data[6] = value.toByte()
        return AdapterProtocol.checksum(data)
    }

    @JvmStatic
    fun requestAdapterUid(): ByteArray? {
        return AdapterProtocol.frame(AdapterProtocol.CMD_VERSION, 0x01)
    }

    @JvmStatic
    fun requestAdapterVersion(): ByteArray? {
        return AdapterProtocol.frame(AdapterProtocol.CMD_VERSION, 0x02)
    }

    @JvmStatic
    fun requestSettings(): ByteArray? {
        return AdapterProtocol.frame(AdapterProtocol.CMD_SETTINGS, 0x00, 0x00)
    }

    @JvmStatic
    fun sasRatio(ratio: Int): ByteArray? {
        return AdapterProtocol.frame(
            AdapterProtocol.CMD_SETTINGS,
            AdapterProtocol.SETTINGS_SUB_SAS_RATIO,
            AdapterProtocol.clamp(ratio, 1, 255),
        )
    }

    @JvmStatic
    fun canbusTemperatureEngine(enabled: Boolean): ByteArray? {
        return AdapterProtocol.frame(
            AdapterProtocol.CMD_SETTINGS,
            AdapterProtocol.SETTINGS_SUB_TEMP_ENGINE,
            if (enabled) 1 else 0,
        )
    }

    @JvmStatic
    fun requestHealth(): ByteArray? {
        return AdapterProtocol.packet(AdapterProtocol.CMD_HEALTH, null)
    }

    @JvmStatic
    fun requestVehicleSnapshot(): ByteArray? {
        return AdapterProtocol.packet(AdapterProtocol.CMD_VEHICLE_SNAPSHOT, null)
    }

    @JvmStatic
    fun requestTpms(): ByteArray? {
        return AdapterProtocol.frame(AdapterProtocol.CMD_TPMS, 0x01)
    }

    @JvmStatic
    fun rawStream(enabled: Boolean): ByteArray? {
        return AdapterProtocol.packet(
            AdapterProtocol.CMD_RAW_STREAM,
            byteArrayOf((if (enabled) 0x01 else 0x00).toByte()),
        )
    }

    @JvmStatic
    fun rawCanPoll(): ByteArray? {
        return AdapterProtocol.packet(AdapterProtocol.CMD_RAW_CAN, null)
    }

    @JvmStatic
    fun rawCanTx(bus: Int, flags: Int, canId: Int, data: ByteArray?): ByteArray? {
        val len: Int = if (data == null) 0 else Math.min(8, data.size)
        val payload: ByteArray = ByteArray(7 + len)
        payload[0] = bus.toByte()
        payload[1] = flags.toByte()
        payload[2] = (canId and 0xff).toByte()
        payload[3] = ((canId shr 8) and 0xff).toByte()
        payload[4] = ((canId shr 16) and 0xff).toByte()
        payload[5] = ((canId shr 24) and 0xff).toByte()
        payload[6] = len.toByte()
        if (len > 0) System.arraycopy(data, 0, payload, 7, len)
        return AdapterProtocol.packet(AdapterProtocol.CMD_RAW_CAN_TX, payload)
    }

    @JvmStatic
    fun textPacket(id: Int, value: String?): ByteArray? {
        return AdapterProtocol.textPacket(id, value, AdapterProtocol.TEXT_MAX_CHARS)
    }

    @JvmStatic
    fun textPacket(id: Int, value: String?, maxChars: Int): ByteArray? {
        val text = AdapterProtocol.trim(value, maxChars)
        if (TextUtils.isEmpty(text)) {
            return AdapterProtocol.checksum(
                byteArrayOf(0xBB.toByte(), 0x41, 0xA1.toByte(), 0x08, id.toByte(), 0, 0, 0)
            )
        }
        val payload = text.toByteArray(StandardCharsets.UTF_16LE)
        val len: Int = payload.size + 6
        val frame: ByteArray = ByteArray(len)
        frame[0] = 0xBB.toByte()
        frame[1] = 0x41
        frame[2] = 0xA1.toByte()
        frame[3] = len.toByte()
        frame[4] = id.toByte()
        System.arraycopy(payload, 0, frame, 5, payload.size)
        return AdapterProtocol.checksum(frame)
    }

    @JvmStatic
    fun textPacketWithSubtype(id: Int, subtype: Int, value: String?): ByteArray? {
        val text = AdapterProtocol.trim(value, AdapterProtocol.TEXT_MAX_CHARS)
        if (TextUtils.isEmpty(text)) {
            return AdapterProtocol.checksum(
                byteArrayOf(
                    0xBB.toByte(),
                    0x41,
                    0xA1.toByte(),
                    0x09,
                    id.toByte(),
                    subtype.toByte(),
                    0,
                    0,
                    0,
                )
            )
        }
        val payload = text.toByteArray(StandardCharsets.UTF_16LE)
        val len: Int = Math.min(payload.size, AdapterProtocol.TEXT_MAX_CHARS * 2) + 7
        val frame: ByteArray = ByteArray(len)
        frame[0] = 0xBB.toByte()
        frame[1] = 0x41
        frame[2] = 0xA1.toByte()
        frame[3] = len.toByte()
        frame[4] = id.toByte()
        frame[5] = subtype.toByte()
        System.arraycopy(payload, 0, frame, 6, len - 7)
        return AdapterProtocol.checksum(frame)
    }

    @JvmStatic
    fun mediaSourceStatus(kind: MediaSourceKind?, detail: String?): ByteArray? {
        val payload: ByteArray?
        if (kind == MediaSourceKind.FM_RADIO) {
            payload = AdapterProtocol.raiseFmSourceStatus(detail)
        } else if (kind == MediaSourceKind.AM_RADIO) {
            payload = AdapterProtocol.raiseAmSourceStatus(detail)
        } else {
            payload = AdapterProtocol.raiseSourceStatus(kind)
        }
        return AdapterProtocol.packet(AdapterProtocol.CMD_SOURCE_STATUS, payload)
    }

    @JvmStatic
    fun mediaSourceStatusRaw(source: Int, detail: String?): ByteArray? {
        val value: Int = AdapterProtocol.clamp(source, 0, 255)
        if (value == 0x80) return AdapterProtocol.mediaOffStatus()
        return AdapterProtocol.packet(
            AdapterProtocol.CMD_SOURCE_STATUS,
            AdapterProtocol.raiseSourceValue(value, detail),
        )
    }

    @JvmStatic
    fun mediaFmSourceStatusNoFrequency(): ByteArray? {
        return AdapterProtocol.packet(
            AdapterProtocol.CMD_SOURCE_STATUS,
            AdapterProtocol.raiseFrame(byteArrayOf(0x08, 0x09, 0x02, 0x00, 0x00, 0x00, 0x00)),
        )
    }

    @JvmStatic
    fun mediaOffStatus(): ByteArray? {
        return AdapterProtocol.packet(
            AdapterProtocol.CMD_SOURCE_STATUS,
            AdapterProtocol.raiseFrame(byteArrayOf(0x06, 0x09, 0x81.toByte(), 0x00, 0x00)),
        )
    }

    @JvmStatic
    fun navOn(active: Boolean): ByteArray? {
        val frame: ByteArray =
            byteArrayOf(
                0xBB.toByte(),
                0x41,
                0xA1.toByte(),
                0x07,
                AdapterProtocol.CMD_NAV_ON.toByte(),
                (if (active) 1 else 0).toByte(),
                0,
            )
        return AdapterProtocol.checksum(frame)
    }

    @JvmStatic
    fun speedLimit(kmh: Int): ByteArray? {
        return AdapterProtocol.frame(
            AdapterProtocol.CMD_SPEED_LIMIT,
            AdapterProtocol.clamp(kmh, 0, 255),
        )
    }

    @JvmStatic
    fun etaDistance(distance: Float, km: Boolean): ByteArray? {
        val frame: ByteArray =
            byteArrayOf(
                0xBB.toByte(),
                0x41,
                0xA1.toByte(),
                0x0B,
                AdapterProtocol.CMD_ETA.toByte(),
                0,
                0,
                0,
                0,
                0,
                0,
            )
        if (distance > 0f) {
            val cleanDistance: Float = AdapterProtocol.txDistance(distance, km)
            val whole: Int = cleanDistance.toInt()
            val tenth: Int = Math.round((cleanDistance - whole) * 10f)
            frame[6] = ((whole shr 8) and 0xff).toByte()
            frame[7] = (whole and 0xff).toByte()
            frame[8] = (tenth and 0xff).toByte()
        }
        frame[9] = (if (km) 1 else 0).toByte()
        return AdapterProtocol.checksum(frame)
    }

    @JvmStatic
    fun etaTime(hour: Int, minute: Int): ByteArray? {
        val frame: ByteArray =
            byteArrayOf(
                0xBB.toByte(),
                0x41,
                0xA1.toByte(),
                0x08,
                AdapterProtocol.CMD_ETA_TIME.toByte(),
                AdapterProtocol.clamp(hour, 0, 23).toByte(),
                AdapterProtocol.clamp(minute, 0, 59).toByte(),
                0,
            )
        return AdapterProtocol.checksum(frame)
    }

    @JvmStatic
    fun navText(value: String?): ByteArray? {
        if (value.isNullOrEmpty()) {
            return AdapterProtocol.checksum(
                byteArrayOf(
                    0xBB.toByte(),
                    0x41,
                    0xA1.toByte(),
                    0x09,
                    AdapterProtocol.CMD_NAV_TEXT.toByte(),
                    0xF0.toByte(),
                    0,
                    0,
                    0,
                )
            )
        }
        val text = value.trim().toByteArray(StandardCharsets.UTF_16LE)
        val length: Int = Math.min(text.size, AdapterProtocol.NAV_TEXT_MAX_UTF16_BYTES) and 1.inv()
        val frame: ByteArray = ByteArray(length + 7)
        frame[0] = 0xBB.toByte()
        frame[1] = 0x41
        frame[2] = 0xA1.toByte()
        frame[3] = frame.size.toByte()
        frame[4] = AdapterProtocol.CMD_NAV_TEXT.toByte()
        frame[5] = 0xF0.toByte()
        System.arraycopy(text, 0, frame, 6, length)
        return AdapterProtocol.checksum(frame)
    }

    @JvmStatic
    fun maneuver(imageId: String?, distance: Float, km: Boolean): ByteArray? {
        return AdapterProtocol.maneuver(imageId, distance, km, false)
    }

    @JvmStatic
    fun maneuver(imageId: String?, distance: Float, km: Boolean, tbt: Boolean): ByteArray? {
        return AdapterProtocol.maneuver(imageId, distance, km, tbt, -1)
    }

    @JvmStatic
    fun maneuver(
        imageId: String?,
        distance: Float,
        km: Boolean,
        tbt: Boolean,
        progressBucket: Int,
    ): ByteArray? {
        val frame: ByteArray =
            byteArrayOf(
                0xBB.toByte(),
                0x41,
                0xA1.toByte(),
                0x0E,
                AdapterProtocol.CMD_MANEUVER.toByte(),
                0x0D,
                0,
                0,
                0x09,
                0,
                0,
                0,
                0,
                0,
            )
        if (tbt) {
            frame[5] = 0
            frame[6] = 0
            frame[7] = 0
            frame[8] = 0
            AdapterProtocol.applyTbtIcon(frame, imageId)
        } else if (AdapterProtocol.applyExactIcon(frame, imageId)) {
            // Exact composite mappings verified on the real KIA cluster.
        } else {
            AdapterProtocol.applyClassicIcon(frame, imageId)
        }
        if (distance > 0f) {
            val cleanDistance: Float = AdapterProtocol.txDistance(distance, km)
            val whole: Int = cleanDistance.toInt()
            frame[9] = ((whole shr 8) and 0xff).toByte()
            frame[10] = (whole and 0xff).toByte()
            frame[11] = (if (km) 0x10 else 0x00).toByte()
            val progress: Int =
                if (progressBucket >= 0) progressBucket
                else AdapterProtocol.defaultProgressBucket(cleanDistance, km)
            frame[12] = AdapterProtocol.progressByte(progress, cleanDistance)
        }
        return AdapterProtocol.checksum(frame)
    }

    @JvmStatic
    fun maneuverWithGrayRoad(
        imageId: String?,
        grayRoadId: String?,
        distance: Float,
        km: Boolean,
        tbt: Boolean,
        progressBucket: Int,
    ): ByteArray? {
        val frame = AdapterProtocol.maneuver(imageId, distance, km, tbt, progressBucket)
        if (!tbt) AdapterProtocol.applyGrayRoadBytes(frame!!, grayRoadId)
        return AdapterProtocol.checksum(frame)
    }

    @JvmStatic
    fun directionToFinish(uiStep: Int, distance: Float, km: Boolean): ByteArray? {
        val angle: Int = AdapterProtocol.calibratedFinishDirectionAngle(uiStep)
        return AdapterProtocol.maneuverExact(0x02, 0x00, 0x00, angle, distance, km, -1)
    }

    @JvmStatic
    fun maneuverExact(
        b5: Int,
        b6: Int,
        b7: Int,
        b8: Int,
        distance: Float,
        km: Boolean,
        progressBucket: Int,
    ): ByteArray? {
        val frame: ByteArray =
            byteArrayOf(
                0xBB.toByte(),
                0x41,
                0xA1.toByte(),
                0x0E,
                AdapterProtocol.CMD_MANEUVER.toByte(),
                AdapterProtocol.clamp(b5, 0, 255).toByte(),
                AdapterProtocol.clamp(b6, 0, 255).toByte(),
                AdapterProtocol.clamp(b7, 0, 255).toByte(),
                AdapterProtocol.clamp(b8, 0, 255).toByte(),
                0,
                0,
                0,
                0,
                0,
            )
        if (distance > 0f) {
            val cleanDistance: Float = AdapterProtocol.txDistance(distance, km)
            val whole: Int = cleanDistance.toInt()
            frame[9] = ((whole shr 8) and 0xff).toByte()
            frame[10] = (whole and 0xff).toByte()
            frame[11] = (if (km) 0x10 else 0x00).toByte()
            val progress: Int =
                if (progressBucket >= 0) progressBucket
                else AdapterProtocol.defaultProgressBucket(cleanDistance, km)
            frame[12] = AdapterProtocol.progressByte(progress, cleanDistance)
        }
        return AdapterProtocol.checksum(frame)
    }

    @JvmStatic
    @kotlin.jvm.JvmOverloads
    fun compassStep(uiStep: Int, kiaNaviFormat: Boolean = false): ByteArray? {
        val fullCircle: Int = if (kiaNaviFormat) 48 else 36
        val ui: Int = AdapterProtocol.nearestCompassUiStep(uiStep, fullCircle)
        val sent: Int = (fullCircle - ui) % fullCircle
        val frame: ByteArray =
            byteArrayOf(
                0xBB.toByte(),
                0x41,
                0xA1.toByte(),
                0x0E,
                AdapterProtocol.CMD_MANEUVER.toByte(),
                0x08,
                0x00,
                0x00,
                sent.toByte(),
                0x00,
                0x00,
                0x00,
                0x00,
                0x00,
            )
        if (!kiaNaviFormat) {
            frame[10] = 0x78
            frame[12] = 0xA0.toByte()
        }
        return AdapterProtocol.checksum(frame)
    }

    @JvmStatic
    fun requestAmpSettings(): ByteArray? {
        return AdapterProtocol.checksum(
            byteArrayOf(
                0xBB.toByte(),
                0x41,
                0xA1.toByte(),
                0x07,
                AdapterProtocol.CMD_AMP.toByte(),
                0x01,
                0x00,
            )
        )
    }

    @JvmStatic
    fun ampSettings(
        volume: Int,
        balance: Int,
        fader: Int,
        bass: Int,
        mid: Int,
        treble: Int,
        mode: Int,
        save: Boolean,
    ): ByteArray? {
        val data: ByteArray = ByteArray(15)
        data[0] = 0xBB.toByte()
        data[1] = 0x41
        data[2] = 0xA1.toByte()
        data[3] = 15
        data[4] = AdapterProtocol.CMD_AMP.toByte()
        data[5] = (if (save) 0x00 else 0x02).toByte()
        data[6] = AdapterProtocol.clamp(volume, 0, 40).toByte()
        data[7] = AdapterProtocol.clamp(balance, 0, 20).toByte()
        data[8] = AdapterProtocol.clamp(fader, 0, 20).toByte()
        data[9] = AdapterProtocol.clamp(bass, 0, 20).toByte()
        data[10] = AdapterProtocol.clamp(mid, 0, 20).toByte()
        data[11] = AdapterProtocol.clamp(treble, 0, 20).toByte()
        data[12] = AdapterProtocol.clamp(mode, 0, 32).toByte()
        data[13] = 0
        return AdapterProtocol.checksum(data)
    }

    @JvmStatic
    fun firmwareStart(): ByteArray? {
        return AdapterProtocol.frame(AdapterProtocol.CMD_FIRMWARE, 0x01)
    }

    @JvmStatic
    fun firmwareFinish(): ByteArray? {
        return AdapterProtocol.frame(AdapterProtocol.CMD_FIRMWARE, 0x00)
    }

    @JvmStatic
    fun firmwareBlock(offset: Int, block: ByteArray?): ByteArray? {
        val frame: ByteArray = ByteArray(25)
        frame[0] = 0xBB.toByte()
        frame[1] = 0x41
        frame[2] = 0xA1.toByte()
        frame[3] = 25
        frame[4] = AdapterProtocol.CMD_FIRMWARE.toByte()
        frame[5] = ((offset shr 16) and 0xff).toByte()
        frame[6] = ((offset shr 8) and 0xff).toByte()
        frame[7] = (offset and 0xff).toByte()
        Arrays.fill(frame, 8, 24, 0xff.toByte())
        if (block != null) System.arraycopy(block, 0, frame, 8, Math.min(16, block.size))
        return AdapterProtocol.checksum(frame)
    }

    @JvmStatic
    fun checksum(frame: ByteArray?): ByteArray? {
        if (frame == null || frame.size == 0) return frame
        val len: Int = if (frame.size > 3) frame.get(3).toInt() and 0xff else frame.size
        var sum: Int = 0
        var i: Int = 0
        while (i < len - 1 && i < frame.size) {
            sum += frame.get(i).toInt() and 0xff
            i++
        }
        if (len > 0 && len <= frame.size) frame[len - 1] = (sum and 0xff).toByte()
        return frame
    }

    @JvmStatic
    fun validChecksum(frame: ByteArray?): Boolean {
        if (frame == null || frame.size < 6) return false
        var sum: Int = 0
        for (i in 0..<frame.size - 1) sum += frame.get(i).toInt() and 0xff
        return (sum and 0xff) == (frame.get(frame.size - 1).toInt() and 0xff)
    }

    @JvmStatic
    fun label(frame: ByteArray?): String {
        if (frame == null || frame.size < 5) return "empty"
        val id: Int = frame.get(4).toInt() and 0xff
        when (id) {
            AdapterProtocol.CMD_RADIO_TEXT -> return "radio/media text"
            AdapterProtocol.CMD_MEDIA_TEXT -> return "media text"
            AdapterProtocol.CMD_USB_TEXT -> return "usb title text"
            AdapterProtocol.CMD_ANDROID_AUTO_TEXT -> return "android auto text"
            AdapterProtocol.CMD_MY_MUSIC_TEXT -> return "my music text"
            AdapterProtocol.CMD_CARPLAY_TEXT -> return "carplay text"
            AdapterProtocol.CMD_AMP -> return "AMP"
            AdapterProtocol.CMD_SPEED_LIMIT -> return "nav speed"
            AdapterProtocol.CMD_MANEUVER -> return "nav maneuver/compass"
            AdapterProtocol.CMD_ETA -> return "nav eta"
            AdapterProtocol.CMD_NAV_ON -> return "nav on/off"
            AdapterProtocol.CMD_ETA_TIME -> return "nav eta time"
            AdapterProtocol.CMD_NAV_TEXT -> return "nav text"
            AdapterProtocol.CMD_FIRMWARE -> return "firmware"
            AdapterProtocol.CMD_VERSION -> return "version/uid"
            AdapterProtocol.CMD_SETTINGS -> return "settings"
            AdapterProtocol.CMD_RAW_STREAM -> return "raw stream"
            AdapterProtocol.CMD_RCTA -> return "RCTA"
            AdapterProtocol.CMD_RAW_CAN -> return "raw can"
            AdapterProtocol.CMD_VEHICLE_SNAPSHOT -> return "vehicle snapshot"
            AdapterProtocol.CMD_RAW_CAN_TX -> return "raw tx"
            AdapterProtocol.CMD_HEALTH -> return "health"
            AdapterProtocol.CMD_TPMS,
            AdapterProtocol.CMD_TPMS_LEGACY -> return "TPMS"
            AdapterProtocol.CMD_SOURCE_STATUS -> return "source/status"
            else -> return "cmd=0x" + Integer.toHexString(id).uppercase(Locale.US)
        }
    }

    @JvmStatic
    fun hex(data: ByteArray?): String {
        if (data == null) return ""
        val out: StringBuilder = StringBuilder()
        for (b: Byte in data) {
            if (out.length > 0) out.append(' ')
            val v: Int = b.toInt() and 0xff
            if (v < 16) out.append('0')
            out.append(Integer.toHexString(v).uppercase(Locale.US))
        }
        return out.toString()
    }

    private fun applyExactIcon(f: ByteArray, id: String?): Boolean {
        when (AdapterProtocol.iconCase(id)) {
            0 -> {
                AdapterProtocol.applyExactBytes(f, 0x03, 0x00, 0x00, 0x00)
                return true
            }
            5 -> {
                AdapterProtocol.applyExactBytes(f, 0x20, 0x08, 0x11, 0xFF)
                return true
            }
            6 -> {
                AdapterProtocol.applyExactBytes(f, 0x20, 0x08, 0x11, 0x0C)
                return true
            }
            7 -> {
                AdapterProtocol.applyExactBytes(f, 0x1F, 0x20, 0x00, 0x24)
                return true
            }
            12 -> {
                AdapterProtocol.applyExactBytes(f, 0x1F, 0x00, 0x03, 0x0C)
                return true
            }
            15 -> {
                AdapterProtocol.applyExactBytes(f, 0x20, 0x08, 0x11, 0x0C)
                return true
            }
            16 -> {
                AdapterProtocol.applyExactBytes(f, 0x20, 0x08, 0x11, 0x00)
                return true
            }
            17 -> {
                AdapterProtocol.applyExactBytes(f, 0x20, 0x08, 0x11, 0x24)
                return true
            }
            18 -> {
                AdapterProtocol.applyExactBytes(f, 0x20, 0x08, 0x11, 0x18)
                return true
            }
            19 -> {
                AdapterProtocol.applyExactBytes(f, 0x20, 0x08, 0x11, 0xFF)
                return true
            }
            20 -> {
                AdapterProtocol.applyExactBytes(f, 0x02, 0x00, 0x00, 0x06)
                return true
            }
            21 -> {
                AdapterProtocol.applyExactBytes(f, 0x0D, 0x00, 0x01, 0x00)
                return true
            }
            22 -> {
                AdapterProtocol.applyExactBytes(f, 0x0D, 0x00, 0x10, 0x00)
                return true
            }
            23 -> {
                AdapterProtocol.applyExactBytes(f, 0x0D, 0x00, 0x11, 0x00)
                return true
            }
            24 -> {
                AdapterProtocol.applyExactBytes(f, 0x0D, 0x08, 0x00, 0x00)
                return true
            }
            25 -> {
                AdapterProtocol.applyExactBytes(f, 0x0D, 0x08, 0x01, 0x00)
                return true
            }
            26 -> {
                AdapterProtocol.applyExactBytes(f, 0x0D, 0x08, 0x10, 0x00)
                return true
            }
            27 -> {
                AdapterProtocol.applyExactBytes(f, 0x0D, 0x08, 0x11, 0x00)
                return true
            }
            28 -> {
                AdapterProtocol.applyExactBytes(f, 0x0D, 0x00, 0x13, 0xFF)
                return true
            }
            29 -> {
                AdapterProtocol.applyExactBytes(f, 0x0D, 0x48, 0x01, 0x01)
                return true
            }
            30 -> {
                AdapterProtocol.applyExactBytes(f, 0x0D, 0x00, 0x03, 0x00)
                return true
            }
            31 -> {
                AdapterProtocol.applyExactBytes(f, 0x0D, 0x20, 0x00, 0x00)
                return true
            }
            else -> return false
        }
    }

    private fun applyExactBytes(f: ByteArray, b5: Int, b6: Int, b7: Int, b8: Int) {
        f[5] = b5.toByte()
        f[6] = b6.toByte()
        f[7] = b7.toByte()
        f[8] = b8.toByte()
    }

    private fun applyGrayRoadBytes(f: ByteArray, id: String?) {
        if (id == null) return
        val family: Int = if ((f.get(5).toInt() and 0xff) == 0x1F) 0x1F else 0x0D
        when (id) {
            "context_ra_gray_straight" -> {
                f[5] = family.toByte()
                f[6] = 0x00
                f[7] = 0x01
            }
            "context_ra_gray_right",
            "context_ra_gray_hard_right" -> {
                f[5] = family.toByte()
                f[6] = 0x00
                f[7] = 0x10
            }
            "context_ra_gray_straight_right" -> {
                f[5] = family.toByte()
                f[6] = 0x00
                f[7] = 0x11
            }
            "context_ra_gray_left",
            "context_ra_gray_hard_left" -> {
                f[5] = family.toByte()
                f[6] = 0x08
                f[7] = 0x00
            }
            "context_ra_gray_straight_left" -> {
                f[5] = family.toByte()
                f[6] = 0x08
                f[7] = 0x01
            }
            "context_ra_gray_left_right" -> {
                f[5] = family.toByte()
                f[6] = 0x08
                f[7] = 0x10
            }
            "context_ra_gray_straight_left_right" -> {
                f[5] = family.toByte()
                f[6] = 0x08
                f[7] = 0x11
            }
            "context_ra_gray_exit_right",
            "context_ra_gray_only_exit_right" -> {
                f[5] = family.toByte()
                f[6] = 0x00
                f[7] = 0x03
            }
            "context_ra_gray_left_exit_right",
            "context_ra_gray_straight_left_exit_right" -> {
                f[5] = family.toByte()
                f[6] = 0x08
                f[7] = 0x03
            }
            "context_ra_gray_exit_left" -> {
                f[5] = family.toByte()
                f[6] = 0x20
                f[7] = 0x00
            }
            "context_ra_gray_straight_exit_left" -> {
                f[5] = family.toByte()
                f[6] = 0x20
                f[7] = 0x01
            }
            "context_ra_gray_exit_left_right" -> {
                f[5] = family.toByte()
                f[6] = 0x20
                f[7] = 0x10
            }
            "context_ra_gray_straight_exit_left_right" -> {
                f[5] = family.toByte()
                f[6] = 0x20
                f[7] = 0x11
            }
            else -> {}
        }
    }

    private fun applyClassicIcon(f: ByteArray, id: String?) {
        when (AdapterProtocol.iconCase(id)) {
            0 -> {
                f[5] = 0x03
                f[6] = 0
                f[7] = 0
                f[8] = 0
            }
            1 -> {
                f[5] = 0x14
                f[6] = 0x00
                f[7] = 0x00
                f[8] = 0x00
            }
            2,
            3 -> {
                f[8] = 0
                f[7] = 1
            }
            4 -> {
                f[7] = 0x00
                f[8] = 0x0F
            }
            5 -> {
                f[5] = 0x20
                f[6] = 0x08
                f[7] = 0x11
                f[8] = 0xFF.toByte()
            }
            6 -> {
                f[5] = 0x20
                f[6] = 0x08
                f[7] = 0x11
                f[8] = 0x0C
            }
            7 -> {
                f[5] = 0x14
                f[6] = 0x00
                f[7] = 0x00
                f[8] = 0x00
            }
            8 -> {
                f[6] = 0x00
                f[7] = 0x00
                f[8] = 0x27
            }
            9 -> {
                f[5] = 0x13
                f[6] = 0x00
                f[7] = 0x00
                f[8] = 0x00
            }
            10 -> {
                f[6] = 0x00
                f[7] = 0
                f[8] = 0x24
            }
            11 -> {
                f[6] = 0
                f[7] = 0x00
                f[8] = 0x0C
            }
            12 -> {
                f[5] = 0x13
                f[6] = 0x00
                f[7] = 0x00
                f[8] = 0x00
            }
            13 -> {
                f[8] = 0x0C
                f[5] = 0x1F
                f[6] = 0x00
                f[7] = 0x03
            }
            14 -> {
                f[8] = 0x24
                f[5] = 0x1F
                f[6] = 0x03
                f[7] = 0x01
            }
            else -> f[5] = 0x09
        }
    }

    private fun applyTbtIcon(f: ByteArray, id: String?) {
        when (AdapterProtocol.iconCase(id)) {
            0 -> f[5] = 0x70
            1 -> f[5] = 0x47
            2 -> f[5] = 0xB0.toByte()
            3 -> f[5] = 0x41
            4 -> f[5] = 0x44
            5 -> f[5] = 0x60
            6 -> f[5] = 0x61
            7 -> f[5] = 0x95.toByte()
            8 -> f[5] = 0x45
            9 -> f[5] = 0x42
            10 -> f[5] = 0x46
            11 -> f[5] = 0x43
            12 -> f[5] = 0x93.toByte()
            13 -> f[5] = 0x49
            14 -> f[5] = 0x48
            15,
            16,
            17,
            18 -> // The one-byte TBT glyph cannot carry the selected exit. Use the verified
                // composite cluster frame so the gray circle keeps its yellow exit branch.
                AdapterProtocol.applyExactIcon(f, id)
            else -> f[5] = 0x09
        }
    }

    private fun iconCase(id: String?): Int {
        if (id == null) return -1
        when (id) {
            "context_ra_finish" -> return 0
            "context_ra_take_left" -> return 1
            "context_ra_boardferry" -> return 2
            "context_ra_forward" -> return 3
            "context_ra_hard_turn_right" -> return 4
            "context_ra_in_circular_movement" -> return 5
            "context_ra_out_circular_movement" -> return 6
            "context_ra_exit_left" -> return 7
            "context_ra_hard_turn_left" -> return 8
            "context_ra_take_right" -> return 9
            "context_ra_turn_left" -> return 10
            "context_ra_turn_right" -> return 11
            "context_ra_exit_right" -> return 12
            "context_ra_turn_back_right" -> return 13
            "context_ra_turn_back",
            "context_ra_turn_back_left" -> return 14
            "context_ra_roundabout_exit_1" -> return 15
            "context_ra_roundabout_exit_2" -> return 16
            "context_ra_roundabout_exit_3" -> return 17
            "context_ra_roundabout_exit_4" -> return 18
            "context_ra_roundabout_gray_4_exits" -> return 19
            "context_ra_direction_to_finish" -> return 20
            "context_ra_gray_straight" -> return 21
            "context_ra_gray_right",
            "context_ra_gray_hard_right" -> return 22
            "context_ra_gray_straight_right" -> return 23
            "context_ra_gray_left",
            "context_ra_gray_hard_left" -> return 24
            "context_ra_gray_straight_left" -> return 25
            "context_ra_gray_left_right",
            "context_ra_gray_left_exit_right",
            "context_ra_gray_exit_left_right" -> return 26
            "context_ra_gray_straight_left_right",
            "context_ra_gray_straight_left_exit_right",
            "context_ra_gray_straight_exit_left_right" -> return 27
            "context_ra_gray_straight_right_multi" -> return 28
            "context_ra_gray_straight_left_multi" -> return 29
            "context_ra_gray_exit_right",
            "context_ra_gray_only_exit_right" -> return 30
            "context_ra_gray_exit_left",
            "context_ra_gray_straight_exit_left" -> return 31
            else -> return -1
        }
    }

    private fun nearestCompassUiStep(value: Int, fullCircle: Int): Int {
        var out: Int = ((value % fullCircle) + fullCircle) % fullCircle
        out = Math.round(out / 3f) * 3
        return if (out == fullCircle) 0 else out
    }

    private fun calibratedFinishDirectionAngle(uiStep: Int): Int {
        var out: Int = ((uiStep % 48) + 48) % 48
        out = Math.round(out / 3f) * 3
        return if (out == 48) 0 else out
    }

    private fun nearestManeuverAngle(value: Int): Int {
        val out: Int = ((value % 48) + 48) % 48
        return if (out == 48) 0 else out
    }

    private fun raiseSourceStatus(kind: MediaSourceKind?): ByteArray {
        if (kind == MediaSourceKind.USB_MUSIC) {
            return AdapterProtocol.raiseSourceValue(0x16, "")
        }
        if (kind == MediaSourceKind.BLUETOOTH_AUDIO) {
            return AdapterProtocol.raiseSourceValue(0x0B, "")
        }
        if (kind == MediaSourceKind.AM_RADIO) {
            return AdapterProtocol.raiseSourceValue(0x09, "")
        }
        if (kind == MediaSourceKind.BT_PHONE) {
            return AdapterProtocol.raiseSourceValue(0x07, "")
        }
        if (kind == MediaSourceKind.ANDROID_AUTO) {
            return AdapterProtocol.raiseSourceValue(0x23, "")
        }
        if (kind == MediaSourceKind.CARPLAY) {
            return AdapterProtocol.raiseSourceValue(0x25, "")
        }
        if (kind == MediaSourceKind.MY_MUSIC) {
            return AdapterProtocol.raiseSourceValue(0x24, "")
        }
        return AdapterProtocol.raiseSourceValue(0x81, "")
    }

    private fun raiseFmSourceStatus(detail: String?): ByteArray {
        val mhz10: Int = AdapterProtocol.fmMhz10(detail)
        val whole: Int = mhz10 / 10
        val decimalTens: Int = (mhz10 % 10) * 10
        return AdapterProtocol.raiseFrame(
            byteArrayOf(0x08, 0x09, 0x02, 0x00, whole.toByte(), decimalTens.toByte(), 0x00)
        )
    }

    private fun raiseAmSourceStatus(detail: String?): ByteArray {
        val khz: Int = AdapterProtocol.amKhz(detail)
        return AdapterProtocol.raiseFrame(
            byteArrayOf(
                0x08,
                0x09,
                0x09,
                0x04,
                ((khz shr 8) and 0xff).toByte(),
                (khz and 0xff).toByte(),
                0x00,
            )
        )
    }

    private fun raiseSourceValue(source: Int, detail: String?): ByteArray {
        val value: Int = AdapterProtocol.clamp(source, 0, 255)
        if (value == 0x02) return AdapterProtocol.raiseFmSourceStatus(detail)
        if (value == 0x09) return AdapterProtocol.raiseAmSourceStatus(detail)
        if (value == 0x0B)
            return AdapterProtocol.raiseFrame(byteArrayOf(0x06, 0x09, 0x0B, 0x00, 0x00))
        if (value == 0x11)
            return AdapterProtocol.raiseFrame(byteArrayOf(0x06, 0x09, 0x11, 0x00, 0x00))
        if (value == 0x07)
            return AdapterProtocol.raiseFrame(byteArrayOf(0x06, 0x09, 0x07, 0x01, 0x00))
        if (value == 0x25)
            return AdapterProtocol.raiseFrame(byteArrayOf(0x06, 0x09, 0x25, 0x00, 0x00))
        if (value == 0x80)
            return AdapterProtocol.raiseFrame(byteArrayOf(0x06, 0x09, 0x81.toByte(), 0x00, 0x00))
        val second: Int = AdapterProtocol.nextMediaSecond()
        if (value == 0x16) {
            return AdapterProtocol.raiseFrame(
                byteArrayOf(0x0A, 0x09, 0x16, 0x00, 0x01, 0x00, 0x00, second.toByte(), 0x00)
            )
        }
        return AdapterProtocol.raiseFrame(
            byteArrayOf(0x0A, 0x09, value.toByte(), 0x00, 0x00, 0x00, 0x00, second.toByte(), 0x00)
        )
    }

    @JvmStatic
    fun raiseFrame(payload: ByteArray): ByteArray {
        val frame: ByteArray = ByteArray(payload.size + 2)
        frame[0] = 0xFD.toByte()
        System.arraycopy(payload, 0, frame, 1, payload.size)
        var sum: Int = 0
        for (i in 1..<frame.size - 1) sum += frame.get(i).toInt() and 0xff
        frame[frame.size - 1] = (sum and 0xff).toByte()
        return frame
    }

    @kotlin.jvm.Synchronized
    private fun nextMediaSecond(): Int {
        AdapterProtocol.mediaSecond = (AdapterProtocol.mediaSecond + 1) % 60
        return AdapterProtocol.mediaSecond
    }

    private fun fmMhz10(value: String?): Int {
        val text: String = if (value == null) "" else value
        var start: Int = 0
        while (start < text.length) {
            if (!Character.isDigit(text.get(start))) {
                start++
                continue
            }
            var end: Int = start
            var separator: Boolean = false
            while (end < text.length) {
                val c: Char = text.get(end)
                if (Character.isDigit(c)) {
                    end++
                } else if ((c == '.' || c == ',') && !separator) {
                    separator = true
                    end++
                } else {
                    break
                }
            }
            val mhz10: Int = AdapterProtocol.parseMhz10(text.substring(start, end))
            if (mhz10 >= 600 && mhz10 <= 1200) return mhz10
            start = Math.max(start, end - 1)
            start++
        }
        return 1010
    }

    private fun parseMhz10(value: String?): Int {
        val text = if (value == null) "" else value.replace(',', '.')
        try {
            val dot: Int = text.indexOf('.')
            if (dot < 0) return Integer.parseInt(text) * 10
            val whole: Int = Integer.parseInt(text.substring(0, dot))
            var tenth: Int = 0
            val next: Int = dot + 1
            if (next < text.length && Character.isDigit(text.get(next))) {
                tenth = text.get(next) - '0'
            }
            return whole * 10 + tenth
        } catch (ignored: Exception) {
            return -1
        }
    }

    private fun amKhz(value: String?): Int {
        val text: String = if (value == null) "" else value
        var start: Int = 0
        while (start < text.length) {
            if (!Character.isDigit(text.get(start))) {
                start++
                continue
            }
            var end: Int = start
            while (end < text.length && Character.isDigit(text.get(end))) end++
            try {
                val khz: Int = Integer.parseInt(text.substring(start, end))
                if (khz > 0 && khz <= 0xffff) return khz
            } catch (ignored: Exception) {}
            start = Math.max(start, end - 1)
            start++
        }
        return 24
    }

    private fun trim(value: String?, maxChars: Int): String {
        val text = if (value == null) "" else value.trim()
        return if (text.length > maxChars) text.substring(0, maxChars) else text
    }

    private fun clamp(value: Int, min: Int, max: Int): Int {
        return Math.max(min, Math.min(max, value))
    }

    private fun defaultProgressBucket(distance: Float, km: Boolean): Int {
        return 0
    }

    @JvmStatic
    fun txProgressBucket(progress: Int): Int {
        return AdapterProtocol.clamp(progress, 0, 9)
    }

    private fun txDistance(distance: Float, km: Boolean): Float {
        if (distance <= 0f || km) return distance
        return Math.max(10f, Math.round(distance / 10f) * 10f)
    }

    private fun progressByte(progress: Int, distance: Float): Byte {
        val positive: Float = Math.max(0f, distance)
        val whole: Int = positive.toInt()
        val tenth: Int = Math.round((positive - whole) * 10f) and 0x0f
        return (((AdapterProtocol.txProgressBucket(progress) and 0x0f) shl 4) or tenth).toByte()
    }
}
