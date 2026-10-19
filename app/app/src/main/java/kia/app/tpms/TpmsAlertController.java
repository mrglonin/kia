package kia.app.tpms;

import android.Manifest;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.SystemClock;
import android.text.TextUtils;

import java.util.Locale;

import kia.app.core.AppLog;
import kia.app.core.model.TpmsState;
import kia.app.core.settings.AppSettings;
import kia.app.entry.MainActivity;

public final class TpmsAlertController {
    public static final int WARNING_NONE = 0;
    public static final int WARNING_FAST_LEAKAGE = 1;
    public static final int WARNING_HIGH_PRESSURE = 2;
    public static final int WARNING_HIGH_TEMP = 3;
    public static final int WARNING_LOW_PRESSURE = 4;
    public static final int WARNING_LOW_BATTERY = 5;
    public static final int WARNING_LOW_TEMP = 6;

    public static final int SEVERITY_NONE = 0;
    public static final int SEVERITY_WARNING = 1;
    public static final int SEVERITY_CRITICAL = 2;

    private static final String CHANNEL = "kia_tpms_status";
    private static final int NOTIFICATION_ID = 4418;
    static final int CAUSE_SLOTS = TpmsState.WHEEL_COUNT * TpmsWarningPolicy.CAUSE_COUNT;

    private static TpmsAlertController instance;

    private final Context app;
    private final TpmsAlertLifecycle notificationLifecycle = new TpmsAlertLifecycle(CAUSE_SLOTS);

    private TpmsAlertController(Context context) {
        this.app = context.getApplicationContext();
    }

    public static synchronized TpmsAlertController get(Context context) {
        if (instance == null) instance = new TpmsAlertController(context);
        return instance;
    }

    public synchronized void apply(TpmsState state) {
        if (!AppSettings.tpmsAlertsEnabled(app)) {
            clear();
            return;
        }
        int[] levels = warningLevels(app, state);
        String details = warningDetails(app, state);
        TpmsWarningOverlayController.get(app).apply(levels, details);
        long now = SystemClock.elapsedRealtime();
        notificationLifecycle.observe(levels, now);
        if (TextUtils.isEmpty(details)) {
            cancel();
            return;
        }
        if (!notificationLifecycle.shouldNotify(now)) return;
        show("TPMS: " + warningText(firstWarning(app, state)), details);
        AppLog.line(app, "TPMS alert: " + details);
    }

    /** Clears every alert surface and invalidates queued overlay/sound updates. */
    public synchronized void clear() {
        notificationLifecycle.reset();
        TpmsWarningOverlayController.get(app).clear();
        cancel();
    }

    public static int warningState(Context context, TpmsState state, int wheel) {
        if (state == null || state.known == null || state.pressureKpa == null
                || state.temperatureC == null || wheel < 0 || wheel >= TpmsState.WHEEL_COUNT
                || !state.known[wheel] || !state.isWheelFresh(wheel)) {
            return WARNING_NONE;
        }
        int flags = state.flags == null ? 0 : state.flags[wheel];
        return warningState(context, state.pressureKpa[wheel], state.temperatureC[wheel], flags);
    }

    public static int warningState(Context context, int pressureKpa, int tempC, int flags) {
        return evaluate(context, pressureKpa, tempC, flags).warning();
    }

    public static int warningSeverity(Context context, TpmsState state, int wheel) {
        if (state == null || state.known == null || state.pressureKpa == null
                || state.temperatureC == null || wheel < 0 || wheel >= TpmsState.WHEEL_COUNT
                || !state.known[wheel] || !state.isWheelFresh(wheel)) {
            return SEVERITY_NONE;
        }
        int flags = state.flags == null ? 0 : state.flags[wheel];
        return warningSeverity(context, state.pressureKpa[wheel], state.temperatureC[wheel], flags);
    }

    public static int warningSeverity(Context context, int pressureKpa, int tempC, int flags) {
        return evaluate(context, pressureKpa, tempC, flags).severity();
    }

    private static TpmsWarningPolicy.Result evaluate(Context context, int pressureKpa,
                                                     int tempC, int flags) {
        if (context == null || !AppSettings.tpmsAlertsEnabled(context)) {
            return new TpmsWarningPolicy.Result();
        }
        return TpmsWarningPolicy.evaluate(pressureKpa, tempC, flags,
                AppSettings.tpmsLowPressureKpa(context), AppSettings.tpmsHighPressureKpa(context),
                AppSettings.tpmsLowTempC(context), AppSettings.tpmsHighTempC(context));
    }

    static int[] warningLevels(Context context, TpmsState state) {
        int[] levels = new int[CAUSE_SLOTS];
        if (state == null || context == null || !AppSettings.tpmsAlertsEnabled(context)) return levels;
        for (int wheel = 0; wheel < TpmsState.WHEEL_COUNT; wheel++) {
            if (!state.isWheelFresh(wheel)) continue;
            TpmsWarningPolicy.Result result = evaluate(context,
                    state.pressureKpa[wheel], state.temperatureC[wheel], state.flags[wheel]);
            System.arraycopy(result.levels, 0, levels,
                    wheel * TpmsWarningPolicy.CAUSE_COUNT, result.levels.length);
        }
        return levels;
    }

    public static boolean hasWarnings(Context context, TpmsState state) {
        return firstWarning(context, state) != WARNING_NONE;
    }

