package com.shopbilling.service;

import com.shopbilling.db.BrandRepository;
import com.shopbilling.db.CategoryRepository;
import com.shopbilling.db.ProductRepository;
import com.shopbilling.db.ProductVariantRepository;
import com.shopbilling.db.TransactionHelper;
import com.shopbilling.db.UnitRepository;
import com.shopbilling.model.Brand;
import com.shopbilling.model.Category;
import com.shopbilling.model.Money;
import com.shopbilling.model.Product;
import com.shopbilling.model.ProductVariant;
import com.shopbilling.model.ProductWithVariants;
import com.shopbilling.model.Quantity;
import com.shopbilling.model.Unit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Service managing Products, Variants, Units, and Barcodes (PRD Section 7.2, FR-4, FR-5, FR-6).
 * Strictly contains NO JavaFX imports.
 */
public class ProductService {

    private static final Logger logger = LoggerFactory.getLogger(ProductService.class);

    private final TransactionHelper transactionHelper;
    private final ProductRepository productRepository;
    private final ProductVariantRepository variantRepository;
    private final BrandRepository brandRepository;
    private final CategoryRepository categoryRepository;
    private final UnitRepository unitRepository;

    public ProductService(TransactionHelper transactionHelper,
                          ProductRepository productRepository,
                          ProductVariantRepository variantRepository,
                          BrandRepository brandRepository,
                          CategoryRepository categoryRepository,
                          UnitRepository unitRepository) {
        this.transactionHelper = Objects.requireNonNull(transactionHelper, "TransactionHelper cannot be null");
        this.productRepository = Objects.requireNonNull(productRepository, "ProductRepository cannot be null");
        this.variantRepository = Objects.requireNonNull(variantRepository, "ProductVariantRepository cannot be null");
        this.brandRepository = Objects.requireNonNull(brandRepository, "BrandRepository cannot be null");
        this.categoryRepository = Objects.requireNonNull(categoryRepository, "CategoryRepository cannot be null");
        this.unitRepository = Objects.requireNonNull(unitRepository, "UnitRepository cannot be null");
    }

    public Product createProduct(long brandId, long categoryId, String name, String unitCode, Quantity reorderLevel) {
        String cleanName = validateNonBlank(name, "Product");
        String cleanUnit = validateNonBlank(unitCode, "Unit code").toUpperCase();

        return transactionHelper.execute(conn -> {
            // Validate references
            Brand brand = brandRepository.findById(conn, brandId)
                    .orElseThrow(() -> new EntityNotFoundException("Brand id=" + brandId + " not found"));
            Category category = categoryRepository.findById(conn, categoryId)
                    .orElseThrow(() -> new EntityNotFoundException("Category id=" + categoryId + " not found"));
            Unit unit = unitRepository.findByCode(conn, cleanUnit)
                    .orElseThrow(() -> new EntityNotFoundException("Unit code '" + cleanUnit + "' not found"));

            if (productRepository.existsByNameInBrandAndCategory(conn, brandId, categoryId, cleanName, null)) {
                throw new DuplicateEntityException("Product '" + cleanName + "' already exists under brand '"
                        + brand.name() + "' and category '" + category.name() + "'");
            }

            Long reorderScaled = null;
            if (reorderLevel != null) {
                if (reorderLevel.isNegative()) {
                    throw new ValidationException("Reorder level cannot be negative");
                }
                reorderLevel.validateForUnit(unit);
                reorderScaled = reorderLevel.getScaledValue();
            }

            Product product = new Product(null, brandId, categoryId, cleanName, cleanUnit, reorderScaled, true, null);
            Product created = productRepository.insert(conn, product);
            logger.info("Created product id={} name='{}' unit={}", created.id(), created.name(), created.unitCode());
            return created;
        });
    }

    public Product updateProduct(long id, long brandId, long categoryId, String name, String unitCode, Quantity reorderLevel, boolean isActive) {
        String cleanName = validateNonBlank(name, "Product");
        String cleanUnit = validateNonBlank(unitCode, "Unit code").toUpperCase();

        return transactionHelper.execute(conn -> {
            Product existing = productRepository.findById(conn, id)
                    .orElseThrow(() -> new EntityNotFoundException("Product id=" + id + " not found"));

            Brand brand = brandRepository.findById(conn, brandId)
                    .orElseThrow(() -> new EntityNotFoundException("Brand id=" + brandId + " not found"));
            Category category = categoryRepository.findById(conn, categoryId)
                    .orElseThrow(() -> new EntityNotFoundException("Category id=" + categoryId + " not found"));
            Unit unit = unitRepository.findByCode(conn, cleanUnit)
                    .orElseThrow(() -> new EntityNotFoundException("Unit code '" + cleanUnit + "' not found"));

            if (productRepository.existsByNameInBrandAndCategory(conn, brandId, categoryId, cleanName, id)) {
                throw new DuplicateEntityException("Another product '" + cleanName + "' already exists under brand '"
                        + brand.name() + "' and category '" + category.name() + "'");
            }

            Long reorderScaled = null;
            if (reorderLevel != null) {
                if (reorderLevel.isNegative()) {
                    throw new ValidationException("Reorder level cannot be negative");
                }
                reorderLevel.validateForUnit(unit);
                reorderScaled = reorderLevel.getScaledValue();
            }

            Product updated = new Product(id, brandId, categoryId, cleanName, cleanUnit, reorderScaled, isActive, existing.createdAt());
            productRepository.update(conn, updated);
            logger.info("Updated product id={} name='{}'", id, cleanName);
            return updated;
        });
    }

