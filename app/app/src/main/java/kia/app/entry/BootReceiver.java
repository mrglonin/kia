package kia.app.entry;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.UserManager;

import kia.app.core.settings.AppSettings;

public final class BootReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        if (context == null) return;
        if (Build.VERSION.SDK_INT >= 24) {
            UserManager userManager = (UserManager) context.getSystemService(Context.USER_SERVICE);
            if (userManager != null && !userManager.isUserUnlocked()) return;
        }
        if (context == null || !AppSettings.autoStart(context)) return;
        AppService.start(context);
    }
}
