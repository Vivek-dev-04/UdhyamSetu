package com.shopbilling.service.printer;

import com.shopbilling.db.*;
import com.shopbilling.model.*;
import com.shopbilling.service.EntityNotFoundException;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Objects;

/**
 * Service managing thermal receipt printing for 58mm and 80mm thermal printers
 * using plain text formatting and ESC/POS standard control sequences (PRD Section 7.3, FR-13).
 * Strictly contains NO JavaFX imports.
 */
public class ThermalPrinterService {

    // ESC/POS Command Constants
    private static final byte[] CMD_INIT = new byte[]{0x1B, 0x40};
    private static final byte[] CMD_ALIGN_LEFT = new byte[]{0x1B, 0x61, 0x00};
    private static final byte[] CMD_ALIGN_CENTER = new byte[]{0x1B, 0x61, 0x01};
    private static final byte[] CMD_ALIGN_RIGHT = new byte[]{0x1B, 0x61, 0x02};
    private static final byte[] CMD_BOLD_ON = new byte[]{0x1B, 0x45, 0x01};
    private static final byte[] CMD_BOLD_OFF = new byte[]{0x1B, 0x45, 0x00};
    private static final byte[] CMD_DOUBLE_SIZE = new byte[]{0x1D, 0x21, 0x11};
    private static final byte[] CMD_NORMAL_SIZE = new byte[]{0x1D, 0x21, 0x00};
    private static final byte[] CMD_FEED_AND_CUT = new byte[]{0x0A, 0x0A, 0x0A, 0x1D, 0x56, 0x41, 0x03};

    private final TransactionHelper transactionHelper;
    private final SaleRepository saleRepository;
    private final PaymentRepository paymentRepository;
    private final CustomerRepository customerRepository;
    private final ProductVariantRepository variantRepository;
    private final ProductRepository productRepository;
    private final SettingsRepository settingsRepository;

    public ThermalPrinterService(TransactionHelper transactionHelper,
                                 SaleRepository saleRepository,
                                 PaymentRepository paymentRepository,
                                 CustomerRepository customerRepository,
                                 ProductVariantRepository variantRepository,
                                 ProductRepository productRepository,
                                 SettingsRepository settingsRepository) {
        this.transactionHelper = Objects.requireNonNull(transactionHelper, "TransactionHelper cannot be null");
        this.saleRepository = Objects.requireNonNull(saleRepository, "SaleRepository cannot be null");
        this.paymentRepository = Objects.requireNonNull(paymentRepository, "PaymentRepository cannot be null");
        this.customerRepository = Objects.requireNonNull(customerRepository, "CustomerRepository cannot be null");
        this.variantRepository = Objects.requireNonNull(variantRepository, "ProductVariantRepository cannot be null");
        this.productRepository = Objects.requireNonNull(productRepository, "ProductRepository cannot be null");
        this.settingsRepository = Objects.requireNonNull(settingsRepository, "SettingsRepository cannot be null");
    }

