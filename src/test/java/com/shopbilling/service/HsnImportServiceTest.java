package com.shopbilling.service;

import com.shopbilling.db.ConnectionFactory;
import com.shopbilling.db.DatabaseConfig;
import com.shopbilling.db.FlywayMigrator;
import com.shopbilling.db.HsnRepository;
import com.shopbilling.db.TransactionHelper;
import com.shopbilling.model.HsnImportResult;
import com.shopbilling.model.HsnItem;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;

class HsnImportServiceTest {

    private HsnImportService importService;
    private HsnService hsnService;

    @BeforeEach
    void setUp(@TempDir Path tempDir) {
        Path dbFile = tempDir.resolve("hsn_import_test.db");
        DatabaseConfig config = DatabaseConfig.forFile(dbFile);
        ConnectionFactory connectionFactory = new ConnectionFactory(config);

        FlywayMigrator migrator = new FlywayMigrator(connectionFactory);
        migrator.migrate();

        TransactionHelper transactionHelper = new TransactionHelper(connectionFactory);
        HsnRepository hsnRepo = new HsnRepository();

        importService = new HsnImportService(transactionHelper, hsnRepo);
        hsnService = new HsnService(transactionHelper, hsnRepo);
    }

    @Test
    @DisplayName("Import valid HSN Excel workbook with automated header detection")
    void testImportValidExcel(@TempDir Path tempDir) throws IOException {
        File excelFile = tempDir.resolve("valid_hsn.xlsx").toFile();
        try (Workbook wb = new XSSFWorkbook();
             FileOutputStream out = new FileOutputStream(excelFile)) {
            Sheet sheet = wb.createSheet("GST_HSN_Tariff");
            Row header = sheet.createRow(0);
            header.createCell(0).setCellValue("HSN Code");
            header.createCell(1).setCellValue("Description of Goods");
            header.createCell(2).setCellValue("Product Type");

            Row r1 = sheet.createRow(1);
            r1.createCell(0).setCellValue("90283010");
            r1.createCell(1).setCellValue("Static converters, SMPS and power adaptors");
            r1.createCell(2).setCellValue("GOODS");

            Row r2 = sheet.createRow(2);
            r2.createCell(0).setCellValue("90289010");
            r2.createCell(1).setCellValue("Fuses for electrical circuits up to 1000V");
            r2.createCell(2).setCellValue("GOODS");

            wb.write(out);
        }

        HsnImportResult result = importService.importExcel(excelFile, null, null);
        assertNotNull(result);
        assertEquals(2, result.importedCount());
        assertEquals(0, result.updatedCount());
        assertEquals(0, result.failedCount());
        assertTrue(result.isSuccess());

        Optional<HsnItem> item1 = hsnService.getHsnByCode("90283010");
        assertTrue(item1.isPresent());
        assertEquals("Static converters, SMPS and power adaptors", item1.get().description());

        Optional<HsnItem> item2 = hsnService.getHsnByCode("90289010");
        assertTrue(item2.isPresent());
    }

    @Test
    @DisplayName("Import Excel with duplicate records updates existing description safely without database errors")
    void testImportDuplicateExcel(@TempDir Path tempDir) throws IOException {
        File excelFile = tempDir.resolve("dup_hsn.xlsx").toFile();
        try (Workbook wb = new XSSFWorkbook();
             FileOutputStream out = new FileOutputStream(excelFile)) {
            Sheet sheet = wb.createSheet("HSN");
            Row header = sheet.createRow(0);
            header.createCell(0).setCellValue("HSN_CODE");
            header.createCell(1).setCellValue("Commodity Description");

            // Row 1: First definition
            Row r1 = sheet.createRow(1);
            r1.createCell(0).setCellValue("85369010");
            r1.createCell(1).setCellValue("MCB Single Pole 10A");

            // Row 2: Updated definition of same HSN code
            Row r2 = sheet.createRow(2);
            r2.createCell(0).setCellValue("85369010");
            r2.createCell(1).setCellValue("Miniature Circuit Breaker (MCB) Single Pole 10A Overload Protection");

            wb.write(out);
        }

        HsnImportResult result = importService.importExcel(excelFile, null, null);
        assertTrue(result.isSuccess());
        assertEquals(1, result.importedCount());

        Optional<HsnItem> item = hsnService.getHsnByCode("85369010");
        assertTrue(item.isPresent());
        assertTrue(item.get().description().contains("Miniature Circuit Breaker"));
    }

    @Test
    @DisplayName("Import Excel with empty rows and invalid code lengths skips them gracefully")
    void testImportEmptyAndInvalidRows(@TempDir Path tempDir) throws IOException {
        File excelFile = tempDir.resolve("dirty_hsn.xlsx").toFile();
        try (Workbook wb = new XSSFWorkbook();
             FileOutputStream out = new FileOutputStream(excelFile)) {
            Sheet sheet = wb.createSheet("HSN");
            Row header = sheet.createRow(0);
            header.createCell(0).setCellValue("HSN Code");
            header.createCell(1).setCellValue("Description");

            // Empty row
            sheet.createRow(1);

            // Valid row
            Row r2 = sheet.createRow(2);
            r2.createCell(0).setCellValue("853921");
            r2.createCell(1).setCellValue("Halogen tungsten lamps");

            // Invalid code (single digit)
            Row r3 = sheet.createRow(3);
            r3.createCell(0).setCellValue("1");
            r3.createCell(1).setCellValue("Invalid short code");

            wb.write(out);
        }

        HsnImportResult result = importService.importExcel(excelFile, null, null);
        assertEquals(1, result.importedCount());
        assertEquals(2, result.skippedCount());
        assertFalse(result.errors().isEmpty());
    }

    @Test
    @DisplayName("Import invalid / non-existent Excel file throws ValidationException")
    void testImportInvalidFile(@TempDir Path tempDir) {
        File nonExistent = tempDir.resolve("missing.xlsx").toFile();
        assertThrows(ValidationException.class, () ->
                importService.importExcel(nonExistent, null, null)
        );
    }

    @Test
    @DisplayName("Cancelling import aborts execution safely")
    void testCancelImport(@TempDir Path tempDir) throws IOException {
        File excelFile = tempDir.resolve("large_hsn.xlsx").toFile();
        try (Workbook wb = new XSSFWorkbook();
             FileOutputStream out = new FileOutputStream(excelFile)) {
            Sheet sheet = wb.createSheet("HSN");
            Row header = sheet.createRow(0);
            header.createCell(0).setCellValue("HSN Code");
            header.createCell(1).setCellValue("Description");

            for (int i = 1; i <= 20; i++) {
                Row r = sheet.createRow(i);
                r.createCell(0).setCellValue("9000" + i);
                r.createCell(1).setCellValue("Item " + i);
            }
            wb.write(out);
        }

        AtomicBoolean cancelled = new AtomicBoolean(true);
        HsnImportResult result = importService.importExcel(excelFile, null, cancelled::get);
        assertNotNull(result);
        assertEquals(0, result.importedCount());
    }
}
