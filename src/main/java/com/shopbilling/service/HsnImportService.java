package com.shopbilling.service;

import com.shopbilling.db.HsnRepository;
import com.shopbilling.db.TransactionHelper;
import com.shopbilling.model.HsnImportResult;
import com.shopbilling.model.HsnImportHistoryItem;
import com.shopbilling.model.HsnItem;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.usermodel.WorkbookFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.BooleanSupplier;

/**
 * High-performance Excel importer for official GST HSN Directory (Section 4).
 * Supports .xlsx and .xls, dynamic column detection, interactive column mapping,
 * batch database upsert transactions, cancellation, and granular progress reporting.
 */
public class HsnImportService {
    private static final Logger logger = LoggerFactory.getLogger(HsnImportService.class);
    private static final int BATCH_SIZE = 500;

    private final TransactionHelper transactionHelper;
    private final HsnRepository hsnRepository;

    @FunctionalInterface
    public interface ProgressCallback {
        void onProgress(int processedRows, int totalRows, String message);
    }

    public record ColumnMapping(
            int sheetIndex,
            int headerRowIndex,
            int hsnCol,
            int descCol,
            Integer typeCol
    ) {}

    public record WorkbookPreview(
            List<String> sheetNames,
            int defaultSheetIndex,
            int defaultHeaderRowIndex,
            List<String> headers,
            Integer detectedHsnCol,
            Integer detectedDescCol,
            Integer detectedTypeCol
    ) {
        public boolean isConfidentlyDetected() {
            return detectedHsnCol != null && detectedDescCol != null;
        }
    }

    public HsnImportService(TransactionHelper transactionHelper, HsnRepository hsnRepository) {
        this.transactionHelper = Objects.requireNonNull(transactionHelper, "TransactionHelper cannot be null");
        this.hsnRepository = Objects.requireNonNull(hsnRepository, "HsnRepository cannot be null");
    }

    /**
     * Inspects the Excel file structure, available sheets, and attempts automated header detection.
     */
    public WorkbookPreview inspectWorkbook(File excelFile) {
        Objects.requireNonNull(excelFile, "Excel file cannot be null");
        if (!excelFile.exists() || !excelFile.canRead()) {
            throw new ValidationException("Cannot read Excel file: " + excelFile.getAbsolutePath());
        }

        try (InputStream in = new FileInputStream(excelFile);
             Workbook workbook = WorkbookFactory.create(in)) {

            List<String> sheetNames = new ArrayList<>();
            for (int i = 0; i < workbook.getNumberOfSheets(); i++) {
                sheetNames.add(workbook.getSheetName(i));
            }

            if (sheetNames.isEmpty()) {
                throw new ValidationException("The selected Excel workbook has no sheets.");
            }

            // Look in the first non-empty sheet
            int targetSheetIdx = 0;
            Sheet sheet = workbook.getSheetAt(targetSheetIdx);
            DataFormatter formatter = new DataFormatter(Locale.US);

            int headerRowIndex = 0;
            List<String> headers = new ArrayList<>();
            Integer hsnCol = null;
            Integer descCol = null;
            Integer typeCol = null;

            // Search first 15 rows for a header row containing HSN/code and description
            for (int r = 0; r <= Math.min(15, sheet.getLastRowNum()); r++) {
                Row row = sheet.getRow(r);
                if (row == null) continue;

                List<String> candidateHeaders = new ArrayList<>();
                Integer candHsn = null;
                Integer candDesc = null;
                Integer candType = null;

                for (int c = 0; c < row.getLastCellNum(); c++) {
                    Cell cell = row.getCell(c);
                    String val = cell != null ? formatter.formatCellValue(cell).trim() : "";
                    candidateHeaders.add(val);
                    String norm = val.toLowerCase(Locale.US).replaceAll("[^a-z0-9]", "");

                    if (candHsn == null && (norm.contains("hsn") || norm.contains("tariff") || norm.contains("chapter") || norm.contains("commoditycode"))) {
                        candHsn = c;
                    } else if (candDesc == null && (norm.contains("desc") || norm.contains("commodity") || norm.contains("goods") || norm.contains("details"))) {
                        candDesc = c;
                    } else if (candType == null && (norm.contains("type") || norm.contains("category") || norm.contains("goodservice"))) {
                        candType = c;
                    }
                }

                if (candHsn != null && candDesc != null) {
                    headerRowIndex = r;
                    headers = candidateHeaders;
                    hsnCol = candHsn;
                    descCol = candDesc;
                    typeCol = candType;
                    break;
                }
            }

            // Fallback if no header row was detected: use row 0
            if (headers.isEmpty()) {
                Row row0 = sheet.getRow(0);
                if (row0 != null) {
                    for (int c = 0; c < row0.getLastCellNum(); c++) {
                        Cell cell = row0.getCell(c);
                        headers.add(cell != null ? formatter.formatCellValue(cell).trim() : "Column " + (c + 1));
                    }
                }
            }

            return new WorkbookPreview(sheetNames, targetSheetIdx, headerRowIndex, headers, hsnCol, descCol, typeCol);
        } catch (Exception ex) {
            logger.error("Failed to inspect Excel workbook: {}", ex.getMessage(), ex);
            throw new ValidationException("Failed to open Excel file: " + ex.getMessage());
        }
    }

