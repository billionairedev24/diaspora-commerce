package com.afrotransact.catalog.it;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import com.afrotransact.catalog.application.CategoryService;
import com.afrotransact.catalog.application.CreateProductCommand;
import com.afrotransact.catalog.application.ProductDraftService;
import com.afrotransact.catalog.application.ProductService;
import com.afrotransact.catalog.application.UpdateProductCommand;
import com.afrotransact.catalog.domain.CatalogRuleViolation;
import com.afrotransact.catalog.domain.CategoryLevel;
import com.afrotransact.catalog.domain.CategoryNode;
import com.afrotransact.catalog.domain.FulfilmentMode;
import com.afrotransact.catalog.domain.ItemCondition;
import com.afrotransact.catalog.domain.ListingDetails;
import com.afrotransact.catalog.domain.PackageDimensions;
import com.afrotransact.catalog.domain.Product;
import com.afrotransact.catalog.domain.ProductImage;
import com.afrotransact.catalog.domain.ProductStatus;
import com.afrotransact.catalog.domain.ProductType;
import com.afrotransact.catalog.domain.SavedDraft;
import com.afrotransact.catalog.domain.ShipFromRef;
import com.afrotransact.catalog.domain.VariantDraft;
import com.afrotransact.platform.security.Actor;
import com.afrotransact.platform.security.PrincipalType;
import com.afrotransact.platform.test.PostgresTestContainer;
import com.afrotransact.platform.web.error.ConflictException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/** The one-step product form against PostgreSQL (S307): product types with traits, listing details, edit, drafts. */
@SpringBootTest(properties = {"at.events.relay-enabled=false", "at.consumers.enabled=false",
    "spring.kafka.bootstrap-servers=localhost:1"})
@ActiveProfiles("local")
@Testcontainers
@Import(TestActors.class)
class ProductFormIT {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer POSTGRES = PostgresTestContainer.create();

    private static final ProductImage IMAGE = new ProductImage("https://utfs.io/f/suya.jpg", 1600, 1600, 300_000,
            "image/jpeg");
    private static final Map<String, String> SUYA = Map.of("brand", "Mama Kano", "ingredients",
            "Groundnut, chilli, ginger", "allergens", "Contains peanuts", "shelf", "12 months", "storage",
            "Room temperature", "origin", "Nigeria", "cert", "None");

    private final ProductService products;
    private final ProductDraftService drafts;
    private final CategoryService categories;
    private final JdbcClient jdbc;

    @Autowired
    ProductFormIT(ProductService products, ProductDraftService drafts, CategoryService categories, JdbcClient jdbc) {
        this.products = products;
        this.drafts = drafts;
        this.categories = categories;
        this.jdbc = jdbc;
    }

    @BeforeEach
    void seller() {
        TestActors.as(new Actor("member-form", PrincipalType.SELLER_MEMBER, Optional.of("org_form"),
                Set.of("seller.owner"), Set.of("catalog:write")));
    }

    @AfterEach
    void reset() {
        TestActors.reset();
    }

    private CategoryNode leaf(String path) {
        return categories.tree().stream().filter(n -> n.level() == CategoryLevel.PRODUCT_TYPE && n.path().equals(path))
                .findFirst().orElseThrow();
    }

    private List<String> events(Product p) {
        return jdbc.sql("select type from platform_outbox where aggregate_id = :id order by occurred_at, type")
                .param("id", p.id().toString()).query(String.class).list();
    }

    @Test
    void productTypesCarryTraitsAndAttributeMetadata() {
        ProductType grocery = categories.productType("grocery");
        assertThat(grocery.traits().food()).isTrue();
        assertThat(grocery.traits().preorder()).isTrue();
        assertThat(grocery.attribute("shelf").orElseThrow().units()).containsExactly("days", "months");
        assertThat(grocery.attribute("allergens").orElseThrow().section().name()).isEqualTo("FOOD");
        ProductType perishable = categories.productType("perishable");
        assertThat(perishable.traits().regulated()).isTrue();
        assertThat(perishable.attribute("permit").orElseThrow().section().name()).isEqualTo("REGULATED");
        assertThat(perishable.attribute("shelf").orElseThrow().required()).isFalse();
        assertThat(categories.productType("dress").traits().food()).isFalse();
    }

