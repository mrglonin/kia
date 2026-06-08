package kia.app.navigation.capture;

import android.content.Context;
import android.content.Intent;
import android.content.pm.ProviderInfo;
import android.database.Cursor;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.SystemClock;
import android.text.TextUtils;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import kia.app.core.AppLog;
import kia.app.core.settings.AppSettings;
import kia.app.navigation.domain.NavigationFeature;

public final class YandexCoreBridgeClient {
    private static final long ACTIVE_POLL_MS = 250L;
    private static final long IDLE_POLL_MS = 1000L;
    private static final long MISSING_PROVIDER_POLL_MS = 5000L;
    private static final long ERROR_POLL_MS = 3000L;
    private static final long MAX_ACTIVE_FRESHNESS_MS = 2500L;
    private static final long MISSING_PROVIDER_LOG_MS = 30000L;
    private static final long SPEED_SNAPSHOT_MIN_MS = 1000L;
    private static final long FINISH_MAX_REMAINING_METERS = 15L;
    private static final long FINISH_PREVIOUS_REMAINING_METERS = 80L;
    private static final long FINISH_RECENT_ROUTE_MS = 45000L;
    private static final long FINISH_MIN_ROUTE_ALIVE_MS = 3000L;
    private static final long ROUTE_REMAINING_MANEUVER_CONFLICT_METERS = 30L;
    private static final Pattern DISTANCE_NUMBER =
            Pattern.compile("[-+]?\\d+(?:[\\.,]\\d+)?");

    private final Context app;
    private HandlerThread thread;
    private Handler handler;
    private boolean running;
    private long lastSeq = Long.MIN_VALUE;
    private long lastTimestampElapsed = Long.MIN_VALUE;
    private long lastMissingProviderLogAt;
    private long lastErrorLogAt;
    private String lastState = "";
    private String lastSignature = "";
    private String lastSpeedSignature = "";
    private long lastSpeedSnapshotSentAt;
    private String lastManeuverDistanceKey = "";
    private long lastManeuverDistanceMeters = -1L;
    private long lastManeuverRouteRemainingMeters = -1L;
    private long lastLiveRouteMetricsAt = -1L;
    private long lastLiveRouteStartedAt = -1L;
    private long lastLiveRouteRemainingMeters = -1L;
    private String lastLiveRouteId = "";

    private final Runnable poller = new Runnable() {
        @Override
        public void run() {
            if (!running || handler == null) return;
            long delay = pollOnce();
            if (running && handler != null) handler.postDelayed(this, delay);
        }
    };

    public YandexCoreBridgeClient(Context context) {
        this.app = context.getApplicationContext();
    }

    public synchronized void start() {
        if (running) return;
        running = true;
        thread = new HandlerThread("KiaYandexCoreBridge");
        thread.start();
        handler = new Handler(thread.getLooper());
        handler.post(poller);
        AppLog.line(app, "Yandex Core Bridge: client started");
    }

    public synchronized void stop() {
        running = false;
        if (handler != null) handler.removeCallbacksAndMessages(null);
        handler = null;
        if (thread != null) thread.quitSafely();
        thread = null;
        AppLog.line(app, "Yandex Core Bridge: client stopped");
    }

    private long pollOnce() {
        if (!AppSettings.navigationEnabled(app) || !AppSettings.yandexNavigationEnabled(app)) {
            return IDLE_POLL_MS;
        }
        if (!providerAvailable()) {
            logMissingProvider();
            return MISSING_PROVIDER_POLL_MS;
        }
        try {
            Bundle snapshot = readSnapshot();
            if (snapshot == null || snapshot.isEmpty()) return IDLE_POLL_MS;
            return applySnapshot(snapshot);
        } catch (Exception e) {
            logError("poll", e);
            return ERROR_POLL_MS;
        }
    }

    private boolean providerAvailable() {
        try {
            ProviderInfo provider = app.getPackageManager().resolveContentProvider(
                    YandexCoreBridgeContract.AUTHORITY, 0);
            return provider != null;
        } catch (Exception e) {
            return false;
        }
    }

    private Bundle readSnapshot() {
        try {
            Bundle bundle = app.getContentResolver().call(YandexCoreBridgeContract.STATE_URI,
                    YandexCoreBridgeContract.METHOD_GET_STATE, null, null);
            if (bundle != null && !bundle.isEmpty()) return bundle;
        } catch (Exception e) {
            logError("call", e);
        }
        try (Cursor cursor = app.getContentResolver().query(
                YandexCoreBridgeContract.STATE_URI, null, null, null, null)) {
            if (cursor == null || !cursor.moveToFirst()) return null;
            Bundle out = new Bundle();
            for (int i = 0; i < cursor.getColumnCount(); i++) {
                String key = cursor.getColumnName(i);
                if (TextUtils.isEmpty(key) || cursor.isNull(i)) continue;
                int type = cursor.getType(i);
                if (type == Cursor.FIELD_TYPE_INTEGER) out.putLong(key, cursor.getLong(i));
                else if (type == Cursor.FIELD_TYPE_FLOAT) out.putDouble(key, cursor.getDouble(i));
                else out.putString(key, cursor.getString(i));
            }
            return out;
        }
    }

