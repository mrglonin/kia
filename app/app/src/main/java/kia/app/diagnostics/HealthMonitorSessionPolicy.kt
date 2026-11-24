package kia.app.diagnostics

/** Prevents an in-flight tick from an old HandlerThread session joining a restarted monitor. */
internal object HealthMonitorSessionPolicy {
    @JvmStatic
    fun current(
        running: Boolean,
        currentGeneration: Long,
        taskGeneration: Long,
        sameHandler: Boolean,
        sameTask: Boolean,
    ): Boolean {
        return running && currentGeneration == taskGeneration && sameHandler && sameTask
    }
}
