package kia.app.protocol.adapter;

import android.text.TextUtils;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Locale;

public final class AdapterProtocol {
    public static final int CMD_RADIO_TEXT = 0x20;
    public static final int CMD_MEDIA_TEXT = 0x21;
    public static final int CMD_USB_TEXT = 0x22;
    public static final int CMD_ANDROID_AUTO_TEXT = 0x23;
    public static final int CMD_MY_MUSIC_TEXT = 0x24;
    public static final int CMD_CARPLAY_TEXT = 0x25;
    public static final int CMD_AMP = 0x30;
    public static final int CMD_SPEED_LIMIT = 0x44;
    public static final int CMD_MANEUVER = 0x45;
    public static final int CMD_ETA = 0x47;
    public static final int CMD_NAV_ON = 0x48;
    public static final int CMD_ETA_TIME = 0x49;
    public static final int CMD_NAV_TEXT = 0x4A;
    public static final int CMD_FIRMWARE = 0x55;
    public static final int CMD_VERSION = 0x56;
    public static final int CMD_SETTINGS = 0x60;
    public static final int CMD_RAW_STREAM = 0x70;
    public static final int CMD_RCTA = 0x75;
    public static final int CMD_SUNROOF = 0x76;
    public static final int CMD_RAW_CAN = 0x76;
    public static final int CMD_VEHICLE_SNAPSHOT = 0x77;
    public static final int CMD_RAW_CAN_TX = 0x78;
    public static final int CMD_HEALTH = 0x79;
    public static final int CMD_TPMS = 0x51;
    public static final int CMD_SOURCE_STATUS = 0x7A;
    public static final int CMD_TPMS_LEGACY = 0x7B;
    public static final int SETTINGS_SUB_SAS_RATIO = 0x01;
    public static final int SETTINGS_SUB_TEMP_ENGINE = 0x02;

    private static final int TEXT_MAX_CHARS = 28;
    private static final int NAV_TEXT_MAX_UTF16_BYTES = 56;
    private static int mediaSecond = 1;

    private AdapterProtocol() {
    }

    public static byte[] packet(int id, byte[] payload) {
        int size = payload == null ? 0 : payload.length;
        byte[] data = new byte[6 + size];
        data[0] = (byte) 0xBB;
        data[1] = 0x41;
        data[2] = (byte) 0xA1;
        data[3] = (byte) data.length;
        data[4] = (byte) id;
        if (size > 0) System.arraycopy(payload, 0, data, 5, size);
        return checksum(data);
    }

    public static byte[] frame(int id, int command) {
        byte[] data = new byte[7];
        data[0] = (byte) 0xBB;
        data[1] = 0x41;
        data[2] = (byte) 0xA1;
        data[3] = 7;
        data[4] = (byte) id;
        data[5] = (byte) command;
        return checksum(data);
    }

    public static byte[] frame(int id, int command, int value) {
        byte[] data = new byte[8];
        data[0] = (byte) 0xBB;
        data[1] = 0x41;
        data[2] = (byte) 0xA1;
        data[3] = 8;
        data[4] = (byte) id;
        data[5] = (byte) command;
        data[6] = (byte) value;
        return checksum(data);
    }

    public static byte[] requestAdapterUid() {
        return frame(CMD_VERSION, 0x01);
    }

    public static byte[] requestAdapterVersion() {
        return frame(CMD_VERSION, 0x02);
    }

    public static byte[] requestSettings() {
        return frame(CMD_SETTINGS, 0x00, 0x00);
    }

    public static byte[] sasRatio(int ratio) {
        return frame(CMD_SETTINGS, SETTINGS_SUB_SAS_RATIO, clamp(ratio, 1, 255));
    }

    public static byte[] canbusTemperatureEngine(boolean enabled) {
        return frame(CMD_SETTINGS, SETTINGS_SUB_TEMP_ENGINE, enabled ? 1 : 0);
    }

    public static byte[] requestHealth() {
        return packet(CMD_HEALTH, null);
    }

    public static byte[] requestVehicleSnapshot() {
        return packet(CMD_VEHICLE_SNAPSHOT, null);
    }

    public static byte[] requestTpms() {
        return frame(CMD_TPMS, 0x01);
    }

    public static byte[] rawStream(boolean enabled) {
        return packet(CMD_RAW_STREAM, new byte[]{(byte) (enabled ? 0x01 : 0x00)});
    }

    public static byte[] rawCanPoll() {
        return packet(CMD_RAW_CAN, null);
    }

