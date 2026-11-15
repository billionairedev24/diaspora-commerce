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
            meterPrefixes = {"tax.", "taxreturns-"}),
    @ModuleSpec(name = "offers", service = "offers", basePackage = "com.afrotransact.offers",
            application = "com.afrotransact.offers.OffersApplication", meterPrefixes = {"offers.", "offers-"}),
    @ModuleSpec(name = "inventory", service = "inventory", basePackage = "com.afrotransact.inventory",
            application = "com.afrotransact.inventory.InventoryApplication", meterPrefixes = {"inventory.", "inventory-"}),
    @ModuleSpec(name = "content", service = "content", basePackage = "com.afrotransact.content",
            application = "com.afrotransact.content.ContentApplication", meterPrefixes = {"content.", "content-"}),
    @ModuleSpec(name = "seller", service = "seller", basePackage = "com.afrotransact.seller",
            application = "com.afrotransact.seller.SellerApplication", meterPrefixes = {"seller.", "seller-"}),
    @ModuleSpec(name = "orders", service = "orders", basePackage = "com.afrotransact.orders",
            application = "com.afrotransact.orders.OrdersApplication", meterPrefixes = {"orders.", "orders-"}),
    @ModuleSpec(name = "payments", service = "payments", basePackage = "com.afrotransact.payments",
            application = "com.afrotransact.payments.PaymentsApplication", meterPrefixes = {"payments.", "payments-"}),
    @ModuleSpec(name = "bookings", service = "bookings", basePackage = "com.afrotransact.bookings",
            application = "com.afrotransact.bookings.BookingsApplication", meterPrefixes = {"bookings.", "bookings-"}),
    @ModuleSpec(name = "fulfilment", service = "fulfilment", basePackage = "com.afrotransact.fulfilment",
            application = "com.afrotransact.fulfilment.FulfilmentApplication", meterPrefixes = {"fulfilment.", "fulfilment-"}),
    @ModuleSpec(name = "trust-ops", service = "trust-ops", basePackage = "com.afrotransact.trustops",
            application = "com.afrotransact.trustops.TrustOpsApplication", meterPrefixes = {"trustops.", "trustops-"}),
    @ModuleSpec(name = "identity", service = "identity", basePackage = "com.afrotransact.identity",
            application = "com.afrotransact.identity.IdentityApplication", meterPrefixes = {"identity.", "identity-"}),
    @ModuleSpec(name = "retail", service = "retail", basePackage = "com.afrotransact.retail",
            application = "com.afrotransact.retail.RetailApplication", meterPrefixes = {"retail.", "retail-"}),
    @ModuleSpec(name = "delivery-runs", service = "delivery-runs", basePackage = "com.afrotransact.deliveryruns",
            application = "com.afrotransact.deliveryruns.DeliveryRunsApplication", meterPrefixes = {"deliveryruns.", "delivery-runs-"}),
    @ModuleSpec(name = "ai-gateway", service = "ai-gateway", basePackage = "com.afrotransact.ai",
            application = "com.afrotransact.ai.AiGatewayApplication", meterPrefixes = {"ai.", "ai-"}),
    @ModuleSpec(name = "mcp-server", service = "mcp-server", basePackage = "com.afrotransact.mcp",
            application = "com.afrotransact.mcp.McpServerApplication", meterPrefixes = {"mcp.", "mcp-"})})
public class CoreApplication {

    public static void main(String[] args) {
        SpringApplication.run(CoreApplication.class, args);
    }
}
