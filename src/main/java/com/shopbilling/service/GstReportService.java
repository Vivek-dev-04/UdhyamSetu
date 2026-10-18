package com.shopbilling.service;

import com.shopbilling.db.SettingsRepository;
import com.shopbilling.db.TransactionHelper;
import com.shopbilling.model.CustomerType;
import com.shopbilling.model.FinancialYear;
import com.shopbilling.model.GstInvoiceRow;
import com.shopbilling.model.GstReportResult;
import com.shopbilling.model.GstSummaryRow;
import com.shopbilling.model.Money;
import com.shopbilling.model.Quarter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.StringWriter;
import java.io.Writer;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Service generating GST tax turnover, CGST/SGST breakdowns, and CA compliance exports
 * (PRD Section 6, 7.8, Section 8 Rule 5, Section 15.1).
 * Pure service: strictly contains NO JavaFX imports.
 */
public class GstReportService {

    private static final Logger logger = LoggerFactory.getLogger(GstReportService.class);
    public static final double DEFAULT_GST_RATE = 18.0; // Standard GST rate for electrical goods

    private final TransactionHelper transactionHelper;
    private final SettingsRepository settingsRepository;
    private final IstClock clock;

    public GstReportService(TransactionHelper transactionHelper,
                            SettingsRepository settingsRepository,
                            IstClock clock) {
        this.transactionHelper = Objects.requireNonNull(transactionHelper, "TransactionHelper cannot be null");
        this.settingsRepository = Objects.requireNonNull(settingsRepository, "SettingsRepository cannot be null");
        this.clock = Objects.requireNonNull(clock, "IstClock cannot be null");
    }

    /**
     * Generates a GST report for an Indian Financial Year and optional Quarter.
     */
    public GstReportResult generateGstReport(FinancialYear fy, Quarter quarter, Double customGstRatePercent) {
        Objects.requireNonNull(fy, "FinancialYear cannot be null");

        LocalDate from = quarter != null ? fy.getQuarterStartDate(quarter) : fy.getStartDate();
        LocalDate to = quarter != null ? fy.getQuarterEndDate(quarter) : fy.getEndDate();

        return generateGstReportInternal(fy, quarter, from, to, customGstRatePercent);
    }

    /**
     * Generates a GST report for a custom date range.
     */
    public GstReportResult generateGstReportForDateRange(LocalDate from, LocalDate to, Double customGstRatePercent) {
        Objects.requireNonNull(from, "From date cannot be null");
        Objects.requireNonNull(to, "To date cannot be null");
        if (from.isAfter(to)) {
            throw new ValidationException("From date cannot be after to date");
        }

        FinancialYear fy = FinancialYear.ofDate(from);
        return generateGstReportInternal(fy, null, from, to, customGstRatePercent);
    }

