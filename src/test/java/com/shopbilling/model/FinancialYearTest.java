package com.shopbilling.model;

import com.shopbilling.service.IstClock;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.Month;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.temporal.ChronoUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FinancialYearTest {

    private static final ZoneId IST = IstClock.ZONE_ID;

    @Test
    @DisplayName("Boundary: 31 March 23:59:59.999999999 IST belongs to closing FY, Q4")
    void testBoundaryMarch31LastMoment() {
        LocalDateTime march31Night = LocalDateTime.of(2025, Month.MARCH, 31, 23, 59, 59, 999_999_999);
        ZonedDateTime zdt = march31Night.atZone(IST);
        Instant instant = zdt.toInstant();

        FinancialYear fy = FinancialYear.ofInstant(instant);
        assertEquals(2024, fy.getStartYear());
        assertEquals(2025, fy.getEndYear());
        assertEquals("2024-25", fy.getLabel());
        assertEquals("FY24-25", fy.getBillPrefix());
        assertEquals(Quarter.Q4, fy.getQuarterOf(zdt.toLocalDate()));
        assertTrue(fy.contains(zdt));
        assertTrue(fy.contains(instant));
    }

    @Test
    @DisplayName("Boundary: 1 April 00:00:00.000000000 IST belongs to new FY, Q1")
    void testBoundaryApril1FirstMoment() {
        LocalDateTime april1Midnight = LocalDateTime.of(2025, Month.APRIL, 1, 0, 0, 0, 0);
        ZonedDateTime zdt = april1Midnight.atZone(IST);
        Instant instant = zdt.toInstant();

        FinancialYear fy = FinancialYear.ofInstant(instant);
        assertEquals(2025, fy.getStartYear());
        assertEquals(2026, fy.getEndYear());
        assertEquals("2025-26", fy.getLabel());
        assertEquals("FY25-26", fy.getBillPrefix());
        assertEquals(Quarter.Q1, fy.getQuarterOf(zdt.toLocalDate()));
        assertTrue(fy.contains(zdt));
        assertTrue(fy.contains(instant));
    }

    @Test
    @DisplayName("Midnight IST vs UTC offset boundary (5h30m difference)")
    void testMidnightIstVsUtcOffset() {
        // 18:29:59 UTC on 31 March == 23:59:59 IST on 31 March (FY 2024-25)
        Instant beforeRolloverUtc = Instant.parse("2025-03-31T18:29:59Z");
        FinancialYear fyBefore = FinancialYear.ofInstant(beforeRolloverUtc);
        assertEquals("2024-25", fyBefore.getLabel());
        assertEquals(Quarter.Q4, fyBefore.getQuarterOf(beforeRolloverUtc.atZone(IST).toLocalDate()));

        // Exactly 18:30:00 UTC on 31 March == 00:00:00 IST on 1 April (FY 2025-26)
        Instant atRolloverUtc = Instant.parse("2025-03-31T18:30:00Z");
        FinancialYear fyAfter = FinancialYear.ofInstant(atRolloverUtc);
        assertEquals("2025-26", fyAfter.getLabel());
        assertEquals(Quarter.Q1, fyAfter.getQuarterOf(atRolloverUtc.atZone(IST).toLocalDate()));
    }

    @Test
    @DisplayName("Leap year handling: FY 2023-24 has 366 days and Feb 29 in Q4")
    void testLeapYearFinancialYear() {
        FinancialYear leapFy = FinancialYear.of(2023); // 2023-04-01 to 2024-03-31
        assertEquals("2023-24", leapFy.getLabel());

        long daysInLeapFy = ChronoUnit.DAYS.between(leapFy.getStartDate(), leapFy.getEndDate().plusDays(1));
        assertEquals(366, daysInLeapFy, "FY 2023-24 must have 366 days due to leap year");

        LocalDate feb29 = LocalDate.of(2024, Month.FEBRUARY, 29);
        assertTrue(leapFy.contains(feb29));
        assertEquals(Quarter.Q4, leapFy.getQuarterOf(feb29));

        LocalDate q4Start = leapFy.getQuarterStartDate(Quarter.Q4);
        LocalDate q4End = leapFy.getQuarterEndDate(Quarter.Q4);
        assertEquals(LocalDate.of(2024, Month.JANUARY, 1), q4Start);
        assertEquals(LocalDate.of(2024, Month.MARCH, 31), q4End);
        assertEquals(91, ChronoUnit.DAYS.between(q4Start, q4End.plusDays(1)), "Q4 in leap year must have 91 days");
    }

    @Test
    @DisplayName("Non-leap year handling: FY 2024-25 has 365 days and Feb 28 in Q4")
    void testNonLeapYearFinancialYear() {
        FinancialYear normalFy = FinancialYear.of(2024); // 2024-04-01 to 2025-03-31
        assertEquals("2024-25", normalFy.getLabel());

        long daysInNormalFy = ChronoUnit.DAYS.between(normalFy.getStartDate(), normalFy.getEndDate().plusDays(1));
        assertEquals(365, daysInNormalFy, "FY 2024-25 must have 365 days");

        LocalDate q4Start = normalFy.getQuarterStartDate(Quarter.Q4);
        LocalDate q4End = normalFy.getQuarterEndDate(Quarter.Q4);
        assertEquals(LocalDate.of(2025, Month.JANUARY, 1), q4Start);
        assertEquals(LocalDate.of(2025, Month.MARCH, 31), q4End);
        assertEquals(90, ChronoUnit.DAYS.between(q4Start, q4End.plusDays(1)), "Q4 in normal year must have 90 days");
    }

    @ParameterizedTest
    @CsvSource({
            "2024-04-01, Q1",
            "2024-06-30, Q1",
            "2024-07-01, Q2",
            "2024-09-30, Q2",
            "2024-10-01, Q3",
            "2024-12-31, Q3",
            "2025-01-01, Q4",
            "2025-03-31, Q4"
    })
    @DisplayName("Quarter assignments within FY 2024-25")
    void testQuarterDateRanges(String dateStr, Quarter expectedQuarter) {
        LocalDate date = LocalDate.parse(dateStr);
        FinancialYear fy = FinancialYear.ofDate(date);
        assertEquals("2024-25", fy.getLabel());
        assertEquals(expectedQuarter, fy.getQuarterOf(date));
    }

    @ParameterizedTest
    @CsvSource({
            "'2024-25', 2024, 2025",
            "'2024-2025', 2024, 2025",
            "'FY2024-25', 2024, 2025",
            "'FY 2024-25', 2024, 2025",
            "'1999-00', 1999, 2000"
    })
    @DisplayName("Parse valid financial year string formats")
    void testParseFinancialYear(String text, int expectedStart, int expectedEnd) {
        FinancialYear fy = FinancialYear.parse(text);
        assertEquals(expectedStart, fy.getStartYear());
        assertEquals(expectedEnd, fy.getEndYear());
    }

    @Test
    @DisplayName("Parse invalid financial year formats should throw IllegalArgumentException")
    void testParseInvalidFinancialYear() {
        assertThrows(IllegalArgumentException.class, () -> FinancialYear.parse("2024"));
        assertThrows(IllegalArgumentException.class, () -> FinancialYear.parse("2024-26")); // Mismatching years
        assertThrows(IllegalArgumentException.class, () -> FinancialYear.parse("2024-2026"));
        assertThrows(IllegalArgumentException.class, () -> FinancialYear.parse("invalid"));
        assertThrows(IllegalArgumentException.class, () -> FinancialYear.parse(""));
    }

    @Test
    @DisplayName("Next and previous financial years")
    void testNextAndPrevious() {
        FinancialYear fy = FinancialYear.of(2024);
        FinancialYear next = fy.next();
        FinancialYear prev = fy.previous();

        assertEquals("2025-26", next.getLabel());
        assertEquals("2023-24", prev.getLabel());
        assertTrue(next.compareTo(fy) > 0);
        assertTrue(prev.compareTo(fy) < 0);
    }

    @Test
    @DisplayName("Check if dates belong to the financial year")
    void testContainsDate() {
        FinancialYear fy = FinancialYear.of(2024); // 2024-04-01 to 2025-03-31

        assertFalse(fy.contains(LocalDate.of(2024, Month.MARCH, 31)));
        assertTrue(fy.contains(LocalDate.of(2024, Month.APRIL, 1)));
        assertTrue(fy.contains(LocalDate.of(2025, Month.MARCH, 31)));
        assertFalse(fy.contains(LocalDate.of(2025, Month.APRIL, 1)));

        assertThrows(IllegalArgumentException.class, () ->
                fy.getQuarterOf(LocalDate.of(2024, Month.MARCH, 31))
        );
    }

    @Test
    @DisplayName("Equals and hashCode contract")
    void testEqualsAndHashCode() {
        FinancialYear fy1 = FinancialYear.of(2024);
        FinancialYear fy2 = FinancialYear.parse("2024-25");
        FinancialYear fy3 = FinancialYear.of(2025);

        assertEquals(fy1, fy2);
        assertEquals(fy1.hashCode(), fy2.hashCode());
        assertNotEquals(fy1, fy3);
        assertNotEquals(fy1, null);
    }
}
