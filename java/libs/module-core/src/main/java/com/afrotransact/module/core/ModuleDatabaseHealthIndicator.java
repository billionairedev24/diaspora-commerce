package com.afrotransact.module.core;

import java.sql.Connection;
import java.sql.SQLException;
import javax.sql.DataSource;

import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;

/** The live check of one module's database, with its own pool, in {@code /actuator/health/<module>}. */
final class ModuleDatabaseHealthIndicator implements HealthIndicator {

    private static final int TIMEOUT_SECONDS = 2;

    private final DataSource dataSource;

    ModuleDatabaseHealthIndicator(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    @Override
    public Health health() {
        try (Connection connection = dataSource.getConnection()) {
            return connection.isValid(TIMEOUT_SECONDS) ? Health.up().build()
                    : Health.down().withDetail("reason", "connection not valid").build();
        } catch (SQLException ex) {
            return Health.down().withDetail("reason", ex.getMessage()).build();
        }
    }
}