    private GstReportResult generateGstReportInternal(FinancialYear fy,
                                                     Quarter quarter,
                                                     LocalDate from,
                                                     LocalDate to,
                                                     Double customRate) {
        double rate = customRate != null ? customRate : getEffectiveGstRate();

        return transactionHelper.executeRead(conn -> {
            // 1. Fetch all Billed sales (Official Tax Invoices)
            List<GstInvoiceRow> invoices = queryBilledInvoices(conn, from, to, rate);

            // 2. Fetch total credit note refunds on billed sales
            long creditNotesPaise = queryCreditNoteRefunds(conn, from, to);

            // 3. Fetch total registered (unbilled) sales turnover
            long registeredSalesPaise = queryRegisteredSalesTurnover(conn, from, to);

            // 4. Group billed invoices by Month for monthly summaries
            Map<YearMonth, List<GstInvoiceRow>> monthGroups = new LinkedHashMap<>();
            YearMonth cur = YearMonth.from(from);
            YearMonth end = YearMonth.from(to);
            while (!cur.isAfter(end)) {
                monthGroups.put(cur, new ArrayList<>());
                cur = cur.plusMonths(1);
            }

            for (GstInvoiceRow inv : invoices) {
                YearMonth ym = YearMonth.from(inv.saleDate());
                monthGroups.computeIfAbsent(ym, k -> new ArrayList<>()).add(inv);
            }

            List<GstSummaryRow> monthlySummaries = new ArrayList<>();
            DateTimeFormatter monthFormatter = DateTimeFormatter.ofPattern("MMMM yyyy", Locale.ENGLISH);

            long totalGrossBilledPaise = 0L;
            long totalTaxablePaise = 0L;
            long totalCgstPaise = 0L;
            long totalSgstPaise = 0L;
            long totalTaxPaise = 0L;

            for (Map.Entry<YearMonth, List<GstInvoiceRow>> entry : monthGroups.entrySet()) {
                YearMonth ym = entry.getKey();
                List<GstInvoiceRow> monthInvoices = entry.getValue();

                long mGross = 0L;
                long mTaxable = 0L;
                long mCgst = 0L;
                long mSgst = 0L;
                long mTax = 0L;

                for (GstInvoiceRow r : monthInvoices) {
                    mGross += r.invoiceTotal().getPaise();
                    mTaxable += r.taxableValue().getPaise();
                    mCgst += r.cgst().getPaise();
                    mSgst += r.sgst().getPaise();
                    mTax += r.totalTax().getPaise();
                }

                totalGrossBilledPaise += mGross;
                totalTaxablePaise += mTaxable;
                totalCgstPaise += mCgst;
                totalSgstPaise += mSgst;
                totalTaxPaise += mTax;

                LocalDate monthStart = ym.atDay(1);
                LocalDate monthEnd = ym.atEndOfMonth();
                LocalDate queryStart = monthStart.isBefore(from) ? from : monthStart;
                LocalDate queryEnd = monthEnd.isAfter(to) ? to : monthEnd;

                long mCreditNotes = queryCreditNoteRefunds(conn, queryStart, queryEnd);
                long mRegistered = queryRegisteredSalesTurnover(conn, queryStart, queryEnd);
                long mNetBilled = Math.max(0L, mGross - mCreditNotes);
                long mTotalTurnover = mNetBilled + mRegistered;

                monthlySummaries.add(new GstSummaryRow(
                        ym.format(monthFormatter),
                        monthInvoices.size(),
                        Money.ofPaise(mGross),
                        Money.ofPaise(mCreditNotes),
                        Money.ofPaise(mNetBilled),
                        Money.ofPaise(mTaxable),
                        Money.ofPaise(mCgst),
                        Money.ofPaise(mSgst),
                        Money.ZERO,
                        Money.ofPaise(mTax),
                        Money.ofPaise(mRegistered),
                        Money.ofPaise(mTotalTurnover)
                ));
            }

            long grandNetBilled = Math.max(0L, totalGrossBilledPaise - creditNotesPaise);
            long grandTotalTurnover = grandNetBilled + registeredSalesPaise;

            GstSummaryRow totalSummary = new GstSummaryRow(
                    "TOTAL (" + from + " to " + to + ")",
                    invoices.size(),
                    Money.ofPaise(totalGrossBilledPaise),
                    Money.ofPaise(creditNotesPaise),
                    Money.ofPaise(grandNetBilled),
                    Money.ofPaise(totalTaxablePaise),
                    Money.ofPaise(totalCgstPaise),
                    Money.ofPaise(totalSgstPaise),
                    Money.ZERO,
                    Money.ofPaise(totalTaxPaise),
                    Money.ofPaise(registeredSalesPaise),
                    Money.ofPaise(grandTotalTurnover)
            );

            logger.info("Generated GST report: invoices={} grossBilled={} netBilled={} totalTax={} regTurnover={}",
                    invoices.size(), totalSummary.grossBilledTurnover(), totalSummary.netBilledTurnover(),
                    totalSummary.totalTax(), totalSummary.registeredTurnover());

            return new GstReportResult(
                    fy,
                    quarter,
                    from,
                    to,
                    rate,
                    invoices,
                    monthlySummaries,
                    totalSummary
            );
        });
    }

