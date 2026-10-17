package com.shopbilling.model;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.util.Locale;
import java.util.Objects;

/**
 * Immutable value type representing money strictly in integer paise.
 * Floats and doubles are strictly forbidden to prevent precision errors
 * (PRD Section 8, Rule 1).
 * 
 * 1 Rupee = 100 Paise.
 */
public final class Money implements Comparable<Money> {

    public static final Money ZERO = new Money(0L);

    private final long paise;

    private Money(long paise) {
        this.paise = paise;
    }

    /**
     * Creates Money from an exact amount of integer paise.
     */
    public static Money ofPaise(long paise) {
        if (paise == 0L) {
            return ZERO;
        }
        return new Money(paise);
    }

    /**
     * Creates Money from whole rupees.
     */
    public static Money ofRupees(long rupees) {
        return ofPaise(Math.multiplyExact(rupees, 100L));
    }

    /**
     * Creates Money from a BigDecimal rupee amount with scale <= 2.
     * Throws ArithmeticException if the scale has more than 2 decimal places.
     */
    public static Money ofRupees(BigDecimal rupees) {
        Objects.requireNonNull(rupees, "Rupees amount cannot be null");
        BigDecimal scaled = rupees.setScale(2, RoundingMode.UNNECESSARY);
        long paiseValue = scaled.movePointRight(2).longValueExact();
        return ofPaise(paiseValue);
    }

    /**
     * Creates Money from a BigDecimal rupee amount, rounding to 2 decimal places
     * with the specified RoundingMode.
     */
    public static Money ofRupees(BigDecimal rupees, RoundingMode roundingMode) {
        Objects.requireNonNull(rupees, "Rupees amount cannot be null");
        Objects.requireNonNull(roundingMode, "RoundingMode cannot be null");
        BigDecimal scaled = rupees.setScale(2, roundingMode);
        long paiseValue = scaled.movePointRight(2).longValueExact();
        return ofPaise(paiseValue);
    }

    /**
     * Parses a string representation of money (e.g. "123.45", "123", "-45.50", "₹1,234.50").
     */
    public static Money parse(String text) {
        Objects.requireNonNull(text, "Text cannot be null");
        String sanitized = text.trim()
                .replace("₹", "")
                .replace(",", "")
                .trim();
        if (sanitized.isEmpty()) {
            throw new IllegalArgumentException("Cannot parse empty string as Money");
        }
        return ofRupees(new BigDecimal(sanitized));
    }

    public long getPaise() {
        return paise;
    }

    /**
     * Returns the exact rupee value as a BigDecimal with scale 2.
     */
    public BigDecimal toRupeesBigDecimal() {
        return BigDecimal.valueOf(paise, 2);
    }

    public Money plus(Money other) {
        Objects.requireNonNull(other, "Other money cannot be null");
        return ofPaise(Math.addExact(this.paise, other.paise));
    }

    public Money minus(Money other) {
        Objects.requireNonNull(other, "Other money cannot be null");
        return ofPaise(Math.subtractExact(this.paise, other.paise));
    }

    public Money times(long multiplier) {
        return ofPaise(Math.multiplyExact(this.paise, multiplier));
    }

    /**
     * Multiplies money by a decimal multiplier (such as tax rate or percentage factor)
     * using the specified rounding mode.
     */
    public Money times(BigDecimal multiplier, RoundingMode roundingMode) {
        Objects.requireNonNull(multiplier, "Multiplier cannot be null");
        Objects.requireNonNull(roundingMode, "RoundingMode cannot be null");
        BigDecimal currentPaise = BigDecimal.valueOf(this.paise);
        BigDecimal product = currentPaise.multiply(multiplier).setScale(0, roundingMode);
        return ofPaise(product.longValueExact());
    }

    public Money negate() {
        return ofPaise(Math.negateExact(this.paise));
    }

    public Money abs() {
        return paise < 0 ? negate() : this;
    }

    public boolean isPositive() {
        return paise > 0;
    }

    public boolean isNegative() {
        return paise < 0;
    }

    public boolean isZero() {
        return paise == 0;
    }

    public boolean isGreaterThan(Money other) {
        return compareTo(other) > 0;
    }

    public boolean isLessThan(Money other) {
        return compareTo(other) < 0;
    }

    public boolean isGreaterThanOrEqual(Money other) {
        return compareTo(other) >= 0;
    }

    public boolean isLessThanOrEqual(Money other) {
        return compareTo(other) <= 0;
    }

    /**
     * Formats to exact two-decimal rupee plain string (e.g. "123.45" or "-50.00").
     */
    public String toRupeesString() {
        return toRupeesBigDecimal().toPlainString();
    }

    /**
     * Formats as Indian currency string with Indian digit grouping (e.g. "₹1,23,456.78").
     */
    public String toFormattedString() {
        return formatIndianCurrency(toRupeesBigDecimal());
    }

    private static String formatIndianCurrency(BigDecimal amount) {
        boolean negative = amount.signum() < 0;
        BigDecimal absAmount = amount.abs().setScale(2, RoundingMode.UNNECESSARY);
        String plain = absAmount.toPlainString();
        int dotIndex = plain.indexOf('.');
        String integerPart = dotIndex >= 0 ? plain.substring(0, dotIndex) : plain;
        String decimalPart = dotIndex >= 0 ? plain.substring(dotIndex) : ".00";

        // Indian numbering grouping: last 3 digits, then groups of 2 digits
        StringBuilder sb = new StringBuilder();
        int len = integerPart.length();
        if (len <= 3) {
            sb.append(integerPart);
        } else {
            String last3 = integerPart.substring(len - 3);
            String rest = integerPart.substring(0, len - 3);
            StringBuilder restGrouped = new StringBuilder();
            int count = 0;
            for (int i = rest.length() - 1; i >= 0; i--) {
                restGrouped.insert(0, rest.charAt(i));
                count++;
                if (count % 2 == 0 && i > 0) {
                    restGrouped.insert(0, ',');
                }
            }
            sb.append(restGrouped).append(',').append(last3);
        }
        sb.append(decimalPart);

        String prefix = negative ? "-₹" : "₹";
        return prefix + sb.toString();
    }

    @Override
    public int compareTo(Money other) {
        Objects.requireNonNull(other, "Cannot compare Money with null");
        return Long.compare(this.paise, other.paise);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof Money money)) return false;
        return this.paise == money.paise;
    }

    @Override
    public int hashCode() {
        return Long.hashCode(paise);
    }

    @Override
    public String toString() {
        return toRupeesString();
    }
}
