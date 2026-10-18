package com.shopbilling.model;

import java.util.Objects;

/**
 * Summary DTO of a supplier with outstanding payables for overview reports.
 */
public record SupplierPayableSummary(
        long supplierId,
        String supplierName,
        String phone,
        String contactPerson,
        Money currentPayable
) {
    public SupplierPayableSummary {
        Objects.requireNonNull(supplierName, "Supplier name cannot be null");
        Objects.requireNonNull(currentPayable, "Current payable cannot be null");
    }
}
