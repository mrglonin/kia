package kia.app.core;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import kia.app.core.model.AdapterState;
import kia.app.core.model.AmpState;
import kia.app.core.model.CallState;
import kia.app.core.model.MediaState;
import kia.app.core.model.NavigationState;
import kia.app.core.model.RctaState;
import kia.app.core.model.TpmsState;
import kia.app.core.model.UpdateState;
import kia.app.core.model.VehicleState;

public final class StateStore {
    private static final String PREFS = "kia_state";
    private static final String NAV_PREFIX = "nav_";
    private static final long NAV_TX_PERSIST_INTERVAL_MS = 2000L;
    private static final Handler UI_BROADCAST_HANDLER = new Handler(Looper.getMainLooper());

    private static AdapterState adapter = AdapterState.empty();
    private static MediaState media = MediaState.empty();
    private static NavigationState navigation = NavigationState.empty();
    private static CallState call = CallState.empty();
    private static AmpState amp = AmpState.empty();
    private static UpdateState updates = UpdateState.empty();
    private static VehicleState vehicle = VehicleState.empty();
    private static TpmsState tpms = TpmsState.empty();
    private static RctaState rcta = RctaState.empty();
    private static String lastLog = "";
    private static boolean pendingBroadcast;
    private static long lastNavigationTxPersistAt;

    private StateStore() {
    }

    public static synchronized AdapterState adapter() {
        return adapter;
    }

    public static synchronized MediaState media() {
        return media;
    }

    public static synchronized NavigationState navigation() {
        return navigation;
    }

    public static synchronized CallState call() {
        return call;
    }

    public static synchronized AmpState amp() {
        return amp;
    }

    public static synchronized UpdateState updates() {
        return updates;
    }

    public static synchronized VehicleState vehicle() {
        return vehicle;
    }

    public static synchronized TpmsState tpms() {
        return tpms;
    }

    public static synchronized RctaState rcta() {
        return rcta;
    }

    public static synchronized String lastLog() {
        return lastLog;
    }

    public static synchronized void setAdapter(Context context, AdapterState value) {
        adapter = value == null ? AdapterState.empty() : value;
        changed(context);
    }

    public static synchronized void setMedia(Context context, MediaState value) {
        MediaState next = value == null ? MediaState.empty() : value;
        if (media != null
                && media.clusterTx != null
                && !media.clusterTx.isEmpty()
                && (next.clusterTx == null || next.clusterTx.isEmpty())) {
            next = next.withClusterTxText(media.clusterTx, next.updatedAt);
        }
        media = next;
        changed(context);
    }

    public static synchronized void setNavigation(Context context, NavigationState value) {
        NavigationState next = value == null ? NavigationState.empty() : value;
        if (navigation != null
                && navigation.clusterTx != null
                && !navigation.clusterTx.isEmpty()) {
            String mergedClusterTx = mergeProtectedNavigationTx(navigation.clusterTx, next.clusterTx);
            if (!mergedClusterTx.equals(next.clusterTx)) {
                next = next.withClusterTxText(mergedClusterTx, next.updatedAt);
            }
        }
        if (next.active && !hasNavigationDebug(next) && navigation != null
                && hasNavigationDebug(navigation)) {
            next = next.withNavigationDebug(navigation.mainManeuverId, navigation.routeActionId,
                    navigation.microManeuverId, navigation.microDistance,
                    navigation.microStatus,
                    navigation.grayRoadId, navigation.grayRoadScheme,
                    next.updatedAt);
        }
        if (next.active && !hasNavigationRaw(next) && navigation != null
                && hasNavigationRaw(navigation)) {
            next = next.withNavigationRaw(navigation.laneRaw, navigation.lanePosition,
                    navigation.roadSchemeRaw, navigation.upcomingRaw, next.updatedAt);
        }
        navigation = next;
        persistNavigation(context, navigation);
        lastNavigationTxPersistAt = SystemClock.elapsedRealtime();
        changed(context);
    }

