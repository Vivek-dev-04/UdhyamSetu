package com.shopbilling.service;

import com.shopbilling.db.*;
import com.shopbilling.model.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.Statement;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("HSN Verification Lifecycle, Audit Trail & GST Rate Tests (Sections 7, 8, 10 & 13)")
class HsnVerificationWorkflowTest {

    private TransactionHelper transactionHelper;
    private ProductRepository productRepository;
    private HsnRepository hsnRepository;
    private ProductHsnMappingRepository mappingRepository;
    private HsnAuditRepository auditRepository;
    private GstRateRepository gstRateRepository;
    private HsnVerificationService verificationService;
    private GstRateService gstRateService;

    @BeforeEach
    void setUp(@TempDir Path tempDir) throws Exception {
        Path dbFile = tempDir.resolve("hsn_workflow_test.db");
        DatabaseConfig config = DatabaseConfig.forFile(dbFile);
        ConnectionFactory connectionFactory = new ConnectionFactory(config);
        FlywayMigrator migrator = new FlywayMigrator(connectionFactory);
        migrator.migrate();

        transactionHelper = new TransactionHelper(connectionFactory);
        productRepository = new ProductRepository();
        hsnRepository = new HsnRepository();
        mappingRepository = new ProductHsnMappingRepository();
        auditRepository = new HsnAuditRepository();
        gstRateRepository = new GstRateRepository();

        verificationService = new HsnVerificationService(
                transactionHelper,
                productRepository,
                hsnRepository,
                mappingRepository,
                auditRepository,
                gstRateRepository
        );

        gstRateService = new GstRateService(transactionHelper, gstRateRepository);

        // Seed default User & Brand/Category
        try (Connection conn = connectionFactory.getConnection();
             Statement stmt = conn.createStatement()) {
            stmt.execute("INSERT INTO users (id, username, password_hash, role, full_name, is_active) " +
                    "VALUES (1, 'owner', '$2a$10$xyz', 'OWNER', 'Store Owner', 1);");
            stmt.execute("INSERT INTO brands (id, name) VALUES (1, 'Havells');");
            stmt.execute("INSERT INTO categories (id, name) VALUES (1, 'Switch');");
        }
    }

    @Test
    @DisplayName("Product verification lifecycle: PENDING -> VERIFIED with audit record")
    void testVerificationApprovalLifecycle() {
        // Create an unverified product
        Product unverified = transactionHelper.execute(conn -> {
            return productRepository.insert(conn, new Product(
                    null, 1L, 1L, "Havells Reo 10A Switch", "PIECE", null, true, null,
                    null, 18.0, null, ClassificationStatus.PENDING
            ));
        });
        assertEquals(ClassificationStatus.PENDING, unverified.hsnStatus());

        // Approve and verify with official HSN "85365020"
        Product approved = verificationService.verifyAndApprove(
                unverified.id(),
                "85365020",
                18.0,
                1L,
                "Verified against CBIC Customs Tariff Heading 8536",
                "CBIC Tariff 2024-25"
        );

        assertEquals(ClassificationStatus.VERIFIED, approved.hsnStatus());
        assertEquals("85365020", approved.hsnCode());
        assertEquals(18.0, approved.gstRate());

        // Verify Audit Trail is recorded
        List<HsnVerificationAuditRecord> audits = verificationService.getAuditHistory(approved.id());
        assertFalse(audits.isEmpty(), "Audit record must be created on verification");
        HsnVerificationAuditRecord lastAudit = audits.get(0);
        assertEquals("85365020", lastAudit.newHsn());
        assertEquals("VERIFIED", lastAudit.status());
        assertEquals(1L, lastAudit.userId());
        assertTrue(lastAudit.reason().contains("CBIC Customs Tariff"));
    }

