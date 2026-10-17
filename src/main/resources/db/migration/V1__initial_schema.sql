-- ============================================================================
-- V1__initial_schema.sql
-- Shop Billing Desktop App: Complete V1 Schema
-- ============================================================================

-- 1. USERS & ROLES (PRD Section 5, 7.10)
CREATE TABLE roles (
    name TEXT PRIMARY KEY NOT NULL
);
INSERT INTO roles (name) VALUES ('OWNER'), ('STAFF');

CREATE TABLE users (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    username TEXT UNIQUE NOT NULL,
    password_hash TEXT NOT NULL,
    role TEXT NOT NULL REFERENCES roles(name),
    full_name TEXT NOT NULL,
    is_active INTEGER NOT NULL DEFAULT 1 CHECK (is_active IN (0, 1)),
    created_at TEXT NOT NULL DEFAULT (datetime('now', '+05:30'))
);

-- 2. PARTIES: CUSTOMERS & SUPPLIERS (PRD Section 7.2, 7.3, 7.6, 11)
CREATE TABLE customer_types (
    code TEXT PRIMARY KEY NOT NULL
);
INSERT INTO customer_types (code) VALUES ('RETAIL'), ('TECHNICIAN');

CREATE TABLE customers (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    name TEXT NOT NULL,
    phone TEXT,
    customer_type TEXT NOT NULL REFERENCES customer_types(code) DEFAULT 'RETAIL',
    address TEXT,
    credit_limit_paise INTEGER DEFAULT NULL,
    notes TEXT,
    created_at TEXT NOT NULL DEFAULT (datetime('now', '+05:30'))
);
CREATE INDEX idx_customers_phone ON customers(phone);
CREATE INDEX idx_customers_type ON customers(customer_type);

CREATE TABLE suppliers (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    name TEXT NOT NULL,
    phone TEXT,
    contact_person TEXT,
    address TEXT,
    gstin TEXT,
    notes TEXT,
    created_at TEXT NOT NULL DEFAULT (datetime('now', '+05:30'))
);
CREATE INDEX idx_suppliers_name ON suppliers(name);

-- 3. PRODUCTS & CATALOGUE (PRD Section 7.2, 11)
CREATE TABLE brands (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    name TEXT UNIQUE NOT NULL,
    created_at TEXT NOT NULL DEFAULT (datetime('now', '+05:30'))
);

CREATE TABLE categories (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    name TEXT UNIQUE NOT NULL,
    created_at TEXT NOT NULL DEFAULT (datetime('now', '+05:30'))
);

CREATE TABLE units (
    code TEXT PRIMARY KEY NOT NULL,
    name TEXT NOT NULL,
    allows_decimal INTEGER NOT NULL DEFAULT 0 CHECK (allows_decimal IN (0, 1))
);
INSERT INTO units (code, name, allows_decimal) VALUES 
('PIECE', 'Piece', 0),
('METRE', 'Metre', 1),
('COIL', 'Coil', 0),
('BOX', 'Box', 0);

CREATE TABLE products (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    brand_id INTEGER NOT NULL REFERENCES brands(id),
    category_id INTEGER NOT NULL REFERENCES categories(id),
    name TEXT NOT NULL,
    unit_code TEXT NOT NULL REFERENCES units(code),
    reorder_level_scaled INTEGER DEFAULT NULL,
    is_active INTEGER NOT NULL DEFAULT 1 CHECK (is_active IN (0, 1)),
    created_at TEXT NOT NULL DEFAULT (datetime('now', '+05:30')),
    UNIQUE(brand_id, category_id, name)
);
CREATE INDEX idx_products_brand ON products(brand_id);
CREATE INDEX idx_products_category ON products(category_id);

CREATE TABLE product_variants (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    product_id INTEGER NOT NULL REFERENCES products(id),
    variant_name TEXT NOT NULL,
    mrp_paise INTEGER NOT NULL CHECK (mrp_paise >= 0),
    selling_price_paise INTEGER NOT NULL CHECK (selling_price_paise >= 0),
    barcode TEXT UNIQUE,
    is_active INTEGER NOT NULL DEFAULT 1 CHECK (is_active IN (0, 1)),
    created_at TEXT NOT NULL DEFAULT (datetime('now', '+05:30'))
);
CREATE INDEX idx_variants_barcode ON product_variants(barcode);
CREATE INDEX idx_variants_product ON product_variants(product_id);

