package kia.app.entry

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.UserManager
import kia.app.core.settings.AppSettings
import kia.app.update.UpdateRuntimeStore

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context?, intent: Intent?) {
        if (context == null || intent == null || !BootReceiver.supportedAction(intent.getAction()))
            return
        if (Build.VERSION.SDK_INT >= 24) {
            val userManager: UserManager? =
                context.getSystemService(Context.USER_SERVICE) as UserManager?
            if (userManager != null && !userManager.isUserUnlocked()) return
        }
        if (Intent.ACTION_MY_PACKAGE_REPLACED.equals(intent.getAction())) {
            AppSettings.resetUpdateCheckTimes(context)
        }
        if (
            Intent.ACTION_BOOT_COMPLETED.equals(intent.getAction()) ||
                Intent.ACTION_USER_UNLOCKED.equals(intent.getAction()) ||
                Intent.ACTION_MY_PACKAGE_REPLACED.equals(intent.getAction())
        ) {
            UpdateRuntimeStore.resetPostedNotification(context)
        }
        if (!AppSettings.autoStart(context)) return
        AppService.start(context)
    }

    companion object {
        private fun supportedAction(action: String?): Boolean {
            return Intent.ACTION_BOOT_COMPLETED.equals(action) ||
                Intent.ACTION_LOCKED_BOOT_COMPLETED.equals(action) ||
                Intent.ACTION_USER_UNLOCKED.equals(action) ||
                Intent.ACTION_MY_PACKAGE_REPLACED.equals(action) ||
                "android.hardware.usb.action.USB_DEVICE_ATTACHED".equals(action)
        }
    }
}