    public static synchronized void setCall(Context context, CallState value) {
        call = value == null ? CallState.empty() : value;
        changed(context);
    }

    public static synchronized void restoreNavigation(Context context) {
        if (context == null) {
            navigation = NavigationState.empty();
            return;
        }
        SharedPreferences p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        NavigationState restored = new NavigationState(
                p.getBoolean(NAV_PREFIX + "active", false),
                p.getBoolean(NAV_PREFIX + "finishReached", false),
                p.getBoolean(NAV_PREFIX + "speedExceeded", false),
                p.getString(NAV_PREFIX + "maneuver", ""),
                p.getString(NAV_PREFIX + "maneuverText", ""),
                p.getString(NAV_PREFIX + "maneuverDistance", ""),
                p.getString(NAV_PREFIX + "routeDistance", ""),
                p.getString(NAV_PREFIX + "routeTime", ""),
                p.getString(NAV_PREFIX + "arrivalTime", ""),
                p.getString(NAV_PREFIX + "currentStreet", ""),
                p.getString(NAV_PREFIX + "nextStreet", ""),
                p.getString(NAV_PREFIX + "finishStreet", ""),
                p.getString(NAV_PREFIX + "speedLimit", ""),
                p.getString(NAV_PREFIX + "currentSpeed", ""),
                p.getString(NAV_PREFIX + "source", ""),
                p.getLong(NAV_PREFIX + "updatedAt", 0L));
        String laneHint = p.getString(NAV_PREFIX + "laneHint", "");
        String laneSource = p.getString(NAV_PREFIX + "laneSource", "");
        if (laneHint != null && !laneHint.isEmpty()) {
            restored = restored.withLaneHint(laneHint, laneSource, restored.updatedAt);
        }
        String eventHint = p.getString(NAV_PREFIX + "eventHint", "");
        String eventSource = p.getString(NAV_PREFIX + "eventSource", "");
        if (eventHint != null && !eventHint.isEmpty()) {
            restored = restored.withEventHint(eventHint, eventSource, restored.updatedAt);
        }
        restored = restored.withNavigationDebug(
                p.getString(NAV_PREFIX + "mainManeuverId", ""),
                p.getString(NAV_PREFIX + "routeActionId", ""),
                p.getString(NAV_PREFIX + "microManeuverId", ""),
                p.getString(NAV_PREFIX + "microDistance", ""),
                p.getString(NAV_PREFIX + "microStatus", ""),
                p.getString(NAV_PREFIX + "grayRoadId", ""),
                p.getString(NAV_PREFIX + "grayRoadScheme", ""),
                restored.updatedAt);
        restored = restored.withNavigationRaw(
                p.getString(NAV_PREFIX + "laneRaw", ""),
                p.getString(NAV_PREFIX + "lanePosition", ""),
                p.getString(NAV_PREFIX + "roadSchemeRaw", ""),
                p.getString(NAV_PREFIX + "upcomingRaw", ""),
                restored.updatedAt);
        String clusterVisual = p.getString(NAV_PREFIX + "clusterVisual", "");
        if (clusterVisual != null && !clusterVisual.isEmpty()) {
            restored = restored.withClusterVisualText(clusterVisual, restored.updatedAt);
        }
        String clusterTx = p.getString(NAV_PREFIX + "clusterTx", "");
        if (clusterTx != null && !clusterTx.isEmpty()) {
            restored = restored.withClusterTxText(clusterTx, restored.updatedAt);
        }
        navigation = restored;
    }

    private static boolean hasNavigationDebug(NavigationState value) {
        if (value == null) return false;
        return notEmpty(value.mainManeuverId)
                || notEmpty(value.routeActionId)
                || notEmpty(value.microManeuverId)
                || notEmpty(value.microDistance)
                || notEmpty(value.microStatus)
                || notEmpty(value.grayRoadId)
                || notEmpty(value.grayRoadScheme);
    }

    private static boolean hasNavigationRaw(NavigationState value) {
        if (value == null) return false;
        return notEmpty(value.laneRaw)
                || notEmpty(value.lanePosition)
                || notEmpty(value.roadSchemeRaw)
                || notEmpty(value.upcomingRaw);
    }

