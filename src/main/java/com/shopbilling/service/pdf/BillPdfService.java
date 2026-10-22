package com.shopbilling.service.pdf;

import com.lowagie.text.Document;
import com.lowagie.text.DocumentException;
import com.lowagie.text.Element;
import com.lowagie.text.Font;
import com.lowagie.text.FontFactory;
import com.lowagie.text.Image;
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
import com.shopbilling.service.pdf.qrcode.QrCode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.awt.Color;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.OutputStream;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;

/**
 * Service for rendering authentic Indian GST Tax Invoices formatted for A4 print.
 * Implements the standard Tally/GST billing format with:
 * - Detailed seller company information (GSTIN, State Code, Contact, Email)
 * - Buyer B2B / B2C "Billed to" section with GSTIN and State
 * - Invoice metadata grid (Invoice No, Date, Terms of payment, Delivery note)
 * - HSN/SAC line items table with Rate, per, and Amount
 * - Embedded UPI dynamic payment QR Code ("Scan to pay") for instant customer UPI settlement
 * - HSN/SAC Tax breakdown summary table (Taxable, CGST, SGST, Total Tax)
 * - Amount in words, Company PAN, Bank Details, Declaration, and Authorised Signatory.
 */
public class BillPdfService {

    private static final Logger logger = LoggerFactory.getLogger(BillPdfService.class);

    // Color Palette
    private static final Color COLOR_PRIMARY = new Color(20, 24, 33);
    private static final Color COLOR_BORDER = new Color(110, 115, 125);
    private static final Color COLOR_LIGHT_BORDER = new Color(180, 185, 195);
    private static final Color COLOR_BG_HEADER = new Color(243, 244, 246);

    // Typography
    private static final Font FONT_INV_TITLE = FontFactory.getFont(FontFactory.HELVETICA_BOLD, 12, COLOR_PRIMARY);
    private static final Font FONT_HEADER_MOTTO = FontFactory.getFont(FontFactory.HELVETICA_BOLD, 7, COLOR_PRIMARY);
    private static final Font FONT_HEADER_RIGHT = FontFactory.getFont(FontFactory.HELVETICA_OBLIQUE, 7.5f, COLOR_PRIMARY);

    private static final Font FONT_SHOP_NAME = FontFactory.getFont(FontFactory.HELVETICA_BOLD, 10, COLOR_PRIMARY);
    private static final Font FONT_SHOP_DETAILS = FontFactory.getFont(FontFactory.HELVETICA, 7.5f, COLOR_PRIMARY);
    private static final Font FONT_SHOP_BOLD = FontFactory.getFont(FontFactory.HELVETICA_BOLD, 7.5f, COLOR_PRIMARY);

    private static final Font FONT_META_LABEL = FontFactory.getFont(FontFactory.HELVETICA, 7.0f, new Color(75, 85, 99));
    private static final Font FONT_META_VALUE = FontFactory.getFont(FontFactory.HELVETICA_BOLD, 8.0f, COLOR_PRIMARY);

    private static final Font FONT_TH = FontFactory.getFont(FontFactory.HELVETICA_BOLD, 7.5f, COLOR_PRIMARY);
    private static final Font FONT_TD = FontFactory.getFont(FontFactory.HELVETICA, 7.5f, COLOR_PRIMARY);
    private static final Font FONT_TD_BOLD = FontFactory.getFont(FontFactory.HELVETICA_BOLD, 7.5f, COLOR_PRIMARY);
    private static final Font FONT_TINY = FontFactory.getFont(FontFactory.HELVETICA, 6.5f, COLOR_PRIMARY);
    private static final Font FONT_TINY_BOLD = FontFactory.getFont(FontFactory.HELVETICA_BOLD, 6.5f, COLOR_PRIMARY);

    private static final Font FONT_SCAN_TO_PAY = FontFactory.getFont(FontFactory.HELVETICA_BOLD, 7.5f, COLOR_PRIMARY);

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

