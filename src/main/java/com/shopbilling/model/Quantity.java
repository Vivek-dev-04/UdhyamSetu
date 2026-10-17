package com.shopbilling.model;

import com.shopbilling.service.ValidationException;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Objects;

/**
 * Immutable value type representing a physical quantity with up to 3 decimal places
 * (stored as integer thousandths, scale 3) to prevent floating point inaccuracies (PRD FR-6).
 * 
 * Examples:
 * - 1 piece = 1000 scaled
 * - 2.5 metres = 2500 scaled
 * - 0.75 metre = 750 scaled
 */
public final class Quantity implements Comparable<Quantity> {

    public static final Quantity ZERO = new Quantity(0L);
    public static final Quantity ONE = new Quantity(1000L);
    public static final int SCALE = 3;
    private static final long SCALE_FACTOR = 1000L;

    private final long scaledValue;

    private Quantity(long scaledValue) {
        this.scaledValue = scaledValue;
    }

    public static Quantity ofScaled(long scaledValue) {
        if (scaledValue == 0L) {
            return ZERO;
        }
        return new Quantity(scaledValue);
    }

    public static Quantity of(long wholeUnits) {
        return ofScaled(Math.multiplyExact(wholeUnits, SCALE_FACTOR));
    }

    public static Quantity of(BigDecimal decimalValue) {
        Objects.requireNonNull(decimalValue, "Quantity decimal value cannot be null");
        BigDecimal scaled = decimalValue.setScale(SCALE, RoundingMode.UNNECESSARY);
        long value = scaled.movePointRight(SCALE).longValueExact();
        return ofScaled(value);
    }

    public static Quantity of(BigDecimal decimalValue, RoundingMode roundingMode) {
        Objects.requireNonNull(decimalValue, "Quantity decimal value cannot be null");
        Objects.requireNonNull(roundingMode, "RoundingMode cannot be null");
        BigDecimal scaled = decimalValue.setScale(SCALE, roundingMode);
        long value = scaled.movePointRight(SCALE).longValueExact();
        return ofScaled(value);
    }

    public static Quantity parse(String text) {
        Objects.requireNonNull(text, "Text cannot be null");
        String trimmed = text.trim();
        if (trimmed.isEmpty()) {
            throw new IllegalArgumentException("Cannot parse blank string as Quantity");
        }
        return of(new BigDecimal(trimmed));
    }

    public long getScaledValue() {
        return scaledValue;
    }

    public BigDecimal toBigDecimal() {
        return BigDecimal.valueOf(scaledValue, SCALE).stripTrailingZeros();
    }

    public String toDisplayString() {
        BigDecimal bd = BigDecimal.valueOf(scaledValue, SCALE);
        // If whole number, format without trailing .000
        if (scaledValue % SCALE_FACTOR == 0) {
            return String.valueOf(scaledValue / SCALE_FACTOR);
        }
        return bd.stripTrailingZeros().toPlainString();
    }

    public Quantity plus(Quantity other) {
        Objects.requireNonNull(other, "Other quantity cannot be null");
        return ofScaled(Math.addExact(this.scaledValue, other.scaledValue));
    }

    public Quantity minus(Quantity other) {
        Objects.requireNonNull(other, "Other quantity cannot be null");
        return ofScaled(Math.subtractExact(this.scaledValue, other.scaledValue));
    }

    public Quantity negate() {
        return ofScaled(Math.negateExact(this.scaledValue));
    }

    public boolean isPositive() {
        return scaledValue > 0;
    }

    public boolean isNegative() {
        return scaledValue < 0;
    }

    public boolean isZero() {
        return scaledValue == 0;
    }

    public boolean hasDecimals() {
        return scaledValue % SCALE_FACTOR != 0;
    }

    /**
     * Validates that if the unit does not allow decimals (e.g. PIECE),
     * the quantity is an exact whole integer.
     */
    public void validateForUnit(Unit unit) {
        Objects.requireNonNull(unit, "Unit cannot be null");
        if (!unit.allowsDecimal() && hasDecimals()) {
            throw new ValidationException("Unit '" + unit.name() + "' does not allow fractional quantities: " + toDisplayString());
        }
    }

    @Override
    public int compareTo(Quantity o) {
        Objects.requireNonNull(o, "Quantity cannot be null");
        return Long.compare(this.scaledValue, o.scaledValue);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof Quantity quantity)) return false;
        return scaledValue == quantity.scaledValue;
    }

    @Override
    public int hashCode() {
        return Long.hashCode(scaledValue);
    }

    @Override
    public String toString() {
        return toDisplayString();
    }
}
