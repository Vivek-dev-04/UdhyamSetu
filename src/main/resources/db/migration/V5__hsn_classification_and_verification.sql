-- V5__hsn_classification_and_verification.sql
-- Production-Grade GST HSN Classification, Verification, Audit Trail & GST Rate Master

-- 1. ENHANCE HSN MASTER DIRECTORY
ALTER TABLE hsn_master ADD COLUMN chapter TEXT;
ALTER TABLE hsn_master ADD COLUMN heading TEXT;
ALTER TABLE hsn_master ADD COLUMN subheading TEXT;
ALTER TABLE hsn_master ADD COLUMN tariff_item TEXT;
ALTER TABLE hsn_master ADD COLUMN source TEXT DEFAULT 'CBIC Customs Tariff';
ALTER TABLE hsn_master ADD COLUMN source_version TEXT DEFAULT '2024-25';
ALTER TABLE hsn_master ADD COLUMN import_date TEXT;
ALTER TABLE hsn_master ADD COLUMN technical_notes TEXT;

-- Update existing V4 records with chapter and heading codes
UPDATE hsn_master SET
    chapter = substr(hsn_code, 1, 2),
    heading = substr(hsn_code, 1, min(length(hsn_code), 4)),
    subheading = CASE WHEN length(hsn_code) >= 6 THEN substr(hsn_code, 1, 6) ELSE NULL END,
    tariff_item = CASE WHEN length(hsn_code) = 8 THEN hsn_code ELSE NULL END,
    source = 'CBIC Customs Tariff',
    source_version = '2024-25',
    import_date = datetime('now', '+05:30')
WHERE chapter IS NULL;

-- 2. HSN IMPORT AUDIT HISTORY
CREATE TABLE IF NOT EXISTS hsn_import_history (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    file_name TEXT NOT NULL,
    file_hash TEXT,
    source_name TEXT NOT NULL,
    source_version TEXT,
    total_rows INTEGER NOT NULL DEFAULT 0,
    imported_count INTEGER NOT NULL DEFAULT 0,
    updated_count INTEGER NOT NULL DEFAULT 0,
    skipped_count INTEGER NOT NULL DEFAULT 0,
    status TEXT NOT NULL CHECK(status IN ('SUCCESS', 'PARTIAL', 'FAILED')),
    imported_by_user_id INTEGER REFERENCES users(id),
    imported_at TEXT NOT NULL DEFAULT (datetime('now', '+05:30'))
);
CREATE INDEX IF NOT EXISTS idx_hsn_import_date ON hsn_import_history(imported_at);

-- 3. PRODUCT GROUPS (Tally-inspired classification hierarchy)
CREATE TABLE IF NOT EXISTS product_groups (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    parent_group_id INTEGER REFERENCES product_groups(id) ON DELETE SET NULL,
    group_code TEXT NOT NULL UNIQUE,
    group_name TEXT NOT NULL,
    default_hsn_code TEXT,
    default_gst_rate REAL DEFAULT 18.0,
    description TEXT,
    is_active INTEGER NOT NULL DEFAULT 1 CHECK (is_active IN (0, 1)),
    created_at TEXT NOT NULL DEFAULT (datetime('now', '+05:30'))
);
CREATE INDEX IF NOT EXISTS idx_product_groups_code ON product_groups(group_code);

-- 4. PRODUCT HSN MAPPING & APPROVAL STATUS
CREATE TABLE IF NOT EXISTS product_hsn_mapping (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    product_id INTEGER NOT NULL REFERENCES products(id) ON DELETE CASCADE,
    hsn_code TEXT NOT NULL REFERENCES hsn_master(hsn_code),
    status TEXT NOT NULL CHECK(status IN ('PENDING', 'SUGGESTED', 'VERIFIED', 'REJECTED', 'REVIEW_REQUIRED')),
    matching_priority INTEGER NOT NULL CHECK(matching_priority BETWEEN 1 AND 7),
    match_reason TEXT NOT NULL,
    supporting_source TEXT,
    missing_specifications TEXT,
    verified_by_user_id INTEGER REFERENCES users(id),
    verified_at TEXT,
    rejection_reason TEXT,
    is_active INTEGER NOT NULL DEFAULT 1 CHECK (is_active IN (0, 1)),
    created_at TEXT NOT NULL DEFAULT (datetime('now', '+05:30')),
    updated_at TEXT NOT NULL DEFAULT (datetime('now', '+05:30')),
    UNIQUE(product_id, is_active)
);
CREATE INDEX IF NOT EXISTS idx_prod_hsn_map_prod ON product_hsn_mapping(product_id);
CREATE INDEX IF NOT EXISTS idx_prod_hsn_map_status ON product_hsn_mapping(status);

