package com.afrotransact.catalog.it;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import com.afrotransact.catalog.application.CatalogPermissions;
import com.afrotransact.catalog.application.CatalogRelations;
import com.afrotransact.catalog.application.CategoryService;
import com.afrotransact.catalog.application.CreateProductCommand;
import com.afrotransact.catalog.application.ProductService;
import com.afrotransact.catalog.domain.CategoryNode;
import com.afrotransact.catalog.domain.Product;
import com.afrotransact.catalog.domain.ProductImage;
import com.afrotransact.catalog.domain.ProductStatus;
import com.afrotransact.catalog.domain.VariantDraft;
import com.afrotransact.platform.security.Actor;
import com.afrotransact.platform.security.PermissionChecker;
import com.afrotransact.platform.security.PrincipalType;
import com.afrotransact.platform.security.relationship.FgaObject;
import com.afrotransact.platform.security.relationship.RelationshipTuple;
import com.afrotransact.platform.security.relationship.RelationshipWriter;
import com.afrotransact.platform.security.relationship.ResourceAccessChecker;
import com.afrotransact.platform.security.relationship.ResourceAccessCheckers;
import com.afrotransact.platform.test.PostgresTestContainer;
import com.afrotransact.platform.web.error.ForbiddenActionException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * S248: catalog's relationship checks against a real OpenFGA (local image {@code openfga/openfga:v1.10.0}) loaded with
 * {@code contracts/openfga/model.json}. A member of one organisation cannot read or change another organisation's
 * products even with the RBAC permission and a forged {@code org} claim; staff grants are scoped by market; the
 * any-status variant lookup (S226) is open to first-party service clients only; revocation applies at once.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
    "at.events.relay-enabled=false", "at.consumers.enabled=false", "spring.kafka.bootstrap-servers=localhost:1"})
@ActiveProfiles("local")
@Import(TestActors.class)
class ProductRelationshipsIT {

    /** LAGOS sells in the US, MAMATEE in Great Britain (see the seeded tuples). */
    private static final String LAGOS = "org_lagostextilehub00001";
    private static final String MAMATEE = "org_mamateespantry000001";
    private static final ProductImage IMAGE = new ProductImage("https://utfs.io/f/ankara.jpg", 1600, 1600, 300_000,
            "image/jpeg");

    private static final PostgreSQLContainer POSTGRES = PostgresTestContainer.create();
    private static final GenericContainer<?> OPENFGA = OpenFgaTestContainer.create();
    private static final String STORE_ID;

    static {
        POSTGRES.start();
        OPENFGA.start();
        STORE_ID = OpenFgaTestContainer.createStoreWithModel(OPENFGA);
    }

    private final ProductService products;
    private final CategoryService categories;
    private final RelationshipWriter tuples;
    private final ResourceAccessChecker access;
    private final PermissionChecker permissions;
    private final int port;