    private List<GstInvoiceRow> queryBilledInvoices(Connection conn, LocalDate from, LocalDate to, double gstRate) throws SQLException {
        String sql = "SELECT s.id, s.bill_number, s.sale_date, s.total_amount_paise, " +
                "c.name as customer_name, c.customer_type, c.notes as customer_notes " +
                "FROM sales s " +
                "LEFT JOIN customers c ON s.customer_id = c.id " +
                "WHERE s.sale_mode = 'BILL' AND s.sale_date BETWEEN ? AND ? " +
                "ORDER BY s.sale_date ASC, s.bill_sequence ASC, s.id ASC;";

        List<GstInvoiceRow> list = new ArrayList<>();
        double factor = 1.0 + (gstRate / 100.0);

        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, from.toString());
            ps.setString(2, to.toString());
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    long id = rs.getLong("id");
                    String billNumber = rs.getString("bill_number");
                    LocalDate date = LocalDate.parse(rs.getString("sale_date"));
                    long totalPaise = rs.getLong("total_amount_paise");

                    String custName = rs.getString("customer_name");
                    if (custName == null || custName.isBlank()) {
                        custName = "Cash Retail Counter";
                    }

                    String notes = rs.getString("customer_notes");
                    String gstin = extractGstin(notes);

                    String typeStr = rs.getString("customer_type");
                    CustomerType custType = typeStr != null ? CustomerType.valueOf(typeStr) : CustomerType.RETAIL;

                    boolean isB2B = (gstin != null && !gstin.isEmpty()) || custType == CustomerType.TECHNICIAN;

                    // Reverse tax calculation from inclusive retail total
                    long taxablePaise = Math.round(totalPaise / factor);
                    long totalTaxPaise = Math.max(0L, totalPaise - taxablePaise);
                    long cgstPaise = totalTaxPaise / 2;
                    long sgstPaise = totalTaxPaise - cgstPaise;

