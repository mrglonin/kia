package kia.app.protocol.adapter;

import android.content.Context;

import java.util.Locale;

import kia.app.amp.AmpController;
import kia.app.core.AppLog;
import kia.app.core.StateStore;
import kia.app.core.model.AdapterState;
import kia.app.core.model.VehicleState;
import kia.app.diagnostics.CanLogger;
import kia.app.rcta.RctaController;
import kia.app.tpms.TpmsController;
import kia.app.update.FirmwareUpdateController;

public final class FrameRouter {
    private final Context app;

    public FrameRouter(Context context) {
        this.app = context.getApplicationContext();
    }

    public void onFrame(byte[] frame) {
        if (frame == null || frame.length < 6) return;
        int id = frame[4] & 0xff;
        StateStore.setAdapter(app, StateStore.adapter().withFrameTime(System.currentTimeMillis()));
        switch (id) {
            case AdapterProtocol.CMD_VERSION:
                handleVersion(frame);
                break;
            case AdapterProtocol.CMD_HEALTH:
                handleHealth(frame);
                break;
            case AdapterProtocol.CMD_AMP:
                AmpController.get(app).handleFrame(frame);
                break;
            case AdapterProtocol.CMD_RAW_CAN:
                if (!handleSunroof(frame)) handleRawCan(frame);
                break;
            case AdapterProtocol.CMD_RAW_CAN_TX:
                AppLog.line(app, "CAN TX ack: " + AdapterProtocol.hex(frame));
                break;
            case AdapterProtocol.CMD_SETTINGS:
                AppLog.line(app, "Settings ack: " + AdapterProtocol.hex(frame));
                break;
            case AdapterProtocol.CMD_RCTA:
                RctaController.get(app).handleFrame(frame);
                break;
            case AdapterProtocol.CMD_FIRMWARE:
                FirmwareUpdateController.get(app).handleAck(frame);
                AppLog.line(app, "Firmware ack: " + AdapterProtocol.hex(frame));
                break;
            case AdapterProtocol.CMD_VEHICLE_SNAPSHOT:
                handleSnapshot(frame);
                break;
            case AdapterProtocol.CMD_TPMS:
            case AdapterProtocol.CMD_TPMS_LEGACY:
                TpmsController.get(app).handleAdapterFrame(frame);
                break;
            default:
                break;
        }
    }

    private void handleVersion(byte[] frame) {
        if (frame.length < 9) return;
        int len = frame[3] & 0xff;
        AdapterState current = StateStore.adapter();
        if (len == 18 && frame.length >= 17) {
            StringBuilder uid = new StringBuilder();
            for (int i = 5; i <= 16; i++) uid.append(byteHex(frame[i]));
            StateStore.setAdapter(app, current.withInfo(uid.toString(), null));
            return;
        }
        if (len == 10 && frame.length >= 9) {
            String family = (frame[6] & 0xff) == 0x35 ? "2CAN35" : "CAN";
            String prefix = (frame[5] & 0xff) == 0xAA ? "BASE " : "";
            String version = prefix + family + " " + byteHex(frame[7]) + "." + byteHex(frame[8]);
            StateStore.setAdapter(app, current.withInfo(null, version));
        }
    }

    private void handleHealth(byte[] frame) {
        int status = frame.length > 5 ? frame[5] & 0xff : -1;
        int apiMajor = frame.length > 6 ? frame[6] & 0xff : -1;
        int apiMinor = frame.length > 7 ? frame[7] & 0xff : -1;
        int rawTx = frame.length > 8 ? frame[8] & 0xff : -1;
        int caps = frame.length > 9 ? frame[9] & 0xff : -1;
        String statusText = status == 0x20 ? "V20 online"
                : status == 0x21 ? "V21 online"
                : "ответ 0x" + byteHex((byte) status);
        String health = statusText + " API " + Math.max(0, apiMajor) + "." + Math.max(0, apiMinor)
                + " rawTX=" + (rawTx == 0 ? "нет" : "да")
                + " caps=0x" + byteHex((byte) Math.max(0, caps));
        StateStore.setAdapter(app, StateStore.adapter().withHealth(health));
    }

    private void handleRawCan(byte[] frame) {
        CanLogger.get(app).recordRawFrame(frame);
        ParsedCan parsed = parseRawCan(frame);
        if (parsed != null) applyCanFrame(parsed.canId, parsed.data);
    }

    private boolean handleSunroof(byte[] frame) {
        int len = frame == null || frame.length < 6 ? 0 : Math.min(frame.length, frame[3] & 0xff);
        if (len != 7) return false;
        boolean open = (frame[5] & 0xff) != 0;
        VehicleState current = StateStore.vehicle();
        if (current.sunroofKnown && current.sunroofOpen == open) return true;
        StateStore.setVehicle(app, current.withSunroof(open, "0x76"));
        AppLog.line(app, "Sunroof: " + (open ? "open" : "closed"));
        return true;
    }

