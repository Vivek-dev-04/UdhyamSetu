package com.shopbilling.service.pdf;

import com.lowagie.text.Document;
import com.lowagie.text.DocumentException;
import com.lowagie.text.Element;
import com.lowagie.text.Font;
import com.lowagie.text.FontFactory;
import com.lowagie.text.PageSize;
import com.lowagie.text.Paragraph;
import com.lowagie.text.Phrase;
import com.lowagie.text.Rectangle;
import com.lowagie.text.pdf.PdfPCell;
import com.lowagie.text.pdf.PdfPTable;
import com.lowagie.text.pdf.PdfWriter;
import com.shopbilling.db.BrandRepository;
import com.shopbilling.db.CustomerRepository;
import com.shopbilling.db.PaymentRepository;
import com.shopbilling.db.ProductRepository;
import com.shopbilling.db.ProductVariantRepository;
import com.shopbilling.db.SaleRepository;
import com.shopbilling.db.SettingsRepository;
import com.shopbilling.db.TransactionHelper;
import com.shopbilling.db.UnitRepository;
import com.shopbilling.model.Brand;
import com.shopbilling.model.Customer;
import com.shopbilling.model.Money;
import com.shopbilling.model.PaymentRecord;
import com.shopbilling.model.Product;
import com.shopbilling.model.ProductVariant;
import com.shopbilling.model.Sale;
import com.shopbilling.model.SaleItem;
import com.shopbilling.service.EntityNotFoundException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.awt.Color;
import java.io.ByteArrayOutputStream;
import java.io.OutputStream;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Service for rendering official A4 PDF invoices using OpenPDF (PRD Section 7.3, FR-13).
 * Produces clean, professional, publication-quality A4 invoices formatted for Indian retail.
 */
public class BillPdfService {

    private static final Logger logger = LoggerFactory.getLogger(BillPdfService.class);

    private static final Font TITLE_FONT = FontFactory.getFont(FontFactory.HELVETICA_BOLD, 15, new Color(30, 41, 59));
    private static final Font SUBTITLE_FONT = FontFactory.getFont(FontFactory.HELVETICA, 8.5f, new Color(71, 85, 105));
    private static final Font SECTION_HEADER_FONT = FontFactory.getFont(FontFactory.HELVETICA_BOLD, 9, new Color(15, 23, 42));
    private static final Font TABLE_HEADER_FONT = FontFactory.getFont(FontFactory.HELVETICA_BOLD, 8.5f, new Color(255, 255, 255));
    private static final Font BOLD_FONT = FontFactory.getFont(FontFactory.HELVETICA_BOLD, 8.5f, new Color(15, 23, 42));
    private static final Font BODY_FONT = FontFactory.getFont(FontFactory.HELVETICA, 8.5f, new Color(30, 41, 59));
    private static final Font SMALL_FONT = FontFactory.getFont(FontFactory.HELVETICA, 7.5f, new Color(100, 116, 139));
    private static final Font TOTAL_FONT = FontFactory.getFont(FontFactory.HELVETICA_BOLD, 11, new Color(15, 23, 42));

    private static final Color PRIMARY_COLOR = new Color(30, 41, 59);       // Slate 800
    private static final Color HEADER_BG_COLOR = new Color(51, 65, 85);     // Slate 700
    private static final Color LIGHT_BG_COLOR = new Color(248, 250, 252);   // Slate 50
    private static final Color BORDER_COLOR = new Color(226, 232, 240);     // Slate 200

    private final TransactionHelper transactionHelper;
    private final SaleRepository saleRepository;
    private final PaymentRepository paymentRepository;
    private final CustomerRepository customerRepository;
    private final ProductVariantRepository variantRepository;
    private final ProductRepository productRepository;
    private final BrandRepository brandRepository;
    private final UnitRepository unitRepository;
    private final SettingsRepository settingsRepository;