    public static byte[] rawCanTx(int bus, int flags, int canId, byte[] data) {
        int len = data == null ? 0 : Math.min(8, data.length);
        byte[] payload = new byte[7 + len];
        payload[0] = (byte) bus;
        payload[1] = (byte) flags;
        payload[2] = (byte) (canId & 0xff);
        payload[3] = (byte) ((canId >> 8) & 0xff);
        payload[4] = (byte) ((canId >> 16) & 0xff);
        payload[5] = (byte) ((canId >> 24) & 0xff);
        payload[6] = (byte) len;
        if (len > 0) System.arraycopy(data, 0, payload, 7, len);
        return packet(CMD_RAW_CAN_TX, payload);
    }

    public static byte[] textPacket(int id, String value) {
        return textPacket(id, value, TEXT_MAX_CHARS);
    }

    public static byte[] textPacket(int id, String value, int maxChars) {
        String text = trim(value, maxChars);
        if (TextUtils.isEmpty(text)) {
            return checksum(new byte[]{(byte) 0xBB, 0x41, (byte) 0xA1, 0x08, (byte) id, 0, 0, 0});
        }
        byte[] payload = text.getBytes(StandardCharsets.UTF_16LE);
        int len = payload.length + 6;
        byte[] frame = new byte[len];
        frame[0] = (byte) 0xBB;
        frame[1] = 0x41;
        frame[2] = (byte) 0xA1;
        frame[3] = (byte) len;
        frame[4] = (byte) id;
        System.arraycopy(payload, 0, frame, 5, payload.length);
        return checksum(frame);
    }

    public static byte[] textPacketWithSubtype(int id, int subtype, String value) {
        String text = trim(value, TEXT_MAX_CHARS);
        if (TextUtils.isEmpty(text)) {
            return checksum(new byte[]{(byte) 0xBB, 0x41, (byte) 0xA1, 0x09, (byte) id, (byte) subtype, 0, 0, 0});
        }
        byte[] payload = text.getBytes(StandardCharsets.UTF_16LE);
        int len = Math.min(payload.length, TEXT_MAX_CHARS * 2) + 7;
        byte[] frame = new byte[len];
        frame[0] = (byte) 0xBB;
        frame[1] = 0x41;
        frame[2] = (byte) 0xA1;
        frame[3] = (byte) len;
        frame[4] = (byte) id;
        frame[5] = (byte) subtype;
        System.arraycopy(payload, 0, frame, 6, len - 7);
        return checksum(frame);
    }

    public static byte[] mediaSourceStatus(MediaSourceKind kind, String detail) {
        byte[] payload;
        if (kind == MediaSourceKind.FM_RADIO) {
            payload = raiseFmSourceStatus(detail);
        } else if (kind == MediaSourceKind.AM_RADIO) {
            payload = raiseAmSourceStatus(detail);
        } else {
            payload = raiseSourceStatus(kind);
        }
        return packet(CMD_SOURCE_STATUS, payload);
    }

    public static byte[] mediaSourceStatusRaw(int source, String detail) {
        int value = clamp(source, 0, 255);
        if (value == 0x80) return mediaOffStatus();
        return packet(CMD_SOURCE_STATUS, raiseSourceValue(value, detail));
    }

    public static byte[] mediaFmSourceStatusNoFrequency() {
        return packet(CMD_SOURCE_STATUS, raiseFrame(new byte[]{0x08, 0x09, 0x02, 0x00, 0x00, 0x00, 0x00}));
    }

    public static byte[] mediaOffStatus() {
        return packet(CMD_SOURCE_STATUS, raiseFrame(new byte[]{0x06, 0x09, (byte) 0x81, 0x00, 0x00}));
    }

    public static byte[] navOn(boolean active) {
        byte[] frame = new byte[]{(byte) 0xBB, 0x41, (byte) 0xA1, 0x07, CMD_NAV_ON, (byte) (active ? 1 : 0), 0};
        return checksum(frame);
    }

    public static byte[] speedLimit(int kmh) {
        byte[] frame = new byte[]{(byte) 0xBB, 0x41, (byte) 0xA1, 0x08, CMD_SPEED_LIMIT, 1, (byte) clamp(kmh, 0, 255), 0};
        return checksum(frame);
    }