    @Test
    @DisplayName("Manual Override with justification creates strict audit record")
    void testManualOverrideWithAudit() {
        Product prod = transactionHelper.execute(conn -> {
            return productRepository.insert(conn, new Product(
                    null, 1L, 1L, "Special Heavy Contact Switch", "PIECE", null, true, null,
                    "85365020", 18.0, null, ClassificationStatus.VERIFIED
            ));
        });

        // Authorized GST practitioner or owner overrides classification to 85359090
        Product overridden = verificationService.manualOverride(
                prod.id(),
                "85359090",
                18.0,
                1L,
                "Technical datasheet indicates rated voltage 1100V, necessitating Heading 8535"
        );

        assertEquals("85359090", overridden.hsnCode());
        assertEquals(ClassificationStatus.VERIFIED, overridden.hsnStatus());

        List<HsnVerificationAuditRecord> audits = verificationService.getAuditHistory(prod.id());
        assertTrue(audits.stream().anyMatch(a ->
                "85365020".equals(a.previousHsn()) &&
                "85359090".equals(a.newHsn()) &&
                a.reason().contains("necessitating Heading 8535")
        ), "Audit record must track old HSN, new HSN, and override reason");
    }

    @Test
    @DisplayName("Rejection and Review Required workflows")
    void testRejectionAndReviewRequired() {
        Product prod = transactionHelper.execute(conn -> {
            return productRepository.insert(conn, new Product(
                    null, 1L, 1L, "Ambiguous Unbranded Pipe", "METRE", null, true, null,
                    "3917", 18.0, null, ClassificationStatus.SUGGESTED
            ));
        });

        // Flag review required
        verificationService.flagReviewRequired(prod.id(), 1L, "Need clarification on PVC vs HDPE material");
        Product flagged = transactionHelper.executeRead(conn -> productRepository.findById(conn, prod.id()).orElseThrow());
        assertEquals(ClassificationStatus.REVIEW_REQUIRED, flagged.hsnStatus());

        // Reject classification
        verificationService.rejectClassification(prod.id(), 1L, "Manufacturer declaration was for agricultural non-taxable pipe");
        Product rejected = transactionHelper.executeRead(conn -> productRepository.findById(conn, prod.id()).orElseThrow());
        assertEquals(ClassificationStatus.REJECTED, rejected.hsnStatus());
    }

    @Test
    @DisplayName("Intra-State vs Inter-State Tax Calculation (CGST+SGST vs IGST)")
    void testIntraVsInterStateGstCalculation() {
        // Supplier is in Rajasthan ("08")
        String supplierStateCode = "08";

        // Case 1: Intra-state sale (Buyer also in Rajasthan "08")
        GstRateService.TaxBreakdown intraTax = gstRateService.calculateTaxes(
                "85365020",
                100000L, // Rs. 1000.00
                supplierStateCode,
                "08",
                LocalDate.of(2026, 4, 1)
        );
        assertEquals(9.0, intraTax.cgstRate());
        assertEquals(9.0, intraTax.sgstRate());
        assertEquals(0.0, intraTax.igstRate());
        assertEquals(9000L, intraTax.cgstPaise());
        assertEquals(9000L, intraTax.sgstPaise());
        assertEquals(0L, intraTax.igstPaise());
        assertEquals(18000L, intraTax.totalTaxPaise());
        assertFalse(intraTax.isInterState());

        // Case 2: Inter-state sale (Buyer in Delhi "07")
        GstRateService.TaxBreakdown interTax = gstRateService.calculateTaxes(
                "85365020",
                100000L, // Rs. 1000.00
                supplierStateCode,
                "07",
                LocalDate.of(2026, 4, 1)
        );
        assertEquals(0.0, interTax.cgstRate());
        assertEquals(0.0, interTax.sgstRate());
        assertEquals(18.0, interTax.igstRate());
        assertEquals(0L, interTax.cgstPaise());
        assertEquals(0L, interTax.sgstPaise());
        assertEquals(18000L, interTax.igstPaise());
        assertEquals(18000L, interTax.totalTaxPaise());
        assertTrue(interTax.isInterState());
    }

    @Test
    @DisplayName("Effective-dated statutory GST Rate lookup")
    void testEffectiveDatedGstRateLookup() {
        // Query for LED lamp Heading 8539
        Optional<GstRateItem> rateOpt = gstRateService.getApplicableRate("85395000", LocalDate.of(2026, 4, 1));
        assertTrue(rateOpt.isPresent(), "Should find statutory rate for LED lamps");
        GstRateItem rate = rateOpt.get();
        assertEquals(12.0, rate.gstRate(), "LED lamps rate is 12.0%");
        assertEquals(6.0, rate.cgstRate());
        assertEquals(6.0, rate.sgstRate());
        assertNotNull(rate.notificationReference());
    }
}
