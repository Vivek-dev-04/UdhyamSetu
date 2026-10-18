package com.shopbilling.model;

import java.math.BigDecimal;

/**
 * Immutable typed representation of shop configuration and operational profile
 * (PRD Section 7.3, 7.10, 7.11, FR-13, FR-44).
 */
public record ShopProfile(
        String shopName,
        String shopTagline,
        String shopAddress,
        String shopPhone,
        String shopGstin,
        ThermalWidth thermalReceiptWidth,
        String termsAndConditions,
        BigDecimal staffMaxDiscountPercent,
        boolean staffCanRegisterSale,
        long staffReturnApprovalLimitPaise,
        boolean allowNegativeStock,
        int backupRetentionCount,
        String backupExternalDirectory
) {
    public ShopProfile {
        if (shopTagline == null) shopTagline = "";
        if (shopAddress == null) shopAddress = "";
        if (shopPhone == null) shopPhone = "";
        if (shopGstin == null) shopGstin = "";
        if (thermalReceiptWidth == null) thermalReceiptWidth = ThermalWidth.WIDTH_58MM;
        if (termsAndConditions == null) termsAndConditions = "";
        if (backupExternalDirectory == null) backupExternalDirectory = "";
    }

    public static ShopProfile defaults() {
        return new ShopProfile(
                "UdhyamSetu Retail Store",
                "Quality Goods & Retail",
                "Main Market",
                "9876543210",
                "",
                ThermalWidth.WIDTH_58MM,
                "Goods once sold cannot be returned without original bill.",
                new BigDecimal("5.00"),
                true,
                50000L,
                false,
                14,
                ""
        );
    }
}