    public static byte[] etaDistance(float distance, boolean km) {
        byte[] frame = new byte[]{(byte) 0xBB, 0x41, (byte) 0xA1, 0x0B, CMD_ETA, 0, 0, 0, 0, 0, 0};
        if (distance > 0f) {
            float cleanDistance = txDistance(distance, km);
            int whole = (int) cleanDistance;
            int tenth = Math.round((cleanDistance - whole) * 10f);
            frame[6] = (byte) ((whole >> 8) & 0xff);
            frame[7] = (byte) (whole & 0xff);
            frame[8] = (byte) (tenth & 0xff);
        }
        frame[9] = (byte) (km ? 1 : 0);
        return checksum(frame);
    }

    public static byte[] etaTime(int hour, int minute) {
        byte[] frame = new byte[]{(byte) 0xBB, 0x41, (byte) 0xA1, 0x08, CMD_ETA_TIME,
                (byte) clamp(hour, 0, 23), (byte) clamp(minute, 0, 59), 0};
        return checksum(frame);
    }

    public static byte[] navText(String value) {
        if (TextUtils.isEmpty(value)) {
            return checksum(new byte[]{(byte) 0xBB, 0x41, (byte) 0xA1, 0x09, CMD_NAV_TEXT, (byte) 0xF0, 0, 0, 0});
        }
        byte[] text = value.trim().getBytes(StandardCharsets.UTF_16LE);
        int length = Math.min(text.length, NAV_TEXT_MAX_UTF16_BYTES) & ~1;
        byte[] frame = new byte[length + 7];
        frame[0] = (byte) 0xBB;
        frame[1] = 0x41;
        frame[2] = (byte) 0xA1;
        frame[3] = (byte) frame.length;
        frame[4] = CMD_NAV_TEXT;
        frame[5] = (byte) 0xF0;
        System.arraycopy(text, 0, frame, 6, length);
        return checksum(frame);
    }

    public static byte[] maneuver(String imageId, float distance, boolean km) {
        return maneuver(imageId, distance, km, false);
    }

    public static byte[] maneuver(String imageId, float distance, boolean km, boolean tbt) {
        return maneuver(imageId, distance, km, tbt, -1);
    }

    public static byte[] maneuver(String imageId, float distance, boolean km, boolean tbt, int progressBucket) {
        byte[] frame = new byte[]{(byte) 0xBB, 0x41, (byte) 0xA1, 0x0E, CMD_MANEUVER,
                0x0D, 0, 0, 0x09, 0, 0, 0, 0, 0};
        if (tbt) {
            frame[5] = 0;
            frame[6] = 0;
            frame[7] = 0;
            frame[8] = 0;
            applyTbtIcon(frame, imageId);
        } else if (applyExactIcon(frame, imageId)) {
            // Exact cluster mappings found on the real KIA cluster; keep them outside TBT mode.
        } else {
            applyClassicIcon(frame, imageId);
        }
        if (distance > 0f) {
            float cleanDistance = txDistance(distance, km);
            int whole = (int) cleanDistance;
            frame[9] = (byte) ((whole >> 8) & 0xff);
            frame[10] = (byte) (whole & 0xff);
            frame[11] = (byte) (km ? 0x10 : 0x00);
            int progress = progressBucket >= 0 ? progressBucket : defaultProgressBucket(cleanDistance, km);
            frame[12] = progressByte(progress, cleanDistance);
        }
        return checksum(frame);
    }

    public static byte[] maneuverWithGrayRoad(String imageId, String grayRoadId, float distance,
                                              boolean km, boolean tbt, int progressBucket) {
        byte[] frame = maneuver(imageId, distance, km, tbt, progressBucket);
        if (!tbt) applyGrayRoadBytes(frame, grayRoadId);
        return checksum(frame);
    }

    public static byte[] directionToFinish(int uiStep, float distance, boolean km) {
        int angle = calibratedFinishDirectionAngle(uiStep);
        return maneuverExact(0x02, 0x00, 0x00, angle, distance, km, -1);
    }

    public static byte[] maneuverExact(int b5, int b6, int b7, int b8,
                                       float distance, boolean km, int progressBucket) {
        byte[] frame = new byte[]{(byte) 0xBB, 0x41, (byte) 0xA1, 0x0E, CMD_MANEUVER,
                (byte) clamp(b5, 0, 255), (byte) clamp(b6, 0, 255),
                (byte) clamp(b7, 0, 255), (byte) clamp(b8, 0, 255),
                0, 0, 0, 0, 0};
        if (distance > 0f) {
            float cleanDistance = txDistance(distance, km);
            int whole = (int) cleanDistance;
            frame[9] = (byte) ((whole >> 8) & 0xff);
            frame[10] = (byte) (whole & 0xff);
            frame[11] = (byte) (km ? 0x10 : 0x00);
            int progress = progressBucket >= 0 ? progressBucket : defaultProgressBucket(cleanDistance, km);
            frame[12] = progressByte(progress, cleanDistance);
        }
        return checksum(frame);
    }