                    list.add(new GstInvoiceRow(
                            id,
                            billNumber != null ? billNumber : ("B-" + id),
                            date,
                            custName,
                            gstin,
                            custType,
                            isB2B,
                            Money.ofPaise(totalPaise),
                            Money.ofPaise(taxablePaise),
                            Money.ofPaise(cgstPaise),
                            Money.ofPaise(sgstPaise),
                            Money.ofPaise(totalTaxPaise)
                    ));
                }
            }
        }
        return list;
    }

    private String extractGstin(String notes) {
        if (notes == null || notes.isBlank()) {
            return null;
        }
        // Match standard 15-character Indian GSTIN (e.g. 08ABCDE1234F1Z5)
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("\\b\\d{2}[A-Z]{5}\\d{4}[A-Z]{1}[A-Z\\d]{1}[Z]{1}[A-Z\\d]{1}\\b")
                .matcher(notes);
        if (m.find()) {
            return m.group();
        }
        return null;
    }

    private long queryCreditNoteRefunds(Connection conn, LocalDate from, LocalDate to) throws SQLException {
        String sql = "SELECT COALESCE(SUM(r.total_refund_paise), 0) " +
                "FROM returns r " +
                "JOIN sales s ON r.sale_id = s.id " +
                "WHERE s.sale_mode = 'BILL' AND r.return_date BETWEEN ? AND ?;";

        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, from.toString());
            ps.setString(2, to.toString());
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return rs.getLong(1);
                }
            }
        }
        return 0L;
    }

    private long queryRegisteredSalesTurnover(Connection conn, LocalDate from, LocalDate to) throws SQLException {
        String sql = "SELECT COALESCE(SUM(total_amount_paise), 0) " +
                "FROM sales WHERE sale_mode = 'REGISTERED' AND sale_date BETWEEN ? AND ?;";

        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, from.toString());
            ps.setString(2, to.toString());
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return rs.getLong(1);
                }
            }
        }
        return 0L;
    }

    private double getEffectiveGstRate() {
        return transactionHelper.executeRead(conn -> {
            Optional<String> rateStr = settingsRepository.getSetting(conn, "GST_RATE_PERCENT");
            if (rateStr.isEmpty()) {
                rateStr = settingsRepository.getSetting(conn, "gst.rate.percent");
            }
            if (rateStr.isPresent()) {
                try {
                    return Double.parseDouble(rateStr.get().trim());
                } catch (NumberFormatException ignored) {}
            }
            return DEFAULT_GST_RATE;
        });
    }

    /**
     * Exports GST compliance data to standard CSV formatted for the shop's Chartered Accountant.
     */
    public void exportGstReportCsv(GstReportResult report, Writer writer) throws IOException {
        Objects.requireNonNull(report, "GstReportResult cannot be null");
        Objects.requireNonNull(writer, "Writer cannot be null");

        writer.write("--- GST & CA COMPLIANCE TURNOVER SUMMARY ---\r\n");
        writer.write(String.format("Period,%s to %s\r\n", report.fromDate(), report.toDate()));
        writer.write(String.format("Financial Year,%s\r\n", report.financialYear().getLabel()));
        if (report.quarter() != null) {
            writer.write(String.format("Quarter,%s\r\n", report.quarter().name()));
        }
        writer.write(String.format("GST Rate Applied,%.2f%%\r\n\r\n", report.gstRatePercent()));

        // Section 1: Monthly Rollup
        writer.write("Month,Billed Invoices,Gross Billed (Rs),Credit Notes (Rs),Net Billed (Rs),Taxable Value (Rs),CGST (Rs),SGST (Rs),Total Tax (Rs),Registered Sales (Rs),Total Turnover (Rs)\r\n");
        for (GstSummaryRow r : report.monthlySummaries()) {
            writer.write(String.format("\"%s\",%d,%s,%s,%s,%s,%s,%s,%s,%s,%s\r\n",
                    escapeCsv(r.periodLabel()),
                    r.billedInvoiceCount(),
                    r.grossBilledTurnover().toRupeesString(),
                    r.creditNoteDeductions().toRupeesString(),
                    r.netBilledTurnover().toRupeesString(),
                    r.taxableTurnover().toRupeesString(),
                    r.cgstAmount().toRupeesString(),
                    r.sgstAmount().toRupeesString(),
                    r.totalTax().toRupeesString(),
                    r.registeredTurnover().toRupeesString(),
                    r.totalTurnover().toRupeesString()
            ));
        }

        GstSummaryRow tot = report.totalSummary();
        writer.write(String.format("\"%s\",%d,%s,%s,%s,%s,%s,%s,%s,%s,%s\r\n\r\n",
                escapeCsv(tot.periodLabel()),
                tot.billedInvoiceCount(),
                tot.grossBilledTurnover().toRupeesString(),
                tot.creditNoteDeductions().toRupeesString(),
                tot.netBilledTurnover().toRupeesString(),
                tot.taxableTurnover().toRupeesString(),
                tot.cgstAmount().toRupeesString(),
                tot.sgstAmount().toRupeesString(),
                tot.totalTax().toRupeesString(),
                tot.registeredTurnover().toRupeesString(),
                tot.totalTurnover().toRupeesString()
        ));

        // Section 2: Detailed Tax Invoices
        writer.write("--- ITEMIZED TAX INVOICES ---\r\n");
        writer.write("Bill Number,Date,Customer Name,GSTIN,Type,Category,Invoice Value (Rs),Taxable Value (Rs),CGST (Rs),SGST (Rs),Total Tax (Rs)\r\n");
        for (GstInvoiceRow inv : report.invoices()) {
            writer.write(String.format("\"%s\",%s,\"%s\",\"%s\",%s,%s,%s,%s,%s,%s,%s\r\n",
                    escapeCsv(inv.billNumber()),
                    inv.saleDate(),
                    escapeCsv(inv.customerName()),
                    escapeCsv(inv.customerGstin() != null ? inv.customerGstin() : "N/A"),
                    inv.customerType().name(),
                    inv.isB2B() ? "B2B" : "B2C",
                    inv.invoiceTotal().toRupeesString(),
                    inv.taxableValue().toRupeesString(),
                    inv.cgst().toRupeesString(),
                    inv.sgst().toRupeesString(),
                    inv.totalTax().toRupeesString()
            ));
        }

        writer.flush();
    }

    public String exportGstReportCsvString(GstReportResult report) {
        StringWriter sw = new StringWriter();
        try {
            exportGstReportCsv(report, sw);
            return sw.toString();
        } catch (IOException e) {
            throw new RuntimeException("Failed to export GST report to CSV: " + e.getMessage(), e);
        }
    }

    private String escapeCsv(String input) {
        if (input == null) return "";
        return input.replace("\"", "\"\"");
    }
}
