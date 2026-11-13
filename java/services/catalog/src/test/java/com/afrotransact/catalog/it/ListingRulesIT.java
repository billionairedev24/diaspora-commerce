package com.afrotransact.catalog.it;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

import com.afrotransact.catalog.application.CategoryService;
import com.afrotransact.catalog.application.CreateProductCommand;
import com.afrotransact.catalog.application.ListingRulesService;
import com.afrotransact.catalog.application.ProductImportService;
import com.afrotransact.catalog.application.ProductService;
import com.afrotransact.catalog.application.UpdateProductCommand;
import com.afrotransact.catalog.domain.CatalogRuleViolation;
import com.afrotransact.catalog.domain.CategoryLevel;
import com.afrotransact.catalog.domain.CategoryNode;
import com.afrotransact.catalog.domain.Gtin;
import com.afrotransact.catalog.domain.ItemCondition;
import com.afrotransact.catalog.domain.ListingDetails;
import com.afrotransact.catalog.domain.PackageDimensions;
import com.afrotransact.catalog.domain.Product;
import com.afrotransact.catalog.domain.ProductImage;
import com.afrotransact.catalog.domain.VariantDraft;
import com.afrotransact.catalog.domain.imports.ImportMode;
import com.afrotransact.catalog.domain.imports.ImportStatus;
import com.afrotransact.catalog.domain.imports.ProductImport;
import com.afrotransact.catalog.domain.imports.RowOutcome;
import com.afrotransact.catalog.domain.imports.SpreadsheetFormat;
import com.afrotransact.catalog.domain.imports.StoredRow;
import com.afrotransact.platform.security.Actor;
import com.afrotransact.platform.security.PrincipalType;
import com.afrotransact.platform.test.PostgresTestContainer;
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

/**
 * The listing rules against PostgreSQL with the real matrix: the package is kept per variant and sent in the event,
 * the dangerous goods answer is stored and sent, hidden answers are never stored, and the bulk upload follows the same
 * rules.
 */
@SpringBootTest(properties = {"at.events.relay-enabled=false", "at.consumers.enabled=false",
    "spring.kafka.bootstrap-servers=localhost:1", "at.catalog.imports.resume-interval=1h"})
@ActiveProfiles("local")
@Testcontainers
@Import({TestActors.class, ProductImportIT.FakeMedia.class})
class ListingRulesIT {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer POSTGRES = PostgresTestContainer.create();

    private static final ProductImage PHOTO = new ProductImage("https://utfs.io/f/rice.jpg", 1600, 1600, 300_000, "image/jpeg");
    private static final ProductImage LABEL = new ProductImage("https://utfs.io/f/label.jpg", 1600, 1600, 300_000,
            "image/jpeg").asLabel();
    private static final PackageDimensions SMALL = new PackageDimensions(8, 6, 2, 0.6);
    private static final PackageDimensions BIG = new PackageDimensions(14, 10, 6, 1.4);

    private final ProductService products;
    private final CategoryService categories;
    private final ListingRulesService rules;
    private final ProductImportService imports;
    private final JdbcClient jdbc;

    @Autowired
    ListingRulesIT(ProductService products, CategoryService categories, ListingRulesService rules,
                   ProductImportService imports, JdbcClient jdbc) {
        this.products = products;
        this.categories = categories;
        this.rules = rules;
        this.imports = imports;
        this.jdbc = jdbc;
    }

    private String org;

    @BeforeEach
    void seller() {
        org = "org_rules_" + UUID.randomUUID().toString().substring(0, 8);
        TestActors.as(new Actor("member-" + org, PrincipalType.SELLER_MEMBER, Optional.of(org),
                Set.of("seller.owner"), Set.of("catalog:write")));
    }

    @AfterEach
    void reset() {
        TestActors.reset();
    }