    public BillPdfService(TransactionHelper transactionHelper,
                          SaleRepository saleRepository,
                          PaymentRepository paymentRepository,
                          CustomerRepository customerRepository,
                          ProductVariantRepository variantRepository,
                          ProductRepository productRepository,
                          BrandRepository brandRepository,
                          UnitRepository unitRepository,
                          SettingsRepository settingsRepository) {
        this.transactionHelper = Objects.requireNonNull(transactionHelper, "TransactionHelper cannot be null");
        this.saleRepository = Objects.requireNonNull(saleRepository, "SaleRepository cannot be null");
        this.paymentRepository = Objects.requireNonNull(paymentRepository, "PaymentRepository cannot be null");
        this.customerRepository = Objects.requireNonNull(customerRepository, "CustomerRepository cannot be null");
        this.variantRepository = Objects.requireNonNull(variantRepository, "ProductVariantRepository cannot be null");
        this.productRepository = Objects.requireNonNull(productRepository, "ProductRepository cannot be null");
        this.brandRepository = Objects.requireNonNull(brandRepository, "BrandRepository cannot be null");
        this.unitRepository = Objects.requireNonNull(unitRepository, "UnitRepository cannot be null");
        this.settingsRepository = Objects.requireNonNull(settingsRepository, "SettingsRepository cannot be null");
    }

    /**
     * Builds the complete BillPrintModel for a sale and renders it to the given OutputStream.
     */
    public void generatePdfForSale(long saleId, OutputStream outputStream) {
        Objects.requireNonNull(outputStream, "OutputStream cannot be null");
        BillPrintModel model = loadPrintModel(saleId);
        renderPdf(model, outputStream);
    }

    /**
     * Generates PDF bytes for a given sale ID.
     */
    public byte[] generatePdfBytesForSale(long saleId) {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        generatePdfForSale(saleId, baos);
        return baos.toByteArray();
    }

    /**
     * Renders a BillPrintModel directly to an OutputStream.
     */
    public void renderPdf(BillPrintModel model, OutputStream outputStream) {
        Objects.requireNonNull(model, "BillPrintModel cannot be null");
        Objects.requireNonNull(outputStream, "OutputStream cannot be null");

        Document document = new Document(PageSize.A4, 32, 32, 32, 32);
        try {
            PdfWriter.getInstance(document, outputStream);
            document.open();

            // 1. Header: Shop Branding & Invoice Title
            addShopHeader(document, model);

            // 2. Invoice Meta & Customer Info Box
            addInvoiceMetaAndCustomerBox(document, model);

            // 3. Line Items Table
            addLineItemsTable(document, model);

            // 4. Totals, Amounts in Words & Payment Details
            addTotalsAndPaymentDetails(document, model);

            // 5. Terms & Signatory Footer
            addFooter(document);

            document.close();
            logger.info("Rendered A4 invoice PDF for bill: {}", model.billNumber());
        } catch (DocumentException e) {
            throw new RuntimeException("Failed to generate PDF invoice for bill " + model.billNumber(), e);
        }
    }

