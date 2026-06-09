package kia.app.navigation.domain;

import android.content.Context;
import android.content.Intent;
import android.location.Address;
import android.location.Geocoder;
import android.location.Location;
import android.os.Handler;
import android.os.Bundle;
import android.os.Looper;
import android.text.TextUtils;
import android.util.Log;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import kia.app.core.AppLog;
import kia.app.core.StateStore;
import kia.app.core.model.NavigationState;
import kia.app.navigation.cluster.NavigationClusterSender;
import kia.app.navigation.capture.YandexCoreBridgeContract;
import kia.app.core.settings.AppSettings;

public final class NavigationFeature {
    private static final String TAG = "KiaNav";

    public static final String ACTION_MANEUVER = "kia.app.ACTION_MANEUVER_DATA";
    public static final String ACTION_ETA = "kia.app.ACTION_ETA_DATA";
    public static final String ACTION_NAVI_ON = "kia.app.ACTION_NAVI_ON_DATA";
    public static final String ACTION_SPEED = "kia.app.ACTION_SPEED_DATA";
    public static final String ACTION_EXCEEDED = "kia.app.ACTION_EXCEEDED_DATA";
    public static final String KIA_ACTION_MANEUVER = "com.kia.navi.ACTION_MANEUVER_DATA";
    public static final String KIA_ACTION_ETA = "com.kia.navi.ACTION_ETA_DATA";
    public static final String KIA_ACTION_NAVI_ON = "com.kia.navi.ACTION_NAVI_ON_DATA";
    public static final String KIA_ACTION_SPEED = "com.kia.navi.ACTION_SPEED_DATA";
    public static final String KIA_ACTION_EXCEEDED = "com.kia.navi.ACTION_EXCEEDED_DATA";

    private static final Pattern NUMBER = Pattern.compile("[-+]?\\d+(?:[\\.,]\\d+)?");
    private static final long ROUTE_WAIT_MAX_MS = 5000L;
    private static final long ROUTE_WAIT_MIN_MS = 3000L;
    private static final long ROUTE_REROUTING_HOLD_MS = 4000L;
    private static final long ROUTE_STOP_CONFIRM_MS = 12000L;
    private static final long FINISH_HOLD_MS = 5000L;
    private static final long FINISH_STALE_AUTO_MS = 12000L;
    private static final long OVERSPEED_TEXT_MS = 5000L;
    private static final long SPEED_LIMIT_TEXT_MS = 5000L;
    private static final long MANEUVER_TEXT_RESEND_DELAY_MS = 50L;
    private static final long ROUNDABOUT_EXIT_HOLD_MS = 45000L;
    private static final long ROAD_OPTION_LEARN_MS = 180000L;
    private static final long GPS_SPEED_MIN_INTERVAL_MS = 900L;
    private static final long GPS_SPEED_LOG_MS = 5000L;
    private static final long YANDEX_CURRENT_SPEED_HOLD_MS = 3500L;
    private static final long YANDEX_ROAD_SPEED_LIMIT_HOLD_MS = 12000L;
    private static final long LANE_HINT_OVERLAY_HOLD_MS = 120000L;
    private static final long EVENT_HINT_HOLD_MS = 45000L;
    private static final long FINISH_DIRECTION_ANIMATION_STEP_MS = 120L;
    private static final long LANE_TX_POST_PASS_PRE_TX_MAX_AGE_MS = 3000L;
    private static final long BACKGROUND_RESEND_MIN_MS = 2000L;
    private static final float FINISH_STALE_RECOVERY_MARGIN_METERS = 2f;
    private static final float AUTO_FINISH_ROUTE_METERS = 35f;
    private static final float AUTO_FINISH_GPS_METERS = 10f;
    private static final float DASHBOARD_FINISH_METERS = 10f;
    private static final float NEW_ROUTE_AFTER_FINISH_METERS = 50f;
    private static final float FINISH_CONFLICT_MANEUVER_METERS = 30f;
    private static final float SUSPICIOUS_FINISH_POINT_ROUTE_METERS = 80f;
    private static final float SUSPICIOUS_FINISH_POINT_GPS_METERS = 30f;
    private static final int FINISH_CONFLICT_MINUTES = 2;
    private static final float DISTANCE_DISPLAY_STEP_METERS = 10f;
    private static final float MANEUVER_PROGRESS_MIN_BASE_METERS = 500f;
    private static final float MANEUVER_PROGRESS_ZERO_METERS = 50f;
    private static final float MAIN_MANEUVER_MICRO_SEPARATION_METERS = 250f;
    private static final float MICRO_POST_PASS_NEAR_MAIN_CLEAR_METERS = 150f;
    private static final float INFERRED_FORWARD_MICRO_MAX_METERS = 450f;
    private static final int MICRO_TX_PROGRESS_BUCKET = 9;
    private static final long FINISH_COMPASS_SUPPRESS_MS = 0L;
    private static final float DGIS_MICRO_DISTANCE_METERS = 160f;
    private static final long YANDEX_WATCHDOG_STALE_MS = 25000L;

    private static NavigationFeature instance;

    private final Context app;
    private final NavigationClusterSender sender;
    private final NavigationClusterTxController clusterTx;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private NavigationState state = NavigationState.empty();
    private long lastDirectManeuverAt;
    private long lastRouteFinishEtaAt;
    private long routeStartedAt;
    private long overspeedTextUntil;
    private long speedLimitTextUntil;
    private long finishHoldUntil;
    private long laneGuidanceUntil;
    private int overspeedGeneration;
    private int finishGeneration;
    private int maneuverTextGeneration;
    private int pendingInactiveGeneration;
    private int routeLoadingFallbackGeneration;
    private int routeReroutingGeneration;
    private int microRestoreGeneration;
    private int yandexWatchdogGeneration;
    private int lastSentSpeedLimit = -1;
    private float maneuverProgressBaseMeters;
    private float maneuverProgressLastMeters;
    private String maneuverProgressKey = "";
    private String lastSentEtaKey = "";
    private String lastSentEtaTimeKey = "";
    private String lastSentNavigationText = "";
    private String activeManeuverHintText = "";
    private String lastSentFinishDirectionKey = "";
    private String lastFinishDirectionReason = "";
    private String staleFinishStreetAfterPointChange = "";
    private String pendingInactiveSource = "";
    private String lastRoundaboutExitManeuver = "";
    private String lastRoundaboutExitText = "";
    private String activeLaneHint = "";
    private String activeLaneSource = "";
    private String activeEventHint = "";
    private String activeEventSource = "";
    private String activeLaneRaw = "";
    private String activeLanePosition = "";
    private String activeRoadSchemeRaw = "";
    private String activeUpcomingRaw = "";
    private String activeGrayRoadId = "";
    private String activeGrayRoadKey = "";
    private String activeCompleteLaneGrayRoad = "";
    private String activeMicroManeuver = "";
    private String activeMicroDistance = "";
    private String activeMicroDebugDistance = "";
    private String activeMicroStatus = "";
    private String activeMicroSource = "";
    private boolean activeMicroTxAllowed = true;
    private long activeMicroPostPassUntil;
    private String activeLaneDistance = "";
    private String lastLaneTxManeuver = "";
    private String lastLaneTxGrayRoad = "";
    private String lastLaneTxDistance = "";
    private int lastLaneTxProgressBucket = 9;
    private long lastLaneTxAt;
    private long laneTxPostPassUntil;
    private String activeClusterVisual = "";
    private String learnedRoadKey = "";
    private int learnedRoadMask;
    private long learnedRoadUntil;
    private int lastGpsCurrentSpeed = -1;
    private long lastGpsCurrentSpeedAt;
    private long lastGpsSpeedLogAt;
    private long lastYandexCurrentSpeedAt;
    private long lastYandexRoadSpeedLimitAt;
    private long lastYandexNavigationPacketAt;
    private long lastFinishDirectionAt;
    private long lastRoundaboutExitUntil;
    private long lastRouteGuidanceAt;
    private long lastRouteMetricsAt;
    private long routeLoadingMinUntil;
    private long routeReroutingUntil;
    private long lastBackgroundResendAt;
    private long laneHintUntil;
    private long eventHintUntil;
    private long navigationRawUntil;
    private long grayRoadUntil;
    private long completeLaneGrayUntil;
    private long laneDistanceUntil;
    private long finishCompassSuppressUntil;
    private String activeRouteTotalDistance = "";
    private String activeRouteId = "";
    private long microHintUntil;
    private long maneuverTextUntil;
    private boolean yandexFinishSuppressed;
    private double lastGpsLatitude = Double.NaN;
    private double lastGpsLongitude = Double.NaN;
    private float lastGpsBearing = Float.NaN;
    private long lastGpsBearingAt;
    private float lastGpsSpeedMetersPerSecond = Float.NaN;
    private float lastDeviceHeading = Float.NaN;
    private float lastDeviceHeadingAccuracy = Float.NaN;
    private long lastDeviceHeadingAt;
    private double finishLatitude = Double.NaN;
    private double finishLongitude = Double.NaN;
    private String finishGeocodeKey = "";
    private long lastFinishGeocodeAt;
    private int finishGeocodeGeneration;
    private String currentGeocodeKey = "";
    private long lastCurrentGeocodeAt;
    private int currentGeocodeGeneration;
    private boolean waitingForRoute;
    private int displayedFinishDirectionStep = -1;
    private int targetFinishDirectionStep = -1;
    private float activeFinishDirectionDistance;
    private boolean activeFinishDirectionKm;
    private boolean finishDirectionAnimationRunning;

    private final Runnable finishDirectionAnimator = new Runnable() {
        @Override
        public void run() {
            synchronized (NavigationFeature.this) {
                finishDirectionAnimationRunning = false;
                if (!finishDirectionAnimationAllowed()) return;
                if (displayedFinishDirectionStep == targetFinishDirectionStep) return;
                int next = nextCompassStep(displayedFinishDirectionStep, targetFinishDirectionStep);
                sendFinishDirectionStep(next, targetFinishDirectionStep,
                        activeFinishDirectionDistance, activeFinishDirectionKm,
                        first(lastFinishDirectionReason, "finish_direction_animation"),
                        false);
                scheduleFinishDirectionAnimation();
            }
        }
    };

    private NavigationFeature(Context context) {
        this.app = context.getApplicationContext();
        this.sender = new NavigationClusterSender(app);
        this.clusterTx = new NavigationClusterTxController(sender, this::applyClusterVisual);
        syncRouteStateFromStore();
    }

    public static synchronized NavigationFeature get(Context context) {
        if (instance == null) instance = new NavigationFeature(context);
        return instance;
    }

    public synchronized boolean active() {
        return state.active;
    }

    public synchronized boolean finishCompassSuppressed() {
        return System.currentTimeMillis() < finishCompassSuppressUntil;
    }

    public synchronized int finishDirectionOverlayStep() {
        if (!NavigationModeSettings.isFinishDirection(app) || !state.active || state.finishReached) {
            return Integer.MIN_VALUE;
        }
        float heading = finishReferenceHeading();
        if (!finishPointKnown() || !gpsPointKnown() || Float.isNaN(heading)) {
            return Integer.MIN_VALUE;
        }
        return compassDirectionStep(bearingToFinish() - heading);
    }

    public synchronized boolean finishDirectionHeadingNeeded() {
        return state.active && !state.finishReached && finishPointKnown() && gpsPointKnown()
                && (NavigationModeSettings.isFinishDirection(app) || state.loading() || waitingForRoute);
    }

    public synchronized void updateGpsSpeed(float metersPerSecond) {
        if (Float.isNaN(metersPerSecond) || Float.isInfinite(metersPerSecond) || metersPerSecond < 0f) {
            return;
        }
        int kmh = Math.max(0, Math.round(metersPerSecond * 3.6f));
        long now = System.currentTimeMillis();
        if (yandexCurrentSpeedFresh(now)) {
            lastGpsCurrentSpeed = kmh;
            lastGpsCurrentSpeedAt = now;
            if (now - lastGpsSpeedLogAt >= GPS_SPEED_LOG_MS) {
                lastGpsSpeedLogAt = now;
                AppLog.line(app, "Navigation GPS speed ignored; Yandex speed is active");
            }
            return;
        }
        if (kmh == lastGpsCurrentSpeed && now - lastGpsCurrentSpeedAt < GPS_SPEED_MIN_INTERVAL_MS) {
            return;
        }

        boolean exceeded = state.speedExceeded;
        int limit = speedNumber(state.speedLimit);
        if (limit > 0) {
            exceeded = kmh > limit;
        }
        boolean exceededChanged = exceeded != state.speedExceeded;
        if (exceeded) startOverspeedTextWindow();
        else clearOverspeedTextWindow();

        String source = stableSourceForSpeed("gps_speed");
        state = new NavigationState(state.active, state.finishReached, exceeded,
                state.maneuver, state.maneuverText, state.maneuverDistance,
                state.routeDistance, state.routeTime, state.arrivalTime,
                state.currentStreet, state.nextStreet, state.finishStreet,
                state.speedLimit, String.valueOf(kmh), source, now);
        publishNavigationState();

        if (now - lastGpsSpeedLogAt >= GPS_SPEED_LOG_MS
                || Math.abs(kmh - lastGpsCurrentSpeed) >= 5) {
            lastGpsSpeedLogAt = now;
            AppLog.line(app, "Navigation GPS speed: " + kmh);
        }
        lastGpsCurrentSpeed = kmh;
        lastGpsCurrentSpeedAt = now;
        if (exceededChanged) sendConfiguredText();
    }

    public synchronized void updateGpsLocation(Location location) {
        if (location == null) return;
        long now = System.currentTimeMillis();
        lastGpsLatitude = location.getLatitude();
        lastGpsLongitude = location.getLongitude();
        if (location.hasBearing()) {
            lastGpsBearing = normalizeDegrees(location.getBearing());
            lastGpsBearingAt = now;
        }
        if (location.hasSpeed()) {
            lastGpsSpeedMetersPerSecond = Math.max(0f, location.getSpeed());
            updateGpsSpeed(location.getSpeed());
        }
        maybeRecoverStaleFinishFromMovement();
        if (maybeAutoFinishStaleNearDestination(now)) return;
        if (routeLoadingFallbackReady() && TextUtils.isEmpty(state.maneuver)) {
            if (sendDirectionToFinishForLoading(false)) return;
        }
        sendDirectionToFinishIfNeeded(false);
    }

    public synchronized void updateDeviceHeading(float headingDegrees, float accuracyDegrees, String source) {
        if (Float.isNaN(headingDegrees) || Float.isInfinite(headingDegrees)) return;
        long now = System.currentTimeMillis();
        float cleanHeading = normalizeDegrees(headingDegrees);
        if (!Float.isNaN(lastDeviceHeading) && now - lastDeviceHeadingAt <= 2500L) {
            float alpha = accuracyDegrees > 35f ? 0.22f : 0.38f;
            cleanHeading = blendDegrees(lastDeviceHeading, cleanHeading, alpha);
            if (Math.abs(normalizeSignedDegrees(cleanHeading - lastDeviceHeading)) < 0.8f
                    && now - lastDeviceHeadingAt < 250L) {
                return;
            }
        }
        lastDeviceHeading = cleanHeading;
        lastDeviceHeadingAccuracy = accuracyDegrees;
        lastDeviceHeadingAt = now;
        if (routeLoadingFallbackReady() && TextUtils.isEmpty(state.maneuver)) {
            if (sendDirectionToFinishForLoading(false)) return;
        }
        sendDirectionToFinishIfNeeded(false);
    }

    public synchronized void handle(Intent intent) {
        if (intent == null || intent.getAction() == null) return;
        String action = intent.getAction();
        String raw = rawExtras(intent);
        Log.i(TAG, action + " " + raw);
        AppLog.line(app, "Navigation raw: " + shortRaw(action, raw));
        if (!isYandexNavigationIntent(intent)) {
            AppLog.line(app, "Navigation ignored: non-Yandex source " + first(text(intent, "source"), action));
            return;
        }
        if (!AppSettings.yandexNavigationEnabled(app)) {
            AppLog.line(app, "Navigation ignored by source mode "
                    + AppSettings.navSourceLabel(app) + ": yandex");
            return;
        }
        String source = first(text(intent, "source"), action);
        if (!YandexCoreBridgeContract.SOURCE.equals(clean(source))) {
            AppLog.line(app, "Navigation ignored legacy Yandex packet; Core Bridge required: "
                    + clean(source));
            return;
        }
        if (yandexRouteHeartbeatIntent(intent)) {
            touchYandexWatchdog(source);
        }
        rememberNavigationRawDebug(intent, System.currentTimeMillis());
        if (routeReroutingIntent(intent)) {
            if (state.active && !state.finishReached) {
                clearRouteChangeVisualHold("route rerouting " + clean(source));
            }
            startRouteReroutingHold(source);
        }
        if (ACTION_MANEUVER.equals(action) || KIA_ACTION_MANEUVER.equals(action)) {
            handleManeuver(intent);
        } else if (ACTION_ETA.equals(action) || KIA_ACTION_ETA.equals(action)) {
            handleEta(intent);
        } else if (ACTION_NAVI_ON.equals(action) || KIA_ACTION_NAVI_ON.equals(action)) {
            handleActive(intent);
        } else if (ACTION_SPEED.equals(action) || KIA_ACTION_SPEED.equals(action)) {
            handleSpeed(intent);
        } else if (ACTION_EXCEEDED.equals(action) || KIA_ACTION_EXCEEDED.equals(action)) {
            boolean exceeded = bool(intent, "exceeded", false);
            if (exceeded) startOverspeedTextWindow();
            else clearOverspeedTextWindow();
            state = new NavigationState(state.active, state.finishReached, exceeded,
                    state.maneuver, state.maneuverText, state.maneuverDistance,
                    state.routeDistance, state.routeTime, state.arrivalTime,
                    state.currentStreet, state.nextStreet, state.finishStreet,
                    state.speedLimit, state.currentSpeed, first(text(intent, "source"), action),
                    System.currentTimeMillis());
            publishNavigationState();
            sendConfiguredText();
            if (state.active) AppLog.line(app, "Navigation exceeded=" + exceeded);
        }
    }

    public synchronized void handleTeyes(Intent intent) {
        String raw = rawExtras(intent);
        Log.i(TAG, intent.getAction() + " " + raw);
        AppLog.line(app, "Navigation raw: " + shortRaw(intent.getAction(), raw));
        String stateText = text(intent, "state");
        if (isOffState(stateText)) {
            AppLog.line(app, "Navigation: ignored TEYES close state");
            return;
        }
        String direction = first(text(intent, "direction"), text(intent, "maneuver"), "forward");
        String passiveText = passiveRoadEventText(intent, direction);
        if (!TextUtils.isEmpty(passiveText)) {
            state = new NavigationState(state.active, state.finishReached, state.speedExceeded,
                    state.maneuver, state.maneuverText, state.maneuverDistance,
                    state.routeDistance, state.routeTime, state.arrivalTime,
                    state.currentStreet, state.nextStreet, state.finishStreet,
                    state.speedLimit, state.currentSpeed, first(text(intent, "source"), "teyes_passive"),
                    System.currentTimeMillis());
            publishNavigationState();
            if (!state.active) sendNavigationText(passiveText);
            AppLog.line(app, "Navigation passive event: " + passiveText + " | " + clean(direction));
            String limit = first(text(intent, "speed_limit"), text(intent, "speedLimit"), text(intent, "limit"));
            if (!TextUtils.isEmpty(limit)) sendSpeed(limit);
            return;
        }
        String fallbackManeuver = normalizeManeuver(direction, intent.getIntExtra("direction_lr", 0));
        if (state.active && (state.loading() || routeWaiting() || clusterVisualIsLoading())
                && isUsableManeuver(fallbackManeuver)) {
            long now = System.currentTimeMillis();
            waitingForRoute = false;
            routeStartedAt = 0L;
            lastRouteGuidanceAt = now;
            cancelPendingInactive("teyes fallback");
            String fallbackDistance = first(state.maneuverDistance, text(intent, "distance"), text(intent, "dist"));
            state = new NavigationState(true, false, state.speedExceeded,
                    fallbackManeuver, maneuverLabel(fallbackManeuver), fallbackDistance,
                    state.routeDistance, state.routeTime, state.arrivalTime,
                    state.currentStreet, state.nextStreet, state.finishStreet,
                    state.speedLimit, state.currentSpeed, "teyes_route_fallback", now);
            publishNavigationState();
            sendManeuverWithFallbackGray(fallbackManeuver, fallbackDistance,
                    maneuverProgressBucket(fallbackManeuver,
                            first(state.nextStreet, state.currentStreet), fallbackDistance),
                    true);
            sendConfiguredText();
            AppLog.line(app, "Navigation TEYES fallback route event: " + fallbackManeuver
                    + " raw=" + shortRaw(intent.getAction(), raw));
            return;
        }
        AppLog.line(app, "Navigation ignored TEYES route event; Yandex source owns route data: "
                + clean(direction));
    }

    public synchronized void resendForBackgroundDelivery(String reason) {
        long now = System.currentTimeMillis();
        if (now - lastBackgroundResendAt < BACKGROUND_RESEND_MIN_MS) return;
        syncRouteStateFromStore();
        if (!state.active || state.finishReached) return;
        lastBackgroundResendAt = now;
        resetNavigationSendCache();
        sender.sendActive(true);
        resendKnownRouteData();
        resendCurrentVisual();
        AppLog.line(app, "Navigation background resend: " + clean(reason)
                + " | " + state.summary());
    }

    public synchronized void handleDgisNotification(String imageId, String distance, String unit,
                                                    String street, String rawText) {
        if (!AppSettings.dgisNavigationEnabled(app)) {
            AppLog.line(app, "Navigation ignored by source mode "
                    + AppSettings.navSourceLabel(app) + ": 2GIS notification");
            return;
        }
        String maneuver = normalizeManeuver(clean(imageId), 0);
        String normalizedDistance = distanceText(distance, unit);
        String normalizedStreet = normalizeStreetLabel(street);
        if (!isUsableManeuver(maneuver) && TextUtils.isEmpty(normalizedStreet)
                && TextUtils.isEmpty(normalizedDistance)) {
            AppLog.line(app, "Navigation 2GIS ignored: " + clean(rawText));
            return;
        }
        setActive(true, "2gis_notification");
        waitingForRoute = false;
        routeStartedAt = 0L;
        long now = System.currentTimeMillis();
        lastRouteGuidanceAt = now;
        if (!isUsableManeuver(maneuver)) {
            state = new NavigationState(true, false, state.speedExceeded,
                    state.maneuver, state.maneuverText, state.maneuverDistance,
                    state.routeDistance, state.routeTime, state.arrivalTime,
                    state.currentStreet, first(normalizedStreet, state.nextStreet), state.finishStreet,
                    state.speedLimit, state.currentSpeed, "2gis_notification", now);
            publishNavigationState();
            sendConfiguredText();
            AppLog.line(app, "Navigation 2GIS text: " + state.summary() + " raw=" + clean(rawText));
            return;
        }
        boolean finishRequested = isFinishManeuver(maneuver);
        boolean finish = finishRequested && finishAllowedByDistance(normalizedDistance,
                state.routeDistance, state.maneuverDistance);
        if (finishRequested && !finish) {
            AppLog.line(app, "Navigation 2GIS ignored early finish: distance="
                    + first(normalizedDistance, state.routeDistance, "-")
                    + " raw=" + clean(rawText));
            return;
        }
        boolean micro = dgisMicroManeuver(maneuver, rawText, normalizedDistance);
        if (micro && NavigationModeSettings.isTbt(app)) {
            sendConfiguredText();
            AppLog.line(app, "Navigation 2GIS ignored micro in TBT: " + clean(rawText));
            return;
        }
        if (!micro && laneGuidanceUntil > now && !finish) {
            sendConfiguredText();
            AppLog.line(app, "Navigation 2GIS held main maneuver during micro: "
                    + state.summary() + " raw=" + clean(rawText));
            return;
        }
        if (micro) laneGuidanceUntil = now + (AppSettings.navMicroHoldSeconds(app) * 1000L);
        state = new NavigationState(true, finish, state.speedExceeded,
                maneuver, maneuverLabel(maneuver), normalizedDistance,
                state.routeDistance, state.routeTime, state.arrivalTime,
                state.currentStreet, first(normalizedStreet, state.nextStreet), state.finishStreet,
                state.speedLimit, state.currentSpeed, "2gis_notification", now);
        publishNavigationState();
        if (!micro) startManeuverTextHint(maneuver, "");
        sendManeuverIfChanged(maneuver, distanceValue(normalizedDistance), isKm(normalizedDistance),
                micro ? 9 : maneuverProgressBucket(maneuver,
                        first(normalizedStreet, state.nextStreet), normalizedDistance),
                micro);
        if (finish) startFinishHold();
        else sendConfiguredText();
        AppLog.line(app, (micro ? "Navigation 2GIS micro maneuver: " : "Navigation 2GIS maneuver: ")
                + state.summary() + " raw=" + clean(rawText));
    }

    public synchronized void handleDgisNotificationRemoved(String packageName) {
        if (!AppSettings.dgisNavigationEnabled(app)) return;
        String source = clean(state.source).toLowerCase(Locale.US);
        if (!source.contains("2gis")) return;
        setActive(false, "2gis_notification_removed");
        AppLog.line(app, "Navigation 2GIS notification removed: " + clean(packageName));
    }

    public synchronized void handleDgisDashboard(String activeMode, String maneuverIcon,
                                                 String maneuverDescription,
                                                 String maneuverDistance, String totalDistance,
                                                 String remainingTime, String arrivalTime,
                                                 String speedLimit, boolean exceeded,
                                                 String rawJson) {
        handleDgisDashboard(activeMode, maneuverIcon, maneuverDescription, maneuverDistance,
                totalDistance, remainingTime, arrivalTime, speedLimit, exceeded,
                Double.NaN, Double.NaN, rawJson);
    }

    public synchronized void handleDgisDashboard(String activeMode, String maneuverIcon,
                                                 String maneuverDescription,
                                                 String maneuverDistance, String totalDistance,
                                                 String remainingTime, String arrivalTime,
                                                 String speedLimit, boolean exceeded,
                                                 double destinationLat, double destinationLon,
                                                 String rawJson) {
        if (!AppSettings.dgisNavigationEnabled(app)) {
            AppLog.line(app, "Navigation ignored by source mode "
                    + AppSettings.navSourceLabel(app) + ": 2GIS dashboard");
            return;
        }
        rememberFinishPoint(destinationLat, destinationLon, "2gis_dashboard");
        String mode = clean(activeMode).toLowerCase(Locale.US);
        String routeDistance = dashboardDistance(totalDistance);
        String routeTime = clean(remainingTime);
        String arrival = clean(arrivalTime);
        String distance = dashboardDistance(maneuverDistance);
        boolean finish = dashboardFinishReached(maneuverIcon, routeDistance, distance);
        String maneuver = finish ? "context_ra_finish" : dashboardManeuver(maneuverIcon, maneuverDescription);
        boolean routeData = !TextUtils.isEmpty(routeDistance) || !TextUtils.isEmpty(routeTime)
                || !TextUtils.isEmpty(arrival) || !TextUtils.isEmpty(distance)
                || isUsableManeuver(maneuver);
        boolean routeMode = !TextUtils.isEmpty(mode) && !"unknown".equals(mode)
                && !"freeroam".equals(mode);
        if (!TextUtils.isEmpty(speedLimit)) {
            if (exceeded) startOverspeedTextWindow();
            else clearOverspeedTextWindow();
            state = new NavigationState(state.active, state.finishReached, exceeded,
                    state.maneuver, state.maneuverText, state.maneuverDistance,
                    state.routeDistance, state.routeTime, state.arrivalTime,
                    state.currentStreet, state.nextStreet, state.finishStreet,
                    speedLimit, state.currentSpeed, "2gis_dashboard",
                    System.currentTimeMillis());
            publishNavigationState();
            sendSpeed(speedLimit);
        }
        if (!routeMode && !routeData) {
            if (state.source.toLowerCase(Locale.US).contains("2gis") && state.active) {
                forceInactive("2gis_dashboard_idle");
            }
            return;
        }
        setActive(true, "2gis_dashboard");
        rememberFinishPoint(destinationLat, destinationLon, "2gis_dashboard");
        waitingForRoute = false;
        routeStartedAt = 0L;
        long now = System.currentTimeMillis();
        lastRouteGuidanceAt = now;
        cancelPendingInactive("2gis_dashboard");
        if (!TextUtils.isEmpty(routeDistance) || !TextUtils.isEmpty(routeTime)
                || !TextUtils.isEmpty(arrival)) {
            mergeEta(routeDistance, routeTime, first(arrival, arrivalFromRouteTime(routeTime)),
                    "", "", "2gis_dashboard", "", false, false);
        }
        if (!isUsableManeuver(maneuver)) {
            sendConfiguredText();
            AppLog.line(app, "Navigation 2GIS dashboard route: " + state.summary()
                    + " raw=" + shortRaw("2gis_dashboard", rawJson));
            return;
        }
        String nextStreet = streetCandidate(maneuverDescription, state.nextStreet,
                state.currentStreet, state.finishStreet);
        boolean micro = dgisMicroManeuver(maneuver, maneuverDescription + " " + maneuverIcon, distance);
        if (micro && NavigationModeSettings.isTbt(app)) {
            sendConfiguredText();
            AppLog.line(app, "Navigation 2GIS dashboard ignored micro in TBT: "
                    + shortRaw("2gis_dashboard", rawJson));
            return;
        }
        if (!micro && laneGuidanceUntil > now && !finish) {
            sendConfiguredText();
            AppLog.line(app, "Navigation 2GIS dashboard held main maneuver during micro: "
                    + state.summary() + " raw=" + shortRaw("2gis_dashboard", rawJson));
            return;
        }
        if (micro) laneGuidanceUntil = now + (AppSettings.navMicroHoldSeconds(app) * 1000L);
        state = new NavigationState(true, finish, state.speedExceeded,
                maneuver, maneuverLabel(maneuver), distance,
                state.routeDistance, state.routeTime, state.arrivalTime,
                state.currentStreet, first(nextStreet, state.nextStreet), state.finishStreet,
                state.speedLimit, state.currentSpeed, "2gis_dashboard",
                System.currentTimeMillis());
        publishNavigationState();
        if (!micro) startManeuverTextHint(maneuver, "");
        if (!TextUtils.isEmpty(distance)) {
            sendManeuverIfChanged(maneuver, distanceValue(distance), isKm(distance),
                    micro ? 9 : maneuverProgressBucket(maneuver, state.nextStreet, distance),
                    micro);
        }
        if (finish) startFinishHold();
        else sendConfiguredText();
        AppLog.line(app, (micro ? "Navigation 2GIS dashboard micro maneuver: "
                : "Navigation 2GIS dashboard maneuver: ") + state.summary()
                + " raw=" + shortRaw("2gis_dashboard", rawJson));
    }

    public synchronized void handleDgisTrip(String currentRoad, String maneuverRoad,
                                            String destinationName, String destinationAddress,
                                            boolean navigationStarted, String rawJson) {
        handleDgisTrip(currentRoad, maneuverRoad, destinationName, destinationAddress,
                navigationStarted, Double.NaN, Double.NaN, rawJson);
    }

    public synchronized void handleDgisTrip(String currentRoad, String maneuverRoad,
                                            String destinationName, String destinationAddress,
                                            boolean navigationStarted, double destinationLat,
                                            double destinationLon, String rawJson) {
        if (!AppSettings.dgisNavigationEnabled(app)) {
            AppLog.line(app, "Navigation ignored by source mode "
                    + AppSettings.navSourceLabel(app) + ": 2GIS car trip");
            return;
        }
        rememberFinishPoint(destinationLat, destinationLon, "2gis_car_trip");
        String acceptedCurrent = streetCandidate(currentRoad, state.currentStreet, "", "");
        String acceptedNext = streetCandidate(maneuverRoad, state.nextStreet,
                first(acceptedCurrent, state.currentStreet), "");
        String acceptedFinish = finishCandidate(first(destinationAddress, destinationName));
        boolean hasTripText = !TextUtils.isEmpty(acceptedCurrent)
                || !TextUtils.isEmpty(acceptedNext)
                || !TextUtils.isEmpty(acceptedFinish);
        if (!navigationStarted) {
            if (state.active && state.source.toLowerCase(Locale.US).contains("2gis")) {
                forceInactive("2gis_car_trip_stop");
            }
            if (hasTripText) {
                AppLog.line(app, "Navigation 2GIS car trip stale after stop: "
                        + shortRaw("2gis_car_trip", rawJson));
            }
            return;
        }
        if (!state.active) {
            setActive(true, "2gis_car_trip");
        }
        rememberFinishPoint(destinationLat, destinationLon, "2gis_car_trip");
        if (!state.active && !hasTripText) return;
        lastRouteGuidanceAt = System.currentTimeMillis();
        cancelPendingInactive("2gis_car_trip");
        String current = first(acceptedCurrent, state.currentStreet);
        String next = first(acceptedNext, state.nextStreet);
        String finish = first(acceptedFinish, validFinishStreet(state.finishStreet));
        if (current.equals(state.currentStreet) && next.equals(state.nextStreet)
                && finish.equals(state.finishStreet) && state.active == navigationStarted) {
            return;
        }
        state = new NavigationState(navigationStarted || state.active, state.finishReached, state.speedExceeded,
                state.maneuver, state.maneuverText, state.maneuverDistance,
                state.routeDistance, state.routeTime, state.arrivalTime,
                current, next, finish,
                state.speedLimit, state.currentSpeed, "2gis_car_trip",
                System.currentTimeMillis());
        publishNavigationState();
        sendConfiguredText();
        AppLog.line(app, "Navigation 2GIS car trip: " + state.summary()
                + " raw=" + shortRaw("2gis_car_trip", rawJson));
    }

    public synchronized void handleDgisStopped(String source) {
        if (!AppSettings.dgisNavigationEnabled(app)) return;
        if (!state.source.toLowerCase(Locale.US).contains("2gis") && !state.active) return;
        forceInactive(source);
    }

    public synchronized void setActive(boolean active) {
        setActive(active, "manual");
    }

    public synchronized void setActive(boolean active, String source) {
        boolean wasActive = state.active;
        long now = System.currentTimeMillis();
        if (!active) {
            String cleanSource = clean(source);
            cancelPendingInactive("off " + cleanSource);
            if (state.finishReached && finishHoldUntil > now && !"finish_hold".equals(cleanSource)) {
                sendNavigationText("Финиш");
                AppLog.line(app, "Navigation finish hold ignores off: " + cleanSource);
                return;
            }
            cancelFinishHold();
            routeLoadingFallbackGeneration++;
            routeReroutingGeneration++;
            microRestoreGeneration++;
            waitingForRoute = false;
            routeStartedAt = 0L;
            routeReroutingUntil = 0L;
            activeRouteTotalDistance = "";
            activeRouteId = "";
            routeLoadingMinUntil = 0L;
            lastDirectManeuverAt = 0L;
            clearLaneHintHold();
            clearEventHintHold();
            lastRouteGuidanceAt = 0L;
            lastRouteMetricsAt = 0L;
            clearRoundaboutExitHold();
            clearFinishPoint();
            resetManeuverProgress();
            resetNavigationTextCache();
            resetNavigationSendCache();
            state = new NavigationState(false, false, state.speedExceeded,
                    "", "", "", "", "", "",
                    state.currentStreet, "", "", state.speedLimit, state.currentSpeed,
                    cleanSource, now);
        } else if (!wasActive) {
            yandexFinishSuppressed = false;
            cancelPendingInactive("active " + clean(source));
            cancelFinishHold();
            finishCompassSuppressUntil = 0L;
            routeLoadingFallbackGeneration++;
            routeReroutingGeneration++;
            microRestoreGeneration++;
            waitingForRoute = true;
            routeStartedAt = now;
            routeReroutingUntil = 0L;
            activeRouteTotalDistance = "";
            activeRouteId = "";
            routeLoadingMinUntil = now + ROUTE_WAIT_MIN_MS;
            clearLaneHintHold();
            clearEventHintHold();
            lastRouteGuidanceAt = 0L;
            clearRoundaboutExitHold();
            clearFinishPoint();
            resetManeuverProgress();
            resetNavigationTextCache();
            resetNavigationSendCache();
            state = new NavigationState(true, false, state.speedExceeded,
                    "", "", "", "", "", "",
                    state.currentStreet, "", "",
                    state.speedLimit, state.currentSpeed, clean(source),
                    now);
        } else {
            cancelPendingInactive("active " + clean(source));
            state = new NavigationState(true, false, state.speedExceeded,
                    state.maneuver, state.maneuverText, state.maneuverDistance,
                    state.routeDistance, state.routeTime, state.arrivalTime,
                    state.currentStreet, state.nextStreet, state.finishStreet,
                    state.speedLimit, state.currentSpeed, clean(source),
                    now);
        }
        publishNavigationState();
        sender.sendActive(active);
        if (active && !wasActive) {
            sendRouteLoadingVisual();
            scheduleRouteLoadingFallback();
        }
        AppLog.line(app, "Navigation: " + (active ? state.summary() : "off"));
    }

    private void startRouteLoadingWithoutMetrics(String source) {
        String cleanSource = clean(source);
        if (routeReroutingActive(System.currentTimeMillis())) {
            sendRouteReroutingVisual();
            AppLog.line(app, "Navigation loading suppressed by rerouting: " + cleanSource);
            return;
        }
        if (state.active && !state.loading()) {
            forceInactive(cleanSource + "_restart_loading");
        }
        if (!state.active) {
            setActive(true, cleanSource);
            AppLog.line(app, "Navigation loading started without route metrics: " + cleanSource);
        }
    }

    private void handleActive(Intent intent) {
        boolean active = bool(intent, "navi_on", false) || bool(intent, "active", false)
                || bool(intent, "is_active", false);
        String source = first(text(intent, "source"), ACTION_NAVI_ON);
        if (!active) {
            if (unconfirmedCoreBridgeFinish(intent, source)) {
                return;
            }
            if (preConfirmRouteStopClick(source)) {
                AppLog.line(app, "Navigation ignored pre-confirm route stop click: " + clean(source));
                return;
            }
            if (confirmedRouteStop(intent, source)) {
                AppLog.line(app, "Navigation confirmed route stop: " + clean(source));
                if (routeStopLooksLikeFinish(intent, source)) {
                    markFinishReached("route_stop_finish_" + clean(source));
                    return;
                }
                forceInactive(source);
                return;
            }
            requestInactiveAfterConfirm(source);
            return;
        }

        String currentStreet = first(text(intent, "current_street"), text(intent, "currentStreet"),
                text(intent, "current_road"), text(intent, "currentRoad"), text(intent, "current_road_name"),
                text(intent, "currentRoadName"), text(intent, "current_street_name"),
                text(intent, "currentStreetName"), text(intent, "position"));
        String nextStreet = first(text(intent, "next_street"), text(intent, "nextStreet"),
                text(intent, "street_after_maneuver"), text(intent, "streetAfterManeuver"),
                text(intent, "next_road"), text(intent, "nextRoad"), text(intent, "next_road_name"),
                text(intent, "nextRoadName"), text(intent, "next_street_name"), text(intent, "nextStreetName"),
                text(intent, "next_turn_street"), text(intent, "nextTurnStreet"));
        String finishStreet = finishTextFromIntent(intent);
        String routeDistance = routeDistanceFromIntent(intent);
        String routeTime = routeTimeFromIntent(intent);
        String arrivalTime = arrivalFromIntent(intent);
        String routeTotalDistance = first(metersDistanceText(first(text(intent, "route_total_meters"),
                        text(intent, "total_route_meters"), text(intent, "route_total_distance_meters"),
                        text(intent, "route_total_initial_meters"))),
                text(intent, "route_total_len"), text(intent, "route_full_len"),
                text(intent, "route_total_distance"), text(intent, "full_route_distance"),
                text(intent, "routeTotalDistance"), text(intent, "totalRouteDistance"));
        String activeManeuver = first(text(intent, "imageId"), text(intent, "image_id"),
                text(intent, "direction"), text(intent, "maneuver"), text(intent, "maneuver_action"),
                text(intent, "current_maneuver"),
                text(intent, "maneuver_type"));
        String rawActiveManeuverDistance = explicitManeuverDistanceFromIntent(intent);
        boolean routeIdChanged = incomingRouteIdChangesActiveRoute(intent);
        if (routeIdChanged) {
            startRouteReroutingHold(clean(source) + "_route_id_pending", true);
        }
        String activeManeuverDistance = mainManeuverDistanceFromIntent(intent, source, routeIdChanged);
        boolean clearCoreBridgeManeuver = YandexCoreBridgeContract.SOURCE.equals(clean(source))
                && TextUtils.isEmpty(activeManeuver)
                && TextUtils.isEmpty(rawActiveManeuverDistance)
                && !laneGuidanceDistancePacket(intent, source);
        long now = System.currentTimeMillis();
        boolean incomingRouteMetrics = hasConfirmedRouteMetrics(routeDistance, routeTime, arrivalTime);
        if (!incomingRouteMetrics) {
            if (state.active && routeMetricsFresh(now)) {
                AppLog.line(app, "Navigation ignored active refresh without route metrics: "
                        + clean(source));
            } else if (state.active && state.loading() && waitingForRoute) {
                AppLog.line(app, "Navigation loading waits for route metrics: " + clean(source));
            } else {
                startRouteLoadingWithoutMetrics(source);
            }
            return;
        }
        if (finishSuppressesYandex(source, first(routeDistance, routeTotalDistance),
                routeTime, finishStreet)) return;
        setActive(true, source);

        boolean finishPointChanged = rememberFinishPoint(intent, source);
        lastRouteMetricsAt = now;
        routeIdChanged |= syncActiveRouteId(intent, source);
        String acceptedCurrent = etaStreetCandidate(currentStreet, state.currentStreet);
        String acceptedNext = streetCandidate(nextStreet, state.nextStreet,
                state.currentStreet, "");
        String acceptedFinish = finishForState(finishStreet, finishPointChanged);
        if (TextUtils.isEmpty(acceptedCurrent) && TextUtils.isEmpty(acceptedNext)
                && TextUtils.isEmpty(acceptedFinish) && TextUtils.isEmpty(routeTotalDistance)
                && !hasConfirmedRouteMetrics(routeDistance, routeTime, arrivalTime)) return;
        if (clearCoreBridgeManeuver && (!TextUtils.isEmpty(state.maneuver)
                || !TextUtils.isEmpty(state.maneuverDistance))) {
            resetManeuverProgress();
            AppLog.line(app, "Navigation cleared stale Core Bridge maneuver without live distance");
        }
        String retainedManeuverDistance = clearCoreBridgeManeuver
                ? ""
                : first(activeManeuverDistance, state.maneuverDistance);
        state = new NavigationState(true, state.finishReached, state.speedExceeded,
                clearCoreBridgeManeuver ? "" : state.maneuver,
                clearCoreBridgeManeuver ? "" : state.maneuverText,
                retainedManeuverDistance,
                first(normalizeDistanceText(routeDistance), state.routeDistance),
                first(clean(routeTime), state.routeTime),
                first(clean(arrivalTime), state.arrivalTime),
                first(acceptedCurrent, state.currentStreet), first(acceptedNext, state.nextStreet),
                first(acceptedFinish, state.finishStreet), state.speedLimit, state.currentSpeed, source, now);
        if (!TextUtils.isEmpty(activeManeuverDistance)
                && !TextUtils.isEmpty(state.maneuver)
                && !activeManeuverDistance.equals(rawActiveManeuverDistance)) {
            AppLog.line(app, "Navigation active snapshot main distance accepted: "
                    + clean(state.maneuver) + " distance=" + clean(activeManeuverDistance)
                    + " raw=" + clean(rawActiveManeuverDistance)
                    + " source=" + clean(source));
        }
        rememberRouteTotalDistance(routeTotalDistance, now);
        publishNavigationState();
        sendConfiguredText();
        AppLog.line(app, "Navigation active street: " + state.summary());
    }

    private boolean unconfirmedCoreBridgeFinish(Intent intent, String source) {
        if (!YandexCoreBridgeContract.SOURCE.equals(clean(source))) return false;
        if (!coreBridgeFinishRequested(intent, source)) return false;
        if (coreBridgeFinishConfirmed(intent)) return false;
        AppLog.line(app, "Navigation ignored unconfirmed Core Bridge finish: "
                + "route=" + clean(routeDistanceFromIntent(intent))
                + " maneuver=" + clean(explicitManeuverDistanceFromIntent(intent))
                + " stateRoute=" + clean(state.routeDistance)
                + " stateManeuver=" + clean(state.maneuverDistance)
                + " waiting=" + waitingForRoute
                + " loading=" + state.loading()
                + " source=" + clean(source));
        return true;
    }

    private boolean coreBridgeFinishConfirmed(Intent intent) {
        if (!state.active || state.finishReached || waitingForRoute || state.loading()) return false;
        long now = System.currentTimeMillis();
        if (routeStartedAt > 0L && now - routeStartedAt < ROUTE_WAIT_MIN_MS) return false;
        if (!routeGuidanceFresh() && !routeMetricsFresh(now)) return false;
        String routeDistance = routeDistanceFromIntent(intent);
        String maneuverDistance = explicitManeuverDistanceFromIntent(intent);
        return finishAllowedByDistance(routeDistance, maneuverDistance,
                state.routeDistance, state.maneuverDistance)
                && !finishContradictedByGuidance(routeDistance,
                first(maneuverDistance, state.maneuverDistance),
                first(routeTimeFromIntent(intent), state.routeTime));
    }

    private static boolean coreBridgeFinishRequested(Intent intent, String source) {
        if (bool(intent, "finish_reached", false)
                || bool(intent, "arrived", false)
                || bool(intent, "destination_reached", false)
                || bool(intent, "route_finished", false)) {
            return true;
        }
        String sourceLower = clean(source).toLowerCase(Locale.US);
        return sourceLower.contains("finish")
                || sourceLower.contains("arrived")
                || sourceLower.contains("destination");
    }

    private static boolean confirmedRouteStop(Intent intent, String source) {
        if (bool(intent, "route_stop_confirmed", false)
                || bool(intent, "confirmed_route_stop", false)
                || bool(intent, "route_confirmed_off", false)
                || bool(intent, "route_removed", false)
                || bool(intent, "route_cancel_confirmed", false)
                || bool(intent, "route_reset_confirmed", false)) {
            return true;
        }
        String cleanSource = clean(source).toLowerCase(Locale.US);
        return cleanSource.contains("route_stop_confirmed")
                || cleanSource.contains("confirmed_route_stop")
                || cleanSource.contains("route_confirmed_off")
                || cleanSource.contains("route_removed")
                || cleanSource.contains("route_cancel_confirmed")
                || cleanSource.contains("route_reset_confirmed");
    }

    private boolean routeStopLooksLikeFinish(Intent intent, String source) {
        if (bool(intent, "finish_reached", false)
                || bool(intent, "arrived", false)
                || bool(intent, "destination_reached", false)
                || bool(intent, "route_finished", false)) {
            return true;
        }
        String sourceLower = clean(source).toLowerCase(Locale.US);
        if (sourceLower.contains("finish")
                || sourceLower.contains("arrived")
                || sourceLower.contains("destination")) {
            return true;
        }
        String routeDistance = first(text(intent, "distance"),
                text(intent, "route_distance"),
                text(intent, "remaining_distance"),
                text(intent, "edistance"),
                state.routeDistance,
                state.maneuverDistance);
        if (finishAllowedByDistance(routeDistance)
                && !finishContradictedByGuidance(routeDistance,
                state.maneuverDistance, state.routeTime)) {
            return true;
        }
        float directMeters = distanceToFinishMeters();
        return directMeters > 0f && directMeters <= AUTO_FINISH_GPS_METERS + FINISH_STALE_RECOVERY_MARGIN_METERS;
    }

    private static boolean preConfirmRouteStopClick(String source) {
        String cleanSource = clean(source).toLowerCase(Locale.US);
        return cleanSource.contains("reset_route_click")
                || cleanSource.contains("route_stop_click")
                || cleanSource.contains("route_cancel_click")
                || cleanSource.contains("close_route_click");
    }

    private void requestInactiveAfterConfirm(String source) {
        String cleanSource = clean(source);
        if (!state.active || state.finishReached || !hasRouteSnapshot()) {
            setActive(false, cleanSource);
            return;
        }
        int generation = ++pendingInactiveGeneration;
        pendingInactiveSource = cleanSource;
        AppLog.line(app, "Navigation pending off until route confirmation: " + cleanSource);
        handler.postDelayed(() -> {
            synchronized (NavigationFeature.this) {
                if (generation != pendingInactiveGeneration) return;
                if (!state.active || state.finishReached) return;
                if (routeGuidanceFresh()) {
                    AppLog.line(app, "Navigation pending off canceled by live route: "
                            + pendingInactiveSource);
                    pendingInactiveSource = "";
                    return;
                }
                String pendingSource = pendingInactiveSource;
                pendingInactiveSource = "";
                setActive(false, pendingSource);
            }
        }, ROUTE_STOP_CONFIRM_MS);
    }

    private void cancelPendingInactive(String reason) {
        if (TextUtils.isEmpty(pendingInactiveSource)) return;
        pendingInactiveGeneration++;
        if (!TextUtils.isEmpty(pendingInactiveSource)) {
            AppLog.line(app, "Navigation pending off canceled: " + clean(reason));
        }
        pendingInactiveSource = "";
    }

    private boolean hasRouteSnapshot() {
        return !TextUtils.isEmpty(state.routeDistance)
                || !TextUtils.isEmpty(state.routeTime)
                || !TextUtils.isEmpty(state.arrivalTime)
                || !TextUtils.isEmpty(state.maneuver)
                || !TextUtils.isEmpty(state.maneuverText)
                || !TextUtils.isEmpty(state.maneuverDistance)
                || !TextUtils.isEmpty(state.nextStreet)
                || !TextUtils.isEmpty(state.finishStreet);
    }

    private boolean routeGuidanceFresh() {
        long now = System.currentTimeMillis();
        return lastRouteGuidanceAt > 0L
                && now - lastRouteGuidanceAt <= ROUTE_STOP_CONFIRM_MS;
    }

    private boolean routeMetricsFresh(long now) {
        return lastRouteMetricsAt > 0L
                && now - lastRouteMetricsAt <= YANDEX_WATCHDOG_STALE_MS;
    }

    private void touchYandexWatchdog(String source) {
        if (!isYandexSource(source)) return;
        lastYandexNavigationPacketAt = System.currentTimeMillis();
        int generation = ++yandexWatchdogGeneration;
        handler.postDelayed(() -> {
            synchronized (NavigationFeature.this) {
                if (generation != yandexWatchdogGeneration) return;
                if (!state.active || !isYandexSource(state.source)) return;
                long staleFor = System.currentTimeMillis() - lastYandexNavigationPacketAt;
                if (lastYandexNavigationPacketAt > 0L && staleFor < YANDEX_WATCHDOG_STALE_MS) return;
                forceInactive("yandex_watchdog_stale");
            }
        }, YANDEX_WATCHDOG_STALE_MS);
    }

    public synchronized void touchYandexCoreBridgeHeartbeat(boolean routeMetrics) {
        long now = System.currentTimeMillis();
        touchYandexWatchdog(YandexCoreBridgeContract.SOURCE);
        if (routeMetrics) {
            lastRouteGuidanceAt = now;
            lastRouteMetricsAt = now;
            cancelPendingInactive("core_bridge_heartbeat");
        }
    }

    private void forceInactive(String source) {
        cancelPendingInactive("force " + clean(source));
        cancelFinishHold();
        waitingForRoute = false;
        routeStartedAt = 0L;
        routeReroutingGeneration++;
        routeReroutingUntil = 0L;
        lastDirectManeuverAt = 0L;
        clearLaneHintHold();
        clearEventHintHold();
        lastRouteGuidanceAt = 0L;
        lastRouteMetricsAt = 0L;
        clearRoundaboutExitHold();
        clearFinishPoint();
        resetManeuverProgress();
        resetNavigationTextCache();
        resetNavigationSendCache();
        state = new NavigationState(false, false, state.speedExceeded,
                "", "", "", "", "", "",
                state.currentStreet, "", "", state.speedLimit, state.currentSpeed,
                clean(source), System.currentTimeMillis());
        publishNavigationState();
        sender.sendActive(false);
        AppLog.line(app, "Navigation forced off: " + clean(source));
    }

    public synchronized void sendManeuver(String imageId, String distance, String unit, String street) {
        setActive(true);
        String normalizedStreet = normalizeStreetLabel(street);
        String normalizedDistance = distanceText(distance, unit);
        String maneuver = clean(imageId);
        lastRouteGuidanceAt = System.currentTimeMillis();
        cancelPendingInactive("manual_maneuver");
        state = new NavigationState(true, isFinishManeuver(maneuver), state.speedExceeded,
                maneuver, maneuverLabel(maneuver), normalizedDistance,
                state.routeDistance, state.routeTime, state.arrivalTime,
                state.currentStreet, first(normalizedStreet, state.nextStreet), state.finishStreet,
                state.speedLimit, state.currentSpeed, "manual_maneuver",
                System.currentTimeMillis());
        publishNavigationState();
        startManeuverTextHint(maneuver, "");
        sendManeuverIfChanged(imageId, distanceValue(distance), isKm(unit),
                maneuverProgressBucket(maneuver, normalizedStreet, normalizedDistance));
        sendConfiguredText();
        AppLog.line(app, "Navigation maneuver: " + state.summary());
    }

    private void handleManeuver(Intent intent) {
        String source = first(text(intent, "source"), ACTION_MANEUVER);
        String sourceLower = source.toLowerCase(Locale.US);
        if (ignoredLaneDebugSource(sourceLower)) {
            AppLog.line(app, "Navigation ignored lane debug packet: " + source);
            return;
        }
        boolean routeRoadOnly = routeRoadOnlySource(intent, sourceLower);
        String maneuver = first(text(intent, "imageId"), text(intent, "image_id"),
                text(intent, "direction"), text(intent, "maneuver"), text(intent, "maneuver_action"),
                textByKeyPart(intent, "image"));
        if (TextUtils.isEmpty(maneuver) && !sourceLower.contains("direction_sign")
                && !routeRoadOnly) {
            maneuver = first(textByKeyPart(intent, "direction"), textByKeyPart(intent, "maneuver"));
        }
        String directionSignManeuver = directionSignManeuver(intent);
        if (TextUtils.isEmpty(maneuver) && isUsableManeuver(directionSignManeuver)) {
            maneuver = directionSignManeuver;
        }
        String unit = first(text(intent, "unit"), text(intent, "distance_unit"));
        String rawManeuverDistance = first(text(intent, "distance"), text(intent, "distance_val_str"),
                textByKeyPart(intent, "distance"));
        String maneuverDistance = distanceText(rawManeuverDistance, unit);
        String laneUnit = first(text(intent, "lane_distance_unit"), text(intent, "micro_distance_unit"));
        String laneMetersDistance = metersDistanceText(first(text(intent, "lane_distance_meters"),
                text(intent, "micro_distance_meters")));
        String explicitLaneDistance = first(laneMetersDistance,
                distanceText(first(text(intent, "lane_distance"), text(intent, "laneDistance"),
                        text(intent, "micro_distance"), text(intent, "microDistance")), first(laneUnit, unit)));
        String explicitMicroDistance = nonZeroDistance(explicitLaneDistance);
        boolean laneDistanceOnlySource = laneDistanceOnlySource(sourceLower);
        String currentStreet = first(text(intent, "current_street"), text(intent, "currentStreet"),
                text(intent, "current_road"), text(intent, "currentRoad"), text(intent, "current_road_name"),
                text(intent, "currentRoadName"), text(intent, "current_street_name"),
                text(intent, "currentStreetName"), text(intent, "position"));
        String explicitAfterStreet = first(text(intent, "road_after"), text(intent, "roadAfter"),
                text(intent, "road_after_name"), text(intent, "street_after"), text(intent, "streetAfter"),
                text(intent, "street_after_maneuver"), text(intent, "streetAfterManeuver"),
                text(intent, "maneuver_toponym"));
        String nextStreet = first(explicitAfterStreet,
                text(intent, "next_street"), text(intent, "nextStreet"),
                text(intent, "next_road"), text(intent, "nextRoad"), text(intent, "next_road_name"),
                text(intent, "nextRoadName"), text(intent, "next_street_name"), text(intent, "nextStreetName"),
                text(intent, "next_turn_street"), text(intent, "nextTurnStreet"),
                text(intent, "street"), text(intent, "raw_street"), text(intent, "road"));
        String finishStreet = finishTextFromIntent(intent);
        if (finishSuppressesYandex(source, maneuverDistance, "", finishStreet)) return;
        String signEvent = yandexSignEventText(intent, sourceLower);
        long signEventNow = System.currentTimeMillis();
        if (!TextUtils.isEmpty(signEvent)) {
            rememberEventHint(signEvent, source, signEventNow);
        }
        boolean signHasMicroSeed = !TextUtils.isEmpty(signEvent)
                && (!TextUtils.isEmpty(explicitMicroDistance) || isUsableManeuver(directionSignManeuver));
        if (!TextUtils.isEmpty(signEvent) && TextUtils.isEmpty(maneuver) && !signHasMicroSeed) {
            rememberLaneHint(signEvent, source, signEventNow);
            state = state.withLaneHint(activeLaneHint, activeLaneSource, signEventNow)
                    .withEventHint(activeEventHint, activeEventSource, signEventNow);
            publishNavigationState();
            AppLog.line(app, "Navigation Yandex sign event: " + signEvent
                    + " source=" + source);
            return;
        }
        boolean finishPointChanged = rememberFinishPoint(intent, source);
        boolean routeIdChanged = syncActiveRouteId(intent, source);
        String routeActionManeuver = routeActionManeuver(intent);
        String contradictedRouteActionManeuver = "";
        String laneHighlightManeuver = laneHighlightedManeuver(intent, sourceLower);
        if (routeRoadOnly && routeRoadActionContradictedByLaneTruth(intent, sourceLower,
                routeActionManeuver, laneHighlightManeuver)) {
            contradictedRouteActionManeuver = routeActionManeuver;
            AppLog.line(app, "Navigation ignored contradictory route-road route_action: action="
                    + clean(routeActionManeuver)
                    + " highlight=" + first(laneHighlightManeuver, "-")
                    + " raw=" + shortRaw(source, rawExtras(intent)));
            routeActionManeuver = "";
        }
        boolean routeRoadActionRejectedAsMain = false;
        if (routeRoadOnly && routeRoadActionBlockedByCloseMain(routeActionManeuver, maneuverDistance)) {
            AppLog.line(app, "Navigation ignored close route-road route_action as main: current="
                    + clean(state.maneuver) + " action=" + clean(routeActionManeuver)
                    + " currentDistance=" + clean(state.maneuverDistance)
                    + " incomingDistance=" + clean(maneuverDistance)
                    + " source=" + source);
            routeRoadActionRejectedAsMain = true;
        }
        String imageManeuver = normalizeManeuver(clean(maneuver), intent.getIntExtra("direction_lr", 0));
        String cleanManeuver = imageManeuver;
        if (!routeRoadActionRejectedAsMain
                && shouldPreferRouteActionManeuver(intent, cleanManeuver, routeActionManeuver)) {
            AppLog.line(app, "Navigation route_action overrides image_id: "
                    + cleanManeuver + " -> " + routeActionManeuver
                    + " source=" + source);
            cleanManeuver = routeActionManeuver;
        } else if (!routeRoadActionRejectedAsMain
                && !isUsableManeuver(cleanManeuver) && !TextUtils.isEmpty(routeActionManeuver)) {
            cleanManeuver = routeActionManeuver;
        }
        String highlightedMicroManeuver = routeRoadOnly
                ? first(laneHighlightManeuver, directionSignManeuver)
                : first(directionSignManeuver, laneHighlightManeuver);
        boolean staleProviderLaneTopology = staleProviderLaneTopologyConflict(intent, sourceLower,
                cleanManeuver, laneHighlightManeuver, explicitLaneDistance);
        if (staleProviderLaneTopology) {
            AppLog.line(app, "Navigation ignored stale provider lane topology: main="
                    + clean(cleanManeuver)
                    + " highlight=" + first(laneHighlightManeuver, "-")
                    + " raw=" + shortRaw(source, rawExtras(intent)));
            laneHighlightManeuver = "";
            highlightedMicroManeuver = "";
        }
        if (routeRoadOnly
                && !isUsableManeuver(cleanManeuver)
                && isUsableManeuver(state.maneuver)
                && isUsableManeuver(highlightedMicroManeuver)) {
            cleanManeuver = state.maneuver;
        }
        if (laneDistanceOnlySource
                && (isUsableManeuver(routeActionManeuver)
                || isUsableManeuver(highlightedMicroManeuver))) {
            laneDistanceOnlySource = false;
        }
        if (routeRoadOnly && isUsableManeuver(state.maneuver)
                && isUsableManeuver(routeActionManeuver)
                && !sameManeuverFamily(state.maneuver, routeActionManeuver)
                && !routeActionHighlightMatches(intent, routeActionManeuver)
                && !routeRoadPriorityActionConfirmed(intent, routeActionManeuver, maneuverDistance)) {
            String rejectedRouteAction = routeActionManeuver;
            AppLog.line(app, "Navigation ignored route-road route_action as main: current="
                    + clean(state.maneuver) + " action=" + clean(routeActionManeuver)
                    + " source=" + source);
            routeRoadActionRejectedAsMain = true;
            routeActionManeuver = "";
            if (!isUsableManeuver(cleanManeuver) || cleanManeuver.equals(rejectedRouteAction)) {
                cleanManeuver = first(state.maneuver, laneHighlightManeuver);
            }
        }
        if (!routeRoadOnly && !isUsableManeuver(cleanManeuver)
                && isUsableManeuver(highlightedMicroManeuver)) {
            cleanManeuver = highlightedMicroManeuver;
            AppLog.line(app, "Navigation lane highlight micro: "
                    + cleanManeuver + " source=" + source);
        }
        String roundaboutSeedManeuver = first(cleanManeuver, routeActionManeuver);
        String roundaboutExit = roundaboutExit(intent, roundaboutSeedManeuver);
        boolean roundaboutFromRouteData = isRoundaboutManeuver(routeActionManeuver)
                || !TextUtils.isEmpty(roundaboutExit)
                || roundaboutExitContext(intent, roundaboutSeedManeuver, sourceLower);
        if (roundaboutFromRouteData && !isUsableManeuver(roundaboutSeedManeuver)) {
            roundaboutSeedManeuver = "context_ra_in_circular_movement";
        }
        if (roundaboutFromRouteData && isUsableManeuver(roundaboutSeedManeuver)) {
            String routeRoundaboutManeuver = roundaboutManeuver(roundaboutSeedManeuver,
                    roundaboutExit, intent, sourceLower);
            if (isUsableManeuver(routeRoundaboutManeuver)
                    && !routeRoundaboutManeuver.equals(cleanManeuver)) {
                AppLog.line(app, "Navigation route roundabout overrides maneuver: "
                        + clean(cleanManeuver) + " -> " + routeRoundaboutManeuver
                        + " exit=" + first(roundaboutExit, "-")
                        + " source=" + source);
                cleanManeuver = routeRoundaboutManeuver;
                routeActionManeuver = routeRoundaboutManeuver;
                laneDistanceOnlySource = false;
            }
        }
        String explicitGrayRoad = grayRoadManeuver(intent, sourceLower);
        if (staleProviderLaneTopology) {
            explicitGrayRoad = "";
            clearGrayRoadHold();
            clearLearnedRoadOptions();
        }
        if (routeRoadOnly) {
            explicitGrayRoad = adjustRouteRoadGrayForCurrentManeuver(intent, explicitGrayRoad,
                    first(state.maneuver, cleanManeuver));
            String trustedGrayRoad = grayRoadForManeuver(first(contradictedRouteActionManeuver,
                    routeActionManeuver, cleanManeuver, state.maneuver));
            if (!TextUtils.isEmpty(contradictedRouteActionManeuver)
                    && !TextUtils.isEmpty(trustedGrayRoad)) {
                AppLog.line(app, "Navigation clamped contradictory route-road gray: "
                        + clean(explicitGrayRoad) + " -> " + trustedGrayRoad
                        + " action=" + contradictedRouteActionManeuver
                        + " highlight=" + first(laneHighlightManeuver, "-")
                        + " source=" + source);
                explicitGrayRoad = trustedGrayRoad;
            }
        }
        long now = System.currentTimeMillis();
        if (!state.active && packetClaimsActiveRoute(intent)
                && canResumeFromManeuverPacket(routeRoadOnly, cleanManeuver,
                routeActionManeuver, explicitGrayRoad)) {
            if (!hasConfirmedRouteMetrics()) {
                AppLog.line(app, "Navigation ignored active maneuver without route metrics: " + source);
                return;
            }
            AppLog.line(app, "Navigation resumed active from maneuver packet: " + source);
            setActive(true, source);
            rememberFinishPoint(intent, source);
            now = System.currentTimeMillis();
        }
        if (laneDistanceOnlySource) {
            if (mainGuidanceDistanceTick(sourceLower, intent, explicitLaneDistance, maneuverDistance)) {
                applyMainGuidanceDistanceTick(maneuverDistance, source, now);
                return;
            }
            if (!state.active) {
                AppLog.line(app, "Navigation ignored lane distance while inactive: " + source);
                return;
            }
            applyLaneDistanceOnly(intent, explicitLaneDistance, source, sourceLower, now);
            return;
        }
        if (!isUsableManeuver(cleanManeuver)) {
            if (!TextUtils.isEmpty(explicitGrayRoad)) {
                applyGrayRoadOnly(explicitGrayRoad, source, maneuverDistance);
                return;
            }
            AppLog.line(app, "Navigation ignored maneuver: " + cleanManeuver
                    + " no road scheme/topology source=" + source
                    + " raw=" + shortRaw(source, rawExtras(intent)));
            return;
        }
        boolean laneData = !staleProviderLaneTopology && hasLaneData(intent);
        boolean laneOnlySource = routeRoadOnly
                || laneMicroSource(sourceLower)
                || sourceLower.contains("route_options");
        boolean providerLaneData = sourceLower.contains(YandexCoreBridgeContract.SOURCE)
                && (bool(intent, "lane_guidance", false) || laneData);
        boolean directSource = !providerLaneData && !laneOnlySource && (sourceLower.contains("setnextmaneuver")
                || sourceLower.contains("context_maneuver")
                || sourceLower.contains("fullscreen_maneuver")
                || sourceLower.contains("auto_widget_state")
                || sourceLower.contains(YandexCoreBridgeContract.SOURCE)
                || sourceLower.contains("roundabout_exit_late"));
        boolean highlightedMicro = isUsableManeuver(highlightedMicroManeuver)
                && !highlightedMicroManeuver.equals(routeActionManeuver)
                && !highlightedMicroManeuver.equals(cleanManeuver)
                && !sameManeuverFamily(highlightedMicroManeuver, routeActionManeuver)
                && !sameManeuverFamily(highlightedMicroManeuver, cleanManeuver);
        boolean routeRoadHighlightedMicro = routeRoadOnly && highlightedMicro;
        boolean microSource = !directSource
                && !laneDistanceOnlySource
                && ((!routeRoadOnly && laneMicroSource(sourceLower))
                || routeRoadHighlightedMicro
                || (!routeRoadOnly && !TextUtils.isEmpty(explicitMicroDistance)));
        boolean laneGuidance = !staleProviderLaneTopology && !directSource
                && (bool(intent, "lane_guidance", false)
                || laneOnlySource || laneData);
        boolean direct = directSource;
        boolean microEnabled = AppSettings.navMicroManeuvers(app);
        if (!microEnabled && laneGuidance) {
            AppLog.line(app, "Navigation lane/lana disabled for main test: maneuver="
                    + clean(cleanManeuver)
                    + " distance=" + clean(maneuverDistance)
                    + " source=" + clean(source));
        }
        String acceptedCurrent = "";
        String acceptedNext = TextUtils.isEmpty(explicitAfterStreet)
                ? streetCandidate(nextStreet, state.nextStreet, state.currentStreet, "")
                : nextStreetCandidate(explicitAfterStreet, state.nextStreet);
        if (!TextUtils.isEmpty(explicitAfterStreet)) {
            String normalizedAfter = normalizeStreetLabel(explicitAfterStreet);
            AppLog.line(app, "Navigation after street: raw=" + clean(explicitAfterStreet)
                    + " normalized=" + normalizedAfter
                    + " accepted=" + first(acceptedNext, "-")
                    + " current=" + first(state.currentStreet, "-")
                    + " nextBefore=" + first(state.nextStreet, "-")
                    + " looks=" + looksLikeStreetText(normalizedAfter)
                    + " sameNext=" + sameStreet(normalizedAfter, state.nextStreet)
                    + " source=" + source);
        }
        String acceptedFinish = finishForState(finishStreet, finishPointChanged);
        boolean finishRequested = isFinishManeuver(cleanManeuver) || bool(intent, "finish_reached", false)
                || bool(intent, "arrived", false);
        boolean finish = finishRequested && finishAllowedByDistance(rawManeuverDistance,
                maneuverDistance, state.routeDistance, state.maneuverDistance);
        if (finishRequested && !finish) {
            AppLog.line(app, "Navigation ignored early finish: distance="
                    + first(maneuverDistance, state.routeDistance, "-")
                    + " source=" + source);
            return;
        }
        boolean roundabout = isRoundaboutManeuver(cleanManeuver) || !TextUtils.isEmpty(roundaboutExit);
        if (finishPointChanged && state.active) {
            clearStaleRouteVisual("finish point changed " + source);
        }
        if (laneGuidance && roundabout && !routeRoadHighlightedMicro) laneGuidance = false;
        String displayManeuver = roundabout
                ? roundaboutManeuver(cleanManeuver, roundaboutExit, intent, sourceLower) : cleanManeuver;
        String visibleRouteActionManeuver = visibleRouteActionManeuver(routeActionManeuver, displayManeuver);
        if (roundabout) {
            if (isRoundaboutExitManeuver(displayManeuver)) {
                if (!routeRoadOnly || !TextUtils.isEmpty(nonZeroDistance(maneuverDistance))) {
                    rememberRoundaboutExit(displayManeuver, roundaboutExit, now);
                }
            } else {
                AppLog.line(app, "Navigation roundabout without explicit exit number: "
                        + shortRaw(source, rawExtras(intent)));
            }
        } else if (!laneGuidance) {
            clearRoundaboutExitHold();
        }
        boolean stateRoundabout = isRoundaboutManeuver(state.maneuver);
        if (!state.active) {
            if (!direct) {
                AppLog.line(app, "Navigation ignored maneuver while inactive: " + source);
                return;
            }
            if (!hasConfirmedRouteMetrics()) {
                AppLog.line(app, "Navigation ignored direct maneuver without route metrics: " + source);
                return;
            }
            AppLog.line(app, "Navigation activating from maneuver: " + source);
            setActive(true, source);
            rememberFinishPoint(intent, source);
            now = System.currentTimeMillis();
        }
        if (routeRoadOnly && roundabout && TextUtils.isEmpty(nonZeroDistance(maneuverDistance))
                && isUsableManeuver(state.maneuver) && !isRoundaboutManeuver(state.maneuver)) {
            AppLog.line(app, "Navigation ignored stale route-road roundabout: current="
                    + state.maneuver + " source=" + source);
            return;
        }
        String heldRoundabout = heldRoundaboutExitManeuver(now);
        if (laneGuidance && !roundabout && !TextUtils.isEmpty(heldRoundabout)
                && isUsableManeuver(cleanManeuver)
                && !isRoundaboutManeuver(cleanManeuver)
                && !sameManeuverFamily(heldRoundabout, cleanManeuver)
                && !TextUtils.isEmpty(nonZeroDistance(maneuverDistance))) {
            String clearedRoundabout = heldRoundabout;
            clearRoundaboutExitHold();
            heldRoundabout = "";
            if (stateRoundabout) {
                clearStaleManeuverVisual("new main after roundabout lane " + source);
                stateRoundabout = false;
            }
            AppLog.line(app, "Navigation cleared held roundabout for new main: held="
                    + clean(clearedRoundabout) + " incoming=" + clean(cleanManeuver)
                    + " distance=" + clean(maneuverDistance)
                    + " source=" + clean(source));
        }
        if (!TextUtils.isEmpty(heldRoundabout) && !stateRoundabout && !roundabout) {
            clearRoundaboutExitHold();
            heldRoundabout = "";
            AppLog.line(app, "Navigation cleared stale roundabout hold before lane: current="
                    + clean(state.maneuver) + " source=" + source);
        }
        if (laneGuidance && !roundabout && !TextUtils.isEmpty(heldRoundabout)) {
            String heldDistance = first(nonZeroDistance(maneuverDistance),
                    nonZeroDistance(state.maneuverDistance));
            String heldGrayRoad = first(explicitGrayRoad,
                    grayRoadUntil > now ? activeGrayRoadId : "");
            if (!TextUtils.isEmpty(explicitGrayRoad)) {
                String grayKey = grayRoadKey(heldRoundabout,
                        first(state.nextStreet, acceptedNext, acceptedCurrent, state.currentStreet));
                rememberLearnedRoadOptions(roadOptionKey(first(acceptedCurrent, state.currentStreet),
                        first(acceptedNext, state.nextStreet)), grayRoadMask(explicitGrayRoad), now, source);
                rememberGrayRoad(explicitGrayRoad, grayKey,
                        laneDebugText(explicitGrayRoad,
                                laneHintText(intent, cleanManeuver, maneuverDistance, sourceLower)),
                        source, now);
                heldGrayRoad = explicitGrayRoad;
            }
            lastRouteGuidanceAt = now;
            cancelPendingInactive("lane while roundabout " + source);
            state = state.withLaneHint(activeLaneHint, activeLaneSource, now)
                    .withNavigationDebug(imageManeuver, visibleRouteActionManeuver, "",
                            "", microMainHoldStatus(true, heldRoundabout),
                            heldGrayRoad, grayRoadLabel(heldGrayRoad), now);
            publishNavigationState();
            if (!TextUtils.isEmpty(heldGrayRoad)) {
                sendManeuverWithGrayRoadIfChanged(heldRoundabout, heldGrayRoad,
                        distanceValue(heldDistance), isKm(heldDistance),
                        maneuverProgressBucket(heldRoundabout, first(state.nextStreet, state.currentStreet),
                                heldDistance),
                        true);
            } else {
                sendManeuverIfChanged(heldRoundabout, distanceValue(heldDistance), isKm(heldDistance),
                        maneuverProgressBucket(heldRoundabout, first(state.nextStreet, state.currentStreet),
                                heldDistance),
                        true);
            }
            sendConfiguredText();
            AppLog.line(app, "Navigation held roundabout over lane: "
                    + heldRoundabout + " gray=" + first(heldGrayRoad, "-") + " laneSource=" + source);
            return;
        }
        if (laneGuidance) {
            String laneHint;
            String incomingMainDistance = nonZeroDistance(maneuverDistance);
            boolean incomingMainDistanceLooksMicro = laneMainDistanceLooksLikeMicro(
                    incomingMainDistance, explicitMicroDistance);
            if (routeIdChanged && incomingMainDistanceLooksMicro
                    && hasMainManeuverDistanceMeters(intent)) {
                AppLog.line(app, "Navigation accepted new route main distance despite lane match: main="
                        + clean(incomingMainDistance)
                        + " lane=" + clean(explicitMicroDistance)
                        + " source=" + clean(source));
                incomingMainDistanceLooksMicro = false;
            }
            if (incomingMainDistanceLooksMicro) {
                AppLog.line(app, "Navigation kept main distance over lane micro distance: main="
                        + clean(currentMainTxDistance())
                        + " incoming=" + clean(incomingMainDistance)
                        + " micro=" + clean(explicitMicroDistance)
                        + " source=" + clean(source));
                incomingMainDistance = "";
            }
            if (!TextUtils.isEmpty(incomingMainDistance)) {
                state = state.withManeuverDistance(incomingMainDistance, now);
            }
            String mainDistance = first(incomingMainDistance, currentMainTxDistance());
            if (YandexCoreBridgeContract.SOURCE.equals(clean(source))
                    && isUsableManeuver(cleanManeuver)
                    && !TextUtils.isEmpty(nonZeroDistance(mainDistance))
                    && (TextUtils.isEmpty(state.maneuver)
                    || maneuverFamilyChanged(state.maneuver, cleanManeuver))) {
                state = state.withMainManeuver(cleanManeuver,
                        maneuverLabel(cleanManeuver, roundaboutExit), mainDistance,
                        source, now);
            }
            boolean yandexLanaGuidance = YandexCoreBridgeContract.SOURCE.equals(clean(source))
                    && laneGuidance
                    && hasLaneData(intent);
            String laneDistanceText = explicitMicroDistance;
            boolean lanaDistanceFallback = yandexLanaGuidance
                    && TextUtils.isEmpty(nonZeroDistance(laneDistanceText))
                    && !TextUtils.isEmpty(nonZeroDistance(mainDistance));
            if (lanaDistanceFallback) {
                laneDistanceText = nonZeroDistance(mainDistance);
            }
            String laneDebugDistance = laneDistanceText;
            boolean lanePassed = handleLaneDistancePassed(explicitLaneDistance, now, source);
            if (lanePassed) {
                laneDistanceText = "";
                laneDebugDistance = "";
            }
            boolean activePostPassMicro = activeMicroPostPassActive(now);
            boolean lanePostPassBlockedByNearMain = (lanePassed || activePostPassMicro)
                    && nextMainManeuverCloseForMicroPostPass();
            if (!lanePassed && lanePostPassBlockedByNearMain && activePostPassMicro) {
                AppLog.line(app, "Navigation lane micro post-pass cleared by close main: micro="
                        + clean(activeMicroManeuver)
                        + " main=" + clean(state.maneuver)
                        + " mainDistance=" + clean(currentMainTxDistance())
                        + " source=" + clean(source));
                clearMicroHintHold();
                clearLaneTxPostPassHold();
                activePostPassMicro = false;
            }
            boolean routeRoadDisplayMicro = false;
            boolean keepActiveMicroTxAllowed = false;
            boolean laneMicroSuppressedByMain = false;
            boolean microManeuverFromFallbackMain = false;
            String microManeuver = "";
            if (microSource) {
                microManeuver = first(highlightedMicroManeuver, visibleRouteActionManeuver);
                if (TextUtils.isEmpty(microManeuver)) {
                    microManeuver = cleanManeuver;
                    microManeuverFromFallbackMain = true;
                }
            } else if (yandexLanaGuidance) {
                microManeuver = first(highlightedMicroManeuver, cleanManeuver, visibleRouteActionManeuver);
                microSource = !TextUtils.isEmpty(microManeuver);
            }
            if (lanePostPassBlockedByNearMain && !TextUtils.isEmpty(microManeuver)) {
                AppLog.line(app, "Navigation lane micro post-pass cleared by close main: micro="
                        + clean(microManeuver)
                        + " main=" + clean(state.maneuver)
                        + " mainDistance=" + clean(currentMainTxDistance())
                        + " source=" + clean(source));
                clearMicroHintHold();
                microManeuver = "";
                microSource = false;
                routeRoadDisplayMicro = false;
                laneDistanceText = "";
                laneDebugDistance = "";
            }
            boolean inferredForwardMicro = false;
            if (shouldInferForwardMicro(intent, sourceLower, microManeuver,
                    microManeuverFromFallbackMain, mainDistance, explicitMicroDistance, routeRoadOnly)) {
                microManeuver = "context_ra_forward";
                laneDistanceText = explicitMicroDistance;
                laneDebugDistance = explicitMicroDistance;
                microSource = true;
                inferredForwardMicro = true;
                AppLog.line(app, "Navigation inferred forward lane micro: distance="
                        + clean(explicitMicroDistance)
                        + " main=" + clean(mainDistance)
                        + " source=" + clean(source));
            }
            if (!TextUtils.isEmpty(microManeuver) && sameManeuverFamily(microManeuver, state.maneuver)
                    && (TextUtils.isEmpty(laneDistanceText) || microManeuverFromFallbackMain)) {
                microManeuver = "";
            }
            String priorityLaneGuard = priorityManeuverForLaneHold(cleanManeuver, state.maneuver);
            String priorityLaneDistance = first(nonZeroDistance(mainDistance),
                    nonZeroDistance(maneuverDistance), nonZeroDistance(state.maneuverDistance));
            boolean blockedByPriority = NavigationManeuverPolicy.priorityBlocksMicro(
                    priorityLaneGuard, priorityLaneDistance);
            boolean blockedByCloseMain = mainManeuverBlocksLaneMicro(microManeuver, mainDistance);
            if (!TextUtils.isEmpty(microManeuver) && (blockedByPriority || blockedByCloseMain)) {
                AppLog.line(app, "Navigation ignored lane micro by main guard: micro="
                        + clean(microManeuver)
                        + " priority=" + first(priorityLaneGuard, "-")
                        + " main=" + clean(state.maneuver)
                        + " mainDistance=" + clean(mainDistance)
                        + " source=" + clean(source));
                clearMicroHintHold();
                microManeuver = "";
                laneDistanceText = "";
                laneDebugDistance = "";
                microSource = false;
                routeRoadDisplayMicro = false;
                laneMicroSuppressedByMain = true;
            }
            boolean explicitLaneDistanceAvailable = !lanePassed && !TextUtils.isEmpty(explicitMicroDistance);
            String microDistanceSource = lanaDistanceFallback ? source + ":lana_fallback" : source;
            laneDistanceText = validMicroDistance(laneDistanceText,
                    mainDistance,
                    microManeuver,
                    microSource && (explicitLaneDistanceAvailable || lanaDistanceFallback),
                    microDistanceSource);
            boolean rejectedLaneDistance = microSource
                    && explicitLaneDistanceAvailable
                    && !TextUtils.isEmpty(microManeuver)
                    && TextUtils.isEmpty(laneDistanceText);
            if (rejectedLaneDistance) {
                boolean trustedIncomingDiffersFromActive = trustedLaneDirectionSource(sourceLower)
                        && isUsableManeuver(microManeuver)
                        && !TextUtils.isEmpty(activeMicroManeuver)
                        && microHintUntil > now
                        && !sameManeuverFamily(microManeuver, activeMicroManeuver);
                boolean trustedExplicitDistanceRejected = trustedLaneDirectionSource(sourceLower)
                        && explicitLaneDistanceAvailable;
                if (trustedIncomingDiffersFromActive) {
                    AppLog.line(app, "Navigation cleared stale lane micro after trusted highlight: active="
                            + clean(activeMicroManeuver) + " incoming=" + clean(microManeuver)
                            + " badDistance=" + clean(explicitMicroDistance)
                            + " source=" + clean(source));
                    clearMicroHintHold();
                    laneDebugDistance = explicitMicroDistance;
                } else if (trustedExplicitDistanceRejected) {
                    AppLog.line(app, "Navigation cleared stale lane micro after rejected trusted distance: active="
                            + clean(activeMicroManeuver) + " incoming=" + clean(microManeuver)
                            + " badDistance=" + clean(explicitMicroDistance)
                            + " main=" + clean(mainDistance)
                            + " source=" + clean(source));
                    clearMicroHintHold();
                    laneDistanceText = "";
                    laneDebugDistance = explicitMicroDistance;
                    keepActiveMicroTxAllowed = false;
                } else if (!TextUtils.isEmpty(activeMicroManeuver)
                        && microHintUntil > now
                        && !TextUtils.isEmpty(activeMicroDistanceToPublish())) {
                    microManeuver = activeMicroManeuver;
                    laneDistanceText = activeMicroDistanceToPublish();
                    laneDebugDistance = activeMicroDebugDistanceToPublish();
                    keepActiveMicroTxAllowed = true;
                    AppLog.line(app, "Navigation kept active lane micro over bad distance: "
                            + clean(microManeuver) + " distance=" + clean(laneDistanceText)
                            + " source=" + clean(source));
                } else {
                    microManeuver = "";
                }
            }
            if (!lanePassed && routeRoadOnly && microSource && TextUtils.isEmpty(laneDistanceText)) {
                laneDistanceText = activeLaneDistanceToPublish(now);
                laneDebugDistance = laneDistanceText;
            }
            if (TextUtils.isEmpty(microManeuver) && routeRoadOnly
                    && !laneMicroSuppressedByMain
                    && !TextUtils.isEmpty(explicitGrayRoad)) {
                String routeRoadMicroManeuver = fallbackLaneMicroManeuver(intent, sourceLower, explicitGrayRoad);
                String routeRoadMicroDistance = first(nonZeroDistance(explicitMicroDistance),
                        activeLaneDistanceToPublish(now));
                if (isUsableManeuver(routeRoadMicroManeuver)
                        && !TextUtils.isEmpty(routeRoadMicroDistance)) {
                    if (mainManeuverBlocksLaneMicro(routeRoadMicroManeuver, mainDistance)) {
                        AppLog.line(app, "Navigation ignored route-road fallback micro by main guard: micro="
                                + clean(routeRoadMicroManeuver)
                                + " main=" + clean(state.maneuver)
                                + " mainDistance=" + clean(mainDistance)
                                + " source=" + clean(source));
                        laneMicroSuppressedByMain = true;
                    } else {
                        microManeuver = routeRoadMicroManeuver;
                        laneDistanceText = routeRoadMicroDistance;
                        laneDebugDistance = routeRoadMicroDistance;
                        routeRoadDisplayMicro = true;
                    }
                }
            }
            if (TextUtils.isEmpty(microManeuver)) {
                laneDistanceText = "";
                laneDebugDistance = "";
            }
            boolean incomingHasTrustedMicroDistance = explicitLaneDistanceAvailable
                    && !TextUtils.isEmpty(nonZeroDistance(laneDistanceText));
            boolean incomingDifferentTrustedMicro = activePostPassMicro
                    && incomingHasTrustedMicroDistance
                    && !TextUtils.isEmpty(microManeuver)
                    && !TextUtils.isEmpty(highlightedMicroManeuver)
                    && !microManeuverFromFallbackMain
                    && !sameManeuverFamily(microManeuver, activeMicroManeuver);
            if (incomingDifferentTrustedMicro) {
                AppLog.line(app, "Navigation lane micro post-pass released by new micro: active="
                        + clean(activeMicroManeuver)
                        + " incoming=" + clean(microManeuver)
                        + " source=" + clean(source));
                clearMicroHintHold();
                clearLaneTxPostPassHold();
                activePostPassMicro = false;
            }
            boolean postPassMicroHolding = activePostPassMicro
                    && !lanePostPassBlockedByNearMain
                    && (lanePassed
                    || TextUtils.isEmpty(microManeuver)
                    || (sameManeuverFamily(microManeuver, activeMicroManeuver)
                    && !incomingHasTrustedMicroDistance));
            if (postPassMicroHolding) {
                microManeuver = activeMicroManeuver;
                microSource = true;
                routeRoadDisplayMicro = false;
                laneDistanceText = "";
                laneDebugDistance = "";
            }
            String microDistance = TextUtils.isEmpty(microManeuver) ? "" : laneDistanceText;
            String microDebugDistance = TextUtils.isEmpty(microManeuver) ? "" : laneDebugDistance;
            boolean microDebugSource = microSource || routeRoadDisplayMicro;
            boolean providerLaneMicro = providerVisualLaneSource(sourceLower) && microDebugSource;
            boolean microTxSource = microDebugSource;
            boolean microHoldTxAllowed = keepActiveMicroTxAllowed
                    ? activeMicroTxAllowed
                    : microTxSource;
            String microHintDistance = first(microDebugDistance, microDistance);
            boolean microHintAllowed = microDebugSource
                    && !TextUtils.isEmpty(microManeuver)
                    && !TextUtils.isEmpty(microHintDistance)
                    && microDistanceAllowedForTx(microManeuver, microHintDistance,
                    inferredForwardMicro, microDistanceSource);
            String hintDistance = microHintAllowed ? microHintDistance : mainDistance;
            laneHint = laneHintText(intent,
                    microHintAllowed ? microManeuver : cleanManeuver,
                    hintDistance,
                    sourceLower);
            if (routeRoadOnly && !TextUtils.isEmpty(explicitGrayRoad)) {
                laneHint = grayRoadHintText(explicitGrayRoad, intent, sourceLower, hintDistance);
            }
            String microHintSource = microDistanceSource + (inferredForwardMicro ? ":inferred_forward" : "");
            String microStatus = postPassMicroHolding
                    ? activeMicroStatusToPublish(now)
                    : microDecisionStatus(microManeuver, microDistance,
                    microDebugSource, microHintSource);
            if (!postPassMicroHolding) {
                rememberMicroHint(microManeuver, microDistance, microDebugDistance, microStatus,
                        microHintSource, now, false, microHoldTxAllowed);
            }
            rememberLaneHint(laneHint, source, now);
            state = state.withLaneHint(activeLaneHint, activeLaneSource, now)
                    .withNavigationDebug(imageManeuver, visibleRouteActionManeuver, microManeuver, microDebugDistance,
                            microStatus, explicitGrayRoad,
                            grayRoadSchemeText(explicitGrayRoad, intent, sourceLower), now);
            publishNavigationState();
            AppLog.line(app, "Navigation lane hint only: " + laneHint + " source=" + source);
            if (!AppSettings.navMicroManeuvers(app)) {
                AppLog.line(app, "Navigation ignored micro maneuver by setting: " + source);
                return;
            }
            if (NavigationModeSettings.isTbt(app)) {
                AppLog.line(app, "Navigation ignored micro maneuver in TBT: " + source);
                return;
            }
            String grayRoad = explicitGrayRoad;
            if (!TextUtils.isEmpty(grayRoad)) {
                if (stateRoundabout && !roundabout) clearStaleManeuverVisual("lane replaced roundabout " + source);
                String priorityLaneManeuver = priorityManeuverForLaneHold(displayManeuver, state.maneuver);
                String priorityLaneTxDistance = first(nonZeroDistance(mainDistance),
                        nonZeroDistance(maneuverDistance), nonZeroDistance(state.maneuverDistance));
                boolean lanePriorityActive = NavigationManeuverPolicy.priorityBlocksMicro(
                        priorityLaneManeuver, priorityLaneTxDistance);
                String stateGrayRoad = lanePriorityActive ? "" : grayRoad;
                String stateGrayScheme = lanePriorityActive ? "" : grayRoadSchemeText(grayRoad, intent, sourceLower);
                String grayKey = grayRoadKey(first(state.maneuver, cleanManeuver),
                        first(state.nextStreet, acceptedNext, acceptedCurrent, state.currentStreet));
                if (lanePriorityActive) {
                    clearGrayRoadHold();
                } else {
                    rememberLearnedRoadOptions(roadOptionKey(first(acceptedCurrent, state.currentStreet),
                            first(acceptedNext, state.nextStreet)), grayRoadMask(grayRoad), now, source);
                    rememberGrayRoad(grayRoad, grayKey, first(stateGrayScheme, laneDebugText(grayRoad, laneHint)),
                            source, now);
                }
                state = state.withLaneHint(activeLaneHint, activeLaneSource, now)
                        .withNavigationDebug(imageManeuver, visibleRouteActionManeuver, microManeuver, microDebugDistance,
                                microStatus, stateGrayRoad, stateGrayScheme, now);
                publishNavigationState();
                lastRouteGuidanceAt = now;
                cancelPendingInactive("lane " + source);
                boolean microAllowed = !postPassMicroHolding
                        && !lanePriorityActive && (!microTxSource
                        || (!TextUtils.isEmpty(microDistance)
                        && microDistanceAllowedForTx(microManeuver, microDistance,
                        inferredForwardMicro, microDistanceSource)));
                boolean sendingMicro = !postPassMicroHolding && microTxSource && microAllowed;
                boolean providerIconOnlyMicro = sendingMicro && providerLaneMicro;
                boolean microReplacesDistanceProgress = sendingMicro && !providerIconOnlyMicro;
                boolean routeActionIsMain = shouldPromoteRouteRoadActionAsMain(routeRoadOnly,
                        visibleRouteActionManeuver, displayManeuver, maneuverDistance,
                        routeRoadActionRejectedAsMain, intent);
                boolean routeActionAllowedAsMain = !lanePriorityActive
                        || visibleRouteActionManeuver.equals(priorityLaneManeuver);
                if (routeActionIsMain && routeActionAllowedAsMain
                        && !TextUtils.isEmpty(visibleRouteActionManeuver)
                        && !visibleRouteActionManeuver.equals(state.maneuver)) {
                    if (maneuverFamilyChanged(state.maneuver, visibleRouteActionManeuver)) {
                        clearClusterVisualHold();
                        resetManeuverProgress();
                        resetNavigationSendCache();
                    }
                    state = new NavigationState(true, finish, state.speedExceeded,
                            visibleRouteActionManeuver, maneuverLabel(visibleRouteActionManeuver, roundaboutExit),
                                    mainDistance, state.routeDistance, state.routeTime, state.arrivalTime,
                            first(acceptedCurrent, state.currentStreet),
                            first(acceptedNext, state.nextStreet), acceptedFinish,
                            state.speedLimit, state.currentSpeed, source, now)
                            .withLaneHint(activeLaneHint, activeLaneSource, now)
                            .withNavigationDebug(imageManeuver, visibleRouteActionManeuver, microManeuver, microDebugDistance,
                                    microStatus, stateGrayRoad, stateGrayScheme, now);
                    publishNavigationState();
                    AppLog.line(app, "Navigation route-road promoted priority main: "
                            + visibleRouteActionManeuver + " distance=" + clean(mainDistance)
                            + " source=" + source);
                }
                if (microTxSource && !microAllowed && !postPassMicroHolding) {
                    if (lanePriorityActive) {
                        AppLog.line(app, "Navigation micro held by priority: "
                                + clean(microManeuver) + " priority=" + priorityLaneManeuver
                                + " source=" + source);
                    } else {
                        AppLog.line(app, "Navigation micro held by distance threshold: "
                                + clean(microManeuver) + " distance=" + clean(microDistance)
                                + " max=" + AppSettings.navMicroMaxDistanceMeters(app)
                                + " source=" + source);
                    }
                }
                if (microDebugSource) {
                    microStatus = postPassMicroHolding
                            ? activeMicroStatusToPublish(now)
                            : lanePriorityActive
                            ? microMainHoldStatus(isRoundaboutManeuver(priorityLaneManeuver),
                            priorityLaneManeuver)
                            : sendingMicro
                            ? providerIconOnlyMicro
                            ? "отправлен значок, до/прогресс основной"
                            : microSentStatus(microDistance)
                            : microDecisionStatus(microManeuver, microDistance, true, microHintSource);
                    if (!TextUtils.isEmpty(microManeuver)) {
                        if (lanePriorityActive) {
                            clearMicroHintHold();
                        } else if (!postPassMicroHolding) {
                            rememberMicroHint(microManeuver, microDistance, microDebugDistance, microStatus,
                                    microHintSource, now, false, microHoldTxAllowed);
                        }
                    }
                    state = state.withNavigationDebug(imageManeuver, visibleRouteActionManeuver,
                            microManeuver, microDebugDistance, microStatus,
                            stateGrayRoad, stateGrayScheme, now);
                    publishNavigationState();
                }
                if (postPassMicroHolding) {
                    if (sendLaneTxPostPassIfActive(now, true)) {
                        AppLog.line(app, "Navigation lane post-pass held micro TX: "
                                + clean(activeMicroManeuver)
                                + " status=" + activeMicroStatusToPublish(now)
                                + " main=" + clean(state.maneuver)
                                + " mainDistance=" + clean(currentMainTxDistance())
                                + " source=" + source);
                        return;
                    }
                    AppLog.line(app, "Navigation lane post-pass debug only, no previous TX: "
                            + clean(activeMicroManeuver)
                            + " status=" + activeMicroStatusToPublish(now)
                            + " source=" + source);
                }
                String laneMainManeuver = firstMergeableManeuver(state.maneuver,
                        visibleRouteActionManeuver, cleanManeuver);
                if (TextUtils.isEmpty(laneMainManeuver)) {
                    laneMainManeuver = first(state.maneuver, visibleRouteActionManeuver, cleanManeuver);
                }
                String routeManeuverForTx = first(priorityLaneManeuver, laneMainManeuver,
                        state.maneuver, visibleRouteActionManeuver, cleanManeuver);
                String routeDistanceForTx = currentMainTxDistance();
                int routeProgressForTx = currentMainTxProgress(routeManeuverForTx, routeDistanceForTx);
                boolean keepActiveMicroOnRouteRoadGray = routeRoadOnly
                        && !sendingMicro
                        && !lanePriorityActive
                        && shouldTransmitActiveMicroToCluster();
                boolean activeProviderIconOnly = keepActiveMicroOnRouteRoadGray
                        && providerVisualLaneSource(activeMicroSource);
                boolean activeMicroReplacesDistanceProgress = keepActiveMicroOnRouteRoadGray
                        && !activeProviderIconOnly;
                String laneManeuver = lanePriorityActive
                        ? priorityLaneManeuver
                        : sendingMicro
                        ? microManeuver
                        : keepActiveMicroOnRouteRoadGray
                        ? activeMicroManeuver
                        : laneMainManeuver;
                String laneSendDistance = microReplacesDistanceProgress
                        ? microDistance
                        : activeMicroReplacesDistanceProgress
                        ? activeMicroDistanceToPublish()
                        : routeDistanceForTx;
                String grayRoadForTx = (sendingMicro || keepActiveMicroOnRouteRoadGray)
                        ? stableGrayRoadForLaneTx(grayRoad, laneManeuver, now)
                        : grayRoad;
                if (!grayRoadForTx.equals(grayRoad)) {
                    String stableGrayKey = grayRoadKey(laneManeuver,
                            first(state.nextStreet, acceptedNext, acceptedCurrent, state.currentStreet));
                    rememberGrayRoad(grayRoadForTx, stableGrayKey,
                            first(grayRoadSchemeText(grayRoadForTx, intent, sourceLower),
                                    laneDebugText(grayRoadForTx, laneHint)),
                            source + ":stable_micro_gray", now);
                }
                String currentClusterYellow = currentClusterYellowManeuver();
                boolean routeRoadMayForceYellow = lanePriorityActive
                        || sendingMicro
                        || keepActiveMicroOnRouteRoadGray;
                boolean forceLaneSnapshot = routeRoadOnly
                        && routeRoadMayForceYellow
                        && !TextUtils.isEmpty(laneManeuver)
                        && clusterYellowDiffersFrom(laneManeuver, currentClusterYellow);
                boolean forceLaneTx = !routeRoadOnly || forceLaneSnapshot;
                if (forceLaneSnapshot) {
                    AppLog.line(app, "Navigation forced lane TX over stale cluster: expected="
                            + laneManeuver + " cluster=" + currentClusterYellow
                            + " source=" + source);
                }
                boolean txByMicroArrowOnly = false;
                String txLaneDistance = laneSendDistance;
                int laneTxProgressBucket = (microReplacesDistanceProgress
                        || activeMicroReplacesDistanceProgress)
                        ? MICRO_TX_PROGRESS_BUCKET
                        : routeProgressForTx;
                if (!TextUtils.isEmpty(laneManeuver) && canMergeGrayRoad(laneManeuver)) {
                    if (txByMicroArrowOnly) {
                        if (routeRoadOnly && TextUtils.isEmpty(nonZeroDistance(txLaneDistance))) {
                            AppLog.line(app, "Navigation lane micro tx held without distance: "
                                    + laneManeuver + " source=" + source);
                        } else {
                            sendManeuverIfChanged(laneManeuver, distanceValue(txLaneDistance),
                                    isKm(txLaneDistance),
                                    laneTxProgressBucket,
                                    forceLaneTx);
                        }
                    } else if (routeRoadOnly && TextUtils.isEmpty(nonZeroDistance(laneSendDistance))) {
                        AppLog.line(app, "Navigation lane gray road held without distance: "
                                + laneManeuver + " gray=" + grayRoad + " source=" + source);
                    } else {
                        sendManeuverWithGrayRoadIfChanged(laneManeuver, grayRoadForTx,
                                distanceValue(laneSendDistance), isKm(laneSendDistance),
                                laneTxProgressBucket,
                                forceLaneTx);
                    }
                } else if (!TextUtils.isEmpty(laneManeuver)) {
                    if (routeRoadOnly && TextUtils.isEmpty(nonZeroDistance(laneSendDistance))) {
                        AppLog.line(app, "Navigation lane maneuver held without distance: "
                                + laneManeuver + " source=" + source);
                    } else {
                        sendManeuverIfChanged(laneManeuver, distanceValue(laneSendDistance),
                                isKm(laneSendDistance),
                                laneTxProgressBucket,
                                forceLaneTx);
                    }
                } else {
                    if (routeRoadOnly && TextUtils.isEmpty(nonZeroDistance(laneSendDistance))) {
                        AppLog.line(app, "Navigation gray road held without distance: "
                                + grayRoad + " source=" + source);
                    } else {
                        sendManeuverIfChanged(grayRoad, distanceValue(laneSendDistance),
                                isKm(laneSendDistance), laneTxProgressBucket, forceLaneTx);
                    }
                }
                if (sendingMicro && !TextUtils.isEmpty(microManeuver)) {
                    rememberLaneTxForPostPass(microManeuver, laneTxProgressBucket,
                            laneSendDistance, now);
                }
                if (providerIconOnlyMicro && !TextUtils.isEmpty(microManeuver)) {
                    AppLog.line(app, "Navigation lane micro icon TX with main distance/progress: "
                            + clean(microManeuver)
                            + " mainDistance=" + clean(routeDistanceForTx)
                            + " laneDistance=" + clean(microDistance)
                            + " progress=" + laneTxProgressBucket
                            + " gray=" + first(grayRoadForTx, "-")
                            + " source=" + source);
                }
                AppLog.line(app, "Navigation lane gray road merge: " + grayRoad
                        + " hint=" + laneHint + " raw=" + shortRaw(source, rawExtras(intent)));
                if (sendingMicro && !TextUtils.isEmpty(microManeuver)) {
                    // keep micro hint active through periodic micro overlay refresh
                }
                return;
            }
            String heldGrayRoad = grayRoadUntil > now ? activeGrayRoadId : "";
            if (providerVisualLaneSource(sourceLower) && !TextUtils.isEmpty(heldGrayRoad)) {
                AppLog.line(app, "Navigation cleared stale provider gray road without topology: held="
                        + clean(heldGrayRoad)
                        + " source=" + clean(source));
                clearGrayRoadHold();
                clearLearnedRoadOptions();
                heldGrayRoad = "";
            }
            AppLog.line(app, "Navigation lane gray road skipped: no topology held="
                    + first(heldGrayRoad, "-") + " " + shortRaw(source, rawExtras(intent)));
            state = state.withNavigationDebug(imageManeuver, visibleRouteActionManeuver,
                    microManeuver, microDebugDistance, microStatus,
                    heldGrayRoad, grayRoadLabel(heldGrayRoad), now);
            publishNavigationState();
            boolean providerMicroWithoutGray = providerVisualLaneSource(sourceLower)
                    && (microSource || routeRoadDisplayMicro);
            boolean microWithoutGrayCanSend = (microSource || routeRoadDisplayMicro)
                    && !TextUtils.isEmpty(microManeuver)
                    && !TextUtils.isEmpty(microDistance)
                    && microDistanceAllowedForTx(microManeuver, microDistance,
                    inferredForwardMicro, source)
                    && AppSettings.navMicroManeuvers(app)
                    && !NavigationModeSettings.isTbt(app)
                    && !isPriorityEventManeuver(state.maneuver);
            if (microWithoutGrayCanSend) {
                lastRouteGuidanceAt = now;
                cancelPendingInactive("lane micro " + source);
                String microTxDistance = providerMicroWithoutGray
                        ? nonZeroDistance(currentMainTxDistance())
                        : nonZeroDistance(microDistance);
                if (TextUtils.isEmpty(microTxDistance)) {
                    AppLog.line(app, "Navigation lane micro held without route distance: "
                            + clean(microManeuver) + " laneDistance=" + clean(microDistance)
                            + " source=" + source);
                } else {
                    boolean forceMicroTx = clusterYellowDiffersFrom(microManeuver,
                            currentClusterYellowManeuver());
                    int microTxProgress = providerMicroWithoutGray
                            ? currentMainTxProgress(first(state.maneuver, cleanManeuver), microTxDistance)
                            : MICRO_TX_PROGRESS_BUCKET;
                    sendManeuverIfChanged(microManeuver, distanceValue(microTxDistance),
                            isKm(microTxDistance), microTxProgress, forceMicroTx);
                    AppLog.line(app, (providerMicroWithoutGray
                            ? "Navigation lane micro icon TX without gray road: "
                            : "Navigation lane micro TX without gray road: ")
                            + clean(microManeuver) + " mainDistance=" + clean(mainDistance)
                            + " laneDistance=" + clean(microDistance)
                            + " txDistance=" + clean(microTxDistance)
                            + " heldGray=" + first(heldGrayRoad, "-")
                            + " source=" + source);
                    rememberLaneTxForPostPass(microManeuver, microTxProgress,
                            microTxDistance, now);
                }
            }
            return;
        } else if (roundabout) {
            clearLaneHintHold(true);
        } else if (laneGuidanceUntil > now && !finish
                && !sameManeuverFamily(state.maneuver, cleanManeuver)) {
            laneGuidanceUntil = 0L;
            if (stateRoundabout) clearStaleManeuverVisual("maneuver family changed after roundabout " + source);
        }
        if (!direct && lastDirectManeuverAt > 0L && now - lastDirectManeuverAt < 1800L
                && !TextUtils.isEmpty(state.maneuver)) {
            AppLog.line(app, "Navigation ignored weak maneuver source: " + source);
            return;
        }
        if (direct) lastDirectManeuverAt = now;
        microRestoreGeneration++;
        lastRouteGuidanceAt = now;
        waitingForRoute = false;
        routeStartedAt = 0L;
        routeLoadingFallbackGeneration++;
        cancelPendingInactive("maneuver " + source);

        if (maneuverFamilyChanged(state.maneuver, displayManeuver)) {
            boolean preserveMicro = shouldPreserveMicroAfterRoundaboutTransition(
                    state.maneuver, displayManeuver, now);
            clearClusterVisualHold();
            resetManeuverProgress();
            resetNavigationSendCache();
            clearLaneHintHold(preserveMicro);
        } else if (TextUtils.isEmpty(explicitGrayRoad)
                && sameFamilyDistanceResetForNewManeuver(state.maneuver, displayManeuver,
                state.maneuverDistance, maneuverDistance)
                && !TextUtils.isEmpty(activeGrayRoadId)) {
            AppLog.line(app, "Navigation cleared stale gray road on same-family distance reset: maneuver="
                    + clean(displayManeuver)
                    + " previous=" + clean(state.maneuverDistance)
                    + " incoming=" + clean(maneuverDistance)
                    + " gray=" + clean(activeGrayRoadId)
                    + " source=" + clean(source));
            clearGrayRoadHold();
            clearLearnedRoadOptions();
            clearLaneTxPostPassHold();
        }
        boolean standaloneFrame = finish || roundabout || isStandaloneManeuverFrame(displayManeuver);
        if (standaloneFrame) {
            clearLaneHintHold(false);
        }
        boolean highlightedMicroForOverlay = isUsableManeuver(highlightedMicroManeuver)
                && !standaloneFrame
                && !direct;
        boolean highlightedMicroDiffersFromMain = highlightedMicroForOverlay
                && !highlightedMicroManeuver.equals(displayManeuver);
        String highlightedMicroDistance = highlightedMicroForOverlay
                ? validMicroDistance(explicitMicroDistance,
                first(nonZeroDistance(maneuverDistance), nonZeroDistance(state.maneuverDistance)),
                highlightedMicroManeuver, !TextUtils.isEmpty(explicitMicroDistance), source)
                : "";
        String highlightedMicroDebugDistance = highlightedMicroForOverlay ? explicitMicroDistance : "";
        boolean highlightedMicroCanSend = highlightedMicroForOverlay
                && highlightedMicroDiffersFromMain
                && !TextUtils.isEmpty(highlightedMicroDistance)
                && microDistanceAllowed(highlightedMicroDistance, source)
                && !NavigationManeuverPolicy.priorityBlocksMicro(displayManeuver,
                first(nonZeroDistance(maneuverDistance), nonZeroDistance(state.maneuverDistance)))
                && !NavigationManeuverPolicy.mainBlocksMicro(displayManeuver,
                first(nonZeroDistance(maneuverDistance), nonZeroDistance(state.maneuverDistance)),
                highlightedMicroManeuver, sameManeuverFamily(highlightedMicroManeuver, displayManeuver))
                && AppSettings.navMicroManeuvers(app)
                && !NavigationModeSettings.isTbt(app);
        String highlightedMicroStatus = highlightedMicroForOverlay
                ? (!highlightedMicroDiffersFromMain
                ? "совпадает с основным"
                : highlightedMicroCanSend
                ? microSentStatus(highlightedMicroDistance)
                : microDecisionStatus(highlightedMicroManeuver, highlightedMicroDistance, true, source))
                : "";
        if (highlightedMicroForOverlay) {
            rememberMicroHint(highlightedMicroManeuver, highlightedMicroDistance, highlightedMicroDebugDistance,
                    highlightedMicroStatus, source, now, false, true);
            AppLog.line(app, "Navigation micro from lane highlight: "
                    + clean(highlightedMicroManeuver)
                    + " distance=" + clean(highlightedMicroDistance)
                    + " status=" + clean(highlightedMicroStatus)
                    + " main=" + clean(displayManeuver)
                    + " source=" + source);
        }
        boolean routeRoadStandaloneWithoutDistance = routeRoadOnly
                && standaloneFrame
                && TextUtils.isEmpty(nonZeroDistance(maneuverDistance));
        if (roundabout && routeRoadStandaloneWithoutDistance
                && lastDirectManeuverAt > 0L
                && now - lastDirectManeuverAt > ROUNDABOUT_EXIT_HOLD_MS) {
            clearStaleManeuverVisual("stale roundabout route-road poll " + source);
            return;
        }
        String displayDistance = maneuverDistance;
        if (routeRoadStandaloneWithoutDistance && displayManeuver.equals(state.maneuver)) {
            displayDistance = state.maneuverDistance;
        }
        String debugGrayRoad = standaloneFrame ? "" : explicitGrayRoad;
        String debugGrayScheme = grayRoadLabel(debugGrayRoad);
        state = new NavigationState(true, finish, state.speedExceeded,
                displayManeuver, maneuverLabel(displayManeuver, roundaboutExit), displayDistance,
                state.routeDistance, state.routeTime, state.arrivalTime,
                first(acceptedCurrent, state.currentStreet), first(acceptedNext, state.nextStreet),
                acceptedFinish, state.speedLimit, state.currentSpeed,
                source, now)
                .withLaneHint(activeLaneHint, activeLaneSource, now)
                .withNavigationDebug(imageManeuver, visibleRouteActionManeuver,
                        highlightedMicroForOverlay ? highlightedMicroManeuver : "",
                        highlightedMicroForOverlay ? highlightedMicroDebugDistance : "",
                        highlightedMicroForOverlay ? highlightedMicroStatus : "",
                        debugGrayRoad, debugGrayScheme, now);
        String fallbackGrayRoad = "";
        if (!laneGuidance && !standaloneFrame) {
            String roadOptionKey = roadOptionKey(state.currentStreet, state.nextStreet);
            boolean explicitGray = !TextUtils.isEmpty(explicitGrayRoad);
            fallbackGrayRoad = explicitGray ? explicitGrayRoad : "";
            if (!TextUtils.isEmpty(fallbackGrayRoad)) {
                if (explicitGray) {
                    String grayKey = grayRoadKey(displayManeuver,
                            first(state.nextStreet, acceptedNext, acceptedCurrent, state.currentStreet));
                    rememberLearnedRoadOptions(roadOptionKey, grayRoadMask(fallbackGrayRoad), now, source);
                    rememberGrayRoad(fallbackGrayRoad, grayKey, grayRoadLabel(fallbackGrayRoad), source, now);
                }
                state = state.withLaneHint(activeLaneHint, activeLaneSource, now)
                        .withNavigationDebug(imageManeuver, visibleRouteActionManeuver,
                                highlightedMicroForOverlay ? highlightedMicroManeuver : "",
                                highlightedMicroForOverlay ? highlightedMicroDebugDistance : "",
                                highlightedMicroForOverlay ? highlightedMicroStatus : "",
                                fallbackGrayRoad, grayRoadLabel(fallbackGrayRoad), now);
            }
        }
        AppLog.line(app, "Navigation gray fallback decision: maneuver=" + displayManeuver
                + " gray=" + first(fallbackGrayRoad, "-")
                + " lane=" + laneGuidance
                + " roundabout=" + roundabout
                + " finish=" + finish
                + " mode=" + NavigationModeSettings.label(app)
                + " finishOverride=" + finishDirectionShouldOverride()
                + " source=" + source);
        publishNavigationState();
        if (!laneGuidance) startManeuverTextHint(displayManeuver, roundaboutExit);
        int progressBucket = maneuverProgressBucket(displayManeuver,
                first(state.nextStreet, state.currentStreet), displayDistance);
        boolean sendHighlightedMicro = highlightedMicroCanSend;
        boolean sendActiveMicro = !standaloneFrame && shouldTransmitActiveMicroToCluster();
        boolean highlightedProviderIconOnly = sendHighlightedMicro
                && providerVisualLaneSource(sourceLower);
        boolean activeProviderIconOnly = sendActiveMicro
                && providerVisualLaneSource(activeMicroSource);
        boolean isMicroTx = sendHighlightedMicro || sendActiveMicro;
        boolean waitingMicroDistance = !sendHighlightedMicro
                && !sendActiveMicro
                && microHintUntil > now
                && !TextUtils.isEmpty(activeMicroManeuver)
                && TextUtils.isEmpty(activeMicroDistanceToPublish());
        String txManeuver;
        String txDistance;
        int txProgressBucket;
        if (sendHighlightedMicro) {
            txManeuver = highlightedMicroManeuver;
            txDistance = highlightedProviderIconOnly ? displayDistance : highlightedMicroDistance;
            txProgressBucket = highlightedProviderIconOnly ? progressBucket : MICRO_TX_PROGRESS_BUCKET;
        } else if (sendActiveMicro) {
            txManeuver = activeMicroManeuver;
            txDistance = activeProviderIconOnly ? displayDistance : activeMicroDistanceToPublish();
            txProgressBucket = activeProviderIconOnly ? progressBucket : MICRO_TX_PROGRESS_BUCKET;
        } else {
            txManeuver = displayManeuver;
            txDistance = displayDistance;
            txProgressBucket = progressBucket;
        }
        if (waitingMicroDistance) {
            AppLog.line(app, "Navigation wait micro distance, keep current tx: "
                    + clean(activeMicroManeuver) + " status=" + clean(activeMicroStatus));
        } else {
            boolean forceRouteSnapshot = routeRoadOnly && !state.clusterTx.contains(txManeuver);
            String txDistanceForTx = txDistance;
            int txProgressForTx = txProgressBucket;
            if (routeRoadStandaloneWithoutDistance) {
                AppLog.line(app, "Navigation route-road standalone held from TX: "
                        + displayManeuver + " source=" + source);
            } else if (!TextUtils.isEmpty(fallbackGrayRoad) && !isMicroTx) {
                    sendManeuverWithGrayRoadIfChanged(txManeuver, fallbackGrayRoad,
                            distanceValue(txDistance), isKm(txDistance), txProgressBucket, true);
            } else {
                sendManeuverIfChanged(txManeuver, distanceValue(txDistanceForTx),
                        isKm(txDistanceForTx), txProgressForTx,
                        laneGuidance || forceRouteSnapshot);
            }
        }
        if (sendHighlightedMicro) {
            rememberLaneTxForPostPass(highlightedMicroManeuver, txProgressBucket, txDistance, now);
        } else if (sendActiveMicro) {
            rememberLaneTxForPostPass(activeMicroManeuver, txProgressBucket, txDistance, now);
        }
        if (sendHighlightedMicro) {
            AppLog.line(app, "Navigation micro TX from lane highlight: "
                    + clean(highlightedMicroManeuver)
                    + " over main=" + clean(displayManeuver)
                    + (highlightedProviderIconOnly ? " txDistance=main txProgress=main" : "")
                    + " gray=" + first(grayRoadLabel(fallbackGrayRoad), "-")
                    + " source=" + source);
        }
        if (finish) startFinishHold();
        else sendConfiguredText();
        AppLog.line(app, (laneGuidance ? "Navigation micro maneuver: " : "Navigation maneuver: ")
                + state.summary());
    }

    private void applyGrayRoadOnly(String grayRoad, String source, String distanceText) {
        long now = System.currentTimeMillis();
        String maneuver = clean(state.maneuver);
        String grayKey = TextUtils.isEmpty(maneuver)
                ? "" : grayRoadKey(maneuver, first(state.nextStreet, state.currentStreet));
        rememberGrayRoad(grayRoad, grayKey, grayRoadLabel(grayRoad), source, now);
        rememberLearnedRoadOptions(roadOptionKey(state.currentStreet, state.nextStreet),
                grayRoadMask(grayRoad), now, source);
        state = state.withLaneHint(activeLaneHint, activeLaneSource, now);
        state = state.withNavigationDebug(state.mainManeuverId, state.routeActionId,
                state.microManeuverId, state.microDistance, state.microStatus,
                grayRoad, grayRoadLabel(grayRoad), now);
        publishNavigationState();
        lastRouteGuidanceAt = now;
        cancelPendingInactive("gray road " + source);
        if (state.active && !TextUtils.isEmpty(maneuver) && canMergeGrayRoad(maneuver)) {
            String distance = currentMainTxDistance();
            if (TextUtils.isEmpty(distance)) {
                AppLog.line(app, "Navigation gray road kept without main TX distance: "
                        + grayRoad + " source=" + clean(source));
            } else {
                sendManeuverWithGrayRoadIfChanged(maneuver, grayRoad,
                        distanceValue(distance), isKm(distance),
                        currentMainTxProgress(maneuver, distance), false);
            }
        }
        AppLog.line(app, "Navigation gray road only: " + grayRoad
                + " source=" + clean(source)
                + " stateManeuver=" + first(maneuver, "-"));
    }

    private void applyMainGuidanceDistanceTick(String distanceText, String source, long now) {
        String distance = nonZeroDistance(distanceText);
        if (TextUtils.isEmpty(distance)) return;
        String maneuver = first(state.maneuver, state.routeActionId, state.mainManeuverId);
        if (!state.active || state.finishReached || !isUsableManeuver(maneuver)) {
            AppLog.line(app, "Navigation ignored main guidance distance: "
                    + clean(distance) + " source=" + clean(source));
            return;
        }
        lastRouteGuidanceAt = now;
        waitingForRoute = false;
        routeStartedAt = 0L;
        cancelPendingInactive("guidance distance " + source);
        state = new NavigationState(true, false, state.speedExceeded,
                maneuver, maneuverLabel(maneuver), distance,
                state.routeDistance, state.routeTime, state.arrivalTime,
                state.currentStreet, state.nextStreet, state.finishStreet,
                state.speedLimit, state.currentSpeed, source, now)
                .withLaneHint(activeLaneHint, activeLaneSource, now)
                .withNavigationDebug(state.mainManeuverId, state.routeActionId,
                        state.microManeuverId, state.microDistance, state.microStatus,
                        state.grayRoadId, state.grayRoadScheme, now)
                .withClusterVisualText(state.clusterVisual, now)
                .withClusterTxText(state.clusterTx, now);
        publishNavigationState();
        int progress = maneuverProgressBucket(maneuver,
                first(state.nextStreet, state.currentStreet), distance);
        sendManeuverWithFallbackGray(maneuver, distance, progress, false);
        AppLog.line(app, "Navigation main guidance distance TX: "
                + clean(maneuver) + " distance=" + clean(distance)
                + " source=" + clean(source));
    }

    private void handleEta(Intent intent) {
        String routeDistance = first(metersDistanceText(first(text(intent, "remaining_meters"),
                        text(intent, "remaining_distance_meters"), text(intent, "distance_left_meters"))),
                text(intent, "edistance"), text(intent, "route_distance"),
                text(intent, "total_distance"), text(intent, "distance"), textByKeyPart(intent, "distance"));
        String routeTotalDistance = first(metersDistanceText(first(text(intent, "route_total_meters"),
                        text(intent, "total_route_meters"), text(intent, "route_total_distance_meters"),
                        text(intent, "route_total_initial_meters"))),
                text(intent, "route_total_len"), text(intent, "route_full_len"),
                text(intent, "route_total_distance"), text(intent, "full_route_distance"),
                text(intent, "routeTotalDistance"), text(intent, "totalRouteDistance"));
        String routeTime = first(text(intent, "route_time"), text(intent, "time_left"),
                text(intent, "duration"), text(intent, "remaining_time"), textByKeyPart(intent, "time_left"));
        String arrival = first(text(intent, "arrival_time"), text(intent, "arrival"),
                text(intent, "eta"));
        String finishStreet = finishTextFromIntent(intent);
        String currentStreet = etaCurrentStreetFromIntent(intent);
        String source = first(text(intent, "source"), ACTION_ETA);
        boolean routeIdChanged = incomingRouteIdChangesActiveRoute(intent);
        String maneuverDistance = mainManeuverDistanceFromIntent(intent, source, routeIdChanged);
        boolean finishPointChanged = rememberFinishPoint(intent, source);
        routeIdChanged |= syncActiveRouteId(intent, source);
        mergeEta(routeDistance, routeTime, arrival, finishStreet, currentStreet, source,
                maneuverDistance, finishPointChanged, packetClaimsActiveRoute(intent));
        if (!TextUtils.isEmpty(routeTotalDistance)) {
            rememberRouteTotalDistance(routeTotalDistance, System.currentTimeMillis());
            publishNavigationState();
        }
    }

    private void rememberRouteTotalDistance(String routeTotalDistance, long now) {
        String total = normalizeRouteTotalDistance(routeTotalDistance, state.routeDistance);
        if (TextUtils.isEmpty(total)) return;
        if (!TextUtils.isEmpty(activeRouteTotalDistance)) {
            float incomingMeters = distanceMetersForCompare(total);
            float activeMeters = distanceMetersForCompare(activeRouteTotalDistance);
            if (incomingMeters > 0f && activeMeters > 0f
                    && incomingMeters + Math.max(25f, activeMeters * 0.03f) < activeMeters) {
                total = activeRouteTotalDistance;
            }
        }
        if (total.equals(activeRouteTotalDistance)
                && clean(state.clusterTx).contains("route total=" + total)) {
            return;
        }
        activeRouteTotalDistance = total;
        state = state.withClusterTxText(withRouteTotalLine(state.clusterTx, total), now);
    }

    private boolean incomingRouteIdChangesActiveRoute(Intent intent) {
        String routeId = routeIdFromIntent(intent);
        return !TextUtils.isEmpty(routeId)
                && !TextUtils.isEmpty(activeRouteId)
                && !routeId.equals(activeRouteId);
    }

    private boolean syncActiveRouteId(Intent intent, String source) {
        String routeId = routeIdFromIntent(intent);
        if (TextUtils.isEmpty(routeId) || routeId.equals(activeRouteId)) return false;
        boolean changed = !TextUtils.isEmpty(activeRouteId);
        activeRouteId = routeId;
        activeRouteTotalDistance = "";
        if (changed) {
            clearRouteChangeVisualHold("route id changed " + clean(source));
            startRouteReroutingHold(clean(source) + "_route_id_changed");
        }
        AppLog.line(app, "Navigation route id changed: " + routeId
                + " source=" + clean(source));
        return changed;
    }

    private static String routeIdFromIntent(Intent intent) {
        String routeId = first(text(intent, "route_id"), text(intent, "routeId"),
                text(intent, "route_uuid"), text(intent, "routeUuid"));
        return clean(routeId);
    }

    private static String normalizeRouteTotalDistance(String routeTotalDistance, String remainingDistance) {
        String total = normalizeDistanceText(routeTotalDistance);
        String remaining = normalizeDistanceText(remainingDistance);
        float totalMeters = distanceMetersForCompare(total);
        float remainingMeters = distanceMetersForCompare(remaining);
        if (totalMeters > 0f && remainingMeters > 0f && totalMeters + 1f < remainingMeters) {
            return remaining;
        }
        return total;
    }

    private static float distanceMetersForCompare(String value) {
        String text = clean(value);
        if (TextUtils.isEmpty(text)) return 0f;
        Matcher matcher = NUMBER.matcher(text.replace(',', '.'));
        if (!matcher.find()) return 0f;
        float number = parseFloat(matcher.group());
        if (number <= 0f) return 0f;
        return isKm(text) ? number * 1000f : number;
    }

    private void sendEta(String value) {
        mergeEta(value, "", "", "", "", "manual_eta", "", false, false);
    }

    private String mainManeuverDistanceFromIntent(Intent intent, String source) {
        return mainManeuverDistanceFromIntent(intent, source, false);
    }

    private String mainManeuverDistanceFromIntent(Intent intent, String source, boolean routeIdChanged) {
        String distance = explicitManeuverDistanceFromIntent(intent);
        if (TextUtils.isEmpty(nonZeroDistance(distance))) return distance;
        if (!laneGuidanceDistancePacket(intent, source)) return distance;
        String laneDistance = explicitLaneDistanceFromIntent(intent);
        if (!TextUtils.isEmpty(nonZeroDistance(laneDistance))
                && sameMicroAndMainDistance(distanceMeters(distance), distanceMeters(laneDistance))) {
            if (routeIdChanged && hasMainManeuverDistanceMeters(intent)) {
                AppLog.line(app, "Navigation accepted new route main distance matching lane: distance="
                        + clean(distance) + " lane=" + clean(laneDistance)
                        + " source=" + clean(source));
                return distance;
            }
            AppLog.line(app, "Navigation ignored lane distance as main maneuver: distance="
                    + clean(distance) + " lane=" + clean(laneDistance)
                    + " source=" + clean(source));
            return "";
        }
        if (YandexCoreBridgeContract.SOURCE.equals(clean(source))
                && (bool(intent, "lane_guidance", false) || hasLaneData(intent))) {
            if (!coreBridgeMainDistancePacket(intent, distance)) {
                AppLog.line(app, "Navigation ignored guidance packet maneuver distance: distance="
                        + clean(distance) + " source=" + clean(source));
                return "";
            }
            AppLog.line(app, "Navigation accepted Core Bridge main distance with lane data: distance="
                    + clean(distance) + " source=" + clean(source));
        }
        return distance;
    }

    private static boolean coreBridgeMainDistancePacket(Intent intent, String distance) {
        if (TextUtils.isEmpty(nonZeroDistance(distance))) return false;
        String laneDistance = explicitLaneDistanceFromIntent(intent);
        if (!TextUtils.isEmpty(nonZeroDistance(laneDistance))
                && sameMicroAndMainDistance(distanceMeters(distance), distanceMeters(laneDistance))) {
            return false;
        }
        if (hasMainManeuverIdentity(intent)) return true;
        String action = intent == null ? "" : clean(intent.getAction());
        boolean routeSnapshot = KIA_ACTION_NAVI_ON.equals(action) || KIA_ACTION_ETA.equals(action);
        if (!routeSnapshot) return false;
        return hasRouteMetricExtras(intent) && hasMainManeuverDistanceMeters(intent);
    }

    private static boolean hasMainManeuverIdentity(Intent intent) {
        String raw = first(text(intent, "imageId"), text(intent, "image_id"),
                text(intent, "direction"), text(intent, "maneuver"), text(intent, "maneuver_action"),
                text(intent, "current_maneuver"), text(intent, "maneuver_type"));
        if (TextUtils.isEmpty(raw)) return false;
        String maneuver = normalizeManeuver(clean(raw), intent == null ? 0 : intent.getIntExtra("direction_lr", 0));
        return isUsableManeuver(maneuver) && !isPriorityEventManeuver(maneuver);
    }

    private static boolean hasMainManeuverDistanceMeters(Intent intent) {
        return !TextUtils.isEmpty(first(text(intent, "maneuver_distance_meters"),
                text(intent, "current_maneuver_distance_meters"),
                text(intent, "distance_to_maneuver_meters")));
    }

    private static boolean hasRouteMetricExtras(Intent intent) {
        return !TextUtils.isEmpty(first(text(intent, "remaining_meters"),
                text(intent, "remaining_distance_meters"),
                text(intent, "distance_left_meters"),
                text(intent, "route_distance"),
                text(intent, "remaining_distance"),
                text(intent, "edistance")))
                && !TextUtils.isEmpty(first(text(intent, "route_time"),
                text(intent, "time_left"),
                text(intent, "remaining_time"),
                text(intent, "arrival_time"),
                text(intent, "arrival")));
    }

    private void mergeEta(String routeDistance, String routeTime, String arrivalTime,
                          String finishStreet, String currentStreet, String source,
                          String maneuverDistance, boolean finishPointChanged,
                          boolean packetClaimsActiveRoute) {
        String rawDistanceText = clean(routeDistance);
        String distanceText = normalizeDistanceText(rawDistanceText);
        String acceptedManeuverDistance = normalizeDistanceText(maneuverDistance);
        String timeText = clean(routeTime);
        String arrivalText = clean(arrivalTime);
        String finishText = finishForState(finishStreet, finishPointChanged);
        String acceptedCurrent = etaStreetCandidate(currentStreet, state.currentStreet);
        String cleanSource = clean(source);
        if (finishSuppressesYandex(cleanSource, distanceText, timeText, finishText)) return;
        if (isRouteSuggestionSource(cleanSource)) {
            AppLog.line(app, "Navigation ignored ETA suggestion: " + cleanSource);
            return;
        }
        if (isCachedFinishSource(cleanSource)) {
            if (TextUtils.isEmpty(finishText)) {
                AppLog.line(app, "Navigation ignored cached ETA: " + cleanSource);
                return;
            }
            if (!state.active) {
                state = new NavigationState(false, false, state.speedExceeded,
                        state.maneuver, state.maneuverText, state.maneuverDistance,
                        state.routeDistance, state.routeTime, state.arrivalTime,
                        state.currentStreet, state.nextStreet, finishText,
                        state.speedLimit, state.currentSpeed, cleanSource, System.currentTimeMillis());
                publishNavigationState();
                AppLog.line(app, "Navigation cached finish stored: " + state.finishStreet);
                return;
            }
            state = new NavigationState(true, state.finishReached, state.speedExceeded,
                    state.maneuver, state.maneuverText, state.maneuverDistance,
                    state.routeDistance, state.routeTime, state.arrivalTime,
                    state.currentStreet, state.nextStreet,
                    finishText,
                    state.speedLimit, state.currentSpeed, cleanSource, System.currentTimeMillis());
            publishNavigationState();
            sendConfiguredText();
            AppLog.line(app, "Navigation cached finish merged: " + state.finishStreet);
            return;
        }
        long now = System.currentTimeMillis();
        if (isWeakOverviewRouteSource(cleanSource) && lastRouteFinishEtaAt > 0L
                && now - lastRouteFinishEtaAt < 5000L && !TextUtils.isEmpty(state.routeDistance)) {
            AppLog.line(app, "Navigation ignored weak ETA: " + cleanSource);
            return;
        }
        if (isRouteFinishSource(cleanSource)) lastRouteFinishEtaAt = now;
        boolean wasActive = state.active;
        if (!wasActive && packetClaimsActiveRoute
                && canResumeFromEtaPacket(cleanSource, distanceText, timeText, arrivalText, finishText)) {
            AppLog.line(app, "Navigation resumed active from ETA packet: " + cleanSource);
            setActive(true, cleanSource);
            wasActive = true;
        }
        if (!wasActive && !isRouteFinishSource(cleanSource) && !isGuidanceEtaSource(cleanSource)) {
            AppLog.line(app, "Navigation ignored inactive ETA: " + cleanSource);
            return;
        }
        lastRouteGuidanceAt = now;
        cancelPendingInactive("eta " + cleanSource);
        if (TextUtils.isEmpty(arrivalText)) {
            arrivalText = arrivalFromRouteTime(timeText);
        }
        if (TextUtils.isEmpty(distanceText) && TextUtils.isEmpty(timeText)
                && TextUtils.isEmpty(arrivalText) && TextUtils.isEmpty(finishText)) return;
        if (hasConfirmedRouteMetrics(distanceText, timeText, arrivalText)) {
            lastRouteMetricsAt = now;
        }
        boolean finishDistance = finishAllowedByDistance(rawDistanceText, distanceText);
        boolean finishConflict = finishContradictedByGuidance(distanceText,
                acceptedManeuverDistance, timeText);
        boolean finish = finishDistance && !finishConflict;
        if (finishDistance && finishConflict) {
            AppLog.line(app, "Navigation ignored ETA finish by contradictory guidance: route="
                    + first(distanceText, rawDistanceText)
                    + " maneuver=" + clean(acceptedManeuverDistance)
                    + " time=" + clean(timeText)
                    + " source=" + cleanSource);
        }
        String carryFinish = finishPointChanged ? "" : validFinishStreet(state.finishStreet);
        boolean routeChanged = wasActive && isRouteFinishSource(cleanSource)
                && routeLikelyChanged(distanceText, timeText);
        if (routeChanged) {
            clearRouteChangeVisualHold("route metrics changed " + cleanSource);
            waitingForRoute = true;
            routeStartedAt = now;
            routeLoadingMinUntil = now + ROUTE_WAIT_MIN_MS;
            lastDirectManeuverAt = 0L;
            resetNavigationTextCache();
            startRouteReroutingHold(cleanSource + "_route_metrics_changed", true);
            scheduleRouteLoadingFallback();
            AppLog.line(app, "Navigation route changed: " + cleanSource);
        }
        if (TextUtils.isEmpty(finishText) && isRouteFinishSource(cleanSource)
                && routeChanged && !TextUtils.isEmpty(carryFinish)) {
            AppLog.line(app, "Navigation finish kept for new route without text: " + cleanSource);
        }
        String previousManeuverDistance = state.maneuverDistance;
        state = new NavigationState(true, finish, state.speedExceeded,
                routeChanged ? "" : state.maneuver,
                routeChanged ? "" : state.maneuverText,
                routeChanged ? "" : first(acceptedManeuverDistance, state.maneuverDistance),
                first(distanceText, state.routeDistance), first(timeText, state.routeTime),
                first(arrivalText, state.arrivalTime), first(acceptedCurrent, state.currentStreet),
                state.nextStreet, first(finishText, carryFinish),
                state.speedLimit, state.currentSpeed, cleanSource, now);
        if (finish) {
            publishNavigationState();
            markFinishReached("auto_finish_eta_" + cleanSource);
            AppLog.line(app, "Navigation finish by ETA distance: "
                    + first(distanceText, rawDistanceText));
            return;
        }
        if (!routeChanged && !TextUtils.isEmpty(acceptedManeuverDistance)
                && !acceptedManeuverDistance.equals(previousManeuverDistance)
                && !TextUtils.isEmpty(state.maneuver) && !finishDirectionShouldOverride()) {
            sendManeuverWithFallbackGray(state.maneuver, acceptedManeuverDistance,
                    maneuverProgressBucket(state.maneuver, first(state.nextStreet, state.currentStreet),
                            acceptedManeuverDistance), false);
        }
        if (!routeChanged && staleManeuverBeyondRoute(state.routeDistance, state.maneuverDistance)) {
            clearStaleManeuverVisual("maneuver beyond route eta " + cleanSource);
        } else {
            publishNavigationState();
        }
        if (!wasActive) sender.sendActive(true);
        sendEtaTimeIfChanged(state.arrivalTime);
        if (finishDirectionShouldOverride()) {
            if (!sendDirectionToFinishIfNeeded(false)) sendFinishDirectionPlaceholder(false);
        }
        if (!state.loading() && !TextUtils.isEmpty(state.maneuver) && clusterVisualIsLoading()) {
            resendCurrentVisual();
        }
        if (!routeChanged) {
            refreshFallbackGrayRoadVisualIfNeeded(now);
        }

        String value = first(distanceText, state.routeDistance);
        if (TextUtils.isEmpty(value)) {
            if (finish) startFinishHold();
            else sendConfiguredText();
            return;
        }
        boolean km = value.toLowerCase(Locale.US).contains("км") || value.toLowerCase(Locale.US).contains("km");
        float distance = distanceValue(value);
        if (distance <= 0f) {
            if (finish) startFinishHold();
            else sendConfiguredText();
            return;
        }
        sendEtaIfChanged(distance, km);
        if (finish) startFinishHold();
        else sendConfiguredText();
        AppLog.line(app, "Navigation eta: " + state.summary());
    }

    private void handleSpeed(Intent intent) {
        String limit = first(text(intent, "speed_limit"), text(intent, "limit"), text(intent, "speedLimit"));
        String cameraLimit = first(text(intent, "camera_speed_limit"), text(intent, "first_camera_speed_limit_kmh"));
        String speedSign = text(intent, "speed_sign");
        String current = first(text(intent, "current_speed"), text(intent, "speed"), text(intent, "speed_value"));
        String source = first(text(intent, "source"), ACTION_SPEED);
        long now = System.currentTimeMillis();
        boolean yandexSource = isYandexSource(source);
        if (!TextUtils.isEmpty(current) && yandexSource) {
            lastYandexCurrentSpeedAt = now;
        }
        if (!TextUtils.isEmpty(limit) && yandexSource) {
            lastYandexRoadSpeedLimitAt = now;
        }
        String nextStreet = first(text(intent, "next_street"), text(intent, "nextStreet"),
                text(intent, "next_road"), text(intent, "nextRoad"), text(intent, "next_road_name"),
                text(intent, "nextRoadName"), text(intent, "next_street_name"), text(intent, "nextStreetName"),
                text(intent, "next_turn_street"), text(intent, "nextTurnStreet"));
        String currentStreet = first(text(intent, "current_street"), text(intent, "currentStreet"),
                text(intent, "current_road"), text(intent, "currentRoad"), text(intent, "current_road_name"),
                text(intent, "currentRoadName"), text(intent, "current_street_name"),
                text(intent, "currentStreetName"), text(intent, "position"));
        // Speed packets are partial updates. They may carry cached/geocoded finish text,
        // but the real destination label belongs to route/guidance packets.
        rememberFinishPoint(intent, source, false);
        boolean exceeded = bool(intent, "exceeded", state.speedExceeded);
        if (!TextUtils.isEmpty(speedSign) && speedSign.toLowerCase(Locale.US).contains("alarm")) {
            exceeded = true;
        }
        boolean cameraOnlyLimit = yandexSource
                && TextUtils.isEmpty(limit)
                && !TextUtils.isEmpty(cameraLimit);
        String nextSpeedLimit = first(limit, state.speedLimit);
        if (cameraOnlyLimit && yandexRoadSpeedLimitStale(now)) {
            nextSpeedLimit = "";
            AppLog.line(app, "Navigation kept camera limit out of road speed limit: camera="
                    + clean(cameraLimit) + " source=" + clean(source));
        }
        String effectiveCurrent = first(current, state.currentSpeed);
        String effectiveLimit = nextSpeedLimit;
        if (!TextUtils.isEmpty(effectiveCurrent) && !TextUtils.isEmpty(effectiveLimit)) {
            exceeded = speedNumber(effectiveCurrent) > speedNumber(effectiveLimit);
        }
        if (exceeded) startOverspeedTextWindow();
        else clearOverspeedTextWindow();
        String acceptedCurrent = isTrustedCurrentStreetSpeedSource(source)
                ? etaStreetCandidate(currentStreet, state.currentStreet) : "";
        String acceptedNext = "";
        String acceptedFinish = state.active ? validFinishStreet(state.finishStreet) : "";
        state = new NavigationState(state.active, state.finishReached, exceeded,
                state.maneuver, state.maneuverText, state.maneuverDistance,
                state.routeDistance, state.routeTime, state.arrivalTime,
                first(acceptedCurrent, state.currentStreet), state.nextStreet,
                acceptedFinish,
                nextSpeedLimit, first(current, state.currentSpeed),
                stableSourceForSpeed(source), now);
        if (!TextUtils.isEmpty(acceptedCurrent)) {
            AppLog.line(app, "Navigation speed current street: " + acceptedCurrent + " source=" + source);
        }
        if (staleManeuverBeyondRoute(state.routeDistance, state.maneuverDistance)) {
            clearStaleManeuverVisual("maneuver beyond route speed " + source);
        } else {
            publishNavigationState();
        }
        sendSpeed(limit);
    }

    private boolean yandexCurrentSpeedFresh(long now) {
        return lastYandexCurrentSpeedAt > 0L
                && now - lastYandexCurrentSpeedAt <= YANDEX_CURRENT_SPEED_HOLD_MS;
    }

    private boolean yandexRoadSpeedLimitStale(long now) {
        return lastYandexRoadSpeedLimitAt <= 0L
                || now - lastYandexRoadSpeedLimitAt > YANDEX_ROAD_SPEED_LIMIT_HOLD_MS;
    }

    private void sendSpeed(String value) {
        int kmh = speedNumber(value);
        if (kmh <= 0) return;
        long now = System.currentTimeMillis();
        boolean changed = kmh != lastSentSpeedLimit;
        state = new NavigationState(state.active, state.finishReached, state.speedExceeded,
                state.maneuver, state.maneuverText, state.maneuverDistance,
                state.routeDistance, state.routeTime, state.arrivalTime,
                state.currentStreet, state.nextStreet, state.finishStreet,
                String.valueOf(kmh), state.currentSpeed, state.source,
                now);
        publishNavigationState();
        if (changed) {
            lastSentSpeedLimit = kmh;
            speedLimitTextUntil = now + SPEED_LIMIT_TEXT_MS;
            sender.sendSpeedLimit(kmh);
            AppLog.line(app, "Navigation speed limit: " + kmh);
        }
        sendConfiguredText();
    }

    private void maybeResolveCurrentStreet(double lat, double lon, String source) {
        if (!validCoordinate(lat, lon)) return;
        long now = System.currentTimeMillis();
        String key = String.format(Locale.US, "%.5f,%.5f", lat, lon);
        if (key.equals(currentGeocodeKey) && now - lastCurrentGeocodeAt < 15000L) return;
        currentGeocodeKey = key;
        lastCurrentGeocodeAt = now;
        int generation = ++currentGeocodeGeneration;
        String cleanSource = clean(source);
        new Thread(() -> {
            String resolved = reverseCurrentStreet(lat, lon, cleanSource);
            if (TextUtils.isEmpty(resolved)) return;
            synchronized (NavigationFeature.this) {
                if (generation != currentGeocodeGeneration) return;
                state = new NavigationState(state.active, state.finishReached, state.speedExceeded,
                        state.maneuver, state.maneuverText, state.maneuverDistance,
                        state.routeDistance, state.routeTime, state.arrivalTime,
                        resolved, state.nextStreet, state.finishStreet,
                        state.speedLimit, state.currentSpeed,
                        first(cleanSource, "current_geocoder"), System.currentTimeMillis());
                publishNavigationState();
                if (state.active) sendConfiguredText();
                AppLog.line(app, "Navigation current street geocoded: " + resolved
                        + " " + key);
            }
        }, "KiaCurrentGeocoder").start();
    }

    private String reverseCurrentStreet(double lat, double lon, String source) {
        try {
            if (!Geocoder.isPresent()) return "";
            Geocoder geocoder = new Geocoder(app, Locale.getDefault());
            List<Address> addresses = geocoder.getFromLocation(lat, lon, 1);
            if (addresses == null || addresses.isEmpty()) return "";
            Address address = addresses.get(0);
            return normalizeStreetLabel(first(address.getThoroughfare()));
        } catch (Exception e) {
            AppLog.line(app, "Navigation current geocode failed: "
                    + e.getClass().getSimpleName() + " " + clean(source));
            return "";
        }
    }

    private static String finishTextFromIntent(Intent intent) {
        String validAddress = firstValidFinishAddress(
                text(intent, "finish_address"), text(intent, "finishAddress"),
                text(intent, "destination_address"), text(intent, "destinationAddress"),
                text(intent, "target_address"), text(intent, "targetAddress"),
                text(intent, "finish_full_address"), text(intent, "finishFullAddress"),
                text(intent, "destination_full_address"), text(intent, "destinationFullAddress"),
                text(intent, "target_full_address"), text(intent, "targetFullAddress"),
                text(intent, "to_address"), text(intent, "toAddress"),
                text(intent, "end_address"), text(intent, "endAddress"),
                text(intent, "address"), text(intent, "formatted_address"),
                text(intent, "formattedAddress"));
        String validStreet = firstValidFinishAddress(
                text(intent, "destination_street"), text(intent, "destinationStreet"),
                text(intent, "finish_street"), text(intent, "finishStreet"),
                text(intent, "target_street"), text(intent, "targetStreet"),
                text(intent, "to_street"), text(intent, "toStreet"),
                text(intent, "end_street"), text(intent, "endStreet"));
        String validName = firstValidFinishName(
                text(intent, "destination_name"), text(intent, "destinationName"),
                text(intent, "destination"), text(intent, "finish_name"),
                text(intent, "finishName"), text(intent, "target_name"),
                text(intent, "targetName"), text(intent, "target"),
                text(intent, "to_name"), text(intent, "toName"),
                text(intent, "end_name"), text(intent, "endName"),
                text(intent, "place_name"), text(intent, "placeName"),
                text(intent, "point_name"), text(intent, "pointName"),
                text(intent, "title"), text(intent, "name"));
        return first(validAddress, validStreet, validName);
    }

    private boolean rememberFinishPoint(Intent intent, String source) {
        return rememberFinishPoint(intent, source, true);
    }

    private boolean rememberFinishPoint(Intent intent, String source, boolean clearFinishText) {
        double lat = coordinate(intent, "finish_lat", "finishLat", "destination_lat", "destinationLat",
                "target_lat", "targetLat", "to_lat", "toLat", "end_lat", "endLat");
        double lon = coordinate(intent, "finish_lon", "finishLon", "finish_lng", "finishLng",
                "destination_lon", "destinationLon", "destination_lng", "destinationLng",
                "target_lon", "targetLon", "target_lng", "targetLng",
                "to_lon", "toLon", "to_lng", "toLng", "end_lon", "endLon", "end_lng", "endLng");
        if (!validCoordinate(lat, lon)) {
            double[] parsed = coordinatePair(first(text(intent, "finish_point"), text(intent, "finishPoint"),
                    text(intent, "destination_point"), text(intent, "destinationPoint"),
                    text(intent, "target_point"), text(intent, "targetPoint"),
                    text(intent, "finish"), text(intent, "destination"), text(intent, "target")));
            if (parsed != null) {
                lat = parsed[0];
                lon = parsed[1];
            }
        }
        if (!validCoordinate(lat, lon)) return false;
        float incomingRouteMeters = distanceMeters(routeDistanceFromIntent(intent));
        if (suspiciousFinishPointNearGps(lat, lon, incomingRouteMeters)) {
            AppLog.line(app, "Navigation ignored suspicious finish point near GPS: "
                    + String.format(Locale.US, "%.6f,%.6f", lat, lon)
                    + " route=" + clean(routeDistanceFromIntent(intent))
                    + " source=" + clean(source));
            return false;
        }
        return rememberFinishPoint(lat, lon, source, clearFinishText);
    }

    private boolean rememberFinishPoint(double lat, double lon, String source) {
        return rememberFinishPoint(lat, lon, source, true);
    }

    private boolean rememberFinishPoint(double lat, double lon, String source, boolean clearFinishText) {
        if (!validCoordinate(lat, lon)) return false;
        if (suspiciousFinishPointNearGps(lat, lon)) {
            AppLog.line(app, "Navigation ignored suspicious finish point near GPS: "
                    + String.format(Locale.US, "%.6f,%.6f", lat, lon)
                    + " route=" + clean(state.routeDistance)
                    + " source=" + clean(source));
            return false;
        }
        boolean changed = Double.isNaN(finishLatitude) || Double.isNaN(finishLongitude)
                || Math.abs(finishLatitude - lat) > 0.00001 || Math.abs(finishLongitude - lon) > 0.00001;
        finishLatitude = lat;
        finishLongitude = lon;
        if (changed) {
            lastSentFinishDirectionKey = "";
            staleFinishStreetAfterPointChange = clearFinishText ? validFinishStreet(state.finishStreet) : "";
            if (clearFinishText && !TextUtils.isEmpty(staleFinishStreetAfterPointChange)) {
                AppLog.line(app, "Navigation finish street kept until replacement: "
                        + staleFinishStreetAfterPointChange + " source=" + clean(source));
            }
            AppLog.line(app, "Navigation finish point: " + String.format(Locale.US, "%.6f,%.6f", lat, lon)
                    + " source=" + clean(source));
            if (finishDirectionShouldOverride() && state.active) {
                sendDirectionToFinishIfNeeded(true);
            }
        }
        maybeResolveFinishStreetFromPoint(source);
        return changed;
    }

    private boolean suspiciousFinishPointNearGps(double lat, double lon) {
        return suspiciousFinishPointNearGps(lat, lon, distanceMeters(state.routeDistance));
    }

    private boolean suspiciousFinishPointNearGps(double lat, double lon, float routeMeters) {
        if (routeMeters <= SUSPICIOUS_FINISH_POINT_ROUTE_METERS || !gpsPointKnown()) return false;
        Location from = new Location("kia_nav_current");
        from.setLatitude(lastGpsLatitude);
        from.setLongitude(lastGpsLongitude);
        Location to = new Location("kia_nav_finish_candidate");
        to.setLatitude(lat);
        to.setLongitude(lon);
        return from.distanceTo(to) <= SUSPICIOUS_FINISH_POINT_GPS_METERS;
    }

    private void clearFinishPoint() {
        finishLatitude = Double.NaN;
        finishLongitude = Double.NaN;
        resetFinishDirectionAnimation();
        finishGeocodeKey = "";
        lastFinishGeocodeAt = 0L;
        finishGeocodeGeneration++;
    }

    private void maybeResolveFinishStreetFromPoint(String source) {
        if (!state.active || state.finishReached || !finishPointKnown()) return;
        if (!TextUtils.isEmpty(validFinishStreet(state.finishStreet))
                && TextUtils.isEmpty(staleFinishStreetAfterPointChange)) return;
        long now = System.currentTimeMillis();
        String key = String.format(Locale.US, "%.5f,%.5f", finishLatitude, finishLongitude);
        if (key.equals(finishGeocodeKey) && now - lastFinishGeocodeAt < 60000L) return;
        finishGeocodeKey = key;
        lastFinishGeocodeAt = now;
        int generation = ++finishGeocodeGeneration;
        double lat = finishLatitude;
        double lon = finishLongitude;
        String cleanSource = clean(source);
        new Thread(() -> {
            String resolved = reverseFinishStreet(lat, lon);
            if (TextUtils.isEmpty(resolved)) {
                AppLog.line(app, "Navigation finish geocode empty: "
                        + String.format(Locale.US, "%.6f,%.6f", lat, lon)
                        + " source=" + cleanSource);
                return;
            }
            handler.post(() -> {
                synchronized (NavigationFeature.this) {
                    if (generation != finishGeocodeGeneration) return;
                    if (!state.active || state.finishReached || !finishPointKnown()) return;
                    if (Math.abs(finishLatitude - lat) > 0.00001
                            || Math.abs(finishLongitude - lon) > 0.00001) return;
                    if (!TextUtils.isEmpty(validFinishStreet(state.finishStreet))
                            && TextUtils.isEmpty(staleFinishStreetAfterPointChange)) return;
                    staleFinishStreetAfterPointChange = "";
                    state = new NavigationState(state.active, state.finishReached, state.speedExceeded,
                            state.maneuver, state.maneuverText, state.maneuverDistance,
                            state.routeDistance, state.routeTime, state.arrivalTime,
                            state.currentStreet, state.nextStreet, resolved,
                            state.speedLimit, state.currentSpeed,
                            first(cleanSource, "finish_geocoder"), System.currentTimeMillis());
                    publishNavigationState();
                    sendConfiguredText();
                    AppLog.line(app, "Navigation finish geocoded: " + resolved
                            + " source=" + cleanSource);
                }
            });
        }, "KiaFinishGeocoder").start();
    }

    private String reverseFinishStreet(double lat, double lon) {
        try {
            if (!Geocoder.isPresent()) return "";
            Geocoder geocoder = new Geocoder(app, Locale.getDefault());
            List<Address> addresses = geocoder.getFromLocation(lat, lon, 1);
            if (addresses == null || addresses.isEmpty()) return "";
            return finishTextFromAddress(addresses.get(0));
        } catch (Exception e) {
            AppLog.line(app, "Navigation finish geocode failed: " + e.getClass().getSimpleName());
            return "";
        }
    }

    private static String finishTextFromAddress(Address address) {
        if (address == null) return "";
        String street = clean(address.getThoroughfare());
        String house = clean(address.getSubThoroughfare());
        String streetAddress = streetWithHouse(street, house);
        String line = address.getMaxAddressLineIndex() >= 0 ? clean(address.getAddressLine(0)) : "";
        String addressText = firstValidFinishAddress(streetAddress, line);
        if (!TextUtils.isEmpty(addressText)) return addressText;
        String feature = meaningfulAddressFeature(address, street, house);
        return firstValidFinishName(clean(address.getPremises()), feature);
    }

    private static String streetWithHouse(String street, String house) {
        String cleanStreet = clean(street);
        String cleanHouse = clean(house);
        if (TextUtils.isEmpty(cleanStreet)) return "";
        if (TextUtils.isEmpty(cleanHouse)) return cleanStreet;
        String lowerStreet = cleanStreet.toLowerCase(Locale.US);
        String lowerHouse = cleanHouse.toLowerCase(Locale.US);
        if (lowerStreet.contains(lowerHouse)) return cleanStreet;
        return cleanStreet + " " + cleanHouse;
    }

    private static String meaningfulAddressFeature(Address address, String street, String house) {
        String feature = clean(address.getFeatureName());
        if (TextUtils.isEmpty(feature)) return "";
        if (feature.equalsIgnoreCase(clean(house))) return "";
        if (feature.matches("(?iu)^(д\\.?\\s*)?\\d+[a-zа-яё0-9/\\-]*$")) return "";
        if (!TextUtils.isEmpty(street) && sameStreet(feature, street)) return "";
        return feature;
    }

    private void publishNavigationState() {
        long now = System.currentTimeMillis();
        if (!NavigationModeSettings.isFinishDirection(app)) {
            boolean droppedFinishDirectionVisual = false;
            if (isFinishDirectionVisual(activeClusterVisual)) {
                activeClusterVisual = "";
                droppedFinishDirectionVisual = true;
            }
            if (isFinishDirectionVisual(state.clusterVisual)) {
                state = state.withClusterVisualText("", state.updatedAt);
                droppedFinishDirectionVisual = true;
            }
            if (droppedFinishDirectionVisual) {
                lastSentFinishDirectionKey = "";
            }
        }
        NavigationState stored = StateStore.navigation();
        if (stored != null && !TextUtils.isEmpty(stored.clusterTx)
                && !stored.clusterTx.equals(state.clusterTx)) {
            state = state.withClusterTxText(stored.clusterTx, state.updatedAt);
        }
        if (state.active && stored != null && stored.active
                && ((TextUtils.isEmpty(state.routeDistance) && !TextUtils.isEmpty(stored.routeDistance))
                || (TextUtils.isEmpty(state.routeTime) && !TextUtils.isEmpty(stored.routeTime))
                || (TextUtils.isEmpty(state.arrivalTime) && !TextUtils.isEmpty(stored.arrivalTime)))) {
            state = new NavigationState(state.active, state.finishReached, state.speedExceeded,
                    state.maneuver, state.maneuverText, state.maneuverDistance,
                    first(state.routeDistance, stored.routeDistance),
                    first(state.routeTime, stored.routeTime),
                    first(state.arrivalTime, stored.arrivalTime),
                    state.currentStreet, state.nextStreet, state.finishStreet,
                    state.speedLimit, state.currentSpeed, state.source, state.updatedAt)
                    .withLaneHint(state.laneHint, state.laneSource, state.updatedAt)
                    .withNavigationDebug(state.mainManeuverId, state.routeActionId,
                            state.microManeuverId, state.microDistance, state.microStatus,
                            state.grayRoadId, state.grayRoadScheme, state.updatedAt)
                    .withClusterVisualText(state.clusterVisual, state.updatedAt)
                    .withClusterTxText(state.clusterTx, state.updatedAt);
        }
        String routeTotalForTx = first(activeRouteTotalDistance, latestRouteTotalDistance(state.clusterTx));
        if (!TextUtils.isEmpty(routeTotalForTx)) {
            state = state.withClusterTxText(withRouteTotalLine(state.clusterTx,
                    routeTotalForTx), state.updatedAt);
        }
        if (state.active && !hasNavigationDebug(state) && hasNavigationDebug(stored)) {
            state = state.withNavigationDebug(stored.mainManeuverId, stored.routeActionId,
                    stored.microManeuverId, stored.microDistance,
                    stored.microStatus,
                    stored.grayRoadId, stored.grayRoadScheme,
                    state.updatedAt);
        }
        if (state.active && TextUtils.isEmpty(state.grayRoadId)
                && !TextUtils.isEmpty(activeGrayRoadId) && grayRoadUntil > now) {
            state = state.withNavigationDebug(state.mainManeuverId, state.routeActionId,
                    state.microManeuverId, state.microDistance, state.microStatus,
                    activeGrayRoadId, grayRoadLabel(activeGrayRoadId), state.updatedAt);
        }
        if (state.active && !TextUtils.isEmpty(activeMicroManeuver)
                && microHintUntil > now
                && shouldApplyActiveMicroForPublish()) {
            String debugDistance = activeMicroDebugDistanceToPublish();
            if (TextUtils.isEmpty(debugDistance)) {
                debugDistance = activeMicroDistanceToPublish();
            }
            String debugStatus = activeMicroStatusToPublish(now);
            if (!TextUtils.isEmpty(debugDistance) || !TextUtils.isEmpty(debugStatus)) {
                state = state.withNavigationDebug(state.mainManeuverId, state.routeActionId,
                        activeMicroManeuver, debugDistance, debugStatus,
                        state.grayRoadId, state.grayRoadScheme, state.updatedAt);
            }
        }
        if (!TextUtils.isEmpty(activeLaneHint) && laneHintUntil > now) {
            state = state.withLaneHint(activeLaneHint, activeLaneSource, state.updatedAt);
        }
        if (!TextUtils.isEmpty(activeEventHint) && eventHintUntil > now) {
            state = state.withEventHint(activeEventHint, activeEventSource, state.updatedAt);
        } else if (!TextUtils.isEmpty(state.eventHint)) {
            state = state.withEventHint("", "", state.updatedAt);
        }
        if (navigationRawUntil > now && hasActiveNavigationRaw()) {
            state = state.withNavigationRaw(activeLaneRaw, activeLanePosition,
                    activeRoadSchemeRaw, activeUpcomingRaw, state.updatedAt);
        } else if (!TextUtils.isEmpty(state.laneRaw) || !TextUtils.isEmpty(state.lanePosition)
                || !TextUtils.isEmpty(state.roadSchemeRaw) || !TextUtils.isEmpty(state.upcomingRaw)) {
            state = state.withNavigationRaw("", "", "", "", state.updatedAt);
        }
        if (!TextUtils.isEmpty(activeClusterVisual)) {
            state = state.withClusterVisualText(activeClusterVisual, state.updatedAt);
        }
        StateStore.setNavigation(app, state);
    }

    private static boolean hasNavigationDebug(NavigationState value) {
        if (value == null) return false;
        return !TextUtils.isEmpty(value.mainManeuverId)
                || !TextUtils.isEmpty(value.routeActionId)
                || !TextUtils.isEmpty(value.microManeuverId)
                || !TextUtils.isEmpty(value.microDistance)
                || !TextUtils.isEmpty(value.microStatus)
                || !TextUtils.isEmpty(value.grayRoadId)
                || !TextUtils.isEmpty(value.grayRoadScheme)
                || !TextUtils.isEmpty(value.laneRaw)
                || !TextUtils.isEmpty(value.lanePosition)
                || !TextUtils.isEmpty(value.roadSchemeRaw)
                || !TextUtils.isEmpty(value.upcomingRaw);
    }

    private static boolean isFinishDirectionVisual(String visual) {
        String text = clean(visual).toLowerCase(Locale.US);
        return text.contains("direction_to_finish") || text.contains("finish direction");
    }

    private void scheduleMicroRestore(String source) {
        int generation = ++microRestoreGeneration;
        long delayMs = Math.max(5, AppSettings.navMicroHoldSeconds(app)) * 1000L;
        handler.postDelayed(() -> {
            synchronized (NavigationFeature.this) {
                if (generation != microRestoreGeneration) return;
                if (!state.active || state.finishReached) return;
                if (NavigationModeSettings.isTbt(app) || NavigationModeSettings.isFinishDirection(app)) return;
                if (shouldTransmitActiveMicroToCluster()) {
                    scheduleMicroRestore(source);
                    return;
                }
            }
        }, delayMs);
    }

    private boolean microDistanceAllowed(String distanceText) {
        return NavigationManeuverPolicy.microDistanceAllowed(app, distanceText);
    }

    private boolean microDistanceAllowed(String distanceText, String source) {
        return microDistanceAllowed(distanceText);
    }

    private boolean microDistanceAllowedForTx(String maneuver, String distanceText,
                                              boolean inferredForward, String source) {
        if ((inferredForward || clean(source).contains("inferred_forward"))
                && "context_ra_forward".equals(clean(maneuver))) {
            float meters = distanceMeters(distanceText);
            if (meters > 0f && meters <= INFERRED_FORWARD_MICRO_MAX_METERS) return true;
        }
        return microDistanceAllowed(distanceText, source);
    }

    private String microDecisionStatus(String microManeuver, String distanceText, boolean microSource) {
        return microDecisionStatus(microManeuver, distanceText, microSource, "");
    }

    private String microDecisionStatus(String microManeuver, String distanceText,
                                       boolean microSource, String source) {
        if (!microSource || TextUtils.isEmpty(microManeuver)) return "нет подсказки";
        if (!AppSettings.navMicroManeuvers(app)) return "выключена";
        if (NavigationModeSettings.isTbt(app)) return "выключена в TBT";
        int maxMeters = AppSettings.navMicroMaxDistanceMeters(app);
        if (maxMeters <= 0) return "готова без порога";
        float meters = distanceMeters(distanceText);
        if (meters <= 0f) return "ждёт дистанцию";
        if (meters > maxMeters) return "ждёт " + maxMeters + " м";
        return "готова";
    }

    private String microSentStatus(String distanceText) {
        if ("сейчас".equalsIgnoreCase(clean(distanceText))) return "отправлена, сейчас";
        String distance = nonZeroDistance(distanceText);
        return TextUtils.isEmpty(distance) ? "отправлена" : "отправлена, " + distance;
    }

    private boolean shouldApplyActiveMicroForPublish() {
        if (activeMicroPostPassActive(System.currentTimeMillis())) return true;
        if (TextUtils.isEmpty(state.microManeuverId)) return true;
        if (!sameManeuverFamily(activeMicroManeuver, state.maneuver)) return true;
        if (activeMicroManeuver.equals(state.microManeuverId)) return true;
        return TextUtils.isEmpty(state.microDistance);
    }

    private String activeMicroDistanceToPublish() {
        String distance = clean(activeMicroDistance);
        return distance;
    }

    private String activeMicroDebugDistanceToPublish() {
        return clean(activeMicroDebugDistance);
    }

    private String activeMicroStatusToPublish(long now) {
        if (activeMicroPostPassUntil > now) {
            long remainingMs = activeMicroPostPassUntil - now;
            int seconds = Math.max(1, (int) Math.ceil(remainingMs / 1000d));
            return "проехали, удержание " + seconds + " сек";
        }
        return clean(activeMicroStatus);
    }

    private boolean activeMicroPostPassActive(long now) {
        return activeMicroPostPassUntil > now && !TextUtils.isEmpty(activeMicroManeuver);
    }

    private boolean activeMicroFromTrustedLaneDirection(long now) {
        return !TextUtils.isEmpty(activeMicroManeuver)
                && microHintUntil > now
                && trustedLaneDirectionSource(activeMicroSource);
    }

    private boolean activeMicroMatchesVisualGrayRoad(String visualGrayRoad) {
        int visualMask = grayRoadMask(visualGrayRoad);
        int microMask = maneuverRoadMask(activeMicroManeuver);
        return visualMask == 0 || microMask == 0 || (visualMask & microMask) == microMask;
    }

    private boolean shouldTransmitActiveMicroToCluster() {
        if (!AppSettings.navMicroManeuvers(app)) return false;
        if (NavigationModeSettings.isTbt(app) || NavigationModeSettings.isFinishDirection(app)) return false;
        if (!state.active || state.finishReached) return false;
        if (NavigationManeuverPolicy.priorityBlocksMicro(state.maneuver,
                state.maneuverDistance)) return false;
        if (mainManeuverBlocksLaneMicro(activeMicroManeuver, currentMainTxDistance())) return false;
        if (TextUtils.isEmpty(activeMicroManeuver)) return false;
        if (microHintUntil <= System.currentTimeMillis()) return false;
        if (!activeMicroTxAllowed) return false;
        String microDistance = activeMicroDistanceToPublish();
        if (TextUtils.isEmpty(microDistance)
                || !microDistanceAllowedForTx(activeMicroManeuver, microDistance,
                clean(activeMicroSource).contains("inferred_forward"), activeMicroSource)) {
            return false;
        }
        if (providerVisualLaneSource(activeMicroSource)) {
            return !TextUtils.isEmpty(nonZeroDistance(currentMainTxDistance()));
        }
        return true;
    }

    private void resendActiveMicroToCluster() {
        if (!shouldTransmitActiveMicroToCluster()) return;
        String laneDistance = activeMicroDistanceToPublish();
        String txDistance = nonZeroDistance(laneDistance);
        if (TextUtils.isEmpty(txDistance)) {
            AppLog.line(app, "Navigation active micro held without route distance: "
                    + clean(activeMicroManeuver)
                    + " laneDistance=" + clean(laneDistance));
            return;
        }
        long now = System.currentTimeMillis();
        boolean providerIconOnly = providerVisualLaneSource(activeMicroSource);
        if (providerIconOnly) {
            txDistance = nonZeroDistance(currentMainTxDistance());
            if (TextUtils.isEmpty(txDistance)) {
                AppLog.line(app, "Navigation active micro icon held without main distance: "
                        + clean(activeMicroManeuver)
                        + " laneDistance=" + clean(laneDistance));
                return;
            }
        }
        int txProgress = providerIconOnly
                ? currentMainTxProgress(first(state.maneuver, activeMicroManeuver), txDistance)
                : MICRO_TX_PROGRESS_BUCKET;
        String grayRoad = activeGrayRoadForLaneTx(activeMicroManeuver, now);
        grayRoad = stableGrayRoadForLaneTx(grayRoad, activeMicroManeuver, now);
        boolean force = clusterYellowDiffersFrom(activeMicroManeuver, currentClusterYellowManeuver());
        if (!TextUtils.isEmpty(grayRoad)) {
            sendManeuverWithGrayRoadIfChanged(activeMicroManeuver, grayRoad,
                    distanceValue(txDistance), isKm(txDistance), txProgress, force);
        } else {
            sendManeuverIfChanged(activeMicroManeuver, distanceValue(txDistance), isKm(txDistance),
                    txProgress, force);
        }
        rememberLaneTxForPostPass(activeMicroManeuver, grayRoad, txProgress, txDistance, now);
        AppLog.line(app, (providerIconOnly
                ? "Navigation active micro icon TX: "
                : "Navigation active micro TX: ") + clean(activeMicroManeuver)
                + " mainDistance=" + clean(currentMainTxDistance())
                + " laneDistance=" + clean(laneDistance)
                + " txDistance=" + clean(txDistance)
                + " gray=" + first(grayRoad, "-"));
    }

    private boolean handleLaneDistancePassed(String distanceText, long now, String source) {
        if (!laneDistancePassed(distanceText)) return false;
        boolean closeMain = nextMainManeuverCloseForMicroPostPass();
        boolean recentLaneTx = !TextUtils.isEmpty(lastLaneTxManeuver)
                && lastLaneTxAt > 0L
                && now - lastLaneTxAt <= LANE_TX_POST_PASS_PRE_TX_MAX_AGE_MS;
        String holdManeuver = recentLaneTx
                ? lastLaneTxManeuver
                : "";
        clearLaneDistanceHold();
        if (!TextUtils.isEmpty(activeMicroManeuver)) {
            if (closeMain) {
                AppLog.line(app, "Navigation lane post-pass micro cleared: next main="
                        + clean(state.maneuver)
                        + " distance=" + clean(currentMainTxDistance())
                        + " source=" + clean(source));
                clearMicroHintHold();
            } else {
                activeMicroDistance = "";
                activeMicroDebugDistance = "";
                activeMicroTxAllowed = false;
                activeMicroPostPassUntil = now + NavigationManeuverPolicy.microPostPassHoldMs(app);
                activeMicroStatus = activeMicroStatusToPublish(now);
                microHintUntil = Math.max(microHintUntil, activeMicroPostPassUntil);
                AppLog.line(app, "Navigation lane micro post-pass started: "
                        + clean(activeMicroManeuver)
                        + " status=" + activeMicroStatus
                        + " source=" + clean(source));
            }
        }
        if (closeMain) {
            clearLaneTxPostPassHold();
            AppLog.line(app, "Navigation lane TX post-pass skipped by close main: distance="
                    + clean(currentMainTxDistance())
                    + " source=" + clean(source));
            return true;
        }
        if (TextUtils.isEmpty(holdManeuver)) {
            AppLog.line(app, "Navigation lane TX post-pass skipped before TX: source="
                    + clean(source));
            return true;
        }
        startLaneTxPostPassHold(holdManeuver, now, source);
        return true;
    }

    private boolean nextMainManeuverCloseForMicroPostPass() {
        float meters = distanceMeters(first(nonZeroDistance(state.maneuverDistance),
                nonZeroDistance(currentMainTxDistance())));
        return meters > 0f && meters <= MICRO_POST_PASS_NEAR_MAIN_CLEAR_METERS;
    }

    private void rememberLaneTxForPostPass(String maneuver, int progressBucket, long now) {
        rememberLaneTxForPostPass(maneuver, activeGrayRoadForLaneTx(maneuver, now),
                progressBucket,
                first(nonZeroDistance(state.maneuverDistance), nonZeroDistance(lastLaneTxDistance)),
                now);
    }

    private void rememberLaneTxForPostPass(String maneuver, int progressBucket,
                                           String distanceText, long now) {
        rememberLaneTxForPostPass(maneuver, activeGrayRoadForLaneTx(maneuver, now),
                progressBucket, distanceText, now);
    }

    private void rememberLaneTxForPostPass(String maneuver, String grayRoad,
                                           int progressBucket, long now) {
        rememberLaneTxForPostPass(maneuver, grayRoad, progressBucket,
                first(nonZeroDistance(state.maneuverDistance), nonZeroDistance(lastLaneTxDistance)),
                now);
    }

    private void rememberLaneTxForPostPass(String maneuver, String grayRoad,
                                           int progressBucket, String distanceText, long now) {
        String cleanManeuver = clean(maneuver);
        if (!isUsableManeuver(cleanManeuver) || isPriorityEventManeuver(cleanManeuver)) return;
        if (NavigationModeSettings.isTbt(app) || NavigationModeSettings.isFinishDirection(app)) return;
        lastLaneTxManeuver = cleanManeuver;
        lastLaneTxGrayRoad = canMergeGrayRoad(cleanManeuver) ? clean(grayRoad) : "";
        lastLaneTxProgressBucket = normalizeProgressBucket(progressBucket);
        lastLaneTxDistance = nonZeroDistance(distanceText);
        lastLaneTxAt = now;
    }

    private String activeGrayRoadForLaneTx(String maneuver, long now) {
        if (!canMergeGrayRoad(maneuver)) return "";
        return grayRoadUntil > now ? clean(activeGrayRoadId) : "";
    }

    private String stableGrayRoadForLaneTx(String incomingGrayRoad, String maneuver, long now) {
        String incoming = clean(incomingGrayRoad);
        if (!canMergeGrayRoad(maneuver)) return incoming;
        int maneuverMask = maneuverRoadMask(maneuver);
        if (maneuverMask == 0) return incoming;
        String held = grayRoadUntil > now ? clean(activeGrayRoadId) : "";
        int heldMask = grayRoadMask(held);
        int incomingMask = grayRoadMask(incoming);
        if (heldMask != 0 && (heldMask & maneuverMask) == maneuverMask) {
            if (incomingMask == 0 || (incomingMask & maneuverMask) == 0
                    || (heldMask | incomingMask) == heldMask) {
                return held;
            }
        }
        if (incomingMask == 0) return grayRoadForManeuver(maneuver);
        if ((incomingMask & maneuverMask) == 0) return grayRoadFromMask(incomingMask | maneuverMask);
        return incoming;
    }

    private void startLaneTxPostPassHold(String maneuver, long now, String source) {
        String cleanManeuver = first(clean(maneuver), lastLaneTxManeuver);
        if (TextUtils.isEmpty(cleanManeuver)) return;
        if (!isUsableManeuver(cleanManeuver) || isPriorityEventManeuver(cleanManeuver)) return;
        rememberLaneTxForPostPass(cleanManeuver,
                first(lastLaneTxGrayRoad, activeGrayRoadForLaneTx(cleanManeuver, now)),
                lastLaneTxProgressBucket,
                first(nonZeroDistance(lastLaneTxDistance), nonZeroDistance(state.maneuverDistance)),
                now);
        laneTxPostPassUntil = now + NavigationManeuverPolicy.microPostPassHoldMs(app);
        if (!shouldAllowLaneTxPostPass(now)) {
            laneTxPostPassUntil = 0L;
            return;
        }
        AppLog.line(app, "Navigation lane TX post-pass debug hold only: " + cleanManeuver
                + " source=" + clean(source));
    }

    private boolean sendLaneTxPostPassIfActive(long now, boolean force) {
        if (!shouldAllowLaneTxPostPass(now)) return false;
        String currentYellow = currentClusterYellowManeuver();
        if (!TextUtils.isEmpty(currentYellow)
                && !currentYellow.equals(lastLaneTxManeuver)
                && !sameManeuverFamily(currentYellow, lastLaneTxManeuver)) {
            AppLog.line(app, "Navigation lane TX post-pass canceled by newer cluster: hold="
                    + clean(lastLaneTxManeuver) + " cluster=" + clean(currentYellow));
            clearLaneTxPostPassHold();
            return false;
        }
        int progressBucket = normalizeProgressBucket(lastLaneTxProgressBucket);
        String grayRoad = canMergeGrayRoad(lastLaneTxManeuver) ? clean(lastLaneTxGrayRoad) : "";
        String distanceText = first(nonZeroDistance(lastLaneTxDistance),
                nonZeroDistance(state.maneuverDistance));
        if (TextUtils.isEmpty(distanceText)) {
            AppLog.line(app, "Navigation lane TX post-pass canceled without route distance: "
                    + clean(lastLaneTxManeuver));
            clearLaneTxPostPassHold();
            return false;
        }
        boolean km = isKm(distanceText);
        if (TextUtils.isEmpty(grayRoad)) {
            clusterTx.sendManeuver(lastLaneTxManeuver, distanceValue(distanceText), km, progressBucket, force);
        } else {
            clusterTx.sendManeuverWithGrayRoad(lastLaneTxManeuver, grayRoad,
                    distanceValue(distanceText), km, progressBucket, force);
        }
        if (force) {
            AppLog.line(app, "Navigation lane TX post-pass hold: " + lastLaneTxManeuver
                    + " gray=" + first(grayRoad, "-")
                    + " distance=" + clean(distanceText)
                    + " progress=" + progressBucket);
        }
        return true;
    }

    private boolean shouldAllowLaneTxPostPass(long now) {
        return state.active
                && !state.finishReached
                && !routeReroutingActive(now)
                && laneTxPostPassUntil > now
                && !TextUtils.isEmpty(lastLaneTxManeuver)
                && lastLaneTxAt > 0L
                && now - lastLaneTxAt <= NavigationManeuverPolicy.microPostPassHoldMs(app)
                && AppSettings.navMicroManeuvers(app)
                && !NavigationModeSettings.isTbt(app)
                && !NavigationModeSettings.isFinishDirection(app)
                && !nextMainManeuverCloseForMicroPostPass()
                && !NavigationManeuverPolicy.priorityBlocksMicro(state.maneuver,
                state.maneuverDistance);
    }

    private void clearLaneTxPostPassHold() {
        lastLaneTxManeuver = "";
        lastLaneTxGrayRoad = "";
        lastLaneTxDistance = "";
        lastLaneTxProgressBucket = 9;
        lastLaneTxAt = 0L;
        laneTxPostPassUntil = 0L;
    }

    private String validMicroDistance(String distanceText, String mainDistance,
                                      String microManeuver, boolean microSource, String source) {
        String normalizedMainDistance = nonZeroDistance(mainDistance);
        String distance = nonZeroDistance(distanceText);
        if (!microSource || TextUtils.isEmpty(microManeuver)) return distance;
        if (TextUtils.isEmpty(distance)) {
            return "";
        }
        float microMeters = distanceMeters(distance);
        if (microMeters <= 0f) {
            return "";
        }
        float mainMeters = distanceMeters(normalizedMainDistance);
        if (sameMicroAndMainDistance(microMeters, mainMeters)) {
            if (clean(source).contains(":lana_fallback")) {
                return distance;
            }
            AppLog.line(app, "Navigation ignored micro equal main distance: "
                    + clean(microManeuver) + " micro=" + clean(distance)
                    + " main=" + clean(normalizedMainDistance)
                    + " source=" + clean(source));
            return "";
        }
        return distance;
    }

    private boolean laneMainDistanceLooksLikeMicro(String incomingMainDistance, String microDistance) {
        String incoming = nonZeroDistance(incomingMainDistance);
        String micro = nonZeroDistance(microDistance);
        if (TextUtils.isEmpty(incoming) || TextUtils.isEmpty(micro)) return false;
        float incomingMeters = distanceMeters(incoming);
        float microMeters = distanceMeters(micro);
        if (incomingMeters <= 0f || microMeters <= 0f) return false;
        float mainMeters = distanceMeters(currentMainTxDistance());
        if (mainMeters <= incomingMeters + MAIN_MANEUVER_MICRO_SEPARATION_METERS) return false;
        float tolerance = Math.max(40f, Math.min(120f, microMeters * 0.4f));
        return Math.abs(incomingMeters - microMeters) <= tolerance;
    }

    private static boolean sameFamilyDistanceResetForNewManeuver(String currentManeuver,
                                                                 String incomingManeuver,
                                                                 String previousDistance,
                                                                 String incomingDistance) {
        if (!sameManeuverFamily(currentManeuver, incomingManeuver)) return false;
        float previousMeters = distanceMeters(previousDistance);
        float incomingMeters = distanceMeters(incomingDistance);
        if (incomingMeters <= 0f) return false;
        if (previousMeters <= 0f) return incomingMeters <= MICRO_POST_PASS_NEAR_MAIN_CLEAR_METERS;
        if (previousMeters > MICRO_POST_PASS_NEAR_MAIN_CLEAR_METERS) return false;
        float jump = Math.max(25f, previousMeters * 0.6f);
        return incomingMeters >= previousMeters + jump
                && incomingMeters <= MICRO_POST_PASS_NEAR_MAIN_CLEAR_METERS;
    }

    private boolean shouldInferForwardMicro(Intent intent, String sourceLower,
                                            String microManeuver, boolean fallbackMain,
                                            String mainDistance, String microDistance,
                                            boolean routeRoadOnly) {
        String micro = nonZeroDistance(microDistance);
        if (TextUtils.isEmpty(micro)) return false;
        float microMeters = distanceMeters(micro);
        if (microMeters <= 0f || microMeters > INFERRED_FORWARD_MICRO_MAX_METERS) return false;
        boolean fallbackOrMain = fallbackMain || TextUtils.isEmpty(microManeuver)
                || sameManeuverFamily(microManeuver, state.maneuver);
        if (!fallbackOrMain) return false;
        if (!hasForwardLaneTruth(intent, sourceLower)) return false;
        if (laneHighlightHas(intent, "right") && !laneHighlightHas(intent, "straight")) return false;
        float mainMeters = distanceMeters(first(nonZeroDistance(mainDistance),
                nonZeroDistance(state.maneuverDistance)));
        if (mainMeters > 0f
                && mainMeters <= microMeters + MAIN_MANEUVER_MICRO_SEPARATION_METERS) {
            return false;
        }
        return routeRoadOnly || laneMicroSource(sourceLower) || hasLaneData(intent);
    }

    private static boolean hasForwardLaneTruth(Intent intent, String sourceLower) {
        return grayRoadHasStraight(intent, sourceLower)
                || laneHighlightHas(intent, "straight")
                || containsLaneToken(laneRawItemsText(intent), "straight")
                || containsLaneToken(visualLaneItemsText(intent), "straight");
    }

    private boolean mainManeuverBlocksLaneMicro(String microManeuver, String mainDistance) {
        String normalizedMainDistance = first(nonZeroDistance(mainDistance),
                nonZeroDistance(state.maneuverDistance));
        return NavigationManeuverPolicy.mainBlocksMicro(state.maneuver, normalizedMainDistance,
                microManeuver, sameManeuverFamily(microManeuver, state.maneuver));
    }

    private boolean routeRoadActionBlockedByCloseMain(String routeActionManeuver,
                                                      String incomingDistance) {
        if (!isUsableManeuver(routeActionManeuver)) return false;
        if (isPriorityEventManeuver(routeActionManeuver)) return false;
        String mainManeuver = clean(state.maneuver);
        if (!isUsableManeuver(mainManeuver)) return false;
        if (sameManeuverFamily(mainManeuver, routeActionManeuver)) return false;
        String mainDistance = first(nonZeroDistance(state.maneuverDistance),
                nonZeroDistance(incomingDistance), currentMainTxDistance());
        return NavigationManeuverPolicy.mainBlocksMicro(mainManeuver, mainDistance,
                routeActionManeuver, false);
    }

    private static boolean sameMicroAndMainDistance(float microMeters, float mainMeters) {
        if (microMeters <= 0f || mainMeters <= 0f) return false;
        float tolerance = Math.max(12f, Math.min(50f, mainMeters * 0.06f));
        return Math.abs(microMeters - mainMeters) <= tolerance;
    }

    private static String microMainHoldStatus(boolean roundabout, String mainManeuver) {
        if (roundabout || isRoundaboutManeuver(mainManeuver)) return "не перебивает круг";
        if (isStandaloneManeuverFrame(mainManeuver)) return "не перебивает отдельное событие";
        return "не перебивает основной манёвр";
    }

    private void rememberLaneHint(String hint, String source, long now) {
        activeLaneHint = clean(hint);
        activeLaneSource = clean(source);
        laneGuidanceUntil = now + (AppSettings.navMicroHoldSeconds(app) * 1000L);
        laneHintUntil = now + Math.max(LANE_HINT_OVERLAY_HOLD_MS, AppSettings.navMicroHoldSeconds(app) * 1000L);
    }

    private void rememberEventHint(String hint, String source, long now) {
        activeEventHint = clean(hint);
        activeEventSource = clean(source);
        eventHintUntil = now + EVENT_HINT_HOLD_MS;
    }

    private void rememberNavigationRawDebug(Intent intent, long now) {
        if (intent == null) return;
        String laneRaw = first(
                text(intent, "lane_topology"),
                text(intent, "lane_topology_json"),
                text(intent, "raw_lane_topology"),
                text(intent, "raw_lane_items"),
                text(intent, "lane_items"),
                text(intent, "ignored_raw_lane_items"),
                text(intent, "ignored_lane_items"));
        String lanePosition = navigationRawPosition(intent);
        String roadRaw = first(
                text(intent, "road_scheme_raw"),
                text(intent, "direction_sign_items"),
                text(intent, "raw_direction_sign_items"),
                text(intent, "route_road_options"),
                text(intent, "gray_road_options"),
                text(intent, "road_options"));
        String upcoming = first(
                text(intent, "upcoming_lane_signs"),
                text(intent, "upcoming_direction_signs"),
                text(intent, "upcoming_road_events"),
                text(intent, "events_json"));
        if (TextUtils.isEmpty(laneRaw) && TextUtils.isEmpty(lanePosition)
                && TextUtils.isEmpty(roadRaw) && TextUtils.isEmpty(upcoming)) {
            return;
        }
        activeLaneRaw = trimDebug(laneRaw);
        activeLanePosition = trimDebug(lanePosition);
        activeRoadSchemeRaw = trimDebug(roadRaw);
        activeUpcomingRaw = trimDebug(upcoming);
        navigationRawUntil = now + LANE_HINT_OVERLAY_HOLD_MS;
    }

    private boolean hasActiveNavigationRaw() {
        return !TextUtils.isEmpty(activeLaneRaw)
                || !TextUtils.isEmpty(activeLanePosition)
                || !TextUtils.isEmpty(activeRoadSchemeRaw)
                || !TextUtils.isEmpty(activeUpcomingRaw);
    }

    private static String navigationRawPosition(Intent intent) {
        StringBuilder out = new StringBuilder();
        appendRawPart(out, "dist", first(
                metersDistanceText(first(text(intent, "lane_distance_meters"),
                        text(intent, "micro_distance_meters"),
                        text(intent, "lane_sign_distance_meters"),
                        text(intent, "upcoming_lane_distance_meters"))),
                text(intent, "lane_distance"),
                text(intent, "micro_distance")));
        appendRawPart(out, "pos", first(
                text(intent, "lane_sign_position"),
                text(intent, "lane_position"),
                text(intent, "upcoming_lane_position")));
        appendRawPart(out, "highlight", first(
                text(intent, "lane_highlight"),
                text(intent, "lane_highlighted_direction"),
                text(intent, "highlighted_direction"),
                text(intent, "highlighted_directions"),
                text(intent, "recommended_lanes")));
        appendRawPart(out, "count", text(intent, "lane_count"));
        return out.toString();
    }

    private static void appendRawPart(StringBuilder out, String label, String value) {
        String cleanValue = clean(value);
        if (out == null || TextUtils.isEmpty(label) || TextUtils.isEmpty(cleanValue)) return;
        if (out.length() > 0) out.append(" | ");
        out.append(label).append('=').append(cleanValue);
    }

    private static String trimDebug(String value) {
        String text = clean(value);
        if (text.length() <= 500) return text;
        return text.substring(0, 497) + "...";
    }

    private void clearEventHintHold() {
        activeEventHint = "";
        activeEventSource = "";
        eventHintUntil = 0L;
    }

    private void rememberGrayRoad(String grayRoadId, String grayRoadKey,
                                  String hint, String source, long now) {
        rememberLaneHint(hint, source, now);
        activeGrayRoadId = clean(grayRoadId);
        activeGrayRoadKey = clean(grayRoadKey);
        grayRoadUntil = laneHintUntil;
    }

    private void rememberRoadHint(String hint, String source, long now) {
        activeLaneHint = clean(hint);
        activeLaneSource = clean(source);
        laneHintUntil = now + LANE_HINT_OVERLAY_HOLD_MS;
    }

    private void applyLaneDistanceOnly(Intent intent, String distance, String source,
                                       String sourceLower, long now) {
        boolean lanePassed = handleLaneDistancePassed(distance, now, source);
        String cleanDistance = lanePassed ? "" : nonZeroDistance(distance);
        if (!TextUtils.isEmpty(cleanDistance)) {
            rememberLaneDistance(cleanDistance, now);
        }
        String shownDistance = activeLaneDistanceToPublish(now);
        String heldGrayRoad = first(grayRoadUntil > now ? activeGrayRoadId : "",
                state.grayRoadId);
        String visualGrayRoad = visualLaneGrayRoadManeuver(intent);
        String visualMicro = visualLaneHighlightedManeuver(intent);
        boolean visualMicroInferred = false;
        if (TextUtils.isEmpty(visualMicro)) {
            visualMicro = visualLaneSingleManeuver(intent, sourceLower);
            visualMicroInferred = !TextUtils.isEmpty(visualMicro);
        }
        boolean visualMicroFound = !TextUtils.isEmpty(visualMicro);
        boolean priorityManeuverActive = NavigationManeuverPolicy.priorityBlocksMicro(
                state.maneuver, state.maneuverDistance);
        if (visualMicroFound && priorityManeuverActive) {
            AppLog.line(app, "Navigation ignored lane distance micro by priority: priority="
                    + clean(state.maneuver) + " visual=" + clean(visualMicro)
                    + " source=" + clean(source));
            visualMicro = "";
            visualMicroFound = false;
            clearMicroHintHold();
        }
        if (visualMicroFound && visualMicroInferred
                && weakVisualLaneSingleSource(sourceLower)
                && activeMicroFromTrustedLaneDirection(now)
                && !TextUtils.isEmpty(activeMicroManeuver)
                && !sameManeuverFamily(visualMicro, activeMicroManeuver)) {
            AppLog.line(app, "Navigation ignored weak single lane over active highlight: active="
                    + clean(activeMicroManeuver) + " incoming=" + clean(visualMicro)
                    + " activeSource=" + clean(activeMicroSource)
                    + " source=" + clean(source));
            visualMicro = "";
            visualMicroFound = false;
        }
        if (visualMicroFound && activeMicroFromTrustedLaneDirection(now)
                && !sameManeuverFamily(visualMicro, activeMicroManeuver)) {
            AppLog.line(app, "Navigation replaced stale active lane micro by visual lane highlight: active="
                    + clean(activeMicroManeuver) + " visual=" + clean(visualMicro)
                    + " source=" + clean(source));
            clearMicroHintHold();
        }
        if (!visualMicroFound && activeMicroFromTrustedLaneDirection(now)
                && visualGrayRoadCanClearActiveMicro(sourceLower)
                && !activeMicroMatchesVisualGrayRoad(visualGrayRoad)) {
            AppLog.line(app, "Navigation cleared stale active lane micro by visual gray road: active="
                    + clean(activeMicroManeuver)
                    + " visualGray=" + first(visualGrayRoad, "-")
                    + " source=" + clean(source));
            clearMicroHintHold();
        }
        boolean postPassMicroHolding = activeMicroPostPassActive(now)
                && !nextMainManeuverCloseForMicroPostPass();
        boolean visualRepeatsPostPass = visualMicroFound
                && postPassMicroHolding
                && sameManeuverFamily(visualMicro, activeMicroManeuver);
        boolean trustedActiveMicro = !visualMicroFound
                && (activeMicroFromTrustedLaneDirection(now) || postPassMicroHolding);
        if (!TextUtils.isEmpty(visualGrayRoad)) {
            String completeLaneGray = activeCompleteLaneGrayRoad(now);
            if (completeLaneGraySource(sourceLower) && visualMicroFound) {
                heldGrayRoad = visualGrayRoad;
                rememberCompleteLaneGrayRoad(heldGrayRoad, now);
            } else if (trustedActiveMicro) {
                heldGrayRoad = mergeGrayRoads(visualGrayRoad,
                        grayRoadFromMask(maneuverRoadMask(activeMicroManeuver)));
            } else if (narrowLaneGraySource(sourceLower)
                    && grayRoadContains(completeLaneGray, visualGrayRoad)) {
                heldGrayRoad = completeLaneGray;
            } else if (visualMicroFound) {
                heldGrayRoad = visualGrayRoad;
            } else {
                heldGrayRoad = visualGrayRoad;
            }
            rememberVisualGrayRoad(heldGrayRoad, now);
        }
        String microManeuver = postPassMicroHolding
                && (visualRepeatsPostPass || !visualMicroFound)
                ? activeMicroManeuver
                : trustedActiveMicro
                ? activeMicroManeuver
                : visualMicroFound
                ? visualMicro
                : "";
        String microDistance = shownDistance;
        String microStatus = TextUtils.isEmpty(shownDistance)
                ? "нет lane дистанции" : "только дистанция lane";
        boolean holdingPostPassManeuver = postPassMicroHolding
                && !TextUtils.isEmpty(microManeuver)
                && sameManeuverFamily(microManeuver, activeMicroManeuver);
        if (holdingPostPassManeuver) {
            microDistance = "";
            microStatus = activeMicroStatusToPublish(now);
        } else if (visualMicroFound) {
            String rememberedStatus = microDecisionStatus(microManeuver, shownDistance, true, source);
            rememberMicroHint(microManeuver, shownDistance, shownDistance,
                    rememberedStatus,
                    source + (visualMicroInferred ? ":visual_lane_single" : ":visual_lane_highlight"),
                    now, false, true);
            microDistance = activeMicroDebugDistanceToPublish();
            microStatus = activeMicroStatus;
        } else if (!trustedActiveMicro && !visualMicroFound && TextUtils.isEmpty(microManeuver)) {
            clearMicroHintHold();
            microStatus = "нет lane подсветки";
            microDistance = "";
        } else if (!trustedActiveMicro && !TextUtils.isEmpty(microManeuver)) {
            clearMicroHintHold();
            microStatus = "по умолчанию прямо";
        } else if (!TextUtils.isEmpty(microManeuver) && !TextUtils.isEmpty(shownDistance)) {
            activeMicroDistance = shownDistance;
            activeMicroDebugDistance = shownDistance;
            activeMicroStatus = microDecisionStatus(microManeuver, shownDistance,
                    true, activeMicroSource);
            microHintUntil = now + laneHoldMs();
            microDistance = activeMicroDebugDistanceToPublish();
            microStatus = activeMicroStatus;
        }
        rememberLaneHint(laneDistanceOnlyHint(shownDistance), source, now);
        state = state.withLaneHint(activeLaneHint, activeLaneSource, now)
                .withNavigationDebug(state.mainManeuverId, state.routeActionId,
                        microManeuver, microDistance, microStatus,
                        heldGrayRoad, grayRoadSchemeText(heldGrayRoad, intent, sourceLower), now);
        publishNavigationState();
        if (holdingPostPassManeuver && sendLaneTxPostPassIfActive(now, true)) {
            AppLog.line(app, "Navigation lane distance post-pass held micro TX: "
                    + clean(activeMicroManeuver)
                    + " status=" + activeMicroStatusToPublish(now)
                    + " source=" + source);
            return;
        }
        if (!TextUtils.isEmpty(microManeuver) && shouldTransmitActiveMicroToCluster()) {
            resendActiveMicroToCluster();
        }
        AppLog.line(app, "Navigation lane distance only: distance="
                + first(shownDistance, "-")
                + " micro=" + first(microManeuver, "-")
                + " gray=" + first(heldGrayRoad, "-")
                + " visual=" + first(visualMicro, "-")
                + " visualGray=" + first(visualGrayRoad, "-")
                + " source=" + source);
    }

    private String laneDistanceOnlyHint(String distance) {
        String cleanDistance = nonZeroDistance(distance);
        return TextUtils.isEmpty(cleanDistance) ? "lane distance" : "lane distance, " + cleanDistance;
    }

    private void rememberLaneDistance(String distance, long now) {
        String cleanDistance = nonZeroDistance(distance);
        if (TextUtils.isEmpty(cleanDistance)) return;
        activeLaneDistance = cleanDistance;
        laneDistanceUntil = now + laneHoldMs();
    }

    private void rememberVisualGrayRoad(String grayRoadId, long now) {
        String cleanGray = clean(grayRoadId);
        if (TextUtils.isEmpty(cleanGray)) return;
        activeGrayRoadId = cleanGray;
        activeGrayRoadKey = grayRoadKey(first(state.maneuver, state.routeActionId),
                first(state.nextStreet, state.currentStreet));
        grayRoadUntil = now + laneHoldMs();
    }

    private void rememberCompleteLaneGrayRoad(String grayRoadId, long now) {
        String cleanGray = clean(grayRoadId);
        if (TextUtils.isEmpty(cleanGray)) return;
        activeCompleteLaneGrayRoad = cleanGray;
        completeLaneGrayUntil = now + laneHoldMs();
    }

    private String activeCompleteLaneGrayRoad(long now) {
        if (completeLaneGrayUntil <= now) return "";
        return clean(activeCompleteLaneGrayRoad);
    }

    private void clearCompleteLaneGrayRoad() {
        activeCompleteLaneGrayRoad = "";
        completeLaneGrayUntil = 0L;
    }

    private void clearGrayRoadHold() {
        activeGrayRoadId = "";
        activeGrayRoadKey = "";
        grayRoadUntil = 0L;
        clearCompleteLaneGrayRoad();
    }

    private void clearLearnedRoadOptions() {
        learnedRoadKey = "";
        learnedRoadMask = 0;
        learnedRoadUntil = 0L;
    }

    private static boolean grayRoadContains(String fullGrayRoad, String partGrayRoad) {
        int fullMask = grayRoadMask(fullGrayRoad);
        int partMask = grayRoadMask(partGrayRoad);
        return fullMask != 0 && partMask != 0 && (fullMask & partMask) == partMask;
    }

    private String activeLaneDistanceToPublish(long now) {
        if (laneDistanceUntil <= now) return "";
        return nonZeroDistance(activeLaneDistance);
    }

    private long laneHoldMs() {
        return Math.max(LANE_HINT_OVERLAY_HOLD_MS,
                AppSettings.navMicroHoldSeconds(app) * 1000L);
    }

    private void rememberMicroHint(String maneuver, String distance, String status, long now) {
        rememberMicroHint(maneuver, distance, distance, status, now, true);
    }

    private void rememberMicroHint(String maneuver, String distance, String debugDistance, String status,
                                  long now, boolean clearOnEmpty) {
        rememberMicroHint(maneuver, distance, debugDistance, status, now, clearOnEmpty, true);
    }

    private void rememberMicroHint(String maneuver, String distance, String debugDistance, String status,
                                  long now, boolean clearOnEmpty, boolean txAllowed) {
        rememberMicroHint(maneuver, distance, debugDistance, status, "", now, clearOnEmpty, txAllowed);
    }

    private void rememberMicroHint(String maneuver, String distance, String debugDistance, String status,
                                  String source, long now, boolean clearOnEmpty, boolean txAllowed) {
        String cleanManeuver = clean(maneuver);
        String cleanDistance = clean(distance);
        String cleanDebugDistance = clean(debugDistance);
        if (!clearOnEmpty && TextUtils.isEmpty(cleanDistance)
                && activeMicroPostPassActive(now)
                && (TextUtils.isEmpty(cleanManeuver)
                || sameManeuverFamily(cleanManeuver, activeMicroManeuver))) {
            AppLog.line(app, "Navigation kept lane micro post-pass over refresh: "
                    + clean(activeMicroManeuver)
                    + " incoming=" + first(cleanManeuver, "-")
                    + " distance=" + first(cleanDistance, "-")
                    + " status=" + activeMicroStatusToPublish(now));
            return;
        }
        if (TextUtils.isEmpty(cleanManeuver)) {
            if (!clearOnEmpty) {
                return;
            }
            clearMicroHintHold();
            return;
        }
        if (!clearOnEmpty && TextUtils.isEmpty(cleanDistance)
                && !TextUtils.isEmpty(activeMicroManeuver)
                && microHintUntil > now
                && !TextUtils.isEmpty(activeMicroDistanceToPublish())) {
            AppLog.line(app, "Navigation kept active lane micro over empty distance: "
                    + clean(activeMicroManeuver) + " distance=" + clean(activeMicroDistance)
                    + " incoming=" + cleanManeuver);
            return;
        }
        activeMicroManeuver = cleanManeuver;
        activeMicroDistance = cleanDistance;
        activeMicroDebugDistance = TextUtils.isEmpty(cleanDebugDistance) ? cleanDistance : cleanDebugDistance;
        activeMicroStatus = clean(status);
        activeMicroSource = clean(source);
        activeMicroTxAllowed = txAllowed && !TextUtils.isEmpty(cleanDistance);
        activeMicroPostPassUntil = 0L;
        microHintUntil = now + laneHoldMs();
    }

    private void clearMicroHintHold() {
        activeMicroManeuver = "";
        activeMicroDebugDistance = "";
        activeMicroDistance = "";
        activeMicroStatus = "";
        activeMicroSource = "";
        activeMicroTxAllowed = true;
        activeMicroPostPassUntil = 0L;
        microHintUntil = 0L;
    }

    private void clearLaneDistanceHold() {
        activeLaneDistance = "";
        laneDistanceUntil = 0L;
        clearCompleteLaneGrayRoad();
    }

    private void clearLaneHintHold() {
        clearLaneHintHold(false);
    }

    private void clearLaneHintHold(boolean keepMicroHint) {
        activeLaneHint = "";
        activeLaneSource = "";
        clearGrayRoadHold();
        clearLearnedRoadOptions();
        clearLaneTxPostPassHold();
        clearLaneDistanceHold();
        if (!keepMicroHint) {
            clearMicroHintHold();
        }
        laneGuidanceUntil = 0L;
        laneHintUntil = 0L;
        grayRoadUntil = 0L;
    }

    private void clearClusterVisualHold() {
        activeClusterVisual = "";
    }

    private void clearRouteChangeVisualHold(String reason) {
        clearLaneHintHold();
        clearEventHintHold();
        clearRoundaboutExitHold();
        resetManeuverProgress();
        resetNavigationSendCache();
        state = new NavigationState(state.active, false, state.speedExceeded,
                "", "", "",
                state.routeDistance, state.routeTime, state.arrivalTime,
                state.currentStreet, state.nextStreet, state.finishStreet,
                state.speedLimit, state.currentSpeed, clean(reason), System.currentTimeMillis());
        publishNavigationState();
        AppLog.line(app, "Navigation route change visual cleared: " + clean(reason));
    }

    private String currentClusterYellowManeuver() {
        String visual = first(activeClusterVisual, state.clusterVisual);
        String maneuver = clusterYellowManeuverFromFrame(visual);
        if (!TextUtils.isEmpty(maneuver)) return maneuver;
        return clusterYellowManeuverFromFrame(latestClusterManeuverLine(state.clusterTx));
    }

    private String currentMainTxDistance() {
        String distance = nonZeroDistance(state.maneuverDistance);
        if (!TextUtils.isEmpty(distance)) return distance;
        String frame = currentClusterManeuverFrame();
        String frameManeuver = clusterYellowManeuverFromFrame(frame);
        if (!TextUtils.isEmpty(state.maneuver)
                && !TextUtils.isEmpty(frameManeuver)
                && !sameManeuverFamily(state.maneuver, frameManeuver)) {
            return "";
        }
        return nonZeroDistance(clusterFrameDistanceText(frame));
    }

    private int currentMainTxProgress(String maneuver, String distanceText) {
        if (!TextUtils.isEmpty(nonZeroDistance(distanceText))) {
            return maneuverProgressBucket(maneuver, first(state.nextStreet, state.currentStreet),
                    distanceText);
        }
        String frame = currentClusterManeuverFrame();
        String frameManeuver = clusterYellowManeuverFromFrame(frame);
        int current = sameManeuverFamily(first(maneuver, state.maneuver), frameManeuver)
                ? clusterFrameProgressBucket(frame)
                : -1;
        if (current >= 0) return current;
        return maneuverProgressBucket(maneuver, first(state.nextStreet, state.currentStreet), distanceText);
    }

    private String currentClusterManeuverFrame() {
        return first(activeClusterVisual, latestClusterManeuverLine(state.clusterTx));
    }

    private static String clusterFrameDistanceText(String frame) {
        String text = clean(frame);
        if (TextUtils.isEmpty(text)) return "";
        if (text.contains(" dist=")) {
            String tail = tokenAfterMarker(text, " dist=");
            return normalizeDistanceText(beforeAny(tail, " progress=", " bytes="));
        }
        String[] parts = text.split("/");
        return parts.length >= 2 ? normalizeDistanceText(parts[1]) : "";
    }

    private static int clusterFrameProgressBucket(String frame) {
        String text = clean(frame);
        if (TextUtils.isEmpty(text) || !text.contains("progress=")) return -1;
        String tail = tokenAfterMarker(text, "progress=");
        Matcher matcher = NUMBER.matcher(tail);
        if (!matcher.find()) return -1;
        int parsed = Math.round(parseFloat(matcher.group()));
        return parsed < 0 || parsed > 9 ? -1 : parsed;
    }

    private static String tokenAfterMarker(String value, String marker) {
        String text = clean(value);
        int start = text.indexOf(marker);
        if (start < 0) return "";
        return clean(text.substring(start + marker.length()));
    }

    private static String beforeAny(String value, String... markers) {
        String text = clean(value);
        int end = -1;
        for (String marker : markers) {
            int index = text.indexOf(marker);
            if (index >= 0 && (end < 0 || index < end)) end = index;
        }
        return clean(end < 0 ? text : text.substring(0, end));
    }

    private static boolean clusterYellowDiffersFrom(String expected, String actual) {
        String cleanExpected = clean(expected);
        String cleanActual = clean(actual);
        return !TextUtils.isEmpty(cleanExpected)
                && !TextUtils.isEmpty(cleanActual)
                && !sameManeuverFamily(cleanExpected, cleanActual);
    }

    private static String latestClusterManeuverLine(String clusterTx) {
        String text = clean(clusterTx);
        if (TextUtils.isEmpty(text)) return "";
        String[] lines = text.split("\\n");
        for (int i = lines.length - 1; i >= 0; i--) {
            String line = clean(lines[i]);
            if (line.startsWith("maneuver+gray ") || line.startsWith("maneuver ")) {
                return line;
            }
        }
        return "";
    }

    private static String clusterYellowManeuverFromFrame(String frame) {
        String text = clean(frame);
        if (TextUtils.isEmpty(text)) return "";
        if (text.startsWith("maneuver+gray ")) {
            String tail = text.substring("maneuver+gray ".length()).trim();
            int gray = tail.indexOf(" gray=");
            return clean(gray >= 0 ? tail.substring(0, gray) : tail);
        }
        if (text.startsWith("maneuver ")) {
            String tail = text.substring("maneuver ".length()).trim();
            int distance = tail.indexOf(" dist=");
            return clean(distance >= 0 ? tail.substring(0, distance) : tail);
        }
        int slash = text.indexOf('/');
        String visual = slash >= 0 ? text.substring(0, slash) : text;
        int plus = visual.indexOf(" + ");
        return clean(plus >= 0 ? visual.substring(0, plus) : visual);
    }

    private boolean clusterVisualIsLoading() {
        String visual = first(activeClusterVisual, state.clusterVisual);
        return TextUtils.isEmpty(visual) || visual.toLowerCase(Locale.US).contains("unknown");
    }

    private void clearStaleRouteVisual(String reason) {
        clearLaneHintHold();
        lastDirectManeuverAt = 0L;
        clearRoundaboutExitHold();
        clearClusterVisualHold();
        resetManeuverProgress();
        resetNavigationSendCache();
        state = new NavigationState(state.active, false, state.speedExceeded,
                "", "", "",
                state.routeDistance, state.routeTime, state.arrivalTime,
                state.currentStreet, state.nextStreet, "",
                state.speedLimit, state.currentSpeed, clean(reason), System.currentTimeMillis());
        publishNavigationState();
        AppLog.line(app, "Navigation stale visual cleared: " + clean(reason));
    }

    private void clearStaleManeuverVisual(String reason) {
        clearRoundaboutExitHold();
        clearClusterVisualHold();
        resetManeuverProgress();
        resetNavigationSendCache();
        state = new NavigationState(state.active, false, state.speedExceeded,
                "", "", "",
                state.routeDistance, state.routeTime, state.arrivalTime,
                state.currentStreet, state.nextStreet, state.finishStreet,
                state.speedLimit, state.currentSpeed, clean(reason), System.currentTimeMillis());
        publishNavigationState();
        if (state.active) sendConfiguredText();
        AppLog.line(app, "Navigation stale maneuver cleared: " + clean(reason));
    }

    private boolean finishPointKnown() {
        return validCoordinate(finishLatitude, finishLongitude);
    }

    private boolean gpsPointKnown() {
        return validCoordinate(lastGpsLatitude, lastGpsLongitude);
    }

    private float bearingToFinish() {
        Location from = new Location("kia_nav_current");
        from.setLatitude(lastGpsLatitude);
        from.setLongitude(lastGpsLongitude);
        Location to = new Location("kia_nav_finish");
        to.setLatitude(finishLatitude);
        to.setLongitude(finishLongitude);
        return normalizeDegrees(from.bearingTo(to));
    }

    private static double coordinate(Intent intent, String... keys) {
        for (String key : keys) {
            double value = intent.getDoubleExtra(key, Double.NaN);
            if (!Double.isNaN(value)) return value;
            float floatValue = intent.getFloatExtra(key, Float.NaN);
            if (!Float.isNaN(floatValue)) return floatValue;
            String text = text(intent, key);
            if (!TextUtils.isEmpty(text)) {
                double parsed = parseDouble(text);
                if (!Double.isNaN(parsed)) return parsed;
            }
        }
        return Double.NaN;
    }

    private static double[] coordinatePair(String value) {
        String source = clean(value);
        String lower = source.toLowerCase(Locale.US);
        if (!(source.contains(",") || source.contains(";") || lower.contains("lat")
                || lower.contains("lon") || lower.contains("lng")
                || source.matches(".*\\d+[\\.,]\\d+.*\\d+[\\.,]\\d+.*"))) {
            return null;
        }
        String text = source.replace(',', '.');
        if (TextUtils.isEmpty(text)) return null;
        Matcher matcher = NUMBER.matcher(text);
        if (!matcher.find()) return null;
        double first = parseDouble(matcher.group());
        if (!matcher.find()) return null;
        double second = parseDouble(matcher.group());
        if (validCoordinate(first, second)) return new double[]{first, second};
        if (validCoordinate(second, first)) return new double[]{second, first};
        return null;
    }

    private static double parseDouble(String value) {
        try {
            return Double.parseDouble(clean(value).replace(',', '.'));
        } catch (Exception ignored) {
            return Double.NaN;
        }
    }

    private static boolean validCoordinate(double lat, double lon) {
        return !Double.isNaN(lat) && !Double.isNaN(lon)
                && lat >= -90d && lat <= 90d && lon >= -180d && lon <= 180d
                && !(lat == 0d && lon == 0d);
    }

    private boolean routeLikelyChanged(String distanceText, String timeText) {
        float oldMeters = distanceMeters(state.routeDistance);
        float newMeters = distanceMeters(distanceText);
        if (oldMeters > 0f && newMeters > 0f) {
            float delta = Math.abs(newMeters - oldMeters);
            float threshold = Math.max(800f, oldMeters * 0.35f);
            if (delta > threshold) return true;
        }
        int oldMinutes = minutesValue(state.routeTime);
        int newMinutes = minutesValue(timeText);
        return oldMinutes > 0 && newMinutes > 0 && Math.abs(newMinutes - oldMinutes) > 3;
    }

    private static boolean staleManeuverBeyondRoute(String routeDistance, String maneuverDistance) {
        float routeMeters = distanceMeters(routeDistance);
        float maneuverMeters = distanceMeters(maneuverDistance);
        if (routeMeters <= 0f || maneuverMeters <= 0f) return false;
        float margin = Math.max(25f, routeMeters * 0.15f);
        return maneuverMeters > routeMeters + margin;
    }

    private static boolean maneuverFamilyChanged(String oldManeuver, String newManeuver) {
        if (TextUtils.isEmpty(oldManeuver) || TextUtils.isEmpty(newManeuver)) return false;
        return !sameManeuverFamily(oldManeuver, newManeuver);
    }

    private static boolean sameManeuverFamily(String left, String right) {
        String a = maneuverFamily(left);
        String b = maneuverFamily(right);
        return !TextUtils.isEmpty(a) && a.equals(b);
    }

    private boolean shouldPreserveMicroAfterRoundaboutTransition(String oldManeuver,
                                                                String newManeuver, long now) {
        if (!isRoundaboutManeuver(oldManeuver) || isRoundaboutManeuver(newManeuver)) {
            return false;
        }
        if (TextUtils.isEmpty(activeMicroManeuver) || microHintUntil <= now) {
            return false;
        }
        if (NavigationModeSettings.isTbt(app) || NavigationModeSettings.isFinishDirection(app)) {
            return false;
        }
        return AppSettings.navMicroManeuvers(app);
    }

    private static String maneuverFamily(String maneuver) {
        String p = clean(maneuver).toLowerCase(Locale.US);
        if (TextUtils.isEmpty(p) || "unknown".equals(p)) return "";
        if (isFinishManeuver(p)) return "finish";
        if (isRoundaboutManeuver(p)) return "roundabout";
        if (p.contains("_gray_")) return "gray";
        if (p.contains("turn_back") || p.contains("uturn") || p.contains("развор")) return "uturn";
        if (p.contains("hard_turn")) return p.contains("left") ? "hard_left" : "hard_right";
        if (p.contains("exit_") || p.contains("take_")) {
            return p.contains("left") ? "exit_left" : "exit_right";
        }
        if (p.contains("left") || p.contains("лев")) return "left";
        if (p.contains("right") || p.contains("прав")) return "right";
        if (p.contains("forward") || p.contains("straight") || p.contains("прям")) return "forward";
        return p;
    }

    private static int minutesValue(String value) {
        if (TextUtils.isEmpty(value)) return 0;
        String text = value.toLowerCase(Locale.US).replace(',', '.');
        Matcher matcher = NUMBER.matcher(text);
        if (!matcher.find()) return 0;
        float first = parseFloat(matcher.group());
        if (first <= 0f) return 0;
        if (text.contains("ч") || text.contains("hour") || text.contains("hr")) {
            float minutes = matcher.find() ? parseFloat(matcher.group()) : 0f;
            return Math.round(first * 60f + minutes);
        }
        return Math.round(first);
    }

    public synchronized void setTbtMode(boolean enabled) {
        setOutputMode(enabled ? NavigationOutputMode.TBT : NavigationOutputMode.NORMAL);
    }

    public synchronized void setFinishDirectionMode(boolean enabled) {
        setOutputMode(enabled ? NavigationOutputMode.FINISH_DIRECTION : NavigationOutputMode.NORMAL);
    }

    public synchronized void setOutputMode(int mode) {
        syncRouteStateFromStore();
        NavigationModeSettings.setMode(app, mode);
        AppLog.line(app, "Navigation route output mode: " + NavigationModeSettings.label(app));
        resetNavigationSendCache();
        if (state.active) sender.sendActive(true);
        resendKnownRouteData();
        if (NavigationModeSettings.isFinishDirection(app)) {
            if (finishDirectionShouldOverride()) {
                sendFinishDirectionPlaceholder(true);
                sendDirectionToFinishIfNeeded(true);
            } else {
                resendCurrentVisual();
            }
            return;
        }
        resendCurrentVisual();
    }

    private void syncRouteStateFromStore() {
        NavigationState stored = StateStore.navigation();
        if (stored == null || !stored.active || stored.finishReached) return;
        boolean currentMissingRoute = !state.active
                || (TextUtils.isEmpty(state.maneuver) && !TextUtils.isEmpty(stored.maneuver));
        boolean storedNewer = stored.updatedAt > state.updatedAt;
        if (currentMissingRoute || storedNewer) {
            state = stored;
            long now = System.currentTimeMillis();
            activeClusterVisual = stored.clusterVisual;
            if (!TextUtils.isEmpty(stored.laneHint)) {
                activeLaneHint = stored.laneHint;
                activeLaneSource = stored.laneSource;
                now = System.currentTimeMillis();
                laneHintUntil = now + LANE_HINT_OVERLAY_HOLD_MS;
                grayRoadUntil = now + LANE_HINT_OVERLAY_HOLD_MS;
            }
            if (!TextUtils.isEmpty(stored.eventHint)) {
                activeEventHint = stored.eventHint;
                activeEventSource = stored.eventSource;
                eventHintUntil = now + EVENT_HINT_HOLD_MS;
            }
            long microHoldMs = Math.max(LANE_HINT_OVERLAY_HOLD_MS,
                    AppSettings.navMicroHoldSeconds(app) * 1000L);
            if (!TextUtils.isEmpty(stored.microManeuverId)) {
                activeMicroManeuver = stored.microManeuverId;
                activeMicroDistance = validMicroDistance(stored.microDistance,
                        stored.maneuverDistance, stored.microManeuverId,
                        !TextUtils.isEmpty(stored.microDistance), stored.source);
                activeMicroDebugDistance = stored.microDistance;
                activeMicroStatus = stored.microStatus;
                activeMicroSource = stored.source;
                activeMicroTxAllowed = microDistanceAllowed(activeMicroDistance);
                microHintUntil = now + microHoldMs;
            } else if (!TextUtils.isEmpty(stored.microDistance)) {
                activeLaneDistance = stored.microDistance;
                laneDistanceUntil = now + microHoldMs;
            }
            AppLog.line(app, "Navigation route state synced for mode switch: " + state.summary());
        }
    }

    public synchronized void setTextMode(int mode) {
        AppSettings.setNavTextMode(app, mode);
        AppLog.line(app, "Navigation text mode: " + textModeName(AppSettings.navTextMode(app)));
        resetNavigationTextCache();
        sendConfiguredText();
    }

    public synchronized void setSourceMode(int mode) {
        AppSettings.setNavSourceMode(app, mode);
        int selected = AppSettings.navSourceMode(app);
        String source = clean(state.source).toLowerCase(Locale.US);
        if (selected == AppSettings.NAV_SOURCE_YANDEX && source.contains("2gis")) {
            setActive(false, "source_mode_yandex");
        } else if (selected == AppSettings.NAV_SOURCE_2GIS && isYandexSource(state.source)) {
            setActive(false, "source_mode_2gis");
        }
        AppLog.line(app, "Navigation source mode: " + AppSettings.navSourceLabel(app));
    }

    private void sendConfiguredText() {
        if (!state.active) return;
        if (state.finishReached) {
            sendNavigationText("Финиш");
            return;
        }
        long now = System.currentTimeMillis();
        if (routeReroutingActive(now)) {
            sendRouteReroutingVisual();
            return;
        }
        sendLaneTxPostPassIfActive(now, false);
        int mode = AppSettings.navTextMode(app);
        if (finishDirectionShouldOverride()) {
            if (!sendDirectionToFinishIfNeeded(false)) sendFinishDirectionPlaceholder(false);
            return;
        }
        if (AppSettings.navOverspeedTextEnabled(app)
                && state.speedExceeded && System.currentTimeMillis() <= overspeedTextUntil
                && !TextUtils.isEmpty(state.speedLimit)) {
            sendNavigationText("Превышение " + state.speedLimit + " km/h");
            return;
        }
        if (routeWaiting()) {
            sendRouteLoadingVisual();
            return;
        }
        if (state.loading()) {
            String selectedText = textForMode(mode);
            if (!TextUtils.isEmpty(selectedText)) {
                sendNavigationText(selectedText);
                return;
            }
            if (TextUtils.isEmpty(state.maneuver)) {
                if (routeLoadingFallbackReady()) {
                    if (!sendDirectionToFinishForLoading(false)) sendFinishDirectionPlaceholder(false);
                } else {
                    sendRouteLoadingVisual();
                }
            }
            return;
        }
        String text = textForMode(mode);
        sendNavigationText(text);
    }

    private void sendNavigationText(String text) {
        String cleanText = clean(text);
        if (TextUtils.isEmpty(cleanText)) return;
        if (cleanText.equals(lastSentNavigationText)) return;
        lastSentNavigationText = cleanText;
        sender.sendText(cleanText);
    }

    private void applyClusterVisual(String visual) {
        activeClusterVisual = clean(visual);
        state = state.withClusterVisualText(activeClusterVisual, System.currentTimeMillis());
        publishNavigationState();
    }

    private void resetNavigationTextCache() {
        lastSentNavigationText = "";
        clearManeuverTextHint();
    }

    private void startManeuverTextHint(String maneuver, String roundaboutExit) {
        clearManeuverTextHint();
    }

    private boolean maneuverHintActive() {
        return !TextUtils.isEmpty(activeManeuverHintText)
                && System.currentTimeMillis() <= maneuverTextUntil;
    }

    private void clearManeuverTextHint() {
        maneuverTextGeneration++;
        activeManeuverHintText = "";
        maneuverTextUntil = 0L;
    }

    private void sendManeuverIfChanged(String imageId, float distance, boolean km, int progressBucket) {
        sendManeuverIfChanged(imageId, distance, km, progressBucket, false);
    }

    private void sendManeuverIfChanged(String imageId, float distance, boolean km, int progressBucket,
                                       boolean force) {
        String cleanImageId = clean(imageId);
        progressBucket = normalizeProgressBucket(progressBucket);
        if (!navigationTxAllowed(cleanImageId)) return;
        if (maneuverTxBlockedByRerouting(cleanImageId)) return;
        if (finishDirectionShouldOverride() && !isFinishManeuver(cleanImageId)) {
            if (!sendDirectionToFinishIfNeeded(force)) sendFinishDirectionPlaceholder(force);
            return;
        }
        if (canMergeGrayRoad(cleanImageId) && !TextUtils.isEmpty(activeGrayRoadId)
                && grayRoadUntil > System.currentTimeMillis()) {
            sendManeuverWithGrayRoadIfChanged(cleanImageId, activeGrayRoadId,
                    distance, km, progressBucket, force);
            return;
        }
        clusterTx.sendManeuver(imageId, distance, km, progressBucket, force);
    }

    private void sendManeuverWithGrayRoadIfChanged(String imageId, String grayRoadId, float distance,
                                                   boolean km, int progressBucket) {
        sendManeuverWithGrayRoadIfChanged(imageId, grayRoadId, distance, km, progressBucket, false);
    }

    private void sendManeuverWithGrayRoadIfChanged(String imageId, String grayRoadId, float distance,
                                                   boolean km, int progressBucket, boolean force) {
        String cleanImageId = clean(imageId);
        progressBucket = normalizeProgressBucket(progressBucket);
        if (!navigationTxAllowed(cleanImageId)) return;
        if (maneuverTxBlockedByRerouting(cleanImageId)) return;
        if (finishDirectionShouldOverride() && !isFinishManeuver(cleanImageId)) {
            boolean sentFinishDirection = sendDirectionToFinishIfNeeded(force);
            AppLog.line(app, "Navigation gray fallback blocked by finish direction: maneuver="
                    + cleanImageId + " gray=" + clean(grayRoadId) + " sent=" + sentFinishDirection);
            if (!sentFinishDirection) sendFinishDirectionPlaceholder(force);
            return;
        }
        String cleanGrayRoad = clean(grayRoadId);
        if (isPriorityEventManeuver(cleanImageId) && !TextUtils.isEmpty(cleanGrayRoad)) {
            AppLog.line(app, "Navigation priority maneuver TX without gray road: "
                    + cleanImageId + " gray=" + cleanGrayRoad);
            sendManeuverIfChanged(cleanImageId, distance, km, progressBucket, force);
            return;
        }
        clusterTx.sendManeuverWithGrayRoad(imageId, grayRoadId, distance, km, progressBucket, force);
    }

    private boolean navigationTxAllowed(String imageId) {
        String cleanImageId = clean(imageId);
        if (!state.active) {
            AppLog.line(app, "Navigation TX blocked while inactive: " + cleanImageId
                    + " visual=" + clean(activeClusterVisual));
            return false;
        }
        if (state.finishReached && !isFinishManeuver(cleanImageId)) {
            AppLog.line(app, "Navigation TX blocked after finish: " + cleanImageId);
            return false;
        }
        return true;
    }

    private boolean maneuverTxBlockedByRerouting(String imageId) {
        if (isFinishManeuver(imageId)) return false;
        if (!routeReroutingActive(System.currentTimeMillis())) return false;
        sendRouteReroutingVisual();
        AppLog.line(app, "Navigation maneuver TX blocked by rerouting: " + clean(imageId));
        return true;
    }

    private static String clusterVisualText(String imageId, int progressBucket, float distance, boolean km) {
        return clean(imageId) + " / " + clusterDistanceText(distance, km)
                + " / progress=" + progressBucket;
    }

    private static int normalizeProgressBucket(int progressBucket) {
        return progressBucket < 0 ? 0 : clampInt(progressBucket, 0, 9);
    }

    private static String clusterDistanceText(float distance, boolean km) {
        if (distance <= 0f) return "0 м";
        if (km) return trimDistance(distance) + " км";
        return trimDistance(roundMetersForDisplay(distance)) + " м";
    }

    private static String trimDistance(float distance) {
        if (distance == Math.round(distance)) return String.valueOf(Math.round(distance));
        return String.format(Locale.US, "%.1f", distance);
    }

    private void sendEtaIfChanged(float distance, boolean km) {
        float sendDistance = clusterDistanceValue(distance, km);
        String key = sendDistance + "|" + km;
        if (key.equals(lastSentEtaKey)) return;
        lastSentEtaKey = key;
        sender.sendEta(sendDistance, km);
    }

    private void sendEtaTimeIfChanged(String arrivalTime) {
        int[] hourMinute = arrivalHourMinute(arrivalTime);
        if (hourMinute == null) return;
        String key = hourMinute[0] + ":" + hourMinute[1];
        if (key.equals(lastSentEtaTimeKey)) return;
        lastSentEtaTimeKey = key;
        sender.sendEtaTime(hourMinute[0], hourMinute[1]);
    }

    private void resendKnownRouteData() {
        if (!state.active) return;
        if (!TextUtils.isEmpty(state.routeDistance)) {
            sendEtaIfChanged(distanceValue(state.routeDistance), isKm(state.routeDistance));
        }
        if (!TextUtils.isEmpty(state.arrivalTime)) {
            sendEtaTimeIfChanged(state.arrivalTime);
        }
        if (!TextUtils.isEmpty(state.speedLimit)) {
            sendSpeed(state.speedLimit);
        }
    }

    private void sendRouteLoadingVisual() {
        if (routeReroutingActive(System.currentTimeMillis())) {
            sendRouteReroutingVisual();
            AppLog.line(app, "Navigation loading visual suppressed by rerouting");
            return;
        }
        activeClusterVisual = "route_loading / text";
        state = state.withClusterVisualText(activeClusterVisual, System.currentTimeMillis());
        publishNavigationState();
        sendNavigationText("Загрузка маршрута");
    }

    private void startRouteReroutingHold(String source) {
        startRouteReroutingHold(source, false);
    }

    private void startRouteReroutingHold(String source, boolean allowInactive) {
        if (state.finishReached) return;
        if (!state.active && !allowInactive) return;
        long now = System.currentTimeMillis();
        routeReroutingUntil = Math.max(routeReroutingUntil, now + ROUTE_REROUTING_HOLD_MS);
        int generation = ++routeReroutingGeneration;
        resetNavigationTextCache();
        AppLog.line(app, "Navigation route rerouting hold: " + clean(source));
        if (state.active) sendRouteReroutingVisual();
        handler.postDelayed(() -> {
            synchronized (NavigationFeature.this) {
                if (generation != routeReroutingGeneration) return;
                if (System.currentTimeMillis() < routeReroutingUntil) return;
                routeReroutingUntil = 0L;
                if (!routeReroutingVisualActive()) return;
                clearClusterVisualHold();
                resetNavigationTextCache();
                resendCurrentVisual();
                sendConfiguredText();
                AppLog.line(app, "Navigation route rerouting hold finished");
            }
        }, ROUTE_REROUTING_HOLD_MS);
    }

    private boolean routeReroutingActive(long now) {
        if (!state.active || state.finishReached || routeReroutingUntil <= 0L) {
            routeReroutingUntil = 0L;
            return false;
        }
        if (now > routeReroutingUntil) {
            routeReroutingUntil = 0L;
            return false;
        }
        return true;
    }

    private void sendRouteReroutingVisual() {
        activeClusterVisual = "route_loading / rerouting_text";
        state = state.withClusterVisualText(activeClusterVisual, System.currentTimeMillis());
        publishNavigationState();
        sendNavigationText("Перестроение маршрута");
    }

    private boolean routeReroutingVisualActive() {
        String visual = clean(first(activeClusterVisual, state.clusterVisual)).toLowerCase(Locale.US);
        return visual.contains("rerouting_text");
    }

    private void scheduleRouteLoadingFallback() {
        int generation = ++routeLoadingFallbackGeneration;
        handler.postDelayed(() -> {
            synchronized (NavigationFeature.this) {
                if (generation != routeLoadingFallbackGeneration) return;
                if (!routeLoadingFallbackReady() || !TextUtils.isEmpty(state.maneuver)) return;
                if (!hasConfirmedRouteMetrics()) {
                    AppLog.line(app, "Navigation loading stopped without route metrics");
                    forceInactive("route_loading_no_metrics");
                    return;
                }
                if (sendDirectionToFinishForLoading(true)) {
                    AppLog.line(app, "Navigation loading fallback: direction to finish");
                } else {
                    sendFinishDirectionPlaceholder(true);
                    AppLog.line(app, "Navigation loading fallback: placeholder direction");
                }
            }
        }, ROUTE_WAIT_MAX_MS);
    }

    private void sendFinishDirectionPlaceholder(boolean force) {
        targetFinishDirectionStep = normalizeCompassStep(6);
        activeFinishDirectionDistance = 0f;
        activeFinishDirectionKm = false;
        lastFinishDirectionReason = "finish_direction_placeholder";
        sendFinishDirectionStep(targetFinishDirectionStep, targetFinishDirectionStep,
                activeFinishDirectionDistance, activeFinishDirectionKm,
                lastFinishDirectionReason, force);
    }

    private boolean sendDirectionToFinishIfNeeded(boolean force) {
        if (!finishDirectionShouldOverride()) return false;
        return sendDirectionToFinishFrame(force,
                "finish_direction_mode");
    }

    private boolean sendDirectionToFinishForLoading(boolean force) {
        if (!state.active || state.finishReached || !state.loading()) return false;
        if (!TextUtils.isEmpty(state.maneuver)) return false;
        if (!hasConfirmedRouteMetrics()) return false;
        return sendDirectionToFinishFrame(force, "loading_finish_direction");
    }

    private boolean sendDirectionToFinishFrame(boolean force, String reason) {
        float heading = finishReferenceHeading();
        if (!finishPointKnown() || !gpsPointKnown() || Float.isNaN(heading)) return false;
        long now = System.currentTimeMillis();
        if (!force && now - lastFinishDirectionAt < 700L) return true;
        float absoluteBearing = bearingToFinish();
        int step = compassDirectionStep(absoluteBearing - heading);
        String distanceText = first(state.routeDistance, state.maneuverDistance);
        boolean km = isKm(distanceText);
        float distance = distanceValue(distanceText);
        if (distance <= 0f) {
            distance = distanceToFinishMeters();
            km = false;
        }
        float sendDistance = clusterDistanceValue(distance, km);
        sendDirectionToFinishAnimated(step, sendDistance, km, force, reason);
        return true;
    }

    private void sendDirectionToFinishAnimated(int targetStep, float sendDistance,
                                               boolean km, boolean force, String reason) {
        targetFinishDirectionStep = normalizeCompassStep(targetStep);
        activeFinishDirectionDistance = sendDistance;
        activeFinishDirectionKm = km;
        lastFinishDirectionReason = clean(reason);
        if (displayedFinishDirectionStep < 0) {
            sendFinishDirectionStep(targetFinishDirectionStep, targetFinishDirectionStep,
                    sendDistance, km, reason, force);
            return;
        }
        int next = displayedFinishDirectionStep == targetFinishDirectionStep
                ? targetFinishDirectionStep
                : nextCompassStep(displayedFinishDirectionStep, targetFinishDirectionStep);
        sendFinishDirectionStep(next, targetFinishDirectionStep, sendDistance, km, reason, force);
        scheduleFinishDirectionAnimation();
    }

    private void sendFinishDirectionStep(int uiStep, int targetStep, float sendDistance,
                                         boolean km, String reason, boolean force) {
        int step = normalizeCompassStep(uiStep);
        int target = normalizeCompassStep(targetStep);
        long now = System.currentTimeMillis();
        String key = clean(reason) + "|" + step + "|" + target + "|"
                + Math.round(sendDistance * 10f) + "|" + km;
        if (!force && key.equals(lastSentFinishDirectionKey)) return;
        lastSentFinishDirectionKey = key;
        lastFinishDirectionAt = now;
        displayedFinishDirectionStep = step;
        activeClusterVisual = "context_ra_direction_to_finish / step=" + step
                + " target=" + target
                + " / " + clusterDistanceText(sendDistance, km);
        state = state.withClusterVisualText(activeClusterVisual, now);
        publishNavigationState();
        sender.sendDirectionToFinish(step, sendDistance, km);
    }

    private void scheduleFinishDirectionAnimation() {
        if (!finishDirectionAnimationAllowed()) return;
        if (displayedFinishDirectionStep == targetFinishDirectionStep) return;
        if (finishDirectionAnimationRunning) return;
        finishDirectionAnimationRunning = true;
        handler.postDelayed(finishDirectionAnimator, FINISH_DIRECTION_ANIMATION_STEP_MS);
    }

    private boolean finishDirectionAnimationAllowed() {
        return state.active && !state.finishReached
                && targetFinishDirectionStep >= 0
                && NavigationModeSettings.isFinishDirection(app);
    }

    private void resetFinishDirectionAnimation() {
        handler.removeCallbacks(finishDirectionAnimator);
        finishDirectionAnimationRunning = false;
        displayedFinishDirectionStep = -1;
        targetFinishDirectionStep = -1;
        activeFinishDirectionDistance = 0f;
        activeFinishDirectionKm = false;
        lastFinishDirectionReason = "";
        lastSentFinishDirectionKey = "";
        lastFinishDirectionAt = 0L;
    }

    private boolean finishDirectionShouldOverride() {
        return NavigationModeSettings.isFinishDirection(app) && state.active && !state.finishReached;
    }

    private boolean finishDirectionWithinLead() {
        int leadMeters = AppSettings.navFinishDirectionLeadMeters(app);
        if (leadMeters <= 0) return true;
        float routeMeters = distanceMeters(state.routeDistance);
        if (routeMeters > 0f) return routeMeters <= leadMeters;
        float directMeters = distanceToFinishMeters();
        if (directMeters > 0f) return directMeters <= leadMeters;
        float maneuverMeters = distanceMeters(state.maneuverDistance);
        return maneuverMeters > 0f && maneuverMeters <= leadMeters;
    }

    private float distanceToFinishMeters() {
        if (!finishPointKnown() || !gpsPointKnown()) return -1f;
        Location from = new Location("kia_nav_current");
        from.setLatitude(lastGpsLatitude);
        from.setLongitude(lastGpsLongitude);
        Location to = new Location("kia_nav_finish");
        to.setLatitude(finishLatitude);
        to.setLongitude(finishLongitude);
        return from.distanceTo(to);
    }

    private float finishReferenceHeading() {
        long now = System.currentTimeMillis();
        boolean deviceFresh = !Float.isNaN(lastDeviceHeading)
                && now - lastDeviceHeadingAt <= 2500L;
        boolean gpsFresh = !Float.isNaN(lastGpsBearing)
                && now - lastGpsBearingAt <= 5000L;
        if (deviceFresh && gpsFresh && lastGpsSpeedMetersPerSecond >= 1.4f) {
            float gpsWeight = Math.max(0.18f,
                    Math.min(0.65f, (lastGpsSpeedMetersPerSecond - 1.4f) / 7.0f));
            return blendDegrees(lastDeviceHeading, lastGpsBearing, gpsWeight);
        }
        if (deviceFresh) return lastDeviceHeading;
        return gpsFresh ? lastGpsBearing : Float.NaN;
    }

    private boolean maybeAutoFinishStaleNearDestination(long now) {
        if (!state.active || state.finishReached) return false;
        float routeMeters = distanceMeters(state.routeDistance);
        float directMeters = distanceToFinishMeters();
        boolean nearByRoute = routeMeters > 0f && routeMeters <= AUTO_FINISH_ROUTE_METERS;
        boolean nearByGps = directMeters > 0f && directMeters <= AUTO_FINISH_GPS_METERS;
        boolean routeStale = lastRouteGuidanceAt <= 0L || now - lastRouteGuidanceAt >= FINISH_STALE_AUTO_MS;
        if (!routeStale || !nearByRoute || !nearByGps) return false;
        markFinishReached("auto_finish_stale_near_destination");
        AppLog.line(app, "Navigation auto finish: route=" + routeMeters
                + " gps=" + directMeters + " stale=" + routeStale);
        return true;
    }

    private void maybeRecoverStaleFinishFromMovement() {
        if (!state.active || !state.finishReached) return;
        float routeMeters = distanceMeters(state.routeDistance);
        float directMeters = distanceToFinishMeters();
        boolean routeKnown = routeMeters > 0f;
        boolean gpsKnown = directMeters > 0f;
        if (!routeKnown && !gpsKnown) return;
        boolean routeAway = routeKnown && routeMeters > AUTO_FINISH_ROUTE_METERS + FINISH_STALE_RECOVERY_MARGIN_METERS;
        boolean gpsAway = gpsKnown && directMeters > AUTO_FINISH_GPS_METERS + FINISH_STALE_RECOVERY_MARGIN_METERS;
        if (!routeAway && !gpsAway) return;
        cancelFinishHold();
        clearManeuverTextHint();
        clearStaleManeuverVisual("recover stale finish state");
        AppLog.line(app, "Navigation stale finish recovered: route=" + routeMeters
                + " gps=" + directMeters);
    }

    private void markFinishReached(String source) {
        long now = System.currentTimeMillis();
        yandexFinishSuppressed = true;
        state = new NavigationState(true, true, state.speedExceeded,
                "context_ra_finish", maneuverLabel("context_ra_finish"), "",
                state.routeDistance, state.routeTime, state.arrivalTime,
                state.currentStreet, state.nextStreet, state.finishStreet,
                state.speedLimit, state.currentSpeed, clean(source), now);
        publishNavigationState();
        startFinishHold();
    }

    private void resendCurrentVisual() {
        if (!state.active || state.finishReached) return;
        if (!TextUtils.isEmpty(state.maneuver)) {
            String distance = first(state.maneuverDistance, state.distance);
            sendManeuverWithFallbackGray(state.maneuver, distance,
                    maneuverProgressBucket(state.maneuver, first(state.nextStreet, state.currentStreet), distance),
                    true);
            return;
        }
        sendConfiguredText();
    }

    private void sendManeuverWithFallbackGray(String maneuver, String distanceText,
                                              int progressBucket, boolean force) {
        if (isStandaloneManeuverFrame(maneuver)) {
            sendManeuverIfChanged(maneuver, distanceValue(distanceText), isKm(distanceText),
                    progressBucket, force);
            return;
        }
        long now = System.currentTimeMillis();
        String grayRoad = activeFallbackGrayRoadForManeuver(maneuver, now);
        if (!TextUtils.isEmpty(grayRoad)) {
            sendManeuverWithGrayRoadIfChanged(maneuver, grayRoad, distanceValue(distanceText),
                    isKm(distanceText), progressBucket, force);
        } else {
            sendManeuverIfChanged(maneuver, distanceValue(distanceText), isKm(distanceText),
                    progressBucket, force);
        }
    }

    private void refreshFallbackGrayRoadVisualIfNeeded(long now) {
        if (!state.active || state.finishReached || state.loading()) return;
        String maneuver = clean(state.maneuver);
        if (TextUtils.isEmpty(maneuver) || !canMergeGrayRoad(maneuver)) return;
        String fallback = activeFallbackGrayRoadForManeuver(maneuver, now);
        if (TextUtils.isEmpty(fallback)) return;
        String currentGray = clean(state.grayRoadId);
        if (!fallback.equals(currentGray)) {
            state = state.withNavigationDebug(state.mainManeuverId, state.routeActionId,
                    state.microManeuverId, state.microDistance, state.microStatus,
                    fallback, grayRoadLabel(fallback), now);
            publishNavigationState();
        }
        String visual = clean(first(activeClusterVisual, state.clusterVisual));
        if (visual.contains(fallback)) return;
        String distance = first(nonZeroDistance(state.maneuverDistance), nonZeroDistance(state.routeDistance));
        if (TextUtils.isEmpty(distance)) return;
        sendManeuverWithGrayRoadIfChanged(maneuver, fallback, distanceValue(distance), isKm(distance),
                maneuverProgressBucket(maneuver, first(state.nextStreet, state.currentStreet), distance), true);
        AppLog.line(app, "Navigation held gray road refreshed: maneuver=" + maneuver
                + " gray=" + fallback + " distance=" + distance);
    }

    private String activeFallbackGrayRoadForManeuver(String maneuver, long now) {
        if (grayRoadUntil <= now || !canMergeGrayRoad(maneuver)) return "";
        String grayRoad = clean(activeGrayRoadId);
        if (TextUtils.isEmpty(grayRoad)) return "";
        int maneuverMask = maneuverRoadMask(maneuver);
        int grayMask = grayRoadMask(grayRoad);
        if (maneuverMask != 0 && grayMask != 0 && (grayMask & maneuverMask) == 0) {
            AppLog.line(app, "Navigation dropped stale fallback gray road: maneuver="
                    + clean(maneuver) + " gray=" + grayRoad);
            clearGrayRoadHold();
            clearLearnedRoadOptions();
            return "";
        }
        return grayRoad;
    }

    private static boolean isLegacyFallbackGrayRoad(String maneuver, String grayRoad) {
        String cleanManeuver = clean(maneuver);
        String cleanGray = clean(grayRoad);
        return ("context_ra_turn_right".equals(cleanManeuver)
                && "context_ra_gray_right".equals(cleanGray))
                || ("context_ra_turn_right".equals(cleanManeuver)
                && "context_ra_gray_straight_right".equals(cleanGray))
                || ("context_ra_turn_right".equals(cleanManeuver)
                && "context_ra_gray_straight_left_right".equals(cleanGray))
                || ("context_ra_turn_left".equals(cleanManeuver)
                && "context_ra_gray_left".equals(cleanGray))
                || ("context_ra_turn_left".equals(cleanManeuver)
                && "context_ra_gray_straight_left".equals(cleanGray))
                || ("context_ra_turn_left".equals(cleanManeuver)
                && "context_ra_gray_straight_left_right".equals(cleanGray));
    }

    private void resetNavigationSendCache() {
        lastSentEtaKey = "";
        lastSentEtaTimeKey = "";
        lastSentFinishDirectionKey = "";
        laneTxPostPassUntil = 0L;
        lastFinishDirectionAt = 0L;
        lastRouteFinishEtaAt = 0L;
        lastSentSpeedLimit = -1;
        speedLimitTextUntil = 0L;
        clearClusterVisualHold();
    }

    private String textForMode(int mode) {
        String finishStreet = validFinishStreet(state.finishStreet);
        String routeText = routeMetricsText();
        switch (mode) {
            case 1:
                return first(state.nextStreet, state.currentStreet, finishStreet, routeText);
            case 2:
                return first(finishStreet, routeText);
            case 3:
                return first(speedLimitText(), state.currentStreet, state.nextStreet, routeText);
            case 0:
            default:
                return first(state.currentStreet, state.nextStreet, finishStreet, routeText);
        }
    }

    private String routeMetricsText() {
        String distance = clean(state.routeDistance);
        String time = clean(state.routeTime);
        String arrival = clean(state.arrivalTime);
        if (!TextUtils.isEmpty(distance) && !TextUtils.isEmpty(arrival)) {
            return distance + " / " + arrival;
        }
        if (!TextUtils.isEmpty(distance) && !TextUtils.isEmpty(time)) {
            return distance + " / " + time;
        }
        return first(distance, time, arrival);
    }

    private String speedLimitText() {
        return TextUtils.isEmpty(state.speedLimit) ? "" : state.speedLimit + " km/h";
    }

    private String textModeName(int mode) {
        switch (mode) {
            case 1:
                return "street_after_maneuver";
            case 2:
                return "finish_street";
            case 3:
                return "speed_limit";
            case 0:
            default:
                return "current_street";
        }
    }

    private boolean routeWaiting() {
        if (!waitingForRoute || !state.active || state.finishReached) return false;
        long now = System.currentTimeMillis();
        if (routeLoadingMinUntil > now) return true;
        if (routeDataReady()) {
            waitingForRoute = false;
            routeLoadingMinUntil = 0L;
            return false;
        }
        if (routeStartedAt <= 0L || now - routeStartedAt >= ROUTE_WAIT_MAX_MS) {
            boolean loading = state.loading();
            if (!loading) {
                waitingForRoute = false;
                routeLoadingMinUntil = 0L;
            }
            return loading;
        }
        return true;
    }

    private boolean routeLoadingFallbackReady() {
        long now = System.currentTimeMillis();
        return waitingForRoute && state.active && !state.finishReached
                && routeStartedAt > 0L
                && routeLoadingMinUntil <= now
                && now - routeStartedAt >= ROUTE_WAIT_MAX_MS
                && state.loading();
    }

    private boolean routeDataReady() {
        boolean hasRoute = !TextUtils.isEmpty(state.routeDistance);
        boolean hasGuidance = !TextUtils.isEmpty(state.maneuverText)
                || !TextUtils.isEmpty(state.maneuverDistance)
                || !TextUtils.isEmpty(state.maneuver);
        return hasRoute && hasGuidance;
    }

    private void startFinishHold() {
        yandexFinishSuppressed = true;
        waitingForRoute = false;
        routeStartedAt = 0L;
        routeReroutingGeneration++;
        routeReroutingUntil = 0L;
        clearManeuverTextHint();
        resetFinishDirectionAnimation();
        int generation = ++finishGeneration;
        long now = System.currentTimeMillis();
        finishHoldUntil = now + FINISH_HOLD_MS;
        finishCompassSuppressUntil = now + FINISH_HOLD_MS + FINISH_COMPASS_SUPPRESS_MS;
        resetNavigationTextCache();
        resetNavigationSendCache();
        sendManeuverIfChanged("context_ra_finish", 0f, false, 9, true);
        sendNavigationText("Финиш");
        handler.postDelayed(() -> {
            synchronized (NavigationFeature.this) {
                if (generation != finishGeneration || !state.finishReached) return;
                setActive(false, "finish_hold");
            }
        }, FINISH_HOLD_MS);
    }

    private void cancelFinishHold() {
        finishGeneration++;
        finishHoldUntil = 0L;
    }

    private void startOverspeedTextWindow() {
        int generation = ++overspeedGeneration;
        overspeedTextUntil = System.currentTimeMillis() + OVERSPEED_TEXT_MS;
        handler.postDelayed(() -> {
            synchronized (NavigationFeature.this) {
                if (generation != overspeedGeneration || !state.active || state.finishReached) return;
                sendConfiguredText();
            }
        }, OVERSPEED_TEXT_MS + 50L);
    }

    private void clearOverspeedTextWindow() {
        overspeedGeneration++;
        overspeedTextUntil = 0L;
    }

    private int maneuverProgressBucket(String maneuver, String street, String distanceText) {
        float meters = distanceMeters(distanceText);
        if (meters <= 0f) return 0;
        String key = clean(maneuver) + "|" + simplifyStreet(street);
        float resetDelta = Math.max(80f, maneuverProgressBaseMeters * 0.35f);
        boolean distanceJumpedForward = maneuverProgressLastMeters > 0f
                && meters > maneuverProgressLastMeters + resetDelta;
        if (!key.equals(maneuverProgressKey) || maneuverProgressBaseMeters <= 0f
                || distanceJumpedForward || meters > maneuverProgressBaseMeters + resetDelta) {
            maneuverProgressKey = key;
            maneuverProgressBaseMeters = meters;
        }
        maneuverProgressLastMeters = meters;
        if (meters <= MANEUVER_PROGRESS_ZERO_METERS) return 0;
        float base = Math.max(MANEUVER_PROGRESS_MIN_BASE_METERS, maneuverProgressBaseMeters);
        float remainingRatio = Math.max(0f, Math.min(1f, meters / base));
        return clampInt(Math.round(remainingRatio * 9f), 0, 9);
    }

    private void resetManeuverProgress() {
        maneuverProgressBaseMeters = 0f;
        maneuverProgressLastMeters = 0f;
        maneuverProgressKey = "";
    }

    private static boolean isYandexNavigationIntent(Intent intent) {
        String source = text(intent, "source");
        if (TextUtils.isEmpty(source)) return false;
        String clean = source.toLowerCase(Locale.US);
        return clean.contains("yandex") || clean.contains("яндекс");
    }

    private boolean yandexRouteHeartbeatIntent(Intent intent) {
        if (intent == null) return false;
        String action = clean(intent.getAction());
        boolean incomingRouteMetrics = hasConfirmedRouteMetrics(routeDistanceFromIntent(intent),
                routeTimeFromIntent(intent), arrivalFromIntent(intent));
        if (ACTION_SPEED.equals(action) || KIA_ACTION_SPEED.equals(action)
                || ACTION_NAVI_ON.equals(action) || KIA_ACTION_NAVI_ON.equals(action)
                || ACTION_ETA.equals(action) || KIA_ACTION_ETA.equals(action)) {
            return incomingRouteMetrics;
        }
        return (ACTION_MANEUVER.equals(action) || KIA_ACTION_MANEUVER.equals(action))
                && routeMetricsFresh(System.currentTimeMillis());
    }

    private static boolean routeReroutingIntent(Intent intent) {
        String state = first(text(intent, "bridge_state"), text(intent, "state"),
                text(intent, "route_state"), text(intent, "status"));
        if (YandexCoreBridgeContract.STATE_REROUTING.equals(clean(state).toLowerCase(Locale.US))) {
            return true;
        }
        String marker = first(text(intent, "last_callback"), text(intent, "callback"),
                text(intent, "event_type"), text(intent, "bridge_reason"), text(intent, "reason"));
        String lower = clean(marker).toLowerCase(Locale.US);
        return lower.contains("rerout") || lower.contains("перестро");
    }

    private boolean hasConfirmedRouteMetrics() {
        return hasConfirmedRouteMetrics(state.routeDistance, state.routeTime, state.arrivalTime);
    }

    private static boolean hasConfirmedRouteMetrics(String routeDistance, String routeTime,
                                                    String arrivalTime) {
        return !TextUtils.isEmpty(clean(routeDistance))
                || !TextUtils.isEmpty(clean(routeTime))
                || !TextUtils.isEmpty(clean(arrivalTime));
    }

    private static String routeDistanceFromIntent(Intent intent) {
        return first(metersDistanceText(first(text(intent, "remaining_meters"),
                        text(intent, "remaining_distance_meters"), text(intent, "distance_left_meters"))),
                text(intent, "edistance"), text(intent, "route_distance"),
                text(intent, "remaining_distance"), text(intent, "distance_left"),
                text(intent, "total_distance"), text(intent, "distance"),
                textByKeyPart(intent, "distance"));
    }

    private static String routeTimeFromIntent(Intent intent) {
        return first(text(intent, "route_time"), text(intent, "time_left"),
                text(intent, "duration"), text(intent, "remaining_time"),
                textByKeyPart(intent, "time_left"));
    }

    private static String arrivalFromIntent(Intent intent) {
        return first(text(intent, "arrival_time"), text(intent, "arrival"),
                text(intent, "eta"));
    }

    private String stableSourceForSpeed(String incoming) {
        String current = clean(state.source);
        String next = clean(incoming);
        if (TextUtils.isEmpty(current)) return first(next, "gps_speed");
        if (isGpsSpeedSource(current) && (isYandexSource(next) || isDgisSource(next))) return next;
        return current;
    }

    private static boolean isYandexSource(String source) {
        String clean = clean(source).toLowerCase(Locale.US);
        return clean.contains("yandex") || clean.contains("яндекс");
    }

    private static boolean isDgisSource(String source) {
        String clean = clean(source).toLowerCase(Locale.US);
        return clean.contains("2gis") || clean.contains("2гис") || clean.contains("dgis");
    }

    private static boolean isGpsSpeedSource(String source) {
        String clean = clean(source).toLowerCase(Locale.US);
        return clean.contains("gps") || clean.equals("speed") || clean.endsWith("_speed");
    }

    private static boolean isRouteSuggestionSource(String source) {
        return clean(source).toLowerCase(Locale.US).contains("suggestion_route");
    }

    private static boolean isCachedFinishSource(String source) {
        String clean = clean(source).toLowerCase(Locale.US);
        return clean.contains("cached_finish")
                || clean.contains("selected_finish")
                || clean.contains("geo_finish");
    }

    private static boolean isRouteFinishSource(String source) {
        return clean(source).toLowerCase(Locale.US).contains("route_finish");
    }

    private static boolean isGuidanceEtaSource(String source) {
        String clean = clean(source).toLowerCase(Locale.US);
        return clean.contains("fullscreen_eta") || clean.contains("guidance_eta");
    }

    private boolean finishSuppressesYandex(String source, String distanceText,
                                           String timeText, String finishText) {
        if (!yandexFinishSuppressed || !isYandexSource(source)) return false;
        if (newYandexRouteAfterFinish(source, distanceText, timeText, finishText)) {
            yandexFinishSuppressed = false;
            cancelFinishHold();
            AppLog.line(app, "Navigation finish suppression cleared by new route: "
                    + clean(source) + " distance=" + clean(distanceText));
            return false;
        }
        AppLog.line(app, "Navigation ignored stale Yandex after finish: "
                + clean(source) + " distance=" + clean(distanceText));
        return true;
    }

    private boolean newYandexRouteAfterFinish(String source, String distanceText,
                                              String timeText, String finishText) {
        String cleanSource = clean(source).toLowerCase(Locale.US);
        if (cleanSource.contains("route_start") || cleanSource.contains("new_route")) return true;
        float meters = distanceMeters(distanceText);
        if (meters > NEW_ROUTE_AFTER_FINISH_METERS) return true;
        if (minutesValue(timeText) >= FINISH_CONFLICT_MINUTES) return true;
        return containsFinishTextChange(finishText) && routeLikelyChanged(distanceText, timeText);
    }

    private static boolean finishContradictedByGuidance(String routeDistance,
                                                        String maneuverDistance,
                                                        String routeTime) {
        float routeMeters = distanceMeters(routeDistance);
        if (routeMeters <= 0f || routeMeters > AUTO_FINISH_ROUTE_METERS) return false;
        float maneuverMeters = distanceMeters(maneuverDistance);
        if (maneuverMeters > routeMeters + FINISH_CONFLICT_MANEUVER_METERS) return true;
        return minutesValue(routeTime) >= FINISH_CONFLICT_MINUTES;
    }

    private boolean containsFinishTextChange(String finishText) {
        String finish = validFinishStreet(finishText);
        return !TextUtils.isEmpty(finish) && !sameStreet(finish, state.finishStreet);
    }

    private static boolean packetClaimsActiveRoute(Intent intent) {
        return bool(intent, "navi_on", false)
                || bool(intent, "active", false)
                || bool(intent, "is_active", false);
    }

    private static boolean canResumeFromEtaPacket(String source, String distanceText,
                                                  String timeText, String arrivalText,
                                                  String finishText) {
        String clean = clean(source);
        if (isRouteSuggestionSource(clean) || isCachedFinishSource(clean)
                || isWeakOverviewRouteSource(clean)) {
            return false;
        }
        return isRouteFinishSource(clean)
                || isGuidanceEtaSource(clean)
                || !TextUtils.isEmpty(distanceText)
                || !TextUtils.isEmpty(timeText)
                || !TextUtils.isEmpty(arrivalText)
                || !TextUtils.isEmpty(finishText);
    }

    private static boolean canResumeFromManeuverPacket(boolean routeRoadOnly,
                                                       String maneuver,
                                                       String routeActionManeuver,
                                                       String grayRoad) {
        return routeRoadOnly
                || isUsableManeuver(maneuver)
                || isUsableManeuver(routeActionManeuver)
                || !TextUtils.isEmpty(grayRoad);
    }

    private static boolean isWeakOverviewRouteSource(String source) {
        String clean = clean(source).toLowerCase(Locale.US);
        return clean.contains("overview_compact") || clean.contains("overview_route");
    }

    private static boolean routeRoadOnlySource(Intent intent, String sourceLower) {
        String source = clean(sourceLower).toLowerCase(Locale.US);
        String priority = clean(text(intent, "priority")).toLowerCase(Locale.US);
        return source.contains("route_options")
                || source.contains("route_road")
                || priority.contains("route_road");
    }

    private String streetCandidate(String value, String currentValue, String blockedA, String blockedB) {
        String text = normalizeStreetLabel(value);
        if (!looksLikeStreetText(text)) return "";
        if (sameStreet(text, blockedA) || sameStreet(text, blockedB)) return "";
        if (!TextUtils.isEmpty(currentValue) && sameStreet(text, currentValue)) return "";
        return text;
    }

    private String nextStreetCandidate(String value, String currentValue) {
        String text = normalizeStreetLabel(value);
        if (!looksLikeStreetText(text)) return "";
        if (!TextUtils.isEmpty(currentValue) && sameStreet(text, currentValue)) return "";
        return text;
    }

    private static String etaCurrentStreetFromIntent(Intent intent) {
        return first(text(intent, "eta_current_street"), text(intent, "etaCurrentStreet"),
                text(intent, "bottom_current_street"), text(intent, "bottomCurrentStreet"),
                text(intent, "route_current_street"), text(intent, "routeCurrentStreet"),
                text(intent, "current_street"), text(intent, "currentStreet"),
                text(intent, "current_road"), text(intent, "currentRoad"),
                text(intent, "current_road_name"), text(intent, "currentRoadName"),
                text(intent, "current_street_name"), text(intent, "currentStreetName"),
                text(intent, "street"), text(intent, "road"));
    }

    private String etaStreetCandidate(String value, String currentValue) {
        String text = normalizeStreetLabel(value);
        if (TextUtils.isEmpty(text) || !looksLikeStreetText(text)) return "";
        if (!TextUtils.isEmpty(currentValue) && sameStreet(text, currentValue)) return "";
        return text;
    }

    private String finishCandidate(String value) {
        return validFinishStreet(value);
    }

    private String finishForState(String value, boolean finishPointChanged) {
        String text = finishCandidate(value);
        if (finishPointChanged) {
            if (TextUtils.isEmpty(text)) return validFinishStreet(state.finishStreet);
            staleFinishStreetAfterPointChange = "";
            return text;
        }
        if (!TextUtils.isEmpty(text)) {
            staleFinishStreetAfterPointChange = "";
            return text;
        }
        return validFinishStreet(state.finishStreet);
    }

    private static String validFinishStreet(String value) {
        String text = normalizeFinishLabel(value);
        if (TextUtils.isEmpty(text)) return "";
        if (looksLikeCoordinate(text)) return "";
        if (!looksLikeFinishText(text)) return "";
        return text;
    }

    private static String firstValidFinishAddress(String... values) {
        for (String value : values) {
            String text = addDefaultStreetPrefix(validFinishStreet(value));
            if (!TextUtils.isEmpty(text)) return text;
        }
        return "";
    }

    private static String firstValidFinishName(String... values) {
        for (String value : values) {
            String text = validFinishStreet(value);
            if (!TextUtils.isEmpty(text)) return text;
        }
        return "";
    }

    private static boolean looksLikeFinishText(String value) {
        if (TextUtils.isEmpty(value)) return false;
        String v = value.toLowerCase(Locale.US).trim();
        if (isCityOrCountryOnly(v)) return false;
        if (v.startsWith("context_") || v.startsWith("nav_")) return false;
        if (v.startsWith("geo:") || v.startsWith("ymaps") || v.startsWith("yandex")) return false;
        if (looksLikeEncodedToken(value)) return false;
        if (v.contains("source=") || v.contains("finish_point") || v.contains("pointcontext")
                || v.contains("uri=") || v.contains("http://") || v.contains("https://")) return false;
        if (v.contains("camera") || v.contains("камера") || v.contains("скорост")
                || v.contains("контрол")) return false;
        if (v.contains("маневр") || v.contains("налево") || v.contains("направо")
                || v.equals("прямо") || v.contains("развор") || v.contains("круг")) return false;
        if (v.equals("финиш") || v.equals("finish") || v.equals("destination")) return false;
        if (isTimeText(v)) return false;
        if ((v.contains(" м") || v.endsWith("м") || v.contains(" km") || v.contains("км"))
                && NUMBER.matcher(v).find() && v.length() < 14) return false;
        return v.matches(".*[a-zа-яё].*");
    }

    private static boolean looksLikeEncodedToken(String value) {
        String text = clean(value);
        if (TextUtils.isEmpty(text)) return false;
        String lower = text.toLowerCase(Locale.US);
        if (lower.startsWith("djj8") || lower.startsWith("djf8")
                || lower.startsWith("v1|") || lower.startsWith("v2|")) return true;
        boolean hasCyrillic = lower.matches(".*[а-яё].*");
        boolean hasHumanSeparator = lower.matches(".*[\\s.,#№/\\\\-].*");
        String compact = lower.replaceAll("[\\s.,:;#№/\\\\_+=-]", "");
        return !hasCyrillic && !hasHumanSeparator && compact.length() >= 32
                && lower.matches("[a-z0-9_+=/\\\\-]+");
    }

    private static boolean looksLikeStreetText(String value) {
        if (!looksLikeFinishText(value)) return false;
        String v = value.toLowerCase(Locale.US).trim();
        if (v.contains("маневр") || v.contains("налево") || v.contains("направо")
                || v.equals("прямо") || v.contains("развор") || v.contains("круг")
                || v.contains("финиш")) return false;
        return true;
    }

    private static String addDefaultStreetPrefix(String value) {
        String text = clean(value);
        if (TextUtils.isEmpty(text) || hasRoadTypePrefix(text) || looksLikePoiName(text)) return text;
        if (NUMBER.matcher(text).find()
                && text.matches("(?iu).*[a-zа-яё].*")
                && !text.matches("^\\d+.*")) {
            return "ул. " + text;
        }
        return text;
    }

    private static boolean hasRoadTypePrefix(String value) {
        String v = clean(value).toLowerCase(Locale.US);
        return v.matches("(?iu)^(ул\\.|улица|пр-т|проспект|просп\\.|пр-кт|б-р|бул\\.|бульвар|"
                + "пер\\.|переулок|пл\\.|площадь|ш\\.|шоссе|наб\\.|набережная|пр-д|проезд|"
                + "туп\\.|тупик|ал\\.|аллея|тр\\.|тракт|мкр\\.|микрорайон|кв-л|квартал|"
                + "лин\\.|линия|дор\\.|дорога)\\s+.*");
    }

    private static boolean looksLikePoiName(String value) {
        String v = clean(value).toLowerCase(Locale.US);
        return v.contains("аптека") || v.contains("магазин") || v.contains("маркет")
                || v.contains("супермаркет") || v.contains("тц ") || v.contains("трц ")
                || v.contains("кафе") || v.contains("ресторан") || v.contains("банк")
                || v.contains("азс") || v.contains("школ") || v.contains("садик")
                || v.contains("клиник") || v.contains("больниц") || v.contains("отель")
                || v.contains("гостиниц") || v.startsWith("жк ") || v.startsWith("бц ");
    }

    private static boolean looksLikeCoordinate(String value) {
        if (TextUtils.isEmpty(value)) return false;
        if (!value.contains(",")) return false;
        String v = value.replace(',', '.');
        Matcher matcher = NUMBER.matcher(v);
        return matcher.find() && matcher.find() && v.length() <= 48;
    }

    private static boolean sameStreet(String a, String b) {
        if (TextUtils.isEmpty(a) || TextUtils.isEmpty(b)) return false;
        String left = simplifyStreet(a);
        String right = simplifyStreet(b);
        return !TextUtils.isEmpty(left) && left.equals(right);
    }

    private static String simplifyStreet(String value) {
        String v = clean(value).toLowerCase(Locale.US);
        v = v.replaceAll("(?iu)\\b(улица|ул|проспект|просп|пр|пр-т|пр-кт|бульвар|бул|б-р|переулок|пер|"
                + "площадь|пл|шоссе|ш|набережная|наб|проезд|пр-д|тупик|туп|аллея|ал|тракт|тр|"
                + "микрорайон|мкр|квартал|кв-л|линия|лин|дорога|дор)\\b\\.?", "");
        return v.replaceAll("[^a-zа-яё0-9]", "");
    }

    private static String normalizeStreetLabel(String value) {
        String text = clean(value);
        if (TextUtils.isEmpty(text)) return "";
        text = text.replaceAll("\\s*,\\s*", ", ");
        text = text.replaceAll("(?iu)\\bулица\\b\\s*", "ул. ");
        text = text.replaceAll("(?iu)\\bул\\.?\\s+", "ул. ");
        text = text.replaceAll("(?iu)\\bпроспект\\b\\s*", "пр-т ");
        text = text.replaceAll("(?iu)\\bпросп\\.?\\s*", "пр-т ");
        text = text.replaceAll("(?iu)\\bпр-кт\\b\\s*", "пр-т ");
        text = text.replaceAll("(?iu)\\bбульвар\\b\\s*", "бул. ");
        text = text.replaceAll("(?iu)\\bбул\\.?\\s*", "бул. ");
        text = text.replaceAll("(?iu)\\bпереулок\\b\\s*", "пер. ");
        text = text.replaceAll("(?iu)\\bпер\\.?\\s*", "пер. ");
        text = text.replaceAll("(?iu)\\bплощадь\\b\\s*", "пл. ");
        text = text.replaceAll("(?iu)\\bпл\\.?\\s*", "пл. ");
        text = text.replaceAll("(?iu)\\bшоссе\\b\\s*", "ш. ");
        text = text.replaceAll("(?iu)\\bш\\.?\\s+", "ш. ");
        text = text.replaceAll("(?iu)\\bнабережная\\b\\s*", "наб. ");
        text = text.replaceAll("(?iu)\\bнаб\\.?\\s*", "наб. ");
        text = text.replaceAll("(?iu)\\bпроезд\\b\\s*", "пр-д ");
        text = text.replaceAll("(?iu)\\bтупик\\b\\s*", "туп. ");
        text = text.replaceAll("(?iu)\\bаллея\\b\\s*", "ал. ");
        text = text.replaceAll("(?iu)\\bтракт\\b\\s*", "тр. ");
        text = text.replaceAll("(?iu)\\bмикрорайон\\b\\s*", "мкр. ");
        text = text.replaceAll("(?iu)\\bквартал\\b\\s*", "кв-л ");
        text = text.replaceAll("(?iu)\\bлиния\\b\\s*", "лин. ");
        text = text.replaceAll("(?iu)\\bдорога\\b\\s*", "дор. ");
        return clean(text);
    }

    private static String normalizeFinishLabel(String value) {
        String text = normalizeStreetLabel(value);
        if (TextUtils.isEmpty(text)) return "";
        text = text.replaceAll("(?iu)\\b(город|г)\\.?\\s+", "");
        text = firstAddressPart(text);
        text = text.replaceAll("(?iu)^\\s*(казахстан|атырау|алматы|астана|нур-султан)\\s*,\\s*", "");
        text = text.replaceAll("(?iu)\\s*,\\s*(казахстан|атырау|алматы|астана|нур-султан)\\s*$", "");
        return clean(text);
    }

    private static String firstAddressPart(String value) {
        String[] parts = clean(value).split("\\s*,\\s*");
        if (parts.length == 0) return "";
        String firstAddress = "";
        for (String part : parts) {
            String text = clean(part);
            if (TextUtils.isEmpty(text)) continue;
            String lower = text.toLowerCase(Locale.US);
            if (lower.equals("казахстан") || lower.equals("атырау")
                    || lower.equals("алматы") || lower.equals("астана")
                    || lower.equals("нур-султан")) {
                continue;
            }
            if (TextUtils.isEmpty(firstAddress)) {
                firstAddress = text;
                continue;
            }
            if (looksLikeHousePart(text) && hasRoadTypePrefix(firstAddress)) {
                return firstAddress + " " + text;
            }
            return firstAddress;
        }
        return firstAddress;
    }

    private static boolean looksLikeHousePart(String value) {
        String text = clean(value).toLowerCase(Locale.US);
        if (TextUtils.isEmpty(text) || text.length() > 16) return false;
        return text.matches("(?iu)^(д\\.?\\s*)?\\d+[a-zа-яё0-9/\\-]*$");
    }

    private static boolean isCityOrCountryOnly(String value) {
        String v = clean(value).toLowerCase(Locale.US);
        if (TextUtils.isEmpty(v)) return true;
        v = v.replaceAll("(?iu)^\\s*(город|г)\\.?\\s+", "");
        v = v.replaceAll("\\s*,\\s*", ",");
        return v.equals("казахстан")
                || v.equals("атырау")
                || v.equals("алматы")
                || v.equals("астана")
                || v.equals("нур-султан")
                || v.equals("атырау,казахстан")
                || v.equals("алматы,казахстан")
                || v.equals("астана,казахстан")
                || v.equals("нур-султан,казахстан");
    }

    private static boolean isUsableManeuver(String maneuver) {
        if (TextUtils.isEmpty(maneuver)) return false;
        return !isCameraDirection(maneuver);
    }

    private static boolean isOffState(String state) {
        if (TextUtils.isEmpty(state)) return false;
        String s = state.toLowerCase(Locale.US).trim();
        return s.equals("0") || s.equals("false") || s.equals("off")
                || s.equals("close") || s.equals("closed") || s.equals("idle");
    }

    private static String[] distance(Intent intent) {
        float meters = intent.getFloatExtra("distance_val", -1f);
        if (meters > 0f) {
            if (meters >= 1000f) return new String[]{String.format(Locale.US, "%.1f", meters / 1000f), "км"};
            return new String[]{String.valueOf(Math.round(meters)), "м"};
        }
        String value = first(text(intent, "distance_val_str"), text(intent, "distance"));
        if (TextUtils.isEmpty(value)) return null;
        String unit = first(text(intent, "distance_unit"), "м");
        return new String[]{value, unit};
    }

    private static String imageId(String direction, int lr) {
        String p = direction == null ? "" : direction.toLowerCase(Locale.US);
        boolean left = lr == 1 || p.contains("left") || p.contains("лев");
        boolean right = lr == 2 || p.contains("right") || p.contains("прав");
        if (p.contains("finish") || p.contains("arriv") || p.contains("destination") || p.contains("финиш")) {
            return "context_ra_finish";
        }
        if (p.contains("round") || p.contains("круг")) return "context_ra_in_circular_movement";
        if (p.contains("uturn") || p.contains("развор")) {
            if (left) return "context_ra_turn_back_left";
            if (right) return "context_ra_turn_back_right";
            return "context_ra_turn_back";
        }
        if (left) return p.contains("take") || p.contains("keep") || p.contains("slight")
                || p.contains("fork") || p.contains("exit") || p.contains("ramp")
                ? "context_ra_exit_left" : "context_ra_turn_left";
        if (right) return p.contains("take") || p.contains("keep") || p.contains("slight")
                || p.contains("fork") || p.contains("exit") || p.contains("ramp")
                ? "context_ra_exit_right" : "context_ra_turn_right";
        return "context_ra_forward";
    }

    private static String routeActionManeuver(Intent intent) {
        String actionValue = first(text(intent, "route_action"), text(intent, "routeAction"),
                text(intent, "route_road_action"), text(intent, "routeRoadAction"),
                text(intent, "maneuver_action"),
                textByKeyPart(intent, "route_action"));
        String sectionValue = first(text(intent, "route_section"), text(intent, "routeSection"));
        String value = first(actionValue, sectionValue);
        String p = clean(value).toLowerCase(Locale.US).replace('-', '_');
        if (TextUtils.isEmpty(p)) return "";
        if (p.contains("turn_back_right") || p.contains("uturn_right") || p.contains("u_turn_right")) {
            return "context_ra_turn_back_right";
        }
        if (p.contains("turn_back_left") || p.contains("turn_back")
                || p.contains("uturn_left") || p.contains("u_turn_left") || p.contains("развор")) {
            return "context_ra_turn_back_left";
        }
        String roundaboutText = clean(value + " " + sectionValue)
                .toLowerCase(Locale.US).replace('-', '_');
        if (p.contains("roundabout") || p.contains("circular") || p.contains("круг")
                || roundaboutText.contains("leave_roundabout")) {
            String exit = first(roundaboutExitFromRouteSection(sectionValue),
                    roundaboutExitFromText(p));
            return TextUtils.isEmpty(exit) ? "context_ra_in_circular_movement" : exit;
        }
        if (p.contains("sharp_right") || p.contains("hard_right")
                || p.contains("hard_turn_right")) return "context_ra_hard_turn_right";
        if (p.contains("sharp_left") || p.contains("hard_left")
                || p.contains("hard_turn_left")) return "context_ra_hard_turn_left";
        if (p.contains("exit_right") || p.contains("ramp_right")
                || p.contains("slight_right") || p.contains("keep_right")
                || p.contains("take_right") || p.contains("fork_right")) {
            return p.contains("take_right") ? "context_ra_take_right" : "context_ra_exit_right";
        }
        if (p.contains("exit_left") || p.contains("ramp_left")
                || p.contains("slight_left") || p.contains("keep_left")
                || p.contains("take_left") || p.contains("fork_left")) {
            return p.contains("take_left") ? "context_ra_take_left" : "context_ra_exit_left";
        }
        if (p.contains("right") || p.contains("направо")) {
            return routeActionRightBranch(intent) ? "context_ra_exit_right" : "context_ra_turn_right";
        }
        if (p.contains("left") || p.contains("налево")) {
            return routeActionLeftBranch(intent) ? "context_ra_exit_left" : "context_ra_turn_left";
        }
        if (p.contains("straight") || p.contains("forward") || p.contains("прям")) {
            return "context_ra_forward";
        }
        return "";
    }

    private static boolean routeRoadActionContradictedByLaneTruth(Intent intent, String sourceLower,
                                                                  String routeActionManeuver,
                                                                  String laneHighlightManeuver) {
        if (!isUsableManeuver(routeActionManeuver)) return false;
        if (routeRoadActionKeepsPriority(routeActionManeuver)) return false;
        if (isUsableManeuver(laneHighlightManeuver)
                && !sameManeuverFamily(routeActionManeuver, laneHighlightManeuver)
                && !routeActionHighlightMatches(intent, routeActionManeuver)) {
            return true;
        }
        if (maneuverNeedsLaneToken(routeActionManeuver, "right")
                && routeRoadForbidsLaneToken(intent, sourceLower, "right")) {
            return true;
        }
        if (maneuverNeedsLaneToken(routeActionManeuver, "left")
                && routeRoadForbidsLaneToken(intent, sourceLower, "left")) {
            return true;
        }
        if (maneuverNeedsLaneToken(routeActionManeuver, "straight")
                && routeRoadForbidsLaneToken(intent, sourceLower, "straight")) {
            return true;
        }
        return false;
    }

    private static boolean routeRoadActionKeepsPriority(String maneuver) {
        String value = clean(maneuver);
        return isFinishManeuver(value)
                || isRoundaboutManeuver(value)
                || value.contains("turn_back")
                || value.contains("uturn")
                || value.contains("hard_turn");
    }

    private static boolean maneuverNeedsLaneToken(String maneuver, String token) {
        String value = clean(maneuver);
        if (TextUtils.isEmpty(value) || TextUtils.isEmpty(token)) return false;
        if ("right".equals(token)) return value.contains("right");
        if ("left".equals(token)) return value.contains("left");
        if ("straight".equals(token)) {
            return value.contains("forward") || value.contains("straight");
        }
        return false;
    }

    private static boolean routeRoadForbidsLaneToken(Intent intent, String sourceLower, String token) {
        if ("right".equals(token)
                && hasAnyExtra(intent, "lane_has_right", "road_has_right", "has_right",
                "can_go_right", "allow_right", "available_right")) {
            return !boolAny(intent, "lane_has_right", "road_has_right", "has_right",
                    "can_go_right", "allow_right", "available_right");
        }
        if ("left".equals(token)
                && hasAnyExtra(intent, "lane_has_left", "road_has_left", "has_left",
                "can_go_left", "allow_left", "available_left")) {
            return !boolAny(intent, "lane_has_left", "road_has_left", "has_left",
                    "can_go_left", "allow_left", "available_left");
        }
        if ("straight".equals(token)
                && hasAnyExtra(intent, "lane_has_straight", "road_has_straight", "has_straight",
                "can_go_straight", "allow_straight", "available_straight")) {
            return !boolAny(intent, "lane_has_straight", "road_has_straight", "has_straight",
                    "can_go_straight", "allow_straight", "available_straight");
        }
        String topology = grayRoadTopologyText(intent, sourceLower);
        return !TextUtils.isEmpty(topology) && !containsLaneToken(topology, token);
    }

    private static boolean shouldPreferRouteActionManeuver(Intent intent, String imageManeuver,
                                                           String routeActionManeuver) {
        if (!isUsableManeuver(routeActionManeuver)) return false;
        if (!isUsableManeuver(imageManeuver)) return true;
        if (routeActionManeuver.equals(imageManeuver)) return false;
        if (isFinishManeuver(imageManeuver) && !isFinishManeuver(routeActionManeuver)) return false;

        String action = clean(first(text(intent, "route_action"), text(intent, "routeAction"),
                textByKeyPart(intent, "route_action"))).toLowerCase(Locale.US).replace('-', '_');
        if (TextUtils.isEmpty(action)) return false;

        if (routeActionManeuver.contains("turn_back")) return true;
        if (isRoundaboutManeuver(routeActionManeuver)) {
            return isRoundaboutManeuver(imageManeuver)
                    || !isUsableManeuver(imageManeuver)
                    || routeActionHighlightMatches(intent, routeActionManeuver);
        }
        if (routeActionHighlightMatches(intent, routeActionManeuver)) return true;

        String imageFamily = maneuverFamily(imageManeuver);
        String actionFamily = maneuverFamily(routeActionManeuver);
        return !TextUtils.isEmpty(imageFamily)
                && !TextUtils.isEmpty(actionFamily)
                && !imageFamily.equals(actionFamily)
                && ("left".equals(actionFamily) || "right".equals(actionFamily)
                || "uturn".equals(actionFamily));
    }

    private static boolean shouldPromoteRouteRoadActionAsMain(boolean routeRoadOnly,
                                                              String routeActionManeuver,
                                                              String displayManeuver,
                                                              String distanceText,
                                                              boolean rejectedAsMain,
                                                              Intent intent) {
        if (!routeRoadOnly || rejectedAsMain || !isUsableManeuver(routeActionManeuver)) return false;
        if (!routeRoadPriorityActionConfirmed(intent, routeActionManeuver, distanceText)) return false;
        if (isUsableManeuver(displayManeuver)
                && !routeActionManeuver.equals(displayManeuver)
                && !sameManeuverFamily(routeActionManeuver, displayManeuver)) {
            return false;
        }
        return true;
    }

    private static boolean routeRoadPriorityActionConfirmed(Intent intent, String routeActionManeuver,
                                                            String distanceText) {
        if (!isPriorityEventManeuver(routeActionManeuver)) return false;
        return routeActionHighlightMatches(intent, routeActionManeuver)
                || !TextUtils.isEmpty(nonZeroDistance(distanceText))
                || routeActionTextConfirmsPriority(intent, routeActionManeuver);
    }

    private static boolean routeActionTextConfirmsPriority(Intent intent, String maneuver) {
        String action = clean(first(text(intent, "route_action"), text(intent, "routeAction"),
                text(intent, "route_road_action"), text(intent, "routeRoadAction"),
                textByKeyPart(intent, "route_action"), text(intent, "route_section"),
                text(intent, "routeSection"))).toLowerCase(Locale.US).replace('-', '_');
        String value = clean(maneuver).toLowerCase(Locale.US);
        if (TextUtils.isEmpty(action) || TextUtils.isEmpty(value)) return false;
        if (value.contains("turn_back") || value.contains("uturn")) {
            return containsAny(action, "turn_back", "u_turn", "uturn", "u-turn",
                    "left180", "right180", "развор");
        }
        if (isRoundaboutManeuver(value)) {
            return containsAny(action, "round", "circular", "кольц", "круг");
        }
        if (value.contains("hard_turn")) {
            return containsAny(action, "hard", "sharp", "резк", "круто");
        }
        if (value.contains("exit_") || value.contains("take_")) {
            return containsAny(action, "exit", "ramp", "take", "fork",
                    "slight", "keep", "slip", "съезд", "держ");
        }
        return false;
    }

    private static String visibleRouteActionManeuver(String routeActionManeuver, String displayManeuver) {
        if (!isUsableManeuver(routeActionManeuver)) return "";
        if (!isUsableManeuver(displayManeuver)) return routeActionManeuver;
        if (routeActionManeuver.equals(displayManeuver)) return routeActionManeuver;
        if (sameManeuverFamily(routeActionManeuver, displayManeuver)) return routeActionManeuver;
        return "";
    }

    private static boolean routeActionHighlightMatches(Intent intent, String maneuver) {
        String value = clean(maneuver);
        if (value.contains("turn_back_left")) return laneHighlightHas(intent, "left180");
        if (value.contains("turn_back_right")) return laneHighlightHas(intent, "right180");
        if (value.contains("left")) return laneHighlightHas(intent, "left");
        if (value.contains("right")) return laneHighlightHas(intent, "right");
        if (value.contains("forward") || value.contains("straight")) {
            return laneHighlightHas(intent, "straight");
        }
        return false;
    }

    private static boolean routeActionRightBranch(Intent intent) {
        return routeActionBranch(intent, true);
    }

    private static boolean routeActionLeftBranch(Intent intent) {
        return routeActionBranch(intent, false);
    }

    private static boolean routeActionBranch(Intent intent, boolean right) {
        String action = clean(first(text(intent, "route_action"), text(intent, "routeAction"),
                textByKeyPart(intent, "route_action"))).toLowerCase(Locale.US).replace('-', '_');
        String section = clean(first(text(intent, "route_section"), text(intent, "routeSection")))
                .toLowerCase(Locale.US).replace('-', '_');
        String lanes = clean(first(laneRawItemsText(intent), grayRoadTopologyText(intent)))
                .toLowerCase(Locale.US).replace('-', '_');
        String all = action + " " + section + " " + lanes;
        String side = right ? "right" : "left";
        String ruSide = right ? "прав" : "лев";
        if (all.contains("exit_" + side) || all.contains("ramp_" + side)
                || all.contains("slight_" + side) || all.contains("fork_" + side)
                || all.contains("take_" + side) || all.contains("slip_" + side)
                || (all.contains("съезд") && all.contains(ruSide))) {
            return true;
        }
        return false;
    }

    private static boolean laneMicroSource(String sourceLower) {
        String value = clean(sourceLower).toLowerCase(Locale.US);
        return value.contains("micro")
                || value.contains("direction_sign")
                || value.contains("auto_widget_signs");
    }

    private static boolean laneDistanceOnlySource(String sourceLower) {
        String value = clean(sourceLower).toLowerCase(Locale.US);
        return value.contains("lane_distance")
                || value.contains("lane_guidance")
                || value.contains("guidance_tick")
                || value.contains("rendered_lane")
                || value.contains("projected_lanes")
                || value.contains("panel_lanes");
    }

    private static boolean mainGuidanceDistanceTick(String sourceLower, Intent intent,
                                                    String explicitLaneDistance,
                                                    String maneuverDistance) {
        String value = clean(sourceLower).toLowerCase(Locale.US);
        if (!value.contains("guidance_tick")) return false;
        if (!TextUtils.isEmpty(nonZeroDistance(explicitLaneDistance))) return false;
        if (bool(intent, "lane_guidance", false) || hasLaneData(intent)) return false;
        return !TextUtils.isEmpty(nonZeroDistance(maneuverDistance));
    }

    private static boolean ignoredLaneDebugSource(String sourceLower) {
        String value = clean(sourceLower).toLowerCase(Locale.US);
        return value.contains("lane_guidance_debug")
                || value.contains("rendered_lane_debug")
                || value.contains("projected_lanes_debug");
    }

    private static boolean trustedLaneDirectionSource(String sourceLower) {
        String value = clean(sourceLower).toLowerCase(Locale.US);
        if (value.contains("visual_lane_highlight")) return true;
        if (value.contains("visual_lane_single")) return true;
        if (value.contains(YandexCoreBridgeContract.SOURCE)) return true;
        if (laneDistanceOnlySource(value)) return false;
        return value.contains("route_road")
                || value.contains("route_options")
                || value.contains("direction_sign")
                || value.contains("auto_widget_signs")
                || value.contains("micro");
    }

    private static String fallbackLaneMicroManeuver(Intent intent, String sourceLower, String grayRoad) {
        String highlighted = laneHighlightedManeuver(intent, sourceLower);
        if (isUsableManeuver(highlighted)) return highlighted;
        if (!containsAny(sourceLower, "direction_sign", "auto_widget_signs",
                "visual_lane_highlight", "micro")) {
            return "";
        }
        return directionManeuverFromText(first(text(intent, "lane_maneuver"),
                text(intent, "lane_highlight"), text(intent, "highlighted_directions"),
                text(intent, "recommended_lanes")));
    }

    private static String visualLaneHighlightedManeuver(Intent intent) {
        String highlighted = first(providerLaneHighlightText(intent),
                highlightedDirectionSignText(visualLaneItemsText(intent)));
        if (TextUtils.isEmpty(highlighted)) return "";
        return directionManeuverFromText(highlighted);
    }

    private static String visualLaneSingleManeuver(Intent intent, String sourceLower) {
        if (!signOnlyLaneSource(sourceLower)) return "";
        String text = visualLaneItemsText(intent);
        if (TextUtils.isEmpty(text)) return "";
        boolean uturnLeft = containsLaneToken(text, "left180")
                || containsLaneToken(text, "uturn_left")
                || containsLaneToken(text, "turn_back_left")
                || containsLaneToken(text, "u_turn_left");
        boolean uturnRight = containsLaneToken(text, "right180")
                || containsLaneToken(text, "uturn_right")
                || containsLaneToken(text, "turn_back_right")
                || containsLaneToken(text, "u_turn_right");
        boolean straight = containsLaneToken(text, "straight");
        boolean left = visualLaneHasLeft(text) && !uturnLeft;
        boolean right = visualLaneHasRight(text) && !uturnRight;
        int families = (uturnLeft || uturnRight ? 1 : 0)
                + (left ? 1 : 0)
                + (right ? 1 : 0)
                + (straight ? 1 : 0);
        if (families != 1) return "";
        if (uturnLeft) return "context_ra_turn_back_left";
        if (uturnRight) return "context_ra_turn_back_right";
        if (left) return "context_ra_turn_left";
        if (right) return "context_ra_turn_right";
        if (straight) return "context_ra_forward";
        return "";
    }

    private static String visualLaneGrayRoadManeuver(Intent intent) {
        String text = first(providerLaneHighlightText(intent), visualLaneItemsText(intent));
        if (TextUtils.isEmpty(text)) return "";
        boolean straight = containsLaneToken(text, "straight");
        boolean left = visualLaneHasLeft(text);
        boolean right = visualLaneHasRight(text);
        return grayRoadFromBasicDirections(straight, left, right);
    }

    private static String visualLaneItemsText(Intent intent) {
        return first(text(intent, "raw_lane_items"),
                text(intent, "ignored_raw_lane_items"),
                text(intent, "lane_topology_json"),
                text(intent, "direction_sign_items"),
                text(intent, "raw_direction_sign_items"),
                text(intent, "lane_items"),
                text(intent, "ignored_lane_items"),
                text(intent, "ignored_recommended_lanes"),
                text(intent, "ignored_lane_type"),
                text(intent, "ignored_allowed_directions"));
    }

    private static String providerLaneHighlightText(Intent intent) {
        return first(text(intent, "lane_highlight"),
                text(intent, "lane_highlighted_direction"),
                text(intent, "highlighted_direction"),
                text(intent, "highlighted_directions"),
                text(intent, "recommended_lanes"),
                text(intent, "ignored_recommended_lanes"),
                text(intent, "ignored_lane_maneuver"));
    }

    private static boolean providerVisualLaneSource(String sourceLower) {
        String value = clean(sourceLower).toLowerCase(Locale.US);
        return value.contains(YandexCoreBridgeContract.SOURCE);
    }

    private static boolean staleProviderLaneTopologyConflict(Intent intent, String sourceLower,
                                                             String mainManeuver,
                                                             String highlightManeuver,
                                                             String laneDistance) {
        if (!providerVisualLaneSource(sourceLower)) return false;
        if (!TextUtils.isEmpty(nonZeroDistance(laneDistance))) return false;
        if (!isUsableManeuver(mainManeuver) || !isUsableManeuver(highlightManeuver)) return false;
        if (sameManeuverFamily(mainManeuver, highlightManeuver)) return false;
        String topology = first(providerRawLaneTopologyText(intent),
                explicitGrayRoadTopologyText(intent),
                text(intent, "allowed_directions"),
                text(intent, "allowedDirections"));
        if (TextUtils.isEmpty(topology)) return false;
        String mainFamily = maneuverFamily(mainManeuver);
        String highlightFamily = maneuverFamily(highlightManeuver);
        return ("left".equals(mainFamily) && "right".equals(highlightFamily))
                || ("right".equals(mainFamily) && "left".equals(highlightFamily));
    }

    private static boolean hasVisualLaneItems(Intent intent) {
        return !TextUtils.isEmpty(visualLaneItemsText(intent));
    }

    private static String visualLaneDirectionsText(Intent intent) {
        String text = first(providerLaneHighlightText(intent), visualLaneItemsText(intent));
        if (TextUtils.isEmpty(text)) return "";
        boolean straight = containsLaneToken(text, "straight");
        boolean left = visualLaneHasLeft(text);
        boolean right = visualLaneHasRight(text);
        StringBuilder out = new StringBuilder();
        appendPart(out, "прямо", straight);
        appendPart(out, "налево", left);
        appendPart(out, "направо", right);
        return out.toString();
    }

    private static boolean visualLaneHasLeft(String text) {
        String value = clean(text).toLowerCase(Locale.US);
        return containsLaneToken(value, "left");
    }

    private static boolean visualLaneHasRight(String text) {
        String value = clean(text).toLowerCase(Locale.US);
        return containsLaneToken(value, "right");
    }

    private static String grayRoadFromBasicDirections(boolean straight, boolean left, boolean right) {
        if (straight && left && right) return "context_ra_gray_straight_left_right";
        if (straight && left) return "context_ra_gray_straight_left";
        if (straight && right) return "context_ra_gray_straight_right";
        if (left && right) return "context_ra_gray_left_right";
        if (straight) return "context_ra_gray_straight";
        if (left) return "context_ra_gray_left";
        if (right) return "context_ra_gray_right";
        return "";
    }

    private static String mergeGrayRoads(String firstGray, String secondGray) {
        int mask = grayRoadMask(firstGray) | grayRoadMask(secondGray);
        return mask <= 0 ? first(firstGray, secondGray) : grayRoadFromMask(mask);
    }

    private static boolean yandexLaneDistanceSource(String source) {
        String value = clean(source).toLowerCase(Locale.US);
        if (!value.contains("yandex")) return false;
        return value.contains("lane_distance")
                || value.contains("guidance_tick")
                || value.contains("direction_sign")
                || value.contains("auto_widget_signs");
    }

    private static String laneHighlightedManeuver(Intent intent, String sourceLower) {
        if (!trustedLaneDirectionSource(sourceLower)) return "";
        boolean uturnLeft = laneHighlightHas(intent, "left180");
        boolean uturnRight = laneHighlightHas(intent, "right180");
        boolean left = laneHighlightHas(intent, "left");
        boolean right = laneHighlightHas(intent, "right");
        boolean straight = laneHighlightHas(intent, "straight");
        int families = (uturnLeft || uturnRight ? 1 : 0)
                + (left ? 1 : 0)
                + (right ? 1 : 0)
                + (straight ? 1 : 0);
        if (families != 1) return "";
        if (uturnLeft) return "context_ra_turn_back_left";
        if (uturnRight) return "context_ra_turn_back_right";
        if (left) return "context_ra_turn_left";
        if (right) return "context_ra_turn_right";
        if (straight) return "context_ra_forward";
        return "";
    }

    private static String directionSignManeuver(Intent intent) {
        String raw = directionSignItemsText(intent);
        if (TextUtils.isEmpty(raw)) return "";
        String highlighted = highlightedDirectionSignText(raw);
        return directionManeuverFromText(first(highlighted, raw));
    }

    private static String directionManeuverFromText(String raw) {
        String value = clean(raw).toLowerCase(Locale.US).replace('-', '_');
        if (TextUtils.isEmpty(value)) return "";
        if (containsDirectionToken(value, "right180")) return "context_ra_turn_back_right";
        if (containsDirectionToken(value, "left180")) return "context_ra_turn_back_left";
        if (containsDirectionToken(value, "uturn")) return "context_ra_turn_back";
        boolean exitRight = containsAny(value, "exit_right", "ramp_right", "take_right",
                "slip_right", "fork_right");
        boolean exitLeft = containsAny(value, "exit_left", "ramp_left", "take_left",
                "slip_left", "fork_left");
        boolean right = exitRight || containsDirectionToken(value, "right");
        boolean left = exitLeft || containsDirectionToken(value, "left");
        boolean straight = containsDirectionToken(value, "straight");
        int families = (left ? 1 : 0) + (right ? 1 : 0) + (straight ? 1 : 0);
        if (families != 1) return "";
        if (exitRight) return "context_ra_exit_right";
        if (exitLeft) return "context_ra_exit_left";
        if (right) return "context_ra_turn_right";
        if (left) return "context_ra_turn_left";
        if (straight) return "context_ra_forward";
        return "";
    }

    private static String highlightedDirectionSignText(String raw) {
        String text = clean(raw);
        if (TextUtils.isEmpty(text)) return "";
        StringBuilder out = new StringBuilder();
        Matcher matcher = Pattern.compile("highlight=([^\\s|,;]+)").matcher(text);
        while (matcher.find()) {
            if (out.length() > 0) out.append(' ');
            out.append(matcher.group(1));
        }
        return out.toString();
    }

    private static String directionSignItemsText(Intent intent) {
        return first(text(intent, "direction_sign_items"),
                text(intent, "raw_direction_sign_items"),
                text(intent, "direction_sign"),
                text(intent, "road_sign"));
    }

    private static boolean containsDirectionToken(String value, String token) {
        String text = clean(value).toLowerCase(Locale.US);
        if (TextUtils.isEmpty(text)) return false;
        if ("straight".equals(token)) {
            return containsAny(text, "straightahead", "straight_ahead", "straight",
                    "forward", "ahead", "прям");
        }
        if ("right".equals(token)) {
            return containsAny(text, "right90", "right45", "right135", "rightshift",
                    "rightfromleft", "turn_right", "right", "направ", "прав");
        }
        if ("left".equals(token)) {
            return containsAny(text, "left90", "left45", "left135", "leftshift",
                    "leftfromright", "turn_left", "left", "налев", "лев");
        }
        if ("right180".equals(token)) {
            return containsAny(text, "right180", "turn_back_right", "uturn_right",
                    "u_turn_right");
        }
        if ("left180".equals(token)) {
            return containsAny(text, "left180", "turn_back_left", "uturn_left",
                    "u_turn_left");
        }
        if ("uturn".equals(token)) {
            return containsAny(text, "turn_back", "u_turn", "uturn", "развор");
        }
        return containsAny(text, token);
    }

    private static boolean containsAny(String value, String... tokens) {
        String text = clean(value).toLowerCase(Locale.US);
        if (TextUtils.isEmpty(text)) return false;
        for (String token : tokens) {
            if (!TextUtils.isEmpty(token) && text.contains(token.toLowerCase(Locale.US))) {
                return true;
            }
        }
        return false;
    }

    private static String dashboardManeuver(String icon, String description) {
        String value = clean(first(icon, description));
        String p = clean((icon == null ? "" : icon) + " " + (description == null ? "" : description))
                .toLowerCase(Locale.US)
                .replace('-', '_');
        if (TextUtils.isEmpty(p)) return "";
        if (p.contains("roundabout") || p.contains("circular") || p.contains("кольц")
                || p.contains("круг")) {
            String exit = roundaboutExitFromText(p);
            if (!TextUtils.isEmpty(exit)) return exit;
            return "context_ra_in_circular_movement";
        }
        boolean left = p.contains("left") || p.contains("лев");
        boolean right = p.contains("right") || p.contains("прав");
        if (p.contains("u_turn") || p.contains("uturn") || p.contains("turn_back")
                || p.contains("развор")) {
            if (right) return "context_ra_turn_back_right";
            if (left) return "context_ra_turn_back_left";
            return "context_ra_turn_back";
        }
        if (right && (p.contains("sharp") || p.contains("hard") || p.contains("резко"))) {
            return "context_ra_hard_turn_right";
        }
        if (left && (p.contains("sharp") || p.contains("hard") || p.contains("резко"))) {
            return "context_ra_hard_turn_left";
        }
        if (right && (p.contains("exit") || p.contains("ramp") || p.contains("съезд"))) {
            return "context_ra_exit_right";
        }
        if (left && (p.contains("exit") || p.contains("ramp") || p.contains("съезд"))) {
            return "context_ra_exit_left";
        }
        if (right && (p.contains("keep") || p.contains("slight") || p.contains("take")
                || p.contains("держитесь") || p.contains("правее"))) {
            return "context_ra_exit_right";
        }
        if (left && (p.contains("keep") || p.contains("slight") || p.contains("take")
                || p.contains("держитесь") || p.contains("левее"))) {
            return "context_ra_exit_left";
        }
        if (right || p.contains("направо")) return "context_ra_turn_right";
        if (left || p.contains("налево")) return "context_ra_turn_left";
        if (p.contains("straight") || p.contains("forward") || p.contains("прямо")) {
            return "context_ra_forward";
        }
        if (containsFinishToken(p)) return "";
        String normalized = normalizeManeuver(value, 0);
        return containsFinishToken(normalized.toLowerCase(Locale.US)) ? "" : normalized;
    }

    private static boolean dashboardFinishReached(String icon, String routeDistance,
                                                  String maneuverDistance) {
        if (routeFinished(routeDistance) || routeFinished(maneuverDistance)) return true;
        String iconText = clean(icon).toLowerCase(Locale.US).replace('-', '_');
        if (!containsFinishToken(iconText)) return false;
        float routeMeters = distanceMeters(routeDistance);
        float maneuverMeters = distanceMeters(maneuverDistance);
        float meters = routeMeters > 0f ? routeMeters : maneuverMeters;
        if (meters > 0f) return meters <= DASHBOARD_FINISH_METERS;
        return false;
    }

    private static boolean containsFinishToken(String value) {
        if (TextUtils.isEmpty(value)) return false;
        String p = value.toLowerCase(Locale.US);
        return p.contains("finish") || p.contains("arriv") || p.contains("destination")
                || p.contains("финиш") || p.contains("место назначения");
    }

    private static boolean dgisMicroManeuver(String maneuver, String rawText, String distanceText) {
        if (isFinishManeuver(maneuver) || isRoundaboutManeuver(maneuver)) return false;
        String p = clean(rawText).toLowerCase(Locale.US).replace('-', '_');
        if (p.contains("lane") || p.contains("полос") || p.contains("ряд")
                || p.contains("держитесь") || p.contains("левее") || p.contains("правее")) {
            return true;
        }
        if (p.contains("съезд") || p.contains("exit") || p.contains("ramp")) return true;
        float meters = distanceMeters(distanceText);
        if (meters <= 0f || meters > DGIS_MICRO_DISTANCE_METERS) return false;
        String id = clean(maneuver);
        return id.contains("take_") || id.contains("exit_") || id.contains("hard_turn")
                || id.contains("turn_back");
    }

    private static String roundaboutExitFromText(String value) {
        String p = clean(value).toLowerCase(Locale.US);
        String numbered = roundaboutExitNumberFromText(p);
        if (!TextUtils.isEmpty(numbered)) return "context_ra_roundabout_exit_" + numbered;
        if (p.contains("first") || p.contains("перв")) {
            return "context_ra_roundabout_exit_1";
        }
        if (p.contains("second") || p.contains("втор")) {
            return "context_ra_roundabout_exit_2";
        }
        if (p.contains("third") || p.contains("трет")) {
            return "context_ra_roundabout_exit_3";
        }
        if (p.contains("fourth") || p.contains("четв")) {
            return "context_ra_roundabout_exit_4";
        }
        return "";
    }

    private static String roundaboutExitFromRouteSection(String value) {
        String text = clean(value);
        if (TextUtils.isEmpty(text)) return "";
        Matcher description = Pattern.compile("description=([^|;]*?)(?:\\s+raw=|\\s+toponym=|,|;|\\||$)",
                        Pattern.CASE_INSENSITIVE)
                .matcher(text);
        if (description.find()) {
            String parsed = roundaboutExitFromText(description.group(1));
            if (!TextUtils.isEmpty(parsed)) return parsed;
        }
        Matcher exit = Pattern.compile("(\\d+)\\s*[-\\u2010-\\u2015]?\\s*(?:й|st|nd|rd|th)?\\s*(?:съезд|exit)",
                        Pattern.CASE_INSENSITIVE)
                .matcher(text);
        if (exit.find()) return roundaboutExitFromText(exit.group(1));
        return "";
    }

    private static String normalizeManeuver(String maneuver, int lr) {
        String value = clean(maneuver);
        if (TextUtils.isEmpty(value)) return value;
        String p = value.toLowerCase(Locale.US);
        if (p.startsWith("context_ra_")) return canonicalManeuver(value);
        if (p.contains("round") || p.contains("круг")) {
            String exit = roundaboutExitFromText(p);
            return TextUtils.isEmpty(exit) ? "context_ra_in_circular_movement" : exit;
        }
        if (p.contains("turn_back") || p.contains("uturn") || p.contains("развор")) return imageId(value, lr);
        if ((p.contains("exit") || p.contains("ramp") || p.contains("съезд"))
                && (p.contains("right") || p.contains("прав"))) return "context_ra_exit_right";
        if ((p.contains("exit") || p.contains("ramp") || p.contains("съезд"))
                && (p.contains("left") || p.contains("лев"))) return "context_ra_exit_left";
        if (p.contains("keep_left") || p.contains("slight_left") || p.contains("fork_left")) {
            return "context_ra_exit_left";
        }
        if (p.contains("keep_right") || p.contains("slight_right") || p.contains("fork_right")) {
            return "context_ra_exit_right";
        }
        if (p.contains("sharp_left") || p.contains("hard_left")) return "context_ra_hard_turn_left";
        if (p.contains("sharp_right") || p.contains("hard_right")) return "context_ra_hard_turn_right";
        if (p.contains("turn_left") || p.contains("left") || p.contains("налево")) return "context_ra_turn_left";
        if (p.contains("turn_right") || p.contains("right") || p.contains("направо")) return "context_ra_turn_right";
        if (p.contains("forward") || p.contains("straight") || p.contains("прям")) return "context_ra_forward";
        return value;
    }

    private static String canonicalManeuver(String maneuver) {
        String p = clean(maneuver).toLowerCase(Locale.US);
        if (p.contains("keep_left") || p.contains("slight_left") || p.contains("fork_left")) {
            return "context_ra_exit_left";
        }
        if (p.contains("keep_right") || p.contains("slight_right") || p.contains("fork_right")) {
            return "context_ra_exit_right";
        }
        if (p.contains("sharp_left")) return "context_ra_hard_turn_left";
        if (p.contains("sharp_right")) return "context_ra_hard_turn_right";
        if (p.contains("uturn_left")) return "context_ra_turn_back_left";
        if (p.contains("uturn_right")) return "context_ra_turn_back_right";
        return clean(maneuver);
    }

    private static String maneuverLabel(String maneuver) {
        return maneuverLabel(maneuver, "");
    }

    private static String maneuverLabel(String maneuver, String roundaboutExit) {
        String p = maneuver == null ? "" : maneuver.toLowerCase(Locale.US);
        if (p.contains("finish") || p.contains("arriv") || p.contains("destination")) return "финиш";
        if (p.contains("out_circular") || p.contains("leave_roundabout")) return "съезд с кругового";
        if (p.contains("roundabout_exit_")) {
            String exit = first(roundaboutExit, roundaboutExitLabelFromId(p));
            return TextUtils.isEmpty(exit) ? "съезд с кругового" : "круговое, " + exit;
        }
        if (p.contains("round") || p.contains("circular") || p.contains("круг")) {
            return TextUtils.isEmpty(roundaboutExit) ? "круговое движение" : "круговое, " + roundaboutExit;
        }
        if (p.contains("turn_back") || p.contains("uturn") || p.contains("развор")) {
            if (p.contains("right")) return "разворот направо";
            if (p.contains("left")) return "разворот налево";
            return "разворот";
        }
        if (p.contains("keep_left")) return "держаться левее";
        if (p.contains("keep_right")) return "держаться правее";
        if (p.contains("exit_left")) return "съезд налево";
        if (p.contains("exit_right")) return "съезд направо";
        if (p.contains("take_left")) return "держитесь левее";
        if (p.contains("take_right")) return "держитесь правее";
        if (p.contains("hard_turn_left")) return "резко налево";
        if (p.contains("hard_turn_right")) return "резко направо";
        if (p.contains("turn_left") || p.contains("left")) return "налево";
        if (p.contains("turn_right") || p.contains("right")) return "направо";
        if (p.contains("straight") || p.contains("forward")) return "прямо";
        if ("unknown".equals(p)) return "расчёт маршрута";
        if (TextUtils.isEmpty(maneuver)) return "";
        return maneuver;
    }

    private static String roundaboutExit(Intent intent, String maneuver) {
        if (!isRoundaboutManeuver(maneuver) && !hasRoundaboutContext(intent)) return "";
        String explicit = first(text(intent, "exit_number"), text(intent, "exitNumber"),
                text(intent, "roundabout_exit"), text(intent, "roundaboutExit"),
                text(intent, "circular_exit"), text(intent, "circularExit"),
                text(intent, "exit_name"), text(intent, "exitName"),
                text(intent, "exit"), text(intent, "direction_sign"), text(intent, "directionSign"),
                text(intent, "road_sign"), text(intent, "roadSign"), textByKeyPart(intent, "exit"),
                textByKeyPart(intent, "roundabout"), textByKeyPart(intent, "circular"));
        String parsed = roundaboutExitText(explicit);
        if (!TextUtils.isEmpty(parsed)) return parsed;
        parsed = roundaboutExitFromExtras(intent);
        if (!TextUtils.isEmpty(parsed)) return parsed;
        return roundaboutExitText(maneuver);
    }

    private static String roundaboutExitLabelFromId(String maneuver) {
        Matcher matcher = Pattern.compile("roundabout_exit_([1-9])").matcher(clean(maneuver));
        return matcher.find() ? roundaboutExitLabel(matcher.group(1)) : "";
    }

    private static String roundaboutManeuver(String maneuver, String roundaboutExit,
                                             Intent intent, String sourceLower) {
        int exit = roundaboutExitIndex(roundaboutExit);
        if (exit >= 1 && exit <= 4) return "context_ra_roundabout_exit_" + exit;
        if (roundaboutExitContext(intent, maneuver, sourceLower)) {
            return "context_ra_out_circular_movement";
        }
        if (isRoundaboutManeuver(maneuver)) return maneuver;
        return "context_ra_in_circular_movement";
    }

    private static boolean roundaboutExitContext(Intent intent, String maneuver, String sourceLower) {
        String seed = clean(maneuver + " " + sourceLower).toLowerCase(Locale.US)
                .replace('-', '_');
        if (containsAny(seed, "out_circular", "leave_roundabout", "exit_roundabout",
                "roundabout_exit", "circular_exit", "съезд", "выезд", "покин")) {
            return true;
        }
        Bundle extras = intent == null ? null : intent.getExtras();
        if (extras == null) return false;
        for (String key : extras.keySet()) {
            String lowerKey = clean(key).toLowerCase(Locale.US).replace('-', '_');
            Object value = extras.get(key);
            String lowerText = value == null ? "" : clean(String.valueOf(value))
                    .toLowerCase(Locale.US).replace('-', '_');
            String joined = lowerKey + " " + lowerText;
            if (containsAny(joined, "out_circular", "leave_roundabout",
                    "exit_roundabout", "roundabout_exit", "circular_exit",
                    "съезд", "выезд", "покин")) {
                return true;
            }
        }
        return false;
    }

    private static boolean isRoundaboutExitManeuver(String maneuver) {
        String value = clean(maneuver);
        return value.startsWith("context_ra_roundabout_exit_")
                || "context_ra_out_circular_movement".equals(value);
    }

    private void rememberRoundaboutExit(String maneuver, String exitText, long now) {
        lastRoundaboutExitManeuver = clean(maneuver);
        lastRoundaboutExitText = clean(exitText);
        lastRoundaboutExitUntil = now + ROUNDABOUT_EXIT_HOLD_MS;
    }

    private String heldRoundaboutExitManeuver(long now) {
        if (now > lastRoundaboutExitUntil) {
            clearRoundaboutExitHold();
            return "";
        }
        return lastRoundaboutExitManeuver;
    }

    private void clearRoundaboutExitHold() {
        lastRoundaboutExitManeuver = "";
        lastRoundaboutExitText = "";
        lastRoundaboutExitUntil = 0L;
    }

    private static String grayRoadTopologyText(Intent intent) {
        return grayRoadTopologyText(intent, "");
    }

    private static String grayRoadTopologyText(Intent intent, String sourceLower) {
        if (providerVisualLaneSource(sourceLower)) {
            return providerTrustedGrayRoadTopologyText(intent);
        }
        String roadTopology = explicitGrayRoadTopologyText(intent);
        if (!TextUtils.isEmpty(roadTopology)) return roadTopology;
        String weakerRoadTopology = first(
                text(intent, "routeRoadOptions"),
                text(intent, "roadOptions"),
                text(intent, "roadDirections"),
                text(intent, "availableDirections"),
                text(intent, "turnOptions"),
                text(intent, "routeOptions"));
        if (!TextUtils.isEmpty(weakerRoadTopology)) return weakerRoadTopology;
        if (signOnlyLaneSource(sourceLower)) return "";
        String topology = first(text(intent, "lane_topology"),
                text(intent, "raw_lane_topology"));
        if (!TextUtils.isEmpty(topology)) return topology;
        return canUseAllowedDirectionsForGrayRoad(sourceLower)
                ? first(text(intent, "allowed_directions"), text(intent, "allowedDirections"))
                : "";
    }

    private static String providerTrustedGrayRoadTopologyText(Intent intent) {
        String rawLaneTopology = providerRawLaneTopologyText(intent);
        if (!TextUtils.isEmpty(rawLaneTopology)) return rawLaneTopology;
        String rawRoadScheme = first(text(intent, "road_scheme_raw"),
                text(intent, "raw_road_scheme"),
                text(intent, "road_scheme_items"),
                text(intent, "direction_sign_items"),
                text(intent, "raw_direction_sign_items"),
                text(intent, "route_road_raw"),
                text(intent, "gray_road_raw"));
        if (!TextUtils.isEmpty(rawRoadScheme)) return rawRoadScheme;
        String source = clean(first(text(intent, "route_road_source"),
                text(intent, "gray_road_source"),
                text(intent, "road_scheme_source"))).toLowerCase(Locale.US);
        if (containsAny(source, "road_scheme", "direction_sign", "route_road")) {
            return explicitGrayRoadTopologyText(intent);
        }
        return explicitGrayRoadTopologyText(intent);
    }

    private static String providerRawLaneTopologyText(Intent intent) {
        return first(text(intent, "raw_lane_items"),
                text(intent, "ignored_raw_lane_items"),
                text(intent, "lane_topology_json"),
                text(intent, "direction_sign_items"),
                text(intent, "raw_direction_sign_items"),
                text(intent, "road_scheme_raw"),
                text(intent, "raw_road_scheme"),
                text(intent, "road_scheme_items"),
                text(intent, "raw_lane_direction"),
                text(intent, "direction_sign"),
                text(intent, "road_sign"));
    }

    private static String explicitGrayRoadTopologyText(Intent intent) {
        return first(text(intent, "route_road_options"),
                text(intent, "routeRoadOptions"),
                text(intent, "gray_road_options"),
                text(intent, "grayRoadOptions"),
                text(intent, "road_options"),
                text(intent, "roadOptions"),
                text(intent, "road_directions"),
                text(intent, "roadDirections"),
                text(intent, "turn_options"),
                text(intent, "turnOptions"),
                text(intent, "route_options"),
                text(intent, "routeOptions"));
    }

    private static String laneRawItemsText(Intent intent) {
        return first(text(intent, "raw_lane_items"),
                text(intent, "lane_items"),
                text(intent, "direction_sign_items"),
                text(intent, "raw_direction_sign_items"),
                text(intent, "raw_lane_direction"),
                text(intent, "direction_sign"),
                text(intent, "road_sign"));
    }

    private static boolean boolAny(Intent intent, String... keys) {
        for (String key : keys) {
            if (bool(intent, key, false)) return true;
        }
        return false;
    }

    private static boolean hasAnyExtra(Intent intent, String... keys) {
        if (intent == null) return false;
        Bundle extras = intent.getExtras();
        if (extras == null) return false;
        for (String key : keys) {
            if (extras.containsKey(key)) return true;
        }
        return false;
    }

    private static boolean hasExplicitGrayDirectionFlags(Intent intent) {
        return hasAnyExtra(intent,
                "lane_has_straight", "road_has_straight", "has_straight",
                "can_go_straight", "allow_straight", "available_straight",
                "lane_has_right", "road_has_right", "has_right",
                "can_go_right", "allow_right", "available_right",
                "lane_has_left", "road_has_left", "has_left",
                "can_go_left", "allow_left", "available_left");
    }

    private static boolean signOnlyLaneSource(String sourceLower) {
        String value = clean(sourceLower).toLowerCase(Locale.US);
        return value.contains("rendered_lane")
                || value.contains("lane_distance")
                || value.contains("lane_guidance")
                || value.contains("guidance_tick")
                || value.contains("projected_lanes")
                || value.contains("panel_lanes");
    }

    private static boolean weakVisualLaneSingleSource(String sourceLower) {
        String value = clean(sourceLower).toLowerCase(Locale.US);
        return value.contains("rendered_lane")
                || value.contains("projected_lanes")
                || value.contains("panel_lanes");
    }

    private static boolean visualGrayRoadCanClearActiveMicro(String sourceLower) {
        String value = clean(sourceLower).toLowerCase(Locale.US);
        if (weakVisualLaneSingleSource(value)) return false;
        return value.contains("lane_distance")
                || value.contains("guidance_tick")
                || value.contains("lane_guidance")
                || value.contains("direction_sign")
                || value.contains("auto_widget_signs");
    }

    private static boolean completeLaneGraySource(String sourceLower) {
        String value = clean(sourceLower).toLowerCase(Locale.US);
        return value.contains("lane_guidance")
                && !value.contains("guidance_tick")
                && !value.contains("lane_distance")
                && !value.contains("rendered_lane");
    }

    private static boolean narrowLaneGraySource(String sourceLower) {
        String value = clean(sourceLower).toLowerCase(Locale.US);
        return value.contains("guidance_tick")
                || value.contains("lane_distance")
                || value.contains("rendered_lane");
    }

    private static boolean canUseAllowedDirectionsForGrayRoad(String sourceLower) {
        String value = clean(sourceLower).toLowerCase(Locale.US);
        if (signOnlyLaneSource(value)) return false;
        return value.contains("lane_guidance")
                || value.contains("route_road")
                || value.contains("route_options")
                || value.contains("road_options")
                || value.contains("road_directions")
                || value.contains("available_directions");
    }

    private static boolean canUseLaneRawItemsForGrayRoad(String sourceLower) {
        if (providerVisualLaneSource(sourceLower)) return false;
        return !signOnlyLaneSource(sourceLower);
    }

    private static boolean laneTextHas(Intent intent, String token) {
        return laneTextHas(intent, token, "");
    }

    private static boolean laneTextHas(Intent intent, String token, String sourceLower) {
        String topology = grayRoadTopologyText(intent, sourceLower);
        if (!TextUtils.isEmpty(topology)) {
            if (containsLaneToken(topology, token)) return true;
            return canUseLaneRawItemsForGrayRoad(sourceLower)
                    && containsLaneToken(laneRawItemsText(intent), token);
        }
        if (hasExplicitGrayDirectionFlags(intent)) return false;
        if (!canUseLaneRawItemsForGrayRoad(sourceLower)) return false;
        return containsLaneToken(laneRawItemsText(intent), token);
    }

    private static boolean laneHasAny(Intent intent, String... tokens) {
        return laneHasAnyForGrayRoad(intent, "", tokens);
    }

    private static boolean laneHasAnyForGrayRoad(Intent intent, String sourceLower, String... tokens) {
        for (String token : tokens) {
            if (laneTextHas(intent, token, sourceLower)) return true;
        }
        return false;
    }

    private static boolean hasLaneData(Intent intent) {
        return !TextUtils.isEmpty(grayRoadTopologyText(intent))
                || !TextUtils.isEmpty(laneRawItemsText(intent))
                || !TextUtils.isEmpty(providerLaneHighlightText(intent))
                || hasLaneTopologyData(intent)
                || hasVisualLaneItems(intent)
                || hasExplicitGrayDirectionFlags(intent);
    }

    private static boolean hasLaneTopologyData(Intent intent) {
        return !TextUtils.isEmpty(first(text(intent, "lane_topology"),
                text(intent, "lane_topology_json"),
                text(intent, "raw_lane_topology"),
                text(intent, "lane_sign_topology"),
                text(intent, "lane_sign_topology_json")));
    }

    private static boolean grayRoadHasStraight(Intent intent) {
        return grayRoadHasStraight(intent, "");
    }

    private static boolean grayRoadHasStraight(Intent intent, String sourceLower) {
        return boolAny(intent, "lane_has_straight", "road_has_straight",
                "has_straight", "can_go_straight", "allow_straight", "available_straight")
                || laneTextHas(intent, "straight", sourceLower);
    }

    private static boolean grayRoadHasRight(Intent intent) {
        return grayRoadHasRight(intent, "");
    }

    private static boolean grayRoadHasRight(Intent intent, String sourceLower) {
        return boolAny(intent, "lane_has_right", "road_has_right",
                "has_right", "can_go_right", "allow_right", "available_right")
                || laneTextHas(intent, "right", sourceLower);
    }

    private static boolean grayRoadHasLeft(Intent intent) {
        return grayRoadHasLeft(intent, "");
    }

    private static boolean grayRoadHasLeft(Intent intent, String sourceLower) {
        return boolAny(intent, "lane_has_left", "road_has_left",
                "has_left", "can_go_left", "allow_left", "available_left")
                || laneTextHas(intent, "left", sourceLower);
    }

    private static boolean grayRoadHasUturnLeft(Intent intent, String sourceLower) {
        if (hasAnyExtra(intent, "lane_has_uturn_left", "road_has_uturn_left",
                "has_uturn_left", "can_uturn_left", "allow_uturn_left",
                "available_uturn_left")) {
            return boolAny(intent, "lane_has_uturn_left", "road_has_uturn_left",
                    "has_uturn_left", "can_uturn_left", "allow_uturn_left",
                    "available_uturn_left");
        }
        return boolAny(intent, "lane_has_uturn_left", "road_has_uturn_left",
                "has_uturn_left", "can_uturn_left", "allow_uturn_left",
                "available_uturn_left")
                || laneHasAnyForGrayRoad(intent, sourceLower,
                "left180", "uturn_left", "turn_back_left", "u_turn_left");
    }

    private static boolean grayRoadHasUturnRight(Intent intent, String sourceLower) {
        if (hasAnyExtra(intent, "lane_has_uturn_right", "road_has_uturn_right",
                "has_uturn_right", "can_uturn_right", "allow_uturn_right",
                "available_uturn_right")) {
            return boolAny(intent, "lane_has_uturn_right", "road_has_uturn_right",
                    "has_uturn_right", "can_uturn_right", "allow_uturn_right",
                    "available_uturn_right");
        }
        return boolAny(intent, "lane_has_uturn_right", "road_has_uturn_right",
                "has_uturn_right", "can_uturn_right", "allow_uturn_right",
                "available_uturn_right")
                || laneHasAnyForGrayRoad(intent, sourceLower,
                "right180", "uturn_right", "turn_back_right", "u_turn_right");
    }

    private static boolean grayRoadAssumeLeftAtJunction(Intent intent, boolean straight, boolean right,
                                                        boolean exitRight, boolean exitLeft) {
        return grayRoadAssumeLeftAtJunction(intent, straight, right, exitRight, exitLeft, "");
    }

    private static boolean grayRoadAssumeLeftAtJunction(Intent intent, boolean straight, boolean right,
                                                        boolean exitRight, boolean exitLeft,
                                                        String sourceLower) {
        return false;
    }

    private static boolean laneHighlightHas(Intent intent, String token) {
        String text = clean(first(providerLaneHighlightText(intent),
                laneRawItemsText(intent), grayRoadTopologyText(intent))).toLowerCase(Locale.US)
                .replace('-', '_');
        String needle = clean(token).toLowerCase(Locale.US);
        if (TextUtils.isEmpty(text) || TextUtils.isEmpty(needle)) return false;
        if (!TextUtils.isEmpty(providerLaneHighlightText(intent))) {
            return containsLaneToken(text, needle);
        }
        Matcher matcher = Pattern.compile("highlight=([^\\s|,;]+)").matcher(text);
        while (matcher.find()) {
            if (containsLaneToken(matcher.group(1), needle)) return true;
        }
        return false;
    }

    private static boolean containsLaneToken(String value, String token) {
        String text = clean(value).toLowerCase(Locale.US);
        if (TextUtils.isEmpty(text) || TextUtils.isEmpty(token)) return false;
        String[] parts = text.split("[,;\\s=:@#|()\\[\\]{}]+");
        for (String part : parts) {
            if (token.equals(part)) return true;
        }
        if ("straight".equals(token)) {
            return text.contains("straight") || text.contains("forward")
                    || text.contains("ahead") || text.contains("прям");
        }
        if ("left".equals(token)) {
            return text.contains("left90") || text.contains("turn_left")
                    || text.contains("налево") || text.contains("лево")
                    || text.contains("левее");
        }
        if ("right".equals(token)) {
            return text.contains("right90") || text.contains("turn_right")
                    || text.contains("направо") || text.contains("право")
                    || text.contains("правее");
        }
        if ("left180".equals(token) || "uturn_left".equals(token)
                || "turn_back_left".equals(token) || "u_turn_left".equals(token)) {
            return containsAny(text, "left180", "turn_back_left", "uturn_left",
                    "u_turn_left");
        }
        if ("right180".equals(token) || "uturn_right".equals(token)
                || "turn_back_right".equals(token) || "u_turn_right".equals(token)) {
            return containsAny(text, "right180", "turn_back_right", "uturn_right",
                    "u_turn_right");
        }
        if ("uturn".equals(token) || "turn_back".equals(token) || "u_turn".equals(token)) {
            return containsAny(text, "turn_back", "u_turn", "uturn", "развор");
        }
        if ("hard_left".equals(token) || "sharp_left".equals(token)) {
            return (text.contains("hard") || text.contains("sharp") || text.contains("резко")
                    || text.contains("круто"))
                    && (text.contains("left") || text.contains("лев"));
        }
        if ("hard_right".equals(token) || "sharp_right".equals(token)) {
            return (text.contains("hard") || text.contains("sharp") || text.contains("резко")
                    || text.contains("круто"))
                    && (text.contains("right") || text.contains("прав"));
        }
        if ("exit_left".equals(token) || "ramp_left".equals(token)
                || "take_left".equals(token) || "slip_left".equals(token)
                || "fork_left".equals(token)) {
            return (text.contains("exit") || text.contains("ramp")
                    || text.contains("take") || text.contains("slip")
                    || text.contains("fork") || text.contains("съезд")
                    || text.contains("ответв"))
                    && (text.contains("left") || text.contains("лев"));
        }
        if ("exit_right".equals(token) || "ramp_right".equals(token)
                || "take_right".equals(token) || "slip_right".equals(token)
                || "fork_right".equals(token)) {
            return (text.contains("exit") || text.contains("ramp")
                    || text.contains("take") || text.contains("slip")
                    || text.contains("fork") || text.contains("съезд")
                    || text.contains("ответв"))
                    && (text.contains("right") || text.contains("прав"));
        }
        return false;
    }

    private static int roundaboutExitIndex(String value) {
        String text = clean(value);
        if (TextUtils.isEmpty(text)) return 0;
        Matcher matcher = NUMBER.matcher(text);
        if (!matcher.find()) return 0;
        return Math.round(parseFloat(matcher.group()));
    }

    private static String roundaboutExitText(String value) {
        String text = clean(value);
        if (TextUtils.isEmpty(text)) return "";
        String number = roundaboutExitNumberFromText(text);
        if (!TextUtils.isEmpty(number)) return roundaboutExitLabel(number);
        String lower = text.toLowerCase(Locale.US);
        if (lower.contains("перв") || lower.contains("first") || lower.contains("1st")) return "1-й съезд";
        if (lower.contains("втор") || lower.contains("second") || lower.contains("2nd")) return "2-й съезд";
        if (lower.contains("трет") || lower.contains("third") || lower.contains("3rd")) return "3-й съезд";
        if (lower.contains("четв") || lower.contains("fourth") || lower.contains("4th")) return "4-й съезд";
        return "";
    }

    private static String roundaboutExitNumberFromText(String value) {
        String text = clean(value).toLowerCase(Locale.US);
        if (TextUtils.isEmpty(text)) return "";
        Matcher plain = Pattern.compile("^\\s*([1-9])\\s*[-\\u2010-\\u2015]?\\s*(?:й|st|nd|rd|th)?\\s*$",
                        Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE)
                .matcher(text);
        if (plain.find()) return plain.group(1);
        Matcher before = Pattern.compile("(?iu)(^|[^0-9])([1-9])\\s*[-\\u2010-\\u2015]?\\s*"
                        + "(?:й|st|nd|rd|th)?\\s*(?:съезд|exit)")
                .matcher(text);
        if (before.find()) return before.group(2);
        Matcher after = Pattern.compile("(?iu)(?:съезд|exit)[^0-9]{0,24}([1-9])")
                .matcher(text);
        if (after.find()) return after.group(1);
        return "";
    }

    private static String roundaboutExitLabel(String number) {
        String clean = clean(number);
        if ("1".equals(clean)) return "1-й съезд";
        if ("2".equals(clean)) return "2-й съезд";
        if ("3".equals(clean)) return "3-й съезд";
        if ("4".equals(clean)) return "4-й съезд";
        return clean + "-й съезд";
    }

    private static String roundaboutExitLabelFromRouteSection(String value) {
        String exitId = roundaboutExitFromRouteSection(value);
        int exit = roundaboutExitIndex(exitId);
        return exit <= 0 ? "" : roundaboutExitLabel(String.valueOf(exit));
    }

    private static String roundaboutExitFromExtras(Intent intent) {
        Bundle extras = intent == null ? null : intent.getExtras();
        if (extras == null) return "";
        for (String key : extras.keySet()) {
            String lowerKey = clean(key).toLowerCase(Locale.US);
            boolean likelyKey = lowerKey.contains("exit") || lowerKey.contains("roundabout")
                    || lowerKey.contains("circular") || lowerKey.contains("direction_sign")
                    || lowerKey.contains("roadsign") || lowerKey.contains("road_sign")
                    || lowerKey.contains("route_section");
            Object value = extras.get(key);
            String text = value == null ? "" : clean(String.valueOf(value));
            String lowerText = text.toLowerCase(Locale.US);
            boolean likelyText = lowerText.contains("съезд") || lowerText.contains("exit");
            if (!likelyKey && !likelyText) continue;
            String parsed = "";
            if (lowerKey.contains("route_section") || lowerText.contains("description=")) {
                parsed = roundaboutExitLabelFromRouteSection(text);
                if (!TextUtils.isEmpty(parsed)) return parsed;
            }
            parsed = roundaboutExitText(text);
            if (!TextUtils.isEmpty(parsed)) return parsed;
        }
        return "";
    }

    private static boolean hasRoundaboutContext(Intent intent) {
        Bundle extras = intent == null ? null : intent.getExtras();
        if (extras == null) return false;
        for (String key : extras.keySet()) {
            String lowerKey = clean(key).toLowerCase(Locale.US);
            Object value = extras.get(key);
            String lowerText = value == null ? "" : clean(String.valueOf(value)).toLowerCase(Locale.US);
            if (lowerKey.contains("roundabout") || lowerKey.contains("circular")
                    || lowerText.contains("roundabout") || lowerText.contains("circular")
                    || lowerText.contains("круг") || lowerText.contains("кольц")
                    || (!TextUtils.isEmpty(roundaboutExitNumberFromText(lowerText))
                    && (lowerKey.contains("route") || lowerKey.contains("sign")
                    || lowerKey.contains("exit") || lowerKey.contains("description")
                    || lowerKey.contains("maneuver")))) {
                return true;
            }
        }
        return false;
    }

    private static String laneDistance(String laneDistance, String routeDistance) {
        String route = nonZeroDistance(routeDistance);
        String lane = nonZeroDistance(laneDistance);
        return first(lane, route);
    }

    private static String withRouteTotalLine(String clusterTx, String routeTotalDistance) {
        String line = "route total=" + clean(routeTotalDistance);
        if ("route total=".equals(line)) return clean(clusterTx);
        String current = clean(clusterTx);
        StringBuilder kept = new StringBuilder();
        if (!TextUtils.isEmpty(current)) {
            String[] lines = current.split("\\n");
            for (String part : lines) {
                String cleanPart = clean(part);
                if (TextUtils.isEmpty(cleanPart) || cleanPart.startsWith("route total=")) {
                    continue;
                }
                if (kept.length() > 0) kept.append('\n');
                kept.append(cleanPart);
            }
        }
        return kept.length() == 0 ? line : kept + "\n" + line;
    }

    private static String latestRouteTotalDistance(String clusterTx) {
        String current = clean(clusterTx);
        if (TextUtils.isEmpty(current)) return "";
        String latest = "";
        String[] lines = current.split("\\n");
        for (String part : lines) {
            String cleanPart = clean(part);
            if (!cleanPart.startsWith("route total=")) continue;
            latest = normalizeDistanceText(cleanRouteTotalDistance(
                    cleanPart.substring("route total=".length())));
        }
        return latest;
    }

    private static String cleanRouteTotalDistance(String value) {
        String text = clean(value);
        if (TextUtils.isEmpty(text)) return "";
        Matcher matcher = Pattern.compile("[-+]?\\d+(?:[\\.,]\\d+)?\\s*(?:км|km|м|m)",
                Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE).matcher(text);
        return matcher.find() ? clean(matcher.group()) : "";
    }

    private static String laneHintText(Intent intent, String maneuver, String distance) {
        return laneHintText(intent, maneuver, distance, "");
    }

    private static String laneHintText(Intent intent, String maneuver, String distance, String sourceLower) {
        String directions = grayRoadDirectionsText(intent, sourceLower);
        String label = maneuverLabel(maneuver);
        String shown = first(directions, label);
        String out = TextUtils.isEmpty(shown) ? "серая дорога" : shown;
        String dist = nonZeroDistance(distance);
        if (!TextUtils.isEmpty(dist)) out += ", " + dist;
        return out;
    }

    private static String grayRoadDirectionsText(Intent intent) {
        return grayRoadDirectionsText(intent, "");
    }

    private static String grayRoadDirectionsText(Intent intent, String sourceLower) {
        if (providerVisualLaneSource(sourceLower)) {
            String visual = visualLaneDirectionsText(intent);
            if (!TextUtils.isEmpty(visual)) return visual;
        }
        boolean straight = grayRoadHasStraight(intent, sourceLower);
        boolean uturnLeft = grayRoadHasUturnLeft(intent, sourceLower);
        boolean uturnRight = grayRoadHasUturnRight(intent, sourceLower);
        String routeAction = routeActionManeuver(intent);
        boolean hardRight = laneHasAnyForGrayRoad(intent, sourceLower, "hard_right", "sharp_right")
                || "context_ra_hard_turn_right".equals(routeAction);
        boolean hardLeft = laneHasAnyForGrayRoad(intent, sourceLower, "hard_left", "sharp_left")
                || "context_ra_hard_turn_left".equals(routeAction);
        boolean exitRight = laneHasAnyForGrayRoad(intent, sourceLower,
                "exit_right", "ramp_right", "take_right", "slip_right", "fork_right")
                || routeActionRightBranch(intent);
        boolean exitLeft = laneHasAnyForGrayRoad(intent, sourceLower,
                "exit_left", "ramp_left", "take_left", "slip_left", "fork_left")
                || routeActionLeftBranch(intent);
        boolean right = grayRoadHasRight(intent, sourceLower) || exitRight || hardRight;
        boolean left = grayRoadHasLeft(intent, sourceLower) || exitLeft || hardLeft;
        if (!left && grayRoadAssumeLeftAtJunction(intent, straight, right, exitRight, exitLeft,
                sourceLower)) {
            left = true;
        }
        if (exitRight && laneHighlightHas(intent, "straight") && !laneHighlightHas(intent, "left")) {
            left = false;
        }
        if (exitLeft && laneHighlightHas(intent, "straight") && !laneHighlightHas(intent, "right")) {
            right = false;
        }
        StringBuilder out = new StringBuilder();
        appendPart(out, "разворот налево", uturnLeft);
        appendPart(out, "разворот направо", uturnRight);
        appendPart(out, "прямо", straight);
        appendPart(out, hardLeft ? "резко налево" : (exitLeft ? "съезд налево" : "налево"), left);
        appendPart(out, hardRight ? "резко направо" : (exitRight ? "съезд направо" : "направо"), right);
        return out.toString();
    }

    private static String laneDebugText(String grayRoad, String laneHint) {
        String road = grayRoadLabel(grayRoad);
        if (TextUtils.isEmpty(road)) return laneHint;
        return road;
    }

    private static String grayRoadLabel(String grayRoad) {
        String value = clean(grayRoad);
        if ("context_ra_gray_straight_left_right".equals(value)) return "прямо + налево + направо";
        if ("context_ra_gray_left_right".equals(value)) return "налево + направо";
        if ("context_ra_gray_straight_left_exit_right".equals(value)) return "прямо + налево + съезд направо";
        if ("context_ra_gray_straight_exit_left_right".equals(value)) return "прямо + съезд налево + направо";
        if ("context_ra_gray_left_exit_right".equals(value)) return "налево + съезд направо";
        if ("context_ra_gray_exit_left_right".equals(value)) return "съезд налево + направо";
        if ("context_ra_gray_exit_right".equals(value)) return "прямо + съезд направо";
        if ("context_ra_gray_only_exit_right".equals(value)) return "съезд направо";
        if ("context_ra_gray_straight_exit_left".equals(value)) return "прямо + съезд налево";
        if ("context_ra_gray_exit_left".equals(value)) return "съезд налево";
        if ("context_ra_gray_hard_right".equals(value)) return "резко направо";
        if ("context_ra_gray_hard_left".equals(value)) return "резко налево";
        if ("context_ra_gray_straight_right".equals(value)) return "прямо + направо";
        if ("context_ra_gray_straight_left".equals(value)) return "прямо + налево";
        if ("context_ra_gray_right".equals(value)) return "направо";
        if ("context_ra_gray_left".equals(value)) return "налево";
        if ("context_ra_gray_straight".equals(value)) return "прямо";
        return "";
    }

    private static String grayRoadSchemeText(String grayRoad, Intent intent, String sourceLower) {
        String base = grayRoadLabel(grayRoad);
        if (intent == null) return base;
        if (providerVisualLaneSource(sourceLower) && hasVisualLaneItems(intent)) return base;
        boolean uturnLeft = grayRoadHasUturnLeft(intent, sourceLower);
        boolean uturnRight = grayRoadHasUturnRight(intent, sourceLower);
        if (!uturnLeft && !uturnRight) return base;
        StringBuilder out = new StringBuilder();
        appendPart(out, "разворот налево", uturnLeft);
        appendPart(out, "разворот направо", uturnRight);
        if (!TextUtils.isEmpty(base)) {
            if (out.length() > 0) out.append(" + ");
            out.append(base);
        }
        return out.toString();
    }

    private static String grayRoadManeuver(Intent intent) {
        return grayRoadManeuver(intent, "");
    }

    private static String grayRoadManeuver(Intent intent, String sourceLower) {
        if (providerVisualLaneSource(sourceLower)) {
            return explicitProviderGrayRoadManeuver(intent);
        }
        boolean straight = grayRoadHasStraight(intent, sourceLower);
        String routeAction = routeActionManeuver(intent);
        boolean hardRight = laneHasAnyForGrayRoad(intent, sourceLower, "hard_right", "sharp_right")
                || "context_ra_hard_turn_right".equals(routeAction);
        boolean hardLeft = laneHasAnyForGrayRoad(intent, sourceLower, "hard_left", "sharp_left")
                || "context_ra_hard_turn_left".equals(routeAction);
        boolean exitRight = laneHasAnyForGrayRoad(intent, sourceLower,
                "exit_right", "ramp_right", "take_right", "slip_right", "fork_right")
                || routeActionRightBranch(intent);
        boolean exitLeft = laneHasAnyForGrayRoad(intent, sourceLower,
                "exit_left", "ramp_left", "take_left", "slip_left", "fork_left")
                || routeActionLeftBranch(intent);
        boolean right = grayRoadHasRight(intent, sourceLower) || exitRight || hardRight;
        boolean left = grayRoadHasLeft(intent, sourceLower) || exitLeft || hardLeft;
        if (!left && grayRoadAssumeLeftAtJunction(intent, straight, right, exitRight, exitLeft,
                sourceLower)) {
            left = true;
        }
        if (exitRight && laneHighlightHas(intent, "straight") && !laneHighlightHas(intent, "left")) {
            left = false;
        }
        if (exitLeft && laneHighlightHas(intent, "straight") && !laneHighlightHas(intent, "right")) {
            right = false;
        }
        // The cluster has one gray road geometry, not lane-level arrows. U-turns and roundabouts
        // are separate yellow maneuver families and must not inflate the gray road.
        if (hardRight && !straight && !left) return "context_ra_gray_hard_right";
        if (hardLeft && !straight && !right) return "context_ra_gray_hard_left";
        if (exitRight && straight && left) return "context_ra_gray_straight_left_exit_right";
        if (exitLeft && straight && right) return "context_ra_gray_straight_exit_left_right";
        if (exitRight && left) return "context_ra_gray_left_exit_right";
        if (exitLeft && right) return "context_ra_gray_exit_left_right";
        if (exitRight && straight) return "context_ra_gray_exit_right";
        if (exitLeft && straight) return "context_ra_gray_straight_exit_left";
        if (exitRight) return "context_ra_gray_only_exit_right";
        if (exitLeft) return "context_ra_gray_exit_left";
        if (straight && left && right) return "context_ra_gray_straight_left_right";
        if (left && right) return "context_ra_gray_left_right";
        if (straight && right) return "context_ra_gray_straight_right";
        if (straight && left) return "context_ra_gray_straight_left";
        if (right) return "context_ra_gray_right";
        if (left) return "context_ra_gray_left";
        if (straight) return "context_ra_gray_straight";
        return "";
    }

    private static String explicitProviderGrayRoadManeuver(Intent intent) {
        String topology = providerTrustedGrayRoadTopologyText(intent);
        if (TextUtils.isEmpty(topology)) return "";
        boolean straight = containsLaneToken(topology, "straight");
        boolean hardRight = containsLaneToken(topology, "hard_right")
                || containsLaneToken(topology, "sharp_right");
        boolean hardLeft = containsLaneToken(topology, "hard_left")
                || containsLaneToken(topology, "sharp_left");
        boolean exitRight = containsLaneToken(topology, "exit_right")
                || containsLaneToken(topology, "ramp_right")
                || containsLaneToken(topology, "take_right")
                || containsLaneToken(topology, "slip_right")
                || containsLaneToken(topology, "fork_right");
        boolean exitLeft = containsLaneToken(topology, "exit_left")
                || containsLaneToken(topology, "ramp_left")
                || containsLaneToken(topology, "take_left")
                || containsLaneToken(topology, "slip_left")
                || containsLaneToken(topology, "fork_left");
        boolean right = containsLaneToken(topology, "right") || exitRight || hardRight;
        boolean left = containsLaneToken(topology, "left") || exitLeft || hardLeft;
        if (hardRight && !straight && !left) return "context_ra_gray_hard_right";
        if (hardLeft && !straight && !right) return "context_ra_gray_hard_left";
        if (exitRight && straight && left) return "context_ra_gray_straight_left_exit_right";
        if (exitLeft && straight && right) return "context_ra_gray_straight_exit_left_right";
        if (exitRight && left) return "context_ra_gray_left_exit_right";
        if (exitLeft && right) return "context_ra_gray_exit_left_right";
        if (exitRight && straight) return "context_ra_gray_exit_right";
        if (exitLeft && straight) return "context_ra_gray_straight_exit_left";
        if (exitRight) return "context_ra_gray_only_exit_right";
        if (exitLeft) return "context_ra_gray_exit_left";
        return grayRoadFromBasicDirections(straight, left, right);
    }

    private static String grayRoadForManeuver(String maneuver) {
        String family = maneuverFamily(maneuver);
        if ("hard_right".equals(family)) return "context_ra_gray_hard_right";
        if ("hard_left".equals(family)) return "context_ra_gray_hard_left";
        if ("exit_right".equals(family)) return "context_ra_gray_only_exit_right";
        if ("exit_left".equals(family)) return "context_ra_gray_exit_left";
        if ("right".equals(family)) return "context_ra_gray_right";
        if ("left".equals(family)) return "context_ra_gray_left";
        if ("forward".equals(family)) return "context_ra_gray_straight";
        return "";
    }

    private static String adjustRouteRoadGrayForCurrentManeuver(Intent intent, String grayRoad,
                                                                String currentManeuver) {
        String currentFamily = maneuverFamily(currentManeuver);
        String routeFamily = maneuverFamily(routeActionManeuver(intent));
        if (TextUtils.isEmpty(grayRoad) || TextUtils.isEmpty(currentFamily)
                || TextUtils.isEmpty(routeFamily) || currentFamily.equals(routeFamily)) {
            return grayRoad;
        }
        boolean straight = grayRoadHasStraight(intent);
        boolean right = grayRoadHasRight(intent) || routeActionRightBranch(intent);
        boolean left = grayRoadHasLeft(intent) || routeActionLeftBranch(intent);
        boolean exitRight = routeActionRightBranch(intent);
        boolean exitLeft = routeActionLeftBranch(intent);
        if ("right".equals(currentFamily) && "left".equals(routeFamily)
                && left && !right && !straight) {
            return "context_ra_gray_right";
        }
        if ("left".equals(currentFamily) && "right".equals(routeFamily)
                && right && !left && !straight) {
            return "context_ra_gray_left";
        }
        if ("left".equals(currentFamily) && "right".equals(routeFamily) && straight && right && !left) {
            return exitRight ? "context_ra_gray_left_exit_right" : "context_ra_gray_left_right";
        }
        if ("right".equals(currentFamily) && "left".equals(routeFamily) && straight && left && !right) {
            return exitLeft ? "context_ra_gray_exit_left_right" : "context_ra_gray_left_right";
        }
        return grayRoad;
    }

    private static String grayRoadHintText(String grayRoad, Intent intent, String sourceLower,
                                           String distance) {
        String label = grayRoadSchemeText(grayRoad, intent, sourceLower);
        if (TextUtils.isEmpty(label)) return "";
        String dist = nonZeroDistance(distance);
        return TextUtils.isEmpty(dist) ? label : label + ", " + dist;
    }

    private static boolean isTrustedCurrentStreetSpeedSource(String source) {
        String value = clean(source).toLowerCase(Locale.US);
        return value.contains("status_panel")
                || value.contains("eta_view")
                || value.contains("bottom_panel")
                || value.contains("route_status");
    }

    private void rememberLearnedRoadOptions(String key, int mask, long now, String source) {
        String cleanKey = clean(key);
        if (TextUtils.isEmpty(cleanKey) || mask == 0) return;
        int previous = learnedRoadMask(cleanKey, now);
        int next = mask;
        learnedRoadKey = cleanKey;
        learnedRoadMask = next;
        learnedRoadUntil = now + ROAD_OPTION_LEARN_MS;
        if (next != previous) {
            AppLog.line(app, "Navigation gray learned road: "
                    + grayRoadLabel(grayRoadFromMask(next))
                    + " key=" + cleanKey
                    + " source=" + clean(source));
        }
    }

    private int learnedRoadMask(String key, long now) {
        String cleanKey = clean(key);
        if (TextUtils.isEmpty(cleanKey) || TextUtils.isEmpty(learnedRoadKey)
                || now > learnedRoadUntil || !cleanKey.equals(learnedRoadKey)) {
            return 0;
        }
        return learnedRoadMask;
    }

    private static String roadOptionKey(String currentStreet, String nextStreet) {
        String current = simplifyStreet(currentStreet);
        String next = simplifyStreet(nextStreet);
        if (TextUtils.isEmpty(current) && TextUtils.isEmpty(next)) return "";
        return current + "|" + next;
    }

    private static int maneuverRoadMask(String maneuver) {
        String value = clean(maneuver).toLowerCase(Locale.US);
        if (TextUtils.isEmpty(value)) return 0;
        int mask = 0;
        if (value.contains("forward") || value.contains("straight")) {
            mask |= 1;
        }
        if (value.contains("turn_left") || value.contains("take_left")
                || value.contains("exit_left") || value.contains("left")) {
            mask |= 2;
        }
        if (value.contains("turn_right") || value.contains("take_right")
                || value.contains("exit_right") || value.contains("right")) {
            mask |= 4;
        }
        return mask;
    }

    private static int grayRoadMask(String grayRoad) {
        String value = clean(grayRoad);
        if (TextUtils.isEmpty(value)) return 0;
        if ("context_ra_gray_straight_left_right".equals(value)) return 7;
        if ("context_ra_gray_left_right".equals(value)) return 6;
        if ("context_ra_gray_straight_right".equals(value)
                || "context_ra_gray_exit_right".equals(value)) return 5;
        if ("context_ra_gray_straight_exit_left".equals(value)) return 3;
        if ("context_ra_gray_straight_left_exit_right".equals(value)
                || "context_ra_gray_straight_exit_left_right".equals(value)) return 7;
        if ("context_ra_gray_left_exit_right".equals(value)
                || "context_ra_gray_exit_left_right".equals(value)) return 6;
        if ("context_ra_gray_straight_left".equals(value)) return 3;
        if ("context_ra_gray_right".equals(value)
                || "context_ra_gray_only_exit_right".equals(value)
                || "context_ra_gray_hard_right".equals(value)) return 4;
        if ("context_ra_gray_left".equals(value)
                || "context_ra_gray_exit_left".equals(value)
                || "context_ra_gray_hard_left".equals(value)) return 2;
        if ("context_ra_gray_straight".equals(value)) return 1;
        return 0;
    }

    private static String grayRoadFromMask(int mask) {
        if ((mask & 7) == 7) return "context_ra_gray_straight_left_right";
        if ((mask & 6) == 6) return "context_ra_gray_left_right";
        if ((mask & 5) == 5) return "context_ra_gray_straight_right";
        if ((mask & 3) == 3) return "context_ra_gray_straight_left";
        if ((mask & 4) == 4) return "context_ra_gray_right";
        if ((mask & 2) == 2) return "context_ra_gray_left";
        if ((mask & 1) == 1) return "context_ra_gray_straight";
        return "";
    }

    private static String grayRoadKey(String maneuver, String street) {
        return clean(maneuver) + "|" + simplifyStreet(street);
    }

    private static void appendPart(StringBuilder out, String text, boolean enabled) {
        if (!enabled) return;
        if (out.length() > 0) out.append(" + ");
        out.append(text);
    }

    private static String nonZeroDistance(String value) {
        String clean = clean(value);
        if (TextUtils.isEmpty(clean)) return "";
        return distanceMeters(clean) > 1f ? clean : "";
    }

    private static boolean laneDistancePassed(String value) {
        String clean = clean(value);
        if (TextUtils.isEmpty(clean)) return false;
        Matcher matcher = NUMBER.matcher(clean.replace(',', '.'));
        return matcher.find() && distanceMeters(clean) <= 1f;
    }

    private static boolean isFinishManeuver(String maneuver) {
        if (maneuver == null) return false;
        String p = maneuver.toLowerCase(Locale.US);
        return p.contains("finish") || p.contains("arriv") || p.contains("destination") || p.contains("финиш");
    }

    private static boolean isRoundaboutManeuver(String maneuver) {
        if (maneuver == null) return false;
        String p = maneuver.toLowerCase(Locale.US);
        return p.contains("round") || p.contains("circular") || p.contains("круг");
    }

    private static boolean isStandaloneManeuverFrame(String maneuver) {
        String value = clean(maneuver);
        if (TextUtils.isEmpty(value)) return false;
        return isFinishManeuver(value) || isRoundaboutManeuver(value)
                || "context_ra_exit_right".equals(value)
                || "context_ra_exit_left".equals(value)
                || value.contains("take_")
                || value.contains("hard_turn")
                || value.contains("turn_back");
    }

    private static boolean isPriorityEventManeuver(String maneuver) {
        String value = clean(maneuver);
        if (TextUtils.isEmpty(value)) return false;
        return isFinishManeuver(value) || isRoundaboutManeuver(value)
                || value.contains("turn_back") || value.contains("uturn")
                || value.contains("hard_turn")
                || value.contains("exit_")
                || value.contains("take_");
    }

    private static String priorityManeuverForLaneHold(String incoming, String current) {
        String next = clean(incoming);
        if (isPriorityEventManeuver(next)) return next;
        String held = clean(current);
        return isPriorityEventManeuver(held) ? held : "";
    }

    private static boolean canMergeGrayRoad(String maneuver) {
        String value = clean(maneuver);
        return !TextUtils.isEmpty(value)
                && !value.contains("_gray_")
                && !isPriorityEventManeuver(value);
    }

    private static String firstMergeableManeuver(String... maneuvers) {
        if (maneuvers == null) return "";
        for (String maneuver : maneuvers) {
            String value = clean(maneuver);
            if (canMergeGrayRoad(value)) return value;
        }
        return "";
    }

    private static int directionStep(float relativeDegrees) {
        float centered = normalizeSignedDegrees(relativeDegrees);
        if (Math.abs(centered) < 7.5f) return 0;
        int step = Math.round(normalizeDegrees(centered) / 7.5f);
        return step >= 48 ? 0 : step;
    }

    private static int compassDirectionStep(float relativeDegrees) {
        float centered = normalizeSignedDegrees(relativeDegrees);
        if (Math.abs(centered) < 15f) return 0;
        int step = Math.round(normalizeDegrees(centered) / 30f) * 3;
        return step >= 36 ? 0 : step;
    }

    private static int nextCompassStep(int current, int target) {
        int c = normalizeCompassStep(current);
        int t = normalizeCompassStep(target);
        int forward = (t - c + 36) % 36;
        int backward = (c - t + 36) % 36;
        if (forward == 0) return t;
        return normalizeCompassStep(c + (forward <= backward ? 3 : -3));
    }

    private static int normalizeCompassStep(int step) {
        int out = ((step % 36) + 36) % 36;
        out = Math.round(out / 3f) * 3;
        return out == 36 ? 0 : out;
    }

    private static float normalizeDegrees(float value) {
        float out = value % 360f;
        return out < 0f ? out + 360f : out;
    }

    private static float normalizeSignedDegrees(float value) {
        float out = normalizeDegrees(value);
        return out > 180f ? out - 360f : out;
    }

    private static float blendDegrees(float from, float to, float toWeight) {
        float weight = Math.max(0f, Math.min(1f, toWeight));
        return normalizeDegrees(from + normalizeSignedDegrees(to - from) * weight);
    }

    private static boolean routeFinished(String routeDistance) {
        if (TextUtils.isEmpty(routeDistance)) return false;
        String value = routeDistance.toLowerCase(Locale.US).replace(',', '.').trim();
        if (value.contains("км") || value.contains("km")) return false;
        if (!(value.contains("м") || value.contains("m"))) return false;
        float parsed = distanceValue(value);
        return parsed >= 0f && parsed <= AUTO_FINISH_ROUTE_METERS;
    }

    private static boolean isTimeText(String value) {
        if (TextUtils.isEmpty(value)) return false;
        String v = value.toLowerCase(Locale.US);
        if (!NUMBER.matcher(v).find()) return false;
        return Pattern.compile("(?iu)(^|[^\\p{L}])(мин\\.?|минут[а-я]*|min|mins|minute|minutes|"
                + "час[а-я]*|hour|hours|hr|hrs)([^\\p{L}]|$)").matcher(v).find();
    }

    private static String arrivalFromRouteTime(String value) {
        if (TextUtils.isEmpty(value)) return "";
        String text = value.toLowerCase(Locale.US).replace(',', '.');
        Matcher matcher = NUMBER.matcher(text);
        float hours = 0f;
        float minutes = 0f;
        if (text.contains("ч") || text.contains("hour") || text.contains("hr")) {
            if (matcher.find()) hours = parseFloat(matcher.group());
            if (matcher.find()) minutes = parseFloat(matcher.group());
        } else if (matcher.find()) {
            minutes = parseFloat(matcher.group());
        }
        long addMinutes = Math.round(hours * 60f + minutes);
        if (addMinutes <= 0L) return "";
        return new SimpleDateFormat("HH:mm", Locale.US).format(new Date(System.currentTimeMillis() + addMinutes * 60000L));
    }

    private static int[] arrivalHourMinute(String value) {
        if (TextUtils.isEmpty(value)) return null;
        Matcher matcher = Pattern.compile("(\\d{1,2})\\s*[:.]\\s*(\\d{2})").matcher(value.trim());
        if (!matcher.find()) return null;
        try {
            int hour = Integer.parseInt(matcher.group(1));
            int minute = Integer.parseInt(matcher.group(2));
            if (hour < 0 || hour > 23 || minute < 0 || minute > 59) return null;
            return new int[]{hour, minute};
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    private static boolean isCameraDirection(String direction) {
        if (TextUtils.isEmpty(direction)) return false;
        String d = direction.toLowerCase(Locale.US);
        return d.contains("camera") || d.contains("камера");
    }

    private static String passiveRoadEventText(Intent intent, String direction) {
        String text = first(text(intent, "position"), text(intent, "event"), text(intent, "title"),
                text(intent, "description"), text(intent, "street"), text(intent, "road"));
        String combined = first(direction, "") + " " + first(text, "");
        combined = combined.toLowerCase(Locale.US);
        boolean passive = isCameraDirection(direction)
                || combined.contains("camera") || combined.contains("камера")
                || combined.contains("obstacle") || combined.contains("препят")
                || combined.contains("road_event") || combined.contains("roadevent");
        return passive ? first(text, maneuverLabel(direction)) : "";
    }

    private static String yandexSignEventText(Intent intent, String sourceLower) {
        String source = clean(sourceLower);
        source = TextUtils.isEmpty(source) ? "" : source.toLowerCase(Locale.US);
        String raw = first(text(intent, "first_event_text"),
                text(intent, "event"),
                text(intent, "main_event"),
                text(intent, "route_event"),
                text(intent, "first_event_type"),
                text(intent, "camera_speed_limit"),
                text(intent, "direction_sign_items"),
                text(intent, "raw_direction_sign_items"),
                text(intent, "direction_sign"),
                text(intent, "road_sign"),
                text(intent, "warning"));
        if (TextUtils.isEmpty(raw)) return "";
        String lower = raw.toLowerCase(Locale.US);
        boolean eventSource = source.contains("direction_sign") || source.contains("road_sign")
                || source.contains("roadsign") || source.contains("auto_widget_signs")
                || source.contains("event") || source.contains("warning")
                || source.contains("camera") || source.contains("next_camera");
        boolean eventText = lower.contains("camera") || lower.contains("камера")
                || lower.contains("speed") || lower.contains("скорост")
                || lower.contains("lane_control") || lower.contains("road_marking_control")
                || lower.contains("cross_road_control") || lower.contains("mobile_control")
                || lower.contains("warning") || lower.contains("предуп")
                || lower.contains("road_sign") || lower.contains("roadsign")
                || lower.contains("sign=") || lower.contains("roadSign=")
                || lower.contains("exit=");
        if (!eventSource && !eventText) return "";
        String exit = first(text(intent, "exit_number"), text(intent, "roundabout_exit"),
                text(intent, "exit_name"), text(intent, "exit"));
        if (!TextUtils.isEmpty(exit)) return "знак: " + exit;
        return "знак/предупреждение: " + trimText(raw, 160);
    }

    private static String trimText(String value, int max) {
        String text = clean(value);
        if (TextUtils.isEmpty(text) || text.length() <= max) return text;
        return text.substring(0, Math.max(0, max - 3)) + "...";
    }

    private static String distanceText(String distance, String unit) {
        if (TextUtils.isEmpty(distance)) return "";
        String cleanDistance = clean(distance);
        String cleanUnit = clean(unit);
        if (TextUtils.isEmpty(cleanUnit)) {
            return normalizeDistanceText(cleanDistance);
        }
        String lowerDistance = cleanDistance.toLowerCase(Locale.US);
        String lowerUnit = cleanUnit.toLowerCase(Locale.US);
        if (lowerDistance.endsWith(" " + lowerUnit)
                || lowerDistance.endsWith(lowerUnit)
                || lowerDistance.contains(" " + lowerUnit + " ")) {
            return normalizeDistanceText(cleanDistance);
        }
        return normalizeDistanceText(cleanDistance + " " + cleanUnit);
    }

    private static String dashboardDistance(String value) {
        String text = clean(value);
        if (TextUtils.isEmpty(text)) return "";
        String lower = text.toLowerCase(Locale.US);
        if (lower.contains("км") || lower.contains("km") || lower.contains("м")
                || lower.contains("meter")) {
            return normalizeDistanceText(text);
        }
        return NUMBER.matcher(text).find() ? normalizeDistanceText(text + " м") : text;
    }

    private static String metersDistanceText(String value) {
        String cleanValue = clean(value);
        if (TextUtils.isEmpty(cleanValue)) return "";
        if (!NUMBER.matcher(cleanValue.replace(',', '.')).find()) return "";
        float meters = parseFloat(cleanValue);
        if (meters < 0f) return "";
        if (meters < 1000f) return Math.round(meters) + " м";
        return String.format(Locale.US, "%.1f км", meters / 1000f);
    }

    private static String normalizeDistanceText(String value) {
        String text = clean(value);
        if (TextUtils.isEmpty(text)) return "";
        Matcher matcher = NUMBER.matcher(text.replace(',', '.'));
        if (!matcher.find()) return text;
        float number = parseFloat(matcher.group());
        if (number <= 0f) return "0 м";
        boolean km = isKm(text);
        float meters = km ? number * 1000f : number;
        if (meters < 1000f) return Math.round(roundMetersForDisplay(meters)) + " м";
        return String.format(Locale.US, "%.1f км", meters / 1000f);
    }

    private static float clusterDistanceValue(float distance, boolean km) {
        if (distance <= 0f) return 0f;
        return km ? distance : roundMetersForDisplay(distance);
    }

    private static float roundMetersForDisplay(float meters) {
        if (meters <= 0f) return 0f;
        return Math.max(DISTANCE_DISPLAY_STEP_METERS,
                Math.round(meters / DISTANCE_DISPLAY_STEP_METERS) * DISTANCE_DISPLAY_STEP_METERS);
    }

    private static boolean finishAllowedByDistance(String... values) {
        for (String value : values) {
            if (TextUtils.isEmpty(value)) continue;
            if (routeFinished(value)) return true;
            float meters = distanceMeters(value);
            if (meters > 0f) return meters <= AUTO_FINISH_ROUTE_METERS;
        }
        return false;
    }

    private static float distanceValue(String value) {
        if (value == null) return 0f;
        Matcher matcher = NUMBER.matcher(value.replace(',', '.'));
        if (!matcher.find()) return 0f;
        return parseFloat(matcher.group());
    }

    private static float distanceMeters(String value) {
        float distance = distanceValue(value);
        if (distance <= 0f) return 0f;
        return isKm(value) ? distance * 1000f : distance;
    }

    private static float parseFloat(String value) {
        try {
            return Float.parseFloat(value.replace(',', '.'));
        } catch (Exception ignored) {
            return 0f;
        }
    }

    private static boolean isKm(String unit) {
        if (unit == null) return false;
        String u = unit.toLowerCase(Locale.US);
        return u.contains("км") || u.contains("km");
    }

    private static String explicitManeuverDistanceFromIntent(Intent intent) {
        return first(metersDistanceText(first(text(intent, "maneuver_distance_meters"),
                        text(intent, "current_maneuver_distance_meters"),
                        text(intent, "distance_to_maneuver_meters"))),
                text(intent, "maneuver_distance"),
                text(intent, "current_maneuver_distance"),
                text(intent, "distance_to_maneuver"),
                text(intent, "next_maneuver_distance"),
                text(intent, "last_maneuver_distance"));
    }

    private static String explicitLaneDistanceFromIntent(Intent intent) {
        String unit = first(text(intent, "lane_distance_unit"), text(intent, "micro_distance_unit"),
                text(intent, "distance_unit"), text(intent, "unit"));
        return first(metersDistanceText(first(text(intent, "lane_distance_meters"),
                        text(intent, "micro_distance_meters"))),
                distanceText(first(text(intent, "lane_distance"), text(intent, "laneDistance"),
                        text(intent, "micro_distance"), text(intent, "microDistance")), unit));
    }

    private static boolean laneGuidanceDistancePacket(Intent intent, String source) {
        String sourceLower = clean(source).toLowerCase(Locale.US);
        if (bool(intent, "lane_guidance", false)) return true;
        if (laneDistanceOnlySource(sourceLower)) return true;
        if (!TextUtils.isEmpty(nonZeroDistance(explicitLaneDistanceFromIntent(intent)))) return true;
        return YandexCoreBridgeContract.SOURCE.equals(clean(source)) && hasLaneData(intent);
    }

    private static int speedNumber(String value) {
        float parsed = distanceValue(value);
        return parsed <= 0 ? 0 : Math.round(parsed);
    }

    private static int clampInt(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    private static String text(Intent intent, String key) {
        if (intent == null || key == null) return null;
        Bundle extras = intent.getExtras();
        if (extras == null || !extras.containsKey(key)) return null;
        Object value = extras.get(key);
        return value == null ? null : clean(String.valueOf(value));
    }

    private static String textByKeyPart(Intent intent, String part) {
        if (intent == null || part == null) return null;
        Bundle extras = intent.getExtras();
        if (extras == null) return null;
        String needle = part.toLowerCase(Locale.US);
        for (String key : extras.keySet()) {
            if (key != null && key.toLowerCase(Locale.US).contains(needle)) {
                Object value = extras.get(key);
                String text = value == null ? "" : clean(String.valueOf(value));
                if (!TextUtils.isEmpty(text)) return text;
            }
        }
        return null;
    }

    private static boolean bool(Intent intent, String key, boolean fallback) {
        if (intent == null || key == null) return fallback;
        Bundle extras = intent.getExtras();
        if (extras == null || !extras.containsKey(key)) return fallback;
        Object value = extras.get(key);
        if (value instanceof Boolean) return (Boolean) value;
        if (value instanceof Number) return ((Number) value).intValue() != 0;
        if (value == null) return fallback;
        String s = String.valueOf(value).toLowerCase(Locale.US).trim();
        if ("1".equals(s) || "true".equals(s) || "yes".equals(s) || "on".equals(s) || "да".equals(s)) return true;
        if ("0".equals(s) || "false".equals(s) || "no".equals(s) || "off".equals(s) || "нет".equals(s)) return false;
        return fallback;
    }

    private static String rawExtras(Intent intent) {
        Bundle extras = intent == null ? null : intent.getExtras();
        if (extras == null || extras.isEmpty()) return "{}";
        StringBuilder out = new StringBuilder("{");
        boolean first = true;
        for (String key : extras.keySet()) {
            if (!first) out.append(", ");
            first = false;
            Object value = extras.get(key);
            out.append(key).append('=').append(value == null ? "null" : clean(String.valueOf(value)));
        }
        out.append('}');
        return out.toString();
    }

    private static String shortRaw(String action, String raw) {
        String value = action + " " + raw;
        return value.length() <= 220 ? value : value.substring(0, 217) + "...";
    }

    private static String first(String... values) {
        for (String value : values) {
            if (!TextUtils.isEmpty(value)) return value;
        }
        return null;
    }

    private static String clean(String value) {
        if (value == null) return "";
        return value.replace('\n', ' ').replace('\r', ' ').trim().replaceAll("\\s+", " ");
    }
}
