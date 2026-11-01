-- V4__hsn_master.sql
-- GST HSN Master Directory & Product / Sale Item Integration

CREATE TABLE IF NOT EXISTS hsn_master (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    hsn_code TEXT NOT NULL UNIQUE,
    description TEXT NOT NULL,
    product_type TEXT DEFAULT 'GOODS',
    updated_at TEXT NOT NULL DEFAULT (datetime('now', '+05:30'))
);

CREATE INDEX IF NOT EXISTS idx_hsn_code ON hsn_master(hsn_code);
CREATE INDEX IF NOT EXISTS idx_hsn_description ON hsn_master(description);

-- Product integration for HSN and GST rate
ALTER TABLE products ADD COLUMN hsn_code TEXT DEFAULT NULL;
ALTER TABLE products ADD COLUMN gst_rate REAL DEFAULT 18.0;

-- Sale line items snapshot for HSN and GST rate
ALTER TABLE sale_items ADD COLUMN hsn_code TEXT DEFAULT NULL;
ALTER TABLE sale_items ADD COLUMN gst_rate REAL DEFAULT 18.0;

-- Seed core electrical trade HSN directory entries
INSERT OR IGNORE INTO hsn_master (hsn_code, description, product_type) VALUES
('8544', 'Insulated (including enamelled or anodised) wire, cable and other insulated electric conductors; optical fibre cables', 'GOODS'),
('85446020', 'Plastic insulated electrical wire and cables for domestic and industrial installation', 'GOODS'),
('8536', 'Electrical apparatus for switching or protecting electrical circuits (switches, relays, fuses, surge suppressors, plugs, sockets)', 'GOODS'),
('85365020', 'Switch plate and switches for domestic electrical use (modular switches, surface switches)', 'GOODS'),
('85369090', 'Other electrical apparatus: junction boxes, terminals, wire connectors', 'GOODS'),
('8538', 'Parts suitable for use with distribution boards, panels, consoles, desks, cabinets and other bases for electric control', 'GOODS'),
('8504', 'Electrical transformers, static converters (e.g. inverters, rectifiers, SMPS, adaptors) and inductors', 'GOODS'),
('8539', 'Electric filament or discharge lamps; arc lamps; light-emitting diode (LED) light sources, bulbs and tubes', 'GOODS'),
('9405', 'Luminaires and lighting fittings including ceiling lights, spotlights, batten holders, LED panel fittings', 'GOODS'),
('8414', 'Air or vacuum pumps, compressors and fans; ventilating or recycling hoods incorporating a fan', 'GOODS'),
('84145120', 'Ceiling fans with self-contained electric motor for domestic and commercial installation', 'GOODS'),
('84145130', 'Pedestal and table fans with electric motor', 'GOODS'),
('84145140', 'Wall exhaust fans and ventilation blowers', 'GOODS'),
('8516', 'Electric instantaneous or storage water heaters (geysers), immersion heaters, electric heating apparatus', 'GOODS'),
('3917', 'Tubes, pipes and hoses, and fittings therefor (PVC electrical conduit pipes, casing, capping and bends)', 'GOODS'),
('8537', 'Boards, panels, consoles, desks, cabinets and other bases equipped with two or more apparatus of heading 8535 or 8536 (MCB Distribution Boxes)', 'GOODS'),
('85362030', 'Miniature Circuit Breakers (MCB) up to 63A for domestic and commercial overload protection', 'GOODS'),
('85363000', 'Residual Current Circuit Breakers (RCCB / ELCB) and other earth leakage circuit breakers', 'GOODS');
