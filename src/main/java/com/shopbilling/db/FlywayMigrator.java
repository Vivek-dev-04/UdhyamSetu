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
 */
public class FlywayMigrator {

    private static final Logger logger = LoggerFactory.getLogger(FlywayMigrator.class);

    public static final String DEFAULT_MIGRATION_LOCATION = "classpath:db/migration";

    private final ConnectionFactory connectionFactory;
    private final String migrationLocation;

    public FlywayMigrator(ConnectionFactory connectionFactory) {
        this(connectionFactory, DEFAULT_MIGRATION_LOCATION);
    }

    public FlywayMigrator(ConnectionFactory connectionFactory, String migrationLocation) {
        this.connectionFactory = Objects.requireNonNull(connectionFactory, "ConnectionFactory cannot be null");
        this.migrationLocation = Objects.requireNonNull(migrationLocation, "Migration location cannot be null");
    }

    /**
     * Initializes and executes all pending Flyway migrations.
     */
    public MigrateResult migrate() {
        logger.info("Initializing Flyway database migrations from location: {}", migrationLocation);
        try {
            Flyway flyway = Flyway.configure()
                    .dataSource(connectionFactory.getDataSource())
                    .locations(migrationLocation)
                    .baselineOnMigrate(true)
                    .baselineVersion("0")
                    .load();

            MigrateResult result = flyway.migrate();
            logger.info("Flyway migration completed successfully. Applied {} migrations, target schema version: {}",
                    result.migrationsExecuted, result.targetSchemaVersion);

            for (MigrationInfo info : flyway.info().applied()) {
                logger.debug("Applied migration: {} - {} (type: {})",
                        info.getVersion(), info.getDescription(), info.getType());
            }

            return result;
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