    /**
     * Imports HSN directory using automated header detection.
     */
    public HsnImportResult importExcel(File excelFile, ProgressCallback progressCallback, BooleanSupplier isCancelled) {
        WorkbookPreview preview = inspectWorkbook(excelFile);
        if (!preview.isConfidentlyDetected()) {
            throw new ValidationException("Could not automatically identify HSN Code and Description columns. Please use column mapping.");
        }
        ColumnMapping mapping = new ColumnMapping(
                preview.defaultSheetIndex(),
                preview.defaultHeaderRowIndex(),
                preview.detectedHsnCol(),
                preview.detectedDescCol(),
                preview.detectedTypeCol()
        );
        return importWithMapping(excelFile, mapping, progressCallback, isCancelled);
    }

    /**
     * Imports HSN directory using an explicit ColumnMapping (Section 4).
     */
    public HsnImportResult importWithMapping(File excelFile,
                                            ColumnMapping mapping,
                                            ProgressCallback progressCallback,
                                            BooleanSupplier isCancelled) {
        Objects.requireNonNull(excelFile, "Excel file cannot be null");
        Objects.requireNonNull(mapping, "ColumnMapping cannot be null");

        long startTime = System.currentTimeMillis();
        List<String> errorMessages = new ArrayList<>();
        int importedCount = 0;
        int updatedCount = 0;
        int skippedCount = 0;
        int failedCount = 0;

        try (InputStream in = new FileInputStream(excelFile);
             Workbook workbook = WorkbookFactory.create(in)) {

            if (mapping.sheetIndex() < 0 || mapping.sheetIndex() >= workbook.getNumberOfSheets()) {
                throw new ValidationException("Selected sheet index " + mapping.sheetIndex() + " is out of bounds.");
            }

            Sheet sheet = workbook.getSheetAt(mapping.sheetIndex());
            int totalRows = sheet.getLastRowNum() - mapping.headerRowIndex();
            if (totalRows <= 0) {
                return new HsnImportResult(0, 0, 0, 0, 0, List.of("Sheet contains no data rows"), System.currentTimeMillis() - startTime);
            }

            DataFormatter formatter = new DataFormatter(Locale.US);
            List<HsnItem> batch = new ArrayList<>(BATCH_SIZE);
            Set<String> batchCodes = new HashSet<>(BATCH_SIZE);
            int processedRows = 0;

            for (int r = mapping.headerRowIndex() + 1; r <= sheet.getLastRowNum(); r++) {
                if (isCancelled != null && isCancelled.getAsBoolean()) {
                    logger.warn("HSN import cancelled by user at row {}", r);
                    break;
                }

                Row row = sheet.getRow(r);
                processedRows++;

                if (row == null) {
                    skippedCount++;
                    continue;
                }

                Cell hsnCell = row.getCell(mapping.hsnCol());
                Cell descCell = row.getCell(mapping.descCol());
                Cell typeCell = mapping.typeCol() != null ? row.getCell(mapping.typeCol()) : null;

                String rawHsn = hsnCell != null ? formatter.formatCellValue(hsnCell).trim() : "";
                String rawDesc = descCell != null ? formatter.formatCellValue(descCell).trim() : "";
                String rawType = typeCell != null ? formatter.formatCellValue(typeCell).trim() : "GOODS";

                String cleanHsn = HsnItem.cleanCode(rawHsn);

                if (cleanHsn.isEmpty() || rawDesc.isEmpty()) {
                    skippedCount++;
                    continue;
                }

                if (cleanHsn.length() < 2 || cleanHsn.length() > 10) {
                    skippedCount++;
                    if (errorMessages.size() < 50) {
                        errorMessages.add("Row " + (r + 1) + ": Invalid HSN length '" + cleanHsn + "' (must be 2-10 alphanumeric characters)");
                    }
                    continue;
                }

                // If duplicate code within current batch, use the later one
                HsnItem item = new HsnItem(null, cleanHsn, rawDesc, rawType, null);
                batch.add(item);
                batchCodes.add(cleanHsn);

                if (batch.size() >= BATCH_SIZE) {
                    int[] counts = processBatch(batch);
                    importedCount += counts[0];
                    updatedCount += counts[1];
                    failedCount += counts[2];
                    batch.clear();
                    batchCodes.clear();

                    if (progressCallback != null) {
                        progressCallback.onProgress(
                                processedRows,
                                totalRows,
                                String.format("Processed %d / %d rows (%d imported, %d updated)...",
                                        processedRows, totalRows, importedCount, updatedCount)
                        );
                    }
                }
            }

            // Process remaining batch
            if (!batch.isEmpty() && (isCancelled == null || !isCancelled.getAsBoolean())) {
                int[] counts = processBatch(batch);
                importedCount += counts[0];
                updatedCount += counts[1];
                failedCount += counts[2];
                batch.clear();
            }

            if (progressCallback != null) {
                progressCallback.onProgress(
                        processedRows,
                        totalRows,
                        String.format("Completed! Imported: %d, Updated: %d, Skipped: %d",
                                importedCount, updatedCount, skippedCount)
                );
            }

            long duration = System.currentTimeMillis() - startTime;
            logger.info("HSN import finished in {}ms: imported={}, updated={}, skipped={}, failed={}",
                    duration, importedCount, updatedCount, skippedCount, failedCount);

            try {
                final int imp = importedCount;
                final int upd = updatedCount;
                final int skp = skippedCount;
                final int prc = processedRows;
                final String status = failedCount > 0 ? "PARTIAL" : "SUCCESS";
                transactionHelper.execute(conn -> hsnRepository.recordImportHistory(conn, new HsnImportHistoryItem(
                        null,
                        excelFile.getName(),
                        null,
                        "Official HSN Directory Excel",
                        "Excel Import",
                        prc,
                        imp,
                        upd,
                        skp,
                        status,
                        1L,
                        null
                )));
            } catch (Exception ex) {
                logger.warn("Failed to persist import history audit record: {}", ex.getMessage());
            }

            return new HsnImportResult(
                    processedRows,
                    importedCount,
                    updatedCount,
                    skippedCount,
                    failedCount,
                    errorMessages,
                    duration
            );
        } catch (ValidationException ve) {
            throw ve;
        } catch (Exception ex) {
            logger.error("HSN import failed unexpectedly: {}", ex.getMessage(), ex);
            throw new ValidationException("Import failed: " + ex.getMessage());
        }
    }

