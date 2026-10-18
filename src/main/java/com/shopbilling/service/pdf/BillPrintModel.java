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
        String notes
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

    public record ShopInfo(
            String name,
            String tagline,
            String address,
            String phone,
            String gstin
    ) {
        public static ShopInfo defaultShop() {
            return new ShopInfo(
                    "SHREE GANESH ELECTRICALS",
                    "Dealers in Havells, Veto, Polycab & Quality Electrical Goods",
                    "Station Road, Main Market, Opp. Nagar Palika",
                    "+91 98765 43210 / 94250 12345",
                    "23AAAAA0000A1Z5"
            );
        }
    }

    public record CustomerInfo(
            String name,
            String phone,
            String address,
            CustomerType customerType
    ) {
        public static CustomerInfo walkIn() {
            return new CustomerInfo("Walk-in Customer", null, null, CustomerType.RETAIL);
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
            Money lineTotal
    ) {}

    public record BillPaymentInfo(
            PaymentMode mode,
            Money amount,
            String referenceNumber
    ) {}
}
