package kia.app.transport.usb;

import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbDeviceConnection;
import android.hardware.usb.UsbManager;
import android.os.Build;

import com.hoho.android.usbserial.driver.UsbSerialDriver;
import com.hoho.android.usbserial.driver.UsbSerialPort;
import com.hoho.android.usbserial.driver.UsbSerialProber;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Queue;
import java.util.Set;

import kia.app.core.AppLog;
import kia.app.core.StateStore;
import kia.app.core.model.AdapterState;
import kia.app.core.settings.AppSettings;
import kia.app.entry.UsbPermissionReceiver;
import kia.app.protocol.adapter.AdapterProtocol;

public final class UsbTransport {
    public static final String ACTION_USB_PERMISSION = "kia.app.USB_PERMISSION";

    private static final int FTDI_VENDOR_ID = 1027;
    private static final int FTDI_CANBOX_PRODUCT_ID = 24577;
    private static final int STM_VENDOR_ID = 1155;
    private static final int STM_CDC_PRODUCT_ID = 22336;
    private static final int MAX_QUEUE = 80;
    private static final Set<String> REQUESTED_USB_PERMISSIONS = new HashSet<>();

    public interface Listener {
        void onFrame(byte[] frame);
    }

    private final Context app;
    private final UsbManager usbManager;
    private final Listener listener;
    private final Queue<byte[]> pending = new ArrayDeque<>();
    private final ByteArrayOutputStream incoming = new ByteArrayOutputStream();

    private UsbSerialPort port;
    private Thread readThread;
    private volatile boolean readRunning;
    private long lastQuietConnectAt;
    private long lastBadChecksumAt;

    public UsbTransport(Context context, Listener listener) {
        this.app = context.getApplicationContext();
        this.usbManager = (UsbManager) app.getSystemService(Context.USB_SERVICE);
        this.listener = listener;
    }

    public synchronized boolean ready() {
        return port != null;
    }

    public synchronized void connect() {
        if (port != null || usbManager == null) return;
        try {
            List<UsbSerialDriver> drivers = UsbSerialProber.getDefaultProber().findAllDrivers(usbManager);
            if (drivers == null || drivers.isEmpty()) {
                setUsb(false, "USB: адаптер не найден");
                return;
            }
            UsbSerialDriver driver = findCanboxDriver(drivers);
            if (driver == null) {
                setUsb(false, "USB: подходящий адаптер не найден");
                return;
            }
            UsbDevice device = driver.getDevice();
            if (!usbManager.hasPermission(device)) {
                if (requestPermission(device)) {
                    setUsb(false, "USB: запрошено разрешение " + device.getDeviceName());
                } else {
                    setUsb(false, "USB: ждём разрешение " + device.getDeviceName());
                }
                return;
            }
            clearPermissionRequest(device);
            AppSettings.clearUsbPermissionRequest(app, permissionKey(device));
            UsbDeviceConnection connection = usbManager.openDevice(device);
            if (connection == null) {
                setUsb(false, "USB: не удалось открыть " + device.getDeviceName());
                return;
            }
            port = driver.getPorts().get(0);
            port.open(connection);
            port.setParameters(115200, 8, UsbSerialPort.STOPBITS_1, UsbSerialPort.PARITY_NONE);
            setUsb(true, "USB: подключено " + device.getDeviceName() + " 115200 8N1");
            startReader();
            flushQueue();
        } catch (Exception e) {
            close();
            setUsb(false, "USB: ошибка " + e.getClass().getSimpleName() + " " + e.getMessage());
        }
    }

    public void write(byte[] frame, boolean quiet) {
        if (frame == null || frame.length == 0) return;
        byte[] copy = Arrays.copyOf(frame, frame.length);
        writeOrQueue(copy, quiet);
    }

    public synchronized void close() {
        readRunning = false;
        if (readThread != null) {
            readThread.interrupt();
            readThread = null;
        }
        if (port != null) {
            try {
                port.close();
            } catch (Exception ignored) {
            }
            port = null;
        }
        synchronized (incoming) {
            incoming.reset();
        }
        setUsb(false, "USB: закрыто");
    }

    private synchronized void writeOrQueue(byte[] frame, boolean quiet) {
        if (port == null) {
            if (!quiet) {
                if (pending.size() > MAX_QUEUE) pending.poll();
                pending.offer(frame);
            }
            long now = System.currentTimeMillis();
            if (!quiet || now - lastQuietConnectAt > 1500L) {
                lastQuietConnectAt = now;
                connect();
            }
            return;
        }
        try {
            port.write(frame, 300);
        } catch (IOException e) {
            if (!quiet) {
                if (pending.size() > MAX_QUEUE) pending.poll();
                pending.offer(frame);
            }
            close();
            if (!quiet) AppLog.line(app, "USB: запись не прошла, переподключение");
            connect();
        }
    }

