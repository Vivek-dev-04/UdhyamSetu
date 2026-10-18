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
 * Service generating publication-quality A4 PDF distributor/supplier account statements
 * (Supplier Khata) with 4-tier FIFO aging analysis and running payables balances (PRD Section 7.6, FR-29, Section 10).
 * Pure service: strictly contains NO JavaFX imports.
 */
public class SupplierStatementPdfService {

    private static final Logger logger = LoggerFactory.getLogger(SupplierStatementPdfService.class);

    private static final Font TITLE_FONT = FontFactory.getFont(FontFactory.HELVETICA_BOLD, 15, new Color(15, 23, 42));
    private static final Font SUBTITLE_FONT = FontFactory.getFont(FontFactory.HELVETICA, 8.5f, new Color(71, 85, 105));
    private static final Font STATEMENT_TITLE_FONT = FontFactory.getFont(FontFactory.HELVETICA_BOLD, 12, new Color(30, 41, 59));
    private static final Font SECTION_HEADER_FONT = FontFactory.getFont(FontFactory.HELVETICA_BOLD, 9, new Color(15, 23, 42));
    private static final Font TABLE_HEADER_FONT = FontFactory.getFont(FontFactory.HELVETICA_BOLD, 8.5f, Color.WHITE);
    private static final Font BOLD_FONT = FontFactory.getFont(FontFactory.HELVETICA_BOLD, 8.5f, new Color(15, 23, 42));
    private static final Font BODY_FONT = FontFactory.getFont(FontFactory.HELVETICA, 8.5f, new Color(30, 41, 59));
    private static final Font SMALL_FONT = FontFactory.getFont(FontFactory.HELVETICA, 7.5f, new Color(100, 116, 139));
    private static final Font PAYABLE_FONT = FontFactory.getFont(FontFactory.HELVETICA_BOLD, 12, new Color(225, 29, 72));
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

    public SupplierStatementPdfService(CreditLedgerService creditLedgerService,
                                       TransactionHelper transactionHelper,
                                       SettingsRepository settingsRepository,
                                       IstClock clock) {
        this.creditLedgerService = Objects.requireNonNull(creditLedgerService, "CreditLedgerService cannot be null");
        this.transactionHelper = Objects.requireNonNull(transactionHelper, "TransactionHelper cannot be null");
        this.settingsRepository = Objects.requireNonNull(settingsRepository, "SettingsRepository cannot be null");
        this.clock = Objects.requireNonNull(clock, "IstClock cannot be null");
    }

    /**
     * Renders a supplier statement as an in-memory PDF byte array.
     */
    public byte[] renderStatementPdf(long supplierId, LocalDate asOfDate) {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        writeStatementPdf(supplierId, asOfDate, baos);
        return baos.toByteArray();
    }

    /**
     * Renders a supplier statement PDF to the given output stream.
     */
    public void writeStatementPdf(long supplierId, LocalDate asOfDate, OutputStream out) {
        Objects.requireNonNull(out, "OutputStream cannot be null");

        SupplierStatement statement = creditLedgerService.getSupplierStatement(supplierId, asOfDate);
        ShopHeader shop = loadShopHeader();

        Document document = new Document(PageSize.A4, 36, 36, 36, 36);
        try {
            PdfWriter.getInstance(document, out);
            document.open();

            // 1. Header (Shop & Document Title)
            document.add(createHeaderTable(shop, statement));
            document.add(new Paragraph(" "));

            // 2. Supplier Details & Payables Summary Card
            document.add(createSupplierSummaryTable(statement));
            document.add(new Paragraph(" "));

            // 3. FIFO Aging Breakdown Table
            document.add(createAgingTable(statement.aging()));
            document.add(new Paragraph(" "));

            // 4. Detailed Transaction Ledger
            document.add(createLedgerTable(statement.entries()));

            // 5. Footer Notice
            document.add(new Paragraph(" "));
            Paragraph footer = new Paragraph(
                    "All payable balances are computed from immutable append-only supplier ledger entries (PRD Section 7.6, FR-29, FR-30).\n" +
                            "UdhyamSetu Offline Billing System — Electrical Goods Shop Management",
                    SMALL_FONT
            );
            footer.setAlignment(Element.ALIGN_CENTER);
            document.add(footer);

            document.close();
            logger.info("Successfully rendered PDF statement for supplier id={} (payable={})",
                    supplierId, statement.currentPayable().toRupeesString());
        } catch (DocumentException e) {
            throw new RuntimeException("Failed to generate supplier statement PDF: " + e.getMessage(), e);
        }
    }

