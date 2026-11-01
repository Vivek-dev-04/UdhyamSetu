package com.shopbilling.service;

import com.shopbilling.db.HsnRepository;
import com.shopbilling.db.TransactionHelper;
import com.shopbilling.model.HsnImportHistoryItem;
import com.shopbilling.model.HsnItem;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Service orchestrating HSN Master directory search, CRUD, import history, and validation (Section 3 & 5).
 */
public class HsnService {
    private static final Logger logger = LoggerFactory.getLogger(HsnService.class);

    private final TransactionHelper transactionHelper;
    private final HsnRepository hsnRepository;

    public HsnService(TransactionHelper transactionHelper, HsnRepository hsnRepository) {
        this.transactionHelper = Objects.requireNonNull(transactionHelper, "TransactionHelper cannot be null");
        this.hsnRepository = Objects.requireNonNull(hsnRepository, "HsnRepository cannot be null");
    }

    public List<HsnItem> searchHsn(String query) {
        return searchHsn(query, 20);
    }

    public List<HsnItem> searchHsn(String query, int limit) {
        return transactionHelper.executeRead(conn -> hsnRepository.search(conn, query, limit));
    }

    public List<HsnItem> searchHsnWithChapter(String query, String chapter, int limit) {
        return transactionHelper.executeRead(conn -> hsnRepository.searchWithChapter(conn, query, chapter, limit));
    }

    public Optional<HsnItem> getHsnByCode(String code) {
        if (code == null || code.trim().isEmpty()) {
            return Optional.empty();
        }
        return transactionHelper.executeRead(conn -> hsnRepository.findByCode(conn, code.trim()));
    }

    public Optional<HsnItem> getHsnById(long id) {
        return transactionHelper.executeRead(conn -> hsnRepository.findById(conn, id));
    }

    public List<HsnItem> getAllHsn(int limit, int offset) {
        return transactionHelper.executeRead(conn -> hsnRepository.findAll(conn, limit, offset));
    }

    public int getTotalCount() {
        return transactionHelper.executeRead(hsnRepository::count);
    }

    public boolean isHsnInUse(String hsnCode) {
        if (hsnCode == null || hsnCode.trim().isEmpty()) {
            return false;
        }
        return transactionHelper.executeRead(conn -> hsnRepository.isHsnInUse(conn, hsnCode));
    }

    public HsnItem createHsn(String code, String description, String productType) {
        return createHsn(code, description, productType, null, null);
    }

    public HsnItem createHsn(String code, String description, String productType, String source, String technicalNotes) {
        String cleanCode = HsnItem.cleanCode(validateNonBlank(code, "HSN code"));
        String cleanDesc = validateNonBlank(description, "Description");
        String pType = (productType == null || productType.trim().isEmpty()) ? "GOODS" : productType.trim().toUpperCase();

        if (cleanCode.length() < 2 || cleanCode.length() > 10) {
            throw new ValidationException("HSN code must be between 2 and 10 digits");
        }

        return transactionHelper.execute(conn -> {
            Optional<HsnItem> existing = hsnRepository.findByCode(conn, cleanCode);
            if (existing.isPresent()) {
                throw new DuplicateEntityException("HSN code '" + cleanCode + "' already exists in master directory");
            }

            HsnItem item = new HsnItem(
                    null, cleanCode, cleanDesc, pType,
                    cleanCode.length() >= 2 ? cleanCode.substring(0, 2) : cleanCode,
                    cleanCode.length() >= 4 ? cleanCode.substring(0, 4) : cleanCode,
                    cleanCode.length() >= 6 ? cleanCode.substring(0, 6) : null,
                    cleanCode.length() == 8 ? cleanCode : null,
                    source != null ? source : "Manual Entry",
                    "Customs Tariff 2024-25",
                    null,
                    technicalNotes,
                    null
            );
            HsnItem created = hsnRepository.insert(conn, item);
            logger.info("Created HSN code={} type={}", created.hsnCode(), created.productType());
            return created;
        });
    }

    public HsnItem updateHsn(long id, String code, String description, String productType) {
        String cleanCode = HsnItem.cleanCode(validateNonBlank(code, "HSN code"));
        String cleanDesc = validateNonBlank(description, "Description");
        String pType = (productType == null || productType.trim().isEmpty()) ? "GOODS" : productType.trim().toUpperCase();

        if (cleanCode.length() < 2 || cleanCode.length() > 10) {
            throw new ValidationException("HSN code must be between 2 and 10 digits");
        }

        return transactionHelper.execute(conn -> {
            HsnItem existing = hsnRepository.findById(conn, id)
                    .orElseThrow(() -> new EntityNotFoundException("HSN item id=" + id + " not found"));

            // Check duplicate code if changed
            if (!existing.hsnCode().equalsIgnoreCase(cleanCode)) {
                Optional<HsnItem> duplicate = hsnRepository.findByCode(conn, cleanCode);
                if (duplicate.isPresent()) {
                    throw new DuplicateEntityException("Another HSN record with code '" + cleanCode + "' already exists");
                }
            }

            HsnItem updated = new HsnItem(
                    id, cleanCode, cleanDesc, pType,
                    existing.chapter(), existing.heading(), existing.subheading(), existing.tariffItem(),
                    existing.source(), existing.sourceVersion(), existing.importDate(), existing.technicalNotes(),
                    existing.updatedAt()
            );
            hsnRepository.update(conn, updated);
            logger.info("Updated HSN id={} code={}", id, cleanCode);
            return updated;
        });
    }

    public boolean deleteHsn(long id) {
        return transactionHelper.execute(conn -> {
            HsnItem existing = hsnRepository.findById(conn, id)
                    .orElseThrow(() -> new EntityNotFoundException("HSN item id=" + id + " not found"));

            if (hsnRepository.isHsnInUse(conn, existing.hsnCode())) {
                throw new ValidationException("Cannot delete HSN code '" + existing.hsnCode() +
                        "': it is currently assigned to products in catalogue or used on invoices.");
            }

            boolean deleted = hsnRepository.delete(conn, id);
            logger.info("Deleted HSN id={} code={}", id, existing.hsnCode());
            return deleted;
        });
    }

    public long recordImportHistory(HsnImportHistoryItem item) {
        return transactionHelper.execute(conn -> hsnRepository.recordImportHistory(conn, item));
    }

    public List<HsnImportHistoryItem> getImportHistory(int limit) {
        return transactionHelper.executeRead(conn -> hsnRepository.getImportHistory(conn, limit));
    }

    private String validateNonBlank(String value, String fieldName) {
        if (value == null || value.trim().isEmpty()) {
            throw new ValidationException(fieldName + " cannot be blank");
        }
        return value.trim();
    }
}
