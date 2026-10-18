package com.shopbilling.service;

import com.shopbilling.db.StockRepository;
import com.shopbilling.db.TransactionHelper;
import com.shopbilling.model.CurrentStock;
import com.shopbilling.model.LowStockItem;
import com.shopbilling.model.Quantity;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * Service managing low-stock monitoring, reorder level alerts, and replenishment planning
 * (PRD Section 7.4, FR-18, FR-16).
 * Strictly contains NO JavaFX imports.
 */
public class LowStockService {

    private static final Logger logger = LoggerFactory.getLogger(LowStockService.class);

    private final TransactionHelper transactionHelper;
    private final StockRepository stockRepository;

    public LowStockService(TransactionHelper transactionHelper, StockRepository stockRepository) {
        this.transactionHelper = Objects.requireNonNull(transactionHelper, "TransactionHelper cannot be null");
        this.stockRepository = Objects.requireNonNull(stockRepository, "StockRepository cannot be null");
    }

    /**
     * Retrieves all active product variants whose current stock is at or below their configured reorder level.
     */
    public List<LowStockItem> getLowStockItems() {
        return transactionHelper.executeRead(conn -> {
            List<CurrentStock> allStock = stockRepository.getAllCurrentStock(conn);
            List<LowStockItem> result = new ArrayList<>();

            for (CurrentStock cs : allStock) {
                if (cs.isLowStock() && cs.reorderLevel() != null) {
                    Quantity current = cs.currentStock();
                    Quantity reorder = cs.reorderLevel();

                    Quantity shortage = reorder.minus(current);
                    if (shortage.isNegative()) {
                        shortage = Quantity.ZERO;
                    }

                    // Standard reorder suggestion: Bring stock back to 2x reorder level
                    Quantity target = Quantity.ofScaled(reorder.getScaledValue() * 2);
                    Quantity suggested = target.minus(current);
                    if (suggested.compareTo(Quantity.ZERO) <= 0) {
                        suggested = shortage.isPositive() ? shortage : Quantity.of(1);
                    }

                    result.add(new LowStockItem(
                            cs.variantId(),
                            cs.variantName(),
                            cs.productId(),
                            cs.productName(),
                            cs.brandName(),
                            cs.categoryName(),
                            cs.unitCode(),
                            current,
                            reorder,
                            shortage,
                            suggested
                    ));
                }
            }

            logger.info("Found {} low-stock item(s) below reorder levels", result.size());
            return result;
        });
    }

    public List<LowStockItem> getLowStockItemsByBrand(String brandName) {
        Objects.requireNonNull(brandName, "Brand name cannot be null");
        return getLowStockItems().stream()
                .filter(item -> brandName.equalsIgnoreCase(item.brandName()))
                .collect(Collectors.toList());
    }

    public List<LowStockItem> getLowStockItemsByCategory(String categoryName) {
        Objects.requireNonNull(categoryName, "Category name cannot be null");
        return getLowStockItems().stream()
                .filter(item -> categoryName.equalsIgnoreCase(item.categoryName()))
                .collect(Collectors.toList());
    }

    /**
     * Exports the low-stock advisory sheet as standard CSV for sending to distributors or suppliers.
     */
    public String exportLowStockCsv() {
        List<LowStockItem> items = getLowStockItems();
        StringBuilder sb = new StringBuilder();
        sb.append("Brand,Category,Product,Variant,Unit,Current Stock,Reorder Level,Shortage,Suggested Order\n");

        for (LowStockItem item : items) {
            sb.append(escapeCsv(item.brandName())).append(",")
              .append(escapeCsv(item.categoryName())).append(",")
              .append(escapeCsv(item.productName())).append(",")
              .append(escapeCsv(item.variantName())).append(",")
              .append(escapeCsv(item.unitCode())).append(",")
              .append(item.currentStock().toDisplayString()).append(",")
              .append(item.reorderLevel().toDisplayString()).append(",")
              .append(item.shortage().toDisplayString()).append(",")
              .append(item.suggestedOrderQuantity().toDisplayString()).append("\n");
        }
        return sb.toString();
    }

    private String escapeCsv(String value) {
        if (value == null) return "";
        if (value.contains(",") || value.contains("\"") || value.contains("\n")) {
            return "\"" + value.replace("\"", "\"\"") + "\"";
        }
        return value;
    }
}
