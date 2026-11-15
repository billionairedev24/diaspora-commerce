package com.afrotransact.core;

import com.afrotransact.module.core.EnableModules;
import com.afrotransact.module.core.ModuleSpec;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.flyway.autoconfigure.FlywayAutoConfiguration;
import org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration;
import org.springframework.boot.jdbc.autoconfigure.DataSourceTransactionManagerAutoConfiguration;
import org.springframework.boot.jdbc.autoconfigure.JdbcClientAutoConfiguration;
import org.springframework.boot.jdbc.autoconfigure.JdbcTemplateAutoConfiguration;

/**
 * afrotransact-core: the Java services of Release 1 as modules of one Spring Boot application (ADR 0011).
 *
 * <p>Every module has its own database, so Spring Boot's single-DataSource auto-configuration (DataSource, JDBC templates,
 * transaction manager, Flyway) is switched off and {@code module-core} gives each module its own set. Spike: catalog and
 * tax-returns; the other modules join in the batches of docs/engineering/release-1-architecture.md.
 */
@SpringBootApplication(exclude = {
    DataSourceAutoConfiguration.class,
    DataSourceTransactionManagerAutoConfiguration.class,
    JdbcTemplateAutoConfiguration.class,
    JdbcClientAutoConfiguration.class,
    FlywayAutoConfiguration.class})
@EnableModules({
    @ModuleSpec(name = "catalog", service = "catalog", basePackage = "com.afrotransact.catalog",
            application = "com.afrotransact.catalog.CatalogApplication", meterPrefixes = {"catalog.", "catalog-"}),
    @ModuleSpec(name = "tax-returns", service = "tax-returns", basePackage = "com.afrotransact.taxreturns",
            application = "com.afrotransact.taxreturns.TaxReturnsApplication",
            meterPrefixes = {"tax.", "taxreturns-"})})
public class CoreApplication {

    public static void main(String[] args) {
        SpringApplication.run(CoreApplication.class, args);
    }
}
