package kia.app.diagnostics;

import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.hardware.usb.UsbConstants;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbDeviceConnection;
import android.hardware.usb.UsbEndpoint;
import android.hardware.usb.UsbInterface;
import android.hardware.usb.UsbManager;
import android.os.Build;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

import kia.app.core.AppLog;
import kia.app.core.settings.AppSettings;
import kia.app.entry.UsbPermissionReceiver;
import kia.app.transport.usb.UsbTransport;

public final class GsUsbCanLogger {
    private static final int GS_USB_ID_VENDOR = 0x1D50;
    private static final int GS_USB_ID_PRODUCT = 0x606F;
    private static final int GS_USB_CANDLELIGHT_VENDOR_ID = 0x1209;
    private static final int GS_USB_CANDLELIGHT_PRODUCT_ID = 0x2323;
    private static final int GS_USB_CES_CANEXT_FD_VENDOR_ID = 0x1CD2;
    private static final int GS_USB_CES_CANEXT_FD_PRODUCT_ID = 0x606F;
    private static final int GS_USB_ABE_CANDEBUGGER_FD_VENDOR_ID = 0x16D0;
    private static final int GS_USB_ABE_CANDEBUGGER_FD_PRODUCT_ID = 0x10B8;
    private static final int GS_USB_CANNECTIVITY_VENDOR_ID = 0x1209;
    private static final int GS_USB_CANNECTIVITY_PRODUCT_ID = 0xCA01;

    private static final int BREQ_BITTIMING = 1;
    private static final int BREQ_MODE = 2;
    private static final int BREQ_BT_CONST = 4;
    private static final int MODE_RESET = 0;
    private static final int MODE_START = 1;
    private static final int MODE_HW_TIMESTAMP = 16;
    private static final int USB_VENDOR_IN = 0xC1;
    private static final int USB_VENDOR_OUT = 0x41;
    private static final int CHANNEL_M_CAN = 0;
    private static final int CHANNEL_C_CAN = 1;
    private static final int BITRATE_M_CAN = 100000;
    private static final int BITRATE_C_CAN = 500000;
    private static final int TIMEOUT_MS = 500;
    private static final Set<String> REQUESTED_USB_PERMISSIONS = new HashSet<>();

    private static GsUsbCanLogger instance;

    private final Context app;
    private final UsbManager usbManager;
    private volatile boolean running;
    private volatile boolean hwTimestamp;
    private volatile String status = "gs_usb: выкл";
    private Thread readerThread;
    private UsbDeviceConnection connection;
    private UsbInterface usbInterface;
    private int[] activeChannels = new int[0];

    private GsUsbCanLogger(Context context) {
        app = context.getApplicationContext();
        usbManager = (UsbManager) app.getSystemService(Context.USB_SERVICE);
    }

    public static synchronized GsUsbCanLogger get(Context context) {
        if (instance == null) instance = new GsUsbCanLogger(context);
        return instance;
    }

    public synchronized void setRecording(boolean enabled) {
        CanLogger.get(app).setRecording(enabled);
        if (enabled) startReader();
        else stopReader();
    }

    public synchronized String statusText() {
        return status;
    }

    public synchronized boolean recording() {
        return running;
    }

    public synchronized boolean usbPresent() {
        return findDevice() != null;
    }

    public static void onPermissionResult(Context context, UsbDevice device, boolean granted) {
        if (device == null || !isGsUsbDevice(device)) return;
        clearPermissionRequest(device);
        if (context != null && granted) {
            AppSettings.clearUsbPermissionRequest(context, permissionKey(device));
        }
        if (!granted || context == null) return;
        AppLog.line(context, "gs_usb permission granted");
        if (AppSettings.debugCan(context)) get(context).setRecording(true);
    }

    public static boolean isGsUsbDevice(UsbDevice device) {
        if (device == null) return false;
        int vendor = device.getVendorId();
        int product = device.getProductId();
        return (vendor == GS_USB_ID_VENDOR && product == GS_USB_ID_PRODUCT)
                || (vendor == GS_USB_CANDLELIGHT_VENDOR_ID && product == GS_USB_CANDLELIGHT_PRODUCT_ID)
                || (vendor == GS_USB_CES_CANEXT_FD_VENDOR_ID && product == GS_USB_CES_CANEXT_FD_PRODUCT_ID)
                || (vendor == GS_USB_ABE_CANDEBUGGER_FD_VENDOR_ID && product == GS_USB_ABE_CANDEBUGGER_FD_PRODUCT_ID)
                || (vendor == GS_USB_CANNECTIVITY_VENDOR_ID && product == GS_USB_CANNECTIVITY_PRODUCT_ID);
    }

