package kia.app.core;

import android.content.Context;
import android.content.Intent;
import android.util.Log;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayDeque;
import java.util.Date;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class AppLog {
    private static final int MAX_LINES = 250;
    private static final int NAV_LOG_MAX_BYTES = 256 * 1024;
    private static final int NAV_LOG_BACKUPS = 3;
    private static final String NAV_LOG_DIR = "navigation-logs";
    private static final String NAV_LOG_FILE = "navigation.log";
    private static final ArrayDeque<String> lines = new ArrayDeque<>();
    private static final ExecutorService navigationLogWriter =
            Executors.newSingleThreadExecutor(runnable -> {
                Thread thread = new Thread(runnable, "kia-navigation-log-writer");
                thread.setDaemon(true);
                return thread;
            });

    private AppLog() {
    }

    public static synchronized void line(Context context, String value) {
        addLine(context, stamp() + " " + (value == null ? "" : value));
    }

    /**
     * Adds a navigation diagnostic to the existing UI ring and to a bounded on-device log.
     *
     * <p>The durable log is stored in app-private storage and rotates at 256 KiB with three
     * backups, so route/TX evidence survives a process restart without unbounded disk growth.
     */
    public static synchronized void navigation(Context context, String value) {
        String message = value == null ? "" : value;
        addLine(context, stamp() + " " + message);
        if (context == null) return;
        Context app = context.getApplicationContext();
        if (app == null) app = context;
        Context target = app;
        String durableText = stampWithDate() + " " + message;
        navigationLogWriter.execute(() -> appendNavigationFile(target, durableText));
    }

    private static void addLine(Context context, String text) {
        while (lines.size() >= MAX_LINES) lines.pollFirst();
        lines.addLast(text);
        Log.i("Kia", text);
        StateStore.setLastLog(context, text);
    }

    public static synchronized String text() {
        StringBuilder out = new StringBuilder();
        for (String line : lines) {
            if (out.length() > 0) out.append('\n');
            out.append(line);
        }
        return out.toString();
    }

    public static synchronized String lastLines(int max) {
        StringBuilder out = new StringBuilder();
        int skip = Math.max(0, lines.size() - max);
        int i = 0;
        for (String line : lines) {
            if (i++ < skip) continue;
            if (out.length() > 0) out.append('\n');
            out.append(line);
        }
        return out.toString();
    }

    static void broadcast(Context context) {
        if (context == null) return;
        Intent intent = new Intent(AppIds.ACTION_STATE_CHANGED);
        intent.setPackage(context.getPackageName());
        context.sendBroadcast(intent);
    }

    private static String stamp() {
        return new SimpleDateFormat("HH:mm:ss", Locale.US).format(new Date());
    }

    private static String stampWithDate() {
        return new SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US).format(new Date());
    }

    private static void appendNavigationFile(Context context, String line) {
        try {
            File root = context.getExternalFilesDir(null);
            if (root == null) root = context.getFilesDir();
            File directory = new File(root, NAV_LOG_DIR);
            if (!directory.exists() && !directory.mkdirs() && !directory.isDirectory()) return;
            byte[] bytes = (line + "\n").getBytes(StandardCharsets.UTF_8);
            File current = new File(directory, NAV_LOG_FILE);
            if (current.length() + bytes.length > NAV_LOG_MAX_BYTES) {
                rotateNavigationFiles(directory, current);
            }
            try (FileOutputStream output = new FileOutputStream(current, true)) {
                output.write(bytes);
                output.flush();
            }
        } catch (Exception e) {
            Log.w("Kia", "Navigation durable log failed: " + e.getClass().getSimpleName());
        }
    }

    private static void rotateNavigationFiles(File directory, File current) {
        File oldest = navigationBackup(directory, NAV_LOG_BACKUPS);
        if (oldest.exists() && !oldest.delete()) {
            Log.w("Kia", "Navigation durable log: cannot delete oldest backup");
        }
        for (int index = NAV_LOG_BACKUPS - 1; index >= 1; index--) {
            File source = navigationBackup(directory, index);
            if (!source.exists()) continue;
            File target = navigationBackup(directory, index + 1);
            if (!source.renameTo(target)) {
                Log.w("Kia", "Navigation durable log: cannot rotate backup " + index);
            }
        }
        File first = navigationBackup(directory, 1);
        if (current.exists() && !current.renameTo(first)) {
            Log.w("Kia", "Navigation durable log: cannot rotate current file");
            try (FileOutputStream ignored = new FileOutputStream(current, false)) {
                // Truncate rather than letting a failed rename turn a bounded log into an
                // unbounded one.
            } catch (Exception e) {
                Log.w("Kia", "Navigation durable log: cannot truncate current file");
            }
        }
    }

    private static File navigationBackup(File directory, int index) {
        return new File(directory, NAV_LOG_FILE + "." + index);
    }
}
