package com.shopbilling;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("PackagingConfig Tests (Standalone Desktop Packaging & Installer Readiness)")
class PackagingConfigTest {

    @Test
    @DisplayName("Application main entry point must be public static void main(String[])")
    void testMainMethodExists() throws NoSuchMethodException {
        Class<?> appClass = ShopBillingApp.class;
        Method mainMethod = appClass.getMethod("main", String[].class);

        assertNotNull(mainMethod);
        assertTrue(Modifier.isPublic(mainMethod.getModifiers()), "main method must be public");
        assertTrue(Modifier.isStatic(mainMethod.getModifiers()), "main method must be static");
        assertEquals(void.class, mainMethod.getReturnType(), "main method must return void");
    }

    @Test
    @DisplayName("Windows packaging script must specify valid jpackage MSI arguments")
    void testWindowsPackagingScriptConfig() throws Exception {
        Path scriptPath = Path.of("scripts", "package-windows.bat");
        assertTrue(Files.exists(scriptPath), "package-windows.bat must exist");

        String content = Files.readString(scriptPath);
        assertTrue(content.contains("--type msi"));
        assertTrue(content.contains("--name UdhyamSetu"));
        assertTrue(content.contains("--main-class com.shopbilling.ShopBillingApp"));
        assertTrue(content.contains("--app-version 0.1.0"));
        assertTrue(content.contains("--win-shortcut"));
    }

    @Test
    @DisplayName("Cross-platform app-image packaging script must specify valid jpackage arguments")
    void testAppImagePackagingScriptConfig() throws Exception {
        Path scriptPath = Path.of("scripts", "package-app.sh");
        assertTrue(Files.exists(scriptPath), "package-app.sh must exist");

        String content = Files.readString(scriptPath);
        assertTrue(content.contains("--type app-image"));
        assertTrue(content.contains("--name UdhyamSetu"));
        assertTrue(content.contains("--main-class com.shopbilling.ShopBillingApp"));
        assertTrue(content.contains("--app-version 0.1.0"));
    }

    @Test
    @DisplayName("POM build plugins must include jar and dependency copy configurations for standalone bundling")
    void testPomPackagingPluginsConfigured() throws Exception {
        Path pomPath = Path.of("pom.xml");
        assertTrue(Files.exists(pomPath));

        String content = Files.readString(pomPath);
        assertTrue(content.contains("maven-jar-plugin"), "pom.xml must configure maven-jar-plugin");
        assertTrue(content.contains("maven-dependency-plugin"), "pom.xml must configure maven-dependency-plugin");
        assertTrue(content.contains("copy-dependencies"), "pom.xml must specify copy-dependencies goal");
    }
}
