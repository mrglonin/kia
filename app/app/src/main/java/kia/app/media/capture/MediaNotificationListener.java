package kia.app.media.capture;

import android.service.notification.NotificationListenerService;
import android.service.notification.StatusBarNotification;

import kia.app.core.AppLog;
import kia.app.core.settings.AppSettings;
import kia.app.media.domain.CallFeature;
import kia.app.navigation.capture.DgisNotificationParser;
import kia.app.navigation.capture.NavigationSourcePolicy;
import kia.app.navigation.domain.NavigationFeature;

public final class MediaNotificationListener extends NotificationListenerService {
    private static final long IGNORED_LOG_MS = 10000L;
    private static String lastIgnoredDgis = "";
    private static long lastIgnoredDgisAt;

    @Override
    public void onListenerConnected() {
        super.onListenerConnected();
        scanActive(this);
    }

    @Override
    public void onNotificationPosted(StatusBarNotification sbn) {
        if (handleCallNotification(this, sbn)) return;
        handleNavigationNotification(this, sbn);
    }

    @Override
    public void onNotificationRemoved(StatusBarNotification sbn) {
        if (sbn != null && CallNotificationParser.isCallLikePackage(sbn.getPackageName())) {
            CallFeature.get(this).reportEnded("notification removed " + sbn.getPackageName());
        }
        if (sbn != null && DgisNotificationParser.isDgisPackage(sbn.getPackageName())
                && dgisIngressAllowed(this)) {
            if (!scanActiveDgis(this)) {
                NavigationFeature.get(this).handleDgisNotificationRemoved(sbn.getPackageName());
            }
        }
    }

    public static void scanActive(MediaNotificationListener listener) {
        if (listener == null) return;
        scanActiveDgis(listener);
    }

    private static boolean scanActiveDgis(MediaNotificationListener listener) {
        try {
            StatusBarNotification[] active = listener.getActiveNotifications();
            if (active == null) return false;
            boolean found = false;
            for (StatusBarNotification sbn : active) {
                found |= handleCallNotification(listener, sbn);
                found |= handleNavigationNotification(listener, sbn);
            }
            return found;
        } catch (Exception e) {
            AppLog.line(listener, "Notification scan failed: " + e.getClass().getSimpleName());
            return false;
        }
    }

    private static boolean handleCallNotification(MediaNotificationListener listener,
                                                  StatusBarNotification sbn) {
        CallNotificationParser.Parsed parsed = CallNotificationParser.parse(sbn);
        if (parsed == null) return false;
        AppLog.line(listener, "Call notification: " + parsed.source
                + " | " + parsed.name + " | " + parsed.phone);
        CallFeature.get(listener).reportActive(parsed.name, parsed.phone, parsed.source);
        return true;
    }

    private static boolean handleNavigationNotification(MediaNotificationListener listener,
                                                        StatusBarNotification sbn) {
        if (!dgisIngressAllowed(listener)) return false;
        DgisNotificationParser.Parsed parsed = DgisNotificationParser.parse(sbn);
        if (parsed == null) {
            logIgnoredDgis(listener, sbn);
            return false;
        }
        AppLog.line(listener, "2GIS notification: " + parsed.raw);
        NavigationFeature.get(listener).handleDgisNotification(parsed.maneuver, parsed.distance,
                parsed.unit, parsed.street, parsed.raw);
        return true;
    }

    private static boolean dgisIngressAllowed(MediaNotificationListener listener) {
        return listener != null && NavigationSourcePolicy.ingressAllowed(
                AppSettings.navigationEnabled(listener),
                AppSettings.navSourceMode(listener),
                NavigationSourcePolicy.SOURCE_DGIS);
    }

    private static void logIgnoredDgis(MediaNotificationListener listener, StatusBarNotification sbn) {
        if (listener == null || sbn == null || !DgisNotificationParser.isDgisPackage(sbn.getPackageName())) {
            return;
        }
        String raw = DgisNotificationParser.rawSummary(sbn);
        if (raw.isEmpty()) return;
        long now = System.currentTimeMillis();
        if (raw.equals(lastIgnoredDgis) && now - lastIgnoredDgisAt < IGNORED_LOG_MS) return;
        lastIgnoredDgis = raw;
        lastIgnoredDgisAt = now;
        AppLog.line(listener, "2GIS notification ignored: " + raw);
    }
}