    /** The product-type leaf of a rules category (the node under the category with this code). */
    private CategoryNode leafOf(String categoryCode) {
        List<CategoryNode> tree = categories.tree();
        UUID category = tree.stream().filter(n -> categoryCode.equals(n.code())).findFirst().orElseThrow().id();
        return tree.stream().filter(n -> n.level() == CategoryLevel.PRODUCT_TYPE && category.equals(n.parentId()))
                .findFirst().orElseThrow();
    }

    private static Map<String, String> riceAttributes() {
        return Map.of("brand", "Mama Kano", "net_weight", "1 lb", "servings", "8",
                "shelf", "12 months");
    }

    private static ListingDetails details(PackageDimensions pkg) {
        return new ListingDetails("Long grain parboiled rice.", ItemCondition.NEW, List.of(), null, pkg, 2, null);
    }

    private CreateProductCommand rice(List<VariantDraft> variants, ListingDetails details) {
        return new CreateProductCommand(leafOf("rice-grains").id(), null, "Ofada Rice, Destoned, Long Grain",
                riceAttributes(), List.of(PHOTO, LABEL), "Net weight", variants, "USD", null, null, details, null);
    }

    private static VariantDraft variant(String sku, String size, String gtin, PackageDimensions pkg) {
        return new VariantDraft(Map.of("Net weight", size), sku, gtin, false, 899, 10).withPackaging(pkg);
    }

    /** A valid GTIN-13 nobody used before (barcodes are unique across the catalog). */
    private static String gtin() {
        String body = String.format("%012d", ThreadLocalRandom.current().nextLong(100_000_000_000L, 999_999_999_999L));
        return body + Gtin.checkDigit(body);
    }

    /** Creates the product; a refusal reports the field errors, not only their count. */
    private Product create(CreateProductCommand cmd) {
        try {
            return products.create(cmd);
        } catch (CatalogRuleViolation ex) {
            throw new AssertionError("refused: " + ex.fieldErrors(), ex);
        }
    }

    private String payloadOf(Product p) {
        return jdbc.sql("select payload::text from platform_outbox where aggregate_id = :id and type = 'catalog.product.created'")
                .param("id", p.id().toString()).query(String.class).single().replace(" ", "");
    }

    @Test
    void everyCategoryOfTheTaxonomyResolvesToItsRules() {
        categories.tree().stream().filter(n -> n.level() == CategoryLevel.PRODUCT_TYPE).forEach(leaf -> {
            var r = rules.forNode(leaf);
            assertThat(r.fields()).as(leaf.path()).hasSize(31);
            assertThat(r.provisional() && !"fabrics-textiles".equals(r.code())).as(leaf.path() + " has rules").isFalse();
        });
    }

    @Test
    void theSamePackageIsNotImposedOnVariantsOfDifferentSizes() {
        Product created = create(rice(List.of(variant("RICE-8", "8 oz", gtin(), SMALL),
                variant("RICE-16", "1 lb", gtin(), BIG)), details(null)));

        Product reloaded = products.get(created.id());
        assertThat(reloaded.variants()).extracting(v -> v.packaging()).containsExactly(SMALL, BIG);
        assertThat(payloadOf(created)).contains("\"lengthIn\":8.0").contains("\"lengthIn\":14.0")
                .contains("\"dangerousGoods\":false");
        assertThat(reloaded.details().packaging()).isEqualTo(SMALL);
    }

    @Test
    void aVariantWithoutItsOwnPackageStartsFromTheListingPackage() {
        Product created = create(rice(List.of(variant("RICE-8", "8 oz", gtin(), null),
                variant("RICE-16", "1 lb", gtin(), BIG)), details(SMALL)));

        assertThat(products.get(created.id()).variants()).extracting(v -> v.packaging()).containsExactly(SMALL, BIG);
    }

    @Test
    void aPhysicalVariantWithoutAnyPackageIsRefusedOnItsOwnField() {
        assertThatThrownBy(() -> products.create(rice(List.of(variant("RICE-8", "8 oz", gtin(), null)),
                details(null)))).isInstanceOfSatisfying(CatalogRuleViolation.class,
                        ex -> assertThat(ex.fieldErrors()).containsKey("variants[0].packaging"));
    }

