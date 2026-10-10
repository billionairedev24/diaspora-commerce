package com.afrotransact.catalog.it;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.afrotransact.catalog.application.CategoryService;
import com.afrotransact.catalog.application.CreateProductCommand;
import com.afrotransact.catalog.application.ListingEnforcement;
import com.afrotransact.catalog.application.ListingVisibilityService;
import com.afrotransact.catalog.application.ProductService;
import com.afrotransact.catalog.application.PublicCatalogService;
import com.afrotransact.catalog.domain.CategoryLevel;
import com.afrotransact.catalog.domain.CategoryNode;
import com.afrotransact.catalog.domain.CategoryRequest;
import com.afrotransact.catalog.domain.ListingOwnerKind;
import com.afrotransact.catalog.domain.Product;
import com.afrotransact.catalog.domain.ProductImage;
import com.afrotransact.catalog.domain.ProductStatus;
import com.afrotransact.catalog.domain.VariantDraft;
import com.afrotransact.platform.test.PostgresTestContainer;
import com.afrotransact.platform.web.error.ConflictException;
import com.afrotransact.platform.web.error.NotFoundException;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/** End-to-end use cases against real PostgreSQL: migrations, seed taxonomy, create, moderate, category requests. */
@SpringBootTest(properties = {"at.events.relay-enabled=false", "at.consumers.enabled=false",
    "spring.kafka.bootstrap-servers=localhost:1"})
@ActiveProfiles("local")
@Testcontainers
class CatalogFlowIT {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer POSTGRES = PostgresTestContainer.create();

    private static final ProductImage IMAGE = new ProductImage("https://utfs.io/f/ankara.jpg", 1600, 1600, 300_000,
            "image/jpeg");

    private final ProductService products;
    private final CategoryService categories;
    private final PublicCatalogService publicCatalog;
    private final ListingVisibilityService visibility;
    private final JdbcClient jdbc;

    @Autowired
    CatalogFlowIT(ProductService products, CategoryService categories, PublicCatalogService publicCatalog,
                  ListingVisibilityService visibility, JdbcClient jdbc) {
        this.products = products;
        this.categories = categories;
        this.publicCatalog = publicCatalog;
        this.visibility = visibility;
        this.jdbc = jdbc;
    }

    @Test
    void createsReviewsAndPublishesOutboxEvents() {
        CategoryNode dresses = leaf("Fashion › Women › Dresses");
        Product created = products.create(new CreateProductCommand(dresses.id(), null,
                "Ankara Wrap Dress with Puff Sleeves",
                Map.of("brand", "Lagos Textile Hub", "material", "Cotton wax print", "fit", "Regular",
                        "length", "Midi", "sleeve", "Short", "occasion", "Wedding / owambe",
                        "care", "Cold wash", "origin", "Nigeria"),
                List.of(IMAGE), "Size × Color",
                List.of(new VariantDraft(Map.of("Size", "M", "Color", "Gold"), "AWD-M-GLD", null, true, 6499, 5),
                        new VariantDraft(Map.of("Size", "L", "Color", "Gold"), "AWD-L-GLD", null, true, 6499, 3))
                , "USD"));

        assertThat(created.status()).isEqualTo(ProductStatus.IN_REVIEW);
        assertThat(created.owner().kind()).isEqualTo(ListingOwnerKind.PLATFORM);
        assertThat(created.atin().value()).matches("AT[0-9A-Z]{8}");
        assertThat(created.variants()).hasSize(2).allSatisfy(v -> assertThat(v.atin()).isNotEqualTo(created.atin()));

        assertThatThrownBy(() -> publicCatalog.liveProduct(created.atin().value()))
                .isInstanceOf(NotFoundException.class);

        Product live = products.approve(created.id());
        assertThat(live.status()).isEqualTo(ProductStatus.LIVE);
        assertThat(live.version()).isEqualTo(1);

        String variantAtin = live.variants().getFirst().atin().value();
        assertThat(publicCatalog.liveVariant(variantAtin).product().id()).isEqualTo(created.id());
        assertThat(publicCatalog.liveVariant(variantAtin).variant().sellerSku()).isEqualTo("AWD-M-GLD");

        List<String> events = jdbc.sql("select type from platform_outbox where aggregate_id = :id order by occurred_at")
                .param("id", created.id().toString()).query(String.class).list();
        assertThat(events).containsExactly("catalog.product.created", "catalog.product.approved");
        assertThat(live.preorderEligible()).as("dresses are not food").isFalse();
        assertThat(live.shipsNow()).isFalse();
    }

