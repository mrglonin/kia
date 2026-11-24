package kia.app.transport.usb

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbManager
import android.os.Build
import android.os.SystemClock
import com.hoho.android.usbserial.driver.UsbSerialDriver
import com.hoho.android.usbserial.driver.UsbSerialPort
import com.hoho.android.usbserial.driver.UsbSerialProber
import java.io.IOException
import java.util.Arrays
import java.util.HashSet
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import kia.app.core.AppLog
import kia.app.core.StateStore
import kia.app.core.model.AdapterState
import kia.app.core.settings.AppSettings
import kia.app.entry.UsbPermissionReceiver
import kia.app.protocol.adapter.AdapterProtocol
import kia.app.protocol.adapter.AdapterTxOutcome

class UsbTransport(context: Context?, listener: kia.app.transport.usb.UsbTransport.Listener?) {
    fun interface Listener {
        fun onFrame(frame: ByteArray?, connectionEpoch: Long, receivedElapsed: Long)
    }

    private val app: Context?
    private val usbManager: UsbManager?
    private val listener: kia.app.transport.usb.UsbTransport.Listener?
    private val pending: PendingFrameQueue = PendingFrameQueue(UsbTransport.MAX_QUEUE)
    private val incoming: UsbIncomingBuffer = UsbIncomingBuffer()
    private val writeOrder: UsbWriteOrderGate = UsbWriteOrderGate()
    private val maintenanceExecutor: ScheduledExecutorService? =
        Executors.newSingleThreadScheduledExecutor({ runnable ->
            val thread: Thread = Thread(runnable, "kia-canbus-usb-maintenance")
            thread.setDaemon(true)
            thread
        })

    private var port: UsbSerialPort? = null
    private var readThread: Thread? = null
    private var readRunning: Boolean = false
    private var desiredConnection: Boolean = false
    private var connectScheduled: Boolean = false
    private var connecting: Boolean = false
    private var connectionEpoch: Long = 0
    private var readerGeneration: Long = 0
    private var connectedAtElapsed: Long = 0
    private var lastRxAtElapsed: Long = 0
    private var reconnectNotBeforeElapsed: Long = 0
    private var lastQuietConnectAt: Long = 0
    private var lastBadChecksumAt: Long = 0

    init {
        this.app = context!!.getApplicationContext()
        this.usbManager = app.getSystemService(Context.USB_SERVICE) as UsbManager?
        this.listener = listener
    }

    @kotlin.jvm.Synchronized
    fun ready(): Boolean {
        return port != null && readRunning
    }

    @kotlin.jvm.Synchronized
    fun connectionEpoch(): Long {
        return connectionEpoch
    }

    @kotlin.jvm.Synchronized
    fun connectedAtElapsedRealtime(): Long {
        return connectedAtElapsed
    }

    @kotlin.jvm.Synchronized
    fun lastRxAtElapsedRealtime(): Long {
        return lastRxAtElapsed
    }

    /** Starts connection work on the dedicated maintenance thread. */
    fun connect() {
        synchronized(this) { desiredConnection = true }
        scheduleConnect(0L)
    }

    /** Forces a generation-safe reconnect without doing USB open/flush work on the caller. */
    fun reconnect(reason: String?) {
        val active: UsbSerialPort?
        val generation: Long
        synchronized(this) {
            if (!desiredConnection) return
            active = port
            generation = readerGeneration
        }
        if (active == null) {
            scheduleConnect(0L)
            return
        }
        handleConnectionFailure(
            active,
            generation,
            "USB: переподключение " + UsbTransport.cleanReason(reason),
            false,
        )
    }

    private fun scheduleConnect(delayMs: Long) {
        val effectiveDelayMs: Long
        synchronized(this) {
            if (!desiredConnection || port != null || connecting || connectScheduled) return
            val now: Long = SystemClock.elapsedRealtime()
            effectiveDelayMs =
                UsbReconnectBackoffPolicy.effectiveDelay(delayMs, now, reconnectNotBeforeElapsed)
            connectScheduled = true
        }
        maintenanceExecutor!!.schedule(
            {
                synchronized(this@UsbTransport) { connectScheduled = false }
                connectNow()
            },
            effectiveDelayMs,
            TimeUnit.MILLISECONDS,
        )
    }