    public static byte[] compassStep(int uiStep) {
        int ui = nearestCompassUiStep(uiStep);
        int sent = (36 - ui) % 36;
        byte[] frame = new byte[]{(byte) 0xBB, 0x41, (byte) 0xA1, 0x0E, CMD_MANEUVER, 0x08, 0x00, 0x00,
                (byte) sent, 0x00, 0x78, 0x00, (byte) 0xA0, 0x00};
        return checksum(frame);
    }

    public static byte[] requestAmpSettings() {
        return checksum(new byte[]{(byte) 0xBB, 0x41, (byte) 0xA1, 0x07, CMD_AMP, 0x01, 0x00});
    }

    public static byte[] ampSettings(int volume, int balance, int fader, int bass, int mid,
                                     int treble, int mode, boolean save) {
        byte[] data = new byte[15];
        data[0] = (byte) 0xBB;
        data[1] = 0x41;
        data[2] = (byte) 0xA1;
        data[3] = 15;
        data[4] = CMD_AMP;
        data[5] = (byte) (save ? 0x00 : 0x02);
        data[6] = (byte) clamp(volume, 0, 40);
        data[7] = (byte) clamp(balance, 0, 20);
        data[8] = (byte) clamp(fader, 0, 20);
        data[9] = (byte) clamp(bass, 0, 20);
        data[10] = (byte) clamp(mid, 0, 20);
        data[11] = (byte) clamp(treble, 0, 20);
        data[12] = (byte) clamp(mode, 0, 32);
        data[13] = 0;
        return checksum(data);
    }

    public static byte[] firmwareStart() {
        return frame(CMD_FIRMWARE, 0x01);
    }

    public static byte[] firmwareFinish() {
        return frame(CMD_FIRMWARE, 0x00);
    }

    public static byte[] firmwareBlock(int offset, byte[] block) {
        byte[] frame = new byte[25];
        frame[0] = (byte) 0xBB;
        frame[1] = 0x41;
        frame[2] = (byte) 0xA1;
        frame[3] = 25;
        frame[4] = CMD_FIRMWARE;
        frame[5] = (byte) ((offset >> 16) & 0xff);
        frame[6] = (byte) ((offset >> 8) & 0xff);
        frame[7] = (byte) (offset & 0xff);
        Arrays.fill(frame, 8, 24, (byte) 0xff);
        if (block != null) System.arraycopy(block, 0, frame, 8, Math.min(16, block.length));
        return checksum(frame);
    }

    public static byte[] checksum(byte[] frame) {
        if (frame == null || frame.length == 0) return frame;
        int len = frame.length > 3 ? frame[3] & 0xff : frame.length;
        int sum = 0;
        for (int i = 0; i < len - 1 && i < frame.length; i++) sum += frame[i] & 0xff;
        if (len > 0 && len <= frame.length) frame[len - 1] = (byte) (sum & 0xff);
        return frame;
    }

    public static boolean validChecksum(byte[] frame) {
        if (frame == null || frame.length < 6) return false;
        int sum = 0;
        for (int i = 0; i < frame.length - 1; i++) sum += frame[i] & 0xff;
        return (sum & 0xff) == (frame[frame.length - 1] & 0xff);
    }

    public static String label(byte[] frame) {
        if (frame == null || frame.length < 5) return "empty";
        int id = frame[4] & 0xff;
        switch (id) {
            case CMD_RADIO_TEXT:
                return "radio/media text";
            case CMD_MEDIA_TEXT:
                return "media text";
            case CMD_USB_TEXT:
                return "usb title text";
            case CMD_ANDROID_AUTO_TEXT:
                return "android auto text";
            case CMD_MY_MUSIC_TEXT:
                return "my music text";
            case CMD_CARPLAY_TEXT:
                return "carplay text";
            case CMD_AMP:
                return "AMP";
            case CMD_SPEED_LIMIT:
                return "nav speed";
            case CMD_MANEUVER:
                return "nav maneuver/compass";
            case CMD_ETA:
                return "nav eta";
            case CMD_NAV_ON:
                return "nav on/off";
            case CMD_ETA_TIME:
                return "nav eta time";
            case CMD_NAV_TEXT:
                return "nav text";
            case CMD_FIRMWARE:
                return "firmware";
            case CMD_VERSION:
                return "version/uid";
            case CMD_SETTINGS:
                return "settings";
            case CMD_RAW_STREAM:
                return "raw stream";
            case CMD_RCTA:
                return "RCTA";
            case CMD_RAW_CAN:
                return "raw can";
            case CMD_VEHICLE_SNAPSHOT:
                return "vehicle snapshot";
            case CMD_RAW_CAN_TX:
                return "raw tx";
            case CMD_HEALTH:
                return "health";
            case CMD_TPMS:
            case CMD_TPMS_LEGACY:
                return "TPMS";
            case CMD_SOURCE_STATUS:
                return "source/status";
            default:
                return "cmd=0x" + Integer.toHexString(id).toUpperCase(Locale.US);
        }
    }

