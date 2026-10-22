package com.shopbilling.model;

import java.util.Objects;

/**
 * Immutable record representing a Customer (Retail or Technician)
 * (PRD Section 7.3, FR-10 & Section 7.6, FR-26).
 */
public record Customer(
        Long id,
        String name,
        String phone,
        CustomerType customerType,
        String address,
        Money creditLimit,
        String notes,
        String createdAt
) {
    public Customer {
        Objects.requireNonNull(name, "Customer name cannot be null");
        Objects.requireNonNull(customerType, "CustomerType cannot be null");
        name = name.trim();
        if (name.isEmpty()) {
            throw new IllegalArgumentException("Customer name cannot be blank");
        }
        if (phone != null) {
            phone = phone.trim();
            if (phone.isEmpty()) {
                phone = null;
            }
        }
        if (address != null) {
            address = address.trim();
            if (address.isEmpty()) {
                address = null;
            }
        }
        if (notes != null) {
            notes = notes.trim();
            if (notes.isEmpty()) {
                notes = null;
            }
        }
    }

    private static final java.util.regex.Pattern GSTIN_PATTERN =
            java.util.regex.Pattern.compile("\\b\\d{2}[A-Z]{5}\\d{4}[A-Z]{1}[A-Z\\d]{1}[Z]{1}[A-Z\\d]{1}\\b");

    public String getGstin() {
        if (notes == null || notes.isBlank()) {
            return null;
        }
        java.util.regex.Matcher m = GSTIN_PATTERN.matcher(notes);
        if (m.find()) {
            return m.group();
        }
        return null;
    }

    public boolean isFirm() {
        if (notes != null && (notes.contains("[FIRM]") || notes.contains("GSTIN:"))) {
            return true;
        }
        return getGstin() != null;
    }

    public String getStateName() {
        if (notes != null && notes.contains("State:")) {
            int idx = notes.indexOf("State:");
            int end = notes.indexOf('|', idx);
            if (end == -1) end = notes.indexOf('\n', idx);
            if (end == -1) end = notes.length();
            String st = notes.substring(idx + 6, end).trim();
            if (st.contains("(")) {
                st = st.substring(0, st.indexOf('(')).trim();
            }
            if (!st.isEmpty()) return st;
        }
        String gstin = getGstin();
        if (gstin != null && gstin.length() >= 2) {
            return StateCodeHelper.getStateName(gstin.substring(0, 2));
        }
        return "Rajasthan";
    }

    public String getStateCode() {
        if (notes != null && notes.contains("StateCode:")) {
            int idx = notes.indexOf("StateCode:");
            int end = notes.indexOf('|', idx);
            if (end == -1) end = notes.indexOf('\n', idx);
            if (end == -1) end = notes.length();
            String cd = notes.substring(idx + 10, end).trim();
            if (!cd.isEmpty()) return cd;
        }
        String gstin = getGstin();
        if (gstin != null && gstin.length() >= 2) {
            return gstin.substring(0, 2);
        }
        return "08";
    }
}
