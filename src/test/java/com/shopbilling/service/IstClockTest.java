package com.shopbilling.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.Month;
import java.time.ZoneId;
import java.time.ZonedDateTime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class IstClockTest {

    private static final ZoneId IST_ZONE = ZoneId.of("Asia/Kolkata");

    @Test
    @DisplayName("System IstClock should always operate in Asia/Kolkata zone")
    void shouldOperateInKolkataZone() {
        IstClock clock = IstClock.system();
        assertEquals(IST_ZONE, clock.getZone());
        assertEquals(IST_ZONE, clock.now().getZone());
        assertNotNull(clock.today());
        assertNotNull(clock.currentTime());
    }

    @Test
    @DisplayName("Fixed IstClock should accurately reflect fixed instant in IST")
    void shouldSupportFixedInstant() {
        // 2026-03-31 18:30:00 UTC == 2026-04-01 00:00:00 IST (Indian FY rollover)
        Instant rolloverInstant = Instant.parse("2026-03-31T18:30:00Z");
        IstClock clock = IstClock.fixed(rolloverInstant);

        assertEquals(LocalDate.of(2026, Month.APRIL, 1), clock.today());
        assertEquals(LocalTime.MIDNIGHT, clock.currentTime());
        assertEquals(LocalDateTime.of(2026, Month.APRIL, 1, 0, 0, 0), clock.nowLocalDateTime());
        assertEquals(rolloverInstant, clock.instant());
    }

    @Test
    @DisplayName("Fixed IstClock from LocalDateTime should correctly convert to UTC instant")
    void shouldSupportFixedLocalDateTime() {
        LocalDateTime ldt = LocalDateTime.of(2026, Month.OCTOBER, 17, 15, 30, 0);
        IstClock clock = IstClock.fixed(ldt);

        assertEquals(LocalDate.of(2026, Month.OCTOBER, 17), clock.today());
        assertEquals(LocalTime.of(15, 30, 0), clock.currentTime());
        assertEquals(ldt, clock.nowLocalDateTime());
        // In UTC: 15:30 IST minus 5h30m = 10:00 UTC
        assertEquals(Instant.parse("2026-10-17T10:00:00Z"), clock.instant());
    }

    @Test
    @DisplayName("Fixed IstClock at start of day")
    void shouldSupportFixedAtStartOfDay() {
        LocalDate date = LocalDate.of(2025, Month.MARCH, 31);
        IstClock clock = IstClock.fixedAtStartOfDay(date);

        assertEquals(date, clock.today());
        assertEquals(LocalTime.MIDNIGHT, clock.currentTime());
        assertEquals(LocalDateTime.of(2025, Month.MARCH, 31, 0, 0, 0), clock.nowLocalDateTime());
    }

    @Test
    @DisplayName("Start and end of day calculations in IST")
    void shouldCalculateStartAndEndOfDay() {
        IstClock clock = IstClock.system();
        LocalDate date = LocalDate.of(2026, Month.APRIL, 1);

        ZonedDateTime start = clock.atStartOfDay(date);
        assertEquals(LocalDateTime.of(2026, Month.APRIL, 1, 0, 0, 0), start.toLocalDateTime());
        assertEquals(IST_ZONE, start.getZone());

        ZonedDateTime end = clock.atEndOfDay(date);
        assertEquals(LocalDateTime.of(2026, Month.APRIL, 1, 23, 59, 59, 999999999), end.toLocalDateTime());
        assertEquals(IST_ZONE, end.getZone());

        assertTrue(end.isAfter(start));
    }
}