    public static String hex(byte[] data) {
        if (data == null) return "";
        StringBuilder out = new StringBuilder();
        for (byte b : data) {
            if (out.length() > 0) out.append(' ');
            int v = b & 0xff;
            if (v < 16) out.append('0');
            out.append(Integer.toHexString(v).toUpperCase(Locale.US));
        }
        return out.toString();
    }

    private static boolean applyExactIcon(byte[] f, String id) {
        switch (iconCase(id)) {
            case 0:
                applyExactBytes(f, 0x03, 0x00, 0x00, 0x00);
                return true;
            case 5:
                applyExactBytes(f, 0x20, 0x08, 0x11, 0xFF);
                return true;
            case 6:
                applyExactBytes(f, 0x20, 0x08, 0x11, 0x0C);
                return true;
            case 7:
                applyExactBytes(f, 0x1F, 0x20, 0x00, 0x24);
                return true;
            case 12:
                applyExactBytes(f, 0x1F, 0x00, 0x03, 0x0C);
                return true;
            case 15:
                applyExactBytes(f, 0x20, 0x08, 0x11, 0x0C);
                return true;
            case 16:
                applyExactBytes(f, 0x20, 0x08, 0x11, 0x00);
                return true;
            case 17:
                applyExactBytes(f, 0x20, 0x08, 0x11, 0x24);
                return true;
            case 18:
                applyExactBytes(f, 0x20, 0x08, 0x11, 0x18);
                return true;
            case 19:
                applyExactBytes(f, 0x20, 0x08, 0x11, 0xFF);
                return true;
            case 20:
                applyExactBytes(f, 0x02, 0x00, 0x00, 0x06);
                return true;
            case 21:
                applyExactBytes(f, 0x0D, 0x00, 0x01, 0x00);
                return true;
            case 22:
                applyExactBytes(f, 0x0D, 0x00, 0x10, 0x00);
                return true;
            case 23:
                applyExactBytes(f, 0x0D, 0x00, 0x11, 0x00);
                return true;
            case 24:
                applyExactBytes(f, 0x0D, 0x08, 0x00, 0x00);
                return true;
            case 25:
                applyExactBytes(f, 0x0D, 0x08, 0x01, 0x00);
                return true;
            case 26:
                applyExactBytes(f, 0x0D, 0x08, 0x10, 0x00);
                return true;
            case 27:
                applyExactBytes(f, 0x0D, 0x08, 0x11, 0x00);
                return true;
            case 28:
                applyExactBytes(f, 0x0D, 0x00, 0x13, 0xFF);
                return true;
            case 29:
                applyExactBytes(f, 0x0D, 0x48, 0x01, 0x01);
                return true;
            case 30:
                applyExactBytes(f, 0x0D, 0x00, 0x03, 0x00);
                return true;
            case 31:
                applyExactBytes(f, 0x0D, 0x20, 0x00, 0x00);
                return true;
            default:
                return false;
        }
    }

    private static void applyExactBytes(byte[] f, int b5, int b6, int b7, int b8) {
        f[5] = (byte) b5;
        f[6] = (byte) b6;
        f[7] = (byte) b7;
        f[8] = (byte) b8;
    }

