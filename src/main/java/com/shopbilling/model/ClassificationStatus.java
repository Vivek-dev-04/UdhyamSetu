package com.shopbilling.model;

/**
 * Statutory lifecycle states for an HSN classification assignment (Section 7).
 */
public enum ClassificationStatus {
    PENDING("Pending Review", "#f59e0b", "Awaiting human verification or initial classification"),
    SUGGESTED("Suggested by Engine", "#3b82f6", "Candidate suggested by classification engine (requires verification)"),
    VERIFIED("Verified & Approved", "#10b981", "Formally verified by owner or authorized GST practitioner"),
    REJECTED("Rejected", "#ef4444", "Classification candidate evaluated and rejected"),
    REVIEW_REQUIRED("Review Required", "#8b5cf6", "Technical ambiguity or missing specifications require expert review");

    private final String label;
    private final String colorHex;
    private final String description;

    ClassificationStatus(String label, String colorHex, String description) {
        this.label = label;
        this.colorHex = colorHex;
        this.description = description;
    }

    public String getLabel() {
        return label;
    }

    public String getColorHex() {
        return colorHex;
    }

    public String getDescription() {
        return description;
    }

    public static ClassificationStatus fromString(String val) {
        if (val == null || val.isBlank()) return PENDING;
        try {
            return ClassificationStatus.valueOf(val.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            return PENDING;
        }
    }
}
