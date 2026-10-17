# Product Requirements Document: Shop Billing Desktop App

**Product:** Offline desktop billing, stock and credit software for a single electrical goods shop (India) **Version:** 0.1 (draft) **Author:** Developer (solo) **Status:** Draft for review with the shop owner and the owner's CA

## 1. Overview

A simple Windows desktop application for one electrical goods shop. The owner sells directly to retail customers and to local technicians. The app must start with a single click on a desktop icon, run fully offline on the shop PC, and keep reliable records of sales, stock, credit and expenses. It is a product built for one client, not a generic platform.

## 2. Problem statement

The shop needs to:

- Bill customers quickly at the counter, sometimes issuing a bill and sometimes only registering the sale.
- Track stock coming in from distributors and going out through sales.
- Know who has been given credit, who has paid, and what is still due.
- See sales broken down by payment mode, brand, product type and period.
- Record shop expenses.

Today these are presumably handled on paper or with tools that do not fit the shop's workflow. The shop wants to avoid recurring cloud costs and dependence on internet connectivity.

## 3. Goals

1. Fast, keyboard-first billing that works at a busy counter.
2. Accurate, tamper-resistant records of money and stock (nothing silently edited or deleted).
3. Zero-friction startup: double-click the icon and the app is ready.
4. Works fully offline, with safe automatic backups.
5. Low running cost: no mandatory cloud services.
6. Simple enough for a non-technical owner and counter staff to use after a short walkthrough.

## 4. Non-goals (v1)

- Multi-shop or multi-branch support.
- Multiple computers sharing one database over a network.
- Web or mobile versions.
- Full GST return filing, e-invoicing or e-way bill generation.
- Online payments or payment gateway integration.
- Full double-entry accounting (this is not a Tally replacement).
- Payroll.

## 5. Users and roles

| Role | Who | Can do |
| --- | --- | --- |
| Owner | Shop owner | Everything: bills, registered sales, returns, stock, credit, expenses, reports, profit, users, settings, backup and restore |
| Staff | Counter staff | Generate Bill and Register Sale (Register Sale can be restricted by a setting), view stock, record customer payments. Cannot see profit or cost prices, cannot change settings or users, cannot approve returns above a configured limit, cannot exceed a configured discount limit |

## 6. Core concept: Generate Bill vs Register Sale

Both actions create a sale using the same tables and the same stock and payment handling. Both count toward turnover.

|  | Generate Bill | Register Sale |
| --- | --- | --- |
| Counts in turnover | Yes | Yes |
| Deducts stock | Yes | Yes |
| Records payment or credit | Yes | Yes |
| Gets a bill number (counter +1) | Yes | No |
| Produces a printable bill | Yes | No |

Rules:

- Every sale has an internal sale ID, separate from the bill number.
- The bill number is assigned only when a bill is generated, from a per-financial-year counter that is incremented inside the same database transaction as the sale. Numbers are gap-free and never duplicated.
- A bill number, once issued, is immutable.
- The owner can later generate a bill for an existing registered sale. It receives the next number at that moment and is not counted twice in turnover.
- Reports can show all sales, billed only, registered only, and net sales (sales minus returns).

**Compliance note:** Whether the shop may keep sales without bills depends on its GST registration status and applicable rules. This must be confirmed with the owner's CA before go-live (see open questions). The product records every sale either way, so rules can be adjusted without losing data.

## 7. Functional requirements

### 7.1 Home screen

- FR-1: The home screen shows exactly three large buttons: **Generate Bill**, **Register Sale**, **Sales**.
- FR-2: Other functions (Stock, Credit, Expenses, Products, Settings, Backup) are reachable from a small menu, not extra home buttons.
- FR-3: The home screen shows the last successful backup time, in red if older than 24 hours.

### 7.2 Products and catalogue

- FR-4: Maintain brands (for example Havells, Veto), categories or types (switch, socket, light, wire), products and variants (rating, colour, wattage).
- FR-5: Each product has a unit (piece, metre, coil, box), MRP, selling price and an optional barcode.
- FR-6: Quantities support decimals (for example wires sold by the metre).
- FR-7: Brands and categories are separate normalised tables so reports and filters stay clean.

### 7.3 Billing and sales entry

- FR-8: Keyboard-first billing screen with quick product search and barcode scanner support (scanner acts as a keyboard).
- FR-9: Per-line quantity and discount, with a configurable discount limit for staff.
- FR-10: Customer picker with customer types: retail and technician. Technicians may have different pricing or discount rules.
- FR-11: Split payments: one sale can have several payments (for example 2000 UPI plus 500 cash). Modes: cash, UPI, card, credit.
- FR-12: Credit sales create an entry in the customer's ledger.
- FR-13: Generate Bill produces an A4 PDF bill and can print it. Thermal (58 mm or 80 mm) printing is supported through a printer interface.
- FR-14: A sale is saved in one atomic transaction. If any part fails, nothing is saved.

