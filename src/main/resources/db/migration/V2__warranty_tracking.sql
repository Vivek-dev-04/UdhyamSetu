-- V2: Warranty & Appliance Serial Number Tracking (PRD Section 11, Section 15 Question 6)

CREATE TABLE item_warranties (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    sale_id INTEGER NOT NULL REFERENCES sales(id),
    variant_id INTEGER NOT NULL REFERENCES product_variants(id),
    serial_number TEXT UNIQUE NOT NULL,
    warranty_months INTEGER NOT NULL CHECK (warranty_months > 0),
    warranty_start_date TEXT NOT NULL,
    warranty_end_date TEXT NOT NULL,
    status TEXT NOT NULL DEFAULT 'ACTIVE' CHECK (status IN ('ACTIVE', 'EXPIRED', 'CLAIMED', 'VOID')),
    claim_notes TEXT,
    created_at TEXT NOT NULL DEFAULT (datetime('now', '+05:30')),
    created_by_user_id INTEGER NOT NULL REFERENCES users(id)
);

CREATE INDEX idx_item_warranties_serial ON item_warranties(serial_number);
CREATE INDEX idx_item_warranties_sale ON item_warranties(sale_id);
CREATE INDEX idx_item_warranties_variant ON item_warranties(variant_id);
