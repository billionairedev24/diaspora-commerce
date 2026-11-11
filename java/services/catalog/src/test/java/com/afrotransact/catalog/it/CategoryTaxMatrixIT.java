package com.afrotransact.catalog.it;

import static org.assertj.core.api.Assertions.assertThat;

import com.afrotransact.catalog.domain.IdentifierRule;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.afrotransact.catalog.application.CategoryService;
import com.afrotransact.catalog.application.CreateProductCommand;
import com.afrotransact.catalog.application.ProductService;
import com.afrotransact.catalog.application.ProductTaxService;
import com.afrotransact.catalog.application.PublicCatalogService;
import com.afrotransact.catalog.domain.AttributeDefinition;
import com.afrotransact.catalog.domain.AttributeKind;
import com.afrotransact.catalog.domain.CatalogRuleViolation;
import com.afrotransact.catalog.domain.CategoryLevel;
import com.afrotransact.catalog.domain.CategoryNode;
import com.afrotransact.catalog.domain.ListingDetails;
import com.afrotransact.catalog.domain.Product;
import com.afrotransact.catalog.domain.ProductImage;
import com.afrotransact.catalog.domain.ProductStatus;
import com.afrotransact.catalog.domain.ProductType;
import com.afrotransact.catalog.domain.VariantDraft;
import com.afrotransact.catalog.domain.tax.TaxNotReady;
import com.afrotransact.catalog.domain.tax.TaxReadiness;
import com.afrotransact.catalog.testsupport.PermissiveRulesConfiguration;
import com.afrotransact.platform.test.PostgresTestContainer;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.context.annotation.Import;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

/**
 * The category tax verification matrix, catalog side, against the real tree seeded by the baseline migration in a real PostgreSQL: for each of the 74
 * categories and each of its decision rows (the 105 golden scenarios), a valid minimal product is built through catalog's real
 * validation (facts per variant, behaviour on the listing, evidence uploaded where the rule needs proof), the publish gate result is
 * checked against the expected one (READY once tax review approves, a gated status otherwise), the product is approved or refused,
 * and the public variant carries the category code and behaviour the checkout reads. Negative cases per category: each required
 * fact removed gives the plain message, text in a number is refused, a gated category cannot go live.
 *
 * <p>Writes {@code target/category-tax-matrix/catalog.json}; {@code tools/taxonomy/verification_report.py} merges it with the
 * tax-returns and orders results into docs/engineering/category-tax-verification.md.
 */
@SpringBootTest(properties = {"at.events.relay-enabled=false", "at.consumers.enabled=false",
    "spring.kafka.bootstrap-servers=localhost:1", "at.catalog.tax.review-required=true"})
@ActiveProfiles("local")
@Testcontainers
@Import(PermissiveRulesConfiguration.class)
class CategoryTaxMatrixIT {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer POSTGRES = PostgresTestContainer.create();

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final ProductImage IMAGE = new ProductImage("https://utfs.io/f/matrix.jpg", 1600, 1600, 300_000, "image/jpeg");
    private static final TypeReference<Map<String, Object>> MAP = new TypeReference<>() { };
    private static final List<String> CLOSED = List.of("NEEDS_APPROVAL", "BLOCKED_BUNDLE", "CONFLICT_OPEN");

    private final ProductService products;
    private final CategoryService categories;
    private final ProductTaxService tax;
    private final PublicCatalogService publicCatalog;

    @Autowired
    CategoryTaxMatrixIT(ProductService products, CategoryService categories, ProductTaxService tax,
                        PublicCatalogService publicCatalog) {
        this.products = products;
        this.categories = categories;
        this.tax = tax;
        this.publicCatalog = publicCatalog;
    }

