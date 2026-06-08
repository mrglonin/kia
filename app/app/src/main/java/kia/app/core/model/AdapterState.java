package kia.app.core.model;

public final class AdapterState {
    public final boolean usbConnected;
    public final String usbText;
    public final String uid;
    public final String firmware;
    public final String health;
    public final long lastFrameAt;

    public AdapterState(boolean usbConnected, String usbText, String uid, String firmware,
                        String health, long lastFrameAt) {
        this.usbConnected = usbConnected;
        this.usbText = safe(usbText);
        this.uid = safe(uid);
        this.firmware = safe(firmware);
        this.health = safe(health);
        this.lastFrameAt = lastFrameAt;
    }

    public static AdapterState empty() {
        return new AdapterState(false, "USB не подключен", "не получен", "не получена",
                "не проверено", 0L);
    }

    public AdapterState withUsb(boolean connected, String text) {
        return new AdapterState(connected, text, uid, firmware, health, lastFrameAt);
    }

    public AdapterState withInfo(String nextUid, String nextFirmware) {
        return new AdapterState(usbConnected, usbText,
                nextUid == null ? uid : nextUid,
                nextFirmware == null ? firmware : nextFirmware,
                health, lastFrameAt);
    }

    public AdapterState withHealth(String value) {
        return new AdapterState(usbConnected, usbText, uid, firmware, value, lastFrameAt);
    }

    public AdapterState withFrameTime(long value) {
        return new AdapterState(usbConnected, usbText, uid, firmware, health, value);
    }

    private static String safe(String value) {
        return value == null ? "" : value;
    }
}
