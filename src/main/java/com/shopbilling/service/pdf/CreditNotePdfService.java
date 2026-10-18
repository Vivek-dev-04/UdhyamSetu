package com.shopbilling.service.pdf;

import com.lowagie.text.*;
import com.lowagie.text.pdf.PdfPCell;
import com.lowagie.text.pdf.PdfPTable;
import com.lowagie.text.pdf.PdfWriter;
import com.shopbilling.db.*;
import com.shopbilling.model.*;
import com.shopbilling.service.EntityNotFoundException;
import com.shopbilling.service.IstClock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.awt.Color;
import java.io.ByteArrayOutputStream;
import java.io.OutputStream;
import java.time.format.DateTimeFormatter;
import java.util.Objects;
import java.util.Optional;

/**
 * Service for rendering official A4 PDF Credit Notes using OpenPDF (PRD Section 7.5, FR-24).
 * Formats GST-compliant credit notes with original invoice references, itemized returns,
 * refund modes, and amount in words.
 * Pure service: strictly contains NO JavaFX imports.
 */
public class CreditNotePdfService {

    private static final Logger logger = LoggerFactory.getLogger(CreditNotePdfService.class);

    private static final Font TITLE_FONT = FontFactory.getFont(FontFactory.HELVETICA_BOLD, 15, new Color(15, 23, 42));
    private static final Font SUBTITLE_FONT = FontFactory.getFont(FontFactory.HELVETICA, 8.5f, new Color(71, 85, 105));
    private static final Font CN_TITLE_FONT = FontFactory.getFont(FontFactory.HELVETICA_BOLD, 13, new Color(190, 18, 60));
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
    private final ReturnRepository returnRepository;
    private final SaleRepository saleRepository;
    private final CustomerRepository customerRepository;
    private final ProductVariantRepository variantRepository;
    private final ProductRepository productRepository;
    private final UnitRepository unitRepository;
    private final SettingsRepository settingsRepository;
    private final IstClock clock;

    private record ShopHeader(String name, String address, String phone, String gstin) {}

    public CreditNotePdfService(TransactionHelper transactionHelper,
                                ReturnRepository returnRepository,
                                SaleRepository saleRepository,
                                CustomerRepository customerRepository,
                                ProductVariantRepository variantRepository,
                                ProductRepository productRepository,
                                UnitRepository unitRepository,
                                SettingsRepository settingsRepository,
                                IstClock clock) {
        this.transactionHelper = Objects.requireNonNull(transactionHelper, "TransactionHelper cannot be null");
        this.returnRepository = Objects.requireNonNull(returnRepository, "ReturnRepository cannot be null");
        this.saleRepository = Objects.requireNonNull(saleRepository, "SaleRepository cannot be null");
        this.customerRepository = Objects.requireNonNull(customerRepository, "CustomerRepository cannot be null");
        this.variantRepository = Objects.requireNonNull(variantRepository, "ProductVariantRepository cannot be null");
        this.productRepository = Objects.requireNonNull(productRepository, "ProductRepository cannot be null");
        this.unitRepository = Objects.requireNonNull(unitRepository, "UnitRepository cannot be null");
        this.settingsRepository = Objects.requireNonNull(settingsRepository, "SettingsRepository cannot be null");
        this.clock = Objects.requireNonNull(clock, "IstClock cannot be null");
    }

    /**
     * Renders a credit note as an in-memory PDF byte array.
     */
    public byte[] renderCreditNotePdf(long returnId) {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        writeCreditNotePdf(returnId, baos);
        return baos.toByteArray();
    }

    /**
     * Writes a credit note PDF to the given output stream.
     */
    public void writeCreditNotePdf(long returnId, OutputStream out) {
        Objects.requireNonNull(out, "OutputStream cannot be null");

        transactionHelper.executeRead(conn -> {
            ReturnRecord returnRecord = returnRepository.findById(conn, returnId)
                    .orElseThrow(() -> new EntityNotFoundException("Return id=" + returnId + " not found"));

            Sale sale = saleRepository.findById(conn, returnRecord.saleId())
                    .orElseThrow(() -> new EntityNotFoundException("Original Sale id=" + returnRecord.saleId() + " not found"));

            Customer customer = null;
            if (sale.customerId() != null) {
                customer = customerRepository.findById(conn, sale.customerId()).orElse(null);
            }

            ShopHeader shop = loadShopHeader(conn);

            Document document = new Document(PageSize.A4, 36, 36, 36, 36);
            try {
                PdfWriter.getInstance(document, out);
                document.open();

                // 1. Header (Shop info + Credit Note title)
                document.add(createHeaderTable(shop, returnRecord));
                document.add(new Paragraph(" "));

                // 2. Info Cards (Original Sale Reference + Customer Details)
                document.add(createMetadataGrid(sale, customer, returnRecord));
                document.add(new Paragraph(" "));

                // 3. Return Items Table
                document.add(createItemsTable(conn, returnRecord));
                document.add(new Paragraph(" "));

                // 4. Totals, Refund Summary, and Amount in Words
                document.add(createSummaryTable(returnRecord));
                document.add(new Paragraph(" "));

                // 5. Signatory Footer
                document.add(createFooter(shop));

                document.close();
                logger.info("Successfully rendered Credit Note PDF for returnId={} (CN: {})",
                        returnId, returnRecord.creditNoteNumber());
            } catch (DocumentException e) {
                throw new RuntimeException("Failed to render Credit Note PDF: " + e.getMessage(), e);
            }
            return null;
        });
    }