    public BillPrintModel loadPrintModel(long saleId) {
        return transactionHelper.executeRead(conn -> {
            Sale sale = saleRepository.findById(conn, saleId)
                    .orElseThrow(() -> new EntityNotFoundException("Sale id=" + saleId + " not found"));

            List<SaleItem> items = saleRepository.findItemsBySaleId(conn, saleId);
            List<PaymentRecord> payments = paymentRepository.findBySaleId(conn, saleId);

            // Customer info
            BillPrintModel.CustomerInfo custInfo;
            if (sale.customerId() != null) {
                Optional<Customer> optCust = customerRepository.findById(conn, sale.customerId());
                custInfo = optCust.map(c -> new BillPrintModel.CustomerInfo(
                        c.name(),
                        c.phone(),
                        c.address(),
                        c.customerType()
                )).orElseGet(BillPrintModel.CustomerInfo::walkIn);
            } else {
                custInfo = BillPrintModel.CustomerInfo.walkIn();
            }

            // Shop info
            String shopName = settingsRepository.getSettingOrDefault(conn, "SHOP_NAME", "SHREE GANESH ELECTRICALS");
            String shopTagline = settingsRepository.getSettingOrDefault(conn, "SHOP_TAGLINE", "Dealers in Cables, Switches, Lighting & Industrial Electrical Goods");
            String shopAddress = settingsRepository.getSettingOrDefault(conn, "SHOP_ADDRESS", "Station Road, Main Market, Opp. Town Hall");
            String shopPhone = settingsRepository.getSettingOrDefault(conn, "SHOP_PHONE", "+91 98765 43210");
            String shopGstin = settingsRepository.getSettingOrDefault(conn, "SHOP_GSTIN", "23AAAAA0000A1Z5");
            BillPrintModel.ShopInfo shopInfo = new BillPrintModel.ShopInfo(shopName, shopTagline, shopAddress, shopPhone, shopGstin);

            // Line items
            List<BillPrintModel.BillLineItem> lineItems = new ArrayList<>();
            int idx = 1;
            for (SaleItem item : items) {
                ProductVariant variant = variantRepository.findById(conn, item.variantId())
                        .orElseThrow(() -> new EntityNotFoundException("Variant id=" + item.variantId() + " not found"));
                Product product = productRepository.findById(conn, variant.productId())
                        .orElseThrow(() -> new EntityNotFoundException("Product id=" + variant.productId() + " not found"));
                Brand brand = brandRepository.findById(conn, product.brandId())
                        .orElseThrow(() -> new EntityNotFoundException("Brand id=" + product.brandId() + " not found"));

                lineItems.add(new BillPrintModel.BillLineItem(
                        idx++,
                        product.name(),
                        variant.variantName(),
                        brand.name(),
                        product.unitCode(),
                        item.quantity(),
                        item.unitPrice(),
                        item.lineDiscount(),
                        item.lineTotal()
                ));
            }

            // Payments
            List<BillPrintModel.BillPaymentInfo> paymentInfos = payments.stream()
                    .map(p -> new BillPrintModel.BillPaymentInfo(p.mode(), p.amount(), p.referenceNumber()))
                    .toList();

            String displayBillNo = sale.billNumber() != null ? sale.billNumber() : ("REG-" + sale.id());
            String words = IndianNumberToWords.convertToWords(sale.totalAmount().getPaise() / 100);

            return new BillPrintModel(
                    shopInfo,
                    displayBillNo,
                    sale.saleDate(),
                    sale.financialYear(),
                    custInfo,
                    lineItems,
                    sale.subtotal(),
                    sale.discount(),
                    sale.totalAmount(),
                    words,
                    paymentInfos,
                    "Counter User #" + sale.createdByUserId(),
                    null
            );
        });
    }