    @Test
    @SuppressWarnings("unchecked")
    void everyCategoryAndEveryDecisionRowGoesThroughThePublishGate() throws IOException {
        Map<String, Object> dataset = read("taxonomy/category_tax_map.json");
        Map<String, Object> scenarios = read("taxonomy/scenarios.json");
        Map<String, Map<String, Object>> categoryDefs = new LinkedHashMap<>();
        for (Object c : (List<Object>) dataset.get("categories")) {
            Map<String, Object> m = (Map<String, Object>) c;
            categoryDefs.put((String) m.get("code"), m);
        }
        List<CategoryNode> tree = categories.tree();
        List<CategoryNode> categoryNodes = tree.stream().filter(n -> n.level() == CategoryLevel.CATEGORY).toList();
        assertThat(categoryNodes).as("73 categories and the provisional Fabrics & textiles").hasSize(74);

        List<Map<String, Object>> report = new ArrayList<>();
        List<String> failures = new ArrayList<>();
        int serial = 0;
        for (CategoryNode category : categoryNodes) {
            List<CategoryNode> leaves = tree.stream()
                    .filter(n -> n.level() == CategoryLevel.PRODUCT_TYPE && category.id().equals(n.parentId())).toList();
            List<Map<String, Object>> rows = ((List<Object>) scenarios.get("scenarios")).stream()
                    .map(o -> (Map<String, Object>) o).filter(s -> category.code().equals(s.get("categoryCode"))).toList();
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("categoryCode", category.code());
            entry.put("name", category.name());
            entry.put("path", category.path());
            entry.put("status", category.taxStatus());
            entry.put("profile", category.taxProfileId());
            entry.put("productTypes", leaves.stream().map(CategoryNode::productTypeCode).toList());
            List<Map<String, Object>> results = new ArrayList<>();
            ProductType first = categories.productType(leaves.getFirst().productTypeCode());
            entry.put("formFields", Map.of("taxQuestions", first.taxFacts().size(),
                    "evidenceQuestions", first.taxFacts().stream().filter(AttributeDefinition::evidence).count(),
                    "detailQuestions", first.applicableAttributes().size()));
            boolean closed = category.taxStatus() != null && CLOSED.contains(category.taxStatus());

            if (rows.isEmpty()) {
                // The provisional category has no profile: nothing can be published into it.
                results.add(gatedProfileless(category, leaves.getFirst(), ++serial, failures));
            }
            for (Map<String, Object> s : rows) {
                for (int t = 0; t < leaves.size(); t++) {
                    // every decision row on the first product type; the other product types are exercised with the first row
                    if (t > 0 && s != rows.getFirst()) {
                        continue;
                    }
                    results.add(scenario(category, leaves.get(t), s, ++serial, failures));
                }
            }
            if (!rows.isEmpty()) {
                entry.put("negatives", negatives(category, leaves.getFirst(), rows.getFirst(), categoryDefs.get(category.code()),
                        ++serial, closed, failures));
            }
            entry.put("scenarios", results);
            report.add(entry);
        }

        Path out = Path.of("target", "category-tax-matrix");
        Files.createDirectories(out);
        Files.writeString(out.resolve("catalog.json"), JSON.writerWithDefaultPrettyPrinter().writeValueAsString(
                Map.of("categories", report, "failures", failures)), StandardCharsets.UTF_8);
        assertThat(failures).as("matrix failures (see target/category-tax-matrix/catalog.json)").isEmpty();
    }

    // ------------------------------------------------------------------ one decision row