-- 5. PRODUCT HSN TRADE KEYWORDS & ALIASES (Priority 3 Matching)
CREATE TABLE IF NOT EXISTS product_hsn_aliases (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    alias_term TEXT NOT NULL,
    hsn_code TEXT NOT NULL REFERENCES hsn_master(hsn_code),
    priority_score INTEGER NOT NULL DEFAULT 100,
    source TEXT DEFAULT 'TRADE_CATALOG',
    created_at TEXT NOT NULL DEFAULT (datetime('now', '+05:30'))
);
CREATE INDEX IF NOT EXISTS idx_hsn_aliases_term ON product_hsn_aliases(alias_term);

-- 6. GST RATE MASTER (Effective-dated statutory tax rates)
CREATE TABLE IF NOT EXISTS gst_rate_master (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    hsn_code TEXT NOT NULL,
    gst_rate REAL NOT NULL,
    cgst_rate REAL NOT NULL,
    sgst_rate REAL NOT NULL,
    igst_rate REAL NOT NULL,
    effective_from TEXT NOT NULL,
    effective_until TEXT NOT NULL DEFAULT '9999-12-31',
    notification_reference TEXT NOT NULL,
    verification_status TEXT NOT NULL DEFAULT 'VERIFIED',
    source TEXT DEFAULT 'CBIC GST Notification',
    created_at TEXT NOT NULL DEFAULT (datetime('now', '+05:30'))
);
CREATE INDEX IF NOT EXISTS idx_gst_rate_hsn_date ON gst_rate_master(hsn_code, effective_from, effective_until);

-- 7. CLASSIFICATION AUDIT TRAIL & CHANGE MANAGEMENT
CREATE TABLE IF NOT EXISTS hsn_verification_history (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    product_id INTEGER NOT NULL REFERENCES products(id) ON DELETE CASCADE,
    action TEXT NOT NULL CHECK(action IN ('SUGGESTED', 'VERIFIED', 'REJECTED', 'OVERRIDDEN', 'RATE_CHANGED', 'UNLINKED')),
    previous_hsn TEXT,
    new_hsn TEXT,
    previous_gst_rate REAL,
    new_gst_rate REAL,
    user_id INTEGER REFERENCES users(id),
    reason TEXT,
    verification_source TEXT,
    status TEXT NOT NULL,
    created_at TEXT NOT NULL DEFAULT (datetime('now', '+05:30'))
);
CREATE INDEX IF NOT EXISTS idx_hsn_audit_prod ON hsn_verification_history(product_id);

-- 8. EXTEND PRODUCTS TABLE FOR GROUP AND CLASSIFICATION STATUS
ALTER TABLE products ADD COLUMN group_id INTEGER REFERENCES product_groups(id) ON DELETE SET NULL;
ALTER TABLE products ADD COLUMN hsn_status TEXT NOT NULL DEFAULT 'PENDING' CHECK (hsn_status IN ('PENDING', 'SUGGESTED', 'VERIFIED', 'REJECTED', 'REVIEW_REQUIRED'));

-- 9. EXTEND SALE_ITEMS TABLE FOR STATUTORY TAX COMPONENT SNAPSHOTS
ALTER TABLE sale_items ADD COLUMN cgst_rate REAL DEFAULT 9.0;
ALTER TABLE sale_items ADD COLUMN sgst_rate REAL DEFAULT 9.0;
ALTER TABLE sale_items ADD COLUMN igst_rate REAL DEFAULT 0.0;
ALTER TABLE sale_items ADD COLUMN cgst_paise INTEGER DEFAULT 0;
ALTER TABLE sale_items ADD COLUMN sgst_paise INTEGER DEFAULT 0;
ALTER TABLE sale_items ADD COLUMN igst_paise INTEGER DEFAULT 0;

