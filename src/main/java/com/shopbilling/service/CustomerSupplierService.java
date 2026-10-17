package com.shopbilling.service;

import com.shopbilling.db.CustomerRepository;
import com.shopbilling.db.SupplierRepository;
import com.shopbilling.db.TransactionHelper;
import com.shopbilling.model.Customer;
import com.shopbilling.model.CustomerType;
import com.shopbilling.model.Money;
import com.shopbilling.model.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Service managing Customers and Suppliers (PRD Section 7.3, FR-10 & Section 7.6, FR-26, FR-29).
 * Strictly contains NO JavaFX imports.
 */
public class CustomerSupplierService {

    private static final Logger logger = LoggerFactory.getLogger(CustomerSupplierService.class);

    private final TransactionHelper transactionHelper;
    private final CustomerRepository customerRepository;
    private final SupplierRepository supplierRepository;

    public CustomerSupplierService(TransactionHelper transactionHelper,
                                   CustomerRepository customerRepository,
                                   SupplierRepository supplierRepository) {
        this.transactionHelper = Objects.requireNonNull(transactionHelper, "TransactionHelper cannot be null");
        this.customerRepository = Objects.requireNonNull(customerRepository, "CustomerRepository cannot be null");
        this.supplierRepository = Objects.requireNonNull(supplierRepository, "SupplierRepository cannot be null");
    }

    // ==========================================
    // CUSTOMER OPERATIONS
    // ==========================================

    public Customer createCustomer(String name, String phone, CustomerType customerType, String address, Money creditLimit, String notes) {
        String cleanName = validateNonBlank(name, "Customer name");
        CustomerType type = customerType != null ? customerType : CustomerType.RETAIL;

        if (creditLimit != null && creditLimit.isNegative()) {
            throw new ValidationException("Credit limit cannot be negative: " + creditLimit);
        }

        Customer customer = new Customer(null, cleanName, phone, type, address, creditLimit, notes, null);

        return transactionHelper.execute(conn -> {
            Customer created = customerRepository.insert(conn, customer);
            logger.info("Created customer id={} name='{}' type={}", created.id(), created.name(), created.customerType());
            return created;
        });
    }

    public Customer updateCustomer(long id, String name, String phone, CustomerType customerType, String address, Money creditLimit, String notes) {
        String cleanName = validateNonBlank(name, "Customer name");
        CustomerType type = customerType != null ? customerType : CustomerType.RETAIL;

        if (creditLimit != null && creditLimit.isNegative()) {
            throw new ValidationException("Credit limit cannot be negative: " + creditLimit);
        }

        return transactionHelper.execute(conn -> {
            Customer existing = customerRepository.findById(conn, id)
                    .orElseThrow(() -> new EntityNotFoundException("Customer with id=" + id + " not found"));

            Customer updated = new Customer(id, cleanName, phone, type, address, creditLimit, notes, existing.createdAt());
            customerRepository.update(conn, updated);
            logger.info("Updated customer id={} name='{}'", id, cleanName);
            return updated;
        });
    }

    public Optional<Customer> getCustomerById(long id) {
        return transactionHelper.executeRead(conn -> customerRepository.findById(conn, id));
    }

    public List<Customer> searchCustomers(String query) {
        return transactionHelper.executeRead(conn -> {
            if (query == null || query.trim().isEmpty()) {
                return customerRepository.findAll(conn);
            }
            return customerRepository.search(conn, query.trim());
        });
    }

    public List<Customer> getCustomersByType(CustomerType type) {
        Objects.requireNonNull(type, "CustomerType cannot be null");
        return transactionHelper.executeRead(conn -> customerRepository.findByType(conn, type));
    }

    public List<Customer> getAllCustomers() {
        return transactionHelper.executeRead(customerRepository::findAll);
    }

    // ==========================================
    // SUPPLIER OPERATIONS
    // ==========================================

    public Supplier createSupplier(String name, String phone, String contactPerson, String address, String gstin, String notes) {
        String cleanName = validateNonBlank(name, "Supplier name");
        String cleanGstin = validateGstin(gstin);

        Supplier supplier = new Supplier(null, cleanName, phone, contactPerson, address, cleanGstin, notes, null);

        return transactionHelper.execute(conn -> {
            Supplier created = supplierRepository.insert(conn, supplier);
            logger.info("Created supplier id={} name='{}'", created.id(), created.name());
            return created;
        });
    }

    public Supplier updateSupplier(long id, String name, String phone, String contactPerson, String address, String gstin, String notes) {
        String cleanName = validateNonBlank(name, "Supplier name");
        String cleanGstin = validateGstin(gstin);

        return transactionHelper.execute(conn -> {
            Supplier existing = supplierRepository.findById(conn, id)
                    .orElseThrow(() -> new EntityNotFoundException("Supplier with id=" + id + " not found"));

            Supplier updated = new Supplier(id, cleanName, phone, contactPerson, address, cleanGstin, notes, existing.createdAt());
            supplierRepository.update(conn, updated);
            logger.info("Updated supplier id={} name='{}'", id, cleanName);
            return updated;
        });
    }

    public Optional<Supplier> getSupplierById(long id) {
        return transactionHelper.executeRead(conn -> supplierRepository.findById(conn, id));
    }

    public List<Supplier> searchSuppliers(String query) {
        return transactionHelper.executeRead(conn -> {
            if (query == null || query.trim().isEmpty()) {
                return supplierRepository.findAll(conn);
            }
            return supplierRepository.search(conn, query.trim());
        });
    }

    public List<Supplier> getAllSuppliers() {
        return transactionHelper.executeRead(supplierRepository::findAll);
    }

    private String validateGstin(String gstin) {
        if (gstin == null || gstin.trim().isEmpty()) {
            return null;
        }
        String clean = gstin.trim().toUpperCase();
        if (clean.length() != 15) {
            throw new ValidationException("GSTIN must be 15 characters long if provided: " + clean);
        }
        return clean;
    }

    private String validateNonBlank(String value, String fieldName) {
        if (value == null || value.trim().isEmpty()) {
            throw new ValidationException(fieldName + " cannot be blank");
        }
        return value.trim();
    }
}
