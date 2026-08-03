package kia.app.diagnostics;

/** Prevents an in-flight tick from an old HandlerThread session joining a restarted monitor. */
final class HealthMonitorSessionPolicy {
    private HealthMonitorSessionPolicy() {
    }

    static boolean current(boolean running, long currentGeneration, long taskGeneration,
                           boolean sameHandler, boolean sameTask) {
        return running && currentGeneration == taskGeneration && sameHandler && sameTask;
    }
}