    @Test
    void anEditThatSendsNoPackageKeepsTheStoredOnePerVariant() {
        Product created = create(rice(List.of(variant("RICE-8", "8 oz", gtin(), SMALL),
                variant("RICE-16", "1 lb", gtin(), BIG)), details(null)));

        Product edited = products.update(created.id(), new UpdateProductCommand(null, created.title(),
                created.attributes(), created.images(), "Net weight", List.of(
                        new VariantDraft(Map.of("Net weight", "8 oz"), "RICE-8", created.variants().get(0).gtin(), false, 799, 10),
                        new VariantDraft(Map.of("Net weight", "1 lb"), "RICE-16", created.variants().get(1).gtin(), false, 999, 10)),
                null, null, details(null), created.version(), null));

        assertThat(edited.variants()).extracting(v -> v.packaging()).containsExactly(SMALL, BIG);
    }

    @Test
    void anEmptyPackagedFoodListingIsRefusedForTheFieldsTheMatrixRequires() {
        CreateProductCommand empty = new CreateProductCommand(leafOf("rice-grains").id(), null,
                "Ofada Rice, Destoned, Long Grain", Map.of(), List.of(PHOTO), "",
                List.of(new VariantDraft(Map.of(), "RICE-1", null, false, 899, 10)), "USD");

        assertThatThrownBy(() -> products.create(empty)).isInstanceOfSatisfying(CatalogRuleViolation.class,
                ex -> assertThat(ex.fieldErrors()).containsKeys("description",
                        "attributes.net_weight", "attributes.servings", "attributes.shelf", "variants[0].gtin",
                        "variants[0].packaging", "attributes.brand"));
    }

    @Test
    void answersToHiddenQuestionsAreNotStoredAndConditionIsNew() {
        Map<String, String> attrs = new HashMap<>(riceAttributes());
        attrs.put("ingredients", "Rice");
        attrs.put("material", "Sack");
        CreateProductCommand cmd = new CreateProductCommand(leafOf("rice-grains").id(), null,
                "Ofada Rice, Destoned, Long Grain", attrs, List.of(PHOTO, LABEL), "Net weight",
                List.of(variant("RICE-8", "8 oz", gtin(), SMALL)), "USD", null, null,
                new ListingDetails("Rice.", ItemCondition.USED_GOOD, List.of(), null, null, 2, null), null);

        Product created = products.get(create(cmd).id());

        assertThat(created.attributes()).doesNotContainKeys("ingredients", "material");
        assertThat(created.details().condition()).isEqualTo(ItemCondition.NEW);
        assertThat(created.images().getLast().isLabel()).isTrue();
    }

    @Test
    void aYesToDangerousGoodsIsStoredAndTravelsInTheEvent() {
        ListingDetails yes = new ListingDetails("Eau de parfum.", ItemCondition.NEW, List.of(), null, null, 2, null)
                .withRuleAnswers(true, List.of(), Map.of("shelf_life", "No expiry printed"));
        CreateProductCommand cmd = new CreateProductCommand(leafOf("perfumes-fragrances").id(), null,
                "Oud Eau de Parfum, 50 ml", Map.of("brand", "Ajmal", "net_weight", "50 ml",
                        "ingredients", "Alcohol, parfum"), List.of(PHOTO, LABEL), "",
                List.of(new VariantDraft(Map.of(), "OUD-50", gtin(), false, 4500, 4).withPackaging(SMALL)),
                "USD", null, null, yes, null);

        Product created = create(cmd);

        assertThat(products.get(created.id()).details().dangerousGoods()).isTrue();
        assertThat(payloadOf(created)).contains("\"dangerousGoods\":true");
    }