    private long applySnapshot(Bundle snapshot) {
        String state = lower(firstString(snapshot, "state", "route_state", "status"));
        if (TextUtils.isEmpty(state)) {
            state = bool(snapshot, "active", false)
                    ? YandexCoreBridgeContract.STATE_ACTIVE : YandexCoreBridgeContract.STATE_OFF;
        }
        long seq = longValue(snapshot, "seq", "sequence", -1L);
        long timestampElapsed = longValue(snapshot, "timestamp_elapsed_ms", "elapsed_realtime_ms", -1L);
        long freshness = freshnessMs(snapshot, timestampElapsed);
        boolean routeLive = routeLive(state);
        if (YandexCoreBridgeContract.STATE_ACTIVE.equals(state)) {
            normalizeActiveSnapshot(snapshot);
            rememberLiveRoute(snapshot);
        } else if (!routeLive) {
            resetManeuverDistanceGuard();
        }
        String signature = snapshotSignature(state, snapshot);
        if (routeLive && freshness > MAX_ACTIVE_FRESHNESS_MS) {
            if (!YandexCoreBridgeContract.STATE_OFF.equals(lastState)) {
                sendOff("stale", freshness, snapshot);
                lastState = YandexCoreBridgeContract.STATE_OFF;
                lastSignature = "";
            }
            AppLog.line(app, "Yandex Core Bridge: stale snapshot ignored freshness=" + freshness);
            return ACTIVE_POLL_MS;
        }
        sendSpeedSnapshot(snapshot, freshness);
        if (YandexCoreBridgeContract.STATE_OFF.equals(state)
                && YandexCoreBridgeContract.STATE_OFF.equals(lastState)) {
            lastSeq = seq;
            lastTimestampElapsed = timestampElapsed;
            lastSignature = signature;
            return IDLE_POLL_MS;
        }
        if (state.equals(lastState) && signature.equals(lastSignature)) {
            lastSeq = seq;
            lastTimestampElapsed = timestampElapsed;
            if (routeLive) {
                NavigationFeature.get(app).touchYandexCoreBridgeHeartbeat(hasRouteMetrics(snapshot));
            }
            return routeLive ? ACTIVE_POLL_MS : IDLE_POLL_MS;
        }
        if (YandexCoreBridgeContract.STATE_FINISHED.equals(state)
                && !finishedSnapshotConfirmed(snapshot, freshness)) {
            lastSeq = seq;
            lastTimestampElapsed = timestampElapsed;
            lastSignature = "";
            long remaining = routeRemainingMeters(snapshot);
            AppLog.line(app, "Yandex Core Bridge: ignored unconfirmed finished state"
                    + " remaining=" + remaining
                    + " lastRemaining=" + lastLiveRouteRemainingMeters
                    + " activeAge=" + liveRouteAgeMs()
                    + " freshness=" + freshness);
            if (hasRouteMetrics(snapshot)) {
                normalizeActiveSnapshot(snapshot);
                sendActiveSnapshot(freshness, snapshot);
            } else {
                sendLoading("finished_unconfirmed", freshness, snapshot);
            }
            return ACTIVE_POLL_MS;
        }
        lastSeq = seq;
        lastTimestampElapsed = timestampElapsed;
        lastState = state;
        lastSignature = signature;

        if (YandexCoreBridgeContract.STATE_OFF.equals(state)) {
            sendOff("off", freshness, snapshot);
            return IDLE_POLL_MS;
        }
        if (YandexCoreBridgeContract.STATE_FINISHED.equals(state)) {
            sendFinished(freshness, snapshot);
            return IDLE_POLL_MS;
        }
        if (YandexCoreBridgeContract.STATE_LOADING.equals(state)
                || YandexCoreBridgeContract.STATE_REROUTING.equals(state)) {
            sendLoading(state, freshness, snapshot);
            return ACTIVE_POLL_MS;
        }
        sendActiveSnapshot(freshness, snapshot);
        return ACTIVE_POLL_MS;
    }

    private void sendLoading(String state, long freshness, Bundle snapshot) {
        resetDistanceGuards();
        Intent active = baseIntent(NavigationFeature.KIA_ACTION_NAVI_ON, snapshot, freshness);
        active.putExtra("navi_on", true);
        active.putExtra("active", true);
        active.putExtra("bridge_state", state);
        NavigationFeature.get(app).handle(active);
    }