    private fun connectNow() {
        var retryDelayMs: Long = 0L
        synchronized(this) {
            if (!desiredConnection || port != null || connecting || usbManager == null) return
            val now: Long = SystemClock.elapsedRealtime()
            if (reconnectNotBeforeElapsed > now) {
                retryDelayMs = reconnectNotBeforeElapsed - now
            } else {
                connecting = true
            }
        }
        if (retryDelayMs > 0L) {
            scheduleConnect(retryDelayMs)
            return
        }
        var candidate: UsbSerialPort? = null
        try {
            val drivers: List<UsbSerialDriver?>? =
                UsbSerialProber.getDefaultProber().findAllDrivers(usbManager)
            if (drivers == null || drivers.isEmpty()) {
                setUsb(false, "USB: адаптер не найден")
                return
            }
            val driver: UsbSerialDriver? = findCanboxDriver(drivers)
            if (driver == null) {
                setUsb(false, "USB: подходящий адаптер не найден")
                return
            }
            val device: UsbDevice? = driver.getDevice()
            if (!usbManager!!.hasPermission(device)) {
                if (requestPermission(device)) {
                    setUsb(false, "USB: запрошено разрешение " + device!!.getDeviceName())
                } else {
                    setUsb(false, "USB: ждём разрешение " + device!!.getDeviceName())
                }
                return
            }
            UsbTransport.clearPermissionRequest(device)
            AppSettings.clearUsbPermissionRequest(app!!, UsbTransport.permissionKey(device))
            val connection: UsbDeviceConnection? = usbManager.openDevice(device)
            if (connection == null) {
                setUsb(false, "USB: не удалось открыть " + device!!.getDeviceName())
                return
            }
            candidate = driver.getPorts().get(0)
            candidate.open(connection)
            candidate.setParameters(115200, 8, UsbSerialPort.STOPBITS_1, UsbSerialPort.PARITY_NONE)
            incoming.reset()

            val openedPort: UsbSerialPort = candidate
            val installed: Boolean =
                writeOrder.run { installAndFlushSerialized(openedPort, device!!) }
            if (!installed) {
                closePort(candidate)
                candidate = null
                return
            }
            candidate = null
        } catch (e: Exception) {
            if (candidate != null) closePort(candidate)
            setUsb(false, "USB: ошибка " + e.javaClass.getSimpleName() + " " + e.message)
        } finally {
            synchronized(this) { connecting = false }
        }
    }

    fun write(frame: ByteArray?, quiet: Boolean): AdapterTxOutcome? {
        if (frame == null || frame.size == 0) return AdapterTxOutcome.BLOCKED
        val copy = Arrays.copyOf(frame, frame.size)
        return writeOrQueue(copy, quiet)
    }

    /** Called while [.writeOrder] is held, before any live writer can use the new port. */
    private fun installAndFlushSerialized(candidate: UsbSerialPort, device: UsbDevice): Boolean {
        var generation: Long = -1L
        var installed: Boolean = false
        try {
            synchronized(this) {
                if (!desiredConnection || port != null) return false
                port = candidate
                connectionEpoch++
                readerGeneration++
                generation = readerGeneration
                connectedAtElapsed = SystemClock.elapsedRealtime()
                lastRxAtElapsed = 0L
                reconnectNotBeforeElapsed = 0L
                readRunning = true
                val reader: Thread = createReader(candidate, generation)
                readThread = reader
                try {
                    reader.start()
                    installed = true
                } catch (e: RuntimeException) {
                    port = null
                    readThread = null
                    readRunning = false
                    readerGeneration++
                    connectedAtElapsed = 0L
                    throw e
                }
            }
            synchronized(this) {
                if (!readerCurrentLocked(candidate, generation)) return true
                setUsb(true, "USB: подключено " + device.getDeviceName() + " 115200 8N1")
            }
            flushQueue(candidate, generation)
            return true
        } catch (e: RuntimeException) {
            // Keep ready() truthful even if an unexpected app-side callback/pending operation
            // fails after the physical port was published.
            if (installed) {
                handleConnectionFailure(
                    candidate,
                    generation,
                    "USB: ошибка запуска " + e.javaClass.getSimpleName(),
                    false,
                )
            }
            throw e
        }
    }