-- 10. SEED CORE PRODUCT GROUPS (Electrical & Sanitary Trade)
INSERT OR IGNORE INTO product_groups (group_code, group_name, default_hsn_code, default_gst_rate, description) VALUES
('GRP_SWITCH', 'Modular & Conventional Switches', '85365020', 18.0, 'Switch plates, modular switches, bell pushes and surface switches <= 1000V'),
('GRP_WIRE', 'Cables & Insulated Wires', '85446020', 18.0, 'PVC insulated single core and multi core copper/aluminium wires'),
('GRP_CIRCUIT', 'Circuit Breakers & Protection', '85362030', 18.0, 'Miniature circuit breakers (MCBs), isolators, RCCBs and distribution boxes'),
('GRP_LIGHT', 'Lighting & Luminaires', '9405', 18.0, 'LED bulbs, batten fittings, panel lights, flood lights and decorative luminaires'),
('GRP_FAN', 'Ceiling, Table & Exhaust Fans', '84145120', 18.0, 'Electric fans incorporating a motor for domestic and commercial ventilation'),
('GRP_HEATER', 'Water Heaters & Geysers', '85161000', 18.0, 'Electric storage geysers, instantaneous water heaters and immersion rods'),
('GRP_CONDUIT', 'PVC Conduit Pipes & Fittings', '39172300', 18.0, 'Rigid PVC conduit pipes, bends, junction boxes, casing and capping'),
('GRP_SANITARY', 'Sanitary Ware & Faucets', '84818020', 18.0, 'Taps, cocks, flush valves, angle valves and sanitary brassware fittings');

-- 11. SEED EXPANDED OFFICIAL HSN DIRECTORY (CBIC Tariff with Technical Notes & Chapter Hierarchies)
INSERT OR IGNORE INTO hsn_master (hsn_code, description, chapter, heading, subheading, tariff_item, product_type, technical_notes) VALUES
('85361010', 'Cartridge fuses up to 16A', '85', '8536', '853610', '85361010', 'GOODS', 'Apparatus for protecting electrical circuits <= 1000V. GRI 1 / Chapter 85 Note 4.'),
('85361020', 'Rewirable and kit-kat porcelain fuses up to 63A', '85', '8536', '853610', '85361020', 'GOODS', 'Apparatus for protecting electrical circuits <= 1000V.'),
('85362010', 'Automatic circuit breakers for current rating < 63A', '85', '8536', '853620', '85362010', 'GOODS', 'Includes single/double/triple pole MCBs.'),
('85362090', 'Other automatic circuit breakers <= 1000V', '85', '8536', '853620', '85362090', 'GOODS', 'Includes MCCBs and motor protection circuit breakers <= 1000V.'),
('85366910', 'Plugs and sockets for domestic installation', '85', '8536', '853669', '85366910', 'GOODS', '6A and 16A modular sockets, 2-pin/3-pin round and flat pin sockets.'),
('85365010', 'Control and switchgear control switches <= 1000V', '85', '8536', '853650', '85365010', 'GOODS', 'Rotary switches, cam switches, selector switches.'),
('85441110', 'Winding wire of copper, enamelled or insulated', '85', '8544', '854411', '85441110', 'GOODS', 'Insulated copper wire for motor winding and transformers.'),
('85441990', 'Other insulated winding wire including aluminium', '85', '8544', '854419', '85441990', 'GOODS', 'Winding wires other than pure copper.'),
('85444990', 'Other electric conductors for voltage <= 1000V', '85', '8544', '854449', '85444990', 'GOODS', 'Multicore flexible cables, telephone cables, submersible flat cables.'),
('85044090', 'Other static converters, inverters and UPS systems', '85', '8504', '850440', '85044090', 'GOODS', 'Solar inverters, domestic home UPS and battery chargers.'),
('85161000', 'Electric instantaneous or storage water heaters and immersion heaters', '85', '8516', '851610', '85161000', 'GOODS', 'Storage geysers up to 25L and instant geysers.'),
('39172300', 'Rigid tubes, pipes and hoses of polymers of vinyl chloride (PVC)', '39', '3917', '391723', '39172300', 'GOODS', 'Heavy and medium duty electrical PVC conduit pipes.'),
('39174000', 'Fittings for tubes, pipes and hoses of plastics (e.g. bends, couplings)', '39', '3917', '391740', '39174000', 'GOODS', 'PVC conduit bends, tees, junction box covers.'),
('84818020', 'Taps, cocks and similar appliances for sink, lavatory or bath', '84', '8481', '848180', '84818020', 'GOODS', 'Brass bib cocks, pillar cocks, angle valves, stop cocks, mixer taps.'),
('69101000', 'Ceramic sinks, wash basins, water closet pans of porcelain or china', '69', '6910', '691010', '69101000', 'GOODS', 'Vitreous china sanitary fixtures. Excludes plastic sanitaryware.'),
('84137010', 'Submersible pumps', '84', '8413', '841370', '84137010', 'GOODS', 'Centrifugal submersible pumps for water supply and agricultural irrigation.'),
('85359090', 'Other electrical apparatus for switching or protecting electrical circuits for a voltage exceeding 1000V', '85', '8535', '853590', '85359090', 'GOODS', 'High voltage switchgear, disconnectors, isolators and circuit breakers exceeding 1000V.');