    private synchronized void startReader() {
        if (running) return;
        running = true;
        status = "gs_usb: старт";
        readerThread = new Thread(this::readLoop, "kia-canbus-gs-usb-logger");
        readerThread.start();
    }

    private synchronized void stopReader() {
        running = false;
        if (readerThread != null) {
            readerThread.interrupt();
            readerThread = null;
        }
        closeConnection();
        status = "gs_usb: выкл";
    }

    private void readLoop() {
        while (running) {
            try {
                UsbEndpoint readEndpoint = openDevice();
                readFrames(readEndpoint);
            } catch (Exception e) {
                if (running) {
                    status = "gs_usb: ошибка " + e.getClass().getSimpleName() + " " + clean(e.getMessage());
                    AppLog.line(app, status);
                    sleep(1000L);
                }
            } finally {
                closeConnection();
            }
        }
    }

    private UsbEndpoint openDevice() {
        if (usbManager == null) throw new IllegalStateException("UsbManager unavailable");
        UsbDevice device = findDevice();
        if (device == null) {
            status = "gs_usb: устройство не найдено";
            throw new IllegalStateException("gs_usb not found");
        }
        if (!usbManager.hasPermission(device)) {
            if (requestPermission(device)) {
                status = "gs_usb: запрошено разрешение " + device.getDeviceName();
            } else {
                status = "gs_usb: ждём разрешение " + device.getDeviceName();
            }
            throw new IllegalStateException("permission required");
        }
        clearPermissionRequest(device);
        AppSettings.clearUsbPermissionRequest(app, permissionKey(device));
        UsbInterface intf = findInterface(device);
        if (intf == null) throw new IllegalStateException("bulk interface not found");
        UsbEndpoint readEndpoint = findBulkEndpoint(intf, true);
        if (readEndpoint == null) throw new IllegalStateException("bulk IN endpoint not found");
        UsbDeviceConnection active = usbManager.openDevice(device);
        if (active == null) throw new IllegalStateException("openDevice failed");
        if (!active.claimInterface(intf, true)) {
            active.close();
            throw new IllegalStateException("claimInterface failed");
        }
        synchronized (this) {
            connection = active;
            usbInterface = intf;
        }
        int[] channels = channelsForMode(AppSettings.loggerBusMode(app));
        synchronized (this) {
            activeChannels = channels;
        }
        configure(active, channels);
        status = "gs_usb: запись " + AppSettings.loggerBusLabel(app)
                + " " + device.getDeviceName()
                + " " + channelSummary(channels)
                + (hwTimestamp ? " ts" : "");
        AppLog.line(app, status);
        return readEndpoint;
    }

    private void readFrames(UsbEndpoint readEndpoint) {
        int frameSize = hwTimestamp ? 24 : 20;
        byte[] buffer = new byte[frameSize];
        while (running) {
            UsbDeviceConnection active;
            synchronized (this) {
                active = connection;
            }
            if (active == null) return;
            int read = active.bulkTransfer(readEndpoint, buffer, frameSize, TIMEOUT_MS);
            if (read == frameSize) parseFrame(buffer, frameSize);
            else if (read < 0 && running) throw new IllegalStateException("bulk read failed");
        }
    }

    private void parseFrame(byte[] frame, int frameSize) {
        int rawCanId = le32(frame, 4);
        int canId = rawCanId & 0x1fffffff;
        int dlc = Math.max(0, Math.min(8, frame[8] & 0xff));
        int channel = frame[9] & 0xff;
        byte[] data = Arrays.copyOfRange(frame, 12, 12 + dlc);
        long timestampUs = frameSize >= 24 ? (le32(frame, 20) & 0xffffffffL) : 0L;
        CanLogger.get(app).recordGsFrame(channel, canId, data, timestampUs);
    }

    private void configure(UsbDeviceConnection active, int[] channels) {
        for (int channel : channels) {
            controlOut(active, BREQ_MODE, channel, le32Bytes(MODE_RESET, 0));
        }
        Capability capability = readCapability(active);
        for (int channel : channels) {
            Timing timing = timingFor(capability, bitrateForChannel(channel));
            controlOut(active, BREQ_BITTIMING, channel, le32Bytes(timing.propSeg, timing.phaseSeg1,
                    timing.phaseSeg2, timing.sjw, timing.brp));
        }
        int flags = 0;
        if ((capability.feature & MODE_HW_TIMESTAMP) != 0) flags |= MODE_HW_TIMESTAMP;
        hwTimestamp = (flags & MODE_HW_TIMESTAMP) != 0;
        for (int channel : channels) {
            controlOut(active, BREQ_MODE, channel, le32Bytes(MODE_START, flags));
        }
    }

