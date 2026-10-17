package com.shopbilling.service;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.Objects;

/**
 * Clock helper operating strictly in Indian Standard Time (Asia/Kolkata).
 * Injectable for testing to simulate arbitrary dates and times.
 * (PRD Section 8, Rule 2: Timestamps stored in IST).
 */
public interface IstClock {

    ZoneId ZONE_ID = ZoneId.of("Asia/Kolkata");

    /**
     * Returns an IstClock backed by the live system clock in Asia/Kolkata.
     */
    static IstClock system() {
        return new ClockImpl(Clock.system(ZONE_ID));
    }

    /**
     * Returns an IstClock fixed at the specified instant in Asia/Kolkata.
     */
    static IstClock fixed(Instant instant) {
        Objects.requireNonNull(instant, "Instant cannot be null");
        return new ClockImpl(Clock.fixed(instant, ZONE_ID));
    }

    /**
     * Returns an IstClock fixed at the specified local date-time in Asia/Kolkata.
     */
    static IstClock fixed(LocalDateTime localDateTime) {
        Objects.requireNonNull(localDateTime, "LocalDateTime cannot be null");
        Instant instant = localDateTime.atZone(ZONE_ID).toInstant();
        return fixed(instant);
    }

    /**
     * Returns an IstClock fixed at midnight (00:00:00) on the given date in Asia/Kolkata.
     */
    static IstClock fixedAtStartOfDay(LocalDate date) {
        Objects.requireNonNull(date, "LocalDate cannot be null");
        return fixed(date.atStartOfDay());
    }

    /**
     * The fixed zone (always Asia/Kolkata).
     */
    default ZoneId getZone() {
        return ZONE_ID;
    }

    /**
     * Current instant.
     */
    Instant instant();

    /**
     * Current date and time in Asia/Kolkata.
     */
    ZonedDateTime now();

    /**
     * Current calendar date in Asia/Kolkata.
     */
    LocalDate today();

    /**
     * Current local date-time in Asia/Kolkata.
     */
    LocalDateTime nowLocalDateTime();

    /**
     * Current local time in Asia/Kolkata.
     */
    LocalTime currentTime();

    /**
     * Returns the start of the specified day (00:00:00) in Asia/Kolkata.
     */
    default ZonedDateTime atStartOfDay(LocalDate date) {
        Objects.requireNonNull(date, "Date cannot be null");
        return date.atStartOfDay(ZONE_ID);
    }

    /**
     * Returns the end of the specified day (23:59:59.999999999) in Asia/Kolkata.
     */
    default ZonedDateTime atEndOfDay(LocalDate date) {
        Objects.requireNonNull(date, "Date cannot be null");
        return date.atTime(LocalTime.MAX).atZone(ZONE_ID);
    }

    /**
     * Default implementation wrapping java.time.Clock.
     */
    class ClockImpl implements IstClock {
        private final Clock clock;

        ClockImpl(Clock clock) {
            this.clock = clock;
        }

        @Override
        public Instant instant() {
            return clock.instant();
        }

        @Override
        public ZonedDateTime now() {
            return clock.instant().atZone(ZONE_ID);
        }

        @Override
        public LocalDate today() {
            return now().toLocalDate();
        }

        @Override
        public LocalDateTime nowLocalDateTime() {
            return now().toLocalDateTime();
        }

        @Override
        public LocalTime currentTime() {
            return now().toLocalTime();
        }
    }
}
