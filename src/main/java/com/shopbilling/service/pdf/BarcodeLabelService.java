package com.shopbilling.service.pdf;

import com.lowagie.text.Document;
import com.lowagie.text.DocumentException;
import com.lowagie.text.Element;
import com.lowagie.text.Font;
import com.lowagie.text.FontFactory;
import com.lowagie.text.Image;
import com.lowagie.text.PageSize;
import com.lowagie.text.Paragraph;
import com.lowagie.text.Rectangle;
import com.lowagie.text.pdf.Barcode128;
import com.lowagie.text.pdf.PdfContentByte;
import com.lowagie.text.pdf.PdfPCell;
import com.lowagie.text.pdf.PdfPTable;
import com.lowagie.text.pdf.PdfWriter;
import com.shopbilling.db.BrandRepository;
import com.shopbilling.db.ProductRepository;
import com.shopbilling.db.ProductVariantRepository;
import com.shopbilling.db.SettingsRepository;
import com.shopbilling.db.TransactionHelper;
import com.shopbilling.model.BarcodeLabelItem;
import com.shopbilling.model.BarcodeSheetConfig;
import com.shopbilling.model.Brand;
import com.shopbilling.model.Product;
import com.shopbilling.model.ProductVariant;
import com.shopbilling.service.EntityNotFoundException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.awt.Color;
import java.io.ByteArrayOutputStream;
import java.io.OutputStream;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Service for generating barcodes and rendering printable barcode sticker sheets (PRD Section 7.2 FR-5, Section 7.3 FR-8).
 * Pure service: strictly contains NO JavaFX imports.
 */
public class BarcodeLabelService {

    private static final Logger logger = LoggerFactory.getLogger(BarcodeLabelService.class);

    private static final Font SHOP_FONT = FontFactory.getFont(FontFactory.HELVETICA_BOLD, 7f, new Color(71, 85, 105));
    private static final Font PRODUCT_FONT = FontFactory.getFont(FontFactory.HELVETICA_BOLD, 7.5f, new Color(15, 23, 42));
    private static final Font PRICE_FONT = FontFactory.getFont(FontFactory.HELVETICA_BOLD, 7f, new Color(15, 23, 42));
    private static final Font MRP_FONT = FontFactory.getFont(FontFactory.HELVETICA, 6.5f, new Color(100, 116, 139));

    private final TransactionHelper transactionHelper;
    private final ProductVariantRepository productVariantRepository;
    private final ProductRepository productRepository;
    private final BrandRepository brandRepository;
    private final SettingsRepository settingsRepository;

    public BarcodeLabelService(TransactionHelper transactionHelper,
                               ProductVariantRepository productVariantRepository,
                               ProductRepository productRepository,
                               BrandRepository brandRepository,
                               SettingsRepository settingsRepository) {
        this.transactionHelper = Objects.requireNonNull(transactionHelper, "TransactionHelper cannot be null");
        this.productVariantRepository = Objects.requireNonNull(productVariantRepository, "ProductVariantRepository cannot be null");
        this.productRepository = Objects.requireNonNull(productRepository, "ProductRepository cannot be null");
        this.brandRepository = Objects.requireNonNull(brandRepository, "BrandRepository cannot be null");
        this.settingsRepository = Objects.requireNonNull(settingsRepository, "SettingsRepository cannot be null");
    }

