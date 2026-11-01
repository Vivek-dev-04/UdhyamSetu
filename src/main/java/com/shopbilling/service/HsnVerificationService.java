package com.shopbilling.service;

import com.shopbilling.db.*;
import com.shopbilling.model.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.Connection;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Service managing Product Classification Approval Workflow and Audit Trail (Section 7, 10 & 13).
 * Ensures no unverified suggestion silently becomes the final HSN on a GST invoice.
 */
public class HsnVerificationService {

    private static final Logger logger = LoggerFactory.getLogger(HsnVerificationService.class);

    private final TransactionHelper transactionHelper;
    private final ProductRepository productRepository;
    private final HsnRepository hsnRepository;
    private final ProductHsnMappingRepository mappingRepository;
    private final HsnAuditRepository auditRepository;
    private final GstRateRepository gstRateRepository;

    public HsnVerificationService(
            TransactionHelper transactionHelper,
            ProductRepository productRepository,
            HsnRepository hsnRepository,
            ProductHsnMappingRepository mappingRepository,
            HsnAuditRepository auditRepository,
            GstRateRepository gstRateRepository
    ) {
        this.transactionHelper = Objects.requireNonNull(transactionHelper, "TransactionHelper cannot be null");
        this.productRepository = Objects.requireNonNull(productRepository, "ProductRepository cannot be null");
        this.hsnRepository = Objects.requireNonNull(hsnRepository, "HsnRepository cannot be null");
        this.mappingRepository = Objects.requireNonNull(mappingRepository, "ProductHsnMappingRepository cannot be null");
        this.auditRepository = Objects.requireNonNull(auditRepository, "HsnAuditRepository cannot be null");
        this.gstRateRepository = Objects.requireNonNull(gstRateRepository, "GstRateRepository cannot be null");
    }

    /**
     * Approves and verifies an HSN classification for a product with full legal audit trail (Section 7).
     */
    public Product verifyAndApprove(long productId, String hsnCode, Double gstRate, String username, String reason) {
        return verifyAndApprove(productId, hsnCode, gstRate, 1L, reason, "Human Verification (" + username + ")");
    }

    public Product verifyAndApprove(long productId, String hsnCode, Double gstRate, Long userId, String reason, String source) {
        Objects.requireNonNull(hsnCode, "HSN code cannot be null");
        String cleanCode = HsnItem.cleanCode(hsnCode);

        return transactionHelper.execute(conn -> {
            Product currentProduct = productRepository.findById(conn, productId)
                    .orElseThrow(() -> new EntityNotFoundException("Product id=" + productId + " not found"));

            HsnItem hsnItem = hsnRepository.findByCode(conn, cleanCode)
                    .orElseThrow(() -> new ValidationException("HSN code '" + cleanCode + "' does not exist in master directory"));

            Double effectiveRate = gstRate;
            if (effectiveRate == null) {
                effectiveRate = gstRateRepository.findApplicableRate(conn, cleanCode, null)
                        .map(GstRateItem::gstRate)
                        .orElse(18.0);
            }

            String prevHsn = currentProduct.hsnCode();
            Double prevRate = currentProduct.gstRate();

            // 1. Update product with verified status
            Product updatedProduct = new Product(
                    currentProduct.id(),
                    currentProduct.brandId(),
                    currentProduct.categoryId(),
                    currentProduct.name(),
                    currentProduct.unitCode(),
                    currentProduct.reorderLevelScaled(),
                    currentProduct.isActive(),
                    currentProduct.createdAt(),
                    cleanCode,
                    effectiveRate,
                    currentProduct.groupId(),
                    ClassificationStatus.VERIFIED
            );
            productRepository.update(conn, updatedProduct);

            // 2. Persist active product mapping
            ProductHsnMapping mapping = new ProductHsnMapping(
                    null,
                    productId,
                    cleanCode,
                    ClassificationStatus.VERIFIED,
                    1,
                    reason != null && !reason.isBlank() ? reason : "Human verification and approval",
                    source != null && !source.isBlank() ? source : "GST Practitioner / Owner Review",
                    null,
                    userId,
                    null,
                    null,
                    true,
                    null,
                    null
            );
            mappingRepository.saveMapping(conn, mapping);

            // 3. Log audit history
            auditRepository.logAudit(conn, new HsnVerificationAuditRecord(
                    null,
                    productId,
                    "VERIFIED",
                    prevHsn,
                    cleanCode,
                    prevRate,
                    effectiveRate,
                    userId,
                    reason != null ? reason : "Approved HSN classification",
                    source != null ? source : "Statutory Review",
                    ClassificationStatus.VERIFIED.name(),
                    null
            ));

            logger.info("Product id={} '{}' verified with HSN={} GST={}% by user={}",
                    productId, currentProduct.name(), cleanCode, effectiveRate, userId);

            return updatedProduct;
        });
    }

