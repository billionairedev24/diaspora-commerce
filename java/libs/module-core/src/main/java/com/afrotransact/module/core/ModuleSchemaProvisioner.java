package com.afrotransact.module.core;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.springframework.core.env.Environment;

/**
 * Creates a module's schema and its role in schema mode, idempotently. It runs only when an administrative connection is
 * configured ({@code at.modules.schema-admin.url|username|password}): local development and the first provisioning of a
 * database. In production the same statements are applied once by the database owner ({@code infra/local/postgres/} holds
 * the script) and the module's own role, which cannot create schemas, only runs Flyway inside its schema.
 */
final class ModuleSchemaProvisioner {

    private static final Log LOG = LogFactory.getLog(ModuleSchemaProvisioner.class);

    private ModuleSchemaProvisioner() {
    }

    static void provisionIfConfigured(ModuleDefinition module, ModuleProperties properties, Environment environment) {
        String adminUrl = environment.getProperty("at.modules.schema-admin.url");
        if (!properties.schemaMode() || adminUrl == null || adminUrl.isBlank()) {
            return;
        }
        ModuleProperties.Database db = properties.datasource();
        List<String> statements = statements(properties.schemaName(module.name()), db.username(), db.password());
        try (Connection connection = DriverManager.getConnection(adminUrl,
                environment.getProperty("at.modules.schema-admin.username"),
                environment.getProperty("at.modules.schema-admin.password")); Statement statement = connection.createStatement()) {
            for (String sql : statements) {
                statement.execute(sql);
            }
            LOG.info("Module '" + module.name() + "': schema '" + properties.schemaName(module.name())
                    + "' and role '" + db.username() + "' are provisioned");
        } catch (SQLException ex) {
            throw new ModuleStartupException(module.name(), "could not provision schema '"
                    + properties.schemaName(module.name()) + "' with the administrative connection", ex);
        }
    }

    /** The statements, in order. Names are validated identifiers; the password is a quoted literal. */
    static List<String> statements(String schema, String role, String password) {
        requireIdentifier(schema);
        requireIdentifier(role);
        if (password == null || password.isBlank()) {
            throw new IllegalArgumentException("role '" + role + "' needs a password");
        }
        String literal = "'" + password.replace("'", "''") + "'";
        return List.of(
                "DO $do$ BEGIN IF NOT EXISTS (SELECT FROM pg_roles WHERE rolname = '" + role + "') THEN CREATE ROLE \""
                        + role + "\" LOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE; END IF; END $do$",
                "ALTER ROLE \"" + role + "\" WITH LOGIN PASSWORD " + literal,
                "CREATE SCHEMA IF NOT EXISTS \"" + schema + "\" AUTHORIZATION \"" + role + "\"",
                "ALTER ROLE \"" + role + "\" SET search_path = \"" + schema + "\", public");
    }

    private static void requireIdentifier(String value) {
        if (value == null || !value.matches("[a-z_][a-z0-9_]{0,62}")) {
            throw new IllegalArgumentException("'" + value + "' is not a lower-case identifier");
        }
    }
}
