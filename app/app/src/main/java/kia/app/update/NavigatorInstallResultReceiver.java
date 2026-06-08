package kia.app.update;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInstaller;

import kia.app.core.AppLog;
import kia.app.core.StateStore;
import kia.app.core.model.UpdateState;

public final class NavigatorInstallResultReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        if (context == null || intent == null) return;
        int status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE);
        String message = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE);
        UpdateState current = StateStore.updates();
        String version = current.navigatorVersion;
        String packageName = current.navigatorPackageName;
        String asset = current.navigatorAsset;
        long total = current.navigatorTotalBytes;
        if (status == PackageInstaller.STATUS_PENDING_USER_ACTION) {
            Intent confirm = intent.getParcelableExtra(Intent.EXTRA_INTENT);
            if (confirm != null) {
                confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                context.startActivity(confirm);
            }
            StateStore.setUpdates(context, current.withNavigator("Navigator update: подтвердите установку",
                    version, packageName, asset, total, total, true, false, false, true, true));
            return;
        }
        if (status == PackageInstaller.STATUS_SUCCESS) {
            StateStore.setUpdates(context, current.withNavigator("Navigator update: установлен",
                    version, packageName, asset, total, total, false, false, false, true, false));
            AppLog.line(context, "Navigator update: install success");
            return;
        }
        String suffix = message == null || message.isEmpty() ? String.valueOf(status) : message;
        StateStore.setUpdates(context, current.withNavigator("Navigator update: ошибка установки " + suffix,
                version, packageName, asset, total, total, true, false, false, true, false));
        AppLog.line(context, "Navigator update install failed: " + suffix);
    }
}