    private PdfPTable createHeaderTable(ShopHeader shop, ReturnRecord returnRecord) {
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

        Paragraph pTitle = new Paragraph("CREDIT NOTE", CN_TITLE_FONT);
        pTitle.setAlignment(Element.ALIGN_RIGHT);
        titleCell.addElement(pTitle);

        String cnNum = returnRecord.creditNoteNumber() != null ? returnRecord.creditNoteNumber() : "CN-PENDING";
        Paragraph pCn = new Paragraph("Credit Note #: " + cnNum, BOLD_FONT);
        pCn.setAlignment(Element.ALIGN_RIGHT);
        titleCell.addElement(pCn);

        DateTimeFormatter dtf = DateTimeFormatter.ofPattern("dd-MMM-yyyy");
        Paragraph pDate = new Paragraph("Date: " + returnRecord.returnDate().format(dtf), BODY_FONT);
        pDate.setAlignment(Element.ALIGN_RIGHT);
        titleCell.addElement(pDate);

        table.addCell(titleCell);
        return table;
    }

    private PdfPTable createMetadataGrid(Sale sale, Customer customer, ReturnRecord returnRecord) {
        PdfPTable table = new PdfPTable(2);
        table.setWidthPercentage(100);

        DateTimeFormatter dtf = DateTimeFormatter.ofPattern("dd-MMM-yyyy");

        // Left: Original Invoice Reference
        PdfPCell left = new PdfPCell();
        left.setBorderColor(BORDER_COLOR);
        left.setBackgroundColor(LIGHT_BG);
        left.setPadding(8);
        left.addElement(new Paragraph("ORIGINAL INVOICE REFERENCE", SECTION_HEADER_FONT));
        String origBill = sale.billNumber() != null ? sale.billNumber() : ("REGISTERED SALE #" + sale.id());
        left.addElement(new Paragraph("Invoice Number: " + origBill, BOLD_FONT));
        left.addElement(new Paragraph("Invoice Date: " + sale.saleDate().format(dtf), BODY_FONT));
        left.addElement(new Paragraph("Invoice Total: ₹ " + sale.totalAmount().toRupeesString(), BODY_FONT));
        table.addCell(left);

        // Right: Customer Details
        PdfPCell right = new PdfPCell();
        right.setBorderColor(BORDER_COLOR);
        right.setBackgroundColor(LIGHT_BG);
        right.setPadding(8);
        right.addElement(new Paragraph("CUSTOMER DETAILS", SECTION_HEADER_FONT));
        if (customer != null) {
            right.addElement(new Paragraph(customer.name() + " (" + customer.customerType() + ")", BOLD_FONT));
            if (customer.phone() != null) right.addElement(new Paragraph("Phone: " + customer.phone(), BODY_FONT));
            if (customer.address() != null) right.addElement(new Paragraph("Address: " + customer.address(), BODY_FONT));
        } else {
            right.addElement(new Paragraph("Retail Counter Customer (Walk-in)", BODY_FONT));
        }
        table.addCell(right);

        return table;
    }