        // Margins: Left=18, Right=18, Top=18, Bottom=18 pt
        Document document = new Document(PageSize.A4, 18, 18, 18, 18);
        try {
            PdfWriter.getInstance(document, outputStream);
            document.open();

            // 1. Top Title & Motto
            addTopTitle(document);

            // 2. Seller Company & Metadata Box
            addSellerAndMetadataBox(document, model);

            // 3. Buyer (Bill to) Box
            addBuyerBox(document, model);

            // 4. Line Items Table with Totals
            addLineItemsTable(document, model);

            // 5. Amount in Words Box
            addAmountInWordsBox(document, model);

            // 6. QR Code ("Scan to pay") & Tax Breakdown Table
            addQrCodeAndTaxBreakdown(document, model);

            // 7. Footer: PAN, Bank Details, Declaration & Signatory
            addBankAndSignatoryFooter(document, model);

            // 8. Jurisdiction & Computer Generated Note
            addJurisdictionNote(document, model);

            document.close();
            logger.info("Rendered GST Tax Invoice A4 PDF for bill: {}", model.billNumber());
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
                        c.customerType(),
                        c.getGstin(),
                        c.getStateName(),
                        c.getStateCode()
                )).orElseGet(BillPrintModel.CustomerInfo::walkIn);
            } else {
                custInfo = BillPrintModel.CustomerInfo.walkIn();
            }

            // Shop info
            String shopName = settingsRepository.getSettingOrDefault(conn, "SHOP_NAME", "MAHESHWARI ELECTRICALS - (2025-27)");
            String shopTagline = settingsRepository.getSettingOrDefault(conn, "SHOP_TAGLINE", "Chamaliwala Market, Opp. G.P.O., M.I. Road, Jaipur (RAJASTHAN)");
            String shopAddress = settingsRepository.getSettingOrDefault(conn, "SHOP_ADDRESS", "Chamaliwala Market, Opp. G.P.O., M.I. Road, Jaipur (RAJASTHAN)");
            String shopPhone = settingsRepository.getSettingOrDefault(conn, "SHOP_PHONE", "0141-2366620, 2371211 / MOB- 9829053822/9829053866");
            String shopGstin = settingsRepository.getSettingOrDefault(conn, "SHOP_GSTIN", "08AACFM6417B1ZN");
            String shopEmail = settingsRepository.getSettingOrDefault(conn, "SHOP_EMAIL", "Mecombines@rediffmail.Com");
            String shopStateName = settingsRepository.getSettingOrDefault(conn, "SHOP_STATE_NAME", "Rajasthan");
            String shopStateCode = settingsRepository.getSettingOrDefault(conn, "SHOP_STATE_CODE", "08");
            String panDefault = (shopGstin.length() >= 12 ? shopGstin.substring(2, 12) : "AACFM6417B");
            String shopPan = settingsRepository.getSettingOrDefault(conn, "SHOP_PAN", panDefault);
            String shopBankName = settingsRepository.getSettingOrDefault(conn, "SHOP_BANK_NAME", "PUNJAB NATIONAL BANK");
            String shopAccountNo = settingsRepository.getSettingOrDefault(conn, "SHOP_ACCOUNT_NO", "00615010000080");
            String shopIfsc = settingsRepository.getSettingOrDefault(conn, "SHOP_IFSC", "PUNB0006110");
            String shopBranch = settingsRepository.getSettingOrDefault(conn, "SHOP_BANK_BRANCH", "M.I. ROAD - JAIPUR");
            String shopUpiId = settingsRepository.getSettingOrDefault(conn, "SHOP_UPI_ID", "9829053822@upi");

            BillPrintModel.ShopInfo shopInfo = new BillPrintModel.ShopInfo(
                    shopName, shopTagline, shopAddress, shopPhone, shopGstin,
                    shopEmail, shopStateName, shopStateCode, shopPan,
                    shopBankName, shopAccountNo, shopIfsc, shopBranch, shopUpiId
            );

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
                        item.lineTotal(),
                        "85446020",
                        18.0
                ));
            }

            // Payments
            List<BillPrintModel.BillPaymentInfo> paymentInfos = payments.stream()
                    .map(p -> new BillPrintModel.BillPaymentInfo(p.mode(), p.amount(), p.referenceNumber()))
                    .toList();

            String displayBillNo = sale.billNumber() != null ? sale.billNumber() : ("REG-" + sale.id());
            String words = IndianNumberToWords.convertPaiseToInrWords(sale.totalAmount().getPaise());

            // Build dynamic UPI QR payload
            String upiAmount = String.format(Locale.US, "%.2f", sale.totalAmount().getPaise() / 100.0);
            String upiQrPayload;
            try {
                String encShop = URLEncoder.encode(shopName, StandardCharsets.UTF_8).replace("+", "%20");
                String encBill = URLEncoder.encode(displayBillNo, StandardCharsets.UTF_8).replace("+", "%20");
                upiQrPayload = "upi://pay?pa=" + shopUpiId + "&pn=" + encShop + "&am=" + upiAmount + "&cu=INR&tn=Invoice%20" + encBill;
            } catch (Exception e) {
                upiQrPayload = "upi://pay?pa=" + shopUpiId + "&pn=Shop&am=" + upiAmount + "&cu=INR";
            }

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
                    null,
                    null,
                    "Hand Delivery",
                    "Jaipur",
                    upiQrPayload
            );
        });
    }

    // -------------------------------------------------------------
    // PDF RENDERING SECTIONS
    // -------------------------------------------------------------

    private void addTopTitle(Document document) throws DocumentException {
        PdfPTable topTable = new PdfPTable(3);
        topTable.setWidthPercentage(100);
        topTable.setWidths(new float[]{25, 50, 25});
        topTable.setSpacingAfter(3);

        PdfPCell leftCell = new PdfPCell(new Phrase(""));
        leftCell.setBorder(Rectangle.NO_BORDER);
        topTable.addCell(leftCell);

        PdfPCell centerCell = new PdfPCell();
        centerCell.setBorder(Rectangle.NO_BORDER);
        centerCell.setHorizontalAlignment(Element.ALIGN_CENTER);

        Paragraph motto = new Paragraph("II JAI SHRI SHYAM II", FONT_HEADER_MOTTO);
        motto.setAlignment(Element.ALIGN_CENTER);
        centerCell.addElement(motto);

        Paragraph title = new Paragraph("TAX INVOICE", FONT_INV_TITLE);
        title.setAlignment(Element.ALIGN_CENTER);
        centerCell.addElement(title);
        topTable.addCell(centerCell);

        PdfPCell rightCell = new PdfPCell();
        rightCell.setBorder(Rectangle.NO_BORDER);
        rightCell.setHorizontalAlignment(Element.ALIGN_RIGHT);
        Paragraph orig = new Paragraph("(ORIGINAL FOR RECIPIENT)", FONT_HEADER_RIGHT);
        orig.setAlignment(Element.ALIGN_RIGHT);
        rightCell.addElement(orig);
        topTable.addCell(rightCell);

        document.add(topTable);
    }

    private void addSellerAndMetadataBox(Document document, BillPrintModel model) throws DocumentException {
        PdfPTable table = new PdfPTable(2);
        table.setWidthPercentage(100);
        table.setWidths(new float[]{55, 45});

        // Left Cell: Seller Details
        PdfPCell sellerCell = new PdfPCell();
        sellerCell.setBorder(Rectangle.BOX);
        sellerCell.setBorderColor(COLOR_BORDER);
        sellerCell.setPadding(5);

        sellerCell.addElement(new Paragraph(model.shop().name(), FONT_SHOP_NAME));
        sellerCell.addElement(new Paragraph(model.shop().address(), FONT_SHOP_DETAILS));
        if (model.shop().phone() != null && !model.shop().phone().isBlank()) {
            sellerCell.addElement(new Paragraph("CONTACT: " + model.shop().phone(), FONT_SHOP_DETAILS));
        }
        if (model.shop().email() != null && !model.shop().email().isBlank()) {
            sellerCell.addElement(new Paragraph("EMAIL- " + model.shop().email(), FONT_SHOP_DETAILS));
        }
        sellerCell.addElement(new Paragraph("GSTIN/UIN : " + (model.shop().gstin() != null ? model.shop().gstin() : ""), FONT_SHOP_BOLD));
        sellerCell.addElement(new Paragraph("State Name : " + model.shop().stateName() + ", Code : " + model.shop().stateCode(), FONT_SHOP_DETAILS));

        table.addCell(sellerCell);

        // Right Cell: Metadata Grid
        PdfPCell metaGridCell = new PdfPCell();
        metaGridCell.setBorder(Rectangle.BOX);
        metaGridCell.setBorderColor(COLOR_BORDER);
        metaGridCell.setPadding(0);

        PdfPTable subMeta = new PdfPTable(2);
        subMeta.setWidthPercentage(100);
        subMeta.setWidths(new float[]{50, 50});

        String dtStr = model.billDate().format(DateTimeFormatter.ofPattern("dd-MMM-yy"));

        // Row 1: Invoice No | Dated
        addMetaPair(subMeta, "Invoice No.", model.billNumber(), "Dated", dtStr);

        // Row 2: Delivery Note | Mode/Terms of Payment
        String payMode = model.payments().isEmpty() ? "Cash / Spot" : model.payments().get(0).mode().toString();
        addMetaPair(subMeta, "Delivery Note", model.deliveryNote() != null ? model.deliveryNote() : "", "Mode/Terms of Payment", payMode);

        // Row 3: Reference No. & Date | Other References
        addMetaPair(subMeta, "Reference No. & Date.", model.billNumber() + " dt. " + dtStr, "Other References", "");

        // Row 4: Buyer's Order No. | Dated
        addMetaPair(subMeta, "Buyer's Order No.", "verbal", "Dated", dtStr);

        // Row 5: Dispatched through | Destination
        addMetaPair(subMeta, "Dispatched through", model.dispatchedThrough() != null ? model.dispatchedThrough() : "Hand Delivery",
                    "Destination", model.destination() != null ? model.destination() : "Local");

        // Row 6: Terms of Delivery
        PdfPCell termsCell = new PdfPCell();
        termsCell.setColspan(2);
        termsCell.setBorder(Rectangle.TOP);
        termsCell.setBorderColor(COLOR_LIGHT_BORDER);
        termsCell.setPadding(2);
        termsCell.addElement(new Paragraph("Terms of Delivery", FONT_META_LABEL));
        termsCell.addElement(new Paragraph("Immediate Handover / Ex-Warehouse", FONT_META_VALUE));
        subMeta.addCell(termsCell);

        metaGridCell.addElement(subMeta);
        table.addCell(metaGridCell);

        document.add(table);
    }

    private void addBuyerBox(Document document, BillPrintModel model) throws DocumentException {
        PdfPTable table = new PdfPTable(1);
        table.setWidthPercentage(100);

        PdfPCell buyerCell = new PdfPCell();
        buyerCell.setBorder(Rectangle.LEFT | Rectangle.RIGHT | Rectangle.BOTTOM);
        buyerCell.setBorderColor(COLOR_BORDER);
        buyerCell.setPadding(5);

        buyerCell.addElement(new Paragraph("Buyer (Bill to)", FONT_META_LABEL));
        buyerCell.addElement(new Paragraph(model.customer().name(), FONT_SHOP_NAME));

        if (model.customer().address() != null && !model.customer().address().isBlank()) {
            buyerCell.addElement(new Paragraph(model.customer().address(), FONT_SHOP_DETAILS));
        }

        String custGstin = model.customer().gstin();
        if (custGstin != null && !custGstin.isBlank()) {
            buyerCell.addElement(new Paragraph("GSTIN/UIN   : " + custGstin, FONT_SHOP_BOLD));
        } else {
            buyerCell.addElement(new Paragraph("GSTIN/UIN   : Unregistered / Consumer", FONT_SHOP_DETAILS));
        }

        String stateName = model.customer().stateName() != null ? model.customer().stateName() : "Rajasthan";
        String stateCode = model.customer().stateCode() != null ? model.customer().stateCode() : "08";
        buyerCell.addElement(new Paragraph("State Name  : " + stateName + ", Code : " + stateCode, FONT_SHOP_DETAILS));

        if (model.customer().phone() != null && !model.customer().phone().isBlank()) {
            buyerCell.addElement(new Paragraph("Contact     : " + model.customer().phone(), FONT_SHOP_DETAILS));
        }

        table.addCell(buyerCell);
        document.add(table);
    }

    private void addLineItemsTable(Document document, BillPrintModel model) throws DocumentException {
        PdfPTable table = new PdfPTable(7);
        table.setWidthPercentage(100);
        table.setWidths(new float[]{5f, 44f, 13f, 12f, 12f, 6f, 14f});

        // Headers
        addTh(table, "SI\nNo.", Element.ALIGN_CENTER);
        addTh(table, "Description of Goods", Element.ALIGN_LEFT);
        addTh(table, "HSN/SAC", Element.ALIGN_CENTER);
        addTh(table, "Quantity", Element.ALIGN_RIGHT);
        addTh(table, "Rate", Element.ALIGN_RIGHT);
        addTh(table, "per", Element.ALIGN_CENTER);
        addTh(table, "Amount", Element.ALIGN_RIGHT);

        // Rows
        double totalQty = 0;
        for (BillPrintModel.BillLineItem item : model.items()) {
            addTd(table, String.valueOf(item.serialNo()), Element.ALIGN_CENTER, FONT_TD, false);

            String desc = item.productName();
            if (item.variantName() != null && !item.variantName().isBlank() && !item.variantName().equalsIgnoreCase("Standard")) {
                desc += " " + item.variantName();
            }
            if (item.brandName() != null && !item.brandName().isBlank()) {
                desc += " -" + item.brandName();
            }
            addTd(table, desc, Element.ALIGN_LEFT, FONT_TD_BOLD, false);

            addTd(table, item.hsnSac() != null ? item.hsnSac() : "85446020", Element.ALIGN_CENTER, FONT_TD, false);

            String qtyStr = item.quantity().toDisplayString() + " " + item.unitCode();
            addTd(table, qtyStr, Element.ALIGN_RIGHT, FONT_TD, false);
            totalQty += item.quantity().getScaledValue() / 1000.0;

            addTd(table, item.unitPrice().toRupeesString(), Element.ALIGN_RIGHT, FONT_TD, false);
            addTd(table, item.unitCode(), Element.ALIGN_CENTER, FONT_TD, false);
            addTd(table, item.lineTotal().toRupeesString(), Element.ALIGN_RIGHT, FONT_TD_BOLD, false);
        }

        // Subtotal and adjustment rows
        long totalPaise = model.totalAmount().getPaise();
        long subtotalPaise = model.subtotal().getPaise();
        long discountPaise = model.discount().getPaise();
        long netAfterDiscount = Math.max(0, subtotalPaise - discountPaise);

        if (totalPaise > netAfterDiscount) {
            // Case 1: Exclusive rates (taxes are added on top of subtotal, matching B2B photo)
            addSummaryLine(table, "", "Subtotal", "", "", "", "", Money.ofPaise(subtotalPaise).toRupeesString());

            if (model.discount().isPositive()) {
                addSummaryLine(table, "Less :", "DISCOUNT / REBATE", "", "", "", "", "(-) " + model.discount().toRupeesString());
            }

            long addedTax = totalPaise - netAfterDiscount;
            long halfTax = addedTax / 2;
            long roundOff = totalPaise - (netAfterDiscount + halfTax * 2);

            addSummaryLine(table, "", "CGST (9%)", "", "", "", "", Money.ofPaise(halfTax).toRupeesString());
            addSummaryLine(table, "", "SGST (9%)", "", "", "", "", Money.ofPaise(halfTax).toRupeesString());

            if (roundOff != 0) {
                String roSign = roundOff < 0 ? "(-) " : "";
                addSummaryLine(table, "Less :", "ROUND OFF", "", "", "", "", roSign + Money.ofPaise(Math.abs(roundOff)).toRupeesString());
            }
        } else {
            // Case 2: Standard tax-inclusive retail rates
            addSummaryLine(table, "", "Subtotal", "", "", "", "", Money.ofPaise(subtotalPaise).toRupeesString());
            if (model.discount().isPositive()) {
                addSummaryLine(table, "Less :", "DISCOUNT / REBATE", "", "", "", "", "(-) " + model.discount().toRupeesString());
            }
        }

        // Total Row
        PdfPCell totalLabelCell = new PdfPCell(new Phrase("Total", FONT_TH));
        totalLabelCell.setColspan(3);
        totalLabelCell.setHorizontalAlignment(Element.ALIGN_RIGHT);
        totalLabelCell.setPadding(4);
        totalLabelCell.setBorder(Rectangle.TOP | Rectangle.BOTTOM);
        totalLabelCell.setBorderColor(COLOR_BORDER);
        table.addCell(totalLabelCell);

        PdfPCell totalQtyCell = new PdfPCell(new Phrase(String.format(Locale.US, "%.3f", totalQty), FONT_TH));
        totalQtyCell.setHorizontalAlignment(Element.ALIGN_RIGHT);
        totalQtyCell.setPadding(4);
        totalQtyCell.setBorder(Rectangle.TOP | Rectangle.BOTTOM);
        totalQtyCell.setBorderColor(COLOR_BORDER);
        table.addCell(totalQtyCell);

        PdfPCell blankCell = new PdfPCell(new Phrase(""));
        blankCell.setColspan(2);
        blankCell.setBorder(Rectangle.TOP | Rectangle.BOTTOM);
        blankCell.setBorderColor(COLOR_BORDER);
        table.addCell(blankCell);

        PdfPCell totalAmtCell = new PdfPCell(new Phrase("₹ " + model.totalAmount().toRupeesString(), FONT_TH));
        totalAmtCell.setHorizontalAlignment(Element.ALIGN_RIGHT);
        totalAmtCell.setPadding(4);
        totalAmtCell.setBorder(Rectangle.TOP | Rectangle.BOTTOM);
        totalAmtCell.setBorderColor(COLOR_BORDER);
        table.addCell(totalAmtCell);

        document.add(table);
    }

    private void addAmountInWordsBox(Document document, BillPrintModel model) throws DocumentException {
        PdfPTable table = new PdfPTable(2);
        table.setWidthPercentage(100);
        table.setWidths(new float[]{85, 15});

        PdfPCell leftCell = new PdfPCell();
        leftCell.setBorder(Rectangle.LEFT | Rectangle.BOTTOM);
        leftCell.setBorderColor(COLOR_BORDER);
        leftCell.setPadding(4);

        leftCell.addElement(new Paragraph("Amount Chargeable (in words)", FONT_META_LABEL));
        leftCell.addElement(new Paragraph(model.amountInWords(), FONT_TD_BOLD));
        table.addCell(leftCell);

        PdfPCell rightCell = new PdfPCell();
        rightCell.setBorder(Rectangle.RIGHT | Rectangle.BOTTOM);
        rightCell.setBorderColor(COLOR_BORDER);
        rightCell.setHorizontalAlignment(Element.ALIGN_RIGHT);
        rightCell.setPadding(4);

        Paragraph eoe = new Paragraph("E. & O.E", FONT_TD_BOLD);
        eoe.setAlignment(Element.ALIGN_RIGHT);
        rightCell.addElement(eoe);
        table.addCell(rightCell);

        document.add(table);
    }

    private void addQrCodeAndTaxBreakdown(Document document, BillPrintModel model) throws DocumentException {
        PdfPTable table = new PdfPTable(2);
        table.setWidthPercentage(100);
        table.setWidths(new float[]{25, 75});

        // Left Cell: Embedded Dynamic UPI QR Code ("Scan to pay")
        PdfPCell qrCell = new PdfPCell();
        qrCell.setBorder(Rectangle.LEFT | Rectangle.BOTTOM | Rectangle.RIGHT);
        qrCell.setBorderColor(COLOR_BORDER);
        qrCell.setHorizontalAlignment(Element.ALIGN_CENTER);
        qrCell.setPadding(4);

        try {
            String upiPayload = model.upiQrPayload();
            if (upiPayload == null || upiPayload.isBlank()) {
                String shopUpi = model.shop().upiId() != null && !model.shop().upiId().isBlank() ? model.shop().upiId() : "9829053822@upi";
                String amt = String.format(Locale.US, "%.2f", model.totalAmount().getPaise() / 100.0);
                upiPayload = "upi://pay?pa=" + shopUpi + "&pn=Shop&am=" + amt + "&cu=INR&tn=Invoice%20" + model.billNumber();
            }

            QrCode qr = QrCode.encodeText(upiPayload, QrCode.Ecc.MEDIUM);
            BufferedImage bi = qr.toImage(4, 1);
            Image pdfImg = Image.getInstance(bi, null);
            pdfImg.scaleToFit(72, 72);
            pdfImg.setAlignment(Element.ALIGN_CENTER);
            qrCell.addElement(pdfImg);

            Paragraph scanLabel = new Paragraph("Scan to pay", FONT_SCAN_TO_PAY);
            scanLabel.setAlignment(Element.ALIGN_CENTER);
            scanLabel.setSpacingBefore(2);
            qrCell.addElement(scanLabel);

            String upiDisplay = model.shop().upiId() != null ? model.shop().upiId() : "UPI Payment";
            Paragraph upiIdLabel = new Paragraph(upiDisplay, FONT_TINY);
            upiIdLabel.setAlignment(Element.ALIGN_CENTER);
            qrCell.addElement(upiIdLabel);

        } catch (Exception ex) {
            logger.warn("Could not generate QR code for bill {}", model.billNumber(), ex);
            qrCell.addElement(new Paragraph("Payment QR", FONT_TD_BOLD));
        }

        table.addCell(qrCell);

        // Right Cell: Tax Breakdown Table & Tax Amount in Words
        PdfPCell taxCell = new PdfPCell();
        taxCell.setBorder(Rectangle.BOTTOM | Rectangle.RIGHT);
        taxCell.setBorderColor(COLOR_BORDER);
        taxCell.setPadding(0);

        PdfPTable taxSubTable = new PdfPTable(7);
        taxSubTable.setWidthPercentage(100);
        taxSubTable.setWidths(new float[]{18, 18, 11, 15, 11, 15, 18});

        addTaxTh(taxSubTable, "HSN/SAC");
        addTaxTh(taxSubTable, "Taxable\nValue");
        addTaxTh(taxSubTable, "CGST\nRate");
        addTaxTh(taxSubTable, "CGST\nAmount");
        addTaxTh(taxSubTable, "SGST\nRate");
        addTaxTh(taxSubTable, "SGST\nAmount");
        addTaxTh(taxSubTable, "Total\nTax Amount");

        long totalPaise = model.totalAmount().getPaise();
        long taxablePaise = Math.round((totalPaise) / 1.18);
        long cgstPaise = Math.round(taxablePaise * 0.09);
        long sgstPaise = Math.round(taxablePaise * 0.09);
        long totalTaxPaise = cgstPaise + sgstPaise;

        addTaxTd(taxSubTable, "85446020", Element.ALIGN_CENTER, false);
        addTaxTd(taxSubTable, Money.ofPaise(taxablePaise).toRupeesString(), Element.ALIGN_RIGHT, false);
        addTaxTd(taxSubTable, "9%", Element.ALIGN_CENTER, false);
        addTaxTd(taxSubTable, Money.ofPaise(cgstPaise).toRupeesString(), Element.ALIGN_RIGHT, false);
        addTaxTd(taxSubTable, "9%", Element.ALIGN_CENTER, false);
        addTaxTd(taxSubTable, Money.ofPaise(sgstPaise).toRupeesString(), Element.ALIGN_RIGHT, false);
        addTaxTd(taxSubTable, Money.ofPaise(totalTaxPaise).toRupeesString(), Element.ALIGN_RIGHT, false);

        // Tax Total Row
        addTaxTd(taxSubTable, "Total", Element.ALIGN_CENTER, true);
        addTaxTd(taxSubTable, Money.ofPaise(taxablePaise).toRupeesString(), Element.ALIGN_RIGHT, true);
        addTaxTd(taxSubTable, "", Element.ALIGN_CENTER, true);
        addTaxTd(taxSubTable, Money.ofPaise(cgstPaise).toRupeesString(), Element.ALIGN_RIGHT, true);
        addTaxTd(taxSubTable, "", Element.ALIGN_CENTER, true);
        addTaxTd(taxSubTable, Money.ofPaise(sgstPaise).toRupeesString(), Element.ALIGN_RIGHT, true);
        addTaxTd(taxSubTable, Money.ofPaise(totalTaxPaise).toRupeesString(), Element.ALIGN_RIGHT, true);

        taxCell.addElement(taxSubTable);

        // Tax in words
        Paragraph taxInWordsPara = new Paragraph(
                "Tax Amount (in words) : " + IndianNumberToWords.convertPaiseToInrWords(totalTaxPaise),
                FONT_TINY_BOLD
        );
        taxInWordsPara.setSpacingBefore(4);
        taxInWordsPara.setSpacingAfter(4);
        taxInWordsPara.setIndentationLeft(4);
        taxCell.addElement(taxInWordsPara);

        table.addCell(taxCell);
        document.add(table);
    }

    private void addBankAndSignatoryFooter(Document document, BillPrintModel model) throws DocumentException {
        PdfPTable table = new PdfPTable(2);
        table.setWidthPercentage(100);
        table.setWidths(new float[]{55, 45});

        // Left: PAN & Declaration
        PdfPCell leftCell = new PdfPCell();
        leftCell.setBorder(Rectangle.LEFT | Rectangle.BOTTOM | Rectangle.RIGHT);
        leftCell.setBorderColor(COLOR_BORDER);
        leftCell.setPadding(4);

        if (model.notes() != null && !model.notes().isBlank()) {
            leftCell.addElement(new Paragraph("Remarks: " + model.notes(), FONT_TINY));
        }

        leftCell.addElement(new Paragraph("Company's PAN : " + (model.shop().pan() != null ? model.shop().pan() : ""), FONT_TD_BOLD));

        Paragraph declTitle = new Paragraph("Declaration", FONT_TINY_BOLD);
        declTitle.setSpacingBefore(2);
        leftCell.addElement(declTitle);

        String bName = model.shop().bankName() != null ? model.shop().bankName() : "PUNJAB NATIONAL BANK";
        String bBranch = model.shop().branch() != null ? model.shop().branch() : "M.I. ROAD - JAIPUR";
        String bAcc = model.shop().accountNo() != null ? model.shop().accountNo() : "00615010000080";
        String bIfsc = model.shop().ifsc() != null ? model.shop().ifsc() : "PUNB0006110";

        String decl1 = "(1) OUR BANK : " + bName + " (" + bBranch + ")\nA/c " + bAcc + " & IFSC :-" + bIfsc;
        String decl2 = "(2) Goods Once sold will not returnable or transferable (3) Our responsibility ceases as soon as goods are delivered to carriers (4) In case of Dishonored cheques charges @ 300/- per cheque will be charged.";
        leftCell.addElement(new Paragraph(decl1, FONT_TINY));
        leftCell.addElement(new Paragraph(decl2, FONT_TINY));

        table.addCell(leftCell);

        // Right: Bank Details & Authorised Signatory
        PdfPCell rightCell = new PdfPCell();
        rightCell.setBorder(Rectangle.BOTTOM | Rectangle.RIGHT);
        rightCell.setBorderColor(COLOR_BORDER);
        rightCell.setPadding(4);

        rightCell.addElement(new Paragraph("Company's Bank Details", FONT_TINY_BOLD));
        rightCell.addElement(new Paragraph("Bank Name : " + bName, FONT_TINY));
        rightCell.addElement(new Paragraph("A/c No.   : " + bAcc, FONT_TINY));
        rightCell.addElement(new Paragraph("Branch & IFS Code: " + bBranch + " & " + bIfsc, FONT_TINY));

        Paragraph forCompany = new Paragraph("for " + model.shop().name(), FONT_TD_BOLD);
        forCompany.setAlignment(Element.ALIGN_RIGHT);
        forCompany.setSpacingBefore(6);
        rightCell.addElement(forCompany);

        // Spacer for physical signature / rubber stamp
        Paragraph signSpace = new Paragraph("\n\n");
        rightCell.addElement(signSpace);

        Paragraph authSign = new Paragraph("Authorised Signatory", FONT_TD_BOLD);
        authSign.setAlignment(Element.ALIGN_RIGHT);
        rightCell.addElement(authSign);

        table.addCell(rightCell);
        document.add(table);
    }

    private void addJurisdictionNote(Document document, BillPrintModel model) throws DocumentException {
        PdfPTable table = new PdfPTable(1);
        table.setWidthPercentage(100);
        table.setSpacingBefore(2);

        PdfPCell cell = new PdfPCell();
        cell.setBorder(Rectangle.NO_BORDER);
        cell.setHorizontalAlignment(Element.ALIGN_CENTER);

        String city = "JAIPUR";
        if (model.shop().address() != null && model.shop().address().toUpperCase().contains("JAIPUR")) {
            city = "JAIPUR";
        } else if (model.shop().stateName() != null) {
            city = model.shop().stateName().toUpperCase();
        }

        Paragraph p1 = new Paragraph("SUBJECT TO " + city + " JURISDICTION", FONT_TINY_BOLD);
        p1.setAlignment(Element.ALIGN_CENTER);
        cell.addElement(p1);

        Paragraph p2 = new Paragraph("This is a Computer Generated Invoice", FONT_TINY);
        p2.setAlignment(Element.ALIGN_CENTER);
        cell.addElement(p2);

        table.addCell(cell);
        document.add(table);
    }

    // -------------------------------------------------------------
    // TABLE CELL HELPERS
    // -------------------------------------------------------------

    private void addMetaPair(PdfPTable table, String l1, String v1, String l2, String v2) {
        PdfPCell c1 = new PdfPCell();
        c1.setBorder(Rectangle.BOTTOM | Rectangle.RIGHT);
        c1.setBorderColor(COLOR_LIGHT_BORDER);
        c1.setPadding(2);
        c1.addElement(new Paragraph(l1, FONT_META_LABEL));
        c1.addElement(new Paragraph(v1 != null && !v1.isBlank() ? v1 : " ", FONT_META_VALUE));
        table.addCell(c1);

        PdfPCell c2 = new PdfPCell();
        c2.setBorder(Rectangle.BOTTOM);
        c2.setBorderColor(COLOR_LIGHT_BORDER);
        c2.setPadding(2);
        c2.addElement(new Paragraph(l2, FONT_META_LABEL));
        c2.addElement(new Paragraph(v2 != null && !v2.isBlank() ? v2 : " ", FONT_META_VALUE));
        table.addCell(c2);
    }

    private void addTh(PdfPTable table, String text, int align) {
        PdfPCell cell = new PdfPCell(new Phrase(text, FONT_TH));
        cell.setHorizontalAlignment(align);
        cell.setVerticalAlignment(Element.ALIGN_MIDDLE);
        cell.setBackgroundColor(COLOR_BG_HEADER);
        cell.setBorderColor(COLOR_BORDER);
        cell.setPadding(4);
        table.addCell(cell);
    }

    private void addTd(PdfPTable table, String text, int align, Font font, boolean borderBottom) {
        PdfPCell cell = new PdfPCell(new Phrase(text, font));
        cell.setHorizontalAlignment(align);
        cell.setVerticalAlignment(Element.ALIGN_MIDDLE);
        cell.setBorder(Rectangle.LEFT | Rectangle.RIGHT | (borderBottom ? Rectangle.BOTTOM : 0));
        cell.setBorderColor(COLOR_LIGHT_BORDER);
        cell.setPadding(3);
        table.addCell(cell);
    }

    private void addSummaryLine(PdfPTable table, String col1, String desc, String col3, String col4, String col5, String col6, String amount) {
        addTd(table, col1, Element.ALIGN_LEFT, FONT_TD, false);
        addTd(table, desc, Element.ALIGN_RIGHT, FONT_TD_BOLD, false);
        addTd(table, col3, Element.ALIGN_CENTER, FONT_TD, false);
        addTd(table, col4, Element.ALIGN_RIGHT, FONT_TD, false);
        addTd(table, col5, Element.ALIGN_RIGHT, FONT_TD, false);
        addTd(table, col6, Element.ALIGN_CENTER, FONT_TD, false);
        addTd(table, amount, Element.ALIGN_RIGHT, FONT_TD_BOLD, false);
    }

    private void addTaxTh(PdfPTable table, String text) {
        PdfPCell cell = new PdfPCell(new Phrase(text, FONT_TINY_BOLD));
        cell.setHorizontalAlignment(Element.ALIGN_CENTER);
        cell.setVerticalAlignment(Element.ALIGN_MIDDLE);
        cell.setBackgroundColor(COLOR_BG_HEADER);
        cell.setBorderColor(COLOR_LIGHT_BORDER);
        cell.setPadding(2);
        table.addCell(cell);
    }

    private void addTaxTd(PdfPTable table, String text, int align, boolean isTotal) {
        PdfPCell cell = new PdfPCell(new Phrase(text, isTotal ? FONT_TINY_BOLD : FONT_TINY));
        cell.setHorizontalAlignment(align);
        cell.setVerticalAlignment(Element.ALIGN_MIDDLE);
        cell.setBorderColor(COLOR_LIGHT_BORDER);
        if (isTotal) {
            cell.setBackgroundColor(COLOR_BG_HEADER);
            cell.setBorder(Rectangle.TOP | Rectangle.BOTTOM);
        } else {
            cell.setBorder(Rectangle.BOX);
        }
        cell.setPadding(2);
        table.addCell(cell);
    }
}