    /**
     * Generates a plain-text receipt formatted specifically for the given thermal width.
     */
    public String formatReceiptText(long saleId, ThermalWidth width) {
        Objects.requireNonNull(width, "ThermalWidth cannot be null");

        return transactionHelper.executeRead(conn -> {
            Sale sale = saleRepository.findById(conn, saleId)
                    .orElseThrow(() -> new EntityNotFoundException("Sale id=" + saleId + " not found"));

            List<SaleItem> items = saleRepository.findItemsBySaleId(conn, saleId);
            List<PaymentRecord> payments = paymentRepository.findBySaleId(conn, saleId);

            String shopName = settingsRepository.getSettingOrDefault(conn, "SHOP_NAME", "UdhyamSetu Electricals");
            String shopAddress = settingsRepository.getSettingOrDefault(conn, "SHOP_ADDRESS", "Main Market, Industrial Area");
            String shopPhone = settingsRepository.getSettingOrDefault(conn, "SHOP_PHONE", "9876543210");
            String shopGstin = settingsRepository.getSettingOrDefault(conn, "SHOP_GSTIN", "");

            Customer customer = null;
            if (sale.customerId() != null) {
                customer = customerRepository.findById(conn, sale.customerId()).orElse(null);
            }

            int w = width.getCharsPerLine();
            StringBuilder sb = new StringBuilder();

            // Header
            sb.append(center(shopName, w)).append("\n");
            if (!shopAddress.isBlank()) {
                sb.append(center(shopAddress, w)).append("\n");
            }
            if (!shopPhone.isBlank()) {
                sb.append(center("Ph: " + shopPhone, w)).append("\n");
            }
            if (!shopGstin.isBlank()) {
                sb.append(center("GSTIN: " + shopGstin, w)).append("\n");
            }

            sb.append(divider(w)).append("\n");

            // Sale Metadata
            String billTitle = sale.billNumber() != null ? ("INVOICE: " + sale.billNumber()) : ("SALE #" + sale.id());
            sb.append(billTitle).append("\n");
            sb.append("Date: ").append(sale.saleDate());
            if (sale.createdAt() != null) {
                String timePart = sale.createdAt().contains("T") ? sale.createdAt().substring(11, 16) : "";
                if (!timePart.isEmpty()) {
                    sb.append(" ").append(timePart);
                }
            }
            sb.append("\n");

            if (customer != null) {
                if (width == ThermalWidth.WIDTH_58MM) {
                    sb.append("Customer: ").append(truncate(customer.name(), w - 10)).append("\n");
                    if (customer.phone() != null && !customer.phone().isBlank()) {
                        sb.append("Phone: ").append(customer.phone()).append("\n");
                    }
                } else {
                    String custLine = "Customer: " + customer.name();
                    if (customer.phone() != null && !customer.phone().isBlank()) {
                        custLine += " (" + customer.phone() + ")";
                    }
                    sb.append(truncate(custLine, w)).append("\n");
                }
            }

            sb.append(divider(w)).append("\n");

            // Items Table
            if (width == ThermalWidth.WIDTH_58MM) {
                sb.append(padRight("Item", 16)).append(padRight("Qty", 6)).append(padLeft("Total", 10)).append("\n");
                sb.append(divider(w)).append("\n");

                for (SaleItem item : items) {
                    String itemName = resolveItemName(conn, item.variantId());
                    sb.append(truncate(itemName, w)).append("\n");

                    String qtyStr = item.quantity().toDisplayString();
                    String rateStr = "@" + item.unitPrice().toRupeesString();
                    String lineTotalStr = "Rs " + item.lineTotal().toRupeesString();

                    String leftCol = " " + qtyStr + " x " + rateStr;
                    sb.append(padRight(leftCol, 18)).append(padLeft(lineTotalStr, 14)).append("\n");

                    if (item.lineDiscount().isPositive()) {
                        sb.append(padLeft("  (Disc -Rs " + item.lineDiscount().toRupeesString() + ")", w)).append("\n");
                    }
                }
            } else {
                // 80mm (~48 chars)
                sb.append(padRight("Item", 22))
                        .append(padLeft("Qty", 6))
                        .append(padLeft("Rate", 9))
                        .append(padLeft("Total", 11))
                        .append("\n");
                sb.append(divider(w)).append("\n");

                for (SaleItem item : items) {
                    String itemName = resolveItemName(conn, item.variantId());
                    String lineTotalStr = item.lineTotal().toRupeesString();
                    String rateStr = item.unitPrice().toRupeesString();
                    String qtyStr = item.quantity().toDisplayString();

                    if (itemName.length() > 21) {
                        sb.append(itemName).append("\n");
                        sb.append(padRight("", 22))
                                .append(padLeft(qtyStr, 6))
                                .append(padLeft(rateStr, 9))
                                .append(padLeft(lineTotalStr, 11))
                                .append("\n");
                    } else {
                        sb.append(padRight(itemName, 22))
                                .append(padLeft(qtyStr, 6))
                                .append(padLeft(rateStr, 9))
                                .append(padLeft(lineTotalStr, 11))
                                .append("\n");
                    }

                    if (item.lineDiscount().isPositive()) {
                        sb.append(padLeft("(Disc: -Rs " + item.lineDiscount().toRupeesString() + ")", w)).append("\n");
                    }
                }
            }

            sb.append(divider(w)).append("\n");

            // Totals
            sb.append(twoColumn("Subtotal:", "Rs " + sale.subtotal().toRupeesString(), w)).append("\n");
            if (sale.discount().isPositive()) {
                sb.append(twoColumn("Bill Discount:", "-Rs " + sale.discount().toRupeesString(), w)).append("\n");
            }
            sb.append(twoColumn("NET TOTAL:", "Rs " + sale.totalAmount().toRupeesString(), w)).append("\n");

            sb.append(divider(w)).append("\n");

            // Payments
            sb.append("Payment Mode(s):").append("\n");
            for (PaymentRecord pr : payments) {
                sb.append("  ").append(twoColumn(pr.mode().name(), "Rs " + pr.amount().toRupeesString(), w - 2)).append("\n");
            }

            sb.append(divider(w)).append("\n");

            // Footer
            sb.append(center("Goods once sold cannot be returned", w)).append("\n");
            sb.append(center("without original bill / invoice.", w)).append("\n");
            sb.append(center("Thank You! Visit Again", w)).append("\n");
            sb.append(center("धन्यवाद", w)).append("\n");

            return sb.toString();
        });
    }