    private PdfPTable createItemsTable(java.sql.Connection conn, ReturnRecord returnRecord) throws java.sql.SQLException {
        // Cols: #, Description, Qty, Unit, Condition, Refund Amt, Reason
        PdfPTable table = new PdfPTable(new float[]{1.0f, 6.0f, 2.0f, 2.0f, 2.5f, 3.0f, 4.5f});
        table.setWidthPercentage(100);

        addCell(table, "#", HEADER_BG, TABLE_HEADER_FONT, Element.ALIGN_CENTER);
        addCell(table, "Item Description", HEADER_BG, TABLE_HEADER_FONT, Element.ALIGN_LEFT);
        addCell(table, "Qty", HEADER_BG, TABLE_HEADER_FONT, Element.ALIGN_RIGHT);
        addCell(table, "Unit", HEADER_BG, TABLE_HEADER_FONT, Element.ALIGN_CENTER);
        addCell(table, "Condition", HEADER_BG, TABLE_HEADER_FONT, Element.ALIGN_CENTER);
        addCell(table, "Refund (₹)", HEADER_BG, TABLE_HEADER_FONT, Element.ALIGN_RIGHT);
        addCell(table, "Reason", HEADER_BG, TABLE_HEADER_FONT, Element.ALIGN_LEFT);

        int idx = 1;
        for (ReturnItemRecord item : returnRecord.items()) {
            ProductVariant variant = variantRepository.findById(conn, item.variantId()).orElse(null);
            String desc = "Variant #" + item.variantId();
            String unitCode = "PIECE";

            if (variant != null) {
                Product product = productRepository.findById(conn, variant.productId()).orElse(null);
                String prodName = product != null ? product.name() : "";
                unitCode = product != null ? product.unitCode() : "PIECE";
                desc = (prodName + " " + variant.variantName()).trim();
            }

            addCell(table, String.valueOf(idx++), Color.WHITE, BODY_FONT, Element.ALIGN_CENTER);
            addCell(table, desc, Color.WHITE, BODY_FONT, Element.ALIGN_LEFT);
            addCell(table, item.quantity().toDisplayString(), Color.WHITE, BODY_FONT, Element.ALIGN_RIGHT);
            addCell(table, unitCode, Color.WHITE, SMALL_FONT, Element.ALIGN_CENTER);
            addCell(table, item.condition().name(), Color.WHITE, SMALL_FONT, Element.ALIGN_CENTER);
            addCell(table, item.refundAmount().toRupeesString(), Color.WHITE, BOLD_FONT, Element.ALIGN_RIGHT);
            addCell(table, item.reason(), Color.WHITE, SMALL_FONT, Element.ALIGN_LEFT);
        }

        return table;
    }

    private PdfPTable createSummaryTable(ReturnRecord returnRecord) {
        PdfPTable table = new PdfPTable(2);
        table.setWidthPercentage(100);
        table.setWidths(new float[]{65, 35});

        // Left Cell: Amount in Words + Notes
        PdfPCell left = new PdfPCell();
        left.setBorderColor(BORDER_COLOR);
        left.setPadding(8);
        left.addElement(new Paragraph("AMOUNT REFUNDED (IN WORDS):", SECTION_HEADER_FONT));
        String inWords = IndianNumberToWords.convertToWords(returnRecord.totalRefund().getPaise() / 100L);
        left.addElement(new Paragraph(inWords, BOLD_FONT));

        left.addElement(new Paragraph("\nRefund Mode: " + returnRecord.refundMode(), BODY_FONT));
        if (returnRecord.notes() != null && !returnRecord.notes().isBlank()) {
            left.addElement(new Paragraph("Notes: " + returnRecord.notes(), SMALL_FONT));
        }
        table.addCell(left);

        // Right Cell: Total Refund Box
        PdfPCell right = new PdfPCell();
        right.setBorderColor(BORDER_COLOR);
        right.setBackgroundColor(LIGHT_BG);
        right.setPadding(8);

        PdfPTable inner = new PdfPTable(2);
        inner.setWidthPercentage(100);
        PdfPCell l1 = new PdfPCell(new Phrase("Total Refund:", TOTAL_FONT));
        l1.setBorder(Rectangle.NO_BORDER);
        PdfPCell r1 = new PdfPCell(new Phrase("₹ " + returnRecord.totalRefund().toRupeesString(), TOTAL_FONT));
        r1.setBorder(Rectangle.NO_BORDER);
        r1.setHorizontalAlignment(Element.ALIGN_RIGHT);
        inner.addCell(l1);
        inner.addCell(r1);

        right.addElement(inner);
        table.addCell(right);

        return table;
    }

    private PdfPTable createFooter(ShopHeader shop) {
        PdfPTable footer = new PdfPTable(2);
        footer.setWidthPercentage(100);

        PdfPCell left = new PdfPCell();
        left.setBorder(Rectangle.NO_BORDER);
        left.addElement(new Paragraph("Generated by UdhyamSetu Offline Billing System (FR-24).", SMALL_FONT));
        footer.addCell(left);

        PdfPCell right = new PdfPCell();
        right.setBorder(Rectangle.NO_BORDER);
        right.setHorizontalAlignment(Element.ALIGN_RIGHT);
        Paragraph sign = new Paragraph("For " + shop.name() + "\n\n\nAuthorized Signatory", BOLD_FONT);
        sign.setAlignment(Element.ALIGN_RIGHT);
        right.addElement(sign);
        footer.addCell(right);

        return footer;
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

    private ShopHeader loadShopHeader(java.sql.Connection conn) throws java.sql.SQLException {
        String name = settingsRepository.getSettingOrDefault(conn, "SHOP_NAME", "UdhyamSetu Electricals");
        String address = settingsRepository.getSettingOrDefault(conn, "SHOP_ADDRESS", "Main Road, Market");
        String phone = settingsRepository.getSettingOrDefault(conn, "SHOP_PHONE", "");
        String gstin = settingsRepository.getSettingOrDefault(conn, "SHOP_GSTIN", "");
        return new ShopHeader(name, address, phone, gstin);
    }
}
