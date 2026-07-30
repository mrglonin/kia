package kia.app.navigation.capture;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.os.PowerManager;
import android.os.SystemClock;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

import kia.app.core.AppLog;
import kia.app.core.settings.AppSettings;
import kia.app.entry.AppService;
import kia.app.navigation.domain.NavigationFeature;

public final class NavBroadcastReceiver extends BroadcastReceiver {
    private static final long DUPLICATE_WINDOW_MS = 120L;
    private static final long SERVICE_START_MIN_INTERVAL_MS = 5000L;
    private static final long NAV_WAKE_MS = 12000L;
    private static final long CORE_BRIDGE_FALLBACK_SUPPRESS_MS = 5000L;
    private static final long CORE_BRIDGE_MAX_ENVELOPE_AGE_MS = 2500L;
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
        String action = intent.getAction();
        if (!YandexCoreBridgeContract.ACTION_V2_SNAPSHOT.equals(action)) {
            AppLog.navigation(context, "Yandex Core Bridge broadcast rejected: unexpected action="
                    + action);
            return;
        }
        Bundle extras = intent.getExtras();
        String invalidReason = invalidCoreBridgeEnvelope(extras);
        if (!invalidReason.isEmpty()) {
            AppLog.navigation(context, "Yandex Core Bridge broadcast rejected: " + invalidReason);
            return;
        }
        if (!sourceIngressAllowed(context, NavigationSourcePolicy.SOURCE_YANDEX)) {
            AppLog.navigation(context,
                    "Yandex Core Bridge broadcast ignored: navigation/source disabled");
            return;
        }
        keepCpuAwake(context);
        ensureServiceStarted(context);
        if (duplicate(intent)) return;
        lastCoreBridgeSnapshotAt = SystemClock.elapsedRealtime();
        YandexCoreBridgeClient.get(context).acceptBroadcastSnapshot(extras);
    }

    static void receiveLegacy(Context context, Intent intent) {
        if (context == null || intent == null) return;
        String action = intent.getAction();
        if (!knownLegacyAction(action)) {
            AppLog.navigation(context, "Navigation legacy broadcast rejected: action=" + action);
            return;
        }
        if (!sourceIngressAllowed(context, NavigationSourcePolicy.SOURCE_YANDEX)) {
            AppLog.navigation(context, "Navigation legacy broadcast ignored by source gate: action="
                    + action);
            return;
        }
        keepCpuAwake(context);
        ensureServiceStarted(context);
        if (duplicate(intent)) return;
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

    static String invalidCoreBridgeEnvelope(Bundle extras) {
        if (extras == null || extras.isEmpty()) return "empty envelope";
        long schema = longValue(extras, "schema_version", -1L);
        if (schema != 2L) return "schema_version=" + schema;
        String source = stringValue(extras, "source");
        if (!YandexCoreBridgeContract.SOURCE.equals(source)) return "source=" + source;
        long seq = longValue(extras, "seq", -1L);
        if (seq < 0L) return "missing seq";
        long timestamp = longValue(extras, "timestamp_elapsed_ms", -1L);
        if (timestamp <= 0L) return "missing timestamp_elapsed_ms";
        long now = SystemClock.elapsedRealtime();
        if (timestamp > now + 60000L) return "future timestamp";
        if (now - timestamp > CORE_BRIDGE_MAX_ENVELOPE_AGE_MS) {
            return "stale timestamp age=" + (now - timestamp);
        }
        String state = stringValue(extras, "state").toLowerCase(Locale.US);
        if (!(YandexCoreBridgeContract.STATE_OFF.equals(state)
                || YandexCoreBridgeContract.STATE_LOADING.equals(state)
                || YandexCoreBridgeContract.STATE_ACTIVE.equals(state)
                || YandexCoreBridgeContract.STATE_REROUTING.equals(state)
                || YandexCoreBridgeContract.STATE_FINISHED.equals(state))) {
            return "state=" + state;
        }
        return "";
    }

    private static boolean knownLegacyAction(String action) {
        return NavigationFeature.ACTION_MANEUVER.equals(action)
                || NavigationFeature.ACTION_ETA.equals(action)
                || NavigationFeature.ACTION_NAVI_ON.equals(action)
                || NavigationFeature.ACTION_SPEED.equals(action)
                || NavigationFeature.ACTION_EXCEEDED.equals(action)
                || NavigationFeature.KIA_ACTION_MANEUVER.equals(action)
                || NavigationFeature.KIA_ACTION_ETA.equals(action)
                || NavigationFeature.KIA_ACTION_NAVI_ON.equals(action)
                || NavigationFeature.KIA_ACTION_SPEED.equals(action)
                || NavigationFeature.KIA_ACTION_EXCEEDED.equals(action)
                || "com.yf.navinfo".equals(action)
                || "com.teyes.MapAssistantService".equals(action)
                || "android.action.MOBILE_NAVIGATION".equals(action);
    }

    private static String stringValue(Bundle extras, String key) {
        Object value = extras == null ? null : extras.get(key);
        return value == null ? "" : String.valueOf(value).trim();
    }

    private static long longValue(Bundle extras, String key, long fallback) {
        Object value = extras == null ? null : extras.get(key);
        if (value instanceof Number) return ((Number) value).longValue();
        if (value == null) return fallback;
        try {
            return Long.parseLong(String.valueOf(value).trim());
        } catch (NumberFormatException ignored) {
            return fallback;
        }
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
        long now = SystemClock.elapsedRealtime();
        boolean strictYandex = mode == AppSettings.NAV_SOURCE_YANDEX;
        boolean recentCoreBridge = mode == AppSettings.NAV_SOURCE_AUTO
                && lastCoreBridgeSnapshotAt > 0L
                && now - lastCoreBridgeSnapshotAt < CORE_BRIDGE_FALLBACK_SUPPRESS_MS;
        if (!strictYandex && !recentCoreBridge) return false;
        if (now - lastSuppressedLogAt >= SUPPRESSED_LOG_MS) {
            lastSuppressedLogAt = now;
            AppLog.navigation(context, "Navigation legacy fallback suppressed: "
                    + (strictYandex ? "strict_yandex" : "core_bridge_recent")
                    + " action=" + action);
        }
        return true;
    }

    private static boolean sourceIngressAllowed(Context context, int source) {
        return context != null && NavigationSourcePolicy.ingressAllowed(
                AppSettings.navigationEnabled(context),
                AppSettings.navSourceMode(context),
                source);
    }
}
