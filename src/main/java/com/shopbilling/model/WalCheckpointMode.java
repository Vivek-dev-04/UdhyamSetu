package com.shopbilling.model;

/**
 * Modes for SQLite WAL checkpoint operations.
 */
public enum WalCheckpointMode {
    PASSIVE,
    FULL,
    RESTART,
    TRUNCATE
}
