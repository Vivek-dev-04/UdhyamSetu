package com.shopbilling.service;

import com.shopbilling.db.ReportRepository;
import com.shopbilling.db.TransactionHelper;
import com.shopbilling.model.FinancialYear;
import com.shopbilling.model.Money;
import com.shopbilling.model.PaymentMode;
import com.shopbilling.model.Quarter;
import com.shopbilling.model.SaleMode;
import com.shopbilling.model.SalesReportFilter;
import com.shopbilling.model.SalesReportResult;
import com.shopbilling.model.SalesSummaryRow;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.StringWriter;
import java.io.Writer;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Service generating multi-filter sales reports, period aggregations, and CSV exports
 * (PRD Section 7.8, FR-33 - FR-36).
 * Pure service: strictly contains NO JavaFX imports.
 */
public class ReportService {

    private static final Logger logger = LoggerFactory.getLogger(ReportService.class);

    private final TransactionHelper transactionHelper;
    private final ReportRepository reportRepository;
    private final IstClock clock;

    public ReportService(TransactionHelper transactionHelper,
                         ReportRepository reportRepository,
                         IstClock clock) {
        this.transactionHelper = Objects.requireNonNull(transactionHelper, "TransactionHelper cannot be null");
        this.reportRepository = Objects.requireNonNull(reportRepository, "ReportRepository cannot be null");
        this.clock = Objects.requireNonNull(clock, "IstClock cannot be null");
    }

    /**
     * Generates an aggregated sales report based on dynamic combinable filter criteria (FR-33, FR-35).
     */
    public SalesReportResult generateSalesReport(SalesReportFilter filter) {
        final SalesReportFilter effectiveFilter = filter != null ? filter : SalesReportFilter.all();

        return transactionHelper.executeRead(conn -> {
            List<SalesSummaryRow> rows = reportRepository.querySales(conn, effectiveFilter);

            int billedCount = 0;
            int registeredCount = 0;
            Money grossSales = Money.ZERO;
            List<Long> saleIds = new ArrayList<>();

            for (SalesSummaryRow row : rows) {
                if (row.saleMode() == SaleMode.BILL) {
                    billedCount++;
                } else {
                    registeredCount++;
                }
                grossSales = grossSales.plus(row.totalAmount());
                saleIds.add(row.saleId());
            }

            long returnsPaise = reportRepository.getTotalReturnsInPeriod(conn, effectiveFilter.dateFrom(), effectiveFilter.dateTo());
            Money totalReturns = Money.ofPaise(returnsPaise);
            Money netSales = grossSales.minus(totalReturns);

            Map<PaymentMode, Money> paymentBreakdown = reportRepository.getPaymentModeBreakdown(conn, saleIds);

            logger.info("Generated sales report: rows={} gross={} returns={} net={}",
                    rows.size(), grossSales.toRupeesString(), totalReturns.toRupeesString(), netSales.toRupeesString());

            return new SalesReportResult(
                    effectiveFilter,
                    rows,
                    rows.size(),
                    billedCount,
                    registeredCount,
                    grossSales,
                    totalReturns,
                    netSales,
                    paymentBreakdown
            );
        });
    }

    /**
     * Generates a quarterly report for an Indian Financial Year (FR-34).
     */
    public SalesReportResult generateQuarterlyReport(FinancialYear fy, Quarter quarter) {
        Objects.requireNonNull(fy, "FinancialYear cannot be null");
        Objects.requireNonNull(quarter, "Quarter cannot be null");

        LocalDate startDate = fy.getQuarterStartDate(quarter);
        LocalDate endDate = fy.getQuarterEndDate(quarter);
        return generateSalesReport(SalesReportFilter.forDateRange(startDate, endDate));
    }

    /**
     * Generates a full yearly report for an Indian Financial Year (FR-34).
     */
    public SalesReportResult generateYearlyReport(FinancialYear fy) {
        Objects.requireNonNull(fy, "FinancialYear cannot be null");
        return generateSalesReport(SalesReportFilter.forDateRange(fy.getStartDate(), fy.getEndDate()));
    }

    /**
     * Exports sales report rows and summary figures to standard CSV format (FR-36).
     */
    public void exportToCsv(SalesReportResult report, Writer writer) throws IOException {
        Objects.requireNonNull(report, "SalesReportResult cannot be null");
        Objects.requireNonNull(writer, "Writer cannot be null");

        // CSV Header
        writer.write("Sale ID,Sale Mode,Bill Number,Date,Customer,Customer Type,Subtotal (Rs),Discount (Rs),Total (Rs),Payment Modes\r\n");

        for (SalesSummaryRow r : report.rows()) {
            writer.write(String.format("%d,%s,%s,%s,\"%s\",%s,%s,%s,%s,\"%s\"\r\n",
                    r.saleId(),
                    r.saleMode().name(),
                    escapeCsv(r.billNumber() != null ? r.billNumber() : ""),
                    r.saleDate().toString(),
                    escapeCsv(r.customerName()),
                    r.customerType() != null ? r.customerType().name() : "RETAIL",
                    r.subtotal().toRupeesString(),
                    r.discount().toRupeesString(),
                    r.totalAmount().toRupeesString(),
                    escapeCsv(r.paymentModes())
            ));
        }

        // Summary Rows
        writer.write("\r\n");
        writer.write("--- SUMMARY ---\r\n");
        writer.write(String.format("Total Transactions,%d\r\n", report.totalTransactions()));
        writer.write(String.format("Billed Transactions,%d\r\n", report.billedTransactions()));
        writer.write(String.format("Registered Transactions,%d\r\n", report.registeredTransactions()));
        writer.write(String.format("Gross Sales (Rs),%s\r\n", report.grossSales().toRupeesString()));
        writer.write(String.format("Total Returns (Rs),%s\r\n", report.totalReturns().toRupeesString()));
        writer.write(String.format("Net Sales (Rs),%s\r\n", report.netSales().toRupeesString()));

        writer.write("\r\nPayment Mode Breakdown:\r\n");
        for (Map.Entry<PaymentMode, Money> entry : report.paymentModeTotals().entrySet()) {
            writer.write(String.format("%s (Rs),%s\r\n", entry.getKey().name(), entry.getValue().toRupeesString()));
        }

        writer.flush();
    }

    /**
     * Exports report directly as a UTF-8 CSV String.
     */
    public String exportToCsvString(SalesReportResult report) {
        StringWriter sw = new StringWriter();
        try {
            exportToCsv(report, sw);
            return sw.toString();
        } catch (IOException e) {
            throw new RuntimeException("Failed to export report to CSV", e);
        }
    }

    private String escapeCsv(String input) {
        if (input == null) return "";
        return input.replace("\"", "\"\"");
    }
}
