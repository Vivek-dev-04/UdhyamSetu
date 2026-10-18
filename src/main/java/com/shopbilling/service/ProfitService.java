package com.shopbilling.service;

import com.shopbilling.db.ExpenseRepository;
import com.shopbilling.db.ProfitRepository;
import com.shopbilling.db.ProfitRepository.VariantReturnSummary;
import com.shopbilling.db.ProfitRepository.VariantSalesSummary;
import com.shopbilling.db.TransactionHelper;
import com.shopbilling.model.FinancialYear;
import com.shopbilling.model.Money;
import com.shopbilling.model.ProductProfitRow;
import com.shopbilling.model.ProfitReportResult;
import com.shopbilling.model.Quantity;
import com.shopbilling.model.Quarter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.math.RoundingMode;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Service for calculating product-level profitability, weighted average purchase cost (COGS),
 * gross margin, and net profit minus operating expenses (PRD Section 7.8, FR-37).
 * Owner-only view: throws ValidationException if user is not OWNER.
 * Pure service: strictly contains NO JavaFX imports.
 */
public class ProfitService {

    private static final Logger logger = LoggerFactory.getLogger(ProfitService.class);

    public static final String COSTING_METHOD_DOC =
            "Product Costing Method: Weighted Average Purchase Cost (PRD FR-37)\n" +
            "------------------------------------------------------------------\n" +
            "1. Unit Cost Calculation:\n" +
            "   For each product variant, Unit Cost is calculated from all historic distributor\n" +
            "   purchases (stock-in entries) as:\n" +
            "   Unit Cost = SUM(purchase_quantity * purchase_cost_price) / SUM(purchase_quantity)\n" +
            "   If a variant has never had distributor purchases recorded, unit cost defaults to 0.\n\n" +
            "2. Cost of Goods Sold (COGS):\n" +
            "   COGS = Net Quantity Sold (Sales - Returns) * Weighted Average Unit Cost\n\n" +
            "3. Gross Profit / Margin:\n" +
            "   Gross Profit = Net Sales Revenue - COGS\n" +
            "   Gross Margin % = (Gross Profit / Net Revenue) * 100\n\n" +
            "4. Net Profit:\n" +
            "   Net Profit = Gross Profit - Total Operating Expenses\n" +
            "   Net Margin % = (Net Profit / Net Revenue) * 100";

    private final TransactionHelper transactionHelper;
    private final ProfitRepository profitRepository;
    private final ExpenseRepository expenseRepository;
    private final IstClock clock;

    public ProfitService(TransactionHelper transactionHelper,
                         ProfitRepository profitRepository,
                         ExpenseRepository expenseRepository,
                         IstClock clock) {
        this.transactionHelper = Objects.requireNonNull(transactionHelper, "TransactionHelper cannot be null");
        this.profitRepository = Objects.requireNonNull(profitRepository, "ProfitRepository cannot be null");
        this.expenseRepository = Objects.requireNonNull(expenseRepository, "ExpenseRepository cannot be null");
        this.clock = Objects.requireNonNull(clock, "IstClock cannot be null");
    }

