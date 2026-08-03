package kia.app.update;

import android.content.Context;
import android.content.SharedPreferences;

/** Runtime-only OTA markers; deliberately excluded from settings export/import. */
public final class UpdateRuntimeStore {
    private static final String PREFS = "KiaUpdateRuntime";
    private static final String KEY_NOTIFICATION_PERMISSION_ASKED =
            "notification_permission_asked";
    private static final String KEY_LAST_NOTIFIED_TOKEN = "last_notified_token";

    private UpdateRuntimeStore() {
    }

    public static boolean notificationPermissionAsked(Context context) {
        return prefs(context).getBoolean(KEY_NOTIFICATION_PERMISSION_ASKED, false);
    }

    public static void markNotificationPermissionAsked(Context context) {
        prefs(context).edit().putBoolean(KEY_NOTIFICATION_PERMISSION_ASKED, true).apply();
    }

    static String lastNotifiedToken(Context context) {
        String value = prefs(context).getString(KEY_LAST_NOTIFIED_TOKEN, "");
        return value == null ? "" : value;
    }

    static void setLastNotifiedToken(Context context, String token) {
        prefs(context).edit().putString(KEY_LAST_NOTIFIED_TOKEN,
                token == null ? "" : token).apply();
    }

    public static void resetPostedNotification(Context context) {
        prefs(context).edit().remove(KEY_LAST_NOTIFIED_TOKEN).apply();
    }

    private static SharedPreferences prefs(Context context) {
        Context app = context.getApplicationContext();
        return app.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }
}