    @Test
    void foodIsPreorderEligibleByDefaultAndFlagsAreEditable() {
        CategoryNode spices = leaf("Groceries › Pantry › Spices & seasonings");
        Product created = products.create(new CreateProductCommand(spices.id(), null,
                "Suya Pepper Spice Blend from Kano",
                Map.of("brand", "Mama Kano", "ingredients", "Groundnut, chilli, ginger", "allergens",
                        "Contains peanuts", "shelf", "365", "storage", "Room temperature", "origin", "Nigeria",
                        "cert", "None"),
                List.of(IMAGE), "Net weight",
                List.of(new VariantDraft(Map.of("Net weight", "8 oz"), "SUYA-8OZ", null, true, 899, 40)), "USD"));
        assertThat(created.preorderEligible()).isTrue();
        assertThat(created.shipsNow()).isFalse();
        assertThat(created.preorderable()).isTrue();

        Product shipsNow = products.setPreorderFlags(created.id(), true, true);
        assertThat(shipsNow.shipsNow()).isTrue();
        assertThat(shipsNow.preorderable()).isFalse();
        assertThat(products.get(created.id()).shipsNow()).isTrue();

        String payload = jdbc.sql("select payload::text from platform_outbox where aggregate_id = :id "
                        + "and type = 'catalog.product.updated'")
                .param("id", created.id().toString()).query(String.class).single();
        assertThat(payload).contains("\"preorderEligible\": true").contains("\"shipsNow\": true");
    }

    @Test
    void sellerListingsHiddenSuppressesPublicReadsAndRepublishesProducts() {
        CategoryNode dresses = leaf("Fashion › Women › Dresses");
        Product created = products.create(new CreateProductCommand(dresses.id(), null,
                "Adire Maxi Dress",
                Map.of("brand", "Lagos Textile Hub", "material", "Cotton", "fit", "Regular",
                        "length", "Maxi", "sleeve", "Long", "occasion", "Wedding / owambe",
                        "care", "Cold wash", "origin", "Nigeria"),
                List.of(IMAGE), "Size × Color",
                List.of(new VariantDraft(Map.of("Size", "M", "Color", "Indigo"), "ADM-M-IND", null, true, 7999, 4)),
                "USD"));
        Product live = products.approve(created.id());
        String org = live.owner().orgId();
        String atin = live.atin().value();
        String variantAtin = live.variants().getFirst().atin().value();
        assertThat(publicCatalog.liveProduct(atin).id()).isEqualTo(live.id());

        Instant t0 = Instant.parse("2026-10-01T10:00:00Z");
        ListingEnforcement hide = new ListingEnforcement(UUID.randomUUID(), org, "CHARGES_DISABLED", t0);
        try {
            assertThat(visibility.hide(hide)).isPositive();
            assertThat(visibility.hide(hide)).as("redelivery is a no-op").isZero();
            assertThat(visibility.hidden(org)).isTrue();
            assertThatThrownBy(() -> publicCatalog.liveProduct(atin)).isInstanceOf(NotFoundException.class);
            assertThatThrownBy(() -> publicCatalog.liveVariant(variantAtin)).isInstanceOf(NotFoundException.class);
            assertThat(payload("catalog.product.hidden", live.id())).contains("\"sellerHidden\": true",
                    "\"status\": \"LIVE\"");

            // The seller's own lifecycle keeps working; its events say the listing is still hidden.
            products.pause(live.id());
            products.resume(live.id());
            assertThat(payload("catalog.product.resumed", live.id())).contains("\"sellerHidden\": true");

            // An older restore delivered late is ignored.
            assertThat(visibility.restore(new ListingEnforcement(UUID.randomUUID(), org, "CLEARED",
                    t0.minusSeconds(60)))).isZero();
            assertThat(visibility.hidden(org)).isTrue();
        } finally {
            assertThat(visibility.restore(new ListingEnforcement(UUID.randomUUID(), org, "CLEARED",
                    t0.plusSeconds(60)))).isPositive();
        }
        assertThat(visibility.hidden(org)).isFalse();
        assertThat(publicCatalog.liveProduct(atin).id()).isEqualTo(live.id());
        assertThat(payload("catalog.product.restored", live.id())).contains("\"sellerHidden\": false",
                "\"status\": \"LIVE\"");
    }