    /**
     * Generates a raw ESC/POS byte array ready for transmission to a thermal receipt printer.
     */
    public byte[] generateEscPosBytes(long saleId, ThermalWidth width) {
        String receiptText = formatReceiptText(saleId, width);
        ByteArrayOutputStream baos = new ByteArrayOutputStream();

        try {
            // 1. Initialize Printer
            baos.write(CMD_INIT);

            // 2. Text Content
            String[] lines = receiptText.split("\n");
            boolean inHeader = true;

            for (String line : lines) {
                if (line.startsWith("---")) {
                    inHeader = false;
                }

                if (inHeader && !line.isBlank()) {
                    baos.write(CMD_ALIGN_CENTER);
                    baos.write(CMD_BOLD_ON);
                    baos.write(line.getBytes(StandardCharsets.UTF_8));
                    baos.write(0x0A);
                    baos.write(CMD_BOLD_OFF);
                    baos.write(CMD_ALIGN_LEFT);
                } else if (line.startsWith("NET TOTAL:")) {
                    baos.write(CMD_BOLD_ON);
                    baos.write(line.getBytes(StandardCharsets.UTF_8));
                    baos.write(0x0A);
                    baos.write(CMD_BOLD_OFF);
                } else {
                    baos.write(line.getBytes(StandardCharsets.UTF_8));
                    baos.write(0x0A);
                }
            }

            // 3. Feed and Cut Paper
            baos.write(CMD_FEED_AND_CUT);

        } catch (IOException e) {
            throw new RuntimeException("Failed to assemble ESC/POS byte stream: " + e.getMessage(), e);
        }

        return baos.toByteArray();
    }

    private String resolveItemName(java.sql.Connection conn, long variantId) throws java.sql.SQLException {
        var optVariant = variantRepository.findById(conn, variantId);
        if (optVariant.isEmpty()) {
            return "Item #" + variantId;
        }
        ProductVariant variant = optVariant.get();
        var optProduct = productRepository.findById(conn, variant.productId());
        if (optProduct.isEmpty()) {
            return variant.variantName();
        }
        Product product = optProduct.get();
        if (variant.variantName() == null || variant.variantName().isBlank() || "Standard".equalsIgnoreCase(variant.variantName())) {
            return product.name();
        }
        return product.name() + " (" + variant.variantName() + ")";
    }

    private static String divider(int width) {
        return "-".repeat(Math.max(1, width));
    }

    private static String center(String text, int width) {
        if (text == null) return "";
        if (text.length() >= width) return text.substring(0, width);
        int totalPad = width - text.length();
        int leftPad = totalPad / 2;
        int rightPad = totalPad - leftPad;
        return " ".repeat(leftPad) + text + " ".repeat(rightPad);
    }

    private static String padRight(String text, int width) {
        if (text == null) text = "";
        if (text.length() >= width) return text.substring(0, width);
        return text + " ".repeat(width - text.length());
    }

    private static String padLeft(String text, int width) {
        if (text == null) text = "";
        if (text.length() >= width) return text.substring(0, width);
        return " ".repeat(width - text.length()) + text;
    }

    private static String truncate(String text, int width) {
        if (text == null) return "";
        if (text.length() <= width) return text;
        return text.substring(0, width);
    }

    private static String twoColumn(String left, String right, int width) {
        if (left == null) left = "";
        if (right == null) right = "";
        int space = width - left.length() - right.length();
        if (space < 1) {
            return left + " " + right;
        }
        return left + " ".repeat(space) + right;
    }
}