    fun close() {
        val detached: UsbSerialPort?
        val reader: Thread?
        synchronized(this) {
            desiredConnection = false
            connectScheduled = false
            detached = port
            reader = readThread
            port = null
            readThread = null
            readRunning = false
            readerGeneration++
            connectedAtElapsed = 0L
            lastRxAtElapsed = 0L
            reconnectNotBeforeElapsed = 0L
            incoming.reset()
            setUsb(false, "USB: закрыто")
        }
        if (reader != null && reader !== Thread.currentThread()) reader.interrupt()
        if (detached != null) maintenanceExecutor!!.execute({ closePort(detached) })
    }

    private fun writeOrQueue(frame: ByteArray?, quiet: Boolean): AdapterTxOutcome? {
        val attempt: WriteAttempt? = writeOrder.run { writeSerialized(frame, quiet) }
        if (attempt!!.requestConnect) scheduleConnect(0L)
        return attempt.outcome
    }

    /** Runs under the shared publication/flush/live-write barrier. */
    private fun writeSerialized(frame: ByteArray?, quiet: Boolean): WriteAttempt {
        val active: UsbSerialPort?
        val generation: Long
        var requestConnect: Boolean = false
        var queuedWithoutPort: Boolean = false
        synchronized(this) {
            active = port
            generation = readerGeneration
            if (PendingFrameQueue.isNavigationOff(frame!!) && active != null) {
                pending.invalidateNavigation()
            }
            if (active == null) {
                if (!quiet) queuedWithoutPort = pending.offer(frame)
                val now: Long = SystemClock.elapsedRealtime()
                if (
                    !quiet ||
                        lastQuietConnectAt <= 0L ||
                        now < lastQuietConnectAt ||
                        now - lastQuietConnectAt > UsbTransport.QUIET_CONNECT_MIN_INTERVAL_MS
                ) {
                    lastQuietConnectAt = now
                    requestConnect = true
                }
            }
        }
        if (active == null) {
            val outcome: AdapterTxOutcome =
                if (queuedWithoutPort) AdapterTxOutcome.QUEUED else AdapterTxOutcome.BLOCKED
            return WriteAttempt(outcome, requestConnect)
        }
        try {
            active.write(frame, 300)
        } catch (e: IOException) {
            var replayQueued: Boolean = false
            if (!quiet) {
                synchronized(this) { replayQueued = pending.offer(frame) }
            }
            // Detach before releasing writeOrder so another producer cannot write a newer frame
            // ahead of this failed replay-safe frame and leave stale state queued behind it.
            handleConnectionFailure(
                active,
                generation,
                "USB: запись остановлена " + e.javaClass.getSimpleName(),
                !quiet,
            )
            return WriteAttempt(
                if (replayQueued) AdapterTxOutcome.QUEUED else AdapterTxOutcome.BLOCKED,
                false,
            )
        } catch (e: RuntimeException) {
            var replayQueued: Boolean = false
            if (!quiet) {
                synchronized(this) { replayQueued = pending.offer(frame) }
            }
            handleConnectionFailure(
                active,
                generation,
                "USB: запись остановлена " + e.javaClass.getSimpleName(),
                !quiet,
            )
            return WriteAttempt(
                if (replayQueued) AdapterTxOutcome.QUEUED else AdapterTxOutcome.BLOCKED,
                false,
            )
        }
        // A completed serial write is ambiguous if a concurrent watchdog invalidated the session:
        // replaying it can duplicate firmware/raw actions. Report the completed attempt as written;
        // stateful navigation/media producers will reassert on the next connection epoch.
        return WriteAttempt(AdapterTxOutcome.WRITTEN, false)
    }

    private fun flushQueue(active: UsbSerialPort?, generation: Long) {
        while (true) {
            val frame: ByteArray?
            synchronized(this) {
                if (!readerCurrentLocked(active, generation)) return
                frame = pending.poll()
            }
            if (frame == null) return
            if (writeOrQueue(frame, false) != AdapterTxOutcome.WRITTEN) return
        }
    }

