package kia.app.update

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import java.util.function.UnaryOperator
import kia.app.core.AppLog
import kia.app.core.StateStore
import kia.app.core.model.UpdateState

class NavigatorInstallResultReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context?, intent: Intent?) {
        if (context == null || intent == null) return
        val status: Int =
            intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)
        val message = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)
        if (status == PackageInstaller.STATUS_PENDING_USER_ACTION) {
            val confirm = intent.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)
            if (confirm != null) {
                confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(confirm)
            }
            StateStore.updateUpdates(
                context,
                UnaryOperator<UpdateState?> { current ->
                    current!!.withNavigator(
                        "Navigator update: подтвердите установку",
                        current.navigatorVersion,
                        current.navigatorPackageName,
                        current.navigatorAsset,
                        current.navigatorFingerprint,
                        current.navigatorTotalBytes,
                        current.navigatorTotalBytes,
                        true,
                        false,
                        false,
                        true,
                        true,
                    )
                },
            )
            return
        }
        if (status == PackageInstaller.STATUS_SUCCESS) {
            StateStore.updateUpdates(
                context,
                UnaryOperator<UpdateState?> { current ->
                    current!!.withNavigator(
                        "Navigator update: установлен",
                        current.navigatorVersion,
                        current.navigatorPackageName,
                        current.navigatorAsset,
                        current.navigatorFingerprint,
                        current.navigatorTotalBytes,
                        current.navigatorTotalBytes,
                        false,
                        false,
                        false,
                        false,
                        false,
                    )
                },
            )
            UpdateNotificationController.refresh(context)
            AppLog.line(context, "Navigator update: install success")
            return
        }
        val suffix =
            if (message == null || message.isEmpty()) java.lang.String.valueOf(status) else message
        StateStore.updateUpdates(
            context,
            UnaryOperator<UpdateState?> { current ->
                current!!.withNavigator(
                    "Navigator update: ошибка установки " + suffix,
                    current.navigatorVersion,
                    current.navigatorPackageName,
                    current.navigatorAsset,
                    current.navigatorFingerprint,
                    current.navigatorTotalBytes,
                    current.navigatorTotalBytes,
                    true,
                    false,
                    false,
                    true,
                    false,
                )
            },
        )
        AppLog.line(context, "Navigator update install failed: " + suffix)
    }
}