    private void addShopHeader(Document document, BillPrintModel model) throws DocumentException {
        PdfPTable headerTable = new PdfPTable(2);
        headerTable.setWidthPercentage(100);
        headerTable.setWidths(new float[]{65, 35});
        headerTable.setSpacingAfter(10);

        // Left: Shop Name & Details
        PdfPCell leftCell = new PdfPCell();
        leftCell.setBorder(Rectangle.NO_BORDER);
        leftCell.addElement(new Paragraph(model.shop().name(), TITLE_FONT));
        leftCell.addElement(new Paragraph(model.shop().tagline(), SUBTITLE_FONT));
        leftCell.addElement(new Paragraph(model.shop().address() + " • Phone: " + model.shop().phone(), SUBTITLE_FONT));
        if (model.shop().gstin() != null && !model.shop().gstin().isBlank()) {
            leftCell.addElement(new Paragraph("GSTIN: " + model.shop().gstin(), SUBTITLE_FONT));
        }
        headerTable.addCell(leftCell);

        // Right: TAX INVOICE banner & Bill Number
        PdfPCell rightCell = new PdfPCell();
        rightCell.setBorder(Rectangle.NO_BORDER);
        rightCell.setHorizontalAlignment(Element.ALIGN_RIGHT);

        Paragraph invoiceLabel = new Paragraph("TAX INVOICE / CASH MEMO", FontFactory.getFont(FontFactory.HELVETICA_BOLD, 12, PRIMARY_COLOR));
        invoiceLabel.setAlignment(Element.ALIGN_RIGHT);
        rightCell.addElement(invoiceLabel);

        Paragraph billNo = new Paragraph("Bill No: " + model.billNumber(), FontFactory.getFont(FontFactory.HELVETICA_BOLD, 10, PRIMARY_COLOR));
        billNo.setAlignment(Element.ALIGN_RIGHT);
        rightCell.addElement(billNo);

        Paragraph datePara = new Paragraph("Date: " + model.billDate().format(DateTimeFormatter.ofPattern("dd-MMM-yyyy")), BODY_FONT);
        datePara.setAlignment(Element.ALIGN_RIGHT);
        rightCell.addElement(datePara);

        Paragraph fyPara = new Paragraph("FY: " + model.financialYear(), SMALL_FONT);
        fyPara.setAlignment(Element.ALIGN_RIGHT);
        rightCell.addElement(fyPara);

        headerTable.addCell(rightCell);
        document.add(headerTable);
    }

    private void addInvoiceMetaAndCustomerBox(Document document, BillPrintModel model) throws DocumentException {
        PdfPTable custTable = new PdfPTable(2);
        custTable.setWidthPercentage(100);
        custTable.setWidths(new float[]{55, 45});
        custTable.setSpacingAfter(12);

        // Billed To Box
        PdfPCell billedToCell = new PdfPCell();
        billedToCell.setBackgroundColor(LIGHT_BG_COLOR);
        billedToCell.setBorderColor(BORDER_COLOR);
        billedToCell.setPadding(8);

        billedToCell.addElement(new Paragraph("BILLED TO:", SECTION_HEADER_FONT));
        billedToCell.addElement(new Paragraph(model.customer().name(), BOLD_FONT));
        if (model.customer().phone() != null && !model.customer().phone().isBlank()) {
            billedToCell.addElement(new Paragraph("Phone: " + model.customer().phone(), BODY_FONT));
        }
        if (model.customer().address() != null && !model.customer().address().isBlank()) {
            billedToCell.addElement(new Paragraph("Address: " + model.customer().address(), BODY_FONT));
        }
        billedToCell.addElement(new Paragraph("Customer Type: " + model.customer().customerType(), SMALL_FONT));
        custTable.addCell(billedToCell);

        // Invoice Meta Box
        PdfPCell metaCell = new PdfPCell();
        metaCell.setBackgroundColor(LIGHT_BG_COLOR);
        metaCell.setBorderColor(BORDER_COLOR);
        metaCell.setPadding(8);

        metaCell.addElement(new Paragraph("TRANSACTION DETAILS:", SECTION_HEADER_FONT));
        metaCell.addElement(new Paragraph("Cashier: " + model.cashierName(), BODY_FONT));
        metaCell.addElement(new Paragraph("Payment Status: " + (model.totalAmount().isZero() ? "NIL" : "SETTLED"), BODY_FONT));
        metaCell.addElement(new Paragraph("State: Madhya Pradesh (Code 23)", SMALL_FONT));
        metaCell.addElement(new Paragraph("Place of Supply: Within State", SMALL_FONT));
        custTable.addCell(metaCell);

        document.add(custTable);
    }