    private Capability readCapability(UsbDeviceConnection active) {
        byte[] data = new byte[40];
        int read = active.controlTransfer(USB_VENDOR_IN, BREQ_BT_CONST, 0, 0,
                data, data.length, TIMEOUT_MS);
        if (read < data.length) return Capability.default36M();
        return new Capability(le32(data, 0), le32(data, 4),
                le32(data, 8), le32(data, 12), le32(data, 16), le32(data, 20),
                le32(data, 24), le32(data, 28), le32(data, 32), le32(data, 36));
    }

    private void controlOut(UsbDeviceConnection active, int request, byte[] data) {
        controlOut(active, request, 0, data);
    }

    private void controlOut(UsbDeviceConnection active, int request, int channel, byte[] data) {
        int sent = active.controlTransfer(USB_VENDOR_OUT, request, channel, 0,
                data, data.length, TIMEOUT_MS);
        if (sent < 0) throw new IllegalStateException("control " + request + " failed");
    }

    private UsbDevice findDevice() {
        if (usbManager == null) return null;
        for (UsbDevice device : usbManager.getDeviceList().values()) {
            if (isGsUsbDevice(device)) return device;
        }
        return null;
    }

    private static UsbInterface findInterface(UsbDevice device) {
        for (int i = 0; i < device.getInterfaceCount(); i++) {
            UsbInterface intf = device.getInterface(i);
            if (findBulkEndpoint(intf, true) != null) return intf;
        }
        return device.getInterfaceCount() > 0 ? device.getInterface(0) : null;
    }

    private static UsbEndpoint findBulkEndpoint(UsbInterface intf, boolean in) {
        if (intf == null) return null;
        int direction = in ? UsbConstants.USB_DIR_IN : UsbConstants.USB_DIR_OUT;
        for (int i = 0; i < intf.getEndpointCount(); i++) {
            UsbEndpoint endpoint = intf.getEndpoint(i);
            if (endpoint.getType() == UsbConstants.USB_ENDPOINT_XFER_BULK
                    && endpoint.getDirection() == direction) return endpoint;
        }
        return null;
    }

    private synchronized void closeConnection() {
        if (connection == null) return;
        try {
            int[] channels = activeChannels.length == 0 ? new int[]{CHANNEL_M_CAN, CHANNEL_C_CAN} : activeChannels;
            for (int channel : channels) {
                try {
                    controlOut(connection, BREQ_MODE, channel, le32Bytes(MODE_RESET, 0));
                } catch (Exception ignored) {
                }
            }
        } catch (Exception ignored) {
        }
        try {
            if (usbInterface != null) connection.releaseInterface(usbInterface);
        } catch (Exception ignored) {
        }
        try {
            connection.close();
        } catch (Exception ignored) {
        }
        connection = null;
        usbInterface = null;
        activeChannels = new int[0];
    }

    private static int[] channelsForMode(int mode) {
        if (mode == AppSettings.LOGGER_BUS_C) return new int[]{CHANNEL_C_CAN};
        if (mode == AppSettings.LOGGER_BUS_BOTH) return new int[]{CHANNEL_M_CAN, CHANNEL_C_CAN};
        return new int[]{CHANNEL_M_CAN};
    }

    private static int bitrateForChannel(int channel) {
        return channel == CHANNEL_C_CAN ? BITRATE_C_CAN : BITRATE_M_CAN;
    }

    private static String channelSummary(int[] channels) {
        if (channels == null || channels.length == 0) return "";
        StringBuilder out = new StringBuilder();
        for (int channel : channels) {
            if (out.length() > 0) out.append(" + ");
            if (channel == CHANNEL_C_CAN) out.append("C-CAN ch1 500k");
            else if (channel == CHANNEL_M_CAN) out.append("M-CAN ch0 100k");
            else out.append("ch").append(channel).append(' ').append(bitrateForChannel(channel));
        }
        return out.toString();
    }