    private static void applyGrayRoadBytes(byte[] f, String id) {
        if (id == null) return;
        int family = (f[5] & 0xff) == 0x1F ? 0x1F : 0x0D;
        switch (id) {
            case "context_ra_gray_straight":
                f[5] = (byte) family;
                f[6] = 0x00;
                f[7] = 0x01;
                break;
            case "context_ra_gray_right":
            case "context_ra_gray_hard_right":
                f[5] = (byte) family;
                f[6] = 0x00;
                f[7] = 0x10;
                break;
            case "context_ra_gray_straight_right":
                f[5] = (byte) family;
                f[6] = 0x00;
                f[7] = 0x11;
                break;
            case "context_ra_gray_left":
            case "context_ra_gray_hard_left":
                f[5] = (byte) family;
                f[6] = 0x08;
                f[7] = 0x00;
                break;
            case "context_ra_gray_straight_left":
                f[5] = (byte) family;
                f[6] = 0x08;
                f[7] = 0x01;
                break;
            case "context_ra_gray_left_right":
                f[5] = (byte) family;
                f[6] = 0x08;
                f[7] = 0x10;
                break;
            case "context_ra_gray_straight_left_right":
                f[5] = (byte) family;
                f[6] = 0x08;
                f[7] = 0x11;
                break;
            case "context_ra_gray_exit_right":
            case "context_ra_gray_only_exit_right":
                f[5] = (byte) family;
                f[6] = 0x00;
                f[7] = 0x03;
                break;
            case "context_ra_gray_left_exit_right":
            case "context_ra_gray_straight_left_exit_right":
                f[5] = (byte) family;
                f[6] = 0x08;
                f[7] = 0x03;
                break;
            case "context_ra_gray_exit_left":
                f[5] = (byte) family;
                f[6] = 0x20;
                f[7] = 0x00;
                break;
            case "context_ra_gray_straight_exit_left":
                f[5] = (byte) family;
                f[6] = 0x20;
                f[7] = 0x01;
                break;
            case "context_ra_gray_exit_left_right":
                f[5] = (byte) family;
                f[6] = 0x20;
                f[7] = 0x10;
                break;
            case "context_ra_gray_straight_exit_left_right":
                f[5] = (byte) family;
                f[6] = 0x20;
                f[7] = 0x11;
                break;
            default:
                break;
        }
    }

    private static void applyClassicIcon(byte[] f, String id) {
        switch (iconCase(id)) {
            case 0:
                f[5] = 0x03;
                f[6] = 0;
                f[7] = 0;
                f[8] = 0;
                break;
            case 1:
                f[5] = 0x14;
                f[6] = 0x00;
                f[7] = 0x00;
                f[8] = 0x00;
                break;
            case 2:
            case 3:
                f[8] = 0;
                f[7] = 1;
                break;
            case 4:
                f[7] = 0x00;
                f[8] = 0x0F;
                break;
            case 5:
                f[5] = 0x20;
                f[6] = 0x08;
                f[7] = 0x11;
                f[8] = (byte) 0xFF;
                break;
            case 6:
                f[5] = 0x20;
                f[6] = 0x08;
                f[7] = 0x11;
                f[8] = 0x0C;
                break;
            case 7:
                f[5] = 0x14;
                f[6] = 0x00;
                f[7] = 0x00;
                f[8] = 0x00;
                break;
            case 8:
                f[6] = 0x00;
                f[7] = 0x00;
                f[8] = 0x27;
                break;
            case 9:
                f[5] = 0x13;
                f[6] = 0x00;
                f[7] = 0x00;
                f[8] = 0x00;
                break;
            case 10:
                f[6] = 0x00;
                f[7] = 0;
                f[8] = 0x24;
                break;
            case 11:
                f[6] = 0;
                f[7] = 0x00;
                f[8] = 0x0C;
                break;
            case 12:
                f[5] = 0x13;
                f[6] = 0x00;
                f[7] = 0x00;
                f[8] = 0x00;
                break;
            case 13:
                f[8] = 0x0C;
                f[5] = 0x1F;
                f[6] = 0x00;
                f[7] = 0x03;
                break;
            case 14:
                f[8] = 0x24;
                f[5] = 0x1F;
                f[6] = 0x03;
                f[7] = 0x01;
                break;
            default:
                f[5] = 0x09;
                break;
        }
    }

    private static void applyTbtIcon(byte[] f, String id) {
        switch (iconCase(id)) {
            case 0:
                f[5] = 0x70;
                break;
            case 1:
                f[5] = 0x47;
                break;
            case 2:
                f[5] = (byte) 0xB0;
                break;
            case 3:
                f[5] = 0x41;
                break;
            case 4:
                f[5] = 0x44;
                break;
            case 5:
                f[5] = 0x60;
                break;
            case 6:
                f[5] = 0x61;
                break;
            case 7:
                f[5] = (byte) 0x95;
                break;
            case 8:
                f[5] = 0x45;
                break;
            case 9:
                f[5] = 0x42;
                break;
            case 10:
                f[5] = 0x46;
                break;
            case 11:
                f[5] = 0x43;
                break;
            case 12:
                f[5] = (byte) 0x93;
                break;
            case 13:
                f[5] = 0x49;
                break;
            case 14:
                f[5] = 0x48;
                break;
            default:
                f[5] = 0x09;
                break;
        }
    }

