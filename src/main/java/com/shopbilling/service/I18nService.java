package com.shopbilling.service;

import com.shopbilling.db.SettingsRepository;
import com.shopbilling.db.TransactionHelper;
import com.shopbilling.model.AppLocale;
import com.shopbilling.model.Money;
import com.shopbilling.service.pdf.HindiNumberToWords;
import com.shopbilling.service.pdf.IndianNumberToWords;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.text.MessageFormat;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Localization and Internationalization service providing dual English and Hindi
 * terminology, error messages, and currency formatting (PRD Section 9, Section 15 Question 8).
 * Pure service: strictly contains NO JavaFX imports.
 */
public class I18nService {

    private static final Logger logger = LoggerFactory.getLogger(I18nService.class);
    private static final String SETTING_KEY_LOCALE = "APP_LOCALE";

    private final TransactionHelper transactionHelper;
    private final SettingsRepository settingsRepository;

    private volatile AppLocale currentLocale = AppLocale.ENGLISH;

    private static final Map<String, String> EN_MESSAGES = new HashMap<>();
    private static final Map<String, String> HI_MESSAGES = new HashMap<>();

    static {
        // English Translations
        EN_MESSAGES.put("app.title", "UdhyamSetu - Electrical Shop Billing System");
        EN_MESSAGES.put("btn.generate_bill", "Generate Bill");
        EN_MESSAGES.put("btn.register_sale", "Register Sale");
        EN_MESSAGES.put("btn.sales", "Sales & Returns");
        EN_MESSAGES.put("btn.stock", "Stock & Purchases");
        EN_MESSAGES.put("btn.credit", "Customer & Supplier Ledgers");
        EN_MESSAGES.put("btn.expenses", "Expenses & Day-End Closing");
        EN_MESSAGES.put("btn.reports", "Reports & Profit Analysis");
        EN_MESSAGES.put("btn.settings", "Settings & Admin");

        EN_MESSAGES.put("bill.tax_invoice", "TAX INVOICE");
        EN_MESSAGES.put("bill.number", "Bill No");
        EN_MESSAGES.put("bill.date", "Date");
        EN_MESSAGES.put("bill.customer", "Customer");
        EN_MESSAGES.put("bill.phone", "Phone");
        EN_MESSAGES.put("bill.item", "Item Description");
        EN_MESSAGES.put("bill.qty", "Qty");
        EN_MESSAGES.put("bill.rate", "Rate");
        EN_MESSAGES.put("bill.discount", "Discount");
        EN_MESSAGES.put("bill.total", "Total");
        EN_MESSAGES.put("bill.payment_mode", "Payment Mode");
        EN_MESSAGES.put("bill.thank_you", "Thank You, Visit Again!");

        EN_MESSAGES.put("err.insufficient_stock", "Insufficient stock available for {0}");
        EN_MESSAGES.put("err.owner_only", "This operation is restricted to the shop owner");
        EN_MESSAGES.put("err.discount_exceeded", "Discount exceeds the maximum allowed limit of {0}%");
        EN_MESSAGES.put("err.day_already_closed", "Day {0} has already been closed");

        EN_MESSAGES.put("status.active", "Active");
        EN_MESSAGES.put("status.expired", "Expired");
        EN_MESSAGES.put("status.claimed", "Claimed / Serviced");
        EN_MESSAGES.put("status.voided", "Voided");

        // Hindi Translations
        HI_MESSAGES.put("app.title", "उद्यमसेतु - विद्युत उपकरण बिलिंग प्रणाली");
        HI_MESSAGES.put("btn.generate_bill", "बिल बनाएं");
        HI_MESSAGES.put("btn.register_sale", "बिक्री दर्ज करें");
        HI_MESSAGES.put("btn.sales", "बिक्री और वापसी");
        HI_MESSAGES.put("btn.stock", "स्टॉक और खरीद");
        HI_MESSAGES.put("btn.credit", "खाता बही (उधार)");
        HI_MESSAGES.put("btn.expenses", "दुकान खर्चे और बंदी");
        HI_MESSAGES.put("btn.reports", "रिपोर्ट और मुनाफा");
        HI_MESSAGES.put("btn.settings", "सेटिंग्स और व्यवस्थापक");

        HI_MESSAGES.put("bill.tax_invoice", "कर चालान / पक्का बिल");
        HI_MESSAGES.put("bill.number", "बिल संख्या");
        HI_MESSAGES.put("bill.date", "दिनांक");
        HI_MESSAGES.put("bill.customer", "ग्राहक का नाम");
        HI_MESSAGES.put("bill.phone", "फोन नंबर");
        HI_MESSAGES.put("bill.item", "सामान का विवरण");
        HI_MESSAGES.put("bill.qty", "मात्रा");
        HI_MESSAGES.put("bill.rate", "दर / भाव");
        HI_MESSAGES.put("bill.discount", "छूट");
        HI_MESSAGES.put("bill.total", "कुल योग");
        HI_MESSAGES.put("bill.payment_mode", "भुगतान प्रकार");
        HI_MESSAGES.put("bill.thank_you", "धन्यवाद, फिर पधारें!");

        HI_MESSAGES.put("err.insufficient_stock", "सामान {0} के लिए पर्याप्त स्टॉक उपलब्ध नहीं है");
        HI_MESSAGES.put("err.owner_only", "यह कार्य केवल दुकान मालिक द्वारा किया जा सकता है");
        HI_MESSAGES.put("err.discount_exceeded", "छूट अनुमत सीमा {0}% से अधिक है");
        HI_MESSAGES.put("err.day_already_closed", "तारीख {0} का खाता पहले ही बंद हो चुका है");

        HI_MESSAGES.put("status.active", "सक्रिय (वारंटी में)");
        HI_MESSAGES.put("status.expired", "समाप्त (एक्सपायर्ड)");
        HI_MESSAGES.put("status.claimed", "सेवा प्राप्त / बदला गया");
        HI_MESSAGES.put("status.voided", "रद्द");
    }