    private void addLineItemsTable(Document document, BillPrintModel model) throws DocumentException {
        PdfPTable table = new PdfPTable(7);
        table.setWidthPercentage(100);
        table.setWidths(new float[]{6, 38, 12, 12, 14, 10, 14});
        table.setSpacingAfter(10);

        // Table Header
        addTableHeaderCell(table, "#");
        addTableHeaderCell(table, "Item Description");
        addTableHeaderCell(table, "Brand");
        addTableHeaderCell(table, "Qty");
        addTableHeaderCell(table, "Rate (₹)");
        addTableHeaderCell(table, "Disc (₹)");
        addTableHeaderCell(table, "Total (₹)");

        // Table Rows
        int count = 1;
        for (BillPrintModel.BillLineItem item : model.items()) {
            Color rowBg = (count % 2 == 0) ? LIGHT_BG_COLOR : Color.WHITE;

            addCell(table, String.valueOf(item.serialNo()), Element.ALIGN_CENTER, BODY_FONT, rowBg);

            String desc = item.productName();
            if (item.variantName() != null && !item.variantName().isBlank() && !item.variantName().equalsIgnoreCase("Standard")) {
                desc += " (" + item.variantName() + ")";
            }
            addCell(table, desc, Element.ALIGN_LEFT, BODY_FONT, rowBg);
            addCell(table, item.brandName(), Element.ALIGN_LEFT, BODY_FONT, rowBg);

            String qtyStr = item.quantity().toDisplayString() + " " + item.unitCode();
            addCell(table, qtyStr, Element.ALIGN_RIGHT, BODY_FONT, rowBg);
            addCell(table, item.unitPrice().toRupeesString(), Element.ALIGN_RIGHT, BODY_FONT, rowBg);
            addCell(table, item.lineDiscount().isPositive() ? item.lineDiscount().toRupeesString() : "-", Element.ALIGN_RIGHT, BODY_FONT, rowBg);
            addCell(table, item.lineTotal().toRupeesString(), Element.ALIGN_RIGHT, BOLD_FONT, rowBg);

            count++;
        }

        document.add(table);
    }

    private void addTotalsAndPaymentDetails(Document document, BillPrintModel model) throws DocumentException {
        PdfPTable summaryTable = new PdfPTable(2);
        summaryTable.setWidthPercentage(100);
        summaryTable.setWidths(new float[]{60, 40});
        summaryTable.setSpacingAfter(12);

        // Left Cell: Amount in Words & Payment Breakdown
        PdfPCell leftCell = new PdfPCell();
        leftCell.setBorderColor(BORDER_COLOR);
        leftCell.setPadding(8);

        leftCell.addElement(new Paragraph("AMOUNT IN WORDS:", SECTION_HEADER_FONT));
        leftCell.addElement(new Paragraph(model.amountInWords(), BOLD_FONT));

        Paragraph sep = new Paragraph(" ");
        sep.setSpacingAfter(4);
        leftCell.addElement(sep);

        leftCell.addElement(new Paragraph("PAYMENT BREAKDOWN:", SECTION_HEADER_FONT));
        for (BillPrintModel.BillPaymentInfo p : model.payments()) {
            String pStr = "• " + p.mode() + ": ₹" + p.amount().toRupeesString();
            if (p.referenceNumber() != null && !p.referenceNumber().isBlank()) {
                pStr += " [Ref: " + p.referenceNumber() + "]";
            }
            leftCell.addElement(new Paragraph(pStr, BODY_FONT));
        }

        summaryTable.addCell(leftCell);

        // Right Cell: Subtotal, Discount, Net Payable
        PdfPCell rightCell = new PdfPCell();
        rightCell.setBorderColor(BORDER_COLOR);
        rightCell.setPadding(8);

        PdfPTable numTable = new PdfPTable(2);
        numTable.setWidthPercentage(100);
        numTable.setWidths(new float[]{55, 45});

        addSummaryRow(numTable, "Subtotal:", "₹ " + model.subtotal().toRupeesString(), BODY_FONT, false);
        if (model.discount().isPositive()) {
            addSummaryRow(numTable, "Overall Discount:", "- ₹ " + model.discount().toRupeesString(), BODY_FONT, false);
        }
        addSummaryRow(numTable, "Net Amount Payable:", "₹ " + model.totalAmount().toRupeesString(), TOTAL_FONT, true);

        rightCell.addElement(numTable);
        summaryTable.addCell(rightCell);

        document.add(summaryTable);
    }

