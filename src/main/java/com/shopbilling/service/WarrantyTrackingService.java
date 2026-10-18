package com.shopbilling.service;

import com.shopbilling.db.ProductVariantRepository;
import com.shopbilling.db.SaleRepository;
import com.shopbilling.db.TransactionHelper;
import com.shopbilling.db.WarrantyRepository;
import com.shopbilling.model.Sale;
import com.shopbilling.model.SaleItem;
import com.shopbilling.model.WarrantyRecord;
import com.shopbilling.model.WarrantyStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.LocalDate;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Service managing appliance serial numbers and warranty lifecycles (PRD Section 11, Section 15.6).
 * Pure service: strictly contains NO JavaFX imports.
 */
public class WarrantyTrackingService {

    private static final Logger logger = LoggerFactory.getLogger(WarrantyTrackingService.class);

    private final TransactionHelper transactionHelper;
    private final WarrantyRepository warrantyRepository;
    private final SaleRepository saleRepository;
    private final ProductVariantRepository productVariantRepository;
    private final IstClock clock;

    public WarrantyTrackingService(TransactionHelper transactionHelper,
                                   WarrantyRepository warrantyRepository,
                                   SaleRepository saleRepository,
                                   ProductVariantRepository productVariantRepository,
                                   IstClock clock) {
        this.transactionHelper = Objects.requireNonNull(transactionHelper, "TransactionHelper cannot be null");
        this.warrantyRepository = Objects.requireNonNull(warrantyRepository, "WarrantyRepository cannot be null");
        this.saleRepository = Objects.requireNonNull(saleRepository, "SaleRepository cannot be null");
        this.productVariantRepository = Objects.requireNonNull(productVariantRepository, "ProductVariantRepository cannot be null");
        this.clock = Objects.requireNonNull(clock, "IstClock cannot be null");
    }

    /**
     * Registers a new serial-tracked product under manufacturer warranty for a completed sale.
     */
    public WarrantyRecord registerWarranty(long saleId,
                                           long variantId,
                                           String serialNumber,
                                           int warrantyMonths,
                                           long userId) {
        Objects.requireNonNull(serialNumber, "Serial number cannot be null");
        String sanitizedSerial = serialNumber.trim().toUpperCase();
        if (sanitizedSerial.isEmpty()) {
            throw new ValidationException("Serial number cannot be blank");
        }
        if (warrantyMonths <= 0) {
            throw new ValidationException("Warranty duration must be at least 1 month");
        }

        return transactionHelper.execute(conn -> {
            Sale sale = saleRepository.findById(conn, saleId)
                    .orElseThrow(() -> new EntityNotFoundException("Sale id=" + saleId + " not found"));

            productVariantRepository.findById(conn, variantId)
                    .orElseThrow(() -> new EntityNotFoundException("Product Variant id=" + variantId + " not found"));

            // Verify variant was sold on this sale
            List<SaleItem> items = saleRepository.findItemsBySaleId(conn, saleId);
            boolean variantInSale = items.stream().anyMatch(i -> i.variantId() == variantId);
            if (!variantInSale) {
                throw new ValidationException("Variant id=" + variantId + " was not part of sale id=" + saleId);
            }

            // Check if serial number already registered
            if (warrantyRepository.isSerialNumberRegistered(conn, sanitizedSerial)) {
                throw new DuplicateEntityException("Serial number '" + sanitizedSerial + "' is already registered in warranty records");
            }

            LocalDate startDate = sale.saleDate();
            LocalDate endDate = startDate.plusMonths(warrantyMonths);

            WarrantyRecord record = warrantyRepository.insertWarranty(
                    conn, saleId, variantId, sanitizedSerial, warrantyMonths, startDate, endDate, userId
            );

            logger.info("Registered warranty: serial='{}' saleId={} variantId={} months={} validUntil={}",
                    sanitizedSerial, saleId, variantId, warrantyMonths, endDate);

            return record;
        });
    }

    /**
     * Looks up an appliance warranty by serial number, auto-updating expired status if needed.
     */
    public Optional<WarrantyRecord> lookupWarranty(String serialNumber) {
        if (serialNumber == null || serialNumber.trim().isBlank()) {
            return Optional.empty();
        }
        final String sanitized = serialNumber.trim().toUpperCase();

        return transactionHelper.execute(conn -> {
            Optional<WarrantyRecord> opt = warrantyRepository.findBySerialNumber(conn, sanitized);
            if (opt.isEmpty()) {
                return Optional.empty();
            }

            WarrantyRecord record = opt.get();
            LocalDate today = clock.today();

            // Auto-transition to EXPIRED if past warranty end date
            if (record.status() == WarrantyStatus.ACTIVE && today.isAfter(record.warrantyEndDate())) {
                warrantyRepository.updateStatus(conn, record.id(), WarrantyStatus.EXPIRED, record.claimNotes());
                record = warrantyRepository.findById(conn, record.id()).orElse(record);
            }

            return Optional.of(record);
        });
    }

    /**
     * Returns all registered warranties associated with a specific sale.
     */
    public List<WarrantyRecord> getWarrantiesForSale(long saleId) {
        return transactionHelper.executeRead(conn -> warrantyRepository.findBySaleId(conn, saleId));
    }

    /**
     * Records a warranty claim / replacement / void event against a registered serial number.
     */
    public WarrantyRecord processWarrantyClaim(String serialNumber,
                                               WarrantyStatus newStatus,
                                               String claimNotes,
                                               long userId) {
        Objects.requireNonNull(newStatus, "WarrantyStatus cannot be null");
        if (serialNumber == null || serialNumber.trim().isBlank()) {
            throw new ValidationException("Serial number cannot be blank");
        }
        final String sanitized = serialNumber.trim().toUpperCase();

        return transactionHelper.execute(conn -> {
            WarrantyRecord existing = warrantyRepository.findBySerialNumber(conn, sanitized)
                    .orElseThrow(() -> new EntityNotFoundException("Warranty record for serial '" + sanitized + "' not found"));

            warrantyRepository.updateStatus(conn, existing.id(), newStatus, claimNotes);

            logger.info("Updated warranty claim status: serial='{}' status={} notes='{}' user={}",
                    sanitized, newStatus, claimNotes, userId);

            return warrantyRepository.findById(conn, existing.id())
                    .orElseThrow(() -> new EntityNotFoundException("Warranty id=" + existing.id() + " not found"));
        });
    }
}
