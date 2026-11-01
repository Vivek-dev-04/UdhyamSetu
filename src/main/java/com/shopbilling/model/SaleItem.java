package com.shopbilling.model;

import java.util.Objects;

/**
 * Persisted line item of a sale (PRD Section 7.3, 11).
 * Preserves immutable HSN, statutory GST rate, and CGST/SGST/IGST tax breakdown snapshots (Section 8 & 13).
 */
public record SaleItem(
        Long id,
        long saleId,
        long variantId,
        Quantity quantity,
        Money unitPrice,
        Money lineDiscount,
        Money lineTotal,
        String hsnCode,
        Double gstRate,
        Double cgstRate,
        Double sgstRate,
        Double igstRate,
        Long cgstPaise,
        Long sgstPaise,
        Long igstPaise
) {
    public SaleItem {
        Objects.requireNonNull(quantity, "Quantity cannot be null");
        Objects.requireNonNull(unitPrice, "Unit price cannot be null");
        Objects.requireNonNull(lineDiscount, "Line discount cannot be null");
        Objects.requireNonNull(lineTotal, "Line total cannot be null");
        if (hsnCode != null) {
            hsnCode = HsnItem.cleanCode(hsnCode);
            if (hsnCode.isEmpty()) {
                hsnCode = null;
            }
        }
        if (gstRate == null) {
            gstRate = 18.0;
        }
        if (cgstRate == null) cgstRate = (igstRate != null && igstRate > 0) ? 0.0 : gstRate / 2.0;
        if (sgstRate == null) sgstRate = (igstRate != null && igstRate > 0) ? 0.0 : gstRate / 2.0;
        if (igstRate == null) igstRate = 0.0;
        if (cgstPaise == null) cgstPaise = 0L;
        if (sgstPaise == null) sgstPaise = 0L;
        if (igstPaise == null) igstPaise = 0L;
    }

    /**
     * Backward-compatible 9-parameter constructor.
     */
    public SaleItem(
            Long id,
            long saleId,
            long variantId,
            Quantity quantity,
            Money unitPrice,
            Money lineDiscount,
            Money lineTotal,
            String hsnCode,
            Double gstRate
    ) {
        this(id, saleId, variantId, quantity, unitPrice, lineDiscount, lineTotal, hsnCode, gstRate,
                (gstRate != null ? gstRate / 2.0 : 9.0),
                (gstRate != null ? gstRate / 2.0 : 9.0),
                0.0, 0L, 0L, 0L);
    }

    /**
     * Backward-compatible 7-parameter constructor for existing callers and tests.
     */
    public SaleItem(
            Long id,
            long saleId,
            long variantId,
            Quantity quantity,
            Money unitPrice,
            Money lineDiscount,
            Money lineTotal
    ) {
        this(id, saleId, variantId, quantity, unitPrice, lineDiscount, lineTotal, null, 18.0);
    }
}
