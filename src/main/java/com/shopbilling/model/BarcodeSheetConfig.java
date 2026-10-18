package com.shopbilling.model;

/**
 * Configuration for printable barcode sticker sheets (e.g. A4 24-up, 40-up, or 65-up labels).
 */
public record BarcodeSheetConfig(
        int columns,
        int rows,
        boolean showShopName,
        boolean showMrp,
        boolean showSellingPrice
) {
    public static final BarcodeSheetConfig DEFAULT_24_UP = new BarcodeSheetConfig(3, 8, true, true, true);
    public static final BarcodeSheetConfig COMPACT_40_UP = new BarcodeSheetConfig(4, 10, false, true, true);
    public static final BarcodeSheetConfig LARGE_14_UP = new BarcodeSheetConfig(2, 7, true, true, true);

    public BarcodeSheetConfig {
        if (columns <= 0) {
            throw new IllegalArgumentException("Columns must be strictly positive: " + columns);
        }
        if (rows <= 0) {
            throw new IllegalArgumentException("Rows must be strictly positive: " + rows);
        }
    }

    public int labelsPerPage() {
        return columns * rows;
    }
}
