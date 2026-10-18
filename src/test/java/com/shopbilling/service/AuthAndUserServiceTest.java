package com.shopbilling.service;

import com.shopbilling.db.AuditLogRepository;
import com.shopbilling.db.ConnectionFactory;
import com.shopbilling.db.DatabaseConfig;
import com.shopbilling.db.FlywayMigrator;
import com.shopbilling.db.TransactionHelper;
import com.shopbilling.db.UserRepository;
import com.shopbilling.model.AuditLogRecord;
import com.shopbilling.model.User;
import com.shopbilling.model.UserRole;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class AuthAndUserServiceTest {

    private ConnectionFactory connectionFactory;
    private TransactionHelper transactionHelper;
    private UserRepository userRepository;
    private AuditLogRepository auditLogRepository;
    private AuditLogService auditLogService;
    private AuthService authService;
    private UserService userService;

    @BeforeEach
    void setUp(@TempDir Path tempDir) {
        Path dbPath = tempDir.resolve("auth_test.db");
        DatabaseConfig config = DatabaseConfig.forFile(dbPath);
        connectionFactory = new ConnectionFactory(config);
        new FlywayMigrator(connectionFactory).migrate();

        transactionHelper = new TransactionHelper(connectionFactory);
        userRepository = new UserRepository();
        auditLogRepository = new AuditLogRepository();
        auditLogService = new AuditLogService(transactionHelper, auditLogRepository, userRepository);
        authService = new AuthService(transactionHelper, userRepository, auditLogService);
        userService = new UserService(transactionHelper, userRepository, auditLogService);
    }

    @Test
    @DisplayName("First launch: detect fresh database and setup initial owner")
    void testFirstLaunchAndOwnerSetup() {
        assertTrue(authService.isFirstLaunch(), "Fresh database must be first launch");

        User owner = authService.setupInitialOwner("shopowner", "Secret123", "Rajesh Gupta");
        assertNotNull(owner.id());
        assertEquals("shopowner", owner.username());
        assertEquals(UserRole.OWNER, owner.role());
        assertEquals("Rajesh Gupta", owner.fullName());
        assertTrue(owner.isActive());
        assertTrue(owner.isOwner());
        assertFalse(owner.isStaff());

        assertFalse(authService.isFirstLaunch(), "Should no longer be first launch after owner creation");

        // Duplicate initial owner setup is prohibited
        assertThrows(ValidationException.class, () ->
                authService.setupInitialOwner("anotherowner", "Secret456", "Second Owner")
        );

        // Verify audit log
        List<AuditLogRecord> logs = auditLogService.getRecentLogs(owner.id(), 10);
        assertEquals(1, logs.size());
        assertEquals("INITIAL_OWNER_SETUP", logs.get(0).action());
    }

    @Test
    @DisplayName("Authentication: successful login, invalid password, unknown user, and deactivated user")
    void testAuthentication() {
        User owner = authService.setupInitialOwner("owner", "Password123", "Main Owner");

        // 1. Successful authentication
        User authenticated = authService.authenticate("owner", "Password123");
        assertNotNull(authenticated);
        assertEquals(owner.id(), authenticated.id());

        // 2. Case-insensitive username authentication
        User caseAuth = authService.authenticate("OWNER", "Password123");
        assertEquals(owner.id(), caseAuth.id());

        // 3. Incorrect password
        assertThrows(AuthenticationException.class, () ->
                authService.authenticate("owner", "WrongPassword")
        );

        // 4. Non-existent username
        assertThrows(AuthenticationException.class, () ->
                authService.authenticate("ghost", "Password123")
        );

        // 5. Blank input
        assertThrows(AuthenticationException.class, () ->
                authService.authenticate("", "Password123")
        );

        // 6. Deactivated user cannot log in
        User staff = userService.createStaffUser(owner.id(), "staff1", "StaffPass123", "Counter Staff 1");
        userService.setUserActive(owner.id(), staff.id(), false);

        AuthenticationException ex = assertThrows(AuthenticationException.class, () ->
                authService.authenticate("staff1", "StaffPass123")
        );
        assertTrue(ex.getMessage().contains("deactivated"));
    }

    @Test
    @DisplayName("User management: Owner creates staff, enforces role permissions, prevents sole owner deactivation")
    void testUserManagementAndPermissions() {
        User owner = authService.setupInitialOwner("owner", "OwnerPass123", "Shop Owner");

        // Owner creates staff
        User staff = userService.createStaffUser(owner.id(), "ramesh", "RameshPass123", "Ramesh Kumar");
        assertNotNull(staff.id());
        assertEquals(UserRole.STAFF, staff.role());
        assertTrue(staff.isStaff());

        // Duplicate username rejected
        assertThrows(DuplicateEntityException.class, () ->
                userService.createStaffUser(owner.id(), "ramesh", "AnotherPass", "Ramesh Duplicate")
        );

        // Staff user cannot manage users
        assertThrows(UnauthorizedException.class, () ->
                userService.createStaffUser(staff.id(), "suresh", "SureshPass", "Suresh Staff")
        );
        assertThrows(UnauthorizedException.class, () ->
                userService.getAllUsers(staff.id())
        );

        // Owner lists all users
        List<User> allUsers = userService.getAllUsers(owner.id());
        assertEquals(2, allUsers.size());

        // Cannot deactivate sole active owner
        ValidationException soleOwnerEx = assertThrows(ValidationException.class, () ->
                userService.setUserActive(owner.id(), owner.id(), false)
        );
        assertTrue(soleOwnerEx.getMessage().contains("sole active shop owner"));

        // Creating a second owner allows deactivating the first owner
        User owner2 = userService.createOwnerUser(owner.id(), "owner2", "Owner2Pass", "Co Owner");
        assertDoesNotThrow(() -> userService.setUserActive(owner2.id(), owner.id(), false));
    }

    @Test
    @DisplayName("Password changes: self change requires current password, owner can reset administrative password")
    void testPasswordChanges() {
        User owner = authService.setupInitialOwner("owner", "OwnerPass123", "Shop Owner");
        User staff = userService.createStaffUser(owner.id(), "staff1", "StaffPass123", "Staff Member");

        // Staff changes own password
        authService.changePassword(staff.id(), staff.id(), "StaffPass123", "NewStaffPass123");
        assertDoesNotThrow(() -> authService.authenticate("staff1", "NewStaffPass123"));

        // Staff providing wrong current password fails
        assertThrows(AuthenticationException.class, () ->
                authService.changePassword(staff.id(), staff.id(), "WrongCurrent", "AnotherPass123")
        );

        // Staff cannot change someone else's password
        assertThrows(UnauthorizedException.class, () ->
                authService.changePassword(staff.id(), owner.id(), null, "HackedPass123")
        );

        // Owner administrative reset of staff password without current password
        authService.changePassword(owner.id(), staff.id(), null, "OwnerResetPass123");
        assertDoesNotThrow(() -> authService.authenticate("staff1", "OwnerResetPass123"));
    }

    @Test
    @DisplayName("Audit logging: verify complete audit trail and access control")
    void testAuditLogging() {
        User owner = authService.setupInitialOwner("owner", "OwnerPass123", "Shop Owner");
        User staff = userService.createStaffUser(owner.id(), "staff1", "StaffPass123", "Staff Member");
        authService.authenticate("staff1", "StaffPass123");

        // Owner views audit logs
        List<AuditLogRecord> ownerLogs = auditLogService.getRecentLogs(owner.id(), 20);
        assertTrue(ownerLogs.size() >= 3, "Must have INITIAL_OWNER_SETUP, USER_CREATED, LOGIN_SUCCESS");

        // Staff cannot view audit logs
        assertThrows(UnauthorizedException.class, () ->
                auditLogService.getRecentLogs(staff.id(), 10)
        );
    }
}