    private static int iconCase(String id) {
        if (id == null) return -1;
        switch (id) {
            case "context_ra_finish":
                return 0;
            case "context_ra_take_left":
                return 1;
            case "context_ra_boardferry":
                return 2;
            case "context_ra_forward":
                return 3;
            case "context_ra_hard_turn_right":
                return 4;
            case "context_ra_in_circular_movement":
                return 5;
            case "context_ra_out_circular_movement":
                return 6;
            case "context_ra_exit_left":
                return 7;
            case "context_ra_hard_turn_left":
                return 8;
            case "context_ra_take_right":
                return 9;
            case "context_ra_turn_left":
                return 10;
            case "context_ra_turn_right":
                return 11;
            case "context_ra_exit_right":
                return 12;
            case "context_ra_turn_back_right":
                return 13;
            case "context_ra_turn_back":
            case "context_ra_turn_back_left":
                return 14;
            case "context_ra_roundabout_exit_1":
                return 15;
            case "context_ra_roundabout_exit_2":
                return 16;
            case "context_ra_roundabout_exit_3":
                return 17;
            case "context_ra_roundabout_exit_4":
                return 18;
            case "context_ra_roundabout_gray_4_exits":
                return 19;
            case "context_ra_direction_to_finish":
                return 20;
            case "context_ra_gray_straight":
                return 21;
            case "context_ra_gray_right":
            case "context_ra_gray_hard_right":
                return 22;
            case "context_ra_gray_straight_right":
                return 23;
            case "context_ra_gray_left":
            case "context_ra_gray_hard_left":
                return 24;
            case "context_ra_gray_straight_left":
                return 25;
            case "context_ra_gray_left_right":
            case "context_ra_gray_left_exit_right":
            case "context_ra_gray_exit_left_right":
                return 26;
            case "context_ra_gray_straight_left_right":
            case "context_ra_gray_straight_left_exit_right":
            case "context_ra_gray_straight_exit_left_right":
                return 27;
            case "context_ra_gray_straight_right_multi":
                return 28;
            case "context_ra_gray_straight_left_multi":
                return 29;
            case "context_ra_gray_exit_right":
            case "context_ra_gray_only_exit_right":
                return 30;
            case "context_ra_gray_exit_left":
            case "context_ra_gray_straight_exit_left":
                return 31;
            default:
                return -1;
        }
    }

    private static int nearestCompassUiStep(int value) {
        int out = ((value % 36) + 36) % 36;
        out = Math.round(out / 3f) * 3;
        return out == 36 ? 0 : out;
    }

    private static int calibratedFinishDirectionAngle(int uiStep) {
        int out = ((uiStep % 48) + 48) % 48;
        out = Math.round(out / 3f) * 3;
        return out == 48 ? 0 : out;
    }

    private static int nearestManeuverAngle(int value) {
        int out = ((value % 48) + 48) % 48;
        return out == 48 ? 0 : out;
    }

    private static byte[] raiseSourceStatus(MediaSourceKind kind) {
        if (kind == MediaSourceKind.USB_MUSIC) {
            return raiseSourceValue(0x16, "");
        }
        if (kind == MediaSourceKind.BLUETOOTH_AUDIO) {
            return raiseSourceValue(0x0B, "");
        }
        if (kind == MediaSourceKind.AM_RADIO) {
            return raiseSourceValue(0x09, "");
        }
        if (kind == MediaSourceKind.BT_PHONE) {
            return raiseSourceValue(0x07, "");
        }
        if (kind == MediaSourceKind.ANDROID_AUTO) {
            return raiseSourceValue(0x23, "");
        }
        if (kind == MediaSourceKind.CARPLAY) {
            return raiseSourceValue(0x25, "");
        }
        if (kind == MediaSourceKind.MY_MUSIC) {
            return raiseSourceValue(0x24, "");
        }
        return raiseSourceValue(0x81, "");
    }

    private static byte[] raiseFmSourceStatus(String detail) {
        int mhz10 = fmMhz10(detail);
        int whole = mhz10 / 10;
        int decimalTens = (mhz10 % 10) * 10;
        return raiseFrame(new byte[]{0x08, 0x09, 0x02, 0x00, (byte) whole, (byte) decimalTens, 0x00});
    }

    private static byte[] raiseAmSourceStatus(String detail) {
        int khz = amKhz(detail);
        return raiseFrame(new byte[]{0x08, 0x09, 0x09, 0x04,
                (byte) ((khz >> 8) & 0xff), (byte) (khz & 0xff), 0x00});
    }