    public ProductVariant addVariant(long productId, String variantName, Money mrp, Money sellingPrice, String barcode) {
        String cleanVariantName = validateNonBlank(variantName, "Variant");
        Objects.requireNonNull(mrp, "MRP cannot be null");
        Objects.requireNonNull(sellingPrice, "Selling price cannot be null");

        validatePrices(mrp, sellingPrice);

        String cleanBarcode = barcode != null && !barcode.trim().isEmpty() ? barcode.trim() : null;

        return transactionHelper.execute(conn -> {
            productRepository.findById(conn, productId)
                    .orElseThrow(() -> new EntityNotFoundException("Product id=" + productId + " not found"));

            if (cleanBarcode != null && variantRepository.existsByBarcode(conn, cleanBarcode, null)) {
                throw new DuplicateEntityException("Barcode '" + cleanBarcode + "' is already assigned to another variant");
            }

            ProductVariant variant = new ProductVariant(null, productId, cleanVariantName, mrp, sellingPrice, cleanBarcode, true, null);
            ProductVariant created = variantRepository.insert(conn, variant);
            logger.info("Created variant id={} for product id={} name='{}' barcode={}",
                    created.id(), productId, created.variantName(), created.barcode());
            return created;
        });
    }

    public ProductVariant updateVariant(long id, String variantName, Money mrp, Money sellingPrice, String barcode, boolean isActive) {
        String cleanVariantName = validateNonBlank(variantName, "Variant");
        Objects.requireNonNull(mrp, "MRP cannot be null");
        Objects.requireNonNull(sellingPrice, "Selling price cannot be null");

        validatePrices(mrp, sellingPrice);

        String cleanBarcode = barcode != null && !barcode.trim().isEmpty() ? barcode.trim() : null;

        return transactionHelper.execute(conn -> {
            ProductVariant existing = variantRepository.findById(conn, id)
                    .orElseThrow(() -> new EntityNotFoundException("Variant id=" + id + " not found"));

            if (cleanBarcode != null && variantRepository.existsByBarcode(conn, cleanBarcode, id)) {
                throw new DuplicateEntityException("Barcode '" + cleanBarcode + "' is already assigned to another variant");
            }

            ProductVariant updated = new ProductVariant(id, existing.productId(), cleanVariantName, mrp, sellingPrice, cleanBarcode, isActive, existing.createdAt());
            variantRepository.update(conn, updated);
            logger.info("Updated variant id={} name='{}'", id, cleanVariantName);
            return updated;
        });
    }

    public Optional<ProductWithVariants> getProductWithVariants(long productId) {
        return transactionHelper.executeRead(conn -> {
            Optional<Product> prodOpt = productRepository.findById(conn, productId);
            if (prodOpt.isEmpty()) {
                return Optional.empty();
            }
            Product product = prodOpt.get();
            Brand brand = brandRepository.findById(conn, product.brandId()).orElse(null);
            Category category = categoryRepository.findById(conn, product.categoryId()).orElse(null);
            Unit unit = unitRepository.findByCode(conn, product.unitCode()).orElse(null);
            List<ProductVariant> variants = variantRepository.findByProductId(conn, productId);

            return Optional.of(new ProductWithVariants(product, brand, category, unit, variants));
        });
    }

    public List<ProductWithVariants> searchProducts(String query) {
        return transactionHelper.executeRead(conn -> {
            List<Product> products = (query == null || query.trim().isEmpty())
                    ? productRepository.findAll(conn, true)
                    : productRepository.searchByName(conn, query.trim());

            List<ProductWithVariants> results = new ArrayList<>();
            for (Product p : products) {
                Brand brand = brandRepository.findById(conn, p.brandId()).orElse(null);
                Category category = categoryRepository.findById(conn, p.categoryId()).orElse(null);
                Unit unit = unitRepository.findByCode(conn, p.unitCode()).orElse(null);
                List<ProductVariant> variants = variantRepository.findByProductId(conn, p.id());

                if (brand != null && category != null && unit != null) {
                    results.add(new ProductWithVariants(p, brand, category, unit, variants));
                }
            }
            return results;
        });
    }

    public Optional<ProductVariant> findVariantByBarcode(String barcode) {
        if (barcode == null || barcode.trim().isEmpty()) {
            return Optional.empty();
        }
        return transactionHelper.executeRead(conn -> variantRepository.findByBarcode(conn, barcode.trim()));
    }

    public List<Unit> getAllUnits() {
        return transactionHelper.executeRead(unitRepository::findAll);
    }

    private void validatePrices(Money mrp, Money sellingPrice) {
        if (mrp.isNegative()) {
            throw new ValidationException("MRP cannot be negative: " + mrp);
        }
        if (sellingPrice.isNegative() || sellingPrice.isZero()) {
            throw new ValidationException("Selling price must be greater than zero: " + sellingPrice);
        }
        if (mrp.isPositive() && sellingPrice.isGreaterThan(mrp)) {
            throw new ValidationException("Selling price (" + sellingPrice + ") cannot exceed MRP (" + mrp + ")");
        }
    }

    private String validateNonBlank(String value, String fieldName) {
        if (value == null || value.trim().isEmpty()) {
            throw new ValidationException(fieldName + " cannot be blank");
        }
        return value.trim();
    }
}