    private fun createReader(active: UsbSerialPort, generation: Long): Thread {
        return Thread(
            {
                val buffer: ByteArray = ByteArray(128)
                try {
                    while (readerCurrent(active, generation)) {
                        val read: Int = active.read(buffer, 500)
                        if (read > 0) appendIncoming(active, generation, buffer, read)
                    }
                } catch (e: IOException) {
                    handleConnectionFailure(
                        active,
                        generation,
                        "USB: чтение остановлено " + e.javaClass.getSimpleName(),
                        false,
                    )
                } catch (e: RuntimeException) {
                    handleConnectionFailure(
                        active,
                        generation,
                        "USB: чтение остановлено " + e.javaClass.getSimpleName(),
                        false,
                    )
                } finally {
                    // Covers unchecked Errors and unexpected exits without leaving a false-ready
                    // port.
                    if (readerCurrent(active, generation)) {
                        handleConnectionFailure(
                            active,
                            generation,
                            "USB: поток чтения завершился",
                            false,
                        )
                    }
                }
            },
            "kia-canbus-usb-reader-" + generation,
        )
    }

    private fun appendIncoming(
        active: UsbSerialPort?,
        generation: Long,
        data: ByteArray?,
        count: Int,
    ) {
        if (!readerCurrent(active, generation)) return
        // append() only parses and returns data. Listener callbacks happen after its lock is gone.
        val parsed = incoming.append(data, count)
        for (bad: ByteArray? in parsed.badChecksumFrames!!) {
            val now: Long = SystemClock.elapsedRealtime()
            val log: Boolean
            synchronized(this) {
                log =
                    lastBadChecksumAt <= 0L ||
                        now < lastBadChecksumAt ||
                        now - lastBadChecksumAt > 10000L
                if (log) lastBadChecksumAt = now
            }
            if (log) AppLog.line(app, "USB rx bad checksum: " + AdapterProtocol.hex(bad))
        }
        for (frame: ByteArray? in parsed.frames!!) {
            val frameEpoch: Long
            val receivedElapsed: Long
            synchronized(this) {
                if (!readerCurrentLocked(active, generation)) return
                lastRxAtElapsed = SystemClock.elapsedRealtime()
                frameEpoch = connectionEpoch
                receivedElapsed = lastRxAtElapsed
            }
            if (listener == null) continue
            try {
                listener.onFrame(frame, frameEpoch, receivedElapsed)
            } catch (e: RuntimeException) {
                AppLog.line(app, "USB rx listener error " + e.javaClass.getSimpleName())
            }
        }
    }

    private fun readerCurrent(active: UsbSerialPort?, generation: Long): Boolean {
        synchronized(this) {
            return readerCurrentLocked(active, generation)
        }
    }

    private fun readerCurrentLocked(active: UsbSerialPort?, generation: Long): Boolean {
        return UsbReaderGenerationPolicy.current(
            readRunning,
            active === port,
            readerGeneration,
            generation,
        )
    }

    private fun handleConnectionFailure(
        active: UsbSerialPort?,
        generation: Long,
        status: String?,
        loud: Boolean,
    ) {
        val reader: Thread?
        synchronized(this) {
            if (!readerCurrentLocked(active, generation)) return
            port = null
            reader = readThread
            readThread = null
            readRunning = false
            readerGeneration++
            connectedAtElapsed = 0L
            lastRxAtElapsed = 0L
            val now: Long = SystemClock.elapsedRealtime()
            reconnectNotBeforeElapsed =
                UsbReconnectBackoffPolicy.extendNotBefore(
                    reconnectNotBeforeElapsed,
                    now,
                    UsbTransport.RECONNECT_BACKOFF_MS,
                )
            incoming.reset()
            setUsb(false, status)
        }
        if (reader != null && reader !== Thread.currentThread()) reader.interrupt()
        AppLog.line(app, status)
        if (loud) AppLog.line(app, "USB: запись не прошла, переподключение")
        maintenanceExecutor!!.execute({
            closePort(active)
            scheduleConnect(UsbTransport.RECONNECT_BACKOFF_MS)
        })
    }

    private fun closePort(active: UsbSerialPort?) {
        if (active == null) return
        writeOrder.run<Any?>(
            UsbWriteOrderGate.Operation {
                try {
                    active.close()
                } catch (ignored: Exception) {}
                null
            }
        )
    }

    private class WriteAttempt(outcome: AdapterTxOutcome?, requestConnect: Boolean) {
        @JvmField val outcome: AdapterTxOutcome?
        @JvmField val requestConnect: Boolean

        init {
            this.outcome = outcome
            this.requestConnect = requestConnect
        }
    }

