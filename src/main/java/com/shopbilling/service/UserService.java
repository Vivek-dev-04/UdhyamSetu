package com.shopbilling.service;

import com.shopbilling.db.TransactionHelper;
import com.shopbilling.db.UserRepository;
import com.shopbilling.model.User;
import com.shopbilling.model.UserRole;
import org.mindrot.jbcrypt.BCrypt;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Service managing user CRUD operations and role-based permissions (PRD Section 5, 7.10, FR-43, FR-44).
 * Owner-only operations: Staff cannot manage user accounts.
 * Strictly contains NO JavaFX imports.
 */
public class UserService {

    private static final Logger logger = LoggerFactory.getLogger(UserService.class);
    private static final int BCRYPT_LOG_ROUNDS = 12;

    private final TransactionHelper transactionHelper;
    private final UserRepository userRepository;
    private final AuditLogService auditLogService;

    public UserService(TransactionHelper transactionHelper,
                       UserRepository userRepository,
                       AuditLogService auditLogService) {
        this.transactionHelper = Objects.requireNonNull(transactionHelper, "TransactionHelper cannot be null");
        this.userRepository = Objects.requireNonNull(userRepository, "UserRepository cannot be null");
        this.auditLogService = Objects.requireNonNull(auditLogService, "AuditLogService cannot be null");
    }

    /**
     * Creates a new staff member account. Only shop owners can create users (FR-43).
     */
    public User createStaffUser(long requestingUserId, String username, String rawPassword, String fullName) {
        return createUserWithRole(requestingUserId, username, rawPassword, fullName, UserRole.STAFF);
    }

    /**
     * Creates an additional owner account. Only existing shop owners can create users (FR-43).
     */
    public User createOwnerUser(long requestingUserId, String username, String rawPassword, String fullName) {
        return createUserWithRole(requestingUserId, username, rawPassword, fullName, UserRole.OWNER);
    }

    /**
     * Retrieves all user accounts. Owner-only operation (FR-43).
     */
    public List<User> getAllUsers(long requestingUserId) {
        return transactionHelper.executeRead(conn -> {
            verifyOwner(conn, requestingUserId);
            return userRepository.findAll(conn);
        });
    }

    /**
     * Finds a user by ID. Accessible by owner, or by self.
     */
    public Optional<User> getUserById(long requestingUserId, long targetUserId) {
        return transactionHelper.executeRead(conn -> {
            if (requestingUserId != targetUserId) {
                verifyOwner(conn, requestingUserId);
            }
            return userRepository.findById(conn, targetUserId);
        });
    }

    /**
     * Activates or deactivates a user account (FR-43, FR-44).
     * Prevents deactivating the only active shop owner.
     */
    public void setUserActive(long requestingUserId, long targetUserId, boolean active) {
        transactionHelper.execute(conn -> {
            verifyOwner(conn, requestingUserId);

            User target = userRepository.findById(conn, targetUserId)
                    .orElseThrow(() -> new EntityNotFoundException("Target user id=" + targetUserId + " not found"));

            if (target.isActive() == active) {
                return null;
            }

            // Prevent deactivating the sole owner
            if (!active && target.isOwner()) {
                int activeOwners = userRepository.countActiveOwners(conn);
                if (activeOwners <= 1) {
                    throw new ValidationException("Cannot deactivate the sole active shop owner");
                }
            }

            userRepository.updateProfile(conn, targetUserId, target.fullName(), target.role(), active);

            auditLogService.logEventInTransaction(
                    conn,
                    requestingUserId,
                    "USER_STATUS_CHANGE",
                    "USER",
                    String.valueOf(targetUserId),
                    String.valueOf(target.isActive()),
                    String.valueOf(active),
                    active ? "Account activated" : "Account deactivated"
            );

            logger.info("User id={} active status changed to {} by owner={}", targetUserId, active, requestingUserId);
            return null;
        });
    }

    /**
     * Updates user full name display. Owner-only.
     */
    public void updateFullName(long requestingUserId, long targetUserId, String newFullName) {
        if (newFullName == null || newFullName.trim().isEmpty()) {
            throw new ValidationException("Full name cannot be blank");
        }

        transactionHelper.execute(conn -> {
            verifyOwner(conn, requestingUserId);

            User target = userRepository.findById(conn, targetUserId)
                    .orElseThrow(() -> new EntityNotFoundException("Target user id=" + targetUserId + " not found"));

            userRepository.updateProfile(conn, targetUserId, newFullName.trim(), target.role(), target.isActive());

            auditLogService.logEventInTransaction(
                    conn,
                    requestingUserId,
                    "USER_PROFILE_UPDATE",
                    "USER",
                    String.valueOf(targetUserId),
                    target.fullName(),
                    newFullName.trim(),
                    "Full name updated"
            );

            return null;
        });
    }

    private User createUserWithRole(long requestingUserId, String username, String rawPassword, String fullName, UserRole role) {
        validateInput(username, rawPassword, fullName);

        return transactionHelper.execute(conn -> {
            verifyOwner(conn, requestingUserId);

            if (userRepository.findByUsername(conn, username.trim()).isPresent()) {
                throw new DuplicateEntityException("Username '" + username.trim() + "' is already taken");
            }

            String passwordHash = BCrypt.hashpw(rawPassword, BCrypt.gensalt(BCRYPT_LOG_ROUNDS));
            User newUser = new User(
                    null,
                    username.trim(),
                    passwordHash,
                    role,
                    fullName.trim(),
                    true,
                    null
            );

            User saved = userRepository.insert(conn, newUser);

            auditLogService.logEventInTransaction(
                    conn,
                    requestingUserId,
                    "USER_CREATED",
                    "USER",
                    String.valueOf(saved.id()),
                    null,
                    saved.username(),
                    "Created new " + role.name() + " account"
            );

            logger.info("Created user id={} username='{}' role={} by owner={}",
                    saved.id(), saved.username(), saved.role(), requestingUserId);
            return saved;
        });
    }

    private void verifyOwner(Connection conn, long userId) throws SQLException {
        User user = userRepository.findById(conn, userId)
                .orElseThrow(() -> new EntityNotFoundException("User id=" + userId + " not found"));
        if (!user.isOwner()) {
            throw new UnauthorizedException("Only the shop owner is authorized to manage users");
        }
    }

    private void validateInput(String username, String password, String fullName) {
        if (username == null || username.trim().length() < 3) {
            throw new ValidationException("Username must be at least 3 characters long");
        }
        if (!username.trim().matches("^[a-zA-Z0-9_.-]+$")) {
            throw new ValidationException("Username can only contain alphanumeric characters, dots, underscores, or hyphens");
        }
        if (password == null || password.trim().length() < 6) {
            throw new ValidationException("Password must be at least 6 characters long");
        }
        if (fullName == null || fullName.trim().isEmpty()) {
            throw new ValidationException("Full name cannot be blank");
        }
    }
}