-- 12. SEED TRADE KEYWORDS & ALIASES (Priority 3 Matching Engine)
INSERT OR IGNORE INTO product_hsn_aliases (alias_term, hsn_code, priority_score, source) VALUES
('switch', '85365020', 100, 'ELECTRICAL_TRADE'),
('1-way switch', '85365020', 120, 'ELECTRICAL_TRADE'),
('2-way switch', '85365020', 120, 'ELECTRICAL_TRADE'),
('bell push', '85365020', 110, 'ELECTRICAL_TRADE'),
('socket', '85366910', 100, 'ELECTRICAL_TRADE'),
('plug', '85366910', 100, 'ELECTRICAL_TRADE'),
('mcb', '85362030', 120, 'ELECTRICAL_TRADE'),
('rccb', '85363000', 120, 'ELECTRICAL_TRADE'),
('isolator', '85362090', 110, 'ELECTRICAL_TRADE'),
('distribution board', '8537', 100, 'ELECTRICAL_TRADE'),
('wire', '85446020', 100, 'ELECTRICAL_TRADE'),
('cable', '85446020', 100, 'ELECTRICAL_TRADE'),
('copper wire', '85446020', 110, 'ELECTRICAL_TRADE'),
('conduit pipe', '39172300', 110, 'ELECTRICAL_TRADE'),
('pvc pipe', '39172300', 100, 'ELECTRICAL_TRADE'),
('fan', '84145120', 100, 'ELECTRICAL_TRADE'),
('ceiling fan', '84145120', 120, 'ELECTRICAL_TRADE'),
('exhaust fan', '84145140', 120, 'ELECTRICAL_TRADE'),
('geyser', '85161000', 120, 'ELECTRICAL_TRADE'),
('water heater', '85161000', 120, 'ELECTRICAL_TRADE'),
('led bulb', '8539', 110, 'ELECTRICAL_TRADE'),
('panel light', '9405', 110, 'ELECTRICAL_TRADE'),
('tap', '84818020', 100, 'SANITARY_TRADE'),
('angle valve', '84818020', 110, 'SANITARY_TRADE'),
('pillar cock', '84818020', 110, 'SANITARY_TRADE'),
('wash basin', '69101000', 110, 'SANITARY_TRADE'),
('commode', '69101000', 110, 'SANITARY_TRADE'),
('submersible pump', '84137010', 120, 'ELECTRICAL_TRADE'),
('pump', '84137010', 100, 'ELECTRICAL_TRADE'),
('high voltage', '85359090', 120, 'ELECTRICAL_TRADE'),
('11kv', '85359090', 130, 'ELECTRICAL_TRADE');