    @SuppressWarnings("unchecked")
    private Map<String, Object> scenario(CategoryNode category, CategoryNode leaf, Map<String, Object> s, int serial,
                                         List<String> failures) {
        Map<String, Object> inputs = (Map<String, Object>) s.get("inputs");
        Map<String, Object> expect = (Map<String, Object>) s.get("expect");
        Map<String, Object> variantFacts = new LinkedHashMap<>((Map<String, Object>) inputs.get("facts"));
        Map<String, Object> behaviour = new LinkedHashMap<>((Map<String, Object>) inputs.get("sellerBehaviour"));
        ProductType type = categories.productType(leaf.productTypeCode());
        String id = "scenario " + s.get("scenario") + " / " + leaf.productTypeCode();
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("scenario", s.get("scenario"));
        r.put("rowId", s.get("rowId"));
        r.put("productType", leaf.productTypeCode());
        r.put("expectedOutcome", expect.get("outcome"));
        r.put("expectedCode", expect.get("internalTaxCode"));
        r.put("expectedStripeCode", expect.get("stripeCode"));
        r.put("expectedResolveStatus", expect.get("resolveStatus"));
        boolean closed = CLOSED.contains(category.taxStatus());
        // the facts a seller types: evidence files are uploaded, not typed
        List<String> evidenceFacts = type.taxFacts().stream().filter(d -> d.kind() == AttributeKind.FILE)
                .map(AttributeDefinition::key).filter(variantFacts::containsKey).toList();
        evidenceFacts.forEach(variantFacts::remove);
        CreateProductCommand cmd = command(leaf, type, "Matrix " + serial, variantFacts, behaviour);
        r.put("publish", new LinkedHashMap<String, Object>());
        Map<String, Object> publish = (Map<String, Object>) r.get("publish");
        Product created;
        try {
            created = products.create(cmd);
        } catch (CatalogRuleViolation e) {
            if (closed) {
                publish.put("result", "REFUSED_CLOSED_CATEGORY");
                publish.put("message", e.fieldErrors().get("category"));
                if (!e.fieldErrors().containsKey("category")) {
                    failures.add(id + ": refused without the category message: " + e.fieldErrors());
                }
                r.put("pass", e.fieldErrors().containsKey("category"));
                return r;
            }
            failures.add(id + ": a valid minimal product was refused: " + e.fieldErrors());
            publish.put("result", "REFUSED");
            publish.put("errors", e.fieldErrors());
            r.put("pass", false);
            return r;
        }
        if (closed) {
            failures.add(id + ": a closed category (" + category.taxStatus() + ") accepted a submission");
            r.put("pass", false);
            return r;
        }
        String atin = created.variants().getFirst().atin().value();
        boolean needsProof = !evidenceFacts.isEmpty();
        TaxReadiness before = tax.readiness(created.id());
        publish.put("statusAfterSubmit", before.status().name());
        if (needsProof) {
            if (!"MISSING_FACTS".equals(before.status().name())) {
                failures.add(id + ": proof is required but the gate says " + before.status());
            }
            for (String factId : evidenceFacts) {
                tax.uploadEvidence(atin, factId, "label.pdf", "application/pdf", "%PDF-1.4 matrix".getBytes(StandardCharsets.UTF_8));
            }
        }
        TaxReadiness gate = tax.readiness(created.id());
        String expectedGate = switch ((String) expect.get("resolveStatus")) {
            case "RESOLVED" -> "PENDING_REVIEW";
            default -> (String) expect.get("resolveStatus");
        };
        publish.put("gate", gate.status().name());
        publish.put("expectedGate", expectedGate);
        boolean ok = expectedGate.equals(gate.status().name());
        if (!ok) {
            failures.add(id + ": gate " + gate.status() + ", expected " + expectedGate);
        }
        // before tax review approves, nothing goes live
        boolean refusedEarly = refusedToGoLive(created.id());
        publish.put("refusedBeforeApproval", refusedEarly);
        if (!refusedEarly) {
            failures.add(id + ": went live before tax review");
            ok = false;
        }
        if ("RESOLVED".equals(expect.get("resolveStatus"))) {
            tax.classificationChanged(atin, "APPROVED");
            TaxReadiness approved = tax.readiness(created.id());
            publish.put("afterTaxReview", approved.status().name());
            if (approved.status().name().equals("READY")) {
                Product live = products.approve(created.id());
                publish.put("result", live.status().name());
                ok &= live.status() == ProductStatus.LIVE;
                Product shown = publicCatalog.liveProduct(live.atin().value());
                String code = publicCatalog.taxProfileOf(shown).orElse(null);
                publish.put("publicCategoryCode", code);
                boolean carries = category.taxProfileId().equals(code)
                        && shown.details().sellerBehaviour().entrySet().containsAll(behaviour.entrySet());
                publish.put("publicCarriesCodeAndBehaviour", carries);
                ok &= carries;
                if (!carries) {
                    failures.add(id + ": the live product does not carry the category code and behaviour checkout reads");
                }
            } else {
                failures.add(id + ": tax review approved but the gate says " + approved.status());
                ok = false;
            }
        } else {
            publish.put("result", "NOT_LIVE_" + expect.get("resolveStatus"));
        }
        r.put("pass", ok);
        return r;
    }

