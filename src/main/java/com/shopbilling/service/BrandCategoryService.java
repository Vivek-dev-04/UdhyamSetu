package com.shopbilling.service;

import com.shopbilling.db.BrandRepository;
import com.shopbilling.db.CategoryRepository;
import com.shopbilling.db.TransactionHelper;
import com.shopbilling.model.Brand;
import com.shopbilling.model.Category;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.SQLException;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Service managing Brands and Categories (PRD Section 7.2, FR-4 & FR-7).
 * Strictly contains NO JavaFX imports.
 */
public class BrandCategoryService {

    private static final Logger logger = LoggerFactory.getLogger(BrandCategoryService.class);

    private final TransactionHelper transactionHelper;
    private final BrandRepository brandRepository;
    private final CategoryRepository categoryRepository;

    public BrandCategoryService(TransactionHelper transactionHelper,
                                BrandRepository brandRepository,
                                CategoryRepository categoryRepository) {
        this.transactionHelper = Objects.requireNonNull(transactionHelper, "TransactionHelper cannot be null");
        this.brandRepository = Objects.requireNonNull(brandRepository, "BrandRepository cannot be null");
        this.categoryRepository = Objects.requireNonNull(categoryRepository, "CategoryRepository cannot be null");
    }

    // ==========================================
    // BRAND OPERATIONS
    // ==========================================

    public Brand createBrand(String name) {
        String cleanName = validateAndCleanName(name, "Brand");

        return transactionHelper.execute(conn -> {
            if (brandRepository.existsByName(conn, cleanName, null)) {
                throw new DuplicateEntityException("Brand with name '" + cleanName + "' already exists");
            }
            Brand created = brandRepository.insert(conn, cleanName);
            logger.info("Created brand id={} name='{}'", created.id(), created.name());
            return created;
        });
    }

    public Brand updateBrand(long id, String newName) {
        String cleanName = validateAndCleanName(newName, "Brand");

        return transactionHelper.execute(conn -> {
            Brand existing = brandRepository.findById(conn, id)
                    .orElseThrow(() -> new EntityNotFoundException("Brand with id=" + id + " not found"));

            if (brandRepository.existsByName(conn, cleanName, id)) {
                throw new DuplicateEntityException("Another brand with name '" + cleanName + "' already exists");
            }

            brandRepository.update(conn, id, cleanName);
            Brand updated = new Brand(id, cleanName, existing.createdAt());
            logger.info("Updated brand id={} to name='{}'", id, cleanName);
            return updated;
        });
    }

    public Optional<Brand> getBrandById(long id) {
        return transactionHelper.executeRead(conn -> brandRepository.findById(conn, id));
    }

    public List<Brand> getAllBrands() {
        return transactionHelper.executeRead(brandRepository::findAll);
    }

    // ==========================================
    // CATEGORY OPERATIONS
    // ==========================================

    public Category createCategory(String name) {
        String cleanName = validateAndCleanName(name, "Category");

        return transactionHelper.execute(conn -> {
            if (categoryRepository.existsByName(conn, cleanName, null)) {
                throw new DuplicateEntityException("Category with name '" + cleanName + "' already exists");
            }
            Category created = categoryRepository.insert(conn, cleanName);
            logger.info("Created category id={} name='{}'", created.id(), created.name());
            return created;
        });
    }

    public Category updateCategory(long id, String newName) {
        String cleanName = validateAndCleanName(newName, "Category");

        return transactionHelper.execute(conn -> {
            Category existing = categoryRepository.findById(conn, id)
                    .orElseThrow(() -> new EntityNotFoundException("Category with id=" + id + " not found"));

            if (categoryRepository.existsByName(conn, cleanName, id)) {
                throw new DuplicateEntityException("Another category with name '" + cleanName + "' already exists");
            }

            categoryRepository.update(conn, id, cleanName);
            Category updated = new Category(id, cleanName, existing.createdAt());
            logger.info("Updated category id={} to name='{}'", id, cleanName);
            return updated;
        });
    }

    public Optional<Category> getCategoryById(long id) {
        return transactionHelper.executeRead(conn -> categoryRepository.findById(conn, id));
    }

    public List<Category> getAllCategories() {
        return transactionHelper.executeRead(categoryRepository::findAll);
    }

    private String validateAndCleanName(String name, String entityType) {
        if (name == null || name.trim().isEmpty()) {
            throw new ValidationException(entityType + " name cannot be blank");
        }
        return name.trim();
    }
}