    /**
     * Explicit manual override of HSN classification with required rationale and audit logging (Section 7 & 10).
     */
    public Product manualOverride(long productId, String newHsnCode, Double newGstRate, Long userId, String overrideReason) {
        if (overrideReason == null || overrideReason.trim().isEmpty()) {
            throw new ValidationException("A written justification is mandatory for manual HSN overrides");
        }
        return verifyAndApprove(productId, newHsnCode, newGstRate, userId, "MANUAL OVERRIDE: " + overrideReason.trim(), "Manual User Override");
    }

    /**
     * Rejects an unverified classification candidate (Section 7).
     */
    public void rejectClassification(long productId, Long userId, String rejectionReason) {
        if (rejectionReason == null || rejectionReason.trim().isEmpty()) {
            throw new ValidationException("A reason is required to reject a classification candidate");
        }

        transactionHelper.execute(conn -> {
            Product product = productRepository.findById(conn, productId)
                    .orElseThrow(() -> new EntityNotFoundException("Product id=" + productId + " not found"));

            Product updated = new Product(
                    product.id(),
                    product.brandId(),
                    product.categoryId(),
                    product.name(),
                    product.unitCode(),
                    product.reorderLevelScaled(),
                    product.isActive(),
                    product.createdAt(),
                    product.hsnCode(),
                    product.gstRate(),
                    product.groupId(),
                    ClassificationStatus.REJECTED
            );
            productRepository.update(conn, updated);

            Optional<ProductHsnMapping> activeMapping = mappingRepository.findActiveByProductId(conn, productId);
            activeMapping.ifPresent(m -> {
                try {
                    mappingRepository.updateStatus(conn, m.id(), ClassificationStatus.REJECTED, userId, rejectionReason);
                } catch (Exception e) {
                    throw new DatabaseException("Failed to update mapping status", e);
                }
            });

            auditRepository.logAudit(conn, new HsnVerificationAuditRecord(
                    null,
                    productId,
                    "REJECTED",
                    product.hsnCode(),
                    null,
                    product.gstRate(),
                    null,
                    userId,
                    rejectionReason,
                    "User Review",
                    ClassificationStatus.REJECTED.name(),
                    null
            ));

            return null;
        });
    }

    /**
     * Flags a product classification as requiring expert review due to missing technical specifications (Section 5).
     */
    public void flagReviewRequired(long productId, Long userId, String missingSpecsNotes) {
        transactionHelper.execute(conn -> {
            Product product = productRepository.findById(conn, productId)
                    .orElseThrow(() -> new EntityNotFoundException("Product id=" + productId + " not found"));

            Product updated = new Product(
                    product.id(),
                    product.brandId(),
                    product.categoryId(),
                    product.name(),
                    product.unitCode(),
                    product.reorderLevelScaled(),
                    product.isActive(),
                    product.createdAt(),
                    product.hsnCode(),
                    product.gstRate(),
                    product.groupId(),
                    ClassificationStatus.REVIEW_REQUIRED
            );
            productRepository.update(conn, updated);

            auditRepository.logAudit(conn, new HsnVerificationAuditRecord(
                    null,
                    productId,
                    "SUGGESTED",
                    product.hsnCode(),
                    product.hsnCode(),
                    product.gstRate(),
                    product.gstRate(),
                    userId,
                    "Flagged for technical review: " + missingSpecsNotes,
                    "Technical Specification Check",
                    ClassificationStatus.REVIEW_REQUIRED.name(),
                    null
            ));

            return null;
        });
    }

    public List<HsnVerificationAuditRecord> getAuditHistory(long productId) {
        return transactionHelper.executeRead(conn -> auditRepository.findByProductId(conn, productId));
    }

    public List<HsnVerificationAuditRecord> getRecentAudits(int limit) {
        return transactionHelper.executeRead(conn -> auditRepository.findRecentAudits(conn, limit));
    }

    public List<ProductHsnMapping> getMappingsByStatus(ClassificationStatus status) {
        return transactionHelper.executeRead(conn -> mappingRepository.findAll(conn, status));
    }
}
