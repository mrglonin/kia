package kia.app.diagnostics;

import android.content.Context;
import android.content.pm.PackageInfo;
import android.os.Build;
import android.provider.Settings;

import java.io.File;
import java.io.FileInputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import kia.app.core.AppLog;
import kia.app.core.StateStore;
import kia.app.core.model.AdapterState;
import kia.app.core.model.MediaState;
import kia.app.core.model.NavigationState;
import kia.app.core.model.RctaState;
import kia.app.core.model.TpmsState;
import kia.app.core.settings.AppSettings;

/** Creates a user-requested, bounded support archive without route or media text fields. */
public final class DiagnosticBundleExporter {
    public static final String FILE_NAME_PREFIX = "kia-diagnostics-";
    private static final long MAX_LOG_BYTES = 1024L * 1024L;

    private DiagnosticBundleExporter() {
    }

    public static void write(Context context, android.net.Uri target) throws Exception {
        if (context == null || target == null) throw new IllegalArgumentException("target");
        try (OutputStream output = context.getContentResolver().openOutputStream(target, "wt");
             ZipOutputStream zip = output == null ? null : new ZipOutputStream(output)) {
            if (zip == null) throw new IllegalStateException("Cannot open output");
            addText(zip, "summary.txt", summary(context));
            addText(zip, "general.log", AppLog.text());
            addText(zip, "settings.json", SettingsTransfer.exportJson(context));
            addNavigationLogs(context, zip);
            zip.finish();
        }
    }

    public static String suggestedFileName() {
        return FILE_NAME_PREFIX
                + new SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(new Date())
                + ".zip";
    }

    public static String summary(Context context) {
        AdapterState adapter = StateStore.adapter();
        NavigationState navigation = StateStore.navigation();
        MediaState media = StateStore.media();
        TpmsState tpms = StateStore.tpms();
        RctaState rcta = StateStore.rcta();
        long now = System.currentTimeMillis();
        StringBuilder out = new StringBuilder();
        out.append("KIA diagnostic snapshot\n");
        out.append("created=").append(date(now)).append('\n');
        out.append("app=").append(appVersion(context)).append('\n');
        out.append("android=").append(Build.VERSION.RELEASE)
                .append(" api=").append(Build.VERSION.SDK_INT)
                .append(" device=").append(Build.MANUFACTURER).append(' ')
                .append(Build.MODEL).append('\n');
        out.append("adapter_connected=").append(adapter.usbConnected)
                .append(" uid=").append(clean(adapter.uid))
                .append(" firmware=").append(clean(adapter.firmware))
                .append(" last_frame_age=").append(age(now, adapter.lastFrameAt))
                .append(" health=").append(clean(adapter.health)).append('\n');
        out.append("navigation_enabled=").append(AppSettings.navigationEnabled(context))
                .append(" source=").append(AppSettings.navSourceLabel(context))
                .append(" active=").append(navigation.active)
                .append(" snapshot_age=").append(age(now, navigation.updatedAt))
                .append(" maneuver_present=").append(!navigation.maneuver.isEmpty())
                .append(" micro_present=").append(!navigation.microManeuverId.isEmpty())
                .append(" cluster_tx_present=").append(!navigation.clusterTx.isEmpty()).append('\n');
        out.append("media_profile=").append(AppSettings.mediaProfileLabel(context))
                .append(" playing=").append(media.playing)
                .append(" package=").append(clean(media.packageName))
                .append(" state_age=").append(age(now, media.updatedAt))
                .append(" cluster_tx_present=").append(!media.clusterTx.isEmpty()).append('\n');
        out.append("tpms=").append(sanitize(tpms.summary()))
                .append(" state_age=").append(age(now, tpms.updatedAt)).append('\n');
        out.append("rcta=").append(rcta.summary())
                .append(" state_age=").append(age(now, rcta.updatedAt)).append('\n');
        out.append("overlay_permission=").append(Build.VERSION.SDK_INT < 23
                || Settings.canDrawOverlays(context)).append('\n');
        out.append("auto_start=").append(AppSettings.autoStart(context))
                .append(" expert_mode=").append(AppSettings.expertMode(context)).append('\n');
        return out.toString();
    }

    private static void addNavigationLogs(Context context, ZipOutputStream zip) throws Exception {
        File root = context.getExternalFilesDir(null);
        if (root == null) root = context.getFilesDir();
        File directory = new File(root, "navigation-logs");
        File[] files = directory.listFiles((dir, name) -> name.startsWith("navigation.log"));
        if (files == null) return;
        long remaining = MAX_LOG_BYTES;
        byte[] buffer = new byte[8192];
        for (File file : files) {
            if (file == null || !file.isFile() || remaining <= 0L) continue;
            long allowed = Math.min(remaining, file.length());
            zip.putNextEntry(new ZipEntry("navigation/" + safeName(file.getName())));
            try (FileInputStream input = new FileInputStream(file)) {
                long written = 0L;
                int read;
                while (written < allowed
                        && (read = input.read(buffer, 0,
                        (int) Math.min(buffer.length, allowed - written))) != -1) {
                    zip.write(buffer, 0, read);
                    written += read;
                }
                remaining -= written;
            }
            zip.closeEntry();
        }
    }

    private static void addText(ZipOutputStream zip, String name, String value) throws Exception {
        zip.putNextEntry(new ZipEntry(name));
        byte[] bytes = (value == null ? "" : value).getBytes(StandardCharsets.UTF_8);
        zip.write(bytes);
        zip.closeEntry();
    }

    private static String appVersion(Context context) {
        try {
            PackageInfo info = context.getPackageManager().getPackageInfo(context.getPackageName(), 0);
            long code = Build.VERSION.SDK_INT >= 28 ? info.getLongVersionCode() : info.versionCode;
            return info.versionName + " (" + code + ")";
        } catch (Exception ignored) {
            return "unknown";
        }
    }

    private static String age(long now, long then) {
        if (then <= 0L) return "never";
        long seconds = Math.max(0L, now - then) / 1000L;
        if (seconds < 60L) return seconds + "s";
        if (seconds < 3600L) return (seconds / 60L) + "m";
        return (seconds / 3600L) + "h";
    }

    private static String date(long value) {
        return new SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US)
                .format(new Date(value));
    }

    private static String clean(String value) {
        if (value == null) return "";
        return sanitize(value.trim());
    }

    private static String sanitize(String value) {
        if (value == null) return "";
        return value.replace('\n', ' ').replace('\r', ' ')
                .replaceAll("\\+?\\d[\\d ()-]{7,}\\d", "[phone]");
    }

    private static String safeName(String value) {
        return value == null ? "navigation.log" : value.replaceAll("[^A-Za-z0-9._-]", "_");
    }
}
