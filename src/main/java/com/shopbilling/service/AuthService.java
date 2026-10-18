package com.shopbilling.service;

import com.shopbilling.db.TransactionHelper;
import com.shopbilling.db.UserRepository;
import com.shopbilling.model.User;
import com.shopbilling.model.UserRole;
import org.mindrot.jbcrypt.BCrypt;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Objects;
import java.util.Optional;

/**
 * Service managing user authentication, BCrypt password hashing, and first-launch setup
 * (PRD Section 7.10, FR-41, FR-42, FR-44).
 * Strictly contains NO JavaFX imports.
 */
public class AuthService {

    private static final Logger logger = LoggerFactory.getLogger(AuthService.class);
    private static final int BCRYPT_LOG_ROUNDS = 12;

    private final TransactionHelper transactionHelper;
    private final UserRepository userRepository;
    private final AuditLogService auditLogService;

    public AuthService(TransactionHelper transactionHelper,
                       UserRepository userRepository,
                       AuditLogService auditLogService) {
        this.transactionHelper = Objects.requireNonNull(transactionHelper, "TransactionHelper cannot be null");
        this.userRepository = Objects.requireNonNull(userRepository, "UserRepository cannot be null");
        this.auditLogService = Objects.requireNonNull(auditLogService, "AuditLogService cannot be null");
    }

    /**
     * Checks if the system is on first launch (no users exist in the database yet).
     */
    public boolean isFirstLaunch() {
        return transactionHelper.executeRead(conn -> userRepository.countUsers(conn) == 0);
    }

    /**
     * Initializes the shop owner account on first launch (FR-41).
     *
     * @param username    Owner username (alphanumeric, min 3 chars)
     * @param rawPassword Password to be BCrypt hashed (min 6 chars)
     * @param fullName    Owner's display name
     * @return Newly created and persisted owner User
     */
    public User setupInitialOwner(String username, String rawPassword, String fullName) {
        validateCredentials(username, rawPassword, fullName);

        return transactionHelper.execute(conn -> {
            if (userRepository.countUsers(conn) > 0) {
                throw new ValidationException("Owner account has already been initialized");
            }

            String passwordHash = BCrypt.hashpw(rawPassword, BCrypt.gensalt(BCRYPT_LOG_ROUNDS));
            User newOwner = new User(
                    null,
                    username.trim(),
                    passwordHash,
                    UserRole.OWNER,
                    fullName.trim(),
                    true,
                    null
            );

            User saved = userRepository.insert(conn, newOwner);

            auditLogService.logEventInTransaction(
                    conn,
                    saved.id(),
                    "INITIAL_OWNER_SETUP",
                    "USER",
                    String.valueOf(saved.id()),
                    null,
                    saved.username(),
                    "Initial shop owner created on first launch"
            );

            logger.info("First launch owner initialized: id={} username='{}'", saved.id(), saved.username());
            return saved;
        });
    }

    /**
     * Authenticates a user with username and raw password using BCrypt (FR-41, FR-42).
     *
     * @param username    Account username
     * @param rawPassword Plaintext password attempt
     * @return Authenticated User record
     * @throws AuthenticationException if username/password is incorrect or user is inactive
     */
    public User authenticate(String username, String rawPassword) {
        if (username == null || username.trim().isEmpty() || rawPassword == null || rawPassword.isEmpty()) {
            throw new AuthenticationException("Username and password cannot be blank");
        }

        return transactionHelper.execute(conn -> {
            Optional<User> optUser = userRepository.findByUsername(conn, username.trim());
            if (optUser.isEmpty()) {
                logger.warn("Failed login attempt for unknown username: '{}'", username);
                throw new AuthenticationException("Invalid username or password");
            }

            User user = optUser.get();
            if (!user.isActive()) {
                logger.warn("Login attempt for deactivated user id={} username='{}'", user.id(), user.username());
                throw new AuthenticationException("User account is deactivated. Please contact shop owner.");
            }

            if (!BCrypt.checkpw(rawPassword, user.passwordHash())) {
                logger.warn("Failed password verification for user id={} username='{}'", user.id(), user.username());
                throw new AuthenticationException("Invalid username or password");
            }

            auditLogService.logEventInTransaction(
                    conn,
                    user.id(),
                    "LOGIN_SUCCESS",
                    "USER",
                    String.valueOf(user.id()),
                    null,
                    null,
                    "Successful authentication"
            );

            logger.info("User authenticated successfully: id={} username='{}' role={}", user.id(), user.username(), user.role());
            return user;
        });
    }

    /**
     * Changes or resets a user password (FR-42, FR-44).
     * Staff can only change their own password and must provide their current password.
     * Owner can reset any user's password without needing the current password.
     */
    public void changePassword(long requestingUserId, long targetUserId, String currentPassword, String newPassword) {
        if (newPassword == null || newPassword.trim().length() < 6) {
            throw new ValidationException("New password must be at least 6 characters long");
        }

        transactionHelper.execute(conn -> {
            User requestingUser = userRepository.findById(conn, requestingUserId)
                    .orElseThrow(() -> new EntityNotFoundException("Requesting user id=" + requestingUserId + " not found"));

            User targetUser = userRepository.findById(conn, targetUserId)
                    .orElseThrow(() -> new EntityNotFoundException("Target user id=" + targetUserId + " not found"));

            boolean isSelf = requestingUserId == targetUserId;

            if (!requestingUser.isOwner() && !isSelf) {
                throw new UnauthorizedException("Counter staff cannot change another user's password");
            }

            if (isSelf) {
                if (currentPassword == null || !BCrypt.checkpw(currentPassword, targetUser.passwordHash())) {
                    throw new AuthenticationException("Current password is incorrect");
                }
            }

            String newHash = BCrypt.hashpw(newPassword, BCrypt.gensalt(BCRYPT_LOG_ROUNDS));
            userRepository.updatePassword(conn, targetUserId, newHash);

            auditLogService.logEventInTransaction(
                    conn,
                    requestingUserId,
                    "PASSWORD_CHANGED",
                    "USER",
                    String.valueOf(targetUserId),
                    null,
                    null,
                    isSelf ? "User changed own password" : "Owner reset password"
            );

            logger.info("Password changed for user id={} by requestingUserId={}", targetUserId, requestingUserId);
            return null;
        });
    }

    private void validateCredentials(String username, String password, String fullName) {
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
