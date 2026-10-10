package com.afrotransact.catalog.it;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import com.afrotransact.catalog.application.CategoryService;
import com.afrotransact.catalog.application.CreateProductCommand;
import com.afrotransact.catalog.application.IdentifierService;
import com.afrotransact.catalog.application.ListingModerationService;
import com.afrotransact.catalog.application.ListingModerationService.ReportResult;
import com.afrotransact.catalog.application.ProductService;
import com.afrotransact.catalog.application.PublicCatalogService;
import com.afrotransact.catalog.domain.CatalogRuleViolation;
import com.afrotransact.catalog.domain.CategoryNode;
import com.afrotransact.catalog.domain.FlagSource;
import com.afrotransact.catalog.domain.ListingReasonCode;
import com.afrotransact.catalog.domain.Product;
import com.afrotransact.catalog.domain.ProductFlag;
import com.afrotransact.catalog.domain.ProductImage;
import com.afrotransact.catalog.domain.ProductStatus;
import com.afrotransact.catalog.domain.VariantDraft;
import com.afrotransact.platform.security.Actor;
import com.afrotransact.platform.security.ActorResolver;
import com.afrotransact.platform.security.PrincipalType;
import com.afrotransact.platform.test.PostgresTestContainer;
import com.afrotransact.platform.web.error.ForbiddenActionException;
import com.afrotransact.platform.web.error.NotFoundException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** S228 reports, flags and takedowns of live listings; S224 GTIN lookups by service tokens. */
@SpringBootTest(properties = {"at.events.relay-enabled=false", "at.consumers.enabled=false",
    "spring.kafka.bootstrap-servers=localhost:1"})
@ActiveProfiles("local")
@Testcontainers
@Import(ListingModerationIT.Actors.class)
class ListingModerationIT {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer POSTGRES = PostgresTestContainer.create();

    private static final ProductImage IMAGE = new ProductImage("https://utfs.io/f/ankara.jpg", 1600, 1600, 300_000,
            "image/jpeg");
    private static final Actor ADMIN = new Actor("local-dev", PrincipalType.STAFF, Optional.empty(),
            Set.of("platform.super_admin"), Set.of("*"));
    private static final AtomicReference<Actor> CURRENT = new AtomicReference<>(ADMIN);
    private static final JsonMapper JSON = JsonMapper.builder().build();

    /** Switchable actor for direct use-case calls. */
    @TestConfiguration(proxyBeanMethods = false)
    static class Actors {

        @Bean
        ActorResolver switchableActorResolver() {
            return CURRENT::get;
        }
    }

    private final ProductService products;
    private final CategoryService categories;
    private final PublicCatalogService publicCatalog;
    private final ListingModerationService listings;
    private final IdentifierService identifiers;
    private final JdbcClient jdbc;

    @Autowired
    ListingModerationIT(ProductService products, CategoryService categories, PublicCatalogService publicCatalog,
                        ListingModerationService listings, IdentifierService identifiers, JdbcClient jdbc) {
        this.products = products;
        this.categories = categories;
        this.publicCatalog = publicCatalog;
        this.listings = listings;
        this.identifiers = identifiers;
        this.jdbc = jdbc;
    }

    @AfterEach
    void resetActor() {
        CURRENT.set(ADMIN);
    }

    private static Actor customer(String id) {
        return new Actor(id, PrincipalType.CUSTOMER, Optional.empty(), Set.of("account.customer"), Set.of());
    }

    private static Actor service(String clientId, String... permissions) {
        return new Actor(clientId, PrincipalType.SERVICE, Optional.empty(), Set.of(), Set.of(permissions));
    }

