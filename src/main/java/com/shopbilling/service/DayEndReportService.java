package com.shopbilling.service;

import com.lowagie.text.*;
import com.lowagie.text.pdf.PdfPCell;
import com.lowagie.text.pdf.PdfPTable;
import com.lowagie.text.pdf.PdfWriter;
import com.shopbilling.db.DayEndClosingRepository;
import com.shopbilling.db.ExpenseRepository;
import com.shopbilling.db.SettingsRepository;
import com.shopbilling.db.TransactionHelper;
import com.shopbilling.model.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.awt.Color;
import java.io.ByteArrayOutputStream;
import java.io.OutputStream;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Service for generating comprehensive Day-End cash reconciliation audit reports,
 * A4 closing sheets, and historical CSV exports (PRD Section 7.9, FR-38 - FR-40, Section 13).
 * Pure service: strictly contains NO JavaFX imports.
 */
public class DayEndReportService {

    private static final Logger logger = LoggerFactory.getLogger(DayEndReportService.class);

    private static final Font TITLE_FONT = FontFactory.getFont(FontFactory.HELVETICA_BOLD, 15, new Color(15, 23, 42));
    private static final Font SUBTITLE_FONT = FontFactory.getFont(FontFactory.HELVETICA, 8.5f, new Color(71, 85, 105));
    private static final Font REPORT_TITLE_FONT = FontFactory.getFont(FontFactory.HELVETICA_BOLD, 13, new Color(30, 41, 59));
    private static final Font SECTION_HEADER_FONT = FontFactory.getFont(FontFactory.HELVETICA_BOLD, 9, new Color(15, 23, 42));
    private static final Font TABLE_HEADER_FONT = FontFactory.getFont(FontFactory.HELVETICA_BOLD, 8.5f, Color.WHITE);
    private static final Font BOLD_FONT = FontFactory.getFont(FontFactory.HELVETICA_BOLD, 8.5f, new Color(15, 23, 42));
    private static final Font BODY_FONT = FontFactory.getFont(FontFactory.HELVETICA, 8.5f, new Color(30, 41, 59));
    private static final Font SMALL_FONT = FontFactory.getFont(FontFactory.HELVETICA, 7.5f, new Color(100, 116, 139));
    private static final Font TOTAL_FONT = FontFactory.getFont(FontFactory.HELVETICA_BOLD, 11, new Color(15, 23, 42));

    private static final Color HEADER_BG = new Color(30, 41, 59);
    private static final Color BORDER_COLOR = new Color(226, 232, 240);
    private static final Color LIGHT_BG = new Color(248, 250, 252);

    private final TransactionHelper transactionHelper;
    private final DayEndClosingRepository dayEndClosingRepository;
    private final ExpenseRepository expenseRepository;
    private final SettingsRepository settingsRepository;
    private final IstClock clock;

    private record ShopHeader(String name, String address, String phone, String gstin) {}

    public DayEndReportService(TransactionHelper transactionHelper,
                              DayEndClosingRepository dayEndClosingRepository,
                              ExpenseRepository expenseRepository,
                              SettingsRepository settingsRepository,
                              IstClock clock) {
        this.transactionHelper = Objects.requireNonNull(transactionHelper, "TransactionHelper cannot be null");
        this.dayEndClosingRepository = Objects.requireNonNull(dayEndClosingRepository, "DayEndClosingRepository cannot be null");
        this.expenseRepository = Objects.requireNonNull(expenseRepository, "ExpenseRepository cannot be null");
        this.settingsRepository = Objects.requireNonNull(settingsRepository, "SettingsRepository cannot be null");
        this.clock = Objects.requireNonNull(clock, "IstClock cannot be null");
    }

