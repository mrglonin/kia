package kia.app.protocol.adapter

import android.content.Context
import java.util.Locale
import kia.app.core.AppLog
import kia.app.core.StateStore
import kia.app.transport.usb.UsbTransport

class AdapterGateway private constructor(context: Context) {
    private val app: Context?
    private val transport: UsbTransport
    private val router: FrameRouter
    private val infoDiscovery: AdapterInfoDiscoveryPolicy = AdapterInfoDiscoveryPolicy()
    @kotlin.concurrent.Volatile private var exclusiveUpdateMode: Boolean = false

    init {
        this.app = context.getApplicationContext()
        this.router = FrameRouter(app)
        this.transport =
            UsbTransport(
                app,
                kia.app.transport.usb.UsbTransport.Listener {
                    frame: ByteArray?,
                    epoch: Long,
                    receivedElapsed: Long ->
                    infoDiscovery.observeReply(frame, epoch)
                    router.onFrame(frame, epoch, receivedElapsed)
                },
            )
    }

    fun start() {
        transport.connect()
    }

    fun stop() {
        transport.close()
    }

    fun usbReady(): Boolean {
        return transport.ready()
    }

    fun connectionEpoch(): Long {
        return transport.connectionEpoch()
    }

    fun connectedAtElapsedRealtime(): Long {
        return transport.connectedAtElapsedRealtime()
    }

    fun lastRxAtElapsedRealtime(): Long {
        return transport.lastRxAtElapsedRealtime()
    }

    fun reconnect(reason: String?) {
        transport.reconnect(reason)
    }

    fun send(command: AdapterCommand?): AdapterTxOutcome? {
        if (command == null || command.frame == null) return AdapterTxOutcome.BLOCKED
        val importantMedia: Boolean = AdapterGateway.isImportantMediaCommand(command.label)
        if (exclusiveUpdateMode && !command.firmwareUpdate) {
            if (!command.quiet || importantMedia) {
                AppLog.line(app, "Adapter: blocked during firmware update " + command.label)
            }
            return AdapterTxOutcome.BLOCKED
        }
        val outcome: AdapterTxOutcome? = transport.write(command.frame, command.quiet)
        if (
            command.label.startsWith("call ") ||
                importantMedia ||
                AdapterGateway.isNavigationCommand(command.label)
        ) {
            val line: String =
                ("Adapter TX " +
                    outcome +
                    ": " +
                    command.label +
                    " " +
                    AdapterProtocol.hex(command.frame))
            if (AdapterGateway.isNavigationCommand(command.label)) AppLog.navigation(app, line)
            else AppLog.line(app, line)
        }
        return outcome
    }

    fun send(frame: ByteArray?, quiet: Boolean): AdapterTxOutcome? {
        if (
            exclusiveUpdateMode &&
                (frame == null ||
                    frame.size < 5 ||
                    (frame.get(4).toInt() and 0xff) != AdapterProtocol.CMD_FIRMWARE)
        ) {
            if (!quiet) AppLog.line(app, "Adapter: blocked raw frame during firmware update")
            return AdapterTxOutcome.BLOCKED
        }
        return transport.write(frame, quiet)
    }

    fun beginExclusiveUpdate() {
        exclusiveUpdateMode = true
        StateStore.updateUpdates(app) { current -> requireNotNull(current).withExclusiveUsbMode(true) }
        AppLog.line(app, "Adapter: exclusive firmware update mode on")
    }

    fun endExclusiveUpdate() {
        // An MCU restart need not detach the USB serial bridge or advance its connection epoch.
        if (exclusiveUpdateMode) infoDiscovery.invalidate()
        exclusiveUpdateMode = false
        StateStore.updateUpdates(app) { current -> requireNotNull(current).withExclusiveUsbMode(false) }
        AppLog.line(app, "Adapter: exclusive firmware update mode off")
    }

    fun exclusiveUpdateMode(): Boolean {
        return exclusiveUpdateMode
    }

    fun requestAdapterInfo() {
        requestAdapterInfo(false)
    }

    fun requestAdapterInfoQuiet() {
        requestAdapterInfo(true)
    }

    /** Periodic liveness probe; identity is retried only until received on this USB session. */
    fun requestHealthQuiet() {
        val missing: Int = infoDiscovery.missingRequests(transport.connectionEpoch())
        if ((missing and AdapterInfoDiscoveryPolicy.REQUEST_UID) != 0) {
            send(AdapterCommand.quiet("uid", AdapterProtocol.requestAdapterUid()))
        }
        if ((missing and AdapterInfoDiscoveryPolicy.REQUEST_VERSION) != 0) {
            send(AdapterCommand.quiet("version", AdapterProtocol.requestAdapterVersion()))
        }
        send(AdapterCommand.quiet("health", AdapterProtocol.requestHealth()))
    }

    private fun requestAdapterInfo(quiet: Boolean) {
        if (!quiet) AppLog.line(app, "Adapter: request info")
        send(AdapterGateway.command("uid", AdapterProtocol.requestAdapterUid(), quiet))
        send(AdapterGateway.command("version", AdapterProtocol.requestAdapterVersion(), quiet))
        send(AdapterGateway.command("health", AdapterProtocol.requestHealth(), quiet))
        send(AdapterCommand.quiet("snapshot", AdapterProtocol.requestVehicleSnapshot()))
    }

    fun setRawStream(enabled: Boolean) {
        send(AdapterCommand.loud("raw stream " + enabled, AdapterProtocol.rawStream(enabled)))
    }

    fun pollRawCan() {
        send(AdapterCommand.quiet("raw poll", AdapterProtocol.rawCanPoll()))
    }

    fun pollVehicleSnapshot() {
        send(AdapterCommand.quiet("snapshot", AdapterProtocol.requestVehicleSnapshot()))
    }

    fun pollTpms() {
        send(AdapterCommand.quiet("TPMS poll", AdapterProtocol.requestTpms()))
    }

    companion object {
        private var instance: AdapterGateway? = null

        @JvmStatic
        @kotlin.jvm.Synchronized
        fun get(context: Context): AdapterGateway {
            if (AdapterGateway.instance == null) AdapterGateway.instance = AdapterGateway(context)
            return AdapterGateway.instance!!
        }

        private fun command(label: String?, frame: ByteArray?, quiet: Boolean): AdapterCommand {
            return if (quiet) AdapterCommand.quiet(label, frame)
            else AdapterCommand.loud(label, frame)
        }

        private fun isImportantMediaCommand(label: String?): Boolean {
            if (label == null) return false
            val lower = label.lowercase(Locale.ROOT)
            return lower.contains("media off") ||
                lower.contains("media text") ||
                lower.contains("source") ||
                lower.contains("radio search")
        }

        private fun isNavigationCommand(label: String?): Boolean {
            if (label == null) return false
            val lower = label.lowercase(Locale.ROOT)
            return lower.startsWith("nav ")
        }
    }
}
