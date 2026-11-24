package kia.app.protocol.adapter

/** Actual handoff result for a frame submitted to the adapter transport. */
enum class AdapterTxOutcome {
    WRITTEN,
    QUEUED,
    BLOCKED,
}