    /**
     * Auto-generates and assigns a unique barcode if the variant does not already have one.
     * E.g. Format "890000000001" for variantId=1.
     */
    public ProductVariant generateBarcodeIfMissing(long variantId) {
        return transactionHelper.execute(conn -> {
            ProductVariant variant = productVariantRepository.findById(conn, variantId)
                    .orElseThrow(() -> new EntityNotFoundException("Variant id=" + variantId + " not found"));

            if (variant.barcode() != null && !variant.barcode().isBlank()) {
                return variant;
            }

            // Generate clean standard 12-digit GS1-compatible code: "890" (India country code) + 9-digit zero-padded id
            String generated = String.format("890%09d", variantId);

            String sql = "UPDATE product_variants SET barcode = ? WHERE id = ?;";
            try (PreparedStatement ps = conn.prepareStatement(sql)) {
                ps.setString(1, generated);
                ps.setLong(2, variantId);
                ps.executeUpdate();
            }

            logger.info("Assigned generated barcode '{}' to variant id={}", generated, variantId);
            return productVariantRepository.findById(conn, variantId)
                    .orElseThrow(() -> new EntityNotFoundException("Variant id=" + variantId + " not found"));
        });
    }

    /**
     * Prepares label metadata for a variant, assigning a barcode if missing.
     */
    public BarcodeLabelItem getLabelItemForVariant(long variantId, int printCount) {
        ProductVariant variant = generateBarcodeIfMissing(variantId);

        return transactionHelper.executeRead(conn -> {
            Product product = productRepository.findById(conn, variant.productId())
                    .orElseThrow(() -> new EntityNotFoundException("Product id=" + variant.productId() + " not found"));

            String brandName = brandRepository.findById(conn, product.brandId())
                    .map(Brand::name)
                    .orElse("");

            return new BarcodeLabelItem(
                    variant.id(),
                    product.name(),
                    brandName,
                    variant.variantName(),
                    variant.barcode(),
                    variant.mrp(),
                    variant.sellingPrice(),
                    Math.max(1, printCount)
            );
        });
    }

    /**
     * Renders a printable A4 PDF sheet containing a grid of barcode labels.
     */
    public byte[] renderBarcodeSheetPdf(List<BarcodeLabelItem> items, BarcodeSheetConfig config) {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        writeBarcodeSheetPdf(items, config, baos);
        return baos.toByteArray();
    }

    /**
     * Writes the barcode sheet PDF to the given output stream.
     */
    public void writeBarcodeSheetPdf(List<BarcodeLabelItem> items, BarcodeSheetConfig config, OutputStream out) {
        Objects.requireNonNull(items, "Items cannot be null");
        Objects.requireNonNull(out, "OutputStream cannot be null");
        final BarcodeSheetConfig cfg = config != null ? config : BarcodeSheetConfig.DEFAULT_24_UP;

        String shopName = transactionHelper.executeRead(conn -> {
            Optional<String> nameOpt = settingsRepository.getSetting(conn, "SHOP_NAME");
            if (nameOpt.isEmpty()) {
                nameOpt = settingsRepository.getSetting(conn, "shop.name");
            }
            return nameOpt.orElse("SHOP BILLING");
        });

        // A4 page with tight 18pt margins (1/4 inch) to maximise printable label area
        Document document = new Document(PageSize.A4, 18, 18, 18, 18);
        try {
            PdfWriter writer = PdfWriter.getInstance(document, out);
            document.open();

            // Expand items by their printCount
            List<BarcodeLabelItem> expandedLabels = new ArrayList<>();
            for (BarcodeLabelItem item : items) {
                int count = Math.max(1, item.printCount());
                for (int i = 0; i < count; i++) {
                    expandedLabels.add(item);
                }
            }

            int cols = cfg.columns();
            int rowsPerPage = cfg.rows();
            int labelsPerPage = cols * rowsPerPage;

            if (expandedLabels.isEmpty()) {
                document.add(new Paragraph("No barcode labels to print."));
                document.close();
                return;
            }

            int totalLabels = expandedLabels.size();
            int currentIndex = 0;

            while (currentIndex < totalLabels) {
                if (currentIndex > 0) {
                    document.newPage();
                }

                PdfPTable pageTable = new PdfPTable(cols);
                pageTable.setWidthPercentage(100);

                // Calculate available page height: A4 is 842pt, margins 36pt -> 806pt available
                float cellHeight = 780f / rowsPerPage;

                for (int slot = 0; slot < labelsPerPage; slot++) {
                    if (currentIndex < totalLabels) {
                        BarcodeLabelItem label = expandedLabels.get(currentIndex++);
                        PdfPCell labelCell = createLabelCell(writer.getDirectContent(), label, shopName, cfg, cellHeight);
                        pageTable.addCell(labelCell);
                    } else {
                        // Empty slot to keep table layout rectangular
                        PdfPCell blankCell = new PdfPCell();
                        blankCell.setBorder(Rectangle.NO_BORDER);
                        blankCell.setFixedHeight(cellHeight);
                        pageTable.addCell(blankCell);
                    }
                }

                document.add(pageTable);
            }

            document.close();
            logger.info("Successfully rendered Barcode Sheet PDF: totalLabels={} pages={}",
                    totalLabels, (int) Math.ceil((double) totalLabels / labelsPerPage));
        } catch (DocumentException e) {
            throw new RuntimeException("Failed to render Barcode Sheet PDF: " + e.getMessage(), e);
        }
    }

