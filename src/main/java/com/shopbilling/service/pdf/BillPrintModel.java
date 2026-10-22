package com.shopbilling.service.pdf;

import com.shopbilling.model.CustomerType;
import com.shopbilling.model.Money;
import com.shopbilling.model.PaymentMode;
import com.shopbilling.model.Quantity;

import java.time.LocalDate;
import java.util.List;
import java.util.Objects;

/**
 * Self-contained DTO containing all data needed to render a formal A4 or thermal bill document.
 * Supports both retail cash memo and B2B firm Tax Invoice with HSN breakdown and payment QR code.
 */
public record BillPrintModel(
        ShopInfo shop,
        String billNumber,
        LocalDate billDate,
        String financialYear,
        CustomerInfo customer,
        List<BillLineItem> items,
        Money subtotal,
        Money discount,
        Money totalAmount,
        String amountInWords,
        List<BillPaymentInfo> payments,
        String cashierName,
        String notes,
        String deliveryNote,
        String dispatchedThrough,
        String destination,
        String upiQrPayload
) {
    public BillPrintModel {
        Objects.requireNonNull(shop, "ShopInfo cannot be null");
        Objects.requireNonNull(billNumber, "BillNumber cannot be null");
        Objects.requireNonNull(billDate, "BillDate cannot be null");
        Objects.requireNonNull(financialYear, "FinancialYear cannot be null");
        Objects.requireNonNull(items, "Items cannot be null");
        Objects.requireNonNull(subtotal, "Subtotal cannot be null");
        Objects.requireNonNull(discount, "Discount cannot be null");
        Objects.requireNonNull(totalAmount, "TotalAmount cannot be null");
        Objects.requireNonNull(payments, "Payments cannot be null");
    }

    /**
     * Backward-compatible 13-parameter constructor for existing tests and callers.
     */
    public BillPrintModel(
            ShopInfo shop,
            String billNumber,
            LocalDate billDate,
            String financialYear,
            CustomerInfo customer,
            List<BillLineItem> items,
            Money subtotal,
            Money discount,
            Money totalAmount,
            String amountInWords,
            List<BillPaymentInfo> payments,
            String cashierName,
            String notes
    ) {
        this(shop, billNumber, billDate, financialYear, customer, items, subtotal, discount, totalAmount,
             amountInWords, payments, cashierName, notes, null, "Hand Delivery", "Local", null);
    }

    public record ShopInfo(
            String name,
            String tagline,
            String address,
            String phone,
            String gstin,
            String email,
            String stateName,
            String stateCode,
            String pan,
            String bankName,
            String accountNo,
            String ifsc,
            String branch,
            String upiId
    ) {
        /**
         * Backward-compatible 5-parameter constructor for existing code and tests.
         */
        public ShopInfo(String name, String tagline, String address, String phone, String gstin) {
            this(name, tagline, address, phone, gstin,
                 "Mecombines@rediffmail.Com",
                 "Rajasthan",
                 "08",
                 (gstin != null && gstin.length() >= 12 ? gstin.substring(2, 12) : "AACFM6417B"),
                 "PUNJAB NATIONAL BANK",
                 "00615010000080",
                 "PUNB0006110",
                 "M.I. ROAD - JAIPUR",
                 (phone != null && !phone.isBlank() ? phone.replaceAll("[^0-9]", "") + "@upi" : "9829053822@upi"));
        }

        public static ShopInfo defaultShop() {
            return new ShopInfo(
                    "MAHESHWARI ELECTRICALS - (2025-27)",
                    "Dealers in Havells, Veto, Polycab & Quality Electrical Goods",
                    "Chamaliwala Market, Opp. G.P.O., M.I. Road, Jaipur (RAJASTHAN)",
                    "0141-2366620, 2371211 / MOB- 9829053822",
                    "08AACFM6417B1ZN"
            );
        }
    }

    public record CustomerInfo(
            String name,
            String phone,
            String address,
            CustomerType customerType,
            String gstin,
            String stateName,
            String stateCode
    ) {
        /**
         * Backward-compatible 4-parameter constructor for existing code and tests.
         */
        public CustomerInfo(String name, String phone, String address, CustomerType customerType) {
            this(name, phone, address, customerType, null, "Rajasthan", "08");
        }

        public static CustomerInfo walkIn() {
            return new CustomerInfo("Walk-in Customer", null, null, CustomerType.RETAIL, null, "Rajasthan", "08");
        }

        public boolean isFirm() {
            return gstin != null && !gstin.isBlank();
        }
    }

    public record BillLineItem(
            int serialNo,
            String productName,
            String variantName,
            String brandName,
            String unitCode,
            Quantity quantity,
            Money unitPrice,
            Money lineDiscount,
            Money lineTotal,
            String hsnSac,
            double gstRatePercent
    ) {
        /**
         * Backward-compatible 9-parameter constructor for existing code and tests.
         */
        public BillLineItem(
                int serialNo,
                String productName,
                String variantName,
                String brandName,
                String unitCode,
                Quantity quantity,
                Money unitPrice,
                Money lineDiscount,
                Money lineTotal
        ) {
            this(serialNo, productName, variantName, brandName, unitCode, quantity, unitPrice, lineDiscount, lineTotal, "85446020", 18.0);
        }
    }

    public record BillPaymentInfo(
            PaymentMode mode,
            Money amount,
            String referenceNumber
    ) {}
}
