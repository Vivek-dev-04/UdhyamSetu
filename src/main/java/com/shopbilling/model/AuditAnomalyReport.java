package com.shopbilling.model;

import java.time.LocalDate;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * Aggregated audit anomaly report presented to shop owner (PRD Section 7.10, Section 14).
 */
public record AuditAnomalyReport(
        LocalDate fromDate,
        LocalDate toDate,
        int totalAnomalies,
        int highSeverityCount,
        int mediumSeverityCount,
        int lowSeverityCount,
        List<AuditAnomalyRecord> anomalies
) {
    public AuditAnomalyReport {
        Objects.requireNonNull(fromDate, "From date cannot be null");
        Objects.requireNonNull(toDate, "To date cannot be null");
        anomalies = anomalies == null ? List.of() : Collections.unmodifiableList(anomalies);
    }

    public boolean hasHighSeverityAnomalies() {
        return highSeverityCount > 0;
    }
}
