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

    private static final int FLAG_FAST_LEAKAGE = 0x01;
    private static final int FLAG_HIGH_PRESSURE = 0x02;
    private static final int FLAG_HIGH_TEMP = 0x04;
    private static final int FLAG_LOW_PRESSURE = 0x08;
    private static final int FLAG_LOW_BATTERY = 0x10;
    private static final int CRITICAL_PRESSURE_MARGIN_KPA = 30;
    private static final int CRITICAL_TEMP_MARGIN_C = 10;
    private static final String CHANNEL = "kia_tpms_status";
    private static final int NOTIFICATION_ID = 4418;
    private static final long REPEAT_MS = 60000L;

    private static TpmsAlertController instance;

    private final Context app;
    private String lastWarningKey = "";
    private long lastWarningAt;

    private TpmsAlertController(Context context) {
        this.app = context.getApplicationContext();
    }

    public static synchronized TpmsAlertController get(Context context) {
        if (instance == null) instance = new TpmsAlertController(context);
        return instance;
    }

    public void apply(TpmsState state) {
        String key = warningKey(app, state);
        TpmsWarningOverlayController.get(app).apply(state, key);
        if (TextUtils.isEmpty(key)) {
            synchronized (this) {
                lastWarningKey = "";
                lastWarningAt = 0L;
            }
            cancel();
            return;
        }
        long now = System.currentTimeMillis();
        synchronized (this) {
            if (key.equals(lastWarningKey) && now - lastWarningAt < REPEAT_MS) return;
            lastWarningKey = key;
            lastWarningAt = now;
        }
        String details = warningDetails(app, state);
        show("TPMS: " + warningText(firstWarning(app, state)), details);
        AppLog.line(app, "TPMS alert: " + details);
    }

    public static int warningState(Context context, TpmsState state, int wheel) {
        if (state == null || state.known == null || state.pressureKpa == null
                || state.temperatureC == null || wheel < 0 || wheel >= TpmsState.WHEEL_COUNT
                || !state.known[wheel]) {
            return WARNING_NONE;
        }
        int flags = state.flags == null ? 0 : state.flags[wheel];
        return warningState(context, state.pressureKpa[wheel], state.temperatureC[wheel], flags);
    }

    public static int warningState(Context context, int pressureKpa, int tempC, int flags) {
        if (context == null || !AppSettings.tpmsAlertsEnabled(context)) return WARNING_NONE;
        int cleanFlags = flags & 0xff;
        if ((cleanFlags & FLAG_FAST_LEAKAGE) != 0) return WARNING_FAST_LEAKAGE;
        if ((cleanFlags & FLAG_HIGH_PRESSURE) != 0) return WARNING_HIGH_PRESSURE;
        if ((cleanFlags & FLAG_HIGH_TEMP) != 0) return WARNING_HIGH_TEMP;
        if ((cleanFlags & FLAG_LOW_PRESSURE) != 0) return WARNING_LOW_PRESSURE;
        if ((cleanFlags & FLAG_LOW_BATTERY) != 0) return WARNING_LOW_BATTERY;

        int lowPressure = Math.min(AppSettings.tpmsLowPressureKpa(context),
                AppSettings.tpmsHighPressureKpa(context) - 1);
        int highPressure = Math.max(AppSettings.tpmsHighPressureKpa(context), lowPressure + 1);
        int lowTemp = Math.min(AppSettings.tpmsLowTempC(context),
                AppSettings.tpmsHighTempC(context) - 1);
        int highTemp = Math.max(AppSettings.tpmsHighTempC(context), lowTemp + 1);

        if (pressureKpa > 0 && pressureKpa < lowPressure) return WARNING_LOW_PRESSURE;
        if (pressureKpa > highPressure) return WARNING_HIGH_PRESSURE;
        if (tempC < lowTemp) return WARNING_LOW_TEMP;
        if (tempC >= highTemp) return WARNING_HIGH_TEMP;
        return WARNING_NONE;
    }

    public static int warningSeverity(Context context, TpmsState state, int wheel) {
        if (state == null || state.known == null || state.pressureKpa == null
                || state.temperatureC == null || wheel < 0 || wheel >= TpmsState.WHEEL_COUNT
                || !state.known[wheel]) {
            return SEVERITY_NONE;
        }
        int flags = state.flags == null ? 0 : state.flags[wheel];
        return warningSeverity(context, state.pressureKpa[wheel], state.temperatureC[wheel], flags);
    }

    public static int warningSeverity(Context context, int pressureKpa, int tempC, int flags) {
        int warning = warningState(context, pressureKpa, tempC, flags);
        if (warning == WARNING_NONE) return SEVERITY_NONE;

        int cleanFlags = flags & 0xff;
        if ((cleanFlags & FLAG_FAST_LEAKAGE) != 0) return SEVERITY_CRITICAL;
        if ((cleanFlags & (FLAG_HIGH_PRESSURE | FLAG_HIGH_TEMP | FLAG_LOW_PRESSURE)) != 0) {
            return SEVERITY_CRITICAL;
        }
        if ((cleanFlags & FLAG_LOW_BATTERY) != 0) return SEVERITY_WARNING;

        int lowPressure = Math.min(AppSettings.tpmsLowPressureKpa(context),
                AppSettings.tpmsHighPressureKpa(context) - 1);
        int highPressure = Math.max(AppSettings.tpmsHighPressureKpa(context), lowPressure + 1);
        int lowTemp = Math.min(AppSettings.tpmsLowTempC(context),
                AppSettings.tpmsHighTempC(context) - 1);
        int highTemp = Math.max(AppSettings.tpmsHighTempC(context), lowTemp + 1);

        if (warning == WARNING_LOW_PRESSURE && pressureKpa <= lowPressure - CRITICAL_PRESSURE_MARGIN_KPA) {
            return SEVERITY_CRITICAL;
        }
        if (warning == WARNING_HIGH_PRESSURE && pressureKpa >= highPressure + CRITICAL_PRESSURE_MARGIN_KPA) {
            return SEVERITY_CRITICAL;
        }
        if (warning == WARNING_LOW_TEMP && tempC <= lowTemp - CRITICAL_TEMP_MARGIN_C) {
            return SEVERITY_CRITICAL;
        }
        if (warning == WARNING_HIGH_TEMP && tempC >= highTemp + CRITICAL_TEMP_MARGIN_C) {
            return SEVERITY_CRITICAL;
        }
        return SEVERITY_WARNING;
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
        if (state == null || state.known == null) return WARNING_NONE;
        for (int wheel = 0; wheel < TpmsState.WHEEL_COUNT; wheel++) {
            int warning = warningState(context, state, wheel);
            if (warning != WARNING_NONE) return warning;
        }
        return WARNING_NONE;
    }

    public static String warningKey(Context context, TpmsState state) {
        if (context == null || state == null || state.known == null
                || !AppSettings.tpmsAlertsEnabled(context)) {
            return "";
        }
        StringBuilder out = new StringBuilder();
        for (int wheel = 0; wheel < TpmsState.WHEEL_COUNT; wheel++) {
            int warning = warningState(context, state, wheel);
            if (warning == WARNING_NONE) continue;
            if (out.length() > 0) out.append('|');
            out.append(wheel).append(':').append(warning).append(':')
                    .append(warningSeverity(context, state, wheel)).append(':')
                    .append(state.pressureKpa[wheel]).append(':').append(state.temperatureC[wheel]);
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