### 7.4 Stock

- FR-15: Stock is an append-only ledger of movements: purchase, sale, return in, return out, damage, adjustment. Current stock is derived, never typed in.
- FR-16: **Stock in:** record stock received from a distributor (supplier, date, items, cost price, supplier invoice number, amount paid versus on credit).
- FR-17: **Stock out** happens through sales. Manual adjustments require the owner and a reason, and are audit-logged.
- FR-18: Low-stock indication for products that have a reorder level set.
- FR-19: Negative stock handling is configurable (see open questions).

### 7.5 Returns

- FR-20: A return is a new entry linked to the original sale line. The original sale is never edited or deleted.
- FR-21: The user selects the sale, line, quantity and reason. The return is dated on the day it is made.
- FR-22: Returned items go back to sellable stock or to a damaged bucket.
- FR-23: Refund as cash or UPI, or credit to the customer's ledger. If the original sale was on credit, the return reduces the customer's dues.
- FR-24: A return on a billed sale generates a credit note with its own number.
- FR-25: Returns above a configured value require the owner.

### 7.6 Credit management

- FR-26: Customer ledger: dues, credit given today, payments received, partial payments, optional credit limit.
- FR-27: Payments can be allocated to specific sales.
- FR-28: Overdue and aging view (0 to 30, 31 to 60, 61 to 90, 90+ days).
- FR-29: Supplier ledger: what the shop owes distributors, payments made, and aging.
- FR-30: Balances are computed from ledger entries and are never stored as editable numbers.

### 7.7 Expenses

- FR-31: Record expenses with date, category, amount, payment mode and note.
- FR-32: Expenses never affect stock or sales figures. They feed the profit view and day-end cash.

### 7.8 Sales screen, filters and reports

- FR-33: The Sales screen supports combinable filters: payment mode, brand or company, category or type, customer type, billed versus registered, and date range.
- FR-34: Period views: daily, monthly, quarterly and yearly, using the Indian financial year (1 April to 31 March). Quarters: Q1 Apr to Jun, Q2 Jul to Sep, Q3 Oct to Dec, Q4 Jan to Mar.
- FR-35: Totals show gross sales, returns and net sales.
- FR-36: Export filtered results to CSV.
- FR-37: Owner-only profit view using weighted average purchase cost, minus expenses. The costing method is documented in the app.

### 7.9 Day-end closing

- FR-38: Expected cash = cash sales minus cash refunds plus cash payments received minus cash expenses, per day.
- FR-39: The owner enters counted cash. The difference is stored and shown.
- FR-40: Closing a day triggers a backup.

### 7.10 Users, security and audit

- FR-41: First launch creates the owner account. Login required afterward.
- FR-42: Passwords are hashed with BCrypt.
- FR-43: Role-based permissions as described in section 5.
- FR-44: Append-only audit log: who, what, when, and old and new values for price overrides, discounts, returns, stock adjustments, logins, backups and settings changes.
- FR-45: No screen offers deleting or editing a sale, bill, stock movement or ledger entry.

### 7.11 Backup, restore and diagnostics

- FR-46: Automatic backups using SQLite's backup mechanism (never a raw copy of the live file), at day-end and on a schedule, to a configurable local or external drive folder.
- FR-47: Optional encrypted upload of backups to cloud storage (for example S3), queued with retries so a missing internet connection never blocks billing. Planned after the core release.
- FR-48: Owner-only restore function, verified by a backup, restore and data verification test.
- FR-49: An automatic backup before any database migration on upgrade.
- FR-50: Rotating log files and an Export Diagnostics button that zips logs and version information.

## 8. Business rules summary

1. Money is stored as integer paise or exact decimals with two places, never floating point.
2. Timestamps are stored in IST (Asia/Kolkata). Reporting boundaries use IST.
3. A sale is one atomic transaction covering the sale, items, payments, stock movements, ledger entries and the bill number (if any).
4. Sales, bills, stock movements and ledger entries are never deleted or edited. Corrections are new linked entries.
5. Reports use the Indian financial year.
6. All database queries are parameterised.

## 9. Non-functional requirements

| Area | Requirement |
| --- | --- |
| Platform | Windows 10 and 11, 64-bit. Target PC: 4 GB RAM, older CPU, possibly a hard disk |
| Startup | App ready in a few seconds, with a loading screen so the user sees it has started |
| Performance | Adding an item to a bill and saving a sale feel instant. Reports over a few years of data load in a couple of seconds |
| Offline | Everything works with no internet |
| Reliability | Survives abrupt power loss without corrupting data (SQLite WAL mode, transactions). A UPS is recommended to the client |
| Security | No open network ports. Database in a protected data folder. Role-based access |
| Usability | Keyboard-first billing, large buttons on the home screen, plain-language error messages, optional Hindi labels later |
| Installability | One installer with a bundled Java runtime and a desktop icon. No separate database or Java install |
| Maintainability | Schema changes only through versioned migrations. Business logic separated from UI code |