    private static boolean notEmpty(String value) {
        return value != null && !value.isEmpty();
    }

    private static void persistNavigation(Context context, NavigationState value) {
        if (context == null || value == null) return;
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit()
                .putBoolean(NAV_PREFIX + "active", value.active)
                .putBoolean(NAV_PREFIX + "finishReached", value.finishReached)
                .putBoolean(NAV_PREFIX + "speedExceeded", value.speedExceeded)
                .putString(NAV_PREFIX + "maneuver", value.maneuver)
                .putString(NAV_PREFIX + "maneuverText", value.maneuverText)
                .putString(NAV_PREFIX + "maneuverDistance", value.maneuverDistance)
                .putString(NAV_PREFIX + "routeDistance", value.routeDistance)
                .putString(NAV_PREFIX + "routeTime", value.routeTime)
                .putString(NAV_PREFIX + "arrivalTime", value.arrivalTime)
                .putString(NAV_PREFIX + "currentStreet", value.currentStreet)
                .putString(NAV_PREFIX + "nextStreet", value.nextStreet)
                .putString(NAV_PREFIX + "finishStreet", value.finishStreet)
                .putString(NAV_PREFIX + "speedLimit", value.speedLimit)
                .putString(NAV_PREFIX + "currentSpeed", value.currentSpeed)
                .putString(NAV_PREFIX + "source", value.source)
                .putString(NAV_PREFIX + "mainManeuverId", value.mainManeuverId)
                .putString(NAV_PREFIX + "routeActionId", value.routeActionId)
                .putString(NAV_PREFIX + "microManeuverId", value.microManeuverId)
                .putString(NAV_PREFIX + "microDistance", value.microDistance)
                .putString(NAV_PREFIX + "microStatus", value.microStatus)
                .putString(NAV_PREFIX + "grayRoadId", value.grayRoadId)
                .putString(NAV_PREFIX + "grayRoadScheme", value.grayRoadScheme)
                .putString(NAV_PREFIX + "laneHint", value.laneHint)
                .putString(NAV_PREFIX + "laneSource", value.laneSource)
                .putString(NAV_PREFIX + "eventHint", value.eventHint)
                .putString(NAV_PREFIX + "eventSource", value.eventSource)
                .putString(NAV_PREFIX + "clusterVisual", value.clusterVisual)
                .putString(NAV_PREFIX + "clusterTx", value.clusterTx)
                .putString(NAV_PREFIX + "laneRaw", value.laneRaw)
                .putString(NAV_PREFIX + "lanePosition", value.lanePosition)
                .putString(NAV_PREFIX + "roadSchemeRaw", value.roadSchemeRaw)
                .putString(NAV_PREFIX + "upcomingRaw", value.upcomingRaw)
                .putLong(NAV_PREFIX + "updatedAt", value.updatedAt)
                .apply();
    }

    public static synchronized void appendNavigationTx(Context context, String value) {
        String line = value == null ? "" : value.trim();
        if (line.isEmpty()) return;
        NavigationState current = navigation == null ? NavigationState.empty() : navigation;
        navigation = current.withClusterTxText(appendNavigationTxLine(current.clusterTx, line),
                System.currentTimeMillis());
        long now = SystemClock.elapsedRealtime();
        if (now - lastNavigationTxPersistAt >= NAV_TX_PERSIST_INTERVAL_MS) {
            persistNavigation(context, navigation);
            lastNavigationTxPersistAt = now;
        }
        changed(context);
    }

    public static synchronized void appendMediaTx(Context context, String value) {
        String line = value == null ? "" : value.trim();
        if (line.isEmpty()) return;
        MediaState current = media == null ? MediaState.empty() : media;
        media = current.withClusterTxText(appendRollingLine(current.clusterTx, line, 14, 3400),
                System.currentTimeMillis());
        changed(context);
    }

