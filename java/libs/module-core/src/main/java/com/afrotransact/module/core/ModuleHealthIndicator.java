package com.afrotransact.module.core;

import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;

/**
 * "Did this module start?": UP once its migration has run. It does not ping the database, so a database that blips does not
 * take the whole application out of rotation through the readiness probe; the live database check is
 * {@link ModuleDatabaseHealthIndicator}, shown in the module's own group.
 */
final class ModuleHealthIndicator implements HealthIndicator {

    private final ModuleStatus status;

    ModuleHealthIndicator(ModuleStatus status) {
        this.status = status;
    }

    @Override
    public Health health() {
        return Health.up().withDetail("module", status.module()).withDetail("database", status.database())
                .withDetail("schemaVersion", status.schemaVersion()).withDetail("startedAt", status.startedAt().toString())
                .build();
    }
}
