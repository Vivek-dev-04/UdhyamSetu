package com.shopbilling.model;

/**
 * Standard thermal receipt printer paper widths (PRD Section 7.3, FR-13).
 */
public enum ThermalWidth {
    WIDTH_58MM(32),
    WIDTH_80MM(48);

    private final int charsPerLine;

    ThermalWidth(int charsPerLine) {
        this.charsPerLine = charsPerLine;
    }

    public int getCharsPerLine() {
        return charsPerLine;
    }
}
