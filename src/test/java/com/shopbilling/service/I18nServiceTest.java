package com.shopbilling.service;

import com.shopbilling.db.ConnectionFactory;
import com.shopbilling.db.DatabaseConfig;
import com.shopbilling.db.FlywayMigrator;
import com.shopbilling.db.SettingsRepository;
import com.shopbilling.db.TransactionHelper;
import com.shopbilling.db.UserRepository;
import com.shopbilling.model.AppLocale;
import com.shopbilling.model.Money;
import com.shopbilling.model.User;
import com.shopbilling.model.UserRole;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("I18nService Tests (English and Hindi Dual-Language Localization)")
class I18nServiceTest {

    @TempDir
    Path tempDir;

    private ConnectionFactory connectionFactory;
    private TransactionHelper transactionHelper;
    private SettingsRepository settingsRepository;
    private I18nService i18nService;
    private long ownerUserId;

    @BeforeEach
    void setUp() throws IOException {
        Path dbPath = tempDir.resolve("test_i18n.db");
        DatabaseConfig config = DatabaseConfig.forFile(dbPath);
        connectionFactory = new ConnectionFactory(config);
        new FlywayMigrator(connectionFactory).migrate();

        transactionHelper = new TransactionHelper(connectionFactory);
        settingsRepository = new SettingsRepository();

        UserRepository userRepository = new UserRepository();
        transactionHelper.executeVoid(conn -> {
            User owner = userRepository.insert(conn, new User(null, "owner", "pass", UserRole.OWNER, "Shop Owner", true, null));
            ownerUserId = owner.id();
        });

        i18nService = new I18nService(transactionHelper, settingsRepository);
    }

    @Test
    @DisplayName("Should initialize with English by default")
    void testDefaultLocale() {
        assertEquals(AppLocale.ENGLISH, i18nService.getCurrentLocale());
        assertEquals("Generate Bill", i18nService.t("btn.generate_bill"));
        assertEquals("TAX INVOICE", i18nService.t("bill.tax_invoice"));
    }

    @Test
    @DisplayName("Should interpolate parameters in translated strings")
    void testParameterInterpolation() {
        String msgEn = i18nService.t(AppLocale.ENGLISH, "err.insufficient_stock", "Anchor Switch");
        assertEquals("Insufficient stock available for Anchor Switch", msgEn);

        String msgHi = i18nService.t(AppLocale.HINDI, "err.insufficient_stock", "Anchor Switch");
        assertEquals("सामान Anchor Switch के लिए पर्याप्त स्टॉक उपलब्ध नहीं है", msgHi);
    }

    @Test
    @DisplayName("Should translate keys in Hindi correctly")
    void testHindiTranslations() {
        assertEquals("बिल बनाएं", i18nService.t(AppLocale.HINDI, "btn.generate_bill"));
        assertEquals("बिक्री दर्ज करें", i18nService.t(AppLocale.HINDI, "btn.register_sale"));
        assertEquals("कर चालान / पक्का बिल", i18nService.t(AppLocale.HINDI, "bill.tax_invoice"));
        assertEquals("सक्रिय (वारंटी में)", i18nService.t(AppLocale.HINDI, "status.active"));
    }

    @Test
    @DisplayName("Should switch locale and persist preference into settings")
    void testLocaleSwitchingAndPersistence() {
        i18nService.setLocale(AppLocale.HINDI, ownerUserId);
        assertEquals(AppLocale.HINDI, i18nService.getCurrentLocale());
        assertEquals("बिल बनाएं", i18nService.t("btn.generate_bill"));

        // Instantiate new service instance to verify persistence in DB
        I18nService newService = new I18nService(transactionHelper, settingsRepository);
        assertEquals(AppLocale.HINDI, newService.getCurrentLocale());
    }

    @Test
    @DisplayName("Should convert money to Hindi words accurately")
    void testHindiCurrencyToWords() {
        assertEquals("शून्य रुपये केवल", i18nService.formatMoneyInWords(Money.ZERO, AppLocale.HINDI));

        Money fiveHundred = Money.ofPaise(50000);
        assertEquals("पाँच सौ रुपये केवल", i18nService.formatMoneyInWords(fiveHundred, AppLocale.HINDI));

        Money fifteenHundred = Money.ofPaise(150000);
        assertEquals("एक हज़ार पाँच सौ रुपये केवल", i18nService.formatMoneyInWords(fifteenHundred, AppLocale.HINDI));

        Money twoLakhFiftyThousand = Money.ofPaise(25000000);
        assertEquals("दो लाख पचास हज़ार रुपये केवल", i18nService.formatMoneyInWords(twoLakhFiftyThousand, AppLocale.HINDI));
    }

    @Test
    @DisplayName("Should convert money to English words accurately")
    void testEnglishCurrencyToWords() {
        Money fifteenHundred = Money.ofPaise(150000);
        String inWords = i18nService.formatMoneyInWords(fifteenHundred, AppLocale.ENGLISH);
        assertTrue(inWords.toLowerCase().contains("one thousand five hundred"));
    }

    @Test
    @DisplayName("Should fallback gracefully when translation key is not found")
    void testFallbackForMissingKey() {
        assertEquals("some.nonexistent.key", i18nService.t("some.nonexistent.key"));
    }
}