    @Autowired
    ProductRelationshipsIT(ProductService products, CategoryService categories, RelationshipWriter tuples,
                           ResourceAccessChecker access, PermissionChecker permissions, @LocalServerPort int port) {
        this.products = products;
        this.categories = categories;
        this.tuples = tuples;
        this.access = access;
        this.permissions = permissions;
        this.port = port;
    }

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("at.security.fga.enabled", () -> "true");
        registry.add("at.security.fga.api-url", () -> OpenFgaTestContainer.apiUrl(OPENFGA));
        registry.add("at.security.fga.store-id", () -> STORE_ID);
        registry.add("at.security.fga.timeout", () -> "5s");
    }

    @BeforeEach
    void seedRelationships() {
        FgaObject platform = FgaObject.PLATFORM;
        FgaObject us = FgaObject.of("market", "US");
        FgaObject gb = FgaObject.of("market", "GB");
        FgaObject lagos = FgaObject.organization(LAGOS);
        FgaObject mamatee = FgaObject.organization(MAMATEE);
        tuples.grant(
                // what authz-sync writes from seller.org.approved / updated
                RelationshipTuple.of(platform, "platform", us), RelationshipTuple.of(platform, "platform", gb),
                RelationshipTuple.of(platform, "platform", lagos), RelationshipTuple.of(us, "market", lagos),
                RelationshipTuple.of("user:*", "sells_products", lagos),
                RelationshipTuple.of(platform, "platform", mamatee), RelationshipTuple.of(gb, "market", mamatee),
                RelationshipTuple.of("user:*", "sells_products", mamatee),
                // organisation roles (identity.role.granted with orgId)
                RelationshipTuple.of("user:cat-lagos", "catalog", lagos),
                RelationshipTuple.of("user:owner-mamatee", "owner", mamatee),
                RelationshipTuple.of("user:ads-mamatee", "ads", mamatee),
                // platform roles, first-party service clients and market-scoped staff (identity role events, S248)
                RelationshipTuple.of("user:local-dev", "super_admin", platform),
                RelationshipTuple.of("client:retail-service", "service", platform),
                RelationshipTuple.of("user:us-catalog", "catalog_manager", us));
    }

    @AfterEach
    void resetActor() {
        TestActors.reset();
    }

    @Test
    void aMemberCannotTouchAnotherOrganisationsProductsEvenWithAForgedOrgClaim() throws Exception {
        assertThat(access.relationshipsEnabled()).isTrue();
        Product lagos = productOf(lagosCatalog(), "LAG");
        Product mamatee = productOf(mamateeOwner(), "MAM");

        // cat-lagos is a catalog member of LAGOS only, but the token claims MAMATEE and carries catalog:write.
        Actor forger = member("cat-lagos", MAMATEE, "seller.catalog");
        TestActors.as(forger);
        assertThatCode(() -> ResourceAccessCheckers.rbacOnly(permissions).requireOwned(CatalogPermissions.WRITE,
                CatalogRelations.CAN_EDIT, FgaObject.of("product", mamatee.id()), MAMATEE))
                .doesNotThrowAnyException();

        assertDenied(() -> products.get(mamatee.id()));
        assertDenied(() -> products.getByAtin(mamatee.atin().value()));
        assertDenied(() -> products.pause(mamatee.id()));
        assertDenied(() -> products.list(null, null, null, 0, 20));
        assertDenied(() -> products.variant(mamatee.variants().getFirst().atin().value()));
        assertDenied(() -> products.create(command(dresses(), "FORGED-" + suffix())));

        HttpResponse<String> http = get("/v1/products/" + mamatee.id());
        assertThat(http.statusCode()).isEqualTo(403);
        assertThat(http.body()).contains("auth.relationship-denied");

        // An ads member of MAMATEE (with its real claim) may view the product but not change it.
        TestActors.as(member("ads-mamatee", MAMATEE, "seller.ads"));
        assertThat(products.get(mamatee.id()).id()).isEqualTo(mamatee.id());
        assertDenied(() -> products.pause(mamatee.id()));

        // With its real claim cat-lagos manages its own products only.
        TestActors.as(lagosCatalog());
        assertThat(products.get(lagos.id()).id()).isEqualTo(lagos.id());
        assertThat(products.list(null, null, null, 0, 100).items()).extracting(p -> p.owner().orgId())
                .containsOnly(LAGOS);

        TestActors.reset();
        assertThat(products.get(mamatee.id()).status()).isEqualTo(ProductStatus.IN_REVIEW);
    }

    @Test
    void aStaffGrantScopedToAMarketWorksOnlyInThatMarket() {
        Product us = live(productOf(lagosCatalog(), "LUS"));
        Product gb = live(productOf(mamateeOwner(), "MGB"));

        TestActors.as(new Actor("us-catalog", PrincipalType.STAFF, Optional.empty(),
                Set.of("platform.catalog_manager"), Set.of(CatalogPermissions.WRITE)));
        assertThat(products.get(us.id()).id()).isEqualTo(us.id());
        assertThat(products.pause(us.id()).status()).isEqualTo(ProductStatus.PAUSED);
        assertThat(products.list(LAGOS, null, null, 0, 100).items()).isNotEmpty();

        assertDenied(() -> products.get(gb.id()));
        assertDenied(() -> products.pause(gb.id()));
        assertDenied(() -> products.list(MAMATEE, null, null, 0, 20));
        assertDenied(() -> products.list(null, null, null, 0, 20));
    }

    @Test
    void theAnyStatusVariantLookupIsForFirstPartyServices() {
        Product draft = productOf(mamateeOwner(), "DRF");
        String variantAtin = draft.variants().getFirst().atin().value();

        // retail-service (platform#service) links a product that is still in review.
        TestActors.as(new Actor("retail-service", PrincipalType.SERVICE, Optional.empty(), Set.of(),
                Set.of(CatalogPermissions.READ)));
        ProductService.VariantLookup found = products.variant(variantAtin.toLowerCase(Locale.ROOT));
        assertThat(found.product().status()).isEqualTo(ProductStatus.IN_REVIEW);
        assertThat(found.variant().atin().value()).isEqualTo(variantAtin);

        // A partner app with the same scope but no delegation from MAMATEE is refused.
        TestActors.as(new Actor("partner-app", PrincipalType.PARTNER_APP, Optional.empty(), Set.of(),
                Set.of(CatalogPermissions.READ)));
        assertDenied(() -> products.variant(variantAtin));

        // The owner sees its own draft through the same endpoint (catalog:write).
        TestActors.as(mamateeOwner());
        assertThat(products.variant(variantAtin).product().id()).isEqualTo(draft.id());
    }

    @Test
    void revokingAMembershipTakesEffectOnTheNextCheck() {
        Product mamatee = productOf(mamateeOwner(), "REV");
        TestActors.as(mamateeOwner());
        assertThat(products.get(mamatee.id()).id()).isEqualTo(mamatee.id());

        RelationshipTuple membership = RelationshipTuple.of("user:owner-mamatee", "owner",
                FgaObject.organization(MAMATEE));
        tuples.revoke(membership);
        try {
            assertDenied(() -> products.get(mamatee.id()));
            assertDenied(() -> products.pause(mamatee.id()));
        } finally {
            tuples.grant(membership);
        }
    }

    private Product productOf(Actor seller, String skuPrefix) {
        TestActors.as(seller);
        try {
            return products.create(command(dresses(), skuPrefix + "-" + suffix()));
        } finally {
            TestActors.reset();
        }
    }

    private Product live(Product p) {
        TestActors.reset();
        return products.approve(p.id());
    }

    private static CreateProductCommand command(CategoryNode leaf, String sku) {
        return new CreateProductCommand(leaf.id(), null, "Ankara Wrap Dress " + sku,
                Map.of("brand", "Lagos Textile Hub", "material", "Cotton wax print", "fit", "Regular",
                        "length", "Midi", "sleeve", "Short", "occasion", "Wedding / owambe",
                        "care", "Cold wash", "origin", "Nigeria"),
                List.of(IMAGE), "Size × Color",
                List.of(new VariantDraft(Map.of("Size", "M", "Color", "Gold"), sku, null, true, 6499, 5)), "USD");
    }

    private CategoryNode dresses() {
        return categories.tree().stream().filter(n -> n.path().equals("Fashion › Women › Dresses")).findFirst()
                .orElseThrow();
    }

    private static Actor lagosCatalog() {
        return member("cat-lagos", LAGOS, "seller.catalog");
    }

    private static Actor mamateeOwner() {
        return member("owner-mamatee", MAMATEE, "seller.owner");
    }

    private static Actor member(String subject, String org, String role) {
        return new Actor(subject, PrincipalType.SELLER_MEMBER, Optional.of(org), Set.of(role),
                Set.of(CatalogPermissions.WRITE));
    }

    private static String suffix() {
        return UUID.randomUUID().toString().substring(0, 8).toUpperCase(Locale.ROOT);
    }

    private static void assertDenied(Runnable call) {
        assertThatThrownBy(call::run).isInstanceOf(ForbiddenActionException.class)
                .hasFieldOrPropertyWithValue("code", "auth.relationship-denied");
    }

    private HttpResponse<String> get(String path) throws IOException, InterruptedException {
        try (HttpClient http = HttpClient.newHttpClient()) {
            return http.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + path)).GET().build(),
                    HttpResponse.BodyHandlers.ofString());
        }
    }
}