    public static boolean hasCriticalWarnings(Context context, TpmsState state) {
        if (state == null || state.known == null) return false;
        for (int wheel = 0; wheel < TpmsState.WHEEL_COUNT; wheel++) {
            if (warningSeverity(context, state, wheel) == SEVERITY_CRITICAL) return true;
        }
        return false;
    }

    public static int firstWarning(Context context, TpmsState state) {
        int[] levels = warningLevels(context, state);
        int selected = WARNING_NONE;
        int highest = SEVERITY_NONE;
        for (int slot = 0; slot < levels.length; slot++) {
            if (levels[slot] > highest) {
                highest = levels[slot];
                selected = slot % TpmsWarningPolicy.CAUSE_COUNT;
            }
        }
        return selected;
    }

    /** Identity contains wheel/cause/severity only; current readings remain in details. */
    public static String warningKey(Context context, TpmsState state) {
        int[] levels = warningLevels(context, state);
        StringBuilder out = new StringBuilder();
        for (int slot = 0; slot < levels.length; slot++) {
            if (levels[slot] == SEVERITY_NONE) continue;
            if (out.length() > 0) out.append('|');
            out.append(slot / TpmsWarningPolicy.CAUSE_COUNT).append(':')
                    .append(slot % TpmsWarningPolicy.CAUSE_COUNT).append(':').append(levels[slot]);
        }
        return out.toString();
    }

    public static String warningDetails(Context context, TpmsState state) {
        if (context == null || state == null || state.known == null) return "";
        StringBuilder out = new StringBuilder();
        for (int wheel = 0; wheel < TpmsState.WHEEL_COUNT; wheel++) {
            int warning = warningState(context, state, wheel);
            if (warning == WARNING_NONE) continue;
            if (out.length() > 0) out.append("; ");
            int severity = warningSeverity(context, state, wheel);
            out.append(wheelName(wheel)).append(' ')
                    .append(barText(state.pressureKpa[wheel])).append(" / ")
                    .append(state.temperatureC[wheel]).append("C, ")
                    .append(severityText(severity)).append(": ")
                    .append(warningText(warning));
        }
        return out.toString();
    }

    public static String severityText(int severity) {
        switch (severity) {
            case SEVERITY_CRITICAL:
                return "критично";
            case SEVERITY_WARNING:
                return "внимание";
            default:
                return "норма";
        }
    }

    public static String warningText(int warning) {
        switch (warning) {
            case WARNING_FAST_LEAKAGE:
                return "утечка давления";
            case WARNING_HIGH_PRESSURE:
                return "высокое давление";
            case WARNING_HIGH_TEMP:
                return "высокая температура";
            case WARNING_LOW_PRESSURE:
                return "низкое давление";
            case WARNING_LOW_BATTERY:
                return "низкий заряд датчика";
            case WARNING_LOW_TEMP:
                return "низкая температура";
            default:
                return "предупреждение";
        }
    }

    public static String wheelName(int wheel) {
        switch (wheel) {
            case TpmsState.WHEEL_FL:
                return "FL";
            case TpmsState.WHEEL_FR:
                return "FR";
            case TpmsState.WHEEL_RL:
                return "RL";
            case TpmsState.WHEEL_RR:
                return "RR";
            default:
                return "Tire";
        }
    }

    public static String barText(int pressureKpa) {
        return String.format(Locale.US, "%.2f bar", pressureKpa / 100f);
    }

    private void show(String title, String text) {
        if (!canNotify()) return;
        NotificationManager manager = (NotificationManager) app.getSystemService(Context.NOTIFICATION_SERVICE);
        if (manager == null) return;
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationChannel channel = new NotificationChannel(CHANNEL,
                    "KIA TPMS", NotificationManager.IMPORTANCE_LOW);
            channel.enableVibration(false);
            channel.setSound(null, null);
            manager.createNotificationChannel(channel);
        }
        Intent open = new Intent(app, MainActivity.class);
        open.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        int flags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= 23) flags |= PendingIntent.FLAG_IMMUTABLE;
        PendingIntent contentIntent = PendingIntent.getActivity(app, NOTIFICATION_ID, open, flags);
        int icon = app.getApplicationInfo().icon;
        if (icon == 0) icon = android.R.drawable.stat_sys_warning;
        Notification.Builder builder = Build.VERSION.SDK_INT >= 26
                ? new Notification.Builder(app, CHANNEL)
                : new Notification.Builder(app);
        builder.setSmallIcon(icon)
                .setContentTitle(title)
                .setContentText(text)
                .setStyle(new Notification.BigTextStyle().bigText(text))
                .setCategory(Notification.CATEGORY_STATUS)
                .setPriority(Notification.PRIORITY_LOW)
                .setDefaults(0)
                .setOnlyAlertOnce(true)
                .setAutoCancel(true)
                .setShowWhen(true)
                .setWhen(System.currentTimeMillis())
                .setContentIntent(contentIntent);
        try {
            manager.notify(NOTIFICATION_ID, builder.build());
        } catch (Exception e) {
            AppLog.line(app, "TPMS alert notify failed: " + e.getClass().getSimpleName());
        }
    }

    private void cancel() {
        NotificationManager manager = (NotificationManager) app.getSystemService(Context.NOTIFICATION_SERVICE);
        if (manager != null) manager.cancel(NOTIFICATION_ID);
    }

    private boolean canNotify() {
        return Build.VERSION.SDK_INT < 33
                || app.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                == PackageManager.PERMISSION_GRANTED;
    }
}
