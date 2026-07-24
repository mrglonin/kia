package kia.app.media.domain;

import java.util.Locale;

import kia.app.protocol.adapter.MediaSourceKind;

/**
 * Source state observed from the head unit through adapter command {@code 0x7A}.
 *
 * <p>The real source is authoritative only for the UART Real profile. Keeping this parser free of
 * Android dependencies makes the byte layout and source mapping regression-testable.</p>
 */
public final class RealMediaSourceStatus {
    public final int rawSource;
    public final MediaSourceKind kind;
    public final String source;
    public final String frequency;
    public final boolean off;

    private RealMediaSourceStatus(int rawSource, MediaSourceKind kind, String source,
                                  String frequency, boolean off) {
        this.rawSource = rawSource;
        this.kind = kind;
        this.source = source;
        this.frequency = frequency;
        this.off = off;
    }

    public static RealMediaSourceStatus parse(byte[] frame) {
        if (frame == null || frame.length < 6) return null;
        int sourceIndex;
        int firstIndex;
        int secondIndex;
        if (u8(frame, 5) == 0xFD) {
            if (frame.length < 13) return null;
            sourceIndex = 8;
            firstIndex = 10;
            secondIndex = 11;
        } else {
            if (frame.length < 8) return null;
            sourceIndex = 5;
            firstIndex = 6;
            secondIndex = 7;
        }

        int raw = u8(frame, sourceIndex);
        int first = u8(frame, firstIndex);
        int second = u8(frame, secondIndex);
        switch (raw) {
            case 0x02:
                return new RealMediaSourceStatus(raw, MediaSourceKind.FM_RADIO, "FM",
                        fmFrequency(first, second), false);
            case 0x09:
                return new RealMediaSourceStatus(raw, MediaSourceKind.AM_RADIO, "AM",
                        amFrequency(first, second), false);
            case 0x0B:
                return new RealMediaSourceStatus(raw, MediaSourceKind.BLUETOOTH_AUDIO,
                        "Bluetooth", "", false);
            case 0x16:
                return new RealMediaSourceStatus(raw, MediaSourceKind.USB_MUSIC,
                        "USB", "", false);
            case 0x07:
            case 0x0E:
                return new RealMediaSourceStatus(raw, MediaSourceKind.BT_PHONE,
                        "BT phone", "", false);
            case 0x23:
                return new RealMediaSourceStatus(raw, MediaSourceKind.ANDROID_AUTO,
                        "Android Auto", "", false);
            case 0x24:
                return new RealMediaSourceStatus(raw, MediaSourceKind.MY_MUSIC,
                        "My Music", "", false);
            case 0x25:
                return new RealMediaSourceStatus(raw, MediaSourceKind.CARPLAY,
                        "CarPlay", "", false);
            case 0x80:
            case 0x81:
                return new RealMediaSourceStatus(raw, MediaSourceKind.GENERIC_MUSIC,
                        "Off", "", true);
            case 0x11:
                return new RealMediaSourceStatus(raw, MediaSourceKind.GENERIC_MUSIC,
                        "AUX", "", false);
            default:
                return new RealMediaSourceStatus(raw, MediaSourceKind.GENERIC_MUSIC,
                        String.format(Locale.US, "UART 0x%02X", raw), "", false);
        }
    }

    public boolean radio() {
        return kind == MediaSourceKind.FM_RADIO || kind == MediaSourceKind.AM_RADIO;
    }

    public String key() {
        return rawSource + "|" + kind.name() + "|" + (radio() ? frequency : "");
    }

    private static String fmFrequency(int first, int second) {
        if (first <= 0) return "";
        int decimal = Math.max(0, Math.min(9, second / 10));
        return first + "." + decimal;
    }

    private static String amFrequency(int first, int second) {
        int khz = (first << 8) | second;
        return khz <= 0 ? "" : String.valueOf(khz);
    }

    private static int u8(byte[] frame, int index) {
        return index >= 0 && index < frame.length ? frame[index] & 0xff : 0;
    }
}