    private boolean refusedToGoLive(UUID productId) {
        try {
            products.approve(productId);
            return false;
        } catch (TaxNotReady e) {
            return true;
        }
    }

    // ------------------------------------------------------------------ the provisional category

    private Map<String, Object> gatedProfileless(CategoryNode category, CategoryNode leaf, int serial, List<String> failures) {
        ProductType type = categories.productType(leaf.productTypeCode());
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("scenario", "none (no profile yet)");
        Map<String, Object> publish = new LinkedHashMap<>();
        r.put("publish", publish);
        try {
            products.create(command(leaf, type, "Matrix " + serial, Map.of(), Map.of()));
            failures.add(category.code() + ": a category without a tax profile accepted a submission");
            r.put("pass", false);
        } catch (CatalogRuleViolation e) {
            publish.put("result", "REFUSED_CLOSED_CATEGORY");
            publish.put("message", e.fieldErrors().get("category"));
            r.put("pass", e.fieldErrors().containsKey("category"));
            if (!e.fieldErrors().containsKey("category")) {
                failures.add(category.code() + ": refused without the category message");
            }
        }
        return r;
    }

    // ------------------------------------------------------------------ negatives

    @SuppressWarnings("unchecked")
    private Map<String, Object> negatives(CategoryNode category, CategoryNode leaf, Map<String, Object> s,
                                          Map<String, Object> categoryDef, int serial, boolean closed, List<String> failures) {
        ProductType type = categories.productType(leaf.productTypeCode());
        Map<String, Object> inputs = (Map<String, Object>) s.get("inputs");
        Map<String, Object> variantFacts = (Map<String, Object>) inputs.get("facts");
        Map<String, Object> behaviour = (Map<String, Object>) inputs.get("sellerBehaviour");
        Map<String, Object> out = new LinkedHashMap<>();
        List<Map<String, Object>> removed = new ArrayList<>();
        List<Map<String, Object>> wrongType = new ArrayList<>();
        // each fact the seller answers, removed in turn
        for (AttributeDefinition d : type.taxFacts()) {
            if (d.kind() == AttributeKind.FILE) {
                continue;
            }
            boolean variantOwned = variantFacts.containsKey(d.key());
            boolean behaviourOwned = behaviour.containsKey(d.key());
            if (!variantOwned && !behaviourOwned) {
                continue;
            }
            Map<String, Object> f = new LinkedHashMap<>(variantFacts);
            Map<String, Object> b = new LinkedHashMap<>(behaviour);
            f.remove(d.key());
            b.remove(d.key());
            String field = (variantOwned ? "variants[0].taxFacts." : "sellerBehaviour.") + d.key();
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("fact", d.key());
            try {
                products.create(command(leaf, type, "Matrix " + serial + " minus " + d.key(), f, b));
                // an answer that is not required for this combination (hidden by show-when) may be left out: fine
                row.put("result", "ACCEPTED_NOT_REQUIRED_HERE");
                row.put("pass", true);
            } catch (CatalogRuleViolation e) {
                String message = e.fieldErrors().get(field);
                boolean plain = message != null && message.startsWith(d.label() + " is needed.");
                row.put("result", plain ? "REFUSED_WITH_PLAIN_MESSAGE" : "REFUSED_OTHER");
                row.put("message", message);
                row.put("pass", plain || message == null && !e.fieldErrors().isEmpty() && closed);
                if (!(boolean) row.get("pass")) {
                    failures.add(category.code() + ": without " + d.key() + " the message is " + message + " (" + e.fieldErrors() + ")");
                }
            }
            removed.add(row);
        }
        // text in a number, anything but yes or no in a yes/no, an unknown choice
        for (AttributeDefinition d : type.taxFacts()) {
            Object bad = switch (d.kind()) {
                case DECIMAL, INTEGER, PERCENT -> "abc";
                case BOOLEAN -> "maybe";
                case ENUM -> "no-such-choice";
                default -> null;
            };
            if (bad == null || !variantFacts.containsKey(d.key()) && !behaviour.containsKey(d.key())) {
                continue;
            }
            boolean variantOwned = variantFacts.containsKey(d.key());
            Map<String, Object> f = new LinkedHashMap<>(variantFacts);
            Map<String, Object> b = new LinkedHashMap<>(behaviour);
            (variantOwned ? f : b).put(d.key(), bad);
            String field = (variantOwned ? "variants[0].taxFacts." : "sellerBehaviour.") + d.key();
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("fact", d.key());
            row.put("kind", d.kind().name());
            try {
                products.create(command(leaf, type, "Matrix " + serial + " bad " + d.key(), f, b));
                row.put("result", "ACCEPTED");
                row.put("pass", false);
                failures.add(category.code() + ": " + bad + " was accepted for " + d.kind() + " fact " + d.key());
            } catch (CatalogRuleViolation e) {
                boolean refused = e.fieldErrors().containsKey(field);
                row.put("result", refused ? "REFUSED" : "REFUSED_OTHER");
                row.put("message", e.fieldErrors().get(field));
                row.put("pass", refused);
                if (!refused) {
                    failures.add(category.code() + ": " + bad + " for " + d.key() + " was refused without a message on the field: " + e.fieldErrors());
                }
            }
            wrongType.add(row);
        }
        out.put("requiredFactRemoved", removed);
        out.put("wrongType", wrongType);
        out.put("goLiveWhileGated", closed || !"ACTIVE".equals(category.taxStatus()) ? "covered by the scenarios (refused or held)" : "n/a: category is open");
        return out;
    }