    public static synchronized void setAmp(Context context, AmpState value) {
        amp = value == null ? AmpState.empty() : value;
        changed(context);
    }

    public static synchronized void setUpdates(Context context, UpdateState value) {
        updates = value == null ? UpdateState.empty() : value;
        changed(context);
    }

    public static synchronized void setVehicle(Context context, VehicleState value) {
        vehicle = value == null ? VehicleState.empty() : value;
        changed(context);
    }

    public static synchronized void setTpms(Context context, TpmsState value) {
        tpms = value == null ? TpmsState.empty() : value;
        changed(context);
    }

    public static synchronized void setRcta(Context context, RctaState value) {
        rcta = value == null ? RctaState.empty() : value;
        changed(context);
    }

    static synchronized void setLastLog(Context context, String value) {
        lastLog = value == null ? "" : value;
        changed(context);
    }

    private static void changed(Context context) {
        if (context == null) return;
        Context broadcastContext = context.getApplicationContext();
        if (broadcastContext == null) return;
        if (pendingBroadcast) return;
        pendingBroadcast = true;
        UI_BROADCAST_HANDLER.post(() -> {
            pendingBroadcast = false;
            AppLog.broadcast(broadcastContext);
        });
    }

    private static String appendRollingLine(String current, String line, int maxLines, int maxChars) {
        String joined = (current == null || current.trim().isEmpty())
                ? line
                : current.trim() + "\n" + line;
        return trimRollingLines(joined, maxLines, maxChars);
    }

    private static String appendNavigationTxLine(String current, String line) {
        String previousCompass = lastLineStarting(current, "compass step=");
        String previousFinishDirection = lastLineStarting(current, "finish direction ");
        String rolled = appendRollingLine(current, line, 16, 3600);
        if (!previousCompass.isEmpty() && !hasLineStarting(rolled, "compass step=")) {
            rolled = previousCompass + "\n" + rolled;
        }
        if (!previousFinishDirection.isEmpty() && !hasLineStarting(rolled, "finish direction ")) {
            rolled = previousFinishDirection + "\n" + rolled;
        }
        return trimRollingLines(rolled, 18, 4200);
    }

    private static String mergeProtectedNavigationTx(String previous, String next) {
        String merged = next == null ? "" : next.trim();
        String previousCompass = lastLineStarting(previous, "compass step=");
        String previousFinishDirection = lastLineStarting(previous, "finish direction ");
        if (!previousCompass.isEmpty() && !hasLineStarting(merged, "compass step=")) {
            merged = merged.isEmpty() ? previousCompass : previousCompass + "\n" + merged;
        }
        if (!previousFinishDirection.isEmpty() && !hasLineStarting(merged, "finish direction ")) {
            merged = merged.isEmpty() ? previousFinishDirection : previousFinishDirection + "\n" + merged;
        }
        return trimRollingLines(merged, 18, 4200);
    }

    private static String trimRollingLines(String text, int maxLines, int maxChars) {
        String joined = text == null ? "" : text.trim();
        if (joined.isEmpty()) return "";
        String[] lines = joined.split("\\n");
        StringBuilder out = new StringBuilder();
        int start = Math.max(0, lines.length - maxLines);
        for (int i = start; i < lines.length; i++) {
            String item = lines[i] == null ? "" : lines[i].trim();
            if (item.isEmpty()) continue;
            if (out.length() > 0) out.append('\n');
            out.append(item);
        }
        if (out.length() <= maxChars) return out.toString();
        return out.substring(out.length() - maxChars);
    }

    private static String lastLineStarting(String text, String prefix) {
        String cleanText = text == null ? "" : text.trim();
        if (cleanText.isEmpty()) return "";
        String[] lines = cleanText.split("\\n");
        for (int i = lines.length - 1; i >= 0; i--) {
            String line = lines[i] == null ? "" : lines[i].trim();
            if (line.startsWith(prefix)) return line;
        }
        return "";
    }

    private static boolean hasLineStarting(String text, String prefix) {
        return !lastLineStarting(text, prefix).isEmpty();
    }
}