    private void flushQueue() {
        while (true) {
            byte[] frame = pending.poll();
            if (frame == null) return;
            writeOrQueue(frame, false);
            if (port == null) return;
        }
    }

    private void startReader() {
        if (readThread != null && readThread.isAlive()) return;
        UsbSerialPort active = port;
        if (active == null) return;
        readRunning = true;
        readThread = new Thread(() -> {
            byte[] buffer = new byte[128];
            while (readRunning && active == port) {
                try {
                    int read = active.read(buffer, 500);
                    if (read > 0) appendIncoming(buffer, read);
                } catch (IOException e) {
                    if (readRunning) {
                        AppLog.line(app, "USB: чтение остановлено, переподключение");
                        close();
                        connect();
                    }
                    return;
                }
            }
        }, "kia-canbus-usb-reader");
        readThread.start();
    }

    private void appendIncoming(byte[] data, int count) {
        synchronized (incoming) {
            incoming.write(data, 0, count);
            parseIncoming();
        }
    }

    private void parseIncoming() {
        byte[] data = incoming.toByteArray();
        int index = 0;
        while (index <= data.length - 6) {
            int start = findHeader(data, index);
            if (start < 0) {
                index = data.length;
                break;
            }
            if (data.length - start < 6) {
                index = start;
                break;
            }
            int len = data[start + 3] & 0xff;
            if (len < 6 || len > 128) {
                index = start + 1;
                continue;
            }
            if (data.length - start < len) {
                index = start;
                break;
            }
            byte[] frame = Arrays.copyOfRange(data, start, start + len);
            if (AdapterProtocol.validChecksum(frame)) {
                if (listener != null) listener.onFrame(frame);
                index = start + len;
            } else {
                long now = System.currentTimeMillis();
                if (now - lastBadChecksumAt > 10000L) {
                    lastBadChecksumAt = now;
                    AppLog.line(app, "USB rx bad checksum: " + AdapterProtocol.hex(frame));
                }
                index = start + 1;
            }
        }
        incoming.reset();
        if (index < data.length) incoming.write(data, index, data.length - index);
    }

    private int findHeader(byte[] data, int from) {
        for (int i = from; i <= data.length - 3; i++) {
            if ((data[i] & 0xff) != 0xBB) continue;
            int second = data[i + 1] & 0xff;
            int third = data[i + 2] & 0xff;
            if ((second == 0xA1 && third == 0x41) || (second == 0x41 && third == 0xA1)) return i;
        }
        return -1;
    }

    private UsbSerialDriver findCanboxDriver(List<UsbSerialDriver> drivers) {
        UsbSerialDriver fallback = null;
        for (UsbSerialDriver candidate : drivers) {
            UsbDevice device = candidate.getDevice();
            if (isKnownCanbox(device)) return candidate;
            if (fallback == null) fallback = candidate;
        }
        return fallback;
    }

    private boolean isKnownCanbox(UsbDevice device) {
        if (device == null) return false;
        int vendor = device.getVendorId();
        int product = device.getProductId();
        return (vendor == FTDI_VENDOR_ID && product == FTDI_CANBOX_PRODUCT_ID)
                || (vendor == STM_VENDOR_ID && product == STM_CDC_PRODUCT_ID);
    }

    public static void onPermissionResult(UsbDevice device, boolean granted) {
        if (device == null) return;
        if (granted) clearPermissionRequest(device);
    }

    private boolean requestPermission(UsbDevice device) {
        String key = permissionKey(device);
        synchronized (REQUESTED_USB_PERMISSIONS) {
            if (REQUESTED_USB_PERMISSIONS.contains(key)) return false;
            if (!AppSettings.shouldRequestUsbPermission(app, key)) return false;
            REQUESTED_USB_PERMISSIONS.add(key);
        }
        Intent intent = new Intent(app, UsbPermissionReceiver.class);
        intent.setAction(ACTION_USB_PERMISSION);
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

    public static void clearPermissionRequest(Context context, UsbDevice device) {
        if (context == null || device == null) return;
        String key = permissionKey(device);
        clearPermissionRequest(device);
        AppSettings.clearUsbPermissionRequest(context.getApplicationContext(), key);
    }

    private static String permissionKey(UsbDevice device) {
        if (device == null) return "unknown";
        return device.getDeviceName() + ":"
                + device.getVendorId() + ":"
                + device.getProductId();
    }

    private void setUsb(boolean connected, String text) {
        AdapterState current = StateStore.adapter();
        StateStore.setAdapter(app, current.withUsb(connected, text));
    }
}