    /**
     * Generates the Owner Profit Report for a given date range (or all-time if nulls passed).
     *
     * @param userId User requesting the report (must have OWNER role)
     * @param from   Inclusive start date (optional)
     * @param to     Inclusive end date (optional)
     * @return Complete profit breakdown including COGS and expenses
     */
    public ProfitReportResult generateProfitReport(long userId, LocalDate from, LocalDate to) {
        return transactionHelper.executeRead(conn -> {
            // 1. Verify user exists and check OWNER permission
            String role = getUserRole(conn, userId);
            if (!"OWNER".equalsIgnoreCase(role)) {
                throw new ValidationException("Only the shop owner is authorized to view the profit report");
            }

            // 2. Fetch weighted average unit costs across all purchases
            Map<Long, Money> weightedAvgCosts = profitRepository.getWeightedAverageUnitCosts(conn);

            // 3. Fetch sales and return aggregates
            List<VariantSalesSummary> salesSummaries = profitRepository.getVariantSales(conn, from, to);
            Map<Long, VariantReturnSummary> returnSummaries = profitRepository.getVariantReturns(conn, from, to);

            // 4. Compute product-level metrics
            List<ProductProfitRow> rows = new ArrayList<>();
            Money totalRevenue = Money.ZERO;
            Money totalCogs = Money.ZERO;
            Money grossProfit = Money.ZERO;

            for (VariantSalesSummary sale : salesSummaries) {
                VariantReturnSummary ret = returnSummaries.get(sale.variantId());
                long retQty = ret != null ? ret.returnedQuantityScaled() : 0L;
                long retRefund = ret != null ? ret.refundPaise() : 0L;

                long netQtyScaled = Math.max(0L, sale.grossQuantityScaled() - retQty);
                long netRevenuePaise = Math.max(0L, sale.grossRevenuePaise() - retRefund);

                Quantity netQuantity = Quantity.ofScaled(netQtyScaled);
                Money revenue = Money.ofPaise(netRevenuePaise);
                Money unitCost = weightedAvgCosts.getOrDefault(sale.variantId(), Money.ZERO);
                Money cogs = unitCost.times(netQuantity.toBigDecimal(), RoundingMode.HALF_UP);
                Money grossMargin = revenue.minus(cogs);

                double marginPercent = revenue.isPositive()
                        ? ((double) grossMargin.getPaise() * 100.0) / (double) revenue.getPaise()
                        : 0.0;

                rows.add(new ProductProfitRow(
                        sale.variantId(),
                        sale.productName(),
                        sale.variantName(),
                        sale.brandName(),
                        netQuantity,
                        revenue,
                        unitCost,
                        cogs,
                        grossMargin,
                        marginPercent
                ));

                totalRevenue = totalRevenue.plus(revenue);
                totalCogs = totalCogs.plus(cogs);
                grossProfit = grossProfit.plus(grossMargin);
            }

            // 5. Query operating expenses in period
            long expensesPaise = expenseRepository.getTotalExpensesInPeriod(conn, from, to);
            Money totalExpenses = Money.ofPaise(expensesPaise);
            Money netProfit = grossProfit.minus(totalExpenses);

            double grossMarginPercent = totalRevenue.isPositive()
                    ? ((double) grossProfit.getPaise() * 100.0) / (double) totalRevenue.getPaise()
                    : 0.0;

            double netMarginPercent = totalRevenue.isPositive()
                    ? ((double) netProfit.getPaise() * 100.0) / (double) totalRevenue.getPaise()
                    : 0.0;

            logger.info("Generated owner profit report: revenue={} cogs={} grossProfit={} expenses={} netProfit={}",
                    totalRevenue.toRupeesString(), totalCogs.toRupeesString(), grossProfit.toRupeesString(),
                    totalExpenses.toRupeesString(), netProfit.toRupeesString());

            return new ProfitReportResult(
                    from,
                    to,
                    rows,
                    totalRevenue,
                    totalCogs,
                    grossProfit,
                    totalExpenses,
                    netProfit,
                    grossMarginPercent,
                    netMarginPercent
            );
        });
    }

    public ProfitReportResult generateQuarterlyProfitReport(long userId, FinancialYear fy, Quarter quarter) {
        Objects.requireNonNull(fy, "FinancialYear cannot be null");
        Objects.requireNonNull(quarter, "Quarter cannot be null");
        return generateProfitReport(userId, fy.getQuarterStartDate(quarter), fy.getQuarterEndDate(quarter));
    }

    public ProfitReportResult generateYearlyProfitReport(long userId, FinancialYear fy) {
        Objects.requireNonNull(fy, "FinancialYear cannot be null");
        return generateProfitReport(userId, fy.getStartDate(), fy.getEndDate());
    }

    public String getCostingMethodDocumentation() {
        return COSTING_METHOD_DOC;
    }

    private String getUserRole(Connection conn, long userId) throws SQLException {
        String sql = "SELECT role FROM users WHERE id = ?;";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, userId);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return rs.getString("role");
                }
            }
        }
        throw new EntityNotFoundException("User id=" + userId + " not found");
    }
}
