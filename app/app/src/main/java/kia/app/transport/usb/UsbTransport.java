package kia.app.transport.usb;

import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbDeviceConnection;
import android.hardware.usb.UsbManager;
import android.os.Build;
import android.os.SystemClock;

import com.hoho.android.usbserial.driver.UsbSerialDriver;
import com.hoho.android.usbserial.driver.UsbSerialPort;
import com.hoho.android.usbserial.driver.UsbSerialProber;

import java.io.IOException;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import kia.app.core.AppLog;
import kia.app.core.StateStore;
import kia.app.core.model.AdapterState;
import kia.app.core.settings.AppSettings;
import kia.app.entry.UsbPermissionReceiver;
import kia.app.protocol.adapter.AdapterProtocol;
import kia.app.protocol.adapter.AdapterTxOutcome;

public final class UsbTransport {
    public static final String ACTION_USB_PERMISSION = "kia.app.USB_PERMISSION";

    private static final int FTDI_VENDOR_ID = 1027;
    private static final int FTDI_CANBOX_PRODUCT_ID = 24577;
    private static final int STM_VENDOR_ID = 1155;
    private static final int STM_CDC_PRODUCT_ID = 22336;
    private static final int MAX_QUEUE = 80;
    private static final long QUIET_CONNECT_MIN_INTERVAL_MS = 1500L;
    private static final long RECONNECT_BACKOFF_MS = 1000L;
    private static final Set<String> REQUESTED_USB_PERMISSIONS = new HashSet<>();

    public interface Listener {
        void onFrame(byte[] frame);
    }

    private final Context app;
    private final UsbManager usbManager;
    private final Listener listener;
    private final PendingFrameQueue pending = new PendingFrameQueue(MAX_QUEUE);
    private final UsbIncomingBuffer incoming = new UsbIncomingBuffer();
    private final UsbWriteOrderGate writeOrder = new UsbWriteOrderGate();
    private final ScheduledExecutorService maintenanceExecutor =
            Executors.newSingleThreadScheduledExecutor(runnable -> {
                Thread thread = new Thread(runnable, "kia-canbus-usb-maintenance");
                thread.setDaemon(true);
                return thread;
            });

    private UsbSerialPort port;
    private Thread readThread;
    private boolean readRunning;
    private boolean desiredConnection;
    private boolean connectScheduled;
    private boolean connecting;
    private long connectionEpoch;
    private long readerGeneration;
    private long connectedAtElapsed;
    private long lastRxAtElapsed;
    private long reconnectNotBeforeElapsed;
    private long lastQuietConnectAt;
    private long lastBadChecksumAt;

    public UsbTransport(Context context, Listener listener) {
        this.app = context.getApplicationContext();
        this.usbManager = (UsbManager) app.getSystemService(Context.USB_SERVICE);
        this.listener = listener;
    }

    public synchronized boolean ready() {
        return port != null && readRunning;
    }

    public synchronized long connectionEpoch() {
        return connectionEpoch;
    }

    public synchronized long connectedAtElapsedRealtime() {
        return connectedAtElapsed;
    }

    public synchronized long lastRxAtElapsedRealtime() {
        return lastRxAtElapsed;
    }

    /** Starts connection work on the dedicated maintenance thread. */
    public void connect() {
        synchronized (this) {
            desiredConnection = true;
        }
        scheduleConnect(0L);
    }

    /** Forces a generation-safe reconnect without doing USB open/flush work on the caller. */
    public void reconnect(String reason) {
        UsbSerialPort active;
        long generation;
        synchronized (this) {
            if (!desiredConnection) return;
            active = port;
            generation = readerGeneration;
        }
        if (active == null) {
            scheduleConnect(0L);
            return;
        }
        handleConnectionFailure(active, generation,
                "USB: переподключение " + cleanReason(reason), false);
    }

    private void scheduleConnect(long delayMs) {
        long effectiveDelayMs;
        synchronized (this) {
            if (!desiredConnection || port != null || connecting || connectScheduled) return;
            long now = SystemClock.elapsedRealtime();
            effectiveDelayMs = UsbReconnectBackoffPolicy.effectiveDelay(
                    delayMs, now, reconnectNotBeforeElapsed);
            connectScheduled = true;
        }
        maintenanceExecutor.schedule(() -> {
            synchronized (UsbTransport.this) {
                connectScheduled = false;
            }
            connectNow();
        }, effectiveDelayMs, TimeUnit.MILLISECONDS);
    }