    private static byte[] raiseSourceValue(int source, String detail) {
        int value = clamp(source, 0, 255);
        if (value == 0x02) return raiseFmSourceStatus(detail);
        if (value == 0x09) return raiseAmSourceStatus(detail);
        if (value == 0x0B) return raiseFrame(new byte[]{0x06, 0x09, 0x0B, 0x00, 0x00});
        if (value == 0x11) return raiseFrame(new byte[]{0x06, 0x09, 0x11, 0x00, 0x00});
        if (value == 0x07) return raiseFrame(new byte[]{0x06, 0x09, 0x07, 0x01, 0x00});
        if (value == 0x25) return raiseFrame(new byte[]{0x06, 0x09, 0x25, 0x00, 0x00});
        if (value == 0x80) return raiseFrame(new byte[]{0x06, 0x09, (byte) 0x81, 0x00, 0x00});
        int second = nextMediaSecond();
        if (value == 0x16) {
            return raiseFrame(new byte[]{0x0A, 0x09, 0x16, 0x00, 0x01, 0x00, 0x00, (byte) second, 0x00});
        }
        return raiseFrame(new byte[]{0x0A, 0x09, (byte) value, 0x00, 0x00, 0x00, 0x00, (byte) second, 0x00});
    }

    static byte[] raiseFrame(byte[] payload) {
        byte[] frame = new byte[payload.length + 2];
        frame[0] = (byte) 0xFD;
        System.arraycopy(payload, 0, frame, 1, payload.length);
        int sum = 0;
        for (int i = 1; i < frame.length - 1; i++) sum += frame[i] & 0xff;
        frame[frame.length - 1] = (byte) (sum & 0xff);
        return frame;
    }

    private static synchronized int nextMediaSecond() {
        mediaSecond = (mediaSecond + 1) % 60;
        return mediaSecond;
    }

    private static int fmMhz10(String value) {
        String text = value == null ? "" : value;
        for (int start = 0; start < text.length(); start++) {
            if (!Character.isDigit(text.charAt(start))) continue;
            int end = start;
            boolean separator = false;
            while (end < text.length()) {
                char c = text.charAt(end);
                if (Character.isDigit(c)) {
                    end++;
                } else if ((c == '.' || c == ',') && !separator) {
                    separator = true;
                    end++;
                } else {
                    break;
                }
            }
            int mhz10 = parseMhz10(text.substring(start, end));
            if (mhz10 >= 600 && mhz10 <= 1200) return mhz10;
            start = Math.max(start, end - 1);
        }
        return 1010;
    }

    private static int parseMhz10(String value) {
        String text = value == null ? "" : value.replace(',', '.');
        try {
            int dot = text.indexOf('.');
            if (dot < 0) return Integer.parseInt(text) * 10;
            int whole = Integer.parseInt(text.substring(0, dot));
            int tenth = 0;
            int next = dot + 1;
            if (next < text.length() && Character.isDigit(text.charAt(next))) {
                tenth = text.charAt(next) - '0';
            }
            return whole * 10 + tenth;
        } catch (Exception ignored) {
            return -1;
        }
    }

    private static int amKhz(String value) {
        String text = value == null ? "" : value;
        for (int start = 0; start < text.length(); start++) {
            if (!Character.isDigit(text.charAt(start))) continue;
            int end = start;
            while (end < text.length() && Character.isDigit(text.charAt(end))) end++;
            try {
                int khz = Integer.parseInt(text.substring(start, end));
                if (khz > 0 && khz <= 0xffff) return khz;
            } catch (Exception ignored) {
            }
            start = Math.max(start, end - 1);
        }
        return 24;
    }

    private static String trim(String value, int maxChars) {
        String text = value == null ? "" : value.trim();
        return text.length() > maxChars ? text.substring(0, maxChars) : text;
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    private static int defaultProgressBucket(float distance, boolean km) {
        return 0;
    }

    public static int txProgressBucket(int progress) {
        return clamp(progress, 0, 9);
    }

    private static float txDistance(float distance, boolean km) {
        if (distance <= 0f || km) return distance;
        return Math.max(10f, Math.round(distance / 10f) * 10f);
    }

    private static byte progressByte(int progress, float distance) {
        float positive = Math.max(0f, distance);
        int whole = (int) positive;
        int tenth = Math.round((positive - whole) * 10f) & 0x0f;
        return (byte) (((txProgressBucket(progress) & 0x0f) << 4) | tenth);
    }
}