    @Test
    void aSimpleProductWithListingDetailsIsCreatedEditedAndResubmitted() {
        ShipFromRef houston = new ShipFromRef("loc-1", "Houston", "Houston", "TX", "US", "77002");
        Product created = products.create(new CreateProductCommand(leaf("Groceries › Pantry › Spices & seasonings").id(),
                null, "Suya Pepper Spice Blend from Kano", SUYA, List.of(IMAGE), "",
                List.of(new VariantDraft(Map.of(), "suya-1", null, true, 899, 40, 1199L, List.of())), "USD", true, null,
                new ListingDetails("Hand-ground in Kano.", ItemCondition.NEW, List.of("suya", "yaji"),
                        FulfilmentMode.SELLER, null, 3, houston), null));
        assertThat(created.variationTheme()).isEmpty();
        assertThat(created.variants()).singleElement().satisfies(v -> {
            assertThat(v.sellerSku()).isEqualTo("SUYA-1");
            assertThat(v.compareAtMinor()).isEqualTo(1199L);
            assertThat(v.options()).isEmpty();
        });
        assertThat(created.details().keywords()).containsExactly("suya", "yaji");
        assertThat(created.details().shipFrom()).isEqualTo(houston);

        TestActors.reset();
        Product rejected = products.reject(created.id(), "Main image has a watermark");
        seller();
        Product edited = products.update(created.id(), new UpdateProductCommand(null, rejected.title(), SUYA,
                List.of(IMAGE), "", List.of(new VariantDraft(Map.of(), "SUYA-1", null, true, 799, 25, null, List.of())),
                null, null, new ListingDetails("Hand-ground in Kano.", ItemCondition.NEW, List.of("suya"),
                        FulfilmentMode.AFROTRANSACT, new PackageDimensions(6, 4, 2, 0.5), 2, null),
                rejected.version(), null));
        assertThat(edited.status()).isEqualTo(ProductStatus.IN_REVIEW);
        assertThat(edited.moderationNote()).isNull();
        assertThat(edited.variants().getFirst().priceMinor()).isEqualTo(799);
        assertThat(edited.variants().getFirst().atin()).isEqualTo(created.variants().getFirst().atin());
        assertThat(edited.details().fulfilment()).isEqualTo(FulfilmentMode.AFROTRANSACT);
        assertThat(events(edited)).contains("catalog.product.updated", "catalog.product.terms_changed",
                "catalog.product.resubmitted");

        assertThatThrownBy(() -> products.update(created.id(), new UpdateProductCommand(null, rejected.title(), SUYA,
                List.of(IMAGE), "", List.of(new VariantDraft(Map.of(), "SUYA-1", null, true, 799, 25)), null, null,
                null, rejected.version(), null))).isInstanceOf(ConflictException.class);
    }

    @Test
    void aPublishedListingKeepsItsVariantsAndCategory() {
        Product created = products.create(new CreateProductCommand(leaf("Groceries › Pantry › Snacks").id(), null,
                "Chin Chin, Crunchy, Lightly Sweet", SUYA, List.of(IMAGE), "Net weight",
                List.of(new VariantDraft(Map.of("Net weight", "8 oz"), "CC-8", null, true, 599, 10),
                        new VariantDraft(Map.of("Net weight", "1 lb"), "CC-16", null, true, 999, 10)), "USD"));
        TestActors.reset();
        Product live = products.approve(created.id());
        seller();
        assertThatThrownBy(() -> products.update(live.id(), new UpdateProductCommand(null, live.title(), SUYA,
                List.of(IMAGE), "Net weight", List.of(new VariantDraft(Map.of("Net weight", "8 oz"), "CC-8", null, true,
                        599, 10)), null, null, null, null, null)))
                .isInstanceOfSatisfying(CatalogRuleViolation.class, ex -> assertThat(ex.fieldErrors()).containsKey("variants"));
        assertThatThrownBy(() -> products.update(live.id(), new UpdateProductCommand(
                leaf("Groceries › Pantry › Spices & seasonings").id(), live.title(), SUYA, List.of(IMAGE), "Net weight",
                List.of(new VariantDraft(Map.of("Net weight", "8 oz"), "CC-8", null, true, 599, 10),
                        new VariantDraft(Map.of("Net weight", "1 lb"), "CC-16", null, true, 999, 10)), null, null, null,
                null, null))).isInstanceOfSatisfying(CatalogRuleViolation.class,
                        ex -> assertThat(ex.fieldErrors()).containsKey("category"));
        Product added = products.update(live.id(), new UpdateProductCommand(null, live.title(), SUYA, List.of(IMAGE),
                "Net weight", List.of(new VariantDraft(Map.of("Net weight", "8 oz"), "CC-8", null, true, 599, 0),
                        new VariantDraft(Map.of("Net weight", "1 lb"), "CC-16", null, true, 999, 10)), null, null, null,
                null, null));
        assertThat(added.status()).isEqualTo(ProductStatus.LIVE);
        assertThat(added.variants().getFirst().quantity()).isZero();
    }

    @Test
    void draftsAutosaveWithVersionsAndAreDeletedOnSubmit() {
        SavedDraft d = drafts.create(null, "Ankara", null, "{\"title\":\"Ankara\"}");
        SavedDraft saved = drafts.save(d.id(), d.version(), "Ankara Wrap Dress", "Fashion › Women › Dresses",
                "{\"title\":\"Ankara Wrap Dress\"}");
        assertThat(saved.version()).isEqualTo(d.version() + 1);
        assertThatThrownBy(() -> drafts.save(d.id(), d.version(), "stale", null, "{}"))
                .isInstanceOf(ConflictException.class);
        assertThat(drafts.list()).extracting(SavedDraft::title).contains("Ankara Wrap Dress");

        Product p = products.create(new CreateProductCommand(leaf("Groceries › Pantry › Snacks").id(), null,
                "Plantain Chips, Sea Salt, 5 oz", SUYA, List.of(IMAGE), "",
                List.of(new VariantDraft(Map.of(), "PLANTAIN-5", null, true, 499, 30)), "USD", null, null, null,
                saved.id()));
        assertThat(p.status()).isEqualTo(ProductStatus.IN_REVIEW);
        assertThat(drafts.list()).extracting(SavedDraft::id).doesNotContain(saved.id());

        SavedDraft edit = drafts.create(p.id(), p.title(), null, "{\"title\":\"x\"}");
        SavedDraft again = drafts.create(p.id(), p.title(), null, "{\"title\":\"y\"}");
        assertThat(again.id()).isEqualTo(edit.id());
        TestActors.as(new Actor("member-other", PrincipalType.SELLER_MEMBER, Optional.of("org_other"),
                Set.of("seller.owner"), Set.of("catalog:write")));
        assertThatThrownBy(() -> drafts.get(edit.id())).hasMessageContaining("not found");
    }
}