    private void sendActiveSnapshot(long freshness, Bundle snapshot) {
        Intent active = baseIntent(NavigationFeature.KIA_ACTION_NAVI_ON, snapshot, freshness);
        active.putExtra("navi_on", true);
        active.putExtra("active", true);
        putRoute(active, snapshot);
        NavigationFeature.get(app).handle(active);

        String mainEvent = firstString(snapshot, "main_event", "voice_main_event", "route_event");
        String eventManeuver = clusterManeuverFromEvent(mainEvent);
        String maneuver = first(eventManeuver, firstString(snapshot,
                "maneuver", "maneuver_type",
                "current_maneuver", "image_id", "imageId", "direction"));
        String maneuverDistance = maneuverDistance(snapshot);
        if (TextUtils.isEmpty(maneuverDistance) && !TextUtils.isEmpty(eventManeuver)) {
            maneuverDistance = "0 м";
        }
        if (earlyFinishManeuver(maneuver, snapshot)) {
            AppLog.line(app, "Yandex Core Bridge: suppressed early finish maneuver"
                    + " remaining=" + routeRemainingMeters(snapshot)
                    + " maneuver=" + maneuverMeters(snapshot));
        } else if (!TextUtils.isEmpty(maneuver) && !TextUtils.isEmpty(maneuverDistance)) {
            Intent man = baseIntent(NavigationFeature.KIA_ACTION_MANEUVER, snapshot, freshness);
            man.putExtra("imageId", maneuver);
            man.putExtra("maneuver", maneuver);
            man.putExtra("direction", maneuver);
            man.putExtra("distance", maneuverDistance);
            putString(man, "main_event", mainEvent);
            putString(man, "voice_main_event", firstString(snapshot, "voice_main_event"));
            putString(man, "voice_prompt", firstString(snapshot, "voice_prompt"));
            putString(man, "exit_number", firstString(snapshot,
                    "exit_number", "roundabout_exit_number"));
            putString(man, "roundabout_exit", firstString(snapshot,
                    "roundabout_exit", "roundabout_exit_number", "exit_number"));
            putString(man, "route_action", firstString(snapshot, "route_action", "routeAction"));
            putString(man, "highlighted_direction", firstString(snapshot,
                    "highlighted_direction", "highlightedDirection"));
            putString(man, "route_road_options", firstString(snapshot,
                    "road_options", "route_road_options", "available_directions"));
            putString(man, "gray_road_options", firstString(snapshot,
                    "gray_road_options", "road_options", "available_directions"));
            putLaneExtras(man, snapshot);
            NavigationFeature.get(app).handle(man);
        }

        Intent eta = baseIntent(NavigationFeature.KIA_ACTION_ETA, snapshot, freshness);
        putRoute(eta, snapshot);
        NavigationFeature.get(app).handle(eta);
    }

    private boolean sendSpeedSnapshot(Bundle snapshot, long freshness) {
        long speed = longValue(snapshot, "current_speed_kmh", "speed_kmh", -1L);
        long limit = longValue(snapshot, "speed_limit_kmh", "speed_limit", -1L);
        boolean hasExceeded = snapshot != null
                && (snapshot.containsKey("speed_exceeded") || snapshot.containsKey("exceeded"));
        if (speed < 0 && limit <= 0 && !hasExceeded) return false;

        boolean exceeded = bool(snapshot, "speed_exceeded", bool(snapshot, "exceeded", false));
        String signature = speed + "|" + limit + "|" + (hasExceeded ? String.valueOf(exceeded) : "-");
        long now = SystemClock.elapsedRealtime();
        if (signature.equals(lastSpeedSignature)
                && now - lastSpeedSnapshotSentAt < SPEED_SNAPSHOT_MIN_MS) {
            return false;
        }
        lastSpeedSignature = signature;
        lastSpeedSnapshotSentAt = now;

        Intent speedIntent = baseIntent(NavigationFeature.KIA_ACTION_SPEED, snapshot, freshness);
        if (speed >= 0) speedIntent.putExtra("current_speed", String.valueOf(speed));
        if (limit > 0) speedIntent.putExtra("speed_limit", String.valueOf(limit));
        if (hasExceeded) speedIntent.putExtra("exceeded", exceeded);
        NavigationFeature.get(app).handle(speedIntent);
        return true;
    }

    private void sendFinished(long freshness, Bundle snapshot) {
        resetRouteGuards();
        Intent off = baseIntent(NavigationFeature.KIA_ACTION_NAVI_ON, snapshot, freshness);
        off.putExtra("navi_on", false);
        off.putExtra("active", false);
        off.putExtra("finish_reached", true);
        off.putExtra("route_finished", true);
        NavigationFeature.get(app).handle(off);
    }

    private void sendOff(String reason, long freshness, Bundle snapshot) {
        resetRouteGuards();
        Intent off = baseIntent(NavigationFeature.KIA_ACTION_NAVI_ON, snapshot, freshness);
        off.putExtra("navi_on", false);
        off.putExtra("active", false);
        off.putExtra("route_removed", true);
        off.putExtra("bridge_reason", reason);
        NavigationFeature.get(app).handle(off);
    }

    private Intent baseIntent(String action, Bundle snapshot, long freshness) {
        Intent intent = new Intent(action);
        intent.setPackage(app.getPackageName());
        intent.putExtra("source", YandexCoreBridgeContract.SOURCE);
        intent.putExtra("bridge_freshness_ms", freshness);
        putString(intent, "bridge_state", firstString(snapshot, "state", "route_state", "status"));
        putString(intent, "main_event", firstString(snapshot, "main_event", "voice_main_event", "route_event"));
        putString(intent, "voice_main_event", firstString(snapshot, "voice_main_event"));
        putString(intent, "voice_prompt", firstString(snapshot, "voice_prompt"));
        putString(intent, "route_id", firstString(snapshot, "route_id", "routeId"));
        long seq = longValue(snapshot, "seq", "sequence", -1L);
        if (seq >= 0) intent.putExtra("seq", seq);
        return intent;
    }

