package kia.app.media.domain

import java.util.Locale
import kia.app.protocol.adapter.MediaSourceKind

/**
 * Source state observed from the head unit through adapter command `0x7A`.
 *
 * The real source is authoritative only for the UART Real profile. Keeping this parser free of
 * Android dependencies makes the byte layout and source mapping regression-testable.
 */
class RealMediaSourceStatus
private constructor(
    rawSource: Int,
    kind: MediaSourceKind?,
    source: String?,
    frequency: String?,
    off: Boolean,
) {
    @JvmField val rawSource: Int
    @JvmField val kind: MediaSourceKind?
    @JvmField val source: String?
    @JvmField val frequency: String?
    @JvmField val off: Boolean

    init {
        this.rawSource = rawSource
        this.kind = kind
        this.source = source
        this.frequency = frequency
        this.off = off
    }

    fun radio(): Boolean {
        return kind == MediaSourceKind.FM_RADIO || kind == MediaSourceKind.AM_RADIO
    }

    fun key(): String? {
        return rawSource.toString() + "|" + kind!!.name + "|" + (if (radio()) frequency else "")
    }

    companion object {
        @JvmStatic
        fun parse(frame: ByteArray?): RealMediaSourceStatus? {
            if (frame == null || frame.size < 6) return null
            val sourceIndex: Int
            val firstIndex: Int
            val secondIndex: Int
            if (RealMediaSourceStatus.u8(frame, 5) == 0xFD) {
                if (frame.size < 13) return null
                sourceIndex = 8
                firstIndex = 10
                secondIndex = 11
            } else {
                if (frame.size < 8) return null
                sourceIndex = 5
                firstIndex = 6
                secondIndex = 7
            }

            val raw: Int = RealMediaSourceStatus.u8(frame, sourceIndex)
            val first: Int = RealMediaSourceStatus.u8(frame, firstIndex)
            val second: Int = RealMediaSourceStatus.u8(frame, secondIndex)
            when (raw) {
                0x02 ->
                    return RealMediaSourceStatus(
                        raw,
                        MediaSourceKind.FM_RADIO,
                        "FM",
                        RealMediaSourceStatus.fmFrequency(first, second),
                        false,
                    )
                0x09 ->
                    return RealMediaSourceStatus(
                        raw,
                        MediaSourceKind.AM_RADIO,
                        "AM",
                        RealMediaSourceStatus.amFrequency(first, second),
                        false,
                    )
                0x0B ->
                    return RealMediaSourceStatus(
                        raw,
                        MediaSourceKind.BLUETOOTH_AUDIO,
                        "Bluetooth",
                        "",
                        false,
                    )
                0x16 ->
                    return RealMediaSourceStatus(raw, MediaSourceKind.USB_MUSIC, "USB", "", false)
                0x07,
                0x0E ->
                    return RealMediaSourceStatus(
                        raw,
                        MediaSourceKind.BT_PHONE,
                        "BT phone",
                        "",
                        false,
                    )
                0x23 ->
                    return RealMediaSourceStatus(
                        raw,
                        MediaSourceKind.ANDROID_AUTO,
                        "Android Auto",
                        "",
                        false,
                    )
                0x24 ->
                    return RealMediaSourceStatus(
                        raw,
                        MediaSourceKind.MY_MUSIC,
                        "My Music",
                        "",
                        false,
                    )
                0x25 ->
                    return RealMediaSourceStatus(raw, MediaSourceKind.CARPLAY, "CarPlay", "", false)
                0x80,
                0x81 ->
                    return RealMediaSourceStatus(
                        raw,
                        MediaSourceKind.GENERIC_MUSIC,
                        "Off",
                        "",
                        true,
                    )
                0x11 ->
                    return RealMediaSourceStatus(
                        raw,
                        MediaSourceKind.GENERIC_MUSIC,
                        "AUX",
                        "",
                        false,
                    )
                else ->
                    return RealMediaSourceStatus(
                        raw,
                        MediaSourceKind.GENERIC_MUSIC,
                        String.format(Locale.US, "UART 0x%02X", raw),
                        "",
                        false,
                    )
            }
        }

        private fun fmFrequency(first: Int, second: Int): String? {
            if (first <= 0) return ""
            val decimal: Int = Math.max(0, Math.min(9, second / 10))
            return first.toString() + "." + decimal
        }

        private fun amFrequency(first: Int, second: Int): String? {
            val khz: Int = (first shl 8) or second
            return if (khz <= 0) "" else java.lang.String.valueOf(khz)
        }

        private fun u8(frame: ByteArray, index: Int): Int {
            return if (index >= 0 && index < frame.size) frame.get(index).toInt() and 0xff else 0
        }
    }
}