-- 4. BILL & CREDIT NOTE NUMBER COUNTERS (PRD Section 6, 7.3, 7.5, 11)
CREATE TABLE bill_counters (
    financial_year TEXT PRIMARY KEY NOT NULL,
    prefix TEXT NOT NULL,
    last_bill_number INTEGER NOT NULL DEFAULT 0,
    updated_at TEXT NOT NULL DEFAULT (datetime('now', '+05:30'))
);

CREATE TABLE credit_note_counters (
    financial_year TEXT PRIMARY KEY NOT NULL,
    prefix TEXT NOT NULL,
    last_note_number INTEGER NOT NULL DEFAULT 0,
    updated_at TEXT NOT NULL DEFAULT (datetime('now', '+05:30'))
);

-- 5. SALES & SALE ITEMS (PRD Section 6, 7.3, 11)
CREATE TABLE sale_modes (
    code TEXT PRIMARY KEY NOT NULL
);
INSERT INTO sale_modes (code) VALUES ('BILL'), ('REGISTERED');

CREATE TABLE sales (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    sale_mode TEXT NOT NULL REFERENCES sale_modes(code),
    financial_year TEXT NOT NULL,
    bill_number TEXT UNIQUE,
    bill_sequence INTEGER,
    customer_id INTEGER REFERENCES customers(id),
    sale_date TEXT NOT NULL,
    created_at TEXT NOT NULL DEFAULT (datetime('now', '+05:30')),
    subtotal_paise INTEGER NOT NULL CHECK (subtotal_paise >= 0),
    discount_paise INTEGER NOT NULL DEFAULT 0 CHECK (discount_paise >= 0),
    total_amount_paise INTEGER NOT NULL CHECK (total_amount_paise >= 0),
    created_by_user_id INTEGER NOT NULL REFERENCES users(id)
);
CREATE INDEX idx_sales_date ON sales(sale_date);
CREATE INDEX idx_sales_fy ON sales(financial_year);
CREATE INDEX idx_sales_customer ON sales(customer_id);
CREATE INDEX idx_sales_bill_number ON sales(bill_number);

CREATE TABLE sale_items (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    sale_id INTEGER NOT NULL REFERENCES sales(id),
    variant_id INTEGER NOT NULL REFERENCES product_variants(id),
    quantity_scaled INTEGER NOT NULL CHECK (quantity_scaled > 0),
    unit_price_paise INTEGER NOT NULL CHECK (unit_price_paise >= 0),
    line_discount_paise INTEGER NOT NULL DEFAULT 0 CHECK (line_discount_paise >= 0),
    line_total_paise INTEGER NOT NULL CHECK (line_total_paise >= 0)
);
CREATE INDEX idx_sale_items_sale ON sale_items(sale_id);
CREATE INDEX idx_sale_items_variant ON sale_items(variant_id);

-- 6. PAYMENTS (PRD Section 7.3, 11)
CREATE TABLE payment_modes (
    code TEXT PRIMARY KEY NOT NULL
);
INSERT INTO payment_modes (code) VALUES ('CASH'), ('UPI'), ('CARD'), ('CREDIT');

CREATE TABLE sale_payments (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    sale_id INTEGER NOT NULL REFERENCES sales(id),
    payment_mode TEXT NOT NULL REFERENCES payment_modes(code),
    amount_paise INTEGER NOT NULL CHECK (amount_paise > 0),
    reference_number TEXT,
    created_at TEXT NOT NULL DEFAULT (datetime('now', '+05:30'))
);
CREATE INDEX idx_sale_payments_sale ON sale_payments(sale_id);
CREATE INDEX idx_sale_payments_mode ON sale_payments(payment_mode);

-- 7. STOCK MOVEMENTS & PURCHASES (PRD Section 7.4, 11 - Append-Only)
CREATE TABLE stock_movement_types (
    code TEXT PRIMARY KEY NOT NULL
);
INSERT INTO stock_movement_types (code) VALUES 
('PURCHASE'), ('SALE'), ('RETURN_IN'), ('RETURN_OUT'), ('DAMAGE'), ('ADJUSTMENT');

CREATE TABLE stock_movements (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    variant_id INTEGER NOT NULL REFERENCES product_variants(id),
    movement_type TEXT NOT NULL REFERENCES stock_movement_types(code),
    quantity_scaled INTEGER NOT NULL,
    reference_type TEXT NOT NULL,
    reference_id INTEGER NOT NULL,
    reason TEXT,
    created_at TEXT NOT NULL DEFAULT (datetime('now', '+05:30')),
    created_by_user_id INTEGER NOT NULL REFERENCES users(id)
);
CREATE INDEX idx_stock_movements_variant ON stock_movements(variant_id);
CREATE INDEX idx_stock_movements_ref ON stock_movements(reference_type, reference_id);

