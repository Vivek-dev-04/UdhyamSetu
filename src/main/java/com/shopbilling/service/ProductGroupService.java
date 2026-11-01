package com.shopbilling.service;

import com.shopbilling.db.ProductGroupRepository;
import com.shopbilling.db.TransactionHelper;
import com.shopbilling.model.Product;
import com.shopbilling.model.ProductGroup;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Service managing Product Groups and Classification/Tax Inheritance (Section 9: Tally-inspired hierarchy).
 */
public class ProductGroupService {

    private static final Logger logger = LoggerFactory.getLogger(ProductGroupService.class);

    private final TransactionHelper transactionHelper;
    private final ProductGroupRepository groupRepository;

    public ProductGroupService(TransactionHelper transactionHelper, ProductGroupRepository groupRepository) {
        this.transactionHelper = Objects.requireNonNull(transactionHelper, "TransactionHelper cannot be null");
        this.groupRepository = Objects.requireNonNull(groupRepository, "ProductGroupRepository cannot be null");
    }

    public ProductGroup createGroup(String code, String name, String defaultHsn, Double defaultGst, String description, Long parentGroupId) {
        Objects.requireNonNull(code, "Group code cannot be null");
        Objects.requireNonNull(name, "Group name cannot be null");

        return transactionHelper.execute(conn -> {
            if (groupRepository.findByCode(conn, code).isPresent()) {
                throw new ValidationException("Product group code '" + code + "' already exists");
            }
            ProductGroup group = new ProductGroup(null, parentGroupId, code, name, defaultHsn, defaultGst, description, true, null);
            return groupRepository.insert(conn, group);
        });
    }

    public boolean updateGroup(ProductGroup group) {
        return transactionHelper.execute(conn -> groupRepository.update(conn, group));
    }

    public boolean deleteGroup(long id) {
        return transactionHelper.execute(conn -> groupRepository.delete(conn, id));
    }

    public Optional<ProductGroup> getGroupById(long id) {
        return transactionHelper.executeRead(conn -> groupRepository.findById(conn, id));
    }

    public Optional<ProductGroup> getGroupByCode(String code) {
        return transactionHelper.executeRead(conn -> groupRepository.findByCode(conn, code));
    }

    public List<ProductGroup> getAllGroups(boolean activeOnly) {
        return transactionHelper.executeRead(conn -> groupRepository.findAll(conn, activeOnly));
    }

    /**
     * Resolves the effective HSN and GST rate for a product adhering to Section 9:
     * - Individual product settings always override group settings.
     * - If product settings are missing, inherits group defaults.
     */
    public record EffectiveClassification(String hsnCode, double gstRate, String source) {}

    public EffectiveClassification resolveEffectiveClassification(Product product) {
        Objects.requireNonNull(product, "Product cannot be null");

        if (product.hsnCode() != null && !product.hsnCode().isBlank()) {
            double rate = product.gstRate() != null ? product.gstRate() : 18.0;
            return new EffectiveClassification(product.hsnCode(), rate, "Product Override");
        }

        if (product.groupId() != null) {
            Optional<ProductGroup> gOpt = getGroupById(product.groupId());
            if (gOpt.isPresent()) {
                ProductGroup g = gOpt.get();
                if (g.defaultHsnCode() != null && !g.defaultHsnCode().isBlank()) {
                    double rate = g.defaultGstRate() != null ? g.defaultGstRate() : 18.0;
                    return new EffectiveClassification(g.defaultHsnCode(), rate, "Inherited from Group '" + g.groupName() + "'");
                }
            }
        }

        return new EffectiveClassification(null, product.gstRate() != null ? product.gstRate() : 18.0, "Default");
    }
}
