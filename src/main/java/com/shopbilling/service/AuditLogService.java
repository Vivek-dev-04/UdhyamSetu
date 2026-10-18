package com.shopbilling.service;

import com.shopbilling.db.AuditLogRepository;
import com.shopbilling.db.TransactionHelper;
import com.shopbilling.db.UserRepository;
import com.shopbilling.model.AuditLogRecord;
import com.shopbilling.model.User;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.Connection;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.List;
import java.util.Objects;

/**
 * Service managing append-only audit logging and query capabilities (PRD Section 7.10, FR-44).
 * Strictly contains NO JavaFX imports.
 */
public class AuditLogService {

    private static final Logger logger = LoggerFactory.getLogger(AuditLogService.class);

    private final TransactionHelper transactionHelper;
    private final AuditLogRepository auditLogRepository;
    private final UserRepository userRepository;

    public AuditLogService(TransactionHelper transactionHelper,
                           AuditLogRepository auditLogRepository,
                           UserRepository userRepository) {
        this.transactionHelper = Objects.requireNonNull(transactionHelper, "TransactionHelper cannot be null");
        this.auditLogRepository = Objects.requireNonNull(auditLogRepository, "AuditLogRepository cannot be null");
        this.userRepository = Objects.requireNonNull(userRepository, "UserRepository cannot be null");
    }

    /**
     * Appends an audit log record within an existing active database transaction.
     */
    public AuditLogRecord logEventInTransaction(Connection conn,
                                                long userId,
                                                String action,
                                                String entityName,
                                                String entityId,
                                                String oldValue,
                                                String newValue,
                                                String reason) throws SQLException {
        AuditLogRecord record = auditLogRepository.insert(
                conn, userId, action, entityName, entityId, oldValue, newValue, reason
        );
        logger.info("Audit log: user={} action='{}' entity='{}:{}' reason='{}'",
                userId, action, entityName, entityId, reason);
        return record;
    }

    /**
     * Appends an audit log record in its own transaction.
     */
    public AuditLogRecord logEvent(long userId,
                                   String action,
                                   String entityName,
                                   String entityId,
                                   String oldValue,
                                   String newValue,
                                   String reason) {
        return transactionHelper.execute(conn ->
                logEventInTransaction(conn, userId, action, entityName, entityId, oldValue, newValue, reason)
        );
    }

    /**
     * Queries audit logs with filtering. Owner-only operation (FR-43).
     */
    public List<AuditLogRecord> queryAuditLogs(long requestingUserId,
                                               LocalDate from,
                                               LocalDate to,
                                               String action,
                                               Long filterUserId,
                                               int limit,
                                               int offset) {
        return transactionHelper.executeRead(conn -> {
            verifyOwner(conn, requestingUserId);
            return auditLogRepository.queryLogs(conn, from, to, action, filterUserId, limit, offset);
        });
    }

    /**
     * Retrieves the most recent audit log entries. Owner-only operation (FR-43).
     */
    public List<AuditLogRecord> getRecentLogs(long requestingUserId, int limit) {
        return queryAuditLogs(requestingUserId, null, null, null, null, limit, 0);
    }

    private void verifyOwner(Connection conn, long userId) throws SQLException {
        User user = userRepository.findById(conn, userId)
                .orElseThrow(() -> new EntityNotFoundException("User id=" + userId + " not found"));
        if (!user.isOwner()) {
            throw new UnauthorizedException("Only the shop owner is authorized to view audit logs");
        }
    }
}
