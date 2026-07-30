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

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.Locale;

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
    private static final long BROADCAST_PROVIDER_SUPPRESS_MS = 5000L;
    private static final long BROADCAST_APPLY_MIN_MS = 200L;
    private static final long FINISH_MAX_REMAINING_METERS = 35L;
    private static final long FINISH_PREVIOUS_REMAINING_METERS = 80L;
    private static final long FINISH_RECENT_ROUTE_MS = 45000L;
    private static final long BACKGROUND_SUSPENSION_ROUTE_EVIDENCE_MS = 45000L;
    private static final long FINISH_MIN_ROUTE_ALIVE_MS = 3000L;
    private static final long ROUTE_REMAINING_MANEUVER_CONFLICT_METERS = 30L;
    private static final long MANEUVER_ROUTE_DELTA_MAX_METERS = 350L;
    private static final long REROUTE_ROUTE_GROWTH_METERS = 25L;
    private static final long MANEUVER_ZERO_METERS = 1L;
    private static YandexCoreBridgeClient shared;

    private static final class SnapshotApplyResult {
        final long delayMs;
        final boolean accepted;

        SnapshotApplyResult(long delayMs, boolean accepted) {
            this.delayMs = delayMs;
            this.accepted = accepted;
        }
    }

    private static final class MainManeuverSnapshot {
        final String mainEvent;
        final String maneuver;
        final String provenance;
        final String distance;
        final String annotationManeuver;
        final String annotationDistance;
        final String notificationManeuver;
        final String notificationDistance;
        final String roundaboutExit;

        MainManeuverSnapshot(String mainEvent, String maneuver, String provenance,
                             String distance, String annotationManeuver,
                             String annotationDistance, String notificationManeuver,
                             String notificationDistance, String roundaboutExit) {
            this.mainEvent = mainEvent;
            this.maneuver = maneuver;
            this.provenance = provenance;
            this.distance = distance;
            this.annotationManeuver = annotationManeuver;
            this.annotationDistance = annotationDistance;
            this.notificationManeuver = notificationManeuver;
            this.notificationDistance = notificationDistance;
            this.roundaboutExit = roundaboutExit;
        }
    }

    private final Context app;
    private HandlerThread thread;
    private Handler handler;
    private boolean running;
    private final YandexSnapshotOrderGuard snapshotOrderGuard = new YandexSnapshotOrderGuard();
    private long lastMissingProviderLogAt;
    private long lastErrorLogAt;
    private String lastState = "";
    private String lastSignature = "";
    private String lastSpeedSignature = "";
    private long lastSpeedSnapshotSentAt;
    private String lastManeuverDistanceKey = "";
    private String lastManeuverProjectionLogKey = "";
    private long lastManeuverProjectionLogAt;
    private long lastManeuverDistanceMeters = -1L;
    private long lastManeuverRouteRemainingMeters = -1L;
    private long lastLiveRouteMetricsAt = -1L;
    private long lastLiveRouteStartedAt = -1L;
    private long lastLiveRouteRemainingMeters = -1L;
    private String lastLiveRouteId = "";
    private long lastBroadcastSnapshotAt = -1L;
    private final Object broadcastLock = new Object();
    private static final int MAX_PENDING_BROADCASTS = 64;
    private final ArrayDeque<Bundle> pendingBroadcastSnapshots = new ArrayDeque<>();
    private boolean broadcastApplyPosted;
    private long lastBroadcastApplyAt = -1L;
    private String lastAppliedCoalescingIdentity = "";
    private String lastBackgroundSuspensionIdentity = "";

    private final Runnable poller = new Runnable() {
        @Override
        public void run() {
            if (!running || handler == null) return;
            long delay = pollOnce();
            if (running && handler != null) handler.postDelayed(this, delay);
        }
    };

    private final Runnable broadcastDrainer = new Runnable() {
        @Override
        public void run() {
            drainBroadcastSnapshot();
        }
    };

    public YandexCoreBridgeClient(Context context) {
        this.app = context.getApplicationContext();
        synchronized (YandexCoreBridgeClient.class) {
            shared = this;
        }
    }

    public static YandexCoreBridgeClient get(Context context) {
        synchronized (YandexCoreBridgeClient.class) {
            if (shared == null) shared = new YandexCoreBridgeClient(context);
            return shared;
        }
    }

    public synchronized void start() {
        if (running) return;
        resetBridgeSessionState();
        running = true;
        thread = new HandlerThread("KiaYandexCoreBridge");
        thread.start();
        handler = new Handler(thread.getLooper());
        snapshotOrderGuard.reset();
        handler.post(poller);
        AppLog.line(app, "Yandex Core Bridge: client started");
    }

    public synchronized void stop() {
        running = false;
        if (handler != null) handler.removeCallbacksAndMessages(null);
        handler = null;
        if (thread != null) thread.quitSafely();
        thread = null;
        snapshotOrderGuard.reset();
        synchronized (broadcastLock) {
            pendingBroadcastSnapshots.clear();
            broadcastApplyPosted = false;
            lastBroadcastApplyAt = -1L;
            lastAppliedCoalescingIdentity = "";
        }
        resetBridgeSessionState();
        AppLog.line(app, "Yandex Core Bridge: client stopped");
    }

    public void acceptBroadcastSnapshot(Bundle input) {
        if (input == null || input.isEmpty()) return;
        if (!sourceIngressAllowed()) return;
        try {
            Bundle snapshot = new Bundle(input);
            normalizeSnapshotEnvelope(snapshot);
            enqueueBroadcastSnapshot(snapshot);
        } catch (Exception e) {
            logError("v2_broadcast", e);
        }
    }

    private void enqueueBroadcastSnapshot(Bundle snapshot) {
        Handler target = handler;
        if (target == null) {
            applyBroadcastSnapshot(snapshot);
            return;
        }
        boolean lifecyclePriority = priorityBroadcastSnapshot(snapshot);
        String incomingIdentity = coalescingIdentity(snapshot);
        synchronized (broadcastLock) {
            Bundle tail = pendingBroadcastSnapshots.peekLast();
            String tailIdentity = coalescingIdentity(tail);
            String referenceIdentity = tail == null
                    ? lastAppliedCoalescingIdentity : tailIdentity;
            boolean semanticTransition = YandexSnapshotCoalescingPolicy.isTransition(
                    referenceIdentity, incomingIdentity);
            if (lifecyclePriority) {
                // Keep producer order intact and retain queued route/micro
                // transitions until the order guard has validated this packet.
                if (!makePendingBroadcastRoom(snapshot, true, semanticTransition)) return;
                pendingBroadcastSnapshots.addLast(snapshot);
            } else {
                if (tail != null && YandexSnapshotCoalescingPolicy.canReplaceTail(
                        priorityBroadcastSnapshot(tail),
                        tailIdentity, incomingIdentity)) {
                    pendingBroadcastSnapshots.removeLast();
                }
                if (!makePendingBroadcastRoom(snapshot, false, semanticTransition)) return;
                pendingBroadcastSnapshots.addLast(snapshot);
            }
            if (lifecyclePriority || semanticTransition) {
                broadcastApplyPosted = true;
                target.removeCallbacks(broadcastDrainer);
                target.post(broadcastDrainer);
                return;
            }
            if (broadcastApplyPosted) return;
            broadcastApplyPosted = true;
            long delay = broadcastApplyDelayMs();
            target.postDelayed(broadcastDrainer, delay);
        }
    }

    private boolean makePendingBroadcastRoom(Bundle incoming,
                                             boolean incomingLifecyclePriority,
                                             boolean incomingSemanticTransition) {
        if (pendingBroadcastSnapshots.size() < MAX_PENDING_BROADCASTS) return true;
        boolean[] lifecycleFlags = new boolean[pendingBroadcastSnapshots.size()];
        int flagIndex = 0;
        for (Bundle queued : pendingBroadcastSnapshots) {
            lifecycleFlags[flagIndex++] = priorityBroadcastSnapshot(queued);
        }
        int evictionIndex = YandexSnapshotCoalescingPolicy.evictionIndex(
                incomingLifecyclePriority, incomingSemanticTransition, lifecycleFlags);
        if (evictionIndex < 0) {
            AppLog.navigation(app, "Yandex Core Bridge queue overflow; retained lifecycle/"
                    + "transition history, dropped ordinary incoming seq="
                    + longValue(incoming, "seq", -1L));
            return false;
        }
        Iterator<Bundle> iterator = pendingBroadcastSnapshots.iterator();
        Bundle evicted = null;
        for (int i = 0; iterator.hasNext(); i++) {
            Bundle candidate = iterator.next();
            if (i != evictionIndex) continue;
            evicted = candidate;
            iterator.remove();
            break;
        }
        AppLog.navigation(app, "Yandex Core Bridge queue overflow; preserved incoming "
                + (incomingLifecyclePriority ? "lifecycle" : "transition")
                + " seq=" + longValue(incoming, "seq", -1L)
                + " evictedSeq=" + longValue(evicted, "seq", -1L));
        return true;
    }

    private void drainBroadcastSnapshot() {
        Bundle snapshot;
        synchronized (broadcastLock) {
            snapshot = pendingBroadcastSnapshots.pollFirst();
            broadcastApplyPosted = false;
        }
        if (snapshot == null) return;

        applyBroadcastSnapshot(snapshot);

        Handler target = handler;
        if (target == null) return;
        synchronized (broadcastLock) {
            if (pendingBroadcastSnapshots.isEmpty() || broadcastApplyPosted) return;
            broadcastApplyPosted = true;
            Bundle next = pendingBroadcastSnapshots.peekFirst();
            boolean transition = next != null
                    && YandexSnapshotCoalescingPolicy.isTransition(
                    lastAppliedCoalescingIdentity, coalescingIdentity(next));
            if (transition || priorityBroadcastSnapshot(next)) {
                target.post(broadcastDrainer);
            } else {
                target.postDelayed(broadcastDrainer, broadcastApplyDelayMs());
            }
        }
    }

    private long broadcastApplyDelayMs() {
        if (lastBroadcastApplyAt <= 0L) return 0L;
        long elapsed = SystemClock.elapsedRealtime() - lastBroadcastApplyAt;
        return Math.max(0L, BROADCAST_APPLY_MIN_MS - elapsed);
    }

    private void applyBroadcastSnapshot(Bundle snapshot) {
        try {
            lastBroadcastApplyAt = SystemClock.elapsedRealtime();
            SnapshotApplyResult result = applySnapshot(snapshot);
            if (result.accepted) {
                lastBroadcastSnapshotAt = SystemClock.elapsedRealtime();
            }
            AppLog.line(app, "Yandex Core Bridge v2 broadcast "
                    + (result.accepted ? "accepted" : "ignored") + " state="
                    + firstString(snapshot, "state", "status")
                    + " seq=" + longValue(snapshot, "seq", -1L)
                    + " nextPoll=" + result.delayMs);
        } catch (Exception e) {
            logError("v2_broadcast_apply", e);
        }
    }

    private long pollOnce() {
        if (!sourceIngressAllowed()) {
            return IDLE_POLL_MS;
        }
        if (lastBroadcastSnapshotAt > 0L
                && SystemClock.elapsedRealtime() - lastBroadcastSnapshotAt
                < BROADCAST_PROVIDER_SUPPRESS_MS) {
            return ACTIVE_POLL_MS;
        }
        if (!providerAvailable()) {
            logMissingProvider();
            return MISSING_PROVIDER_POLL_MS;
        }
        try {
            Bundle snapshot = readSnapshot();
            if (snapshot == null || snapshot.isEmpty()) return IDLE_POLL_MS;
            return applySnapshot(snapshot).delayMs;
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

    private synchronized SnapshotApplyResult applySnapshot(Bundle snapshot) {
        if (!sourceIngressAllowed()) {
            return snapshotResult(IDLE_POLL_MS, false);
        }
        String state = lower(firstString(snapshot, "state", "route_state", "status"));
        if (TextUtils.isEmpty(state)) {
            state = bool(snapshot, "active", false)
                    ? YandexCoreBridgeContract.STATE_ACTIVE : YandexCoreBridgeContract.STATE_OFF;
        }
        String callback = firstString(snapshot, "last_callback", "callback",
                "event_type", "bridge_reason", "reason");
        long seq = longValue(snapshot, "seq", "sequence", -1L);
        long timestampElapsed = longValue(snapshot, "timestamp_elapsed_ms", "elapsed_realtime_ms", -1L);
        long freshness = freshnessMs(snapshot, timestampElapsed);
        boolean routePresentKnown = snapshot != null && snapshot.containsKey("route_present");
        boolean routePresent = bool(snapshot, "route_present", false);
        long nowElapsed = SystemClock.elapsedRealtime();
        boolean recentLiveRoute = lastLiveRouteMetricsAt > 0L
                && nowElapsed - lastLiveRouteMetricsAt
                <= BACKGROUND_SUSPENSION_ROUTE_EVIDENCE_MS;
        if (YandexBridgeLifecyclePolicy.shouldPreserveActiveRoute(
                state, callback, recentLiveRoute, hasRouteMetrics(snapshot),
                routePresentKnown, routePresent)) {
            String suspensionIdentity = lower(callback) + "|" + seq + "|"
                    + timestampElapsed + "|"
                    + firstString(snapshot, "route_id", "routeId");
            if (!suspensionIdentity.equals(lastBackgroundSuspensionIdentity)) {
                lastBackgroundSuspensionIdentity = suspensionIdentity;
                NavigationFeature.get(app).onYandexBackgroundGuidanceSuspended(
                        callback, routePresentKnown && routePresent);
                AppLog.line(app, "Yandex Core Bridge: held route during background suspension"
                        + " callback=" + callback
                        + " routePresent="
                        + (routePresentKnown ? String.valueOf(routePresent) : "unknown")
                        + " freshness=" + freshness);
            }
            return snapshotResult(ACTIVE_POLL_MS, true);
        }
        lastBackgroundSuspensionIdentity = "";
        boolean routeLive = routeLive(state);
        if (routeLive && freshness > MAX_ACTIVE_FRESHNESS_MS) {
            AppLog.line(app, "Yandex Core Bridge: stale snapshot ignored freshness=" + freshness);
            return snapshotResult(ACTIVE_POLL_MS, false);
        }
        YandexSnapshotOrderGuard.Result orderResult = snapshotOrderGuard.evaluate(
                firstString(snapshot, "route_id", "routeId"), seq, timestampElapsed);
        if (orderResult != YandexSnapshotOrderGuard.Result.ACCEPT
                && orderResult != YandexSnapshotOrderGuard.Result.ACCEPT_UNTRUSTED_INITIAL) {
            AppLog.line(app, "Yandex Core Bridge: out-of-order snapshot ignored"
                    + " reason=" + orderResult
                    + " state=" + state
                    + " seq=" + seq
                    + " timestamp=" + timestampElapsed);
            return snapshotResult(routeLive(state) ? ACTIVE_POLL_MS : IDLE_POLL_MS, false);
        }
        if (orderResult == YandexSnapshotOrderGuard.Result.ACCEPT_UNTRUSTED_INITIAL) {
            AppLog.line(app, "Yandex Core Bridge: accepted initial untrusted envelope"
                    + " state=" + state);
        }
        rememberAppliedCoalescingIdentity(snapshot);
        if (YandexCoreBridgeContract.STATE_ACTIVE.equals(state)) {
            resetManeuverGuardForRouteChange(snapshot);
            normalizeActiveSnapshot(snapshot, freshness);
            rememberLiveRoute(snapshot);
        } else if (!routeLive) {
            resetManeuverDistanceGuard();
        }
        String signature = snapshotSignature(state, snapshot);
        sendSpeedSnapshot(snapshot, freshness);
        if (YandexCoreBridgeContract.STATE_OFF.equals(state)
                && YandexCoreBridgeContract.STATE_OFF.equals(lastState)) {
            lastSignature = signature;
            return snapshotResult(IDLE_POLL_MS, true);
        }
        if (state.equals(lastState) && signature.equals(lastSignature)) {
            if (routeLive) {
                boolean replay = NavigationFeature.get(app)
                        .touchYandexCoreBridgeHeartbeat(hasRouteMetrics(snapshot));
                if (replay) sendActiveSnapshot(freshness, snapshot);
            }
            return snapshotResult(routeLive ? ACTIVE_POLL_MS : IDLE_POLL_MS, true);
        }
        if (YandexCoreBridgeContract.STATE_FINISHED.equals(state)
                && !finishedSnapshotConfirmed(snapshot, freshness)) {
            lastSignature = "";
            long remaining = routeRemainingMeters(snapshot);
            AppLog.line(app, "Yandex Core Bridge: ignored unconfirmed finished state"
                    + " remaining=" + remaining
                    + " lastRemaining=" + lastLiveRouteRemainingMeters
                    + " activeAge=" + liveRouteAgeMs()
                    + " freshness=" + freshness);
            if (hasRouteMetrics(snapshot)) {
                normalizeActiveSnapshot(snapshot, freshness);
                sendActiveSnapshot(freshness, snapshot);
            } else {
                sendLoading("finished_unconfirmed", freshness, snapshot);
            }
            return snapshotResult(ACTIVE_POLL_MS, true);
        }
        lastState = state;
        lastSignature = signature;

        if (YandexCoreBridgeContract.STATE_OFF.equals(state)) {
            sendOff("off", freshness, snapshot);
            return snapshotResult(IDLE_POLL_MS, true);
        }
        if (YandexCoreBridgeContract.STATE_FINISHED.equals(state)) {
            sendFinished(freshness, snapshot);
            return snapshotResult(IDLE_POLL_MS, true);
        }
        if (YandexCoreBridgeContract.STATE_LOADING.equals(state)
                || YandexCoreBridgeContract.STATE_REROUTING.equals(state)) {
            sendLoading(state, freshness, snapshot);
            return snapshotResult(ACTIVE_POLL_MS, true);
        }
        sendActiveSnapshot(freshness, snapshot);
        return snapshotResult(ACTIVE_POLL_MS, true);
    }

    private boolean sourceIngressAllowed() {
        return NavigationSourcePolicy.ingressAllowed(
                AppSettings.navigationEnabled(app),
                AppSettings.navSourceMode(app),
                NavigationSourcePolicy.SOURCE_YANDEX);
    }

    private static SnapshotApplyResult snapshotResult(long delayMs, boolean accepted) {
        return new SnapshotApplyResult(delayMs, accepted);
    }

    private void rememberAppliedCoalescingIdentity(Bundle snapshot) {
        synchronized (broadcastLock) {
            lastAppliedCoalescingIdentity = coalescingIdentity(snapshot);
        }
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

        MainManeuverSnapshot selected = selectMainManeuver(snapshot);
        String mainEvent = selected.mainEvent;
        String maneuver = selected.maneuver;
        String maneuverProvenance = selected.provenance;
        String maneuverDistance = selected.distance;
        String annotationManeuver = selected.annotationManeuver;
        String annotationDistance = selected.annotationDistance;
        String notificationManeuver = selected.notificationManeuver;
        String notificationDistance = selected.notificationDistance;
        String selectedRoundaboutExit = selected.roundaboutExit;
        boolean semanticMicroPresent = hasSemanticMicroSignal(snapshot);
        boolean mainDistanceExplicitlyUnknown =
                YandexSnapshotSemantics.distanceMeters(maneuverDistance) <= 0L
                        && semanticMicroPresent;
        if (TextUtils.isEmpty(maneuverDistance) && semanticMicroPresent) {
            maneuverDistance = "0 м";
        }
        if (earlyFinishManeuver(maneuver, snapshot)) {
            AppLog.line(app, "Yandex Core Bridge: suppressed early finish maneuver"
                    + " remaining=" + routeRemainingMeters(snapshot)
                    + " maneuver=" + maneuverMeters(snapshot));
        } else if (YandexSnapshotSemantics.shouldEmitManeuver(
                maneuver, maneuverDistance, semanticMicroPresent)) {
            Intent man = baseIntent(NavigationFeature.KIA_ACTION_MANEUVER, snapshot, freshness);
            man.putExtra("imageId", maneuver);
            man.putExtra("maneuver", maneuver);
            man.putExtra("direction", maneuver);
            man.putExtra("distance", maneuverDistance);
            man.putExtra("maneuver_provenance", maneuverProvenance);
            if (mainDistanceExplicitlyUnknown) {
                man.putExtra("main_distance_explicitly_unknown", true);
            }
            putString(man, "annotation_maneuver", annotationManeuver);
            putString(man, "annotation_maneuver_distance", annotationDistance);
            putString(man, "notification_maneuver", notificationManeuver);
            putString(man, "notification_maneuver_distance", notificationDistance);
            putString(man, "maneuver_text", firstString(snapshot, "maneuver_text", "voice_hint"));
            putString(man, "voice_hint", firstString(snapshot, "voice_hint", "maneuver_text"));
            putString(man, "street_after_maneuver", firstString(snapshot,
                    "street_after_maneuver", "next_street", "next_turn_street", "maneuver_toponym"));
            putString(man, "main_event", mainEvent);
            putString(man, "voice_main_event", firstString(snapshot, "voice_main_event"));
            putString(man, "voice_prompt", firstString(snapshot, "voice_prompt"));
            putString(man, "exit_number", selectedRoundaboutExit);
            putString(man, "roundabout_exit", selectedRoundaboutExit);
            if (isRoundaboutManeuverValue(maneuver)) {
                man.putExtra("roundabout_scope", "current");
                man.putExtra("roundabout_provenance", maneuverProvenance);
            }
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

        sendEventSnapshot(snapshot, freshness);

        Intent eta = baseIntent(NavigationFeature.KIA_ACTION_ETA, snapshot, freshness);
        putRoute(eta, snapshot);
        NavigationFeature.get(app).handle(eta);
    }

    private void sendEventSnapshot(Bundle snapshot, long freshness) {
        String event = firstString(snapshot, "first_event_text", "event", "main_event",
                "voice_main_event", "route_event");
        if (TextUtils.isEmpty(event)) return;
        Intent out = baseIntent(NavigationFeature.KIA_ACTION_MANEUVER, snapshot, freshness);
        putString(out, "event", event);
        putString(out, "first_event_text", event);
        putString(out, "first_event_type", firstString(snapshot, "first_event_type", "event_type"));
        putString(out, "camera_speed_limit", firstString(snapshot,
                "camera_speed_limit", "first_camera_speed_limit_kmh"));
        NavigationFeature.get(app).handle(out);
    }

    private boolean sendSpeedSnapshot(Bundle snapshot, long freshness) {
        long speed = longValue(snapshot, "current_speed_kmh", "speed_kmh", -1L);
        long limit = longValue(snapshot,
                "speed_limit_kmh", "speed_limit", "road_speed_limit", -1L);
        boolean hasRoadLimit = containsAnyKey(snapshot,
                "speed_limit_kmh", "speed_limit", "road_speed_limit");
        boolean hasExceeded = snapshot != null
                && (snapshot.containsKey("speed_exceeded") || snapshot.containsKey("exceeded"));
        if (speed < 0 && limit <= 0 && !hasExceeded && !hasRoadLimit) return false;

        boolean exceeded = bool(snapshot, "speed_exceeded", bool(snapshot, "exceeded", false));
        String signature = speed + "|" + limit + "|" + hasRoadLimit + "|"
                + (hasExceeded ? String.valueOf(exceeded) : "-");
        long now = SystemClock.elapsedRealtime();
        if (signature.equals(lastSpeedSignature)
                && now - lastSpeedSnapshotSentAt < SPEED_SNAPSHOT_MIN_MS) {
            return false;
        }
        lastSpeedSignature = signature;
        lastSpeedSnapshotSentAt = now;

        Intent speedIntent = baseIntent(NavigationFeature.KIA_ACTION_SPEED, snapshot, freshness);
        if (speed >= 0) speedIntent.putExtra("current_speed", String.valueOf(speed));
        speedIntent.putExtra("road_speed_limit_present", hasRoadLimit);
        if (limit > 0) {
            speedIntent.putExtra("speed_limit", String.valueOf(limit));
            speedIntent.putExtra("road_speed_limit", String.valueOf(limit));
            speedIntent.putExtra("speed_limit_source", "guide");
        } else if (hasRoadLimit) {
            speedIntent.putExtra("speed_limit_clear", true);
        }
        putString(speedIntent, "camera_speed_limit", firstString(snapshot,
                "camera_speed_limit", "first_camera_speed_limit_kmh"));
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
        intent.putExtra("bridge_full_snapshot", true);
        boolean hasMicro = !YandexSnapshotSemantics.MICRO_ABSENT_IDENTITY.equals(
                microStateIdentity(snapshot));
        intent.putExtra("micro_snapshot_present", hasMicro);
        intent.putExtra("micro_clear", !hasMicro);
        putString(intent, "bridge_state", firstString(snapshot, "state", "route_state", "status"));
        putString(intent, "bridge_original_state", firstString(snapshot, "bridge_original_state"));
        putString(intent, "last_callback", firstString(snapshot, "last_callback", "callback",
                "event_type", "bridge_reason", "reason"));
        if (bool(snapshot, "background_guidance_suspended", false)) {
            intent.putExtra("background_guidance_suspended", true);
        }
        putBooleanIfPresent(intent, snapshot, "route_present");
        putBooleanIfPresent(intent, snapshot, "process_alive");
        putBooleanIfPresent(intent, snapshot, "guidance_resumed");
        putString(intent, "main_event", firstString(snapshot, "main_event", "voice_main_event", "route_event"));
        putString(intent, "voice_main_event", firstString(snapshot, "voice_main_event"));
        putString(intent, "voice_prompt", firstString(snapshot, "voice_prompt"));
        putString(intent, "route_id", firstString(snapshot, "route_id", "routeId"));
        putRoutePose(intent, snapshot);
        long seq = longValue(snapshot, "seq", "sequence", -1L);
        if (seq >= 0) intent.putExtra("seq", seq);
        return intent;
    }

    private void putRoutePose(Intent intent, Bundle snapshot) {
        putString(intent, "current_point", firstString(snapshot,
                "current_point", "currentPoint",
                "vehicle_point", "vehiclePoint",
                "car_point", "carPoint",
                "position_point", "positionPoint",
                "route_position_point", "routePositionPoint",
                "location_point", "locationPoint",
                "gps_point", "gpsPoint"));
        putString(intent, "current_lat", firstString(snapshot,
                "current_lat", "currentLat",
                "vehicle_lat", "vehicleLat",
                "car_lat", "carLat",
                "position_lat", "positionLat",
                "route_position_lat", "routePositionLat",
                "location_lat", "locationLat",
                "gps_lat", "gpsLat",
                "latitude"));
        putString(intent, "current_lon", firstString(snapshot,
                "current_lon", "currentLon", "current_lng", "currentLng",
                "vehicle_lon", "vehicleLon", "vehicle_lng", "vehicleLng",
                "car_lon", "carLon", "car_lng", "carLng",
                "position_lon", "positionLon", "position_lng", "positionLng",
                "route_position_lon", "routePositionLon",
                "route_position_lng", "routePositionLng",
                "location_lon", "locationLon", "location_lng", "locationLng",
                "gps_lon", "gpsLon", "gps_lng", "gpsLng",
                "longitude"));
        putString(intent, "yandex_heading", firstString(snapshot,
                "yandex_heading", "yandexHeading",
                "vehicle_heading", "vehicleHeading",
                "car_heading", "carHeading",
                "map_heading", "mapHeading",
                "navigation_heading", "navigationHeading",
                "route_position_heading", "routePositionHeading",
                "location_heading", "locationHeading",
                "location_bearing", "locationBearing",
                "gps_bearing", "gpsBearing",
                "bearing", "heading", "azimuth", "course"));
    }

    private void putRoute(Intent intent, Bundle snapshot) {
        long remainingMeters = routeRemainingMeters(snapshot);
        long totalMeters = routeTotalMeters(snapshot);
        MainManeuverSnapshot selectedMain = selectMainManeuver(snapshot);
        long maneuverMeters = YandexSnapshotSemantics.distanceMeters(selectedMain.distance);
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
                    "eta_time_left", "etime");
        }
        String arrival = firstString(snapshot, "arrival_time", "arrival", "eta", "atime");
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
        putString(intent, "etime", eta);
        putString(intent, "arrival_time", arrival);
        putString(intent, "atime", arrival);
        putString(intent, "finish_point", firstString(snapshot, "finish_point", "finishPoint"));
        putString(intent, "finish_street", firstString(snapshot,
                "finish_street", "finishStreet", "finish_address", "destination_name"));
        putString(intent, "current_street", firstString(snapshot, "current_street", "currentStreet"));
        putString(intent, "next_street", firstString(snapshot, "next_street", "nextStreet"));
        putString(intent, "street_after_maneuver", firstString(snapshot,
                "street_after_maneuver", "next_turn_street", "maneuver_toponym"));
        putString(intent, "main_event", firstString(snapshot, "main_event", "voice_main_event",
                "route_event"));
        putString(intent, "voice_prompt", firstString(snapshot, "voice_prompt"));
        if (isRoundaboutManeuverValue(selectedMain.maneuver)) {
            putString(intent, "exit_number", selectedMain.roundaboutExit);
            putString(intent, "roundabout_exit", selectedMain.roundaboutExit);
            intent.putExtra("roundabout_scope", "current");
        }
        putManeuverDistance(intent, snapshot);
        putLaneExtras(intent, snapshot);
    }

    private void putManeuverDistance(Intent intent, Bundle snapshot) {
        MainManeuverSnapshot selected = selectMainManeuver(snapshot);
        putString(intent, "current_maneuver", selected.maneuver);
        putString(intent, "maneuver_provenance", selected.provenance);
        putString(intent, "maneuver_distance_identity", selected.maneuver);
        putString(intent, "maneuver_distance_provenance", selected.provenance);
        long meters = YandexSnapshotSemantics.distanceMeters(selected.distance);
        if (meters <= 0L) return;
        String distance = selected.distance;
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
                "micro_distance_meters", "lane_sign_distance_meters",
                "upcoming_lane_distance_meters", "laneDistanceMeters", "microDistanceMeters");
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
        String laneItems = firstString(snapshot, "ignored_lane_items", "lane_items",
                "lane_topology");
        String rawLaneItems = firstString(snapshot, "ignored_raw_lane_items", "raw_lane_items",
                "lane_topology", "lane_topology_json");
        String routeDirections = firstString(snapshot, "route_road_options", "road_options",
                "gray_road_options", "available_directions");
        String grayDirections = firstString(snapshot, "gray_road_options", "route_road_options",
                "road_options");
        String highlight = firstString(snapshot,
                "lane_highlighted_direction", "highlighted_direction",
                "highlighted_directions", "lane_highlight",
                "recommended_lanes", "ignored_recommended_lanes",
                "micro_maneuver", "lane_maneuver", "ignored_lane_maneuver");
        boolean staleMainConflict = staleLaneGuidanceConflictsWithMain(snapshot, highlight,
                laneDistance, laneMeters);
        boolean positionedMicro = laneMeters > 0L
                || YandexSnapshotSemantics.distanceMeters(laneDistance) > 0L;
        if ((!positionedMicro
                && laneGuidanceConflicts(routeDirections, first(rawLaneItems, laneItems, highlight)))
                || staleMainConflict) {
            if (staleMainConflict) {
                AppLog.line(app, "Yandex Core Bridge: ignored stale lane topology main="
                        + firstString(snapshot, "direction", "maneuver", "imageId", "route_action")
                        + " highlight=" + lower(highlight)
                        + " route=" + lower(routeDirections));
            }
            laneItems = "";
            rawLaneItems = "";
            routeDirections = "";
            grayDirections = "";
            highlight = "";
            clearLaneExtras(intent);
        }
        String allowedDirections = first(routeDirections, firstString(snapshot,
                "ignored_allowed_directions", "allowed_directions"));
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
        putString(intent, "lane_highlighted_direction", highlight);
        putString(intent, "highlighted_direction", highlight);
        putString(intent, "highlighted_directions", highlight);
        putString(intent, "lane_maneuver", highlight);
        putString(intent, "lane_topology", firstString(snapshot,
                "lane_topology", "raw_lane_topology"));
        putString(intent, "lane_topology_json", firstString(snapshot, "lane_topology_json"));
        putString(intent, "lane_sign_position", firstString(snapshot,
                "lane_sign_position", "lane_position", "upcoming_lane_position"));
        putString(intent, "lane_count", firstString(snapshot, "lane_count", "route_lane_count"));
        putString(intent, "road_scheme_raw", firstString(snapshot,
                "road_scheme_raw", "direction_sign_items", "raw_direction_sign_items"));
        putString(intent, "direction_sign_items", firstString(snapshot,
                "direction_sign_items", "raw_direction_sign_items"));
        putString(intent, "route_road_options", routeDirections);
        putString(intent, "gray_road_options", grayDirections);
        putString(intent, "upcoming_lane_signs", firstString(snapshot, "upcoming_lane_signs"));
        putString(intent, "upcoming_direction_signs", firstString(snapshot, "upcoming_direction_signs"));
        putString(intent, "upcoming_road_events", firstString(snapshot, "upcoming_road_events"));
        putLaneDirectionFlags(intent, first(rawLaneItems, laneItems, allowedDirections));
        if (bool(snapshot, "lane_guidance", false)
                || !TextUtils.isEmpty(laneDistance)
                || !TextUtils.isEmpty(first(rawLaneItems, laneItems, allowedDirections, highlight))) {
            intent.putExtra("lane_guidance", true);
        }
    }

    private void normalizeActiveSnapshot(Bundle snapshot, long freshness) {
        if (snapshot == null || snapshot.isEmpty()) return;
        long remainingMeters = routeRemainingMeters(snapshot);
        long totalMeters = routeTotalMeters(snapshot);
        remainingMeters = correctedRemainingMeters(remainingMeters, totalMeters);
        MainManeuverSnapshot selectedMain = selectMainManeuver(snapshot);
        long maneuverMeters = YandexSnapshotSemantics.distanceMeters(selectedMain.distance);
        long travelDelta = travelDeltaMeters(snapshot, freshness);
        if (travelDelta > 0L) {
            if (remainingMeters >= 0L) remainingMeters = Math.max(0L, remainingMeters - travelDelta);
            if (maneuverMeters >= 0L) maneuverMeters = Math.max(0L, maneuverMeters - travelDelta);
        }
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

        String maneuverKey = mainManeuverDistanceIdentity(snapshot, selectedMain);
        boolean semanticMicroPresent = hasSemanticMicroSignal(snapshot);
        long correctedManeuver = correctedManeuverMeters(maneuverKey,
                maneuverMeters, remainingMeters, semanticMicroPresent);
        putMeters(snapshot, correctedManeuver,
                YandexMainDistanceContinuityPolicy.synchronizedDistanceMeterKeys(
                        selectedMain.provenance));
    }

    private long correctedManeuverMeters(String maneuverIdentity, long meters,
                                         long routeRemainingMeters,
                                         boolean semanticMicroPresent) {
        if (meters < 0L || TextUtils.isEmpty(maneuverIdentity)) {
            resetManeuverDistanceGuard();
            return meters;
        }
        String key = lower(maneuverIdentity);
        if (!key.equals(lastManeuverDistanceKey) || lastManeuverDistanceMeters < 0L) {
            rememberManeuverDistance(key, meters, routeRemainingMeters);
            return meters;
        }
        boolean routeRemainingKnown = routeRemainingMeters >= 0L
                && lastManeuverRouteRemainingMeters >= 0L;
        long routeDelta = routeRemainingKnown
                ? lastManeuverRouteRemainingMeters - routeRemainingMeters : 0L;
        boolean routeGrew = routeRemainingKnown
                && routeRemainingMeters > lastManeuverRouteRemainingMeters + REROUTE_ROUTE_GROWTH_METERS;
        if (routeGrew) {
            long previousRouteRemaining = lastManeuverRouteRemainingMeters;
            rememberManeuverDistance(key, meters, routeRemainingMeters);
            AppLog.line(app, "Yandex Core Bridge: maneuver guard reset by route growth"
                    + " lastRoute=" + previousRouteRemaining
                    + " route=" + routeRemainingMeters
                    + " meters=" + meters);
            return meters;
        }
        YandexMainDistanceContinuityPolicy.ZeroContinuity zeroContinuity =
                YandexMainDistanceContinuityPolicy.resolveTransientZero(
                lastManeuverDistanceKey, key,
                lastManeuverDistanceMeters, meters,
                lastManeuverRouteRemainingMeters, routeRemainingMeters,
                semanticMicroPresent, MANEUVER_ROUTE_DELTA_MAX_METERS,
                REROUTE_ROUTE_GROWTH_METERS, MANEUVER_ZERO_METERS);
        if (zeroContinuity.handled) {
            long previousManeuverMeters = lastManeuverDistanceMeters;
            rememberManeuverDistance(key, zeroContinuity.stateMeters,
                    zeroContinuity.stateRouteRemaining);
            logManeuverProjection(key, zeroContinuity.mode,
                    previousManeuverMeters, meters,
                    zeroContinuity.stateMeters, zeroContinuity.outputMeters,
                    routeRemainingMeters);
            return zeroContinuity.outputMeters;
        }
        if (lastManeuverDistanceMeters <= MANEUVER_ZERO_METERS && meters > MANEUVER_ZERO_METERS) {
            rememberManeuverDistance(key, meters, routeRemainingMeters);
            AppLog.line(app, "Yandex Core Bridge: maneuver distance recovered from zero"
                    + " meters=" + meters
                    + " route=" + routeRemainingMeters);
            return meters;
        }
        boolean routeDidNotGrow = routeRemainingKnown
                && routeRemainingMeters <= lastManeuverRouteRemainingMeters + 10L;
        boolean noisyBackstep = routeDidNotGrow && meters > lastManeuverDistanceMeters + 5L;
        long jumpThreshold = Math.max(80L, Math.round(lastManeuverDistanceMeters * 0.45));
        boolean largeUnknownJump = !routeRemainingKnown
                && meters > lastManeuverDistanceMeters + jumpThreshold;
        if (noisyBackstep || largeUnknownJump) {
            long corrected = lastManeuverDistanceMeters;
            if (routeDelta > 0L && routeDelta <= MANEUVER_ROUTE_DELTA_MAX_METERS) {
                corrected = Math.max(0L, lastManeuverDistanceMeters - routeDelta);
                rememberManeuverDistance(key, corrected, routeRemainingMeters);
            } else {
                lastManeuverRouteRemainingMeters = routeRemainingMeters;
            }
            return corrected;
        }
        if (routeDelta > 0L && routeDelta <= MANEUVER_ROUTE_DELTA_MAX_METERS
                && lastManeuverDistanceMeters > 0L) {
            long projected = Math.max(0L, lastManeuverDistanceMeters - routeDelta);
            if (projected < meters) {
                rememberManeuverDistance(key, projected, routeRemainingMeters);
                return projected;
            }
        }
        rememberManeuverDistance(key, meters, routeRemainingMeters);
        return meters;
    }

    private static String mainManeuverDistanceIdentity(Bundle snapshot,
                                                       MainManeuverSnapshot selected) {
        if (snapshot == null || selected == null || TextUtils.isEmpty(selected.maneuver)) {
            return "";
        }
        String routeId = firstString(snapshot, "route_id", "routeId",
                "route_uuid", "routeUuid");
        if (TextUtils.isEmpty(routeId)) return "";
        return YandexMainDistanceContinuityPolicy.identity(
                routeId, selected.provenance, selected.maneuver);
    }

    private void rememberManeuverDistance(String key, long meters, long routeRemainingMeters) {
        lastManeuverDistanceKey = key;
        lastManeuverDistanceMeters = meters;
        lastManeuverRouteRemainingMeters = routeRemainingMeters;
    }

    private void logManeuverProjection(String identity, String mode,
                                       long previousMeters, long incomingMeters,
                                       long stateMeters, long outputMeters,
                                       long routeRemainingMeters) {
        long now = SystemClock.elapsedRealtime();
        String key = lower(identity) + "|" + lower(mode);
        if (key.equals(lastManeuverProjectionLogKey)
                && now - lastManeuverProjectionLogAt < 5000L) {
            return;
        }
        lastManeuverProjectionLogKey = key;
        lastManeuverProjectionLogAt = now;
        AppLog.line(app, "Yandex Core Bridge: maneuver continuity"
                + " mode=" + (mode == null ? "" : mode.trim())
                + " previous=" + previousMeters
                + " incoming=" + incomingMeters
                + " state=" + stateMeters
                + " tx=" + (outputMeters <= MANEUVER_ZERO_METERS
                ? "hold" : String.valueOf(outputMeters))
                + " route=" + routeRemainingMeters);
    }

    private void resetDistanceGuards() {
        resetManeuverDistanceGuard();
    }

    private void resetBridgeSessionState() {
        resetRouteGuards();
        lastState = "";
        lastSignature = "";
        lastSpeedSignature = "";
        lastSpeedSnapshotSentAt = 0L;
        lastBroadcastSnapshotAt = -1L;
    }

    private void resetRouteGuards() {
        resetManeuverDistanceGuard();
        resetLiveRouteGuard();
    }

    private void resetManeuverDistanceGuard() {
        lastManeuverDistanceKey = "";
        lastManeuverProjectionLogKey = "";
        lastManeuverProjectionLogAt = 0L;
        lastManeuverDistanceMeters = -1L;
        lastManeuverRouteRemainingMeters = -1L;
    }

    private void resetManeuverGuardForRouteChange(Bundle snapshot) {
        if (snapshot == null) return;
        String routeId = firstString(snapshot, "route_id", "routeId");
        String marker = lower(firstString(snapshot, "last_callback", "callback",
                "event_type", "bridge_reason", "reason"));
        boolean rerouting = marker.contains("rerout") || marker.contains("перестро");
        boolean routeChanged = !TextUtils.isEmpty(routeId)
                && !TextUtils.isEmpty(lastLiveRouteId)
                && !routeId.equals(lastLiveRouteId);
        if (!rerouting && !routeChanged) return;
        if (!TextUtils.isEmpty(lastManeuverDistanceKey)) {
            AppLog.line(app, "Yandex Core Bridge: maneuver guard reset by "
                    + (routeChanged ? "route_id" : "reroute")
                    + " lastRouteId=" + lastLiveRouteId
                    + " routeId=" + routeId);
        }
        resetManeuverDistanceGuard();
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

    private static boolean priorityBroadcastSnapshot(Bundle snapshot) {
        String state = lower(firstString(snapshot, "state", "route_state", "status"));
        if (YandexCoreBridgeContract.STATE_OFF.equals(state)
                || YandexCoreBridgeContract.STATE_LOADING.equals(state)
                || YandexCoreBridgeContract.STATE_REROUTING.equals(state)
                || YandexCoreBridgeContract.STATE_FINISHED.equals(state)) {
            return true;
        }
        if (snapshot != null && snapshot.containsKey("active") && !bool(snapshot, "active", true)) {
            return true;
        }
        if (snapshot != null && snapshot.containsKey("navi_on")
                && !bool(snapshot, "navi_on", true)) {
            return true;
        }
        String marker = lower(firstString(snapshot, "last_callback", "callback",
                "event_type", "bridge_reason", "reason"));
        return marker.contains("finish")
                || marker.contains("off")
                || marker.contains("remove")
                || marker.contains("task_removed")
                || marker.contains("loading")
                || marker.contains("rerout");
    }

    private static long travelDeltaMeters(Bundle snapshot, long freshness) {
        if (snapshot == null || freshness <= 300L) return 0L;
        long speed = longValue(snapshot, "current_speed_kmh", "speed_kmh", -1L);
        if (speed <= 0L || speed > 220L) return 0L;
        long cappedFreshness = Math.min(freshness, MAX_ACTIVE_FRESHNESS_MS);
        return Math.min(120L, Math.round(speed * cappedFreshness / 3600.0));
    }

    private static void normalizeSnapshotEnvelope(Bundle snapshot) {
        if (snapshot == null) return;
        if (!snapshot.containsKey("source")) {
            snapshot.putString("source", YandexCoreBridgeContract.SOURCE);
        }
        if (!snapshot.containsKey("state") && snapshot.containsKey("status")) {
            snapshot.putString("state", String.valueOf(snapshot.get("status")));
        }
        if (!snapshot.containsKey("route_state") && snapshot.containsKey("state")) {
            snapshot.putString("route_state", String.valueOf(snapshot.get("state")));
        }
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
                "eta_time_left", "etime");
        String arrival = firstString(snapshot, "arrival_time", "arrival", "eta", "atime");
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
        return YandexSnapshotSemantics.isEnvelopeKey(key);
    }

    private static boolean hasMicroEnvelope(Bundle snapshot) {
        if (snapshot == null || snapshot.isEmpty()) return false;
        for (String key : snapshot.keySet()) {
            if (YandexSnapshotSemantics.isMicroKey(key)) return true;
        }
        return false;
    }

    private static String microIdentity(Bundle snapshot) {
        if (!hasMicroEnvelope(snapshot)) return "";
        return YandexSnapshotSemantics.microIdentity(true,
                firstString(snapshot,
                        "lane_maneuver", "micro_maneuver", "ignored_lane_maneuver"),
                firstString(snapshot,
                        "lane_highlight", "lane_highlighted_direction",
                        "highlighted_direction", "highlighted_directions",
                        "recommended_lanes", "ignored_recommended_lanes"),
                firstString(snapshot,
                        "lane_topology_json", "lane_topology",
                        "raw_lane_topology", "lane_items", "raw_lane_items"),
                firstString(snapshot,
                        "lane_sign_position", "lane_position", "upcoming_lane_position"),
                firstString(snapshot,
                        "direction_sign_items", "raw_direction_sign_items",
                        "road_scheme_raw", "upcoming_direction_signs",
                        "upcoming_lane_signs"),
                firstString(snapshot,
                        "route_road_options", "gray_road_options", "road_options"));
    }

    private static String microStateIdentity(Bundle snapshot) {
        if (snapshot == null) return YandexSnapshotSemantics.MICRO_ABSENT_IDENTITY;
        if (!hasSemanticMicroSignal(snapshot)) {
            return YandexSnapshotSemantics.MICRO_ABSENT_IDENTITY;
        }
        String identity = microIdentity(snapshot);
        if (TextUtils.isEmpty(identity) || "micro|none".equals(identity)) {
            return "micro|distance_only";
        }
        return identity;
    }

    private static String coalescingIdentity(Bundle snapshot) {
        if (snapshot == null) return "";
        String state = lower(firstString(snapshot, "state", "route_state", "status"));
        if (TextUtils.isEmpty(state)) {
            state = bool(snapshot, "active", false)
                    ? YandexCoreBridgeContract.STATE_ACTIVE : YandexCoreBridgeContract.STATE_OFF;
        }
        MainManeuverSnapshot selected = selectMainManeuver(snapshot);
        return YandexSnapshotSemantics.coalescingIdentity(
                state,
                firstString(snapshot, "route_id", "routeId"),
                selected.maneuver,
                selected.provenance,
                selected.mainEvent,
                selected.roundaboutExit,
                firstString(snapshot, "route_action", "routeAction"),
                firstString(snapshot, "road_options", "route_road_options",
                        "gray_road_options", "available_directions"),
                microStateIdentity(snapshot));
    }

    private static boolean hasSemanticMicroSignal(Bundle snapshot) {
        if (snapshot == null || snapshot.isEmpty()) return false;
        long meters = firstLong(snapshot, -1L,
                "lane_distance_meters", "micro_distance_meters",
                "lane_sign_distance_meters", "upcoming_lane_distance_meters");
        if (meters > 0L) return true;
        String distance = firstString(snapshot,
                "lane_distance", "micro_distance", "lane_sign_distance",
                "upcoming_lane_distance");
        if (YandexSnapshotSemantics.distanceMeters(distance) > 0L) return true;
        return !TextUtils.isEmpty(firstString(snapshot,
                "lane_maneuver", "micro_maneuver", "ignored_lane_maneuver",
                "lane_highlight", "lane_highlighted_direction",
                "highlighted_direction", "highlighted_directions",
                "recommended_lanes", "ignored_recommended_lanes",
                "lane_topology_json", "lane_topology", "raw_lane_topology",
                "lane_items", "raw_lane_items",
                "direction_sign_items", "raw_direction_sign_items",
                "road_scheme_raw", "upcoming_direction_signs",
                "upcoming_lane_signs"));
    }

    private static String candidateDistance(Bundle snapshot, String... prefixes) {
        if (snapshot == null || prefixes == null) return "";
        for (String prefix : prefixes) {
            long meters = firstLong(snapshot, -1L,
                    prefix + "_maneuver_distance_meters",
                    prefix + "_distance_to_maneuver_meters",
                    prefix + "_distance_meters");
            String numeric = metersText(meters);
            if (!TextUtils.isEmpty(numeric)) return numeric;
            String text = firstString(snapshot,
                    prefix + "_maneuver_distance",
                    prefix + "_distance_to_maneuver",
                    prefix + "_distance");
            if (!TextUtils.isEmpty(text)) return text;
        }
        return "";
    }

    private static MainManeuverSnapshot selectMainManeuver(Bundle snapshot) {
        String mainEvent = firstString(snapshot,
                "main_event", "voice_main_event", "route_event",
                "first_event_text", "event");
        String eventManeuver = clusterManeuverFromEvent(mainEvent);
        String annotationManeuver = firstString(snapshot,
                "annotation_maneuver", "guide_maneuver",
                "displayed_annotation_maneuver", "current_annotation_maneuver");
        String rawManeuver = firstString(snapshot,
                "maneuver", "maneuver_type", "maneuver_action",
                "current_maneuver", "image_id", "imageId", "direction");
        String notificationManeuver = firstString(snapshot,
                "notification_maneuver", "notification_maneuver_action",
                "notification_direction");
        String voiceManeuver = maneuverFromVoiceText(firstString(snapshot,
                "maneuver_text", "voice_hint", "voice_prompt"));
        String maneuver = first(annotationManeuver, rawManeuver, eventManeuver,
                notificationManeuver, voiceManeuver);
        String provenance = !TextUtils.isEmpty(annotationManeuver) ? "annotation"
                : !TextUtils.isEmpty(rawManeuver) ? "bridge_main"
                : !TextUtils.isEmpty(eventManeuver) ? "route_event"
                : !TextUtils.isEmpty(notificationManeuver) ? "notification"
                : "voice";
        String annotationDistance = candidateDistance(snapshot, "annotation", "guide",
                "displayed_annotation", "current_annotation");
        String notificationDistance = candidateDistance(snapshot, "notification");
        String genericDistance = maneuverDistance(snapshot);
        String distance = YandexMainDistanceContinuityPolicy.selectDistanceForProvenance(
                provenance, sameManeuverValue(annotationManeuver, rawManeuver),
                annotationDistance, notificationDistance, genericDistance);
        if (TextUtils.isEmpty(distance) && !TextUtils.isEmpty(eventManeuver)) {
            distance = "0 м";
        }
        return new MainManeuverSnapshot(mainEvent, maneuver, provenance, distance,
                annotationManeuver, annotationDistance,
                notificationManeuver, notificationDistance,
                selectedRoundaboutExit(snapshot, provenance));
    }

    private static boolean isRoundaboutManeuverValue(String value) {
        String text = lower(value);
        return text.contains("roundabout") || text.contains("circular")
                || text.contains("круг") || text.contains("кольц");
    }

    private static boolean sameManeuverValue(String left, String right) {
        String first = lower(left).replace('-', '_');
        String second = lower(right).replace('-', '_');
        return !TextUtils.isEmpty(first) && first.equals(second);
    }

    private static String selectedRoundaboutExit(Bundle snapshot, String provenance) {
        String annotationExit = firstString(snapshot,
                "annotation_roundabout_exit", "annotation_roundabout_exit_number",
                "annotation_exit_number",
                "guide_roundabout_exit", "guide_roundabout_exit_number",
                "guide_exit_number");
        String notificationExit = firstString(snapshot,
                "notification_roundabout_exit", "notification_roundabout_exit_number",
                "notification_exit_number");
        String genericExit = firstString(snapshot,
                "roundabout_exit", "roundabout_exit_number",
                "maneuver_exit_number", "exit_number");
        return YandexSnapshotSemantics.roundaboutExitForProvenance(
                provenance, annotationExit, notificationExit, genericExit);
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

    private static boolean staleLaneGuidanceConflictsWithMain(Bundle snapshot, String highlight,
                                                              String laneDistance,
                                                              long laneMeters) {
        if (!TextUtils.isEmpty(metersText(laneMeters)) || !TextUtils.isEmpty(laneDistance)) {
            return false;
        }
        String main = firstString(snapshot, "direction", "maneuver", "imageId", "route_action",
                "maneuver_text", "voice_hint");
        if (TextUtils.isEmpty(main) || TextUtils.isEmpty(highlight)) return false;
        boolean mainLeft = directionLeft(main);
        boolean mainRight = directionRight(main);
        boolean highlightLeft = directionLeft(highlight);
        boolean highlightRight = directionRight(highlight);
        if (mainLeft && highlightRight && !highlightLeft) return true;
        return mainRight && highlightLeft && !highlightRight;
    }

    private static void clearLaneExtras(Intent intent) {
        if (intent == null) return;
        String[] keys = {
                "ignored_lane_items", "ignored_raw_lane_items", "ignored_allowed_directions",
                "lane_items", "raw_lane_items", "allowed_directions",
                "ignored_recommended_lanes", "ignored_lane_maneuver", "recommended_lanes",
                "lane_highlight", "lane_highlighted_direction",
                "highlighted_direction", "highlighted_directions",
                "lane_maneuver", "lane_topology", "lane_topology_json", "road_scheme_raw",
                "direction_sign_items", "route_road_options", "gray_road_options"
        };
        for (String key : keys) {
            intent.removeExtra(key);
        }
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

    private static String maneuverFromVoiceText(String value) {
        String text = lower(value).replace('-', '_');
        if (TextUtils.isEmpty(text)) return "";
        if (text.contains("камер") || text.contains("контроль")
                || text.contains("скорост") || text.contains("speed")) {
            return "";
        }
        if (text.contains("разворот") || text.contains("разверн")
                || text.contains("u_turn") || text.contains("uturn")) {
            if (text.contains("прав")) return "context_ra_turn_back_right";
            return "context_ra_turn_back_left";
        }
        if (text.contains("круг") || text.contains("кольц")
                || text.contains("roundabout") || text.contains("circular")) {
            return "context_ra_in_circular_movement";
        }
        if (text.contains("съезд") || text.contains("выезд") || text.contains("exit")
                || text.contains("ramp")) {
            if (text.contains("лев") || text.contains("left")) return "context_ra_exit_left";
            if (text.contains("прав") || text.contains("right")) return "context_ra_exit_right";
        }
        if (text.contains("держ") || text.contains("keep")) {
            if (text.contains("лев") || text.contains("left")) return "context_ra_exit_left";
            if (text.contains("прав") || text.contains("right")) return "context_ra_exit_right";
        }
        if (text.contains("налево") || text.contains("левее")
                || text.contains("turn left") || text.contains("left")) {
            return "context_ra_turn_left";
        }
        if (text.contains("направо") || text.contains("правее")
                || text.contains("turn right") || text.contains("right")) {
            return "context_ra_turn_right";
        }
        if (text.contains("прямо") || text.contains("straight")
                || text.contains("forward") || text.contains("ahead")) {
            return "context_ra_forward";
        }
        return "";
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

    private static boolean containsAnyKey(Bundle bundle, String... keys) {
        if (bundle == null || keys == null) return false;
        for (String key : keys) {
            if (!TextUtils.isEmpty(key) && bundle.containsKey(key)) return true;
        }
        return false;
    }

    private static void putBooleanIfPresent(Intent intent, Bundle bundle, String key) {
        if (intent == null || bundle == null || TextUtils.isEmpty(key)
                || !bundle.containsKey(key)) {
            return;
        }
        intent.putExtra(key, bool(bundle, key, false));
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
        return YandexSnapshotSemantics.distanceMeters(String.valueOf(value));
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