    private void putRoute(Intent intent, Bundle snapshot) {
        long remainingMeters = routeRemainingMeters(snapshot);
        long totalMeters = routeTotalMeters(snapshot);
        long maneuverMeters = maneuverMeters(snapshot);
        remainingMeters = correctedRemainingMeters(remainingMeters, totalMeters);
        remainingMeters = correctedRemainingByGuidance(remainingMeters, maneuverMeters);
        if (remainingMeters >= 0L && totalMeters >= 0L && totalMeters < remainingMeters) {
            totalMeters = remainingMeters;
        }
        String remaining = metersText(remainingMeters);
        String total = metersText(totalMeters);
        String eta = secondsText(longValue(snapshot,
                "eta_seconds", "remaining_seconds", "time_left_seconds", -1L));
        if (TextUtils.isEmpty(remaining)) {
            remaining = firstString(snapshot, "remaining_distance", "route_distance",
                    "edistance", "distance_left", "eta_distance_left");
        }
        if (TextUtils.isEmpty(total)) {
            total = firstString(snapshot, "route_total_len", "route_full_len",
                    "route_total_distance", "full_route_distance", "routeTotalDistance",
                    "totalRouteDistance");
        }
        if (TextUtils.isEmpty(eta)) {
            eta = firstString(snapshot, "route_time", "remaining_time", "time_left",
                    "eta_time_left");
        }
        putString(intent, "route_distance", remaining);
        putString(intent, "remaining_distance", remaining);
        putString(intent, "edistance", remaining);
        putString(intent, "route_total_len", total);
        putMeters(intent, remainingMeters, "remaining_meters", "remaining_distance_meters",
                "distance_left_meters");
        putMeters(intent, totalMeters, "route_total_meters", "total_route_meters",
                "route_total_distance_meters");
        putString(intent, "route_time", eta);
        putString(intent, "time_left", eta);
        putString(intent, "arrival_time", firstString(snapshot, "arrival_time", "arrival", "eta"));
        putString(intent, "finish_point", firstString(snapshot, "finish_point", "finishPoint"));
        putString(intent, "finish_street", firstString(snapshot,
                "finish_street", "finishStreet", "finish_address", "destination_name"));
        putString(intent, "current_street", firstString(snapshot, "current_street", "currentStreet"));
        putString(intent, "next_street", firstString(snapshot, "next_street", "nextStreet"));
        putString(intent, "main_event", firstString(snapshot, "main_event", "voice_main_event",
                "route_event"));
        putString(intent, "voice_prompt", firstString(snapshot, "voice_prompt"));
        putString(intent, "exit_number", firstString(snapshot,
                "exit_number", "roundabout_exit_number"));
        putString(intent, "roundabout_exit", firstString(snapshot,
                "roundabout_exit", "roundabout_exit_number", "exit_number"));
        putManeuverDistance(intent, snapshot);
        putLaneExtras(intent, snapshot);
    }

    private void putManeuverDistance(Intent intent, Bundle snapshot) {
        long meters = maneuverMeters(snapshot);
        String distance = maneuverDistance(snapshot);
        putString(intent, "maneuver_distance", distance);
        putString(intent, "current_maneuver_distance", distance);
        putString(intent, "distance_to_maneuver", distance);
        putString(intent, "next_maneuver_distance", distance);
        putString(intent, "last_maneuver_distance", distance);
        putMeters(intent, meters, "maneuver_distance_meters",
                "current_maneuver_distance_meters", "distance_to_maneuver_meters");
    }

    private void putLaneExtras(Intent intent, Bundle snapshot) {
        long laneMeters = firstLong(snapshot, -1L, "lane_distance_meters",
                "micro_distance_meters", "laneDistanceMeters", "microDistanceMeters");
        String laneDistance = metersText(laneMeters);
        if (TextUtils.isEmpty(laneDistance)) {
            laneDistance = firstString(snapshot, "lane_distance", "laneDistance",
                    "micro_distance", "microDistance");
        }
        putString(intent, "lane_distance", laneDistance);
        putString(intent, "micro_distance", laneDistance);
        putMeters(intent, laneMeters, "lane_distance_meters", "micro_distance_meters");
        putString(intent, "lane_distance_unit", firstString(snapshot,
                "lane_distance_unit", "micro_distance_unit"));
        String laneItems = firstString(snapshot, "ignored_lane_items", "lane_items");
        String rawLaneItems = firstString(snapshot, "ignored_raw_lane_items", "raw_lane_items");
        String routeDirections = firstString(snapshot, "route_road_options", "road_options",
                "available_directions");
        String allowedDirections = first(routeDirections, firstString(snapshot,
                "ignored_allowed_directions", "allowed_directions"));
        String highlight = firstString(snapshot, "ignored_recommended_lanes",
                "ignored_lane_maneuver", "recommended_lanes", "lane_highlight");
        if (laneGuidanceConflicts(routeDirections, first(rawLaneItems, laneItems, highlight))) {
            laneItems = "";
            rawLaneItems = "";
            highlight = "";
        }
        putString(intent, "ignored_lane_items", laneItems);
        putString(intent, "ignored_raw_lane_items", rawLaneItems);
        putString(intent, "ignored_allowed_directions", allowedDirections);
        putString(intent, "lane_items", first(rawLaneItems, laneItems));
        putString(intent, "raw_lane_items", first(rawLaneItems, laneItems));
        putString(intent, "allowed_directions", allowedDirections);
        putString(intent, "ignored_recommended_lanes", highlight);
        putString(intent, "ignored_lane_maneuver", highlight);
        putString(intent, "recommended_lanes", highlight);
        putString(intent, "lane_highlight", highlight);
        putString(intent, "highlighted_direction", highlight);
        putString(intent, "highlighted_directions", highlight);
        putString(intent, "lane_maneuver", highlight);
        putLaneDirectionFlags(intent, first(allowedDirections, rawLaneItems, laneItems));
        if (bool(snapshot, "lane_guidance", false)
                || !TextUtils.isEmpty(laneDistance)
                || !TextUtils.isEmpty(first(rawLaneItems, laneItems, allowedDirections, highlight))) {
            intent.putExtra("lane_guidance", true);
        }
    }

