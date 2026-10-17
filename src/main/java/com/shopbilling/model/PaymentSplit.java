package com.shopbilling.model;

import com.shopbilling.service.ValidationException;

import java.util.List;
import java.util.Objects;

/**
 * Value object encapsulating multiple payment modes split for a single sale
 * (PRD Section 7.3, FR-11: Split payments e.g. 2000 UPI + 500 Cash).
 */
public final class PaymentSplit {

    private final List<PaymentInput> payments;

    public PaymentSplit(List<PaymentInput> payments) {
        Objects.requireNonNull(payments, "Payments list cannot be null");
        if (payments.isEmpty()) {
            throw new ValidationException("At least one payment method is required");
        }
        this.payments = List.copyOf(payments);
    }

    public static PaymentSplit of(PaymentInput... payments) {
        return new PaymentSplit(List.of(payments));
    }

    public static PaymentSplit of(List<PaymentInput> payments) {
        return new PaymentSplit(payments);
    }

    public static PaymentSplit single(PaymentMode mode, Money amount) {
        return new PaymentSplit(List.of(PaymentInput.of(mode, amount)));
    }

    public static PaymentSplit single(PaymentMode mode, Money amount, String ref) {
        return new PaymentSplit(List.of(PaymentInput.of(mode, amount, ref)));
    }

    public List<PaymentInput> getPayments() {
        return payments;
    }

    /**
     * Total amount summed across all split payment methods.
     */
    public Money total() {
        Money sum = Money.ZERO;
        for (PaymentInput p : payments) {
            sum = sum.plus(p.amount());
        }
        return sum;
    }

    public Money amountForMode(PaymentMode mode) {
        Objects.requireNonNull(mode, "PaymentMode cannot be null");
        Money sum = Money.ZERO;
        for (PaymentInput p : payments) {
            if (p.mode() == mode) {
                sum = sum.plus(p.amount());
            }
        }
        return sum;
    }

    public Money cashTotal() {
        return amountForMode(PaymentMode.CASH);
    }

    public Money upiTotal() {
        return amountForMode(PaymentMode.UPI);
    }

    public Money cardTotal() {
        return amountForMode(PaymentMode.CARD);
    }

    public Money creditTotal() {
        return amountForMode(PaymentMode.CREDIT);
    }

    public boolean hasCredit() {
        return creditTotal().isPositive();
    }

    /**
     * Validates that the sum of split payments exactly matches the sale total.
     * Throws ValidationException with the exact discrepancy if not matching.
     */
    public void validateAgainstTotal(Money saleTotal) {
        Objects.requireNonNull(saleTotal, "Sale total cannot be null");
        Money paymentTotal = total();

        if (paymentTotal.compareTo(saleTotal) != 0) {
            Money diff = paymentTotal.minus(saleTotal).abs();
            if (paymentTotal.isLessThan(saleTotal)) {
                throw new ValidationException(String.format(
                        "Payment under-allocated: Total payments %s do not cover sale total %s (short by %s)",
                        paymentTotal.toFormattedString(), saleTotal.toFormattedString(), diff.toFormattedString()
                ));
            } else {
                throw new ValidationException(String.format(
                        "Payment over-allocated: Total payments %s exceed sale total %s (excess by %s)",
                        paymentTotal.toFormattedString(), saleTotal.toFormattedString(), diff.toFormattedString()
                ));
            }
        }
    }
}
