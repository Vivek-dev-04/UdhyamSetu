package com.shopbilling.model;

import java.util.Objects;

/**
 * Immutable record representing a Product Group in the Tally-inspired classification hierarchy (Section 9).
 * Maintains default HSN code and GST rate for product inheritance with individual product override capability.
 */
public record ProductGroup(
        Long id,
        Long parentGroupId,
        String groupCode,
        String groupName,
        String defaultHsnCode,
        Double defaultGstRate,
        String description,
        boolean isActive,
        String createdAt
) {
    public ProductGroup {
        Objects.requireNonNull(groupCode, "groupCode cannot be null");
        Objects.requireNonNull(groupName, "groupName cannot be null");
        groupCode = groupCode.trim().toUpperCase();
        groupName = groupName.trim();
        if (groupCode.isEmpty()) {
            throw new IllegalArgumentException("groupCode cannot be blank");
        }
        if (groupName.isEmpty()) {
            throw new IllegalArgumentException("groupName cannot be blank");
        }
        if (defaultGstRate == null) {
            defaultGstRate = 18.0;
        }
    }

    public ProductGroup(String groupCode, String groupName, String defaultHsnCode, Double defaultGstRate, String description) {
        this(null, null, groupCode, groupName, defaultHsnCode, defaultGstRate, description, true, null);
    }
}
