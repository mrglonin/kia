package kia.app.diagnostics;

import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.net.Uri;
import android.os.Environment;
import android.provider.MediaStore;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayDeque;
import java.util.Date;
import java.util.Locale;
import java.util.zip.GZIPOutputStream;

import kia.app.core.AppLog;
import kia.app.core.StateStore;
import kia.app.core.model.DiagnosticState;
import kia.app.core.settings.AppSettings;
import kia.app.protocol.adapter.AdapterProtocol;

public final class CanLogger {
    private static final int MAX_FRAMES = 50000;
    private static CanLogger instance;

    private final Context app;
    private final ArrayDeque<String> frames = new ArrayDeque<>();
    private boolean recording;
    private int count;

    private CanLogger(Context context) {
        this.app = context.getApplicationContext();
    }

    public static synchronized CanLogger get(Context context) {
        if (instance == null) instance = new CanLogger(context);
        return instance;
    }

    public synchronized void setRecording(boolean value) {
        if (value && !recording) {
            frames.clear();
            count = 0;
            line("CAN START bus=" + AppSettings.loggerBusLabel(app));
        } else if (!value && recording) {
            line("CAN STOP frames=" + count);
        }
        recording = value;
        StateStore.setDiagnostics(app, StateStore.diagnostics().withRaw(value));
    }

    public synchronized boolean recording() {
        return recording;
    }

    public synchronized void recordRawFrame(byte[] frame) {
        if (frame == null) return;
        String text = stamp() + " RAW " + AdapterProtocol.hex(frame);
        if (recording && count < MAX_FRAMES) {
            frames.addLast(text);
            count++;
        }
        StateStore.setDiagnostics(app, StateStore.diagnostics().withFrame(text, count));
        if (recording && count >= MAX_FRAMES) setRecording(false);
    }

    public synchronized void recordGsFrame(int channel, int canId, byte[] data, long timestampUs) {
        if (data == null || !acceptBus(channel)) return;
        String text = stamp() + " " + channelLabel(channel) + " "
                + String.format(Locale.US, "%03X", canId & 0x1fffffff)
                + "#" + compactHex(data)
                + " dlc=" + data.length
                + (timestampUs > 0L ? " dev_us=" + timestampUs : "");
        if (recording && count < MAX_FRAMES) {
            frames.addLast(text);
            count++;
        }
        StateStore.setDiagnostics(app, StateStore.diagnostics().withFrame(text, count));
        if (recording && count >= MAX_FRAMES) setRecording(false);
    }

    public synchronized void recordSnapshot(byte[] frame) {
        if (frame == null) return;
        StateStore.setDiagnostics(app, StateStore.diagnostics()
                .withFrame(stamp() + " SNAPSHOT " + AdapterProtocol.hex(frame), count));
    }

    public synchronized File saveCompressed() throws Exception {
        String text = exportText();
        String name = "kia_canbus_can_" + new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(new Date()) + ".log.gz";
        byte[] data = text.getBytes(StandardCharsets.UTF_8);
        File file = writeDownloads(name, data);
        StateStore.setDiagnostics(app, StateStore.diagnostics().withSaved(file.getAbsolutePath()));
        AppLog.line(app, "CAN log saved: " + file.getAbsolutePath());
        return file;
    }

    public synchronized void clear() {
        frames.clear();
        count = 0;
        StateStore.setDiagnostics(app, StateStore.diagnostics().withFrame("", 0).withSaved(""));
        AppLog.line(app, "CAN logger: buffer cleared");
    }

    public synchronized String exportText() {
        StringBuilder out = new StringBuilder();
        for (String frame : frames) {
            if (out.length() > 0) out.append('\n');
            out.append(frame);
        }
        return out.toString();
    }

    private void line(String value) {
        frames.addLast(stamp() + " " + value);
    }

    private boolean acceptBus(int channel) {
        int mode = AppSettings.loggerBusMode(app);
        if (mode == AppSettings.LOGGER_BUS_BOTH) return true;
        if (mode == AppSettings.LOGGER_BUS_C) return channel == 1;
        return channel == 0;
    }

    private static String channelLabel(int channel) {
        if (channel == 0) return "M-CAN";
        if (channel == 1) return "C-CAN";
        return "CAN" + channel;
    }

    private static String compactHex(byte[] data) {
        StringBuilder out = new StringBuilder();
        for (byte b : data) {
            int value = b & 0xff;
            if (value < 16) out.append('0');
            out.append(Integer.toHexString(value).toUpperCase(Locale.US));
        }
        return out.toString();
    }

    private File writeDownloads(String name, byte[] data) throws Exception {
        File expected = new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), name);
        Uri uri = null;
        try {
            ContentResolver resolver = app.getContentResolver();
            ContentValues values = new ContentValues();
            values.put(MediaStore.MediaColumns.DISPLAY_NAME, name);
            values.put(MediaStore.MediaColumns.MIME_TYPE, "application/gzip");
            values.put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS);
            values.put(MediaStore.MediaColumns.IS_PENDING, 1);
            uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values);
            if (uri == null) throw new IllegalStateException("Downloads unavailable");
            try (OutputStream out = resolver.openOutputStream(uri)) {
                if (out == null) throw new IllegalStateException("Downloads stream unavailable");
                writeGzip(out, data);
            }
            values.clear();
            values.put(MediaStore.MediaColumns.IS_PENDING, 0);
            resolver.update(uri, values, null, null);
            return expected;
        } catch (Exception e) {
            if (uri != null) {
                try {
                    app.getContentResolver().delete(uri, null, null);
                } catch (Exception ignored) {
                }
            }
            File dir = app.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS);
            if (dir == null) dir = app.getFilesDir();
            File file = new File(dir, name);
            try (FileOutputStream out = new FileOutputStream(file)) {
                writeGzip(out, data);
            }
            return file;
        }
    }

    private void writeGzip(OutputStream out, byte[] data) throws Exception {
        try (GZIPOutputStream gzip = new GZIPOutputStream(out)) {
            gzip.write(data);
        }
    }

    private static String stamp() {
        return new SimpleDateFormat("HH:mm:ss.SSS", Locale.US).format(new Date());
    }
}
