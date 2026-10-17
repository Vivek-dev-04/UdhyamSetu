package com.shopbilling.service;

import com.shopbilling.db.ConnectionFactory;
import com.shopbilling.db.CustomerRepository;
import com.shopbilling.db.DatabaseConfig;
import com.shopbilling.db.FlywayMigrator;
import com.shopbilling.db.SupplierRepository;
import com.shopbilling.db.TransactionHelper;
import com.shopbilling.model.Customer;
import com.shopbilling.model.CustomerType;
import com.shopbilling.model.Money;
import com.shopbilling.model.Supplier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CustomerSupplierServiceTest {

    private CustomerSupplierService service;

    @BeforeEach
    void setUp(@TempDir Path tempDir) {
        Path dbFile = tempDir.resolve("cust_supp_test.db");
        DatabaseConfig config = DatabaseConfig.forFile(dbFile);
        ConnectionFactory connectionFactory = new ConnectionFactory(config);

        // Run migrations
        FlywayMigrator migrator = new FlywayMigrator(connectionFactory);
        migrator.migrate();

        TransactionHelper transactionHelper = new TransactionHelper(connectionFactory);
        CustomerRepository customerRepo = new CustomerRepository();
        SupplierRepository supplierRepo = new SupplierRepository();

        service = new CustomerSupplierService(transactionHelper, customerRepo, supplierRepo);
    }

    @Test
    @DisplayName("Should create retail and technician customers with credit limits")
    void shouldCreateCustomers() {
        Customer retail = service.createCustomer(
                "Ramesh Sharma",
                "9876543210",
                CustomerType.RETAIL,
                "Shop No. 4, Main Bazar",
                null,
                "Walk-in regular customer"
        );

        Customer tech = service.createCustomer(
                "Pooja Electric Works (Pooja Singh)",
                "9811223344",
                CustomerType.TECHNICIAN,
                "Industrial Area Phase 1",
                Money.ofRupees(50000), // Rs 50,000 credit limit
                "Local contractor"
        );

        assertNotNull(retail.id());
        assertEquals("Ramesh Sharma", retail.name());
        assertEquals(CustomerType.RETAIL, retail.customerType());
        assertNull(retail.creditLimit());

        assertNotNull(tech.id());
        assertEquals(CustomerType.TECHNICIAN, tech.customerType());
        assertEquals(Money.ofRupees(50000), tech.creditLimit());
    }

    @Test
    @DisplayName("Should reject negative credit limit")
    void shouldRejectNegativeCreditLimit() {
        assertThrows(ValidationException.class, () ->
                service.createCustomer(
                        "Faulty Customer",
                        "9999999999",
                        CustomerType.RETAIL,
                        null,
                        Money.ofPaise(-500),
                        null
                )
        );
    }

    @Test
    @DisplayName("Should search customers by name or phone")
    void shouldSearchCustomers() {
        service.createCustomer("Sunil Electricals", "9876500001", CustomerType.TECHNICIAN, null, null, null);
        service.createCustomer("Anil Sharma", "9876500002", CustomerType.RETAIL, null, null, null);
        service.createCustomer("Vikas Kumar", "9123400003", CustomerType.RETAIL, null, null, null);

        // Search by partial name
        List<Customer> byName = service.searchCustomers("nil");
        assertEquals(2, byName.size());

        // Search by phone snippet
        List<Customer> byPhone = service.searchCustomers("91234");
        assertEquals(1, byPhone.size());
        assertEquals("Vikas Kumar", byPhone.get(0).name());
    }

    @Test
    @DisplayName("Should filter customers by CustomerType")
    void shouldFilterByType() {
        service.createCustomer("Customer A", null, CustomerType.RETAIL, null, null, null);
        service.createCustomer("Technician B", null, CustomerType.TECHNICIAN, null, null, null);
        service.createCustomer("Technician C", null, CustomerType.TECHNICIAN, null, null, null);

        List<Customer> techs = service.getCustomersByType(CustomerType.TECHNICIAN);
        assertEquals(2, techs.size());

        List<Customer> retail = service.getCustomersByType(CustomerType.RETAIL);
        assertEquals(1, retail.size());
    }

    @Test
    @DisplayName("Should update customer information")
    void shouldUpdateCustomer() {
        Customer c = service.createCustomer("Amit", "9000000001", CustomerType.RETAIL, null, null, null);

        Customer updated = service.updateCustomer(
                c.id(),
                "Amit Verma",
                "9000000002",
                CustomerType.TECHNICIAN,
                "New Delhi",
                Money.ofRupees(20000),
                "Upgraded to technician"
        );

        assertEquals("Amit Verma", updated.name());
        assertEquals("9000000002", updated.phone());
        assertEquals(CustomerType.TECHNICIAN, updated.customerType());
        assertEquals(Money.ofRupees(20000), updated.creditLimit());
    }

    @Test
    @DisplayName("Should create supplier with valid 15-character GSTIN")
    void shouldCreateSupplier() {
        Supplier s = service.createSupplier(
                "Havells India Distribution",
                "0112345678",
                "Rajesh Khanna",
                "Okhla Industrial Area",
                "07AAAAA0000A1Z5", // 15 characters
                "Authorized distributor"
        );

        assertNotNull(s.id());
        assertEquals("Havells India Distribution", s.name());
        assertEquals("07AAAAA0000A1Z5", s.gstin());
    }

    @Test
    @DisplayName("Should reject invalid GSTIN length")
    void shouldRejectInvalidGstin() {
        assertThrows(ValidationException.class, () ->
                service.createSupplier(
                        "Bad Supplier",
                        null,
                        null,
                        null,
                        "12345", // too short
                        null
                )
        );
    }

    @Test
    @DisplayName("Should search suppliers by name, contact person, or GSTIN")
    void shouldSearchSuppliers() {
        service.createSupplier("Polycab Wire Agency", "9810011111", "Manoj Gupta", null, "07BBBBB0000B1Z1", null);
        service.createSupplier("Anchor Electricals Depot", "9810022222", "Deepak Sharma", null, "07CCCCC0000C1Z2", null);

        List<Supplier> byContact = service.searchSuppliers("manoj");
        assertEquals(1, byContact.size());
        assertEquals("Polycab Wire Agency", byContact.get(0).name());

        List<Supplier> byGstin = service.searchSuppliers("07CCCCC");
        assertEquals(1, byGstin.size());
        assertEquals("Anchor Electricals Depot", byGstin.get(0).name());
    }
}
