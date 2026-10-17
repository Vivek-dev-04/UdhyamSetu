package com.shopbilling.model;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.math.BigDecimal;
import java.math.RoundingMode;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MoneyTest {

    @Test
    @DisplayName("Should create Money from paise and rupees correctly")
    void shouldCreateMoney() {
        Money m1 = Money.ofPaise(1050);
        assertEquals(1050, m1.getPaise());
        assertEquals("10.50", m1.toRupeesString());

        Money m2 = Money.ofRupees(150);
        assertEquals(15000, m2.getPaise());
        assertEquals("150.00", m2.toRupeesString());

        Money zero = Money.ofPaise(0);
        assertSameValue(Money.ZERO, zero);
        assertTrue(zero.isZero());
        assertFalse(zero.isPositive());
        assertFalse(zero.isNegative());
    }

    @Test
    @DisplayName("Should create Money from BigDecimal and enforce 2 decimal places")
    void shouldHandleBigDecimalCreation() {
        Money m = Money.ofRupees(new BigDecimal("99.99"));
        assertEquals(9999, m.getPaise());

        Money mWhole = Money.ofRupees(new BigDecimal("100"));
        assertEquals(10000, mWhole.getPaise());

        // Attempting to pass 3 decimal places without rounding mode must fail
        assertThrows(ArithmeticException.class, () ->
                Money.ofRupees(new BigDecimal("99.995"))
        );

        // With rounding mode
        Money roundedUp = Money.ofRupees(new BigDecimal("99.995"), RoundingMode.HALF_UP);
        assertEquals(10000, roundedUp.getPaise());

        Money roundedDown = Money.ofRupees(new BigDecimal("99.994"), RoundingMode.HALF_UP);
        assertEquals(9999, roundedDown.getPaise());
    }

    @ParameterizedTest
    @CsvSource({
            "'100', 10000",
            "'100.50', 10050",
            "'0.05', 5",
            "'₹1,234.50', 123450",
            "' -500.00 ', -50000"
    })
    @DisplayName("Should parse money strings accurately")
    void shouldParseString(String input, long expectedPaise) {
        Money parsed = Money.parse(input);
        assertEquals(expectedPaise, parsed.getPaise());
    }

    @Test
    @DisplayName("Should reject invalid money strings")
    void shouldRejectInvalidStrings() {
        assertThrows(IllegalArgumentException.class, () -> Money.parse(""));
        assertThrows(IllegalArgumentException.class, () -> Money.parse("   "));
        assertThrows(Exception.class, () -> Money.parse("abc"));
        assertThrows(ArithmeticException.class, () -> Money.parse("10.555"));
    }

    @Test
    @DisplayName("Should perform arithmetic correctly")
    void shouldPerformArithmetic() {
        Money a = Money.ofPaise(5025); // 50.25
        Money b = Money.ofPaise(1050); // 10.50

        assertEquals(Money.ofPaise(6075), a.plus(b));
        assertEquals(Money.ofPaise(3975), a.minus(b));
        assertEquals(Money.ofPaise(10050), a.times(2));
        assertEquals(Money.ofPaise(-5025), a.negate());
        assertEquals(Money.ofPaise(5025), a.negate().abs());
    }

    @Test
    @DisplayName("Should calculate percentage / tax with RoundingMode")
    void shouldMultiplyWithBigDecimal() {
        Money price = Money.ofRupees(100); // 100.00 (10000 paise)
        BigDecimal gstRate = new BigDecimal("0.18"); // 18% GST

        Money tax = price.times(gstRate, RoundingMode.HALF_UP);
        assertEquals(Money.ofPaise(1800), tax);
        assertEquals(Money.ofRupees(new BigDecimal("118.00")), price.plus(tax));

        // Odd fractional paise calculation
        Money odd = Money.ofPaise(105); // 1.05
        BigDecimal multiplier = new BigDecimal("0.333");
        Money result = odd.times(multiplier, RoundingMode.HALF_UP);
        // 105 * 0.333 = 34.965 -> rounds to 35 paise
        assertEquals(35, result.getPaise());
    }

    @Test
    @DisplayName("Should detect overflow safely with ArithmeticException")
    void shouldDetectOverflow() {
        Money huge = Money.ofPaise(Long.MAX_VALUE - 10);
        assertThrows(ArithmeticException.class, () -> huge.plus(Money.ofPaise(20)));
        assertThrows(ArithmeticException.class, () -> huge.times(2));
    }

    @Test
    @DisplayName("Should compare and sort Money objects correctly")
    void shouldCompareMoney() {
        Money m1 = Money.ofPaise(100);
        Money m2 = Money.ofPaise(200);
        Money m3 = Money.ofPaise(100);

        assertTrue(m1.isLessThan(m2));
        assertTrue(m2.isGreaterThan(m1));
        assertTrue(m1.isLessThanOrEqual(m3));
        assertTrue(m1.isGreaterThanOrEqual(m3));
        assertEquals(0, m1.compareTo(m3));
    }

    @Test
    @DisplayName("Should format currency with Indian numbering system")
    void shouldFormatIndianCurrency() {
        assertEquals("₹0.00", Money.ofPaise(0).toFormattedString());
        assertEquals("₹5.50", Money.ofPaise(550).toFormattedString());
        assertEquals("₹1,000.00", Money.ofRupees(1000).toFormattedString());
        assertEquals("₹10,000.00", Money.ofRupees(10000).toFormattedString());
        assertEquals("₹1,00,000.00", Money.ofRupees(100000).toFormattedString());
        assertEquals("₹12,34,567.89", Money.ofPaise(123456789).toFormattedString());
        assertEquals("-₹50.00", Money.ofPaise(-5000).toFormattedString());
    }

    @Test
    @DisplayName("Equals and hashCode contract")
    void shouldRespectEqualsAndHashCode() {
        Money a = Money.ofPaise(1234);
        Money b = Money.ofPaise(1234);
        Money c = Money.ofPaise(4321);

        assertEquals(a, b);
        assertEquals(a.hashCode(), b.hashCode());
        assertNotEquals(a, c);
        assertNotEquals(a, null);
        assertNotEquals(a, "12.34");
    }

    private void assertSameValue(Money expected, Money actual) {
        assertEquals(expected.getPaise(), actual.getPaise());
    }
}