    private PdfPTable createHeaderTable(ShopHeader shop, SupplierStatement statement) {
        PdfPTable table = new PdfPTable(2);
        table.setWidthPercentage(100);

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

        PdfPCell titleCell = new PdfPCell();
        titleCell.setBorder(Rectangle.NO_BORDER);
        titleCell.setHorizontalAlignment(Element.ALIGN_RIGHT);

        Paragraph pTitle = new Paragraph("SUPPLIER STATEMENT", STATEMENT_TITLE_FONT);
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

    private PdfPTable createSupplierSummaryTable(SupplierStatement statement) {
        PdfPTable table = new PdfPTable(2);
        table.setWidthPercentage(100);

        Supplier s = statement.supplier();

        // Supplier Details Box
        PdfPCell left = new PdfPCell();
        left.setBorderColor(BORDER_COLOR);
        left.setPadding(8);
        left.addElement(new Paragraph("DISTRIBUTOR / SUPPLIER DETAILS", SECTION_HEADER_FONT));
        left.addElement(new Paragraph(s.name(), BOLD_FONT));
        if (s.contactPerson() != null) left.addElement(new Paragraph("Contact: " + s.contactPerson(), BODY_FONT));
        if (s.phone() != null) left.addElement(new Paragraph("Phone: " + s.phone(), BODY_FONT));
        if (s.gstin() != null) left.addElement(new Paragraph("GSTIN: " + s.gstin(), SMALL_FONT));
        if (s.address() != null) left.addElement(new Paragraph("Address: " + s.address(), SMALL_FONT));
        table.addCell(left);

        // Account Summary Box
        PdfPCell right = new PdfPCell();
        right.setBorderColor(BORDER_COLOR);
        right.setPadding(8);
        right.addElement(new Paragraph("PAYABLES SUMMARY", SECTION_HEADER_FONT));

        Paragraph payPara = new Paragraph("Current Outstanding Payable: ₹ " + statement.currentPayable().toRupeesString(), PAYABLE_FONT);
        right.addElement(payPara);
        right.addElement(new Paragraph("\nAmount the shop currently owes this distributor.", SMALL_FONT));
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
        addCell(table, "₹ " + aging.days90Plus().toRupeesString(), Color.WHITE, PAYABLE_FONT, Element.ALIGN_CENTER);

        return table;
    }

    private PdfPTable createLedgerTable(List<SupplierLedgerEntry> entries) {
        // Cols: Date, Entry Type, Reference, Notes, Purchases (+), Payments/Returns (-), Running Payable Balance
        PdfPTable table = new PdfPTable(new float[]{2.0f, 2.5f, 2.2f, 3.3f, 2.0f, 2.0f, 2.2f});
        table.setWidthPercentage(100);

        addCell(table, "Date", HEADER_BG, TABLE_HEADER_FONT, Element.ALIGN_LEFT);
        addCell(table, "Type", HEADER_BG, TABLE_HEADER_FONT, Element.ALIGN_LEFT);
        addCell(table, "Ref #", HEADER_BG, TABLE_HEADER_FONT, Element.ALIGN_LEFT);
        addCell(table, "Notes", HEADER_BG, TABLE_HEADER_FONT, Element.ALIGN_LEFT);
        addCell(table, "Purchases (+)", HEADER_BG, TABLE_HEADER_FONT, Element.ALIGN_RIGHT);
        addCell(table, "Paid/Returned (-)", HEADER_BG, TABLE_HEADER_FONT, Element.ALIGN_RIGHT);
        addCell(table, "Balance", HEADER_BG, TABLE_HEADER_FONT, Element.ALIGN_RIGHT);

        DateTimeFormatter dtf = DateTimeFormatter.ofPattern("dd-MM-yyyy");
        long runningPaise = 0L;
        boolean alt = false;

        for (SupplierLedgerEntry entry : entries) {
            Color rowColor = alt ? ALT_ROW_COLOR : Color.WHITE;
            alt = !alt;

            long amt = entry.amount().getPaise();
            runningPaise += amt;

            String purchaseStr = amt > 0 ? "₹ " + Money.ofPaise(amt).toRupeesString() : "-";
            String paidStr = amt < 0 ? "₹ " + Money.ofPaise(Math.abs(amt)).toRupeesString() : "-";
            String balanceStr = "₹ " + Money.ofPaise(runningPaise).toRupeesString();

            String ref = entry.referenceType() != null && entry.referenceId() > 0
                    ? (entry.referenceType() + " #" + entry.referenceId())
                    : (entry.referenceType() != null ? entry.referenceType() : "-");

            addCell(table, entry.entryDate().format(dtf), rowColor, BODY_FONT, Element.ALIGN_LEFT);
            addCell(table, entry.entryType(), rowColor, BODY_FONT, Element.ALIGN_LEFT);
            addCell(table, ref, rowColor, SMALL_FONT, Element.ALIGN_LEFT);
            addCell(table, entry.notes() != null ? entry.notes() : "", rowColor, SMALL_FONT, Element.ALIGN_LEFT);
            addCell(table, purchaseStr, rowColor, BODY_FONT, Element.ALIGN_RIGHT);
            addCell(table, paidStr, rowColor, GREEN_FONT, Element.ALIGN_RIGHT);
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
