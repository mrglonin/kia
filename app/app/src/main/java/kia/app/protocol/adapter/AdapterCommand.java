package kia.app.protocol.adapter;

public final class AdapterCommand {
    public final String label;
    public final byte[] frame;
    public final boolean quiet;
    public final boolean firmwareUpdate;

    public AdapterCommand(String label, byte[] frame, boolean quiet) {
        this(label, frame, quiet, false);
    }

    public AdapterCommand(String label, byte[] frame, boolean quiet, boolean firmwareUpdate) {
        this.label = label == null ? "" : label;
        this.frame = frame;
        this.quiet = quiet;
        this.firmwareUpdate = firmwareUpdate;
    }

    public static AdapterCommand loud(String label, byte[] frame) {
        return new AdapterCommand(label, frame, false);
    }

    public static AdapterCommand quiet(String label, byte[] frame) {
        return new AdapterCommand(label, frame, true);
    }

    public static AdapterCommand firmware(String label, byte[] frame, boolean quiet) {
        return new AdapterCommand(label, frame, quiet, true);
    }
}