    private void normalizeActiveSnapshot(Bundle snapshot) {
        if (snapshot == null || snapshot.isEmpty()) return;
        long remainingMeters = routeRemainingMeters(snapshot);
        long totalMeters = routeTotalMeters(snapshot);
        remainingMeters = correctedRemainingMeters(remainingMeters, totalMeters);
        long maneuverMeters = maneuverMeters(snapshot);
        long correctedRemaining = correctedRemainingByGuidance(remainingMeters, maneuverMeters);
        if (correctedRemaining != remainingMeters) {
            AppLog.line(app, "Yandex Core Bridge: route remaining corrected by maneuver"
                    + " route=" + remainingMeters + " maneuver=" + maneuverMeters);
            remainingMeters = correctedRemaining;
        }
        putMeters(snapshot, remainingMeters, "remaining_meters", "remaining_distance_meters",
                "distance_left_meters");
        if (remainingMeters >= 0L && totalMeters >= 0L && totalMeters < remainingMeters) {
            putMeters(snapshot, remainingMeters, "route_total_meters", "total_route_meters",
                    "route_total_distance_meters");
        }

        String maneuverKey = first(clusterManeuverFromEvent(firstString(snapshot,
                        "main_event", "voice_main_event", "route_event")),
                firstString(snapshot,
                "maneuver", "maneuver_type",
                "current_maneuver", "image_id", "imageId", "direction"));
        String roadKey = firstString(snapshot, "route_road_options", "road_options",
                "available_directions");
        long correctedManeuver = correctedManeuverMeters(maneuverKey, roadKey,
                maneuverMeters, remainingMeters);
        putMeters(snapshot, correctedManeuver, "maneuver_distance_meters",
                "current_maneuver_distance_meters", "distance_to_maneuver_meters");
    }

    private long correctedManeuverMeters(String maneuver, String road, long meters,
                                         long routeRemainingMeters) {
        if (meters < 0L || TextUtils.isEmpty(maneuver)) {
            resetManeuverDistanceGuard();
            return meters;
        }
        String key = lower(maneuver) + "|" + lower(road);
        if (!key.equals(lastManeuverDistanceKey) || lastManeuverDistanceMeters < 0L) {
            rememberManeuverDistance(key, meters, routeRemainingMeters);
            return meters;
        }
        boolean routeRemainingKnown = routeRemainingMeters >= 0L
                && lastManeuverRouteRemainingMeters >= 0L;
        boolean routeDidNotGrow = routeRemainingKnown
                && routeRemainingMeters <= lastManeuverRouteRemainingMeters + 10L;
        boolean noisyBackstep = routeDidNotGrow && meters > lastManeuverDistanceMeters + 5L;
        long jumpThreshold = Math.max(80L, Math.round(lastManeuverDistanceMeters * 0.45));
        boolean largeUnknownJump = !routeRemainingKnown
                && meters > lastManeuverDistanceMeters + jumpThreshold;
        if (noisyBackstep || largeUnknownJump) {
            lastManeuverRouteRemainingMeters = routeRemainingMeters;
            return lastManeuverDistanceMeters;
        }
        rememberManeuverDistance(key, meters, routeRemainingMeters);
        return meters;
    }

    private void rememberManeuverDistance(String key, long meters, long routeRemainingMeters) {
        lastManeuverDistanceKey = key;
        lastManeuverDistanceMeters = meters;
        lastManeuverRouteRemainingMeters = routeRemainingMeters;
    }

    private void resetDistanceGuards() {
        resetManeuverDistanceGuard();
    }

    private void resetRouteGuards() {
        resetManeuverDistanceGuard();
        resetLiveRouteGuard();
    }

    private void resetManeuverDistanceGuard() {
        lastManeuverDistanceKey = "";
        lastManeuverDistanceMeters = -1L;
        lastManeuverRouteRemainingMeters = -1L;
    }

    private void resetLiveRouteGuard() {
        lastLiveRouteMetricsAt = -1L;
        lastLiveRouteStartedAt = -1L;
        lastLiveRouteRemainingMeters = -1L;
        lastLiveRouteId = "";
    }

