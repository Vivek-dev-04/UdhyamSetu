package com.shopbilling;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Placeholder verification test for M01 project skeleton.
 */
class AppSkeletonTest {

    private static final Logger logger = LoggerFactory.getLogger(AppSkeletonTest.class);

    @Test
    @DisplayName("Project skeleton initializes logging and core packages correctly")
    void shouldInitializeSkeletonAndLogger() {
        logger.info("Running M01 placeholder verification test...");

        assertDoesNotThrow(() -> {
            logger.debug("Testing debug-level logging.");
            logger.info("Testing info-level logging.");
        });

        // Verify package structure exists
        Package appPkg = ShopBillingApp.class.getPackage();
        assertNotNull(appPkg, "Base package should exist");
        assertTrue(appPkg.getName().startsWith("com.shopbilling"));
    }
}