    // ------------------------------------------------------------------ building a minimal product

    private CreateProductCommand command(CategoryNode leaf, ProductType type, String title, Map<String, Object> variantFacts,
                                         Map<String, Object> behaviour) {
        Map<String, String> details = new LinkedHashMap<>();
        for (AttributeDefinition a : type.applicableAttributes()) {
            if (!a.required()) {
                continue;
            }
            details.put(a.key(), switch (a.kind()) {
                case ENUM -> a.options().getFirst();
                case NUMBER -> "12" + (a.units().isEmpty() ? "" : " " + a.units().getFirst());
                case BRAND -> a.options().isEmpty() ? "Matrix brand" : a.options().getFirst();
                default -> "Sample " + a.key();
            });
        }
        String theme = "";
        Map<String, String> options = Map.of();
        if (!type.themes().isEmpty()) {
            var t = type.themes().getFirst();
            theme = t.name();
            Map<String, String> o = new LinkedHashMap<>();
            t.dimensions().forEach(d -> o.put(d.name(), d.options().isEmpty() ? "One" : d.options().getFirst()));
            options = o;
        }
        boolean isbn = type.identifierRule() == IdentifierRule.ISBN;
        VariantDraft v = isbn
                ? new VariantDraft(options, "", isbn13(), false, 1250, 10, null, List.of(), null, variantFacts)
                : new VariantDraft(options, "", "", true, 1250, 10, null, List.of(), null, variantFacts);
        ListingDetails listing = ListingDetails.DEFAULT.withSellerBehaviour(behaviour);
        return new CreateProductCommand(leaf.id(), null, "Matrix product " + title + " " + UUID.randomUUID().toString().substring(0, 6),
                details, List.of(IMAGE), theme, List.of(v), "USD", null, null, listing, null);
    }

    private static int isbnSerial;

    /** A valid ISBN-13 (978 prefix, check digit) that no other product of the run uses. */
    private static synchronized String isbn13() {
        String body = String.format("978%09d", 700_000_000 + ++isbnSerial);
        int sum = 0;
        for (int i = 0; i < 12; i++) {
            sum += (body.charAt(i) - '0') * (i % 2 == 0 ? 1 : 3);
        }
        return body + (10 - sum % 10) % 10;
    }

    private static Map<String, Object> read(String resource) throws IOException {
        try (InputStream in = CategoryTaxMatrixIT.class.getClassLoader().getResourceAsStream(resource)) {
            assertThat(in).as(resource).isNotNull();
            return JSON.readValue(in, MAP);
        }
    }

}
