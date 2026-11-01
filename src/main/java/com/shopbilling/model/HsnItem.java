package com.shopbilling.model;

import java.util.Objects;

/**
 * Immutable record representing an official GST HSN/SAC Directory Entry.
 * Stored in SQLite table hsn_master (Section 3).
 * Supports Chapter, Heading, Subheading, Tariff Item, Source Provenance, and Legal/Technical Notes.
 */
public record HsnItem(
        Long id,
        String hsnCode,
        String description,
        String productType,
        String chapter,
        String heading,
        String subheading,
        String tariffItem,
        String source,
        String sourceVersion,
        String importDate,
        String technicalNotes,
        String updatedAt
) {
    public HsnItem {
        Objects.requireNonNull(hsnCode, "HSN code cannot be null");
        Objects.requireNonNull(description, "Description cannot be null");
        hsnCode = cleanCode(hsnCode);
        description = description.trim();
        if (hsnCode.isEmpty()) {
            throw new IllegalArgumentException("HSN code cannot be blank");
        }
        if (description.isEmpty()) {
            throw new IllegalArgumentException("Description cannot be blank");
        }
        if (productType == null || productType.trim().isEmpty()) {
            productType = "GOODS";
        } else {
            productType = productType.trim().toUpperCase();
        }

        // Derive chapter and heading automatically if not provided
        if (chapter == null || chapter.isBlank()) {
            chapter = hsnCode.length() >= 2 ? hsnCode.substring(0, 2) : hsnCode;
        }
        if (heading == null || heading.isBlank()) {
            heading = hsnCode.length() >= 4 ? hsnCode.substring(0, 4) : hsnCode;
        }
        if (subheading == null || subheading.isBlank()) {
            subheading = hsnCode.length() >= 6 ? hsnCode.substring(0, 6) : null;
        }
        if (tariffItem == null || tariffItem.isBlank()) {
            tariffItem = hsnCode.length() == 8 ? hsnCode : null;
        }
        if (source == null || source.isBlank()) {
            source = "CBIC Customs Tariff";
        }
        if (sourceVersion == null || sourceVersion.isBlank()) {
            sourceVersion = "2024-25";
        }
    }

    /**
     * Backward-compatible 5-parameter constructor.
     */
    public HsnItem(Long id, String hsnCode, String description, String productType, String updatedAt) {
        this(id, hsnCode, description, productType, null, null, null, null, null, null, null, null, updatedAt);
    }

    /**
     * Backward-compatible 3-parameter constructor.
     */
    public HsnItem(String hsnCode, String description, String productType) {
        this(null, hsnCode, description, productType, null, null, null, null, null, null, null, null, null);
    }

    public static String cleanCode(String raw) {
        if (raw == null) {
            return "";
        }
        String cleaned = raw.trim();
        // Handle Excel numeric formatting e.g. "8544.0" -> "8544"
        if (cleaned.endsWith(".0")) {
            cleaned = cleaned.substring(0, cleaned.length() - 2);
        }
        // Remove spaces, dots, and hyphens often present in formatted tariff codes
        return cleaned.replaceAll("[^a-zA-Z0-9]", "");
    }
}
