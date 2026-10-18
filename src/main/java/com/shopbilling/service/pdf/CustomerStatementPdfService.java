package com.shopbilling.service.pdf;

import com.lowagie.text.*;
import com.lowagie.text.pdf.PdfPCell;
import com.lowagie.text.pdf.PdfPTable;
import com.lowagie.text.pdf.PdfWriter;
import com.shopbilling.db.SettingsRepository;
import com.shopbilling.db.TransactionHelper;
import com.shopbilling.model.*;
import com.shopbilling.service.CreditLedgerService;
import com.shopbilling.service.IstClock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.awt.Color;
import java.io.ByteArrayOutputStream;
import java.io.OutputStream;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Objects;

/**
 * Service generating publication-quality A4 PDF customer ledger statements (Khata Statements)
 * with 4-tier FIFO aging analysis and running balances (PRD Section 7.6, FR-26, FR-28, FR-30, Section 10).
 * Pure service: strictly contains NO JavaFX imports.
 */
public class CustomerStatementPdfService {

    private static final Logger logger = LoggerFactory.getLogger(CustomerStatementPdfService.class);

    private static final Font TITLE_FONT = FontFactory.getFont(FontFactory.HELVETICA_BOLD, 15, new Color(15, 23, 42));
    private static final Font SUBTITLE_FONT = FontFactory.getFont(FontFactory.HELVETICA, 8.5f, new Color(71, 85, 105));
    private static final Font STATEMENT_TITLE_FONT = FontFactory.getFont(FontFactory.HELVETICA_BOLD, 12, new Color(30, 41, 59));
    private static final Font SECTION_HEADER_FONT = FontFactory.getFont(FontFactory.HELVETICA_BOLD, 9, new Color(15, 23, 42));
    private static final Font TABLE_HEADER_FONT = FontFactory.getFont(FontFactory.HELVETICA_BOLD, 8.5f, Color.WHITE);
    private static final Font BOLD_FONT = FontFactory.getFont(FontFactory.HELVETICA_BOLD, 8.5f, new Color(15, 23, 42));
    private static final Font BODY_FONT = FontFactory.getFont(FontFactory.HELVETICA, 8.5f, new Color(30, 41, 59));
    private static final Font SMALL_FONT = FontFactory.getFont(FontFactory.HELVETICA, 7.5f, new Color(100, 116, 139));
    private static final Font DUES_FONT = FontFactory.getFont(FontFactory.HELVETICA_BOLD, 12, new Color(225, 29, 72));
    private static final Font GREEN_FONT = FontFactory.getFont(FontFactory.HELVETICA_BOLD, 8.5f, new Color(16, 185, 129));

    private static final Color HEADER_BG = new Color(30, 41, 59);
    private static final Color AGING_HEADER_BG = new Color(51, 65, 85);
    private static final Color BORDER_COLOR = new Color(226, 232, 240);
    private static final Color ALT_ROW_COLOR = new Color(248, 250, 252);

    private final CreditLedgerService creditLedgerService;
    private final TransactionHelper transactionHelper;
    private final SettingsRepository settingsRepository;
    private final IstClock clock;

    private record ShopHeader(String name, String address, String phone, String gstin) {}

    public CustomerStatementPdfService(CreditLedgerService creditLedgerService,
                                       TransactionHelper transactionHelper,
                                       SettingsRepository settingsRepository,
                                       IstClock clock) {
        this.creditLedgerService = Objects.requireNonNull(creditLedgerService, "CreditLedgerService cannot be null");
        this.transactionHelper = Objects.requireNonNull(transactionHelper, "TransactionHelper cannot be null");
        this.settingsRepository = Objects.requireNonNull(settingsRepository, "SettingsRepository cannot be null");
        this.clock = Objects.requireNonNull(clock, "IstClock cannot be null");
    }

    /**
     * Renders a customer statement as an in-memory PDF byte array.
     */
    public byte[] renderStatementPdf(long customerId, LocalDate asOfDate) {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        writeStatementPdf(customerId, asOfDate, baos);
        return baos.toByteArray();
    }

    /**
     * Renders a customer statement PDF to the given output stream.
     */
    public void writeStatementPdf(long customerId, LocalDate asOfDate, OutputStream out) {
        Objects.requireNonNull(out, "OutputStream cannot be null");

        CustomerCreditStatement statement = creditLedgerService.getCustomerStatement(customerId, asOfDate);
        ShopHeader shop = loadShopHeader();

        Document document = new Document(PageSize.A4, 36, 36, 36, 36);
        try {
            PdfWriter.getInstance(document, out);
            document.open();

            // 1. Header (Shop & Document Title)
            document.add(createHeaderTable(shop, statement));
            document.add(new Paragraph(" "));

            // 2. Customer Details & Balances Card
            document.add(createCustomerSummaryTable(statement));
            document.add(new Paragraph(" "));

            // 3. FIFO Aging Breakdown Table
            document.add(createAgingTable(statement.aging()));
            document.add(new Paragraph(" "));

            // 4. Detailed Transaction Ledger
            document.add(createLedgerTable(statement.entries()));

            // 5. Footer Notice
            document.add(new Paragraph(" "));
            Paragraph footer = new Paragraph(
                    "All balances are computed from immutable append-only ledger entries (PRD Section 7.6, FR-30).\n" +
                            "UdhyamSetu Offline Billing System — Electrical Goods Shop Management",
                    SMALL_FONT
            );
            footer.setAlignment(Element.ALIGN_CENTER);
            document.add(footer);

            document.close();
            logger.info("Successfully rendered PDF statement for customer id={} (dues={})",
                    customerId, statement.currentDues().toRupeesString());
        } catch (DocumentException e) {
            throw new RuntimeException("Failed to generate customer statement PDF: " + e.getMessage(), e);
        }
    }

