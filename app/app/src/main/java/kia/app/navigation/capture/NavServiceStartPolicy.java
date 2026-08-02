package kia.app.navigation.capture;

/** Pure gate preventing one-second navigator heartbeats from restarting an already live service. */
final class NavServiceStartPolicy {
    private NavServiceStartPolicy() {
    }

    static boolean shouldStart(boolean serviceRunning, long nowElapsed,
                               long lastStartElapsed, long minIntervalMs) {
        if (serviceRunning) return false;
        if (lastStartElapsed <= 0L || nowElapsed < lastStartElapsed) return true;
        return nowElapsed - lastStartElapsed >= Math.max(0L, minIntervalMs);
    }
}
