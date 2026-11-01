package com.shopbilling.model;

import java.util.Objects;

/**
 * Immutable record representing an official effective-dated statutory GST rate configuration (Section 8).
 * Stored in SQLite table gst_rate_master.
 */
public record GstRateItem(
        Long id,
        String hsnCode,
        double gstRate,
        double cgstRate,
        double sgstRate,
        double igstRate,
        String effectiveFrom,
        String effectiveUntil,
        String notificationReference,
        String verificationStatus,
        String source,
        String createdAt
) {
    public GstRateItem {
        Objects.requireNonNull(hsnCode, "hsnCode cannot be null");
        Objects.requireNonNull(effectiveFrom, "effectiveFrom cannot be null");
        Objects.requireNonNull(notificationReference, "notificationReference cannot be null");
        if (effectiveUntil == null || effectiveUntil.isBlank()) {
            effectiveUntil = "9999-12-31";
        }
        if (verificationStatus == null || verificationStatus.isBlank()) {
            verificationStatus = "VERIFIED";
        }
    }

    public GstRateItem(String hsnCode, double gstRate, String effectiveFrom, String notificationReference) {
        this(null, hsnCode, gstRate, gstRate / 2.0, gstRate / 2.0, gstRate, effectiveFrom, "9999-12-31", notificationReference, "VERIFIED", "CBIC GST Notification", null);
    }
}
