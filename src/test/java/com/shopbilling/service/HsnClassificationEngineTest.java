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
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("Production-Grade HSN Classification Engine Tests (Section 4 & 5)")
class HsnClassificationEngineTest {

    private TransactionHelper transactionHelper;
    private HsnClassificationEngine classificationEngine;
    private ProductHsnMappingRepository mappingRepository;
    private ProductGroupRepository groupRepository;
    private ProductRepository productRepository;
    private HsnRepository hsnRepository;
    private GstRateRepository gstRateRepository;

    @BeforeEach
    void setUp(@TempDir Path tempDir) throws Exception {
        Path dbFile = tempDir.resolve("hsn_engine_test.db");
        DatabaseConfig config = DatabaseConfig.forFile(dbFile);
        ConnectionFactory connectionFactory = new ConnectionFactory(config);
        FlywayMigrator migrator = new FlywayMigrator(connectionFactory);
        migrator.migrate();

        transactionHelper = new TransactionHelper(connectionFactory);
        hsnRepository = new HsnRepository();
        mappingRepository = new ProductHsnMappingRepository();
        groupRepository = new ProductGroupRepository();
        productRepository = new ProductRepository();
        gstRateRepository = new GstRateRepository();

        classificationEngine = new HsnClassificationEngine(
                transactionHelper,
                hsnRepository,
                mappingRepository,
                groupRepository,
                gstRateRepository
        );

        // Seed Users, Brands, and Categories
        try (Connection conn = connectionFactory.getConnection();
             Statement stmt = conn.createStatement()) {
            stmt.execute("INSERT INTO users (id, username, password_hash, role, full_name, is_active) " +
                    "VALUES (1, 'owner', '$2a$10$xyz', 'OWNER', 'Store Owner', 1);");
            stmt.execute("INSERT INTO brands (id, name) VALUES (1, 'Havells');");
            stmt.execute("INSERT INTO categories (id, name) VALUES (1, 'Switch');");
        }
    }

    @Test
    @DisplayName("Priority 1: Previously verified exact product mapping returns immediately")
    void testPriority1PreviouslyVerifiedMapping() {
        // Create an existing product and map it with VERIFIED status
        Product prod = transactionHelper.execute(conn -> {
            return productRepository.insert(conn, new Product(
                    null, 1L, 1L, "Havells Fabio 1-Way Switch 10A", "PIECE", null, true, null,
                    "85365020", 18.0, null, ClassificationStatus.VERIFIED
            ));
        });

        transactionHelper.execute(conn -> {
            mappingRepository.saveMapping(conn, new ProductHsnMapping(
                    null, prod.id(), "85365020", ClassificationStatus.VERIFIED,
                    1, "Verified exact product catalogue entry",
                    "Customs Tariff Act 1975 Heading 8536", null, 1L, null
            ));
            return null;
        });

        List<HsnCandidate> candidates = classificationEngine.classify("Havells Fabio 1-Way Switch 10A", "Havells", "Switch");
        assertFalse(candidates.isEmpty(), "Should return candidates");
        HsnCandidate top = candidates.get(0);
        assertEquals(1, top.matchingPriority(), "Must match Priority 1");
        assertEquals("85365020", top.hsnCode());
        assertEquals(ClassificationStatus.VERIFIED, top.status());
        assertTrue(top.matchReason().contains("Previously verified"));
    }

    @Test
    @DisplayName("Priority 3: Exact trade alias and keyword matching for electrical items")
    void testPriority3TradeAliasMatching() {
        // Test 1: Miniature Circuit Breaker (MCB)
        List<HsnCandidate> mcbCandidates = classificationEngine.classify("Schneider Acti9 32A C-Curve MCB", "Schneider", "Switchgear");
        assertFalse(mcbCandidates.isEmpty());
        assertTrue(mcbCandidates.stream().anyMatch(c -> c.hsnCode().equals("85362030") || c.hsnCode().startsWith("8536")),
                "MCB should match Heading 8536 circuit breakers");

        // Test 2: Submersible Pump
        List<HsnCandidate> pumpCandidates = classificationEngine.classify("Kirloskar 1HP Submersible Pump", "Kirloskar", "Pumps");
        assertFalse(pumpCandidates.isEmpty());
        assertTrue(pumpCandidates.stream().anyMatch(c -> c.hsnCode().equals("84137010") || c.hsnCode().startsWith("8413")),
                "Submersible pump should match Heading 8413");

        // Test 3: Sanitary Ware - Ceramic wash basin
        List<HsnCandidate> basinCandidates = classificationEngine.classify("Hindware Ceramic Wash Basin Wall Hung", "Hindware", "Sanitary");
        assertFalse(basinCandidates.isEmpty());
        assertTrue(basinCandidates.stream().anyMatch(c -> c.hsnCode().equals("69101000") || c.hsnCode().startsWith("6910")),
                "Ceramic basin should match Chapter 69 sanitary fixtures");
    }

