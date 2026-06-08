package kia.app.protocol.adapter;

import android.content.Context;

import kia.app.core.AppLog;
import kia.app.core.StateStore;
import kia.app.transport.usb.UsbTransport;

public final class AdapterGateway {
    private static AdapterGateway instance;

    private final Context app;
    private final UsbTransport transport;
    private final FrameRouter router;
    private volatile boolean exclusiveUpdateMode;

    private AdapterGateway(Context context) {
        this.app = context.getApplicationContext();
        this.router = new FrameRouter(app);
        this.transport = new UsbTransport(app, router::onFrame);
    }

    public static synchronized AdapterGateway get(Context context) {
        if (instance == null) instance = new AdapterGateway(context);
        return instance;
    }

    public void start() {
        transport.connect();
    }

    public void stop() {
        transport.close();
    }

    public boolean usbReady() {
        return transport.ready();
    }

    public void send(AdapterCommand command) {
        if (command == null || command.frame == null) return;
        boolean importantMedia = isImportantMediaCommand(command.label);
        if (exclusiveUpdateMode && !command.firmwareUpdate) {
            if (!command.quiet || importantMedia) {
                AppLog.line(app, "Adapter: blocked during firmware update " + command.label);
            }
            return;
        }
        if (command.label.startsWith("call ") || importantMedia || isNavigationCommand(command.label)) {
            AppLog.line(app, "Adapter TX: " + command.label
                    + " usb=" + transport.ready()
                    + " " + AdapterProtocol.hex(command.frame));
        }
        transport.write(command.frame, command.quiet);
    }

    public void send(byte[] frame, boolean quiet) {
        if (exclusiveUpdateMode && (frame == null || frame.length < 5
                || (frame[4] & 0xff) != AdapterProtocol.CMD_FIRMWARE)) {
            if (!quiet) AppLog.line(app, "Adapter: blocked raw frame during firmware update");
            return;
        }
        transport.write(frame, quiet);
    }

    public void beginExclusiveUpdate() {
        exclusiveUpdateMode = true;
        StateStore.setUpdates(app, StateStore.updates().withExclusiveUsbMode(true));
        AppLog.line(app, "Adapter: exclusive firmware update mode on");
    }

    public void endExclusiveUpdate() {
        exclusiveUpdateMode = false;
        StateStore.setUpdates(app, StateStore.updates().withExclusiveUsbMode(false));
        AppLog.line(app, "Adapter: exclusive firmware update mode off");
    }

    public boolean exclusiveUpdateMode() {
        return exclusiveUpdateMode;
    }

    public void requestAdapterInfo() {
        requestAdapterInfo(false);
    }

    public void requestAdapterInfoQuiet() {
        requestAdapterInfo(true);
    }

    private void requestAdapterInfo(boolean quiet) {
        if (!quiet) AppLog.line(app, "Adapter: request info");
        send(command("uid", AdapterProtocol.requestAdapterUid(), quiet));
        send(command("version", AdapterProtocol.requestAdapterVersion(), quiet));
        send(command("health", AdapterProtocol.requestHealth(), quiet));
        send(AdapterCommand.quiet("snapshot", AdapterProtocol.requestVehicleSnapshot()));
    }

    private static AdapterCommand command(String label, byte[] frame, boolean quiet) {
        return quiet ? AdapterCommand.quiet(label, frame) : AdapterCommand.loud(label, frame);
    }

    private static boolean isImportantMediaCommand(String label) {
        if (label == null) return false;
        String lower = label.toLowerCase();
        return lower.contains("media off")
                || lower.contains("media text")
                || lower.contains("source")
                || lower.contains("radio search");
    }

    private static boolean isNavigationCommand(String label) {
        if (label == null) return false;
        String lower = label.toLowerCase();
        return lower.startsWith("nav ");
    }

    public void setRawStream(boolean enabled) {
        send(AdapterCommand.loud("raw stream " + enabled, AdapterProtocol.rawStream(enabled)));
    }

    public void pollRawCan() {
        send(AdapterCommand.quiet("raw poll", AdapterProtocol.rawCanPoll()));
    }

    public void pollVehicleSnapshot() {
        send(AdapterCommand.quiet("snapshot", AdapterProtocol.requestVehicleSnapshot()));
    }

    public void pollTpms() {
        send(AdapterCommand.quiet("TPMS poll", AdapterProtocol.requestTpms()));
    }
}