    @Test
    void shoppersReportLiveListingsOncePerListing() {
        Product live = liveProduct("Kente Stole Handwoven Gold");
        CURRENT.set(customer("cust-1"));

        ReportResult first = listings.report(live.atin().value(), ListingReasonCode.COUNTERFEIT, "Fake kente print");
        assertThat(first.created()).isTrue();
        assertThat(first.flag().source()).isEqualTo(FlagSource.CUSTOMER_REPORT);
        JsonNode event = payload("catalog.product.flagged", live.id());
        assertThat(event.path("source").asString()).isEqualTo("CUSTOMER_REPORT");
        assertThat(event.path("reasonCode").asString()).isEqualTo("COUNTERFEIT");
        assertThat(event.path("reporterId").asString()).isEqualTo("cust-1");
        assertThat(event.path("ownerOrgId").asString()).isEqualTo(live.owner().orgId());
        assertThat(event.path("status").asString()).isEqualTo("LIVE");
        assertThat(event.path("flagId").asString()).isEqualTo(first.flag().id().toString());

        ReportResult again = listings.report(live.atin().value().toLowerCase(Locale.ROOT), ListingReasonCode.OTHER, "again");
        assertThat(again.created()).isFalse();
        assertThat(again.flag().id()).isEqualTo(first.flag().id());
        assertThat(count("catalog.product.flagged", live.id())).isEqualTo(1);

        CURRENT.set(customer("cust-2"));
        assertThatThrownBy(() -> listings.report(live.atin().value(), ListingReasonCode.OTHER, " "))
                .isInstanceOf(CatalogRuleViolation.class);
        assertThatThrownBy(() -> listings.report("ATZZZZZZZZ", ListingReasonCode.COUNTERFEIT, null))
                .isInstanceOf(NotFoundException.class);
        assertThatThrownBy(() -> listings.report("not-an-atin", ListingReasonCode.COUNTERFEIT, null))
                .isInstanceOf(NotFoundException.class);
    }

    @Test
    void servicesFlagAutomaticallyAndStaffTakeListingsDownAndRestoreThem() {
        Product live = liveProduct("Shea Butter Raw Unrefined 16 oz");
        String atin = live.atin().value();

        CURRENT.set(service("ai-gateway", "catalog:moderate"));
        ProductFlag auto = listings.flag(live.id(), ListingReasonCode.UNAPPROVED_HEALTH_CLAIM, "Cures eczema",
                "HIGH");
        assertThat(auto.source()).isEqualTo(FlagSource.AUTOMATED);
        assertThat(payload("catalog.product.flagged", live.id()).path("riskLevel").asString()).isEqualTo("HIGH");

        CURRENT.set(customer("cust-3"));
        assertThatThrownBy(() -> listings.takeDown(live.id(), ListingReasonCode.COUNTERFEIT, "x"))
                .isInstanceOf(ForbiddenActionException.class);

        CURRENT.set(service("trust-ops-service", "catalog:moderate"));
        assertThatThrownBy(() -> listings.takeDown(live.id(), ListingReasonCode.OTHER, null))
                .isInstanceOf(CatalogRuleViolation.class);
        Product down = listings.takeDown(live.id(), ListingReasonCode.UNAPPROVED_HEALTH_CLAIM, "Medical claims");
        assertThat(down.status()).isEqualTo(ProductStatus.TAKEN_DOWN);
        assertThat(down.moderationNote()).isEqualTo("[UNAPPROVED_HEALTH_CLAIM] Taken down: Medical claims");
        assertThatThrownBy(() -> publicCatalog.liveProduct(atin)).isInstanceOf(NotFoundException.class);
        JsonNode takenDown = payload("catalog.product.taken_down", live.id());
        assertThat(takenDown.path("status").asString()).isEqualTo("TAKEN_DOWN");
        assertThat(takenDown.path("reasonCode").asString()).isEqualTo("UNAPPROVED_HEALTH_CLAIM");

        // Retried takedowns are no-ops; the owner cannot resume it; shoppers cannot report it.
        assertThat(listings.takeDown(live.id(), ListingReasonCode.COUNTERFEIT, "again").version())
                .isEqualTo(down.version());
        assertThat(count("catalog.product.taken_down", live.id())).isEqualTo(1);
        CURRENT.set(ADMIN);
        assertThatThrownBy(() -> products.resume(live.id())).isInstanceOf(IllegalStateException.class);
        CURRENT.set(customer("cust-4"));
        assertThatThrownBy(() -> listings.report(atin, ListingReasonCode.COUNTERFEIT, null))
                .isInstanceOf(NotFoundException.class);

        CURRENT.set(service("trust-ops-service", "catalog:moderate"));
        Product restored = listings.restore(live.id(), "Claims removed");
        assertThat(restored.status()).isEqualTo(ProductStatus.LIVE);
        assertThat(publicCatalog.liveProduct(atin).id()).isEqualTo(live.id());
        assertThat(payload("catalog.product.reinstated", live.id()).path("status").asString()).isEqualTo("LIVE");
        assertThat(listings.restore(live.id(), null).version()).isEqualTo(restored.version());
        assertThat(jdbc.sql("select restored_by from product_takedown where product_id = :p")
                .param("p", live.id()).query(String.class).single()).isEqualTo("trust-ops-service");
    }