    private PdfPCell createLabelCell(PdfContentByte cb, BarcodeLabelItem item, String shopName,
                                     BarcodeSheetConfig cfg, float cellHeight) {
        PdfPCell cell = new PdfPCell();
        cell.setFixedHeight(cellHeight);
        cell.setBorderColor(new Color(226, 232, 240));
        cell.setBorderWidth(0.5f);
        cell.setPadding(3);
        cell.setHorizontalAlignment(Element.ALIGN_CENTER);

        // 1. Shop Header
        if (cfg.showShopName() && shopName != null && !shopName.isBlank()) {
            Paragraph pShop = new Paragraph(shopName.toUpperCase(), SHOP_FONT);
            pShop.setAlignment(Element.ALIGN_CENTER);
            cell.addElement(pShop);
        }

        // 2. Product Name & Variant
        String displayTitle = item.productName();
        if (item.variantName() != null && !item.variantName().equalsIgnoreCase("Standard")
                && !item.variantName().equalsIgnoreCase("Default")) {
            displayTitle += " (" + item.variantName() + ")";
        }
        Paragraph pProduct = new Paragraph(displayTitle, PRODUCT_FONT);
        pProduct.setAlignment(Element.ALIGN_CENTER);
        cell.addElement(pProduct);

        // 3. Vector Barcode
        try {
            Barcode128 code128 = new Barcode128();
            code128.setCode(item.barcode());
            code128.setCodeType(Barcode128.CODE128);
            code128.setBarHeight(18f);
            code128.setX(0.9f);
            code128.setSize(7f);
            code128.setBaseline(8f);

            Image barcodeImg = code128.createImageWithBarcode(cb, Color.BLACK, Color.BLACK);
            barcodeImg.setAlignment(Element.ALIGN_CENTER);
            cell.addElement(barcodeImg);
        } catch (Exception ex) {
            logger.warn("Failed to generate vector barcode for code '{}': {}", item.barcode(), ex.getMessage());
            Paragraph fallback = new Paragraph("[" + item.barcode() + "]", PRICE_FONT);
            fallback.setAlignment(Element.ALIGN_CENTER);
            cell.addElement(fallback);
        }

        // 4. Pricing (MRP vs Selling Price)
        StringBuilder priceLine = new StringBuilder();
        if (cfg.showMrp() && item.mrp().isPositive()) {
            priceLine.append("MRP: ₹").append(item.mrp().toRupeesString());
        }
        if (cfg.showSellingPrice()) {
            if (!priceLine.isEmpty()) {
                priceLine.append("  ");
            }
            priceLine.append("Offer: ₹").append(item.sellingPrice().toRupeesString());
        }

        if (!priceLine.isEmpty()) {
            Paragraph pPrice = new Paragraph(priceLine.toString(), PRICE_FONT);
            pPrice.setAlignment(Element.ALIGN_CENTER);
            cell.addElement(pPrice);
        }

        return cell;
    }
}
