package com.shopbilling.model;

import java.time.Month;
import java.util.Objects;

/**
 * Quarters of the Indian Financial Year (PRD FR-34 & Hard Rule 2).
 * 
 * - Q1: 1 April to 30 June
 * - Q2: 1 July to 30 September
 * - Q3: 1 October to 31 December
 * - Q4: 1 January to 31 March
 */
public enum Quarter {
    Q1("Q1 (Apr - Jun)", Month.APRIL, Month.JUNE),
    Q2("Q2 (Jul - Sep)", Month.JULY, Month.SEPTEMBER),
    Q3("Q3 (Oct - Dec)", Month.OCTOBER, Month.DECEMBER),
    Q4("Q4 (Jan - Mar)", Month.JANUARY, Month.MARCH);

    private final String displayLabel;
    private final Month startMonth;
    private final Month endMonth;

    Quarter(String displayLabel, Month startMonth, Month endMonth) {
        this.displayLabel = displayLabel;
        this.startMonth = startMonth;
        this.endMonth = endMonth;
    }

    public String getDisplayLabel() {
        return displayLabel;
    }

    public Month getStartMonth() {
        return startMonth;
    }

    public Month getEndMonth() {
        return endMonth;
    }

    /**
     * Determines the financial year quarter for a calendar month.
     */
    public static Quarter ofMonth(Month month) {
        Objects.requireNonNull(month, "Month cannot be null");
        return switch (month) {
            case APRIL, MAY, JUNE -> Q1;
            case JULY, AUGUST, SEPTEMBER -> Q2;
            case OCTOBER, NOVEMBER, DECEMBER -> Q3;
            case JANUARY, FEBRUARY, MARCH -> Q4;
        };
    }
}
