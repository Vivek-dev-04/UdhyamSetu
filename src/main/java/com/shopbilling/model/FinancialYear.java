package com.shopbilling.model;

import com.shopbilling.service.IstClock;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.Month;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Represents an Indian Financial Year (1 April to 31 March) per PRD Section 8, Rule 2 & 5.
 * 
 * Examples:
 * - FY 2024-25 starts 2024-04-01 and ends 2025-03-31.
 * - Date 2025-03-31 belongs to FY 2024-25 (Q4).
 * - Date 2025-04-01 belongs to FY 2025-26 (Q1).
 */
public final class FinancialYear implements Comparable<FinancialYear> {

    private static final Pattern FY_PATTERN = Pattern.compile("^(?:FY)?\\s*(\\d{4})\\s*[-/]\\s*(\\d{2}|\\d{4})$", Pattern.CASE_INSENSITIVE);

    private final int startYear;
    private final int endYear;

    private FinancialYear(int startYear) {
        this.startYear = startYear;
        this.endYear = startYear + 1;
    }

    /**
     * Creates a FinancialYear starting on 1 April of startYear and ending on 31 March of startYear + 1.
     */
    public static FinancialYear of(int startYear) {
        return new FinancialYear(startYear);
    }

    /**
     * Determines the financial year containing the given calendar date.
     */
    public static FinancialYear ofDate(LocalDate date) {
        Objects.requireNonNull(date, "Date cannot be null");
        int year = date.getYear();
        // Indian FY starts on April 1
        if (date.getMonthValue() >= Month.APRIL.getValue()) {
            return new FinancialYear(year);
        } else {
            return new FinancialYear(year - 1);
        }
    }

    /**
     * Determines the financial year containing the given instant in Asia/Kolkata.
     */
    public static FinancialYear ofInstant(Instant instant) {
        return ofInstant(instant, IstClock.ZONE_ID);
    }

    /**
     * Determines the financial year containing the given instant in the specified zone.
     */
    public static FinancialYear ofInstant(Instant instant, ZoneId zoneId) {
        Objects.requireNonNull(instant, "Instant cannot be null");
        Objects.requireNonNull(zoneId, "ZoneId cannot be null");
        LocalDate date = instant.atZone(zoneId).toLocalDate();
        return ofDate(date);
    }

    /**
     * Parses financial year strings such as "2024-25", "2024-2025", "FY2024-25", "FY 2024-25".
     */
    public static FinancialYear parse(String text) {
        Objects.requireNonNull(text, "Text cannot be null");
        Matcher matcher = FY_PATTERN.matcher(text.trim());
        if (!matcher.matches()) {
            throw new IllegalArgumentException("Invalid financial year format: '" + text + "'. Expected 'YYYY-YY' or 'YYYY-YYYY'");
        }
        int start = Integer.parseInt(matcher.group(1));
        String secondPart = matcher.group(2);
        int expectedEnd = start + 1;
        int parsedEnd;
        if (secondPart.length() == 2) {
            parsedEnd = (start / 100) * 100 + Integer.parseInt(secondPart);
            // Handle century rollover, e.g. 1999-00 -> 2000
            if (parsedEnd < start) {
                parsedEnd += 100;
            }
        } else {
            parsedEnd = Integer.parseInt(secondPart);
        }

        if (parsedEnd != expectedEnd) {
            throw new IllegalArgumentException("Invalid financial year range: '" + text + "'. End year must be start year + 1 (" + expectedEnd + ")");
        }

        return of(start);
    }

    public int getStartYear() {
        return startYear;
    }

    public int getEndYear() {
        return endYear;
    }

    public LocalDate getStartDate() {
        return LocalDate.of(startYear, Month.APRIL, 1);
    }

    public LocalDate getEndDate() {
        return LocalDate.of(endYear, Month.MARCH, 31);
    }

    public ZonedDateTime getStartDateTime() {
        return getStartDate().atStartOfDay(IstClock.ZONE_ID);
    }

    public ZonedDateTime getEndDateTime() {
        return getEndDate().atTime(LocalTime.MAX).atZone(IstClock.ZONE_ID);
    }

    public Instant getStartInstant() {
        return getStartDateTime().toInstant();
    }

    public Instant getEndInstant() {
        return getEndDateTime().toInstant();
    }

    /**
     * Returns standard short label, e.g. "2024-25".
     */
    public String getLabel() {
        return String.format("%04d-%02d", startYear, endYear % 100);
    }

    /**
     * Returns full label, e.g. "2024-2025".
     */
    public String getFullLabel() {
        return String.format("%04d-%04d", startYear, endYear);
    }

    /**
     * Returns prefix for bill counters and receipts, e.g. "FY24-25".
     */
    public String getBillPrefix() {
        return String.format("FY%02d-%02d", startYear % 100, endYear % 100);
    }

    public Quarter getQuarterOf(LocalDate date) {
        if (!contains(date)) {
            throw new IllegalArgumentException("Date " + date + " is not within Financial Year " + getLabel());
        }
        return Quarter.ofMonth(date.getMonth());
    }

    public LocalDate getQuarterStartDate(Quarter quarter) {
        Objects.requireNonNull(quarter, "Quarter cannot be null");
        int year = (quarter == Quarter.Q4) ? endYear : startYear;
        return LocalDate.of(year, quarter.getStartMonth(), 1);
    }

    public LocalDate getQuarterEndDate(Quarter quarter) {
        Objects.requireNonNull(quarter, "Quarter cannot be null");
        int year = (quarter == Quarter.Q4) ? endYear : startYear;
        Month endMonth = quarter.getEndMonth();
        return LocalDate.of(year, endMonth, endMonth.length(LocalDate.of(year, endMonth, 1).isLeapYear()));
    }

    public ZonedDateTime getQuarterStartDateTime(Quarter quarter) {
        return getQuarterStartDate(quarter).atStartOfDay(IstClock.ZONE_ID);
    }

    public ZonedDateTime getQuarterEndDateTime(Quarter quarter) {
        return getQuarterEndDate(quarter).atTime(LocalTime.MAX).atZone(IstClock.ZONE_ID);
    }

    public boolean contains(LocalDate date) {
        Objects.requireNonNull(date, "Date cannot be null");
        return !date.isBefore(getStartDate()) && !date.isAfter(getEndDate());
    }

    public boolean contains(ZonedDateTime zonedDateTime) {
        Objects.requireNonNull(zonedDateTime, "ZonedDateTime cannot be null");
        LocalDate date = zonedDateTime.withZoneSameInstant(IstClock.ZONE_ID).toLocalDate();
        return contains(date);
    }

    public boolean contains(Instant instant) {
        Objects.requireNonNull(instant, "Instant cannot be null");
        LocalDate date = instant.atZone(IstClock.ZONE_ID).toLocalDate();
        return contains(date);
    }

    public FinancialYear next() {
        return of(startYear + 1);
    }

    public FinancialYear previous() {
        return of(startYear - 1);
    }

    @Override
    public int compareTo(FinancialYear o) {
        return Integer.compare(this.startYear, o.startYear);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof FinancialYear that)) return false;
        return startYear == that.startYear;
    }

    @Override
    public int hashCode() {
        return Integer.hashCode(startYear);
    }

    @Override
    public String toString() {
        return getLabel();
    }
}
