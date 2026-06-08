package kia.app.core.model;

public final class DiagnosticState {
    public final boolean rawCanEnabled;
    public final int capturedFrames;
    public final String lastFrame;
    public final String lastSaved;

    public DiagnosticState(boolean rawCanEnabled, int capturedFrames, String lastFrame, String lastSaved) {
        this.rawCanEnabled = rawCanEnabled;
        this.capturedFrames = capturedFrames;
        this.lastFrame = safe(lastFrame);
        this.lastSaved = safe(lastSaved);
    }

    public static DiagnosticState empty() {
        return new DiagnosticState(false, 0, "", "");
    }

    public DiagnosticState withRaw(boolean value) {
        return new DiagnosticState(value, capturedFrames, lastFrame, lastSaved);
    }

    public DiagnosticState withFrame(String frame, int count) {
        return new DiagnosticState(rawCanEnabled, count, frame, lastSaved);
    }

    public DiagnosticState withSaved(String path) {
        return new DiagnosticState(rawCanEnabled, capturedFrames, lastFrame, path);
    }

    private static String safe(String value) {
        return value == null ? "" : value;
    }
}
