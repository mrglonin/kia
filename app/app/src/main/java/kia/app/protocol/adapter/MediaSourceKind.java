package kia.app.protocol.adapter;

import java.util.Locale;

public enum MediaSourceKind {
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

    public static MediaSourceKind from(String source, String packageName) {
        String text = ((source == null ? "" : source) + " " + (packageName == null ? "" : packageName))
                .toLowerCase(Locale.US);
        if (text.contains("android auto") || text.contains("android_auto") || text.contains("androidauto")) {
            return ANDROID_AUTO;
        }
        if (text.contains("carplay") || text.contains("car play")) {
            return CARPLAY;
        }
        if (text.contains("my music") || text.contains("mymusic")
                || text.contains("teyes media") || text.contains("teyes music")) {
            return MY_MUSIC;
        }
        if (text.contains("phone") || text.contains("телефон")) return BT_PHONE;
        if (text.contains("bluetooth") || text.contains("btmusic") || text.contains("a2dp")
                || text.contains("avrcp") || text.contains("com.spd.bluetooth")) {
            return BLUETOOTH_AUDIO;
        }
        if (text.equals("am") || text.startsWith("am ") || text.contains("am радио")
                || text.contains("am radio")) {
            return AM_RADIO;
        }
        if (text.contains("fm") || text.contains("радио") || text.contains("radio")
                || text.contains("com.spd.radio")) {
            return FM_RADIO;
        }
        if (text.contains("интернет") || text.contains("network radio") || text.contains("net radio")
                || text.contains("s-radio") || text.contains("s radio")) {
            return CLOUD_MUSIC;
        }
        if (text.contains("яндекс") || text.contains("yandex") || text.contains("spotify")
                || text.contains("youtube") || text.contains("cloud")
                || text.contains("network")) {
            return CLOUD_MUSIC;
        }
        if (text.contains("usb") || text.contains("local") || text.contains("com.spd.media")) {
            return USB_MUSIC;
        }
        return GENERIC_MUSIC;
    }

    public String defaultLabel() {
        switch (this) {
            case BLUETOOTH_AUDIO:
                return "Bluetooth";
            case FM_RADIO:
                return "FM";
            case AM_RADIO:
                return "AM";
            case BT_PHONE:
                return "BT phone";
            case ANDROID_AUTO:
                return "Android Auto";
            case CARPLAY:
                return "CarPlay";
            case MY_MUSIC:
                return "My Music";
            case CLOUD_MUSIC:
                return "Cloud music";
            case USB_MUSIC:
                return "USB";
            case GENERIC_MUSIC:
            default:
                return "Music";
        }
    }
}