CREATE TABLE purchases (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    supplier_id INTEGER NOT NULL REFERENCES suppliers(id),
    invoice_number TEXT NOT NULL,
    invoice_date TEXT NOT NULL,
    total_amount_paise INTEGER NOT NULL CHECK (total_amount_paise >= 0),
    paid_amount_paise INTEGER NOT NULL DEFAULT 0 CHECK (paid_amount_paise >= 0),
    credit_amount_paise INTEGER NOT NULL DEFAULT 0 CHECK (credit_amount_paise >= 0),
    notes TEXT,
    created_at TEXT NOT NULL DEFAULT (datetime('now', '+05:30')),
    created_by_user_id INTEGER NOT NULL REFERENCES users(id)
);
CREATE INDEX idx_purchases_supplier ON purchases(supplier_id);
CREATE INDEX idx_purchases_date ON purchases(invoice_date);

CREATE TABLE purchase_items (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    purchase_id INTEGER NOT NULL REFERENCES purchases(id),
    variant_id INTEGER NOT NULL REFERENCES product_variants(id),
    quantity_scaled INTEGER NOT NULL CHECK (quantity_scaled > 0),
    cost_price_paise INTEGER NOT NULL CHECK (cost_price_paise >= 0),
    line_total_paise INTEGER NOT NULL CHECK (line_total_paise >= 0)
);
CREATE INDEX idx_purchase_items_purchase ON purchase_items(purchase_id);
CREATE INDEX idx_purchase_items_variant ON purchase_items(variant_id);

-- 8. RETURNS & CREDIT NOTES (PRD Section 7.5, 11)
CREATE TABLE returns (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    sale_id INTEGER NOT NULL REFERENCES sales(id),
    credit_note_number TEXT UNIQUE,
    financial_year TEXT NOT NULL,
    return_date TEXT NOT NULL,
    refund_mode TEXT NOT NULL REFERENCES payment_modes(code),
    total_refund_paise INTEGER NOT NULL CHECK (total_refund_paise >= 0),
    notes TEXT,
    created_at TEXT NOT NULL DEFAULT (datetime('now', '+05:30')),
    created_by_user_id INTEGER NOT NULL REFERENCES users(id)
);
CREATE INDEX idx_returns_sale ON returns(sale_id);
CREATE INDEX idx_returns_date ON returns(return_date);

CREATE TABLE return_items (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    return_id INTEGER NOT NULL REFERENCES returns(id),
    sale_item_id INTEGER NOT NULL REFERENCES sale_items(id),
    variant_id INTEGER NOT NULL REFERENCES product_variants(id),
    quantity_scaled INTEGER NOT NULL CHECK (quantity_scaled > 0),
    condition TEXT NOT NULL CHECK (condition IN ('SELLABLE', 'DAMAGED')),
    refund_paise INTEGER NOT NULL CHECK (refund_paise >= 0),
    reason TEXT NOT NULL
);
CREATE INDEX idx_return_items_return ON return_items(return_id);

-- 9. CUSTOMER & SUPPLIER LEDGERS (PRD Section 7.6, 11 - Append-Only)
CREATE TABLE customer_ledger_types (
    code TEXT PRIMARY KEY NOT NULL
);
INSERT INTO customer_ledger_types (code) VALUES 
('CREDIT_SALE'), ('PAYMENT_RECEIVED'), ('RETURN_REFUND'), ('ADJUSTMENT');

CREATE TABLE customer_ledger_entries (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    customer_id INTEGER NOT NULL REFERENCES customers(id),
    entry_type TEXT NOT NULL REFERENCES customer_ledger_types(code),
    amount_paise INTEGER NOT NULL,
    reference_type TEXT NOT NULL,
    reference_id INTEGER NOT NULL,
    entry_date TEXT NOT NULL,
    notes TEXT,
    created_at TEXT NOT NULL DEFAULT (datetime('now', '+05:30')),
    created_by_user_id INTEGER NOT NULL REFERENCES users(id)
);
CREATE INDEX idx_cust_ledger_customer ON customer_ledger_entries(customer_id);
CREATE INDEX idx_cust_ledger_date ON customer_ledger_entries(entry_date);

CREATE TABLE customer_payment_allocations (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    payment_ledger_entry_id INTEGER NOT NULL REFERENCES customer_ledger_entries(id),
    sale_id INTEGER NOT NULL REFERENCES sales(id),
    allocated_amount_paise INTEGER NOT NULL CHECK (allocated_amount_paise > 0),
    created_at TEXT NOT NULL DEFAULT (datetime('now', '+05:30'))
);