-- 13. SEED STATUTORY GST RATE MASTER WITH OFFICIAL NOTIFICATION CITATIONS
INSERT OR IGNORE INTO gst_rate_master (hsn_code, gst_rate, cgst_rate, sgst_rate, igst_rate, effective_from, notification_reference) VALUES
('8544', 18.0, 9.0, 9.0, 18.0, '2017-07-01', 'Notification No. 01/2017-Central Tax (Rate), Schedule III, S.No. 395'),
('85446020', 18.0, 9.0, 9.0, 18.0, '2017-07-01', 'Notification No. 01/2017-Central Tax (Rate), Schedule III, S.No. 395'),
('8536', 18.0, 9.0, 9.0, 18.0, '2017-07-01', 'Notification No. 01/2017-Central Tax (Rate), Schedule III, S.No. 390'),
('85365020', 18.0, 9.0, 9.0, 18.0, '2017-07-01', 'Notification No. 01/2017-Central Tax (Rate), Schedule III, S.No. 390'),
('85362030', 18.0, 9.0, 9.0, 18.0, '2017-07-01', 'Notification No. 01/2017-Central Tax (Rate), Schedule III, S.No. 390'),
('85363000', 18.0, 9.0, 9.0, 18.0, '2017-07-01', 'Notification No. 01/2017-Central Tax (Rate), Schedule III, S.No. 390'),
('85366910', 18.0, 9.0, 9.0, 18.0, '2017-07-01', 'Notification No. 01/2017-Central Tax (Rate), Schedule III, S.No. 390'),
('8537', 18.0, 9.0, 9.0, 18.0, '2017-07-01', 'Notification No. 01/2017-Central Tax (Rate), Schedule III, S.No. 391'),
('8538', 18.0, 9.0, 9.0, 18.0, '2017-07-01', 'Notification No. 01/2017-Central Tax (Rate), Schedule III, S.No. 392'),
('8539', 18.0, 9.0, 9.0, 18.0, '2017-07-01', 'Notification No. 01/2017-Central Tax (Rate), Schedule III, S.No. 393'),
('85395000', 12.0, 6.0, 6.0, 12.0, '2019-10-01', 'Notification No. 14/2019-Central Tax (Rate), Schedule II, S.No. 226A'),
('9405', 18.0, 9.0, 9.0, 18.0, '2017-07-01', 'Notification No. 01/2017-Central Tax (Rate), Schedule III, S.No. 440'),
('8414', 18.0, 9.0, 9.0, 18.0, '2017-07-01', 'Notification No. 01/2017-Central Tax (Rate), Schedule III, S.No. 317'),
('84145120', 18.0, 9.0, 9.0, 18.0, '2017-07-01', 'Notification No. 01/2017-Central Tax (Rate), Schedule III, S.No. 317'),
('84145140', 18.0, 9.0, 9.0, 18.0, '2017-07-01', 'Notification No. 01/2017-Central Tax (Rate), Schedule III, S.No. 317'),
('8516', 18.0, 9.0, 9.0, 18.0, '2017-07-01', 'Notification No. 01/2017-Central Tax (Rate), Schedule III, S.No. 379'),
('85161000', 18.0, 9.0, 9.0, 18.0, '2017-07-01', 'Notification No. 01/2017-Central Tax (Rate), Schedule III, S.No. 379'),
('3917', 18.0, 9.0, 9.0, 18.0, '2017-07-01', 'Notification No. 01/2017-Central Tax (Rate), Schedule III, S.No. 100'),
('39172300', 18.0, 9.0, 9.0, 18.0, '2017-07-01', 'Notification No. 01/2017-Central Tax (Rate), Schedule III, S.No. 100'),
('84818020', 18.0, 9.0, 9.0, 18.0, '2017-07-01', 'Notification No. 01/2017-Central Tax (Rate), Schedule III, S.No. 362'),
('69101000', 18.0, 9.0, 9.0, 18.0, '2017-07-01', 'Notification No. 01/2017-Central Tax (Rate), Schedule III, S.No. 180');

-- 14. AUTO-LINK ANY EXISTING PRODUCTS TO VERIFIED STATUS IF PREVIOUSLY MAPPED
INSERT OR IGNORE INTO product_hsn_mapping (product_id, hsn_code, status, matching_priority, match_reason, supporting_source, is_active)
SELECT p.id, p.hsn_code, 'VERIFIED', 1, 'Initial migration from verified product catalog', 'Product Master', 1
FROM products p
WHERE p.hsn_code IS NOT NULL AND p.hsn_code != '';

UPDATE products SET hsn_status = 'VERIFIED' WHERE hsn_code IS NOT NULL AND hsn_code != '';