    private PdfPTable createHeaderTable(ShopHeader shop, CustomerCreditStatement statement) {
        PdfPTable table = new PdfPTable(2);
        table.setWidthPercentage(100);

        // Left: Shop Info
        PdfPCell shopCell = new PdfPCell();
        shopCell.setBorder(Rectangle.NO_BORDER);
        shopCell.addElement(new Paragraph(shop.name(), TITLE_FONT));
        if (shop.address() != null && !shop.address().isBlank()) {
            shopCell.addElement(new Paragraph(shop.address(), SUBTITLE_FONT));
        }
        String phoneStr = shop.phone() != null && !shop.phone().isBlank() ? "Phone: " + shop.phone() : "";
        String gstinStr = shop.gstin() != null && !shop.gstin().isBlank() ? " | GSTIN: " + shop.gstin() : "";
        if (!phoneStr.isEmpty() || !gstinStr.isEmpty()) {
            shopCell.addElement(new Paragraph(phoneStr + gstinStr, SUBTITLE_FONT));
        }
        table.addCell(shopCell);

        // Right: Statement Details
        PdfPCell titleCell = new PdfPCell();
        titleCell.setBorder(Rectangle.NO_BORDER);
        titleCell.setHorizontalAlignment(Element.ALIGN_RIGHT);

        Paragraph pTitle = new Paragraph("KHATA STATEMENT", STATEMENT_TITLE_FONT);
        pTitle.setAlignment(Element.ALIGN_RIGHT);
        titleCell.addElement(pTitle);

        DateTimeFormatter dtf = DateTimeFormatter.ofPattern("dd-MMM-yyyy");
        Paragraph pDate = new Paragraph("Statement Date: " + statement.asOfDate().format(dtf), BODY_FONT);
        pDate.setAlignment(Element.ALIGN_RIGHT);
        titleCell.addElement(pDate);

        Paragraph pGenerated = new Paragraph("Generated at: " + clock.now().format(DateTimeFormatter.ofPattern("dd-MMM-yyyy HH:mm")) + " IST", SMALL_FONT);
        pGenerated.setAlignment(Element.ALIGN_RIGHT);
        titleCell.addElement(pGenerated);

        table.addCell(titleCell);
        return table;
    }

    private PdfPTable createCustomerSummaryTable(CustomerCreditStatement statement) {
        PdfPTable table = new PdfPTable(2);
        table.setWidthPercentage(100);

        Customer c = statement.customer();

        // Customer Details Box
        PdfPCell left = new PdfPCell();
        left.setBorderColor(BORDER_COLOR);
        left.setPadding(8);
        left.addElement(new Paragraph("CUSTOMER DETAILS", SECTION_HEADER_FONT));
        left.addElement(new Paragraph(c.name() + " (" + c.customerType() + ")", BOLD_FONT));
        if (c.phone() != null) left.addElement(new Paragraph("Phone: " + c.phone(), BODY_FONT));
        if (c.address() != null) left.addElement(new Paragraph("Address: " + c.address(), BODY_FONT));
        table.addCell(left);

        // Financial Summary Box
        PdfPCell right = new PdfPCell();
        right.setBorderColor(BORDER_COLOR);
        right.setPadding(8);
        right.addElement(new Paragraph("ACCOUNT SUMMARY", SECTION_HEADER_FONT));

        Paragraph duesPara = new Paragraph("Total Outstanding Dues: ₹ " + statement.currentDues().toRupeesString(), DUES_FONT);
        right.addElement(duesPara);

        if (statement.creditLimit() != null) {
            right.addElement(new Paragraph("Credit Limit: ₹ " + statement.creditLimit().toRupeesString(), BODY_FONT));
            Money avail = statement.availableCredit() != null ? statement.availableCredit() : Money.ZERO;
            Font availFont = avail.isPositive() ? GREEN_FONT : BOLD_FONT;
            right.addElement(new Paragraph("Available Credit: ₹ " + avail.toRupeesString(), availFont));
        } else {
            right.addElement(new Paragraph("Credit Limit: Unlimited (No Limit Set)", SMALL_FONT));
        }
        table.addCell(right);

        return table;
    }

