package kia.app.protocol.adapter

import java.util.Locale

enum class MediaSourceKind {
    USB_MUSIC,
    BLUETOOTH_AUDIO,
    FM_RADIO,
    AM_RADIO,
    BT_PHONE,
    ANDROID_AUTO,
    CARPLAY,
    MY_MUSIC,
    CLOUD_MUSIC,
    GENERIC_MUSIC;

    fun defaultLabel(): String {
        when (this) {
            MediaSourceKind.BLUETOOTH_AUDIO -> return "Bluetooth"
            MediaSourceKind.FM_RADIO -> return "FM"
            MediaSourceKind.AM_RADIO -> return "AM"
            MediaSourceKind.BT_PHONE -> return "BT phone"
            MediaSourceKind.ANDROID_AUTO -> return "Android Auto"
            MediaSourceKind.CARPLAY -> return "CarPlay"
            MediaSourceKind.MY_MUSIC -> return "My Music"
            MediaSourceKind.CLOUD_MUSIC -> return "Cloud music"
            MediaSourceKind.USB_MUSIC -> return "USB"
            MediaSourceKind.GENERIC_MUSIC -> return "Music"
            else -> return "Music"
        }
    }

    companion object {
        @JvmStatic
        fun from(source: String?, packageName: String?): MediaSourceKind {
            val text =
                ((if (source == null) "" else source) +
                        " " +
                        (if (packageName == null) "" else packageName))
                    .lowercase(Locale.US)
            if (
                text.contains("android auto") ||
                    text.contains("android_auto") ||
                    text.contains("androidauto")
            ) {
                return MediaSourceKind.ANDROID_AUTO
            }
            if (text.contains("carplay") || text.contains("car play")) {
                return MediaSourceKind.CARPLAY
            }
            if (
                text.contains("my music") ||
                    text.contains("mymusic") ||
                    text.contains("teyes media") ||
                    text.contains("teyes music")
            ) {
                return MediaSourceKind.MY_MUSIC
            }
            if (text.contains("phone") || text.contains("телефон")) return MediaSourceKind.BT_PHONE
            if (
                text.contains("bluetooth") ||
                    text.contains("btmusic") ||
                    text.contains("a2dp") ||
                    text.contains("avrcp") ||
                    text.contains("com.spd.bluetooth")
            ) {
                return MediaSourceKind.BLUETOOTH_AUDIO
            }
            if (
                text.equals("am") ||
                    text.startsWith("am ") ||
                    text.contains("am радио") ||
                    text.contains("am radio")
            ) {
                return MediaSourceKind.AM_RADIO
            }
            if (
                text.contains("fm") ||
                    text.contains("радио") ||
                    text.contains("radio") ||
                    text.contains("com.spd.radio")
            ) {
                return MediaSourceKind.FM_RADIO
            }
            if (
                text.contains("интернет") ||
                    text.contains("network radio") ||
                    text.contains("net radio") ||
                    text.contains("s-radio") ||
                    text.contains("s radio")
            ) {
                return MediaSourceKind.CLOUD_MUSIC
            }
            if (
                text.contains("яндекс") ||
                    text.contains("yandex") ||
                    text.contains("spotify") ||
                    text.contains("youtube") ||
                    text.contains("cloud") ||
                    text.contains("network")
            ) {
                return MediaSourceKind.CLOUD_MUSIC
            }
            if (text.contains("usb") || text.contains("local") || text.contains("com.spd.media")) {
                return MediaSourceKind.USB_MUSIC
            }
            return MediaSourceKind.GENERIC_MUSIC
        }
    }
}