    public I18nService(TransactionHelper transactionHelper, SettingsRepository settingsRepository) {
        this.transactionHelper = Objects.requireNonNull(transactionHelper, "TransactionHelper cannot be null");
        this.settingsRepository = Objects.requireNonNull(settingsRepository, "SettingsRepository cannot be null");

        // Load configured locale from DB if present
        try {
            transactionHelper.executeRead(conn -> {
                settingsRepository.getSetting(conn, SETTING_KEY_LOCALE)
                        .ifPresent(val -> this.currentLocale = AppLocale.fromCode(val));
                return null;
            });
        } catch (Exception e) {
            logger.warn("Could not load initial locale from settings, defaulting to English: {}", e.getMessage());
            this.currentLocale = AppLocale.ENGLISH;
        }
    }

    public AppLocale getCurrentLocale() {
        return currentLocale;
    }

    public void setLocale(AppLocale locale, long userId) {
        Objects.requireNonNull(locale, "Locale cannot be null");
        this.currentLocale = locale;

        transactionHelper.executeVoid(conn ->
                settingsRepository.setSetting(conn, SETTING_KEY_LOCALE, locale.getCode(), userId)
        );
        logger.info("Language changed to {} by user={}", locale.name(), userId);
    }

    /**
     * Translates the key using the current application locale.
     */
    public String t(String key, Object... args) {
        return t(this.currentLocale, key, args);
    }

    /**
     * Translates the key using a specified locale with parameter interpolation.
     */
    public String t(AppLocale locale, String key, Object... args) {
        Objects.requireNonNull(key, "Translation key cannot be null");
        Map<String, String> dict = (locale == AppLocale.HINDI) ? HI_MESSAGES : EN_MESSAGES;

        String template = dict.get(key);
        if (template == null) {
            // Fallback to English dictionary
            template = EN_MESSAGES.getOrDefault(key, key);
        }

        if (args != null && args.length > 0) {
            return MessageFormat.format(template, args);
        }
        return template;
    }

    /**
     * Converts a monetary amount to words in the current locale.
     */
    public String formatMoneyInWords(Money money) {
        return formatMoneyInWords(money, this.currentLocale);
    }

    /**
     * Converts a monetary amount to words in a specified locale.
     */
    public String formatMoneyInWords(Money money, AppLocale locale) {
        Objects.requireNonNull(money, "Money cannot be null");
        if (locale == AppLocale.HINDI) {
            return HindiNumberToWords.convertToWords(money.getPaise());
        } else {
            return IndianNumberToWords.convertToWords(money.getPaise() / 100);
        }
    }

    public Map<String, String> getLoadedMessages(AppLocale locale) {
        return Collections.unmodifiableMap(locale == AppLocale.HINDI ? HI_MESSAGES : EN_MESSAGES);
    }
}