    private PdfPTable createAgingTable(AgingSummary aging) {
        PdfPTable table = new PdfPTable(4);
        table.setWidthPercentage(100);

        // Header
        addCell(table, "0 - 30 Days", AGING_HEADER_BG, TABLE_HEADER_FONT, Element.ALIGN_CENTER);
        addCell(table, "31 - 60 Days", AGING_HEADER_BG, TABLE_HEADER_FONT, Element.ALIGN_CENTER);
        addCell(table, "61 - 90 Days", AGING_HEADER_BG, TABLE_HEADER_FONT, Element.ALIGN_CENTER);
        addCell(table, "90+ Days (Overdue)", AGING_HEADER_BG, TABLE_HEADER_FONT, Element.ALIGN_CENTER);

        // Values
        addCell(table, "₹ " + aging.days0To30().toRupeesString(), Color.WHITE, BOLD_FONT, Element.ALIGN_CENTER);
        addCell(table, "₹ " + aging.days31To60().toRupeesString(), Color.WHITE, BOLD_FONT, Element.ALIGN_CENTER);
        addCell(table, "₹ " + aging.days61To90().toRupeesString(), Color.WHITE, BOLD_FONT, Element.ALIGN_CENTER);
        addCell(table, "₹ " + aging.days90Plus().toRupeesString(), Color.WHITE, DUES_FONT, Element.ALIGN_CENTER);

        return table;
    }

    private PdfPTable createLedgerTable(List<CustomerLedgerEntry> entries) {
        // Cols: Date, Entry Type, Reference, Description/Notes, Debit (+), Credit (-), Balance
        PdfPTable table = new PdfPTable(new float[]{2.0f, 2.5f, 2.0f, 3.5f, 2.0f, 2.0f, 2.2f});
        table.setWidthPercentage(100);

        addCell(table, "Date", HEADER_BG, TABLE_HEADER_FONT, Element.ALIGN_LEFT);
        addCell(table, "Type", HEADER_BG, TABLE_HEADER_FONT, Element.ALIGN_LEFT);
        addCell(table, "Ref #", HEADER_BG, TABLE_HEADER_FONT, Element.ALIGN_LEFT);
        addCell(table, "Notes", HEADER_BG, TABLE_HEADER_FONT, Element.ALIGN_LEFT);
        addCell(table, "Debit (+)", HEADER_BG, TABLE_HEADER_FONT, Element.ALIGN_RIGHT);
        addCell(table, "Credit (-)", HEADER_BG, TABLE_HEADER_FONT, Element.ALIGN_RIGHT);
        addCell(table, "Balance", HEADER_BG, TABLE_HEADER_FONT, Element.ALIGN_RIGHT);

        DateTimeFormatter dtf = DateTimeFormatter.ofPattern("dd-MM-yyyy");
        long runningPaise = 0L;
        boolean alt = false;

        for (CustomerLedgerEntry entry : entries) {
            Color rowColor = alt ? ALT_ROW_COLOR : Color.WHITE;
            alt = !alt;

            long amt = entry.amount().getPaise();
            runningPaise += amt;

            String debitStr = amt > 0 ? "₹ " + Money.ofPaise(amt).toRupeesString() : "-";
            String creditStr = amt < 0 ? "₹ " + Money.ofPaise(Math.abs(amt)).toRupeesString() : "-";
            String balanceStr = "₹ " + Money.ofPaise(runningPaise).toRupeesString();

            String ref = entry.referenceType() != null && entry.referenceId() > 0
                    ? (entry.referenceType() + " #" + entry.referenceId())
                    : (entry.referenceType() != null ? entry.referenceType() : "-");

            addCell(table, entry.entryDate().format(dtf), rowColor, BODY_FONT, Element.ALIGN_LEFT);
            addCell(table, entry.entryType(), rowColor, BODY_FONT, Element.ALIGN_LEFT);
            addCell(table, ref, rowColor, SMALL_FONT, Element.ALIGN_LEFT);
            addCell(table, entry.notes() != null ? entry.notes() : "", rowColor, SMALL_FONT, Element.ALIGN_LEFT);
            addCell(table, debitStr, rowColor, BODY_FONT, Element.ALIGN_RIGHT);
            addCell(table, creditStr, rowColor, GREEN_FONT, Element.ALIGN_RIGHT);
            addCell(table, balanceStr, rowColor, BOLD_FONT, Element.ALIGN_RIGHT);
        }

        return table;
    }

    private void addCell(PdfPTable table, String text, Color bg, Font font, int align) {
        PdfPCell cell = new PdfPCell(new Phrase(text, font));
        cell.setBackgroundColor(bg);
        cell.setBorderColor(BORDER_COLOR);
        cell.setHorizontalAlignment(align);
        cell.setVerticalAlignment(Element.ALIGN_MIDDLE);
        cell.setPadding(5);
        table.addCell(cell);
    }

    private ShopHeader loadShopHeader() {
        return transactionHelper.executeRead(conn -> {
            String name = settingsRepository.getSettingOrDefault(conn, "SHOP_NAME", "UdhyamSetu Electricals");
            String address = settingsRepository.getSettingOrDefault(conn, "SHOP_ADDRESS", "Main Road, Market");
            String phone = settingsRepository.getSettingOrDefault(conn, "SHOP_PHONE", "");
            String gstin = settingsRepository.getSettingOrDefault(conn, "SHOP_GSTIN", "");
            return new ShopHeader(name, address, phone, gstin);
        });
    }
}
