#!/usr/bin/env bash
# ==============================================================================
# UdhyamSetu Desktop Application Standalone Packaging Script
# Uses JDK 21 jpackage to build a standalone, self-contained desktop runtime image
# (PRD Section 9, Section 10, Section 12 Phase 10)
# ==============================================================================
set -euo pipefail

echo "[1/3] Compiling and packaging application JAR with runtime dependencies..."
./mvnw clean package -DskipTests

mkdir -p dist

echo "[2/3] Executing jpackage to build self-contained application runtime..."
jpackage \
  --type app-image \
  --input target \
  --dest dist \
  --name UdhyamSetu \
  --main-jar shop-billing-app-0.1.0-SNAPSHOT.jar \
  --main-class com.shopbilling.ShopBillingApp \
  --app-version 0.1.0 \
  --vendor "UdhyamSetu" \
  --description "Offline desktop billing and stock management software for electrical goods shops"

echo "[3/3] Packaging successful! Self-contained desktop application image created in: dist/UdhyamSetu"