    private boolean requestPermission(UsbDevice device) {
        String key = permissionKey(device);
        synchronized (REQUESTED_USB_PERMISSIONS) {
            if (REQUESTED_USB_PERMISSIONS.contains(key)) return false;
            if (!AppSettings.shouldRequestUsbPermission(app, key)) return false;
            REQUESTED_USB_PERMISSIONS.add(key);
        }
        Intent intent = new Intent(app, UsbPermissionReceiver.class);
        intent.setAction(UsbTransport.ACTION_USB_PERMISSION);
        int flags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= 31) flags |= PendingIntent.FLAG_MUTABLE;
        else flags |= PendingIntent.FLAG_IMMUTABLE;
        PendingIntent pi = PendingIntent.getBroadcast(
                app,
                key.hashCode(),
                intent,
                flags
        );
        AppSettings.markUsbPermissionRequested(app, key);
        usbManager.requestPermission(device, pi);
        return true;
    }

    private static void clearPermissionRequest(UsbDevice device) {
        synchronized (REQUESTED_USB_PERMISSIONS) {
            REQUESTED_USB_PERMISSIONS.remove(permissionKey(device));
        }
    }

    private static String permissionKey(UsbDevice device) {
        if (device == null) return "unknown";
        return device.getDeviceName() + ":" + device.getVendorId() + ":" + device.getProductId();
    }

    private static Timing timingFor(Capability cap, int bitrate) {
        if (cap.fclkCan == 36000000 && (bitrate == 100000 || bitrate == 250000 || bitrate == 500000)) {
            int brp = 36000000 / (bitrate * 18);
            return new Timing(1, 14, 2, 1, brp);
        }
        if (cap.fclkCan == 48000000 && bitrate == 100000) {
            return new Timing(1, 12, 2, 1, 30);
        }
        Timing best = null;
        float bestScore = Float.MAX_VALUE;
        for (int totalTq = 8; totalTq <= 25; totalTq++) {
            int denom = bitrate * totalTq;
            if (denom <= 0 || cap.fclkCan % denom != 0) continue;
            int brp = cap.fclkCan / denom;
            if (brp < cap.brpMin || brp > cap.brpMax) continue;
            int inc = Math.max(1, cap.brpInc);
            if ((brp - cap.brpMin) % inc != 0) continue;
            for (int phaseSeg2 = cap.tseg2Min; phaseSeg2 <= Math.min(cap.tseg2Max, totalTq - 3); phaseSeg2++) {
                int tseg1 = totalTq - 1 - phaseSeg2;
                if (tseg1 < cap.tseg1Min || tseg1 > cap.tseg1Max) continue;
                int propSeg = 1;
                int phaseSeg1 = tseg1 - propSeg;
                if (phaseSeg1 <= 0) continue;
                float sample = (1f + tseg1) / totalTq;
                float score = Math.abs(sample - 0.875f);
                if (score < bestScore) {
                    bestScore = score;
                    best = new Timing(propSeg, phaseSeg1, phaseSeg2, Math.max(1, Math.min(4, cap.sjwMax)), brp);
                }
            }
        }
        if (best == null) throw new IllegalStateException("bit timing unavailable");
        return best;
    }

    private static int le32(byte[] data, int offset) {
        return (data[offset] & 0xff)
                | ((data[offset + 1] & 0xff) << 8)
                | ((data[offset + 2] & 0xff) << 16)
                | ((data[offset + 3] & 0xff) << 24);
    }

    private static byte[] le32Bytes(int... values) {
        byte[] out = new byte[values.length * 4];
        for (int i = 0; i < values.length; i++) {
            int v = values[i];
            int offset = i * 4;
            out[offset] = (byte) (v & 0xff);
            out[offset + 1] = (byte) ((v >> 8) & 0xff);
            out[offset + 2] = (byte) ((v >> 16) & 0xff);
            out[offset + 3] = (byte) ((v >> 24) & 0xff);
        }
        return out;
    }

    private static String clean(String value) {
        if (value == null) return "";
        return value.replace('\n', ' ').replace('\r', ' ').trim();
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
        }
    }

    private static final class Capability {
        final int feature;
        final int fclkCan;
        final int tseg1Min;
        final int tseg1Max;
        final int tseg2Min;
        final int tseg2Max;
        final int sjwMax;
        final int brpMin;
        final int brpMax;
        final int brpInc;

        Capability(int feature, int fclkCan, int tseg1Min, int tseg1Max,
                   int tseg2Min, int tseg2Max, int sjwMax, int brpMin,
                   int brpMax, int brpInc) {
            this.feature = feature;
            this.fclkCan = fclkCan;
            this.tseg1Min = tseg1Min;
            this.tseg1Max = tseg1Max;
            this.tseg2Min = tseg2Min;
            this.tseg2Max = tseg2Max;
            this.sjwMax = sjwMax;
            this.brpMin = brpMin;
            this.brpMax = brpMax;
            this.brpInc = brpInc;
        }

        static Capability default36M() {
            return new Capability(MODE_HW_TIMESTAMP, 36000000,
                    1, 16, 1, 8, 4, 1, 1024, 1);
        }
    }

    private static final class Timing {
        final int propSeg;
        final int phaseSeg1;
        final int phaseSeg2;
        final int sjw;
        final int brp;

        Timing(int propSeg, int phaseSeg1, int phaseSeg2, int sjw, int brp) {
            this.propSeg = propSeg;
            this.phaseSeg1 = phaseSeg1;
            this.phaseSeg2 = phaseSeg2;
            this.sjw = sjw;
            this.brp = brp;
        }
    }
}