    private void handleSnapshot(byte[] frame) {
        CanLogger.get(app).recordSnapshot(frame);
        int frameLen = frame == null || frame.length < 6 ? 0 : Math.min(frame.length, frame[3] & 0xff);
        int payloadLen = frameLen >= 6 ? frameLen - 6 : 0;
        if (payloadLen < 22) return;
        int base = 5;
        int status = u8(frame, base);
        if (status == 0) return;
        int known = u8(frame, base + 1) | (u8(frame, base + 2) << 8) | (u8(frame, base + 3) << 16);
        VehicleState current = StateStore.vehicle();
        VehicleState next = current;
        if ((known & 0x004) != 0) next = next.withEngineTemp(s16(frame, base + 12), "snapshot");
        if ((known & 0x400) != 0) next = next.withOutsideTemp(s16(frame, base + 20), "snapshot");
        if (next != current) StateStore.setVehicle(app, next);
    }

    private ParsedCan parseRawCan(byte[] frame) {
        if (frame == null || frame.length < 23) return null;
        int len = Math.min(frame.length, frame[3] & 0xff);
        if (len < 23 || (frame[4] & 0xff) != AdapterProtocol.CMD_RAW_CAN) return null;
        if ((frame[5] & 0xff) == 0) return null;
        int dlc = Math.min(frame[8] & 0xff, 8);
        if (dlc <= 0 || len < 14 + dlc) return null;
        int canId = le32(frame, 10);
        if (canId == 0) return null;
        byte[] data = new byte[dlc];
        System.arraycopy(frame, 14, data, 0, dlc);
        return new ParsedCan(canId, data);
    }

    private void applyCanFrame(int canId, byte[] data) {
        if (data == null) return;
        VehicleState current = StateStore.vehicle();
        VehicleState next = current;
        if (canId == 0x329 && data.length >= 2) {
            int temp = Math.round(((data[1] & 0xff) - 0x40) * 0.75f);
            next = next.withEngineTemp(temp, "0x329");
        } else if (canId == 0x044 && data.length >= 4) {
            int outside = Math.round(((data[3] & 0xff) - 0x52) / 2f);
            next = next.withOutsideTemp(outside, "0x044");
        } else if (canId == 0x042 && data.length >= 4) {
            next = applyCabinClassic(next, data[1] & 0xff, data[3] & 0xff, "0x042");
        } else if (canId == 0x131 && data.length >= 5) {
            next = applyCabinV8(next, data[0] & 0xff, data[4] & 0xff, "0x131");
        }
        if (next != current) StateStore.setVehicle(app, next);
    }

    private static VehicleState applyCabinClassic(VehicleState current, int leftRaw, int rightRaw, String source) {
        int left = classicClimateTemp(leftRaw);
        int right = classicClimateTemp(rightRaw);
        boolean leftKnown = left != Integer.MIN_VALUE || current.cabinLeftKnown;
        boolean rightKnown = right != Integer.MIN_VALUE || current.cabinRightKnown;
        return current.withCabinTemp(leftKnown,
                left != Integer.MIN_VALUE ? left : current.cabinLeftTempC,
                rightKnown,
                right != Integer.MIN_VALUE ? right : current.cabinRightTempC,
                source);
    }

    private static VehicleState applyCabinV8(VehicleState current, int leftRaw, int rightRaw, String source) {
        int left = v8ClimateTemp(leftRaw);
        int right = v8ClimateTemp(rightRaw);
        boolean leftKnown = left != Integer.MIN_VALUE || current.cabinLeftKnown;
        boolean rightKnown = right != Integer.MIN_VALUE || current.cabinRightKnown;
        return current.withCabinTemp(leftKnown,
                left != Integer.MIN_VALUE ? left : current.cabinLeftTempC,
                rightKnown,
                right != Integer.MIN_VALUE ? right : current.cabinRightTempC,
                source);
    }

    private static int classicClimateTemp(int raw) {
        if (raw == 0 || raw == 0xff || raw == 0x7f) return Integer.MIN_VALUE;
        if (raw >= 0x10 && raw <= 0x30) return raw;
        if (raw >= 0x32 && raw <= 0x64) return raw / 2;
        return Integer.MIN_VALUE;
    }

    private static int v8ClimateTemp(int raw) {
        if (raw == 0 || raw == 0xff || raw <= 6) return Integer.MIN_VALUE;
        return raw - 6;
    }

    private static int u8(byte[] data, int offset) {
        return data == null || offset < 0 || offset >= data.length ? 0 : data[offset] & 0xff;
    }

    private static int u16(byte[] data, int offset) {
        return u8(data, offset) | (u8(data, offset + 1) << 8);
    }

    private static int s16(byte[] data, int offset) {
        int value = u16(data, offset);
        return value >= 0x8000 ? value - 0x10000 : value;
    }

    private static int le32(byte[] data, int offset) {
        return u8(data, offset)
                | (u8(data, offset + 1) << 8)
                | (u8(data, offset + 2) << 16)
                | (u8(data, offset + 3) << 24);
    }

    private static String byteHex(byte b) {
        int v = b & 0xff;
        return (v < 16 ? "0" : "") + Integer.toHexString(v).toUpperCase(Locale.US);
    }

    private static final class ParsedCan {
        final int canId;
        final byte[] data;

        ParsedCan(int canId, byte[] data) {
            this.canId = canId;
            this.data = data;
        }
    }
}
