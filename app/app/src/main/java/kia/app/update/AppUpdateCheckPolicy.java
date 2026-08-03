package kia.app.update;

/** Shared wall-clock policy for launch and foreground-service OTA checks. */
public final class AppUpdateCheckPolicy {
    public static final long SUCCESS_INTERVAL_MS = 60L * 60L * 1000L;
    public static final long BACKGROUND_POLL_MS = 15L * 60L * 1000L;

    private AppUpdateCheckPolicy() {
    }

    public static boolean shouldCheck(long nowMillis, long lastSuccessfulCheckMillis) {
        if (nowMillis <= 0L || lastSuccessfulCheckMillis <= 0L) return true;
        if (nowMillis < lastSuccessfulCheckMillis) return true;
        return nowMillis - lastSuccessfulCheckMillis >= SUCCESS_INTERVAL_MS;
    }
}
