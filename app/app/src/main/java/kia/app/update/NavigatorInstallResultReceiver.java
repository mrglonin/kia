package kia.app.update;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInstaller;

import kia.app.core.AppLog;
import kia.app.core.StateStore;

public final class NavigatorInstallResultReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        if (context == null || intent == null) return;
        int status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE);
        String message = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE);
        if (status == PackageInstaller.STATUS_PENDING_USER_ACTION) {
            Intent confirm = intent.getParcelableExtra(Intent.EXTRA_INTENT);
            if (confirm != null) {
                confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                context.startActivity(confirm);
            }
            StateStore.updateUpdates(context, current -> current.withNavigator(
                    "Navigator update: подтвердите установку",
                    current.navigatorVersion, current.navigatorPackageName,
                    current.navigatorAsset, current.navigatorFingerprint,
                    current.navigatorTotalBytes,
                    current.navigatorTotalBytes, true, false, false, true, true));
            return;
        }
        if (status == PackageInstaller.STATUS_SUCCESS) {
            StateStore.updateUpdates(context, current -> current.withNavigator(
                    "Navigator update: установлен",
                    current.navigatorVersion, current.navigatorPackageName,
                    current.navigatorAsset, current.navigatorFingerprint,
                    current.navigatorTotalBytes,
                    current.navigatorTotalBytes, false, false, false, false, false));
            UpdateNotificationController.refresh(context);
            AppLog.line(context, "Navigator update: install success");
            return;
        }
        String suffix = message == null || message.isEmpty() ? String.valueOf(status) : message;
        StateStore.updateUpdates(context, current -> current.withNavigator(
                "Navigator update: ошибка установки " + suffix,
                current.navigatorVersion, current.navigatorPackageName,
                current.navigatorAsset, current.navigatorFingerprint,
                current.navigatorTotalBytes,
                current.navigatorTotalBytes, true, false, false, true, false));
        AppLog.line(context, "Navigator update install failed: " + suffix);
    }
}
