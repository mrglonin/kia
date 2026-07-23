package kia.app.navigation.capture;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;

import kia.app.navigation.domain.NavigationFeature;

/**
 * Compatibility endpoint for navigation integrations that cannot use the signed Yandex bridge.
 *
 * <p>The signed bridge action is deliberately rejected here even for explicit broadcasts. Android
 * intent filters do not constrain an explicitly addressed manifest receiver, so this check keeps
 * the unprotected compatibility component from becoming an alternate path into the Yandex bridge.
 */
public final class LegacyNavBroadcastReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent == null
                || YandexCoreBridgeContract.ACTION_V2_SNAPSHOT.equals(intent.getAction())) {
            return;
        }
        NavBroadcastReceiver.receiveLegacy(context, intent);
    }

    public static void addActions(IntentFilter filter) {
        if (filter == null) return;
        filter.addAction(NavigationFeature.ACTION_MANEUVER);
        filter.addAction(NavigationFeature.ACTION_ETA);
        filter.addAction(NavigationFeature.ACTION_NAVI_ON);
        filter.addAction(NavigationFeature.ACTION_SPEED);
        filter.addAction(NavigationFeature.ACTION_EXCEEDED);
        filter.addAction(NavigationFeature.KIA_ACTION_MANEUVER);
        filter.addAction(NavigationFeature.KIA_ACTION_ETA);
        filter.addAction(NavigationFeature.KIA_ACTION_NAVI_ON);
        filter.addAction(NavigationFeature.KIA_ACTION_SPEED);
        filter.addAction(NavigationFeature.KIA_ACTION_EXCEEDED);
        filter.addAction("com.yf.navinfo");
        filter.addAction("com.teyes.MapAssistantService");
        filter.addAction("android.action.MOBILE_NAVIGATION");
    }
}