    private void connectNow() {
        long retryDelayMs = 0L;
        synchronized (this) {
            if (!desiredConnection || port != null || connecting || usbManager == null) return;
            long now = SystemClock.elapsedRealtime();
            if (reconnectNotBeforeElapsed > now) {
                retryDelayMs = reconnectNotBeforeElapsed - now;
            } else {
                connecting = true;
            }
        }
        if (retryDelayMs > 0L) {
            scheduleConnect(retryDelayMs);
            return;
        }
        UsbSerialPort candidate = null;
        try {
            List<UsbSerialDriver> drivers =
                    UsbSerialProber.getDefaultProber().findAllDrivers(usbManager);
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
            candidate = driver.getPorts().get(0);
            candidate.open(connection);
            candidate.setParameters(115200, 8,
                    UsbSerialPort.STOPBITS_1, UsbSerialPort.PARITY_NONE);
            incoming.reset();

            UsbSerialPort openedPort = candidate;
            boolean installed = writeOrder.run(
                    () -> installAndFlushSerialized(openedPort, device));
            if (!installed) {
                closePort(candidate);
                candidate = null;
                return;
            }
            candidate = null;
        } catch (Exception e) {
            if (candidate != null) closePort(candidate);
            setUsb(false, "USB: ошибка " + e.getClass().getSimpleName() + " " + e.getMessage());
        } finally {
            synchronized (this) {
                connecting = false;
            }
        }
    }

    public AdapterTxOutcome write(byte[] frame, boolean quiet) {
        if (frame == null || frame.length == 0) return AdapterTxOutcome.BLOCKED;
        byte[] copy = Arrays.copyOf(frame, frame.length);
        return writeOrQueue(copy, quiet);
    }

    /** Called while {@link #writeOrder} is held, before any live writer can use the new port. */
    private boolean installAndFlushSerialized(UsbSerialPort candidate, UsbDevice device) {
        long generation = -1L;
        boolean installed = false;
        try {
            synchronized (this) {
                if (!desiredConnection || port != null) return false;
                port = candidate;
                connectionEpoch++;
                readerGeneration++;
                generation = readerGeneration;
                connectedAtElapsed = SystemClock.elapsedRealtime();
                lastRxAtElapsed = 0L;
                reconnectNotBeforeElapsed = 0L;
                readRunning = true;
                Thread reader = createReader(candidate, generation);
                readThread = reader;
                try {
                    reader.start();
                    installed = true;
                } catch (RuntimeException e) {
                    port = null;
                    readThread = null;
                    readRunning = false;
                    readerGeneration++;
                    connectedAtElapsed = 0L;
                    throw e;
                }
            }
            synchronized (this) {
                if (!readerCurrentLocked(candidate, generation)) return true;
                setUsb(true, "USB: подключено " + device.getDeviceName() + " 115200 8N1");
            }
            flushQueue(candidate, generation);
            return true;
        } catch (RuntimeException e) {
            // Keep ready() truthful even if an unexpected app-side callback/pending operation
            // fails after the physical port was published.
            if (installed) {
                handleConnectionFailure(candidate, generation,
                        "USB: ошибка запуска " + e.getClass().getSimpleName(), false);
            }
            throw e;
        }
    }

    public void close() {
        UsbSerialPort detached;
        Thread reader;
        synchronized (this) {
            desiredConnection = false;
            connectScheduled = false;
            detached = port;
            reader = readThread;
            port = null;
            readThread = null;
            readRunning = false;
            readerGeneration++;
            connectedAtElapsed = 0L;
            lastRxAtElapsed = 0L;
            reconnectNotBeforeElapsed = 0L;
            incoming.reset();
            setUsb(false, "USB: закрыто");
        }
        if (reader != null && reader != Thread.currentThread()) reader.interrupt();
        if (detached != null) maintenanceExecutor.execute(() -> closePort(detached));
    }

    private AdapterTxOutcome writeOrQueue(byte[] frame, boolean quiet) {
        WriteAttempt attempt = writeOrder.run(() -> writeSerialized(frame, quiet));
        if (attempt.requestConnect) scheduleConnect(0L);
        return attempt.outcome;
    }

    /** Runs under the shared publication/flush/live-write barrier. */
    private WriteAttempt writeSerialized(byte[] frame, boolean quiet) {
        UsbSerialPort active;
        long generation;
        boolean requestConnect = false;
        boolean queuedWithoutPort = false;
        synchronized (this) {
            active = port;
            generation = readerGeneration;
            if (PendingFrameQueue.isNavigationOff(frame) && active != null) {
                pending.invalidateNavigation();
            }
            if (active == null) {
                if (!quiet) queuedWithoutPort = pending.offer(frame);
                long now = SystemClock.elapsedRealtime();
                if (!quiet || lastQuietConnectAt <= 0L || now < lastQuietConnectAt
                        || now - lastQuietConnectAt > QUIET_CONNECT_MIN_INTERVAL_MS) {
                    lastQuietConnectAt = now;
                    requestConnect = true;
                }
            }
        }
        if (active == null) {
            AdapterTxOutcome outcome = queuedWithoutPort
                    ? AdapterTxOutcome.QUEUED : AdapterTxOutcome.BLOCKED;
            return new WriteAttempt(outcome, requestConnect);
        }
        try {
            active.write(frame, 300);
        } catch (IOException | RuntimeException e) {
            boolean replayQueued = false;
            if (!quiet) {
                synchronized (this) {
                    replayQueued = pending.offer(frame);
                }
            }
            // Detach before releasing writeOrder so another producer cannot write a newer frame
            // ahead of this failed replay-safe frame and leave stale state queued behind it.
            handleConnectionFailure(active, generation,
                    "USB: запись остановлена " + e.getClass().getSimpleName(), !quiet);
            return new WriteAttempt(replayQueued
                    ? AdapterTxOutcome.QUEUED : AdapterTxOutcome.BLOCKED, false);
        }
        // A completed serial write is ambiguous if a concurrent watchdog invalidated the session:
        // replaying it can duplicate firmware/raw actions. Report the completed attempt as written;
        // stateful navigation/media producers will reassert on the next connection epoch.
        return new WriteAttempt(AdapterTxOutcome.WRITTEN, false);
    }