CREATE TABLE supplier_ledger_types (
    code TEXT PRIMARY KEY NOT NULL
);
INSERT INTO supplier_ledger_types (code) VALUES 
('PURCHASE_CREDIT'), ('PAYMENT_MADE'), ('RETURN_OUT'), ('ADJUSTMENT');

CREATE TABLE supplier_ledger_entries (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    supplier_id INTEGER NOT NULL REFERENCES suppliers(id),
    entry_type TEXT NOT NULL REFERENCES supplier_ledger_types(code),
    amount_paise INTEGER NOT NULL,
    reference_type TEXT NOT NULL,
    reference_id INTEGER NOT NULL,
    entry_date TEXT NOT NULL,
    notes TEXT,
    created_at TEXT NOT NULL DEFAULT (datetime('now', '+05:30')),
    created_by_user_id INTEGER NOT NULL REFERENCES users(id)
);
CREATE INDEX idx_supp_ledger_supplier ON supplier_ledger_entries(supplier_id);
CREATE INDEX idx_supp_ledger_date ON supplier_ledger_entries(entry_date);

-- 10. EXPENSES & DAY-END CLOSING (PRD Section 7.7, 7.9, 11)
CREATE TABLE expense_categories (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    name TEXT UNIQUE NOT NULL
);
INSERT INTO expense_categories (name) VALUES 
('TEA_SNACKS'), ('ELECTRICITY'), ('RENT'), ('TRANSPORT'), ('SALARY_ADVANCE'), ('MISCELLANEOUS');

CREATE TABLE expenses (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    category_id INTEGER NOT NULL REFERENCES expense_categories(id),
    expense_date TEXT NOT NULL,
    amount_paise INTEGER NOT NULL CHECK (amount_paise > 0),
    payment_mode TEXT NOT NULL REFERENCES payment_modes(code),
    note TEXT,
    created_at TEXT NOT NULL DEFAULT (datetime('now', '+05:30')),
    created_by_user_id INTEGER NOT NULL REFERENCES users(id)
);
CREATE INDEX idx_expenses_date ON expenses(expense_date);

CREATE TABLE day_end_closings (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    closing_date TEXT UNIQUE NOT NULL,
    expected_cash_paise INTEGER NOT NULL,
    counted_cash_paise INTEGER NOT NULL,
    difference_paise INTEGER NOT NULL,
    notes TEXT,
    closed_at TEXT NOT NULL DEFAULT (datetime('now', '+05:30')),
    closed_by_user_id INTEGER NOT NULL REFERENCES users(id)
);

-- 11. AUDIT LOG, SETTINGS & BACKUP LOG (PRD Section 7.10, 7.11, 11)
CREATE TABLE audit_log (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    user_id INTEGER NOT NULL REFERENCES users(id),
    action TEXT NOT NULL,
    entity_name TEXT NOT NULL,
    entity_id TEXT,
    old_value TEXT,
    new_value TEXT,
    reason TEXT,
    created_at TEXT NOT NULL DEFAULT (datetime('now', '+05:30'))
);
CREATE INDEX idx_audit_created ON audit_log(created_at);
CREATE INDEX idx_audit_action ON audit_log(action);

CREATE TABLE settings (
    key TEXT PRIMARY KEY NOT NULL,
    value TEXT NOT NULL,
    description TEXT,
    updated_at TEXT NOT NULL DEFAULT (datetime('now', '+05:30')),
    updated_by_user_id INTEGER REFERENCES users(id)
);

INSERT INTO settings (key, value, description) VALUES
('ALLOW_NEGATIVE_STOCK', 'false', 'Whether stock can be sold below zero'),
('STAFF_MAX_DISCOUNT_PERCENT', '5.00', 'Maximum discount percent counter staff can give'),
('STAFF_CAN_REGISTER_SALE', 'true', 'Whether staff can register unbilled sales'),
('STAFF_RETURN_APPROVAL_LIMIT_PAISE', '50000', 'Returns above this amount (paise) require owner approval'),
('BACKUP_EXTERNAL_DIRECTORY', '', 'Configurable backup folder path');

CREATE TABLE backup_log (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    backup_file_path TEXT NOT NULL,
    backup_type TEXT NOT NULL CHECK (backup_type IN ('SCHEDULED', 'DAY_END', 'PRE_MIGRATION', 'MANUAL')),
    file_size_bytes INTEGER NOT NULL,
    status TEXT NOT NULL CHECK (status IN ('SUCCESS', 'FAILED')),
    error_message TEXT,
    created_at TEXT NOT NULL DEFAULT (datetime('now', '+05:30'))
);
