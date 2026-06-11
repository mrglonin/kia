package kia.app.navigation.capture;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Bundle;
import android.os.PowerManager;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import kia.app.core.AppLog;
import kia.app.core.settings.AppSettings;
import kia.app.entry.AppService;
import kia.app.navigation.domain.NavigationFeature;

public final class NavBroadcastReceiver extends BroadcastReceiver {
    private static final long DUPLICATE_WINDOW_MS = 120L;
    private static final long SERVICE_START_MIN_INTERVAL_MS = 5000L;
    private static final long NAV_WAKE_MS = 12000L;
    private static final long CORE_BRIDGE_FALLBACK_SUPPRESS_MS = 5000L;
    private static final long SUPPRESSED_LOG_MS = 10000L;

    private static String lastSignature = "";
    private static long lastSignatureAt;
    private static long lastServiceStartAt;
    private static long lastCoreBridgeSnapshotAt;
    private static long lastSuppressedLogAt;
    private static PowerManager.WakeLock wakeLock;

    @Override
    public void onReceive(Context context, Intent intent) {
        if (context == null || intent == null) return;
        keepCpuAwake(context);
        ensureServiceStarted(context);
        if (duplicate(intent)) return;
        String action = intent.getAction();
        if (YandexCoreBridgeContract.ACTION_V2_SNAPSHOT.equals(action)) {
            lastCoreBridgeSnapshotAt = System.currentTimeMillis();
            YandexCoreBridgeClient.get(context).acceptBroadcastSnapshot(intent.getExtras());
            return;
        }
        if (legacyFallbackSuppressed(context, action)) return;
        NavigationFeature feature = NavigationFeature.get(context);
        if ("com.yf.navinfo".equals(action)
                || "com.teyes.MapAssistantService".equals(action)
                || "android.action.MOBILE_NAVIGATION".equals(action)) {
            feature.handleTeyes(intent);
            feature.resendForBackgroundDelivery(action);
            return;
        }
        feature.handle(intent);
        feature.resendForBackgroundDelivery(action);
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
        filter.addAction(YandexCoreBridgeContract.ACTION_V2_SNAPSHOT);
        filter.addAction("com.yf.navinfo");
        filter.addAction("com.teyes.MapAssistantService");
        filter.addAction("android.action.MOBILE_NAVIGATION");
    }

    private static void ensureServiceStarted(Context context) {
        long now = System.currentTimeMillis();
        synchronized (NavBroadcastReceiver.class) {
            if (now - lastServiceStartAt < SERVICE_START_MIN_INTERVAL_MS) return;
            lastServiceStartAt = now;
        }
        AppService.start(context.getApplicationContext());
    }

    private static void keepCpuAwake(Context context) {
        try {
            Context app = context.getApplicationContext();
            synchronized (NavBroadcastReceiver.class) {
                if (wakeLock == null) {
                    PowerManager pm = (PowerManager) app.getSystemService(Context.POWER_SERVICE);
                    if (pm != null) {
                        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "Kia:NavBroadcast");
                        wakeLock.setReferenceCounted(false);
                    }
                }
                if (wakeLock != null) wakeLock.acquire(NAV_WAKE_MS);
            }
        } catch (Exception e) {
            AppLog.line(context, "Navigation receiver wakelock failed: "
                    + e.getClass().getSimpleName());
        }
    }

    private static boolean duplicate(Intent intent) {
        long now = System.currentTimeMillis();
        String signature = signature(intent);
        synchronized (NavBroadcastReceiver.class) {
            if (signature.equals(lastSignature) && now - lastSignatureAt <= DUPLICATE_WINDOW_MS) {
                return true;
            }
            lastSignature = signature;
            lastSignatureAt = now;
            return false;
        }
    }

    private static String signature(Intent intent) {
        StringBuilder out = new StringBuilder();
        out.append(intent.getAction()).append('|');
        Bundle extras = intent.getExtras();
        if (extras == null || extras.isEmpty()) return out.toString();
        List<String> keys = new ArrayList<>(extras.keySet());
        Collections.sort(keys);
        for (String key : keys) {
            Object value = extras.get(key);
            out.append(key).append('=').append(value == null ? "null" : String.valueOf(value)).append(';');
        }
        return out.toString();
    }

    private static boolean legacyFallbackSuppressed(Context context, String action) {
        int mode = AppSettings.navSourceMode(context);
        long now = System.currentTimeMillis();
        boolean strictYandex = mode == AppSettings.NAV_SOURCE_YANDEX;
        boolean recentCoreBridge = mode == AppSettings.NAV_SOURCE_AUTO
                && lastCoreBridgeSnapshotAt > 0L
                && now - lastCoreBridgeSnapshotAt < CORE_BRIDGE_FALLBACK_SUPPRESS_MS;
        if (!strictYandex && !recentCoreBridge) return false;
        if (now - lastSuppressedLogAt >= SUPPRESSED_LOG_MS) {
            lastSuppressedLogAt = now;
            AppLog.line(context, "Navigation legacy fallback suppressed: "
                    + (strictYandex ? "strict_yandex" : "core_bridge_recent")
                    + " action=" + action);
        }
        return true;
    }
}