    private void flushQueue(UsbSerialPort active, long generation) {
        while (true) {
            byte[] frame;
            synchronized (this) {
                if (!readerCurrentLocked(active, generation)) return;
                frame = pending.poll();
            }
            if (frame == null) return;
            if (writeOrQueue(frame, false) != AdapterTxOutcome.WRITTEN) return;
        }
    }

    private Thread createReader(UsbSerialPort active, long generation) {
        return new Thread(() -> {
            byte[] buffer = new byte[128];
            try {
                while (readerCurrent(active, generation)) {
                    int read = active.read(buffer, 500);
                    if (read > 0) appendIncoming(active, generation, buffer, read);
                }
            } catch (IOException | RuntimeException e) {
                handleConnectionFailure(active, generation,
                        "USB: чтение остановлено " + e.getClass().getSimpleName(), false);
            } finally {
                // Covers unchecked Errors and unexpected exits without leaving a false-ready port.
                if (readerCurrent(active, generation)) {
                    handleConnectionFailure(active, generation,
                            "USB: поток чтения завершился", false);
                }
            }
        }, "kia-canbus-usb-reader-" + generation);
    }

    private void appendIncoming(UsbSerialPort active, long generation, byte[] data, int count) {
        if (!readerCurrent(active, generation)) return;
        // append() only parses and returns data. Listener callbacks happen after its lock is gone.
        UsbIncomingBuffer.ParseResult parsed = incoming.append(data, count);
        for (byte[] bad : parsed.badChecksumFrames) {
            long now = SystemClock.elapsedRealtime();
            boolean log;
            synchronized (this) {
                log = lastBadChecksumAt <= 0L || now < lastBadChecksumAt
                        || now - lastBadChecksumAt > 10000L;
                if (log) lastBadChecksumAt = now;
            }
            if (log) AppLog.line(app, "USB rx bad checksum: " + AdapterProtocol.hex(bad));
        }
        for (byte[] frame : parsed.frames) {
            synchronized (this) {
                if (!readerCurrentLocked(active, generation)) return;
                lastRxAtElapsed = SystemClock.elapsedRealtime();
            }
            if (listener == null) continue;
            try {
                listener.onFrame(frame);
            } catch (RuntimeException e) {
                AppLog.line(app, "USB rx listener error " + e.getClass().getSimpleName());
            }
        }
    }

    private boolean readerCurrent(UsbSerialPort active, long generation) {
        synchronized (this) {
            return readerCurrentLocked(active, generation);
        }
    }

    private boolean readerCurrentLocked(UsbSerialPort active, long generation) {
        return UsbReaderGenerationPolicy.current(
                readRunning, active == port, readerGeneration, generation);
    }

    private void handleConnectionFailure(UsbSerialPort active, long generation,
                                         String status, boolean loud) {
        Thread reader;
        synchronized (this) {
            if (!readerCurrentLocked(active, generation)) return;
            port = null;
            reader = readThread;
            readThread = null;
            readRunning = false;
            readerGeneration++;
            connectedAtElapsed = 0L;
            lastRxAtElapsed = 0L;
            long now = SystemClock.elapsedRealtime();
            reconnectNotBeforeElapsed = UsbReconnectBackoffPolicy.extendNotBefore(
                    reconnectNotBeforeElapsed, now, RECONNECT_BACKOFF_MS);
            incoming.reset();
            setUsb(false, status);
        }
        if (reader != null && reader != Thread.currentThread()) reader.interrupt();
        AppLog.line(app, status);
        if (loud) AppLog.line(app, "USB: запись не прошла, переподключение");
        maintenanceExecutor.execute(() -> {
            closePort(active);
            scheduleConnect(RECONNECT_BACKOFF_MS);
        });
    }

    private void closePort(UsbSerialPort active) {
        if (active == null) return;
        writeOrder.run(() -> {
            try {
                active.close();
            } catch (Exception ignored) {
            }
            return null;
        });
    }

    private static final class WriteAttempt {
        final AdapterTxOutcome outcome;
        final boolean requestConnect;

        WriteAttempt(AdapterTxOutcome outcome, boolean requestConnect) {
            this.outcome = outcome;
            this.requestConnect = requestConnect;
        }
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

    private static String cleanReason(String reason) {
        String clean = reason == null ? "" : reason.replace('\n', ' ').replace('\r', ' ').trim();
        return clean.isEmpty() ? "по watchdog" : clean;
    }

    private void setUsb(boolean connected, String text) {
        AdapterState current = StateStore.adapter();
        StateStore.setAdapter(app, current.withUsb(connected, text));
    }
}
