package com.shopbilling.model;

import java.time.LocalDate;

/**
 * Filter criteria for querying and generating sales reports (PRD Section 7.8, FR-33, FR-34).
 * Supports combinable criteria: date range, sale mode, payment mode, brand, category, and customer type.
 */
public record SalesReportFilter(
        LocalDate dateFrom,
        LocalDate dateTo,
        SaleMode saleMode,
        PaymentMode paymentMode,
        Long brandId,
        Long categoryId,
        CustomerType customerType,
        Long customerId
) {
    public static SalesReportFilter forDateRange(LocalDate from, LocalDate to) {
        return new SalesReportFilter(from, to, null, null, null, null, null, null);
    }

    public static SalesReportFilter all() {
        return new SalesReportFilter(null, null, null, null, null, null, null, null);
    }

    public SalesReportFilter withSaleMode(SaleMode mode) {
        return new SalesReportFilter(dateFrom, dateTo, mode, paymentMode, brandId, categoryId, customerType, customerId);
    }

    public SalesReportFilter withPaymentMode(PaymentMode mode) {
        return new SalesReportFilter(dateFrom, dateTo, saleMode, mode, brandId, categoryId, customerType, customerId);
    }

    public SalesReportFilter withBrandId(Long bId) {
        return new SalesReportFilter(dateFrom, dateTo, saleMode, paymentMode, bId, categoryId, customerType, customerId);
    }

    public SalesReportFilter withCategoryId(Long cId) {
        return new SalesReportFilter(dateFrom, dateTo, saleMode, paymentMode, brandId, cId, customerType, customerId);
    }

    public SalesReportFilter withCustomerType(CustomerType cType) {
        return new SalesReportFilter(dateFrom, dateTo, saleMode, paymentMode, brandId, categoryId, cType, customerId);
    }
}
