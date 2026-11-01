package com.shopbilling.service;

import com.shopbilling.db.GstRateRepository;
import com.shopbilling.db.TransactionHelper;
import com.shopbilling.model.GstRateItem;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.LocalDate;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Service managing Statutory GST Rates, Effective-Dated Schedules & Place of Supply Tax Calculations (Section 8 & 13).
 */
public class GstRateService {

    private static final Logger logger = LoggerFactory.getLogger(GstRateService.class);

    private final TransactionHelper transactionHelper;
    private final GstRateRepository gstRateRepository;

    public GstRateService(TransactionHelper transactionHelper, GstRateRepository gstRateRepository) {
        this.transactionHelper = Objects.requireNonNull(transactionHelper, "TransactionHelper cannot be null");
        this.gstRateRepository = Objects.requireNonNull(gstRateRepository, "GstRateRepository cannot be null");
    }

    public GstRateItem addRate(String hsnCode, double gstRate, String effectiveFrom, String notificationReference) {
        Objects.requireNonNull(hsnCode, "HSN code cannot be null");
        Objects.requireNonNull(effectiveFrom, "Effective from date cannot be null");
        Objects.requireNonNull(notificationReference, "Notification reference cannot be null");

        GstRateItem item = new GstRateItem(hsnCode, gstRate, effectiveFrom, notificationReference);
        return transactionHelper.execute(conn -> gstRateRepository.insert(conn, item));
    }

    public Optional<GstRateItem> getApplicableRate(String hsnCode, LocalDate date) {
        return transactionHelper.executeRead(conn -> gstRateRepository.findApplicableRate(conn, hsnCode, date));
    }

    public List<GstRateItem> getAllCurrentRates() {
        return transactionHelper.executeRead(gstRateRepository::findAllCurrent);
    }

    /**
     * Immutable calculation result for GST breakdown adhering to Section 13 rules:
     * - Differentiates Intra-State (CGST + SGST) vs Inter-State (IGST) using State Codes.
     */
    public record TaxBreakdown(
            String hsnCode,
            long taxablePaise,
            double gstRate,
            double cgstRate,
            double sgstRate,
            double igstRate,
            long cgstPaise,
            long sgstPaise,
            long igstPaise,
            long totalTaxPaise,
            boolean isInterState,
            String notificationReference
    ) {}

    public TaxBreakdown calculateTaxes(
            String hsnCode,
            long taxablePaise,
            String supplierStateCode,
            String customerStateCode,
            LocalDate date
    ) {
        LocalDate effectiveDate = date != null ? date : LocalDate.now();
        Optional<GstRateItem> rateItemOpt = getApplicableRate(hsnCode, effectiveDate);

        double gstRate = rateItemOpt.map(GstRateItem::gstRate).orElse(18.0);
        String notifRef = rateItemOpt.map(GstRateItem::notificationReference)
                .orElse("Notification No. 01/2017-Central Tax (Rate)");

        String suppCode = supplierStateCode != null ? supplierStateCode.trim() : "08";
        String custCode = customerStateCode != null ? customerStateCode.trim() : "08";

        boolean isInterState = !suppCode.equalsIgnoreCase(custCode);

        double cgstRate = isInterState ? 0.0 : (gstRate / 2.0);
        double sgstRate = isInterState ? 0.0 : (gstRate / 2.0);
        double igstRate = isInterState ? gstRate : 0.0;

        long cgstPaise = isInterState ? 0L : Math.round(taxablePaise * (cgstRate / 100.0));
        long sgstPaise = isInterState ? 0L : Math.round(taxablePaise * (sgstRate / 100.0));
        long igstPaise = isInterState ? Math.round(taxablePaise * (igstRate / 100.0)) : 0L;
        long totalTaxPaise = cgstPaise + sgstPaise + igstPaise;

        return new TaxBreakdown(
                hsnCode,
                taxablePaise,
                gstRate,
                cgstRate,
                sgstRate,
                igstRate,
                cgstPaise,
                sgstPaise,
                igstPaise,
                totalTaxPaise,
                isInterState,
                notifRef
        );
    }
}