    private void rememberLiveRoute(Bundle snapshot) {
        long remaining = routeRemainingMeters(snapshot);
        if (remaining < 0L && !hasRouteMetrics(snapshot)) return;
        long now = SystemClock.elapsedRealtime();
        String routeId = firstString(snapshot, "route_id", "routeId");
        boolean newRoute = lastLiveRouteMetricsAt <= 0L
                || (!TextUtils.isEmpty(routeId) && !routeId.equals(lastLiveRouteId));
        if (newRoute) {
            lastLiveRouteStartedAt = now;
            lastLiveRouteId = routeId;
        }
        lastLiveRouteMetricsAt = now;
        if (remaining >= 0L) lastLiveRouteRemainingMeters = remaining;
    }

    private boolean finishedSnapshotConfirmed(Bundle snapshot, long freshness) {
        long now = SystemClock.elapsedRealtime();
        if (lastLiveRouteMetricsAt <= 0L || lastLiveRouteStartedAt <= 0L) return false;
        if (now - lastLiveRouteMetricsAt > FINISH_RECENT_ROUTE_MS) return false;
        if (now - lastLiveRouteStartedAt < FINISH_MIN_ROUTE_ALIVE_MS) return false;
        long remaining = routeRemainingMeters(snapshot);
        boolean snapshotNear = remaining >= 0L && remaining <= FINISH_MAX_REMAINING_METERS;
        boolean previousNear = lastLiveRouteRemainingMeters >= 0L
                && lastLiveRouteRemainingMeters <= FINISH_PREVIOUS_REMAINING_METERS;
        return snapshotNear && previousNear && freshness <= FINISH_RECENT_ROUTE_MS;
    }

    private long liveRouteAgeMs() {
        return lastLiveRouteStartedAt <= 0L ? -1L
                : Math.max(0L, SystemClock.elapsedRealtime() - lastLiveRouteStartedAt);
    }

    private static boolean routeLive(String state) {
        return YandexCoreBridgeContract.STATE_ACTIVE.equals(state)
                || YandexCoreBridgeContract.STATE_LOADING.equals(state)
                || YandexCoreBridgeContract.STATE_REROUTING.equals(state);
    }

    private static long freshnessMs(Bundle snapshot, long timestampElapsed) {
        long explicit = longValue(snapshot, "freshness_ms", "freshnessMs", -1L);
        if (explicit >= 0L) return explicit;
        if (timestampElapsed <= 0L) return 0L;
        return Math.max(0L, SystemClock.elapsedRealtime() - timestampElapsed);
    }

    private static boolean hasRouteMetrics(Bundle snapshot) {
        String distance = first(metersText(firstLong(snapshot, -1L,
                        "remaining_meters", "remainingMeters", "remaining_distance_meters",
                        "distance_left_meters")),
                firstString(snapshot, "remaining_distance", "route_distance",
                        "edistance", "distance_left", "eta_distance_left"));
        String time = firstString(snapshot, "route_time", "remaining_time", "time_left",
                "eta_time_left");
        String arrival = firstString(snapshot, "arrival_time", "arrival", "eta");
        return !TextUtils.isEmpty(distance) && (!TextUtils.isEmpty(time) || !TextUtils.isEmpty(arrival));
    }

    private static long routeRemainingMeters(Bundle snapshot) {
        long numeric = firstLong(snapshot, -1L,
                "remaining_meters", "remainingMeters", "remaining_distance_meters",
                "distance_left_meters");
        long text = firstDistanceMeters(snapshot,
                "remaining_distance", "route_distance", "edistance",
                "distance_left", "eta_distance_left");
        return correctedRemainingMeters(numeric >= 0L ? numeric : text, routeTotalMeters(snapshot));
    }

    private static long routeTotalMeters(Bundle snapshot) {
        long numeric = firstLong(snapshot, -1L,
                "total_route_meters", "route_total_meters", "routeTotalMeters",
                "route_total_distance_meters");
        if (numeric >= 0L) return numeric;
        return firstDistanceMeters(snapshot,
                "route_total_len", "route_full_len", "route_total_distance",
                "full_route_distance", "routeTotalDistance", "totalRouteDistance");
    }

    private static String snapshotSignature(String state, Bundle snapshot) {
        if (snapshot == null || snapshot.isEmpty()) return lower(state);
        ArrayList<String> keys = new ArrayList<>(snapshot.keySet());
        Collections.sort(keys);
        StringBuilder out = new StringBuilder(lower(state));
        for (String key : keys) {
            if (volatileKey(key)) continue;
            Object value = snapshot.get(key);
            if (value == null) continue;
            out.append('|').append(key).append('=').append(String.valueOf(value).trim());
        }
        return out.toString();
    }

    private static boolean volatileKey(String key) {
        String clean = lower(key);
        return "seq".equals(clean)
                || "sequence".equals(clean)
                || "timestamp_ms".equals(clean)
                || "timestampelapsedms".equals(clean)
                || "timestamp_elapsed_ms".equals(clean)
                || "elapsed_realtime_ms".equals(clean)
                || "updated_at_elapsed_ms".equals(clean)
                || "freshness_ms".equals(clean)
                || "freshnessms".equals(clean)
                || "bridge_freshness_ms".equals(clean);
    }