    @Test
    @DisplayName("Priority 4: Group default inheritance when no exact or alias match exists")
    void testPriority4GroupDefaultInheritance() {
        ProductGroup group = transactionHelper.execute(conn -> {
            return groupRepository.insert(conn, new ProductGroup(
                    "CONDUIT_PVC", "PVC Conduit Pipes & Fittings", "39172310", 18.0, "Rigid PVC electrical conduits"
            ));
        });

        List<HsnCandidate> candidates = classificationEngine.classify("Custom Conduit Fitting 25mm", "Generic", "Conduit", group.id());
        assertFalse(candidates.isEmpty());
        HsnCandidate groupMatch = candidates.stream()
                .filter(c -> c.matchingPriority() == 4)
                .findFirst()
                .orElse(null);

        assertNotNull(groupMatch, "Should have Priority 4 candidate from group");
        assertEquals("39172310", groupMatch.hsnCode());
        assertTrue(groupMatch.matchReason().contains("PVC Conduit Pipes"));
    }

    @Test
    @DisplayName("Missing technical specifications warning: Voltage & current rating detection")
    void testMissingSpecificationsDetection() {
        // Query without voltage specification
        List<HsnCandidate> switchCandidates = classificationEngine.classify("General Purpose Disconnect Switch", "Unknown", "Switch");
        assertFalse(switchCandidates.isEmpty());
        HsnCandidate top = switchCandidates.get(0);

        if (top.hsnCode().startsWith("8536")) {
            assertTrue(top.missingSpecifications() != null && !top.missingSpecifications().isBlank(),
                    "Domestic electrical switches under 8536 must warn if voltage <=1000V is unconfirmed");
            assertTrue(top.missingSpecifications().contains("1000V"), "Warning should cite 1000V limit");
        }
    }

    @Test
    @DisplayName("Similar product names with different statutory classifications (Switches vs High Voltage)")
    void testSimilarNamesDifferentClassifications() {
        // Domestic switch <= 1000V
        List<HsnCandidate> domesticCandidates = classificationEngine.classify("Modular 1-Way Switch 10A 240V", "Anchor", "Switch");
        assertFalse(domesticCandidates.isEmpty());
        assertEquals("85365020", domesticCandidates.get(0).hsnCode(), "Domestic switch must classify under 8536");

        // High voltage industrial switch > 1000V (Tariff Heading 8535)
        List<HsnCandidate> hvCandidates = classificationEngine.classify("High Voltage Circuit Isolator 11kV 630A", "ABB", "Switchgear");
        assertFalse(hvCandidates.isEmpty());
        assertTrue(hvCandidates.get(0).hsnCode().startsWith("8535") || hvCandidates.get(0).matchReason().contains("8535"),
                "High voltage switch (>1000V) must cite Heading 8535 or require review");
    }

    @Test
    @DisplayName("No fabricated confidence percentages or fabricated HSN codes")
    void testNoFabricatedConfidenceOrCodes() {
        List<HsnCandidate> candidates = classificationEngine.classify("Unusual Obscure Gadget XYZ", "Unknown", "Unknown");
        for (HsnCandidate c : candidates) {
            // Must have clean 2, 4, 6, or 8 digit code
            assertTrue(c.hsnCode().matches("^\\d{2,8}$"), "HSN code must be valid digits: " + c.hsnCode());
            // Verification status must not be fake VERIFIED for unknown items
            assertNotEquals(ClassificationStatus.VERIFIED, c.status(), "Automated suggestions must never be pre-VERIFIED");
            // Must not contain fake percentage confidence in description or reason
            assertFalse(c.matchReason().contains("% confidence"), "Must not display invented confidence percentages");
        }
    }
}
