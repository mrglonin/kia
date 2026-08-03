package kia.app.update;

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

import kia.app.R;
import kia.app.core.AppIds;
import kia.app.core.AppLog;
import kia.app.core.StateStore;
import kia.app.core.model.UpdateState;
import kia.app.entry.MainActivity;

/** Posts non-modal OTA alerts, including when only the foreground service is visible. */
public final class UpdateNotificationController {
    private static final String CHANNEL = "kia_updates";
    private static final String CHANNEL_NAME = "Обновления KIA";
    private static final int NOTIFICATION_ID = 23091;

    private UpdateNotificationController() {
    }

    public static void prepareChannel(Context context) {
        Context app = context == null ? null : context.getApplicationContext();
        if (app == null) return;
        try {
            NotificationManager manager =
                    (NotificationManager) app.getSystemService(Context.NOTIFICATION_SERVICE);
            if (manager == null) return;
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL, CHANNEL_NAME, NotificationManager.IMPORTANCE_DEFAULT);
            channel.setDescription("Новые версии KIA и совместимого Yandex Navigator");
            manager.createNotificationChannel(channel);
        } catch (Exception error) {
            AppLog.line(app, "Update notification channel failed: "
                    + error.getClass().getSimpleName());
        }
    }

    public static void refresh(Context context) {
        Context app = context == null ? null : context.getApplicationContext();
        if (app == null) return;
        prepareChannel(app);
        UpdateState state = StateStore.updates();
        String token = notificationToken(state);
        UpdateNotificationPolicy.Action action = UpdateNotificationPolicy.action(
                state.appAvailable, state.navigatorAvailable,
                state.appChecking, state.navigatorChecking,
                token, UpdateRuntimeStore.lastNotifiedToken(app));
        if (action == UpdateNotificationPolicy.Action.KEEP) return;
        if (action == UpdateNotificationPolicy.Action.CANCEL) {
            cancel(app, NOTIFICATION_ID);
            UpdateRuntimeStore.resetPostedNotification(app);
            return;
        }
        String title;
        String text;
        if (state.appAvailable && state.navigatorAvailable) {
            title = "Доступны обновления KIA и Yandex";
            text = state.appAsset + " · " + state.navigatorVersion;
        } else if (state.appAvailable) {
            title = "Доступно обновление KIA";
            text = state.appAsset;
        } else {
            title = "Доступно обновление Yandex Navigator";
            text = TextUtils.isEmpty(state.navigatorVersion)
                    ? state.navigatorAsset : state.navigatorVersion;
        }
        if (show(app, NOTIFICATION_ID, 91, title,
                text + " — откройте KIA для установки")) {
            UpdateRuntimeStore.setLastNotifiedToken(app, token);
        }
    }

    private static boolean show(Context context, int notificationId, int requestCode,
                                String title, String text) {
        Context app = context == null ? null : context.getApplicationContext();
        if (app == null) return false;
        if (Build.VERSION.SDK_INT >= 33
                && app.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) {
            AppLog.line(app, "Update notification skipped: POST_NOTIFICATIONS denied");
            return false;
        }
        try {
            NotificationManager manager =
                    (NotificationManager) app.getSystemService(Context.NOTIFICATION_SERVICE);
            if (manager == null) return false;
            Intent intent = new Intent(AppIds.ACTION_OPEN_UPDATES, null, app, MainActivity.class)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
            PendingIntent open = PendingIntent.getActivity(app, requestCode, intent,
                    PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
            Notification notification = new Notification.Builder(app, CHANNEL)
                    .setSmallIcon(R.drawable.ic_stat_kia)
                    .setContentTitle(title)
                    .setContentText(text)
                    .setStyle(new Notification.BigTextStyle().bigText(text))
                    .setContentIntent(open)
                    .setAutoCancel(true)
                    .setCategory(Notification.CATEGORY_STATUS)
                    .setPriority(Notification.PRIORITY_DEFAULT)
                    .build();
            manager.notify(notificationId, notification);
            return true;
        } catch (Exception error) {
            AppLog.line(app, "Update notification failed: "
                    + error.getClass().getSimpleName());
            return false;
        }
    }

    private static String notificationToken(UpdateState state) {
        if (state == null) return "";
        String app = state.appAvailable
                ? state.appAsset + ":" + state.appSha256 : "";
        String navigator = state.navigatorAvailable
                ? state.navigatorAsset + ":" + state.navigatorVersion + ":"
                + state.navigatorFingerprint : "";
        return app + "|" + navigator;
    }

    private static void cancel(Context context, int notificationId) {
        Context app = context == null ? null : context.getApplicationContext();
        if (app == null) return;
        try {
            NotificationManager manager =
                    (NotificationManager) app.getSystemService(Context.NOTIFICATION_SERVICE);
            if (manager != null) manager.cancel(notificationId);
        } catch (Exception ignored) {
        }
    }
}