    /**
     * Executes atomic batch upsert for a chunk and determines insert vs update count.
     * Returns [importedCount, updatedCount, failedCount].
     */
    private int[] processBatch(List<HsnItem> items) {
        if (items.isEmpty()) {
            return new int[]{0, 0, 0};
        }

        // Deduplicate within the chunk preserving the latest item
        Map<String, HsnItem> dedupMap = new LinkedHashMap<>();
        for (HsnItem item : items) {
            dedupMap.put(item.hsnCode(), item);
        }
        List<HsnItem> uniqueChunk = new ArrayList<>(dedupMap.values());

        return transactionHelper.execute(conn -> {
            // Count how many codes already exist in DB
            Set<String> existingCodes = new HashSet<>();
            StringBuilder sqlBuilder = new StringBuilder("SELECT hsn_code FROM hsn_master WHERE hsn_code IN (");
            for (int i = 0; i < uniqueChunk.size(); i++) {
                if (i > 0) sqlBuilder.append(",");
                sqlBuilder.append("?");
            }
            sqlBuilder.append(");");

            try (PreparedStatement ps = conn.prepareStatement(sqlBuilder.toString())) {
                for (int i = 0; i < uniqueChunk.size(); i++) {
                    ps.setString(i + 1, uniqueChunk.get(i).hsnCode());
                }
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        existingCodes.add(rs.getString(1));
                    }
                }
            }

            int updated = 0;
            int imported = 0;
            for (HsnItem item : uniqueChunk) {
                if (existingCodes.contains(item.hsnCode())) {
                    updated++;
                } else {
                    imported++;
                }
            }

            hsnRepository.batchUpsert(conn, uniqueChunk);
            return new int[]{imported, updated, 0};
        });
    }
}
