-- V3__cloud_backup_sync.sql
-- Cloud Backup & Disaster Recovery Sync Queue (PRD Section 7.11 FR-47, Section 14)

CREATE TABLE IF NOT EXISTS cloud_sync_queue (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    backup_file_name TEXT NOT NULL,
    encrypted_file_path TEXT NOT NULL,
    status TEXT NOT NULL CHECK (status IN ('PENDING', 'IN_PROGRESS', 'UPLOADED', 'FAILED')),
    attempts INTEGER NOT NULL DEFAULT 0,
    last_error TEXT,
    created_at TEXT NOT NULL DEFAULT (datetime('now', '+05:30')),
    uploaded_at TEXT
);

CREATE INDEX IF NOT EXISTS idx_cloud_sync_status ON cloud_sync_queue(status);
CREATE INDEX IF NOT EXISTS idx_cloud_sync_created ON cloud_sync_queue(created_at);
