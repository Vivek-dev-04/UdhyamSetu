package com.shopbilling.db;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationInfo;
import org.flywaydb.core.api.output.MigrateResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Objects;

/**
 * Manages database schema migrations using Flyway (plain JDBC, no Spring).
 * (PRD Section 10: Plain JDBC with hand-written SQL, and Flyway for migrations).
 * Supports automatic pre-migration safety backups before applying schema upgrades (FR-49).
 */
public class FlywayMigrator {

    private static final Logger logger = LoggerFactory.getLogger(FlywayMigrator.class);

    public static final String DEFAULT_MIGRATION_LOCATION = "classpath:db/migration";

    private final ConnectionFactory connectionFactory;
    private final String migrationLocation;
    private final PreMigrationBackupHook preMigrationBackupHook;

    public FlywayMigrator(ConnectionFactory connectionFactory) {
        this(connectionFactory, DEFAULT_MIGRATION_LOCATION, null);
    }

    public FlywayMigrator(ConnectionFactory connectionFactory, PreMigrationBackupHook preMigrationBackupHook) {
        this(connectionFactory, DEFAULT_MIGRATION_LOCATION, preMigrationBackupHook);
    }

    public FlywayMigrator(ConnectionFactory connectionFactory, String migrationLocation) {
        this(connectionFactory, migrationLocation, null);
    }

    public FlywayMigrator(ConnectionFactory connectionFactory, String migrationLocation, PreMigrationBackupHook preMigrationBackupHook) {
        this.connectionFactory = Objects.requireNonNull(connectionFactory, "ConnectionFactory cannot be null");
        this.migrationLocation = Objects.requireNonNull(migrationLocation, "Migration location cannot be null");
        this.preMigrationBackupHook = preMigrationBackupHook;
    }

    /**
     * Initializes and executes all pending Flyway migrations.
     * If an existing database has pending migrations and a hook is registered,
     * triggers pre-migration safety backup (FR-49) before applying updates.
     */
    public MigrateResult migrate() {
        return migrate(this.preMigrationBackupHook);
    }

    public MigrateResult migrate(PreMigrationBackupHook explicitHook) {
        logger.info("Initializing Flyway database migrations from location: {}", migrationLocation);
        try {
            Flyway flyway = Flyway.configure()
                    .dataSource(connectionFactory.getDataSource())
                    .locations(migrationLocation)
                    .baselineOnMigrate(true)
                    .baselineVersion("0")
                    .load();

            MigrationInfo[] applied = flyway.info().applied();
            MigrationInfo[] pending = flyway.info().pending();

            PreMigrationBackupHook activeHook = explicitHook != null ? explicitHook : this.preMigrationBackupHook;
            if (activeHook != null && applied.length > 0 && pending.length > 0) {
                logger.info("FR-49: Detected {} pending migration(s) on existing schema (applied={}). Executing pre-migration safety backup...",
                        pending.length, applied.length);
                try {
                    activeHook.performPreMigrationBackup();
                    logger.info("Pre-migration safety backup completed successfully.");
                } catch (Exception e) {
                    String errorMsg = "Pre-migration safety backup failed! Aborting migration to protect database: " + e.getMessage();
                    logger.error(errorMsg, e);
                    throw new DatabaseException(errorMsg, e);
                }
            }

            MigrateResult result = flyway.migrate();
            logger.info("Flyway migration completed successfully. Applied {} migrations, target schema version: {}",
                    result.migrationsExecuted, result.targetSchemaVersion);

            for (MigrationInfo info : flyway.info().applied()) {
                logger.debug("Applied migration: {} - {} (type: {})",
                        info.getVersion(), info.getDescription(), info.getType());
            }

            return result;
        } catch (DatabaseException e) {
            throw e;
        } catch (Exception e) {
            logger.error("Database migration failed", e);
            throw new DatabaseException("Failed to execute database migrations: " + e.getMessage(), e);
        }
    }

    /**
     * Retrieves current migration metadata.
     */
    public MigrationInfo[] getMigrationInfo() {
        Flyway flyway = Flyway.configure()
                .dataSource(connectionFactory.getDataSource())
                .locations(migrationLocation)
                .load();
        return flyway.info().all();
    }
}