    @Test
    void theBulkTemplateOfACategoryAndTheUploadFollowTheSameRules() {
        CategoryNode leaf = leafOf("rice-grains");
        String template = new String(imports.template(null, leaf.id(), SpreadsheetFormat.CSV).content(), StandardCharsets.UTF_8);
        assertThat(template).contains("Label photo URL").doesNotContain("Label photo URL *").contains("Package length (in) *").doesNotContain("Ingredients");

        String header = "category,parent_sku,sku,title,brand,description,image_urls,label_photo_url,price,quantity,gtin,"
                + "variation_theme,option:Net weight,attr:net_weight,attr:net_weight:unit,attr:servings,"
                + "attr:shelf,attr:shelf:unit,package_length_in,package_width_in,package_height_in,package_weight_lb";
        String g1 = gtin();
        String g2 = gtin();
        String first = leaf.path() + ",RICE,RICE-8,\"Ofada Rice, Destoned, Long Grain\",Mama Kano,Long grain rice,"
                + "https://cdn.example.com/rice.jpg,https://cdn.example.com/label.jpg,8.99,10," + g1 + ","
                + "Net weight,8 oz,8,oz,4,12,months,8,6,2,0.6";
        String second = leaf.path() + ",RICE,RICE-16,,,,,,10.99,10," + g2 + ",,1 lb,,,,,,14,10,6,1.4";
        byte[] file = (header + "\r\n" + first + "\r\n" + second + "\r\n").getBytes(StandardCharsets.UTF_8);

        ProductImport upload = imports.upload("rice.csv", file, ImportMode.CREATE, leaf.productTypeCode(), "Ade Okafor");
        assertThat(upload.errorRows()).isZero();
        imports.start(upload.id(), false);
        ProductImport done = awaitDone(upload.id());

        assertThat(done.createdProducts()).isEqualTo(1);
        List<StoredRow> rows = imports.rows(upload.id(), null, null, 0, 10).items();
        assertThat(rows).extracting(StoredRow::outcome).containsOnly(RowOutcome.CREATED);
        Product p = products.get(rows.getFirst().productId());
        assertThat(p.variants()).extracting(v -> v.packaging()).containsExactly(SMALL, BIG);
        assertThat(p.images()).extracting(ProductImage::isLabel).containsExactly(false, true);
    }

    @Test
    void aBulkRowMissingThePackageGetsAPlainErrorPerColumn() {
        String header = "sku,title,brand,description,image_urls,price,quantity,gtin,attr:net_weight,"
                + "attr:net_weight:unit,attr:servings,attr:shelf,attr:shelf:unit,category,label_photo_url,package_length_in";
        String row = "RICE-1,\"Ofada Rice, Destoned, Long Grain\",Mama Kano,Long grain rice,https://cdn.example.com/rice.jpg,"
                + "8.99,10," + gtin() + ",8,oz,4,12,months," + leafOf("rice-grains").path() + ",,";
        ProductImport upload = imports.upload("rice.csv", (header + "\r\n" + row + "\r\n").getBytes(StandardCharsets.UTF_8),
                ImportMode.CREATE, leafOf("rice-grains").productTypeCode(), "Ade Okafor");

        assertThat(upload.errorRows()).isEqualTo(1);
        StoredRow bad = imports.rows(upload.id(), null, null, 0, 10).items().getFirst();
        assertThat(bad.messages()).extracting(m -> m.column() + ": " + m.message()).contains(
                "package_length_in: Add the package length, width, height and weight of the box as shipped");
    }

    private ProductImport awaitDone(UUID id) {
        Instant until = Instant.now().plus(Duration.ofSeconds(30));
        while (Instant.now().isBefore(until)) {
            ProductImport u = imports.get(id);
            if (u.status() == ImportStatus.COMPLETED || u.status() == ImportStatus.FAILED) {
                return u;
            }
            try {
                Thread.sleep(100);
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(ex);
            }
        }
        throw new AssertionError("import " + id + " did not finish: " + imports.get(id));
    }
}