    private void addFooter(Document document) throws DocumentException {
        PdfPTable footerTable = new PdfPTable(2);
        footerTable.setWidthPercentage(100);
        footerTable.setWidths(new float[]{65, 35});

        PdfPCell termsCell = new PdfPCell();
        termsCell.setBorder(Rectangle.NO_BORDER);
        termsCell.addElement(new Paragraph("Terms & Conditions:", SECTION_HEADER_FONT));
        termsCell.addElement(new Paragraph("1. Goods once sold can be exchanged within 7 days against original invoice.", SMALL_FONT));
        termsCell.addElement(new Paragraph("2. Manufacturer warranty applies where specified. Burnt or physically damaged goods void warranty.", SMALL_FONT));
        termsCell.addElement(new Paragraph("3. Subject to local jurisdiction only.", SMALL_FONT));
        footerTable.addCell(termsCell);

        PdfPCell signCell = new PdfPCell();
        signCell.setBorder(Rectangle.NO_BORDER);
        signCell.setHorizontalAlignment(Element.ALIGN_RIGHT);
        Paragraph signHeader = new Paragraph("For SHREE GANESH ELECTRICALS", BOLD_FONT);
        signHeader.setAlignment(Element.ALIGN_RIGHT);
        signCell.addElement(signHeader);

        Paragraph spacer = new Paragraph("\n\n");
        signCell.addElement(spacer);

        Paragraph authSign = new Paragraph("Authorized Signatory", SMALL_FONT);
        authSign.setAlignment(Element.ALIGN_RIGHT);
        signCell.addElement(authSign);
        footerTable.addCell(signCell);

        document.add(footerTable);
    }

    private void addTableHeaderCell(PdfPTable table, String text) {
        PdfPCell cell = new PdfPCell(new Phrase(text, TABLE_HEADER_FONT));
        cell.setBackgroundColor(HEADER_BG_COLOR);
        cell.setPadding(6);
        cell.setBorderColor(HEADER_BG_COLOR);
        cell.setHorizontalAlignment(Element.ALIGN_CENTER);
        table.addCell(cell);
    }

    private void addCell(PdfPTable table, String text, int align, Font font, Color bgColor) {
        PdfPCell cell = new PdfPCell(new Phrase(text, font));
        cell.setHorizontalAlignment(align);
        cell.setBackgroundColor(bgColor);
        cell.setPadding(5);
        cell.setBorderColor(BORDER_COLOR);
        table.addCell(cell);
    }

    private void addSummaryRow(PdfPTable table, String label, String value, Font font, boolean isTotal) {
        PdfPCell labelCell = new PdfPCell(new Phrase(label, font));
        labelCell.setBorder(Rectangle.NO_BORDER);
        labelCell.setPadding(3);
        if (isTotal) {
            labelCell.setBackgroundColor(LIGHT_BG_COLOR);
            labelCell.setBorder(Rectangle.TOP);
            labelCell.setBorderColor(BORDER_COLOR);
        }
        table.addCell(labelCell);

        PdfPCell valueCell = new PdfPCell(new Phrase(value, font));
        valueCell.setBorder(Rectangle.NO_BORDER);
        valueCell.setHorizontalAlignment(Element.ALIGN_RIGHT);
        valueCell.setPadding(3);
        if (isTotal) {
            valueCell.setBackgroundColor(LIGHT_BG_COLOR);
            valueCell.setBorder(Rectangle.TOP);
            valueCell.setBorderColor(BORDER_COLOR);
        }
        table.addCell(valueCell);
    }
}
