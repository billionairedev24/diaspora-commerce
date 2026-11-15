package com.afrotransact.module.core;

import java.util.ArrayList;
import java.util.List;
import javax.sql.DataSource;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.FlywayException;
import org.flywaydb.core.api.MigrationInfo;
import org.flywaydb.core.api.output.MigrateResult;

/** Runs one module's Flyway history against its own database; a failure aborts the start and names the module. */
final class ModuleMigration {

    private static final Log LOG = LogFactory.getLog(ModuleMigration.class);

    private ModuleMigration() {
    }

    static ModuleStatus run(ModuleDefinition module, ModuleProperties properties, DataSource dataSource,
                            ClassLoader classLoader) {
        String database = ModuleDataSources.databaseName(properties.datasource().url());
        if (!properties.flyway().enabled()) {
            LOG.info("Module '" + module.name() + "': migrations are switched off (database '" + database + "')");
            return new ModuleStatus(module.name(), database, "unknown", 0);
        }
        List<String> locations = properties.flyway().locations().isEmpty()
                ? List.of("classpath:db/platform", "classpath:modules/" + module.name() + "/db/migration")
                : properties.flyway().locations();
        try {
            Flyway flyway = Flyway.configure(classLoader).dataSource(dataSource)
                    .locations(new ArrayList<>(locations).toArray(String[]::new)).load();
            MigrateResult result = flyway.migrate();
            MigrationInfo current = flyway.info().current();
            String version = current == null || current.getVersion() == null ? "none" : current.getVersion().toString();
            LOG.info("Module '" + module.name() + "': database '" + database + "' at schema version " + version + " ("
                    + result.migrationsExecuted + " migration(s) applied now)");
            return new ModuleStatus(module.name(), database, version, result.migrationsExecuted);
        } catch (FlywayException ex) {
            throw new ModuleStartupException(module.name(), "failed to migrate database '" + database + "' at "
                    + locations, ex);
        }
    }
}