## 10. Technical approach (summary)

- Java 21 with JavaFX for the UI.
- SQLite (WAL mode, foreign keys on) in a single database file, for example in C:\\ProgramData\\ShopBilling\\data.
- Plain JDBC with hand-written SQL, and Flyway for migrations. No Spring Boot, no Hibernate, no Docker, no web server.
- OpenPDF for A4 bills, an ESC/POS library for thermal printers.
- Packaged with jpackage into one Windows installer.
- Layers: ui, service (no UI imports), db, model. Database work runs off the UI thread.
- If multiple counters on a network are ever needed, the service layer can be moved behind a server and the database moved to PostgreSQL.

## 11. Data model (high level)

Products and catalogue: brands, categories, products, variants, units. Parties: customers (with type), suppliers. Sales: sales (internal ID, mode BILL or REGISTERED, nullable bill number, date, customer), sale items, payments. Billing: bill counters (per financial year), credit note counters. Returns: returns, return items, linked to sale lines. Stock: stock movements (signed quantity, type, reference), purchases and purchase items. Credit: customer ledger entries, supplier ledger entries, payment allocations. Other: expenses, day-end closings, users, roles, audit log, settings, backup log.

## 12. Release plan

| Phase | Scope |
| --- | --- |
| 1 | Project setup, schema and migrations, database layer, money and financial-year helpers |
| 2 | Sale service (Generate Bill, Register Sale, bill counter) with tests |
| 3 | Products, stock in, stock ledger |
| 4 | Home screen, billing screen, A4 bill PDF |
| 5 | Returns and credit notes |
| 6 | Credit ledgers and aging |
| 7 | Expenses and day-end closing |
| 8 | Filters, reports, CSV export, profit view |
| 9 | Login, roles, audit log |
| 10 | Backup and restore, thermal printing, installer |

Before full reliance, the shop runs the new app in parallel with its existing method for about a month.

## 13. Success metrics

- A cash sale can be billed in under 30 seconds with barcode scanning.
- Stock levels in the app match a physical count after the first month, within an agreed tolerance.
- Daily cash difference at closing is explained and trending to zero.
- Zero data loss incidents, and a restore test passes on a clean machine.
- The owner can start the app, bill, and run a monthly report without help after training.

## 14. Risks and mitigations

| Risk | Mitigation |
| --- | --- |
| Shop PC is old or underpowered | Use a light stack, test on the weakest available PC early |
| Power cuts corrupt data | SQLite WAL and transactions, UPS recommended, frequent backups |
| Disk failure or theft loses all data | Automatic backups to an external drive and encrypted cloud copy, tested restores |
| Staff misuse (price overrides, hidden sales, cash theft) | Roles, discount limits, no deletes or edits, audit log, day-end closing |
| Tax compliance risk from unbilled sales | Record all sales, confirm rules with a CA before go-live, no hiding of unbilled sales from totals |
| Thermal printer incompatibility | Test with the actual printer model early |
| Installer blocked by antivirus or SmartScreen | Consider a code-signing certificate, test on clean machines |
| Solo developer is the single support point | Diagnostics export, written support agreement, remote support tool |

## 15. Open questions

1. Is the shop GST-registered, and under which scheme? What does the CA say about unbilled (registered) sales and sales to technicians?
2. Should negative stock be allowed (selling before stock-in is entered), or blocked?
3. How are technician prices set: a fixed discount, a separate price list, or per-customer?
4. Which printers does the shop have (thermal size and model, A4 printer)?
5. Are barcode scanners already in use, and do products have barcodes?
6. Does the shop need warranty or serial number tracking for any items?
7. Should bill numbering restart each financial year, and what format is preferred?
8. Does the owner want Hindi labels or Hindi text on bills?
9. Where will backups go (external drive model, cloud account owner)?
10. What are the support terms, data ownership terms and update process in the written agreement with the client?

## 16. Acceptance criteria for v1

- All functional requirements in section 7 pass tests, apart from items marked for later release.
- Generating a bill increments the counter and registering a sale does not, verified under concurrent saves and forced failures.
- Stock, credit and cash figures reconcile in test scenarios including partial returns and split payments.
- No UI path can delete or edit a sale, bill, stock movement or ledger entry.
- Backup and restore are verified on a clean Windows machine.
- The installer runs on a clean Windows machine and the app starts from the desktop icon without extra installs.

*This document is a product and engineering draft, not legal or tax advice. Confirm GST and record-keeping obligations with a qualified CA.*