    /**
     * Aggregates the full Day-End Cash Reconciliation Report for a target date (FR-38, FR-39).
     */
    public DayEndReportModel getDayEndReport(LocalDate date) {
        final LocalDate targetDate = date != null ? date : clock.today();

        return transactionHelper.executeRead(conn -> {
            Optional<DayEndClosingRecord> closingOpt = dayEndClosingRepository.findByDate(conn, targetDate);

            long cashSales = dayEndClosingRepository.getCashSalesForDate(conn, targetDate);
            long cashRefunds = dayEndClosingRepository.getCashRefundsForDate(conn, targetDate);
            long cashCustPayments = dayEndClosingRepository.getCashCustomerPaymentsForDate(conn, targetDate);
            long cashExpenses = expenseRepository.getTotalCashExpensesForDate(conn, targetDate);

            DayEndCashSummary cashSummary = DayEndCashSummary.of(
                    targetDate,
                    Money.ofPaise(cashSales),
                    Money.ofPaise(cashRefunds),
                    Money.ofPaise(cashCustPayments),
                    Money.ofPaise(cashExpenses)
            );

            List<ExpenseRecord> expenses = expenseRepository.getExpensesByDate(conn, targetDate);

            return new DayEndReportModel(
                    targetDate,
                    closingOpt.isPresent(),
                    closingOpt,
                    cashSummary,
                    expenses
            );
        });
    }

    /**
     * Renders an A4 Day-End Closing Cash Reconciliation Sheet as an in-memory PDF.
     */
    public byte[] renderDayEndPdf(LocalDate date) {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        writeDayEndPdf(date, baos);
        return baos.toByteArray();
    }

    /**
     * Writes an A4 Day-End Closing Cash Reconciliation Sheet PDF to an output stream.
     */
    public void writeDayEndPdf(LocalDate date, OutputStream out) {
        Objects.requireNonNull(out, "OutputStream cannot be null");

        DayEndReportModel report = getDayEndReport(date);
        ShopHeader shop = transactionHelper.executeRead(this::loadShopHeader);

        Document document = new Document(PageSize.A4, 36, 36, 36, 36);
        try {
            PdfWriter.getInstance(document, out);
            document.open();

            // 1. Header (Shop info + Day-End title)
            document.add(createHeaderTable(shop, report));
            document.add(new Paragraph(" "));

            // 2. Cash Drawer Reconciliation Table
            document.add(createCashReconciliationTable(report));
            document.add(new Paragraph(" "));

            // 3. Itemized Expenses for the Day
            document.add(createExpensesTable(report.expenses()));
            document.add(new Paragraph(" "));

            // 4. Owner Closing Notes & Sign-off Block
            document.add(createClosingAuditFooter(report, shop));

            document.close();
            logger.info("Successfully rendered Day-End Closing PDF for date: {}", report.date());
        } catch (DocumentException e) {
            throw new RuntimeException("Failed to generate Day-End Closing PDF: " + e.getMessage(), e);
        }
    }

    /**
     * Exports historical daily closing records between two dates as CSV (PRD Section 7.8, Section 13).
     */
    public String exportClosingsCsv(LocalDate fromDate, LocalDate toDate) {
        return transactionHelper.executeRead(conn -> {
            List<DayEndClosingRecord> closings = dayEndClosingRepository.getAllClosings(conn);
            StringBuilder sb = new StringBuilder();
            sb.append("Date,Expected Cash (INR),Counted Cash (INR),Difference (INR),Closed By User ID,Closed At,Notes\n");

            for (DayEndClosingRecord r : closings) {
                if (fromDate != null && r.closingDate().isBefore(fromDate)) continue;
                if (toDate != null && r.closingDate().isAfter(toDate)) continue;

                sb.append(r.closingDate()).append(",")
                  .append(r.expectedCash().toRupeesString()).append(",")
                  .append(r.countedCash().toRupeesString()).append(",")
                  .append(r.difference().toRupeesString()).append(",")
                  .append(r.closedByUserId()).append(",")
                  .append(r.closedAt() != null ? r.closedAt() : "").append(",")
                  .append(escapeCsv(r.notes())).append("\n");
            }
            return sb.toString();
        });
    }

