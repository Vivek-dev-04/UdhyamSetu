@echo off
REM ==============================================================================
REM UdhyamSetu Desktop Application Windows Installer Packaging Script
REM Uses JDK 21 jpackage to build a standalone, zero-dependency Windows MSI installer
REM (PRD Section 9, Section 10, Section 12 Phase 10)
REM ==============================================================================

echo [1/3] Building application JAR and copying runtime dependencies...
call mvn clean package -DskipTests
if %ERRORLEVEL% NEQ 0 (
    echo Error: Maven package build failed.
    exit /b %ERRORLEVEL%
)

if not exist dist mkdir dist

echo [2/3] Bundling standalone Windows MSI installer via jpackage...
jpackage ^
  --type msi ^
  --input target ^
  --dest dist ^
  --name UdhyamSetu ^
  --main-jar shop-billing-app-0.1.0-SNAPSHOT.jar ^
  --main-class com.shopbilling.ShopBillingApp ^
  --app-version 0.1.0 ^
  --vendor "UdhyamSetu" ^
  --description "Offline desktop billing and stock management software for electrical goods shops" ^
  --win-dir-chooser ^
  --win-menu ^
  --win-shortcut

if %ERRORLEVEL% NEQ 0 (
    echo Error: jpackage failed to generate Windows installer.
    exit /b %ERRORLEVEL%
)

echo [3/3] Build complete! Standalone installer located in: dist\UdhyamSetu-0.1.0.msi