    @Test
    void pausedListingsReturnToPausedAndUnreviewedListingsCannotBeTakenDown() {
        Product live = liveProduct("Ankara Tote Bag Large Print");
        products.pause(live.id());
        Product down = listings.takeDown(live.id(), ListingReasonCode.IMAGE_POLICY, "Watermarked photos");
        assertThat(down.status()).isEqualTo(ProductStatus.TAKEN_DOWN);
        assertThat(listings.restore(live.id(), null).status()).isEqualTo(ProductStatus.PAUSED);

        Product inReview = create("Raffia Sun Hat Wide Brim");
        assertThatThrownBy(() -> listings.takeDown(inReview.id(), ListingReasonCode.COUNTERFEIT, "x"))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> listings.flag(inReview.id(), ListingReasonCode.COUNTERFEIT, "x", null))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void gtinLookupIsReadableByServiceTokensWithCatalogRead() {
        CURRENT.set(service("offers-service", "catalog:read"));
        assertThat(identifiers.check("4006381333931", false).valid()).isTrue();
        CURRENT.set(new Actor("seller-member", PrincipalType.SELLER_MEMBER, Optional.of("org_x"),
                Set.of("seller.fulfil"), Set.of("catalog:read")));
        assertThat(identifiers.check("4006381333931", false).valid()).isTrue();
        CURRENT.set(service("offers-service", "offers:write"));
        assertThatThrownBy(() -> identifiers.check("4006381333931", false))
                .isInstanceOf(ForbiddenActionException.class);
    }

    private Product liveProduct(String title) {
        return products.approve(create(title).id());
    }

    private Product create(String title) {
        CategoryNode dresses = categories.tree().stream()
                .filter(n -> n.path().equals("Fashion › Women › Dresses")).findFirst().orElseThrow();
        String sku = "S" + UUID.randomUUID().toString().substring(0, 8).toUpperCase(Locale.ROOT);
        return products.create(new CreateProductCommand(dresses.id(), null, title,
                Map.of("brand", "Lagos Textile Hub", "material", "Cotton", "fit", "Regular", "length", "Midi",
                        "sleeve", "Short", "occasion", "Wedding / owambe", "care", "Cold wash", "origin", "Nigeria"),
                List.of(IMAGE), "Size × Color",
                List.of(new VariantDraft(Map.of("Size", "M", "Color", "Gold"), sku, null, true, 4999, 3)), "USD"));
    }

    private JsonNode payload(String type, UUID productId) {
        return JSON.readTree(jdbc.sql("select payload::text from platform_outbox where type = :t and aggregate_id = :id "
                        + "order by occurred_at desc limit 1")
                .param("t", type).param("id", productId.toString()).query(String.class).single());
    }

    private long count(String type, UUID productId) {
        return jdbc.sql("select count(*) from platform_outbox where type = :t and aggregate_id = :id")
                .param("t", type).param("id", productId.toString()).query(Long.class).single();
    }
}