    private PdfPTable createHeaderTable(ShopHeader shop, DayEndReportModel report) {
        PdfPTable table = new PdfPTable(2);
        table.setWidthPercentage(100);

        PdfPCell shopCell = new PdfPCell();
        shopCell.setBorder(Rectangle.NO_BORDER);
        shopCell.addElement(new Paragraph(shop.name(), TITLE_FONT));
        if (shop.address() != null && !shop.address().isBlank()) {
            shopCell.addElement(new Paragraph(shop.address(), SUBTITLE_FONT));
        }
        table.addCell(shopCell);

        PdfPCell titleCell = new PdfPCell();
        titleCell.setBorder(Rectangle.NO_BORDER);
        titleCell.setHorizontalAlignment(Element.ALIGN_RIGHT);

        Paragraph pTitle = new Paragraph("DAY-END CASH CLOSING SHEET", REPORT_TITLE_FONT);
        pTitle.setAlignment(Element.ALIGN_RIGHT);
        titleCell.addElement(pTitle);

        DateTimeFormatter dtf = DateTimeFormatter.ofPattern("dd-MMM-yyyy");
        Paragraph pDate = new Paragraph("Business Date: " + report.date().format(dtf), BOLD_FONT);
        pDate.setAlignment(Element.ALIGN_RIGHT);
        titleCell.addElement(pDate);

        String statusStr = report.isClosed() ? "STATUS: CLOSED" : "STATUS: PENDING CLOSING";
        Paragraph pStatus = new Paragraph(statusStr, report.isClosed() ? BOLD_FONT : SMALL_FONT);
        pStatus.setAlignment(Element.ALIGN_RIGHT);
        titleCell.addElement(pStatus);

        table.addCell(titleCell);
        return table;
    }

    private PdfPTable createCashReconciliationTable(DayEndReportModel report) {
        PdfPTable table = new PdfPTable(2);
        table.setWidthPercentage(100);
        table.setWidths(new float[]{70, 30});

        addHeaderCell(table, "Cash Component Description", Element.ALIGN_LEFT);
        addHeaderCell(table, "Amount (₹)", Element.ALIGN_RIGHT);

        DayEndCashSummary c = report.cashSummary();
        addRow(table, "(+) Cash Sales Collected", c.cashSales().toRupeesString(), BODY_FONT, Element.ALIGN_RIGHT);
        addRow(table, "(-) Cash Refunds Paid Out", "- " + c.cashRefunds().toRupeesString(), BODY_FONT, Element.ALIGN_RIGHT);
        addRow(table, "(+) Customer Credit Payments Received in Cash", c.cashCustomerPayments().toRupeesString(), BODY_FONT, Element.ALIGN_RIGHT);
        addRow(table, "(-) Cash Expenses Paid", "- " + c.cashExpenses().toRupeesString(), BODY_FONT, Element.ALIGN_RIGHT);

        // Expected Cash
        addRow(table, "(=) Net Expected Cash in Drawer", "₹ " + c.expectedCash().toRupeesString(), TOTAL_FONT, Element.ALIGN_RIGHT);

        if (report.isClosed() && report.closingRecord().isPresent()) {
            DayEndClosingRecord r = report.closingRecord().get();
            addRow(table, "Actual Counted Cash (Entered by Owner)", "₹ " + r.countedCash().toRupeesString(), TOTAL_FONT, Element.ALIGN_RIGHT);

            Money diff = r.difference();
            String diffLabel = diff.isNegative()
                    ? "Cash Shortage (Difference)"
                    : (diff.isPositive() ? "Cash Surplus (Difference)" : "Cash Balanced (Difference)");
            Font diffFont = diff.isNegative()
                    ? FontFactory.getFont(FontFactory.HELVETICA_BOLD, 10, new Color(225, 29, 72))
                    : FontFactory.getFont(FontFactory.HELVETICA_BOLD, 10, new Color(16, 185, 129));

            addRow(table, diffLabel, "₹ " + diff.toRupeesString(), diffFont, Element.ALIGN_RIGHT);
        }

        return table;
    }

    private PdfPTable createExpensesTable(List<ExpenseRecord> expenses) {
        PdfPTable table = new PdfPTable(new float[]{2.5f, 2.0f, 5.5f, 2.5f});
        table.setWidthPercentage(100);

        addHeaderCell(table, "Expense Category", Element.ALIGN_LEFT);
        addHeaderCell(table, "Mode", Element.ALIGN_CENTER);
        addHeaderCell(table, "Note / Reason", Element.ALIGN_LEFT);
        addHeaderCell(table, "Amount (₹)", Element.ALIGN_RIGHT);

        if (expenses.isEmpty()) {
            PdfPCell empty = new PdfPCell(new Phrase("No expenses recorded for this business day.", SMALL_FONT));
            empty.setColspan(4);
            empty.setPadding(6);
            empty.setBorderColor(BORDER_COLOR);
            table.addCell(empty);
        } else {
            for (ExpenseRecord e : expenses) {
                addCell(table, e.categoryName(), Color.WHITE, BODY_FONT, Element.ALIGN_LEFT);
                addCell(table, e.paymentMode().name(), Color.WHITE, SMALL_FONT, Element.ALIGN_CENTER);
                addCell(table, e.note() != null ? e.note() : "-", Color.WHITE, SMALL_FONT, Element.ALIGN_LEFT);
                addCell(table, e.amount().toRupeesString(), Color.WHITE, BODY_FONT, Element.ALIGN_RIGHT);
            }
        }
        return table;
    }

