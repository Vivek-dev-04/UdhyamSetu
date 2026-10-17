package com.shopbilling.model;

import com.shopbilling.service.ValidationException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class QuantityTest {

    @Test
    @DisplayName("Should create and scale quantities accurately (scale 3)")
    void shouldHandleScaling() {
        Quantity qWhole = Quantity.of(5);
        assertEquals(5000, qWhole.getScaledValue());
        assertEquals("5", qWhole.toDisplayString());
        assertFalse(qWhole.hasDecimals());

        Quantity qDecimal = Quantity.parse("2.5");
        assertEquals(2500, qDecimal.getScaledValue());
        assertEquals("2.5", qDecimal.toDisplayString());
        assertTrue(qDecimal.hasDecimals());

        Quantity qMilli = Quantity.parse("0.125");
        assertEquals(125, qMilli.getScaledValue());
        assertEquals("0.125", qMilli.toDisplayString());
        assertTrue(qMilli.hasDecimals());
    }

    @ParameterizedTest
    @CsvSource({
            "'1', 1000, '1'",
            "'2.5', 2500, '2.5'",
            "'0.750', 750, '0.75'",
            "'10.000', 10000, '10'"
    })
    @DisplayName("Should parse and display quantities correctly")
    void shouldParseQuantities(String input, long expectedScaled, String expectedDisplay) {
        Quantity q = Quantity.parse(input);
        assertEquals(expectedScaled, q.getScaledValue());
        assertEquals(expectedDisplay, q.toDisplayString());
    }

    @Test
    @DisplayName("Should reject more than 3 decimal places without rounding")
    void shouldRejectExcessiveDecimals() {
        assertThrows(ArithmeticException.class, () -> Quantity.of(new BigDecimal("1.2345")));
    }

    @Test
    @DisplayName("Should validate against Unit decimal restrictions")
    void shouldValidateAgainstUnit() {
        Unit piece = new Unit("PIECE", "Piece", false);
        Unit metre = new Unit("METRE", "Metre", true);

        Quantity whole = Quantity.of(3);
        Quantity fractional = Quantity.parse("3.5");

        // Whole quantity valid for both
        assertDoesNotThrow(() -> whole.validateForUnit(piece));
        assertDoesNotThrow(() -> whole.validateForUnit(metre));

        // Fractional quantity valid for metre but invalid for piece
        assertDoesNotThrow(() -> fractional.validateForUnit(metre));
        assertThrows(ValidationException.class, () -> fractional.validateForUnit(piece));
    }

    @Test
    @DisplayName("Should perform arithmetic correctly")
    void shouldPerformArithmetic() {
        Quantity q1 = Quantity.parse("1.5");
        Quantity q2 = Quantity.parse("2.25");

        Quantity sum = q1.plus(q2);
        assertEquals(3750, sum.getScaledValue());
        assertEquals("3.75", sum.toDisplayString());

        Quantity diff = q2.minus(q1);
        assertEquals(750, diff.getScaledValue());
        assertEquals("0.75", diff.toDisplayString());
    }
}
