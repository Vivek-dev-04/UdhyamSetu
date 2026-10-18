package com.shopbilling.model;

/**
 * Supported application UI and document localization languages (PRD Section 9, Section 15 Question 8).
 */
public enum AppLocale {
    ENGLISH("en", "English"),
    HINDI("hi", "हिन्दी");

    private final String code;
    private final String displayName;

    AppLocale(String code, String displayName) {
        this.code = code;
        this.displayName = displayName;
    }

    public String getCode() {
        return code;
    }

    public String getDisplayName() {
        return displayName;
    }

    public static AppLocale fromCode(String code) {
        if (code == null) return ENGLISH;
        for (AppLocale l : values()) {
            if (l.code.equalsIgnoreCase(code) || l.name().equalsIgnoreCase(code)) {
                return l;
            }
        }
        return ENGLISH;
    }
}