    private PdfPTable createClosingAuditFooter(DayEndReportModel report, ShopHeader shop) {
        PdfPTable table = new PdfPTable(2);
        table.setWidthPercentage(100);

        PdfPCell left = new PdfPCell();
        left.setBorder(Rectangle.NO_BORDER);
        if (report.isClosed() && report.closingRecord().isPresent()) {
            DayEndClosingRecord r = report.closingRecord().get();
            left.addElement(new Paragraph("Closing Notes:", SECTION_HEADER_FONT));
            left.addElement(new Paragraph(r.notes() != null && !r.notes().isBlank() ? r.notes() : "None provided", BODY_FONT));
            left.addElement(new Paragraph("Closed on: " + r.closedAt() + " by User ID #" + r.closedByUserId(), SMALL_FONT));
        } else {
            left.addElement(new Paragraph("Day closing is pending owner sign-off and cash verification.", SMALL_FONT));
        }
        table.addCell(left);

        PdfPCell right = new PdfPCell();
        right.setBorder(Rectangle.NO_BORDER);
        right.setHorizontalAlignment(Element.ALIGN_RIGHT);
        Paragraph p = new Paragraph("For " + shop.name() + "\n\n\nShop Owner Sign-off", BOLD_FONT);
        p.setAlignment(Element.ALIGN_RIGHT);
        right.addElement(p);
        table.addCell(right);

        return table;
    }

    private void addHeaderCell(PdfPTable table, String text, int align) {
        PdfPCell cell = new PdfPCell(new Phrase(text, TABLE_HEADER_FONT));
        cell.setBackgroundColor(HEADER_BG);
        cell.setBorderColor(BORDER_COLOR);
        cell.setHorizontalAlignment(align);
        cell.setPadding(5);
        table.addCell(cell);
    }

    private void addRow(PdfPTable table, String label, String value, Font font, int valueAlign) {
        PdfPCell l = new PdfPCell(new Phrase(label, font));
        l.setBorderColor(BORDER_COLOR);
        l.setPadding(5);
        table.addCell(l);

        PdfPCell v = new PdfPCell(new Phrase(value, font));
        v.setBorderColor(BORDER_COLOR);
        v.setHorizontalAlignment(valueAlign);
        v.setPadding(5);
        table.addCell(v);
    }

    private void addCell(PdfPTable table, String text, Color bg, Font font, int align) {
        PdfPCell cell = new PdfPCell(new Phrase(text, font));
        cell.setBackgroundColor(bg);
        cell.setBorderColor(BORDER_COLOR);
        cell.setHorizontalAlignment(align);
        cell.setPadding(5);
        table.addCell(cell);
    }

    private ShopHeader loadShopHeader(java.sql.Connection conn) throws java.sql.SQLException {
        String name = settingsRepository.getSettingOrDefault(conn, "SHOP_NAME", "UdhyamSetu Electricals");
        String address = settingsRepository.getSettingOrDefault(conn, "SHOP_ADDRESS", "Main Road, Market");
        String phone = settingsRepository.getSettingOrDefault(conn, "SHOP_PHONE", "");
        String gstin = settingsRepository.getSettingOrDefault(conn, "SHOP_GSTIN", "");
        return new ShopHeader(name, address, phone, gstin);
    }

    private String escapeCsv(String value) {
        if (value == null) return "";
        if (value.contains(",") || value.contains("\"") || value.contains("\n")) {
            return "\"" + value.replace("\"", "\"\"") + "\"";
        }
        return value;
    }
}