    private String payload(String type, UUID productId) {
        return jdbc.sql("select payload::text from platform_outbox where type = :t and aggregate_id = :id "
                        + "order by occurred_at desc limit 1")
                .param("t", type).param("id", productId.toString()).query(String.class).single();
    }

    @Test
    void duplicateGtinPointsToExistingProductPage() {
        CategoryNode spices = leaf("Groceries › Pantry › Spices & seasonings");
        Map<String, String> attrs = Map.of("brand", "AfroTransact", "ingredients", "Pepper, thyme, curry",
                "allergens", "None", "shelf", "365", "storage", "Room temperature", "origin", "Nigeria", "cert", "None");
        products.create(new CreateProductCommand(spices.id(), null, "Jollof Seasoning Blend 8 oz", attrs, List.of(IMAGE),
                "Net weight", List.of(new VariantDraft(Map.of("Net weight", "8 oz"), "JOL-8", "4006381333931", false,
                899, 10)), "USD"));

        assertThatThrownBy(() -> products.create(new CreateProductCommand(spices.id(), null,
                "Jollof Seasoning Blend Copy", attrs, List.of(IMAGE), "Net weight",
                List.of(new VariantDraft(Map.of("Net weight", "8 oz"), "JOL-8-B", "4006381333931", false, 799, 4)),
                "USD")))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("already has a product page");
    }

    @Test
    void productsWaitForRequestedCategoryThenMoveToReview() {
        CategoryRequest request = categories.request("Fashion › Women", "Bridal aso-ebi sets", "6-piece aso-ebi set",
                "Pieces, colour, fabric, sizes");
        assertThat(request.reference()).startsWith("REQ-");

        Product waiting = products.create(new CreateProductCommand(null, request.id(), "Six-piece Aso-ebi Bridal Set",
                Map.of("brand", "Ife Bridal", "material", "Aso-oke", "details", "Gele, ipele, iro, buba",
                        "origin", "Nigeria"),
                List.of(IMAGE), "Option",
                List.of(new VariantDraft(Map.of("Option", "Standard"), "ASO-STD", null, true, 25000, 2)), "USD"));
        assertThat(waiting.status()).isEqualTo(ProductStatus.PENDING_CATEGORY_APPROVAL);

        CategoryNode women = categories.tree().stream()
                .filter(n -> n.level() == CategoryLevel.CATEGORY && n.path().equals("Fashion › Women"))
                .findFirst().orElseThrow();
        categories.approve(request.id(), women.id(), "custom", "Approved for launch");

        Product moved = products.get(waiting.id());
        assertThat(moved.status()).isEqualTo(ProductStatus.IN_REVIEW);
        assertThat(moved.categoryId()).isNotNull();
        assertThat(categories.tree()).anyMatch(n -> n.path().equals("Fashion › Women › Bridal aso-ebi sets"));
    }

    private CategoryNode leaf(String path) {
        return categories.tree().stream().filter(n -> n.path().equals(path)).findFirst()
                .orElseThrow(() -> new IllegalStateException("Seed category missing: " + path + " " + UUID.randomUUID()));
    }
}
