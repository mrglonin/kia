package kia.app.core;

import android.content.Context;
import android.content.Intent;
import android.util.Log;

import java.text.SimpleDateFormat;
import java.util.ArrayDeque;
import java.util.Date;
import java.util.Locale;

public final class AppLog {
    private static final int MAX_LINES = 250;
    private static final ArrayDeque<String> lines = new ArrayDeque<>();

    private AppLog() {
    }

    public static synchronized void line(Context context, String value) {
        String text = stamp() + " " + (value == null ? "" : value);
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
}
