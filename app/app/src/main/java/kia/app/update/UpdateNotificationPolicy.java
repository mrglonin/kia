package kia.app.update;

/** Pure decision rule for the shared KIA/Yandex OTA notification. */
final class UpdateNotificationPolicy {
    enum Action {
        KEEP,
        CANCEL,
        NOTIFY
    }

    private UpdateNotificationPolicy() {
    }

    static Action action(boolean appAvailable, boolean navigatorAvailable,
                         boolean appChecking, boolean navigatorChecking,
                         String token, String lastNotifiedToken) {
        if (appChecking || navigatorChecking) return Action.KEEP;
        if (!appAvailable && !navigatorAvailable) {
            return Action.CANCEL;
        }
        String current = token == null ? "" : token;
        String previous = lastNotifiedToken == null ? "" : lastNotifiedToken;
        return !current.isEmpty() && current.equals(previous) ? Action.KEEP : Action.NOTIFY;
    }
}