    private void logMissingProvider() {
        long now = SystemClock.elapsedRealtime();
        if (now - lastMissingProviderLogAt < MISSING_PROVIDER_LOG_MS) return;
        lastMissingProviderLogAt = now;
        AppLog.line(app, "Yandex Core Bridge: provider not found");
    }

    private void logError(String stage, Exception e) {
        long now = SystemClock.elapsedRealtime();
        if (now - lastErrorLogAt < MISSING_PROVIDER_LOG_MS) return;
        lastErrorLogAt = now;
        AppLog.line(app, "Yandex Core Bridge " + stage + ": "
                + e.getClass().getSimpleName() + " " + e.getMessage());
    }

    private static void putString(Intent intent, String key, String value) {
        if (!TextUtils.isEmpty(value)) intent.putExtra(key, value);
    }

    private static void putMeters(Intent intent, long meters, String... keys) {
        if (intent == null || meters < 0L || keys == null) return;
        String value = String.valueOf(meters);
        for (String key : keys) {
            if (!TextUtils.isEmpty(key)) intent.putExtra(key, value);
        }
    }

    private static void putMeters(Bundle bundle, long meters, String... keys) {
        if (bundle == null || meters < 0L || keys == null) return;
        for (String key : keys) {
            if (!TextUtils.isEmpty(key)) bundle.putLong(key, meters);
        }
    }

    private static String first(String... values) {
        if (values == null) return "";
        for (String value : values) {
            if (!TextUtils.isEmpty(value)) return value;
        }
        return "";
    }

    private static void putLaneDirectionFlags(Intent intent, String value) {
        if (TextUtils.isEmpty(value)) return;
        intent.putExtra("lane_has_straight", laneTextHas(value, "straight"));
        intent.putExtra("lane_has_right", laneTextHas(value, "right"));
        intent.putExtra("lane_has_left", laneTextHas(value, "left"));
        intent.putExtra("lane_has_uturn_left", laneTextHas(value, "uturn_left"));
        intent.putExtra("lane_has_uturn_right", laneTextHas(value, "uturn_right"));
    }

    private static boolean laneTextHas(String value, String token) {
        String text = lower(value).replace('-', '_');
        if (TextUtils.isEmpty(text) || TextUtils.isEmpty(token)) return false;
        String[] parts = text.split("[,;\\s=:@#|()\\[\\]{}]+");
        String needle = lower(token).replace('-', '_');
        for (String part : parts) {
            if (needle.equals(part)) return true;
        }
        if ("straight".equals(needle)) {
            return text.contains("straight") || text.contains("forward")
                    || text.contains("ahead") || text.contains("прям");
        }
        if ("right".equals(needle)) {
            return text.contains("right90") || text.contains("right45")
                    || text.contains("right135") || text.contains("turn_right")
                    || text.contains("направо") || text.contains("право");
        }
        if ("left".equals(needle)) {
            return text.contains("left90") || text.contains("left45")
                    || text.contains("left135") || text.contains("turn_left")
                    || text.contains("налево") || text.contains("лево");
        }
        if ("uturn_left".equals(needle)) {
            return text.contains("left180") || text.contains("turn_back_left")
                    || text.contains("uturn_left") || text.contains("u_turn_left");
        }
        if ("uturn_right".equals(needle)) {
            return text.contains("right180") || text.contains("turn_back_right")
                    || text.contains("uturn_right") || text.contains("u_turn_right");
        }
        return false;
    }

    private static boolean laneGuidanceConflicts(String routeDirections, String laneText) {
        if (TextUtils.isEmpty(routeDirections) || TextUtils.isEmpty(laneText)) return false;
        boolean routeLeft = directionLeft(routeDirections);
        boolean routeRight = directionRight(routeDirections);
        boolean laneLeft = directionLeft(laneText);
        boolean laneRight = directionRight(laneText);
        if (routeLeft && laneRight && !laneLeft) return true;
        return routeRight && laneLeft && !laneRight;
    }

    private static boolean directionLeft(String value) {
        return laneTextHas(value, "left") || laneTextHas(value, "uturn_left");
    }

    private static boolean directionRight(String value) {
        return laneTextHas(value, "right") || laneTextHas(value, "uturn_right");
    }

    private static String firstString(Bundle bundle, String... keys) {
        if (bundle == null || keys == null) return "";
        for (String key : keys) {
            if (TextUtils.isEmpty(key) || !bundle.containsKey(key)) continue;
            Object value = bundle.get(key);
            String text = value == null ? "" : String.valueOf(value).trim();
            if (!TextUtils.isEmpty(text)) return text;
        }
        return "";
    }

    private static String maneuverDistance(Bundle snapshot) {
        String distance = metersText(maneuverMeters(snapshot));
        if (TextUtils.isEmpty(distance)) {
            distance = firstString(snapshot, "maneuver_distance", "current_maneuver_distance",
                    "distance_to_maneuver", "next_maneuver_distance", "last_maneuver_distance");
        }
        return distance;
    }

    private static boolean earlyFinishManeuver(String maneuver, Bundle snapshot) {
        if (!isFinishManeuver(maneuver)) return false;
        long remaining = routeRemainingMeters(snapshot);
        long maneuverMeters = maneuverMeters(snapshot);
        if (remaining > FINISH_MAX_REMAINING_METERS) return true;
        return maneuverMeters > FINISH_MAX_REMAINING_METERS;
    }

