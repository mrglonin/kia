package kia.app.protocol.adapter;

/** Actual handoff result for a frame submitted to the adapter transport. */
public enum AdapterTxOutcome {
    WRITTEN,
    QUEUED,
    BLOCKED
}
