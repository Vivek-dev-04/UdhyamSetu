package com.shopbilling.model;

import java.util.Objects;

/**
 * Immutable record representing an individual stock ledger entry (PRD Section 7.4, FR-15).
 * Strictly append-only: movements are never edited or deleted.
 */
public record StockMovement(
        Long id,
        long variantId,
        StockMovementType movementType,
        Quantity quantity,           // Signed quantity: positive for in, negative for out
        String referenceType,       // 'PURCHASE', 'SALE', 'RETURN', 'DAMAGE', 'ADJUSTMENT'
        long referenceId,
        String reason,
        String createdAt,
        long createdByUserId
) {
    public StockMovement {
        Objects.requireNonNull(movementType, "Movement type cannot be null");
        Objects.requireNonNull(quantity, "Quantity cannot be null");
        Objects.requireNonNull(referenceType, "Reference type cannot be null");
        referenceType = referenceType.trim().toUpperCase();
    }
}
