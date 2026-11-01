package com.shopbilling.model;

import java.util.Objects;

/**
 * Immutable record representing an active or historical product-to-HSN classification mapping (Section 7).
 */
public record ProductHsnMapping(
        Long id,
        long productId,
        String hsnCode,
        ClassificationStatus status,
        int matchingPriority,
        String matchReason,
        String supportingSource,
        String missingSpecifications,
        Long verifiedByUserId,
        String verifiedAt,
        String rejectionReason,
        boolean isActive,
        String createdAt,
        String updatedAt
) {
    public ProductHsnMapping(
            Long id,
            long productId,
            String hsnCode,
            ClassificationStatus status,
            int matchingPriority,
            String matchReason,
            String supportingSource,
            String missingSpecifications,
            Long verifiedByUserId,
            String verifiedAt
    ) {
        this(id, productId, hsnCode, status, matchingPriority, matchReason, supportingSource, missingSpecifications, verifiedByUserId, verifiedAt, null, true, null, null);
    }

    public ProductHsnMapping {
        Objects.requireNonNull(hsnCode, "hsnCode cannot be null");
        Objects.requireNonNull(matchReason, "matchReason cannot be null");
        if (status == null) {
            status = ClassificationStatus.PENDING;
        }
    }
}