    private static boolean isFinishManeuver(String value) {
        String text = lower(value);
        return text.contains("finish") || text.contains("arriv")
                || text.contains("destination") || text.contains("финиш");
    }

    private static String clusterManeuverFromEvent(String event) {
        String value = lower(event);
        if (TextUtils.isEmpty(value)) return "";
        return value.startsWith("context_ra_") ? value : "";
    }

    private static long maneuverMeters(Bundle snapshot) {
        return firstLong(snapshot, -1L, "maneuver_distance_meters",
                "current_maneuver_distance_meters", "distance_to_maneuver_meters");
    }

    private static boolean bool(Bundle bundle, String key, boolean fallback) {
        if (bundle == null || !bundle.containsKey(key)) return fallback;
        Object value = bundle.get(key);
        if (value instanceof Boolean) return (Boolean) value;
        if (value instanceof Number) return ((Number) value).intValue() != 0;
        if (value == null) return fallback;
        String text = lower(String.valueOf(value));
        if ("1".equals(text) || "true".equals(text) || "yes".equals(text) || "on".equals(text)) return true;
        if ("0".equals(text) || "false".equals(text) || "no".equals(text) || "off".equals(text)) return false;
        return fallback;
    }

    private static long longValue(Bundle bundle, String key1, String key2, long fallback) {
        long value = longValue(bundle, key1, fallback);
        return value != fallback ? value : longValue(bundle, key2, fallback);
    }

    private static long longValue(Bundle bundle, String key1, String key2, String key3, long fallback) {
        long value = longValue(bundle, key1, fallback);
        if (value != fallback) return value;
        value = longValue(bundle, key2, fallback);
        return value != fallback ? value : longValue(bundle, key3, fallback);
    }

    private static long longValue(Bundle bundle, String key, long fallback) {
        if (bundle == null || TextUtils.isEmpty(key) || !bundle.containsKey(key)) return fallback;
        Object value = bundle.get(key);
        if (value instanceof Number) return ((Number) value).longValue();
        if (value == null) return fallback;
        try {
            return Math.round(Double.parseDouble(String.valueOf(value).trim().replace(',', '.')));
        } catch (Exception e) {
            return fallback;
        }
    }

    private static long firstLong(Bundle bundle, long fallback, String... keys) {
        if (keys == null) return fallback;
        for (String key : keys) {
            long value = longValue(bundle, key, fallback);
            if (value != fallback) return value;
        }
        return fallback;
    }

    private static long firstDistanceMeters(Bundle bundle, String... keys) {
        if (bundle == null || keys == null) return -1L;
        for (String key : keys) {
            if (TextUtils.isEmpty(key) || !bundle.containsKey(key)) continue;
            long meters = distanceTextMeters(bundle.get(key));
            if (meters >= 0L) return meters;
        }
        return -1L;
    }

    private static long distanceTextMeters(Object value) {
        if (value == null) return -1L;
        if (value instanceof Number) return Math.max(0L, ((Number) value).longValue());
        String text = String.valueOf(value).trim();
        if (TextUtils.isEmpty(text)) return -1L;
        Matcher matcher = DISTANCE_NUMBER.matcher(text.replace(',', '.'));
        if (!matcher.find()) return -1L;
        double parsed;
        try {
            parsed = Double.parseDouble(matcher.group().replace(',', '.'));
        } catch (Exception ignored) {
            return -1L;
        }
        if (parsed < 0d) return -1L;
        String lower = text.toLowerCase(Locale.US);
        boolean km = lower.contains("км") || lower.contains("km");
        return Math.round(km ? parsed * 1000d : parsed);
    }

    private static long correctedRemainingMeters(long remainingMeters, long totalMeters) {
        if (remainingMeters < 0L || totalMeters < 0L) return remainingMeters;
        if (totalMeters == 0L) return remainingMeters;
        if (totalMeters < 100L && remainingMeters >= 1000L) return remainingMeters;
        long margin = Math.max(25L, Math.round(totalMeters * 0.05));
        return remainingMeters > totalMeters + margin ? totalMeters : remainingMeters;
    }

    private static long correctedRemainingByGuidance(long remainingMeters, long maneuverMeters) {
        if (remainingMeters < 0L || maneuverMeters < 0L) return remainingMeters;
        if (maneuverMeters <= remainingMeters + ROUTE_REMAINING_MANEUVER_CONFLICT_METERS) {
            return remainingMeters;
        }
        return maneuverMeters;
    }

    private static String metersText(long meters) {
        if (meters < 0) return "";
        if (meters < 1000) return meters + " м";
        double km = meters / 1000.0;
        if (km < 10.0) return String.format(Locale.US, "%.1f км", km);
        return Math.round(km) + " км";
    }

    private static String secondsText(long seconds) {
        if (seconds < 0) return "";
        long minutes = Math.max(1L, Math.round(seconds / 60.0));
        if (minutes < 60) return minutes + " мин";
        long hours = minutes / 60;
        long rest = minutes % 60;
        return rest == 0 ? hours + " ч" : hours + " ч " + rest + " мин";
    }

    private static String lower(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.US);
    }
}