    private fun findCanboxDriver(drivers: List<UsbSerialDriver?>): UsbSerialDriver? {
        var fallback: UsbSerialDriver? = null
        for (candidate in drivers) {
            val device: UsbDevice? = candidate!!.getDevice()
            if (isKnownCanbox(device)) return candidate
            if (fallback == null) fallback = candidate
        }
        return fallback
    }

    private fun isKnownCanbox(device: UsbDevice?): Boolean {
        if (device == null) return false
        val vendor: Int = device.getVendorId()
        val product: Int = device.getProductId()
        return (vendor == UsbTransport.FTDI_VENDOR_ID &&
            product == UsbTransport.FTDI_CANBOX_PRODUCT_ID) ||
            (vendor == UsbTransport.STM_VENDOR_ID && product == UsbTransport.STM_CDC_PRODUCT_ID)
    }

    private fun requestPermission(device: UsbDevice?): Boolean {
        val key: String = UsbTransport.permissionKey(device)
        synchronized(UsbTransport.REQUESTED_USB_PERMISSIONS) {
            if (UsbTransport.REQUESTED_USB_PERMISSIONS.contains(key)) return false
            if (!AppSettings.shouldRequestUsbPermission(app!!, key)) return false
            UsbTransport.REQUESTED_USB_PERMISSIONS.add(key)
        }
        val intent: Intent = Intent(app, UsbPermissionReceiver::class.java)
        intent.setAction(UsbTransport.ACTION_USB_PERMISSION)
        var flags: Int = PendingIntent.FLAG_UPDATE_CURRENT
        if (Build.VERSION.SDK_INT >= 31) flags = flags or PendingIntent.FLAG_MUTABLE
        else flags = flags or PendingIntent.FLAG_IMMUTABLE
        val pi: PendingIntent? = PendingIntent.getBroadcast(app, key.hashCode(), intent, flags)
        AppSettings.markUsbPermissionRequested(app!!, key)
        usbManager!!.requestPermission(device, pi)
        return true
    }

    private fun setUsb(connected: Boolean, text: String?) {
        if (!connected) {
            // A roof sample belongs to a USB session; temperatures must not make it current.
            StateStore.setVehicle(app, StateStore.vehicle()!!.withoutSunroof())
        }
        val current: AdapterState? = StateStore.adapter()
        StateStore.setAdapter(app, current!!.withUsb(connected, text))
    }

    companion object {
        const val ACTION_USB_PERMISSION: String = "kia.app.USB_PERMISSION"

        private const val FTDI_VENDOR_ID: Int = 1027
        private const val FTDI_CANBOX_PRODUCT_ID: Int = 24577
        private const val STM_VENDOR_ID: Int = 1155
        private const val STM_CDC_PRODUCT_ID: Int = 22336
        private const val MAX_QUEUE: Int = 80
        private const val QUIET_CONNECT_MIN_INTERVAL_MS: Long = 1500L
        private const val RECONNECT_BACKOFF_MS: Long = 1000L
        private val REQUESTED_USB_PERMISSIONS: MutableSet<String?> = HashSet()

        @JvmStatic
        fun onPermissionResult(device: UsbDevice?, granted: Boolean) {
            if (device == null) return
            if (granted) UsbTransport.clearPermissionRequest(device)
        }

        private fun clearPermissionRequest(device: UsbDevice?) {
            synchronized(UsbTransport.REQUESTED_USB_PERMISSIONS) {
                UsbTransport.REQUESTED_USB_PERMISSIONS.remove(UsbTransport.permissionKey(device))
            }
        }

        @JvmStatic
        fun clearPermissionRequest(context: Context?, device: UsbDevice?) {
            if (context == null || device == null) return
            val key: String = UsbTransport.permissionKey(device)
            UsbTransport.clearPermissionRequest(device)
            AppSettings.clearUsbPermissionRequest(context.getApplicationContext(), key)
        }

        private fun permissionKey(device: UsbDevice?): String {
            if (device == null) return "unknown"
            return (device.getDeviceName() +
                ":" +
                device.getVendorId() +
                ":" +
                device.getProductId())
        }

        private fun cleanReason(reason: String?): String {
            val clean =
                if (reason == null) "" else reason.replace('\n', ' ').replace('\r', ' ').trim()
            return if (clean.isEmpty()) "по watchdog" else clean
        }
    }
}
