package com.shopbilling.model;

/**
 * Result of executing an SQLite WAL checkpoint pragma.
 */
public record WalCheckpointResult(
        WalCheckpointMode mode,
        int busy,
        int logPages,
        int checkpointedPages
) {
    public boolean isSuccessful() {
        return busy == 0;
    }
}
