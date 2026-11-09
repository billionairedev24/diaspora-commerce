package com.afrotransact.catalog.domain.taxonomy.imports;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

import com.afrotransact.catalog.adapter.out.tax.LocalDatasetTaxResolver;
import com.afrotransact.catalog.adapter.out.taxonomy.ClasspathTaxonomySource;
import com.afrotransact.catalog.domain.AttributeDefinition;
import com.afrotransact.catalog.domain.AttributeKind;
import com.afrotransact.catalog.domain.CategoryLevel;
import com.afrotransact.catalog.domain.ProductType;
import com.afrotransact.catalog.domain.tax.FactExpressions;
import com.afrotransact.catalog.domain.tax.MissingTaxFact;
import com.afrotransact.catalog.domain.tax.TaxFactRules;
import com.afrotransact.catalog.domain.taxonomy.imports.TaxonomyDataset.Category;
import com.afrotransact.catalog.domain.taxonomy.imports.TaxonomyPlanner.Plan;
import com.afrotransact.catalog.domain.taxonomy.imports.TaxonomyPlanner.PlannedNode;
import com.afrotransact.catalog.port.TaxResolver;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;

/**
 * Generated from {@code tools/taxonomy/category_tax_map.json}: for every one of the 73 categories a valid minimal
 * product is built from the dataset's facts and passes validation, and removing each required fact is refused with the
 * fact's plain message. The dataset's own decision tables agree (the local fake resolves what the form accepts).
 */
class CategoryMatrixTest {

    private static final ClasspathTaxonomySource SOURCE = new ClasspathTaxonomySource();
    private static final TaxonomyDataset DATASET = SOURCE.bundled();
    private static final Plan PLAN = TaxonomyPlanner.plan(DATASET, SOURCE.schemas());
    private static final LocalDatasetTaxResolver RESOLVER = new LocalDatasetTaxResolver(SOURCE);

    /** A satisfiable set of answers for the questions of a category: one value per question, hidden ones dropped. */
    static Map<String, Object> minimalAnswers(List<AttributeDefinition> defs) {
        Map<String, Object> raw = new LinkedHashMap<>();
        for (AttributeDefinition d : defs) {
            raw.put(d.key(), switch (d.kind()) {
                case BOOLEAN -> Boolean.FALSE;
                case DECIMAL -> 8.5;
                case INTEGER -> 2;
                case PERCENT -> 50;
                case ENUM -> d.options().getFirst();
                case FILE -> "evidence-file-id";
                default -> throw new IllegalStateException("not a tax kind " + d.kind());
            });
        }
        return TaxFactRules.check(defs, raw).facts();
    }

    @Test
    void theTreeHasThirteenDepartmentsSeventyThreeCategoriesAndTheProvisionalFabrics() {
        assertThat(PLAN.count(CategoryLevel.DEPARTMENT)).isEqualTo(13);
        assertThat(PLAN.count(CategoryLevel.CATEGORY)).isEqualTo(74);
        assertThat(DATASET.categories()).hasSize(73);
        PlannedNode fabrics = PLAN.nodes().stream().filter(n -> n.code().equals("fabrics-textiles")).findFirst().orElseThrow();
        assertThat(fabrics.taxStatus()).isEqualTo("NEEDS_APPROVAL");
        assertThat(fabrics.taxProfileId()).isNull();
        assertThat(fabrics.path()).isEqualTo("Fashion & Accessories › Fabrics & textiles");
        assertThat(PLAN.nodes().stream().map(PlannedNode::code)).doesNotHaveDuplicates();
        assertThat(PLAN.nodes().stream().map(PlannedNode::path)).doesNotHaveDuplicates();
        assertThat(PLAN.nodes().stream().map(PlannedNode::id)).doesNotHaveDuplicates();
    }

    @Test
    void everyCategoryHasAtLeastOneProductTypeLeafAndSizedFashionKeepsItsTypes() {
        Set<String> typeCodes = new HashSet<>();
        PLAN.types().forEach(t -> typeCodes.add(t.code()));
        for (PlannedNode category : PLAN.nodes().stream().filter(n -> n.level() == CategoryLevel.CATEGORY).toList()) {
            List<PlannedNode> leaves = PLAN.nodes().stream()
                    .filter(n -> n.level() == CategoryLevel.PRODUCT_TYPE && category.id().equals(n.parentId())).toList();
            assertThat(leaves).as(category.code()).isNotEmpty();
            leaves.forEach(l -> assertThat(typeCodes).contains(l.productTypeCode()));
        }
        assertThat(PLAN.types().stream().filter(t -> t.sizing().isPresent()).map(ProductType::code))
                .containsExactlyInAnyOrder("dress", "top", "menwear", "footwear", "kids");
        ProductType dress = PLAN.types().stream().filter(t -> t.code().equals("dress")).findFirst().orElseThrow();
        assertThat(dress.theme("Size x Color").orElseThrow().dimension("Size").allowCustomValues()).isTrue();
    }

    @Test
    void theStatusCountsAreThoseOfTheDataset() {
        Map<String, Long> counts = new LinkedHashMap<>();
        PLAN.nodes().stream().filter(n -> n.level() == CategoryLevel.CATEGORY && n.taxProfileId() != null)
                .forEach(n -> counts.merge(n.taxStatus(), 1L, Long::sum));
        assertThat(counts).containsEntry("ACTIVE", 57L).containsEntry("NEEDS_APPROVAL", 7L)
                .containsEntry("REFINE_CODE", 6L).containsEntry("CONFLICT_OPEN", 2L).containsEntry("BLOCKED_BUNDLE", 1L);
    }

    @Test
    void planningTwiceGivesTheSameTree() {
        Plan again = TaxonomyPlanner.plan(DATASET, SOURCE.schemas());
        assertThat(again.nodes()).isEqualTo(PLAN.nodes());
        assertThat(again.types()).isEqualTo(PLAN.types());
    }

    @TestFactory
    Stream<DynamicTest> everyCategoryAcceptsAValidMinimalProductAndRefusesEachMissingRequiredFact() {
        return DATASET.categories().stream().map(c -> DynamicTest.dynamicTest(c.code(), () -> check(c)));
    }

    private static void check(Category c) {
        List<AttributeDefinition> defs = TaxFactSchema.forCategory(DATASET, c);
        Map<String, Object> answers = minimalAnswers(defs);
        TaxFactRules.Result valid = TaxFactRules.check(defs, answers);
        assertThat(valid.problems()).as(c.code() + " problems").isEmpty();
        assertThat(valid.missing()).as(c.code() + " missing").isEmpty();

        // Every visible, required question: removing its answer is refused with that question's own message.
        for (AttributeDefinition d : defs) {
            boolean required = d.required() && answers.containsKey(d.key())
                    && FactExpressions.evaluate(d.requiredWhen(), answers);
            if (!required) {
                continue;
            }
            Map<String, Object> without = new LinkedHashMap<>(answers);
            without.remove(d.key());
            TaxFactRules.Result r = TaxFactRules.check(defs, without);
            assertThat(r.missing()).as(c.code() + " without " + d.key()).extracting(MissingTaxFact::factId)
                    .contains(d.key());
            MissingTaxFact m = r.missing().stream().filter(x -> x.factId().equals(d.key())).findFirst().orElseThrow();
            assertThat(m.message()).startsWith(d.kind() == AttributeKind.FILE ? "Please upload: " + d.label()
                    : d.label() + " is needed.");
        }

        // The dataset's decision table decides what the form accepted: never "missing facts" for a seller-answerable
        // category (staff-set facts are the only exception), and the gate is the category's status.
        Map<String, Object> variant = new LinkedHashMap<>();
        Map<String, Object> behaviour = new LinkedHashMap<>();
        TaxFactRules.split(defs, answers).get(0).forEach(variant::put);
        TaxFactRules.split(defs, answers).get(1).forEach(behaviour::put);
        TaxResolver.Resolution res = RESOLVER.resolve(new TaxResolver.Request(c.code(), c.code(), variant, behaviour));
        boolean staffFact = c.facts().stream()
                .anyMatch(f -> Set.of("STAFF", "CONTEXT").contains(DATASET.fact(f.fact()).owner())
                        && !f.fact().equals("exemption_window_active"));
        if (staffFact) {
            assertThat(res.status()).isIn("MISSING_FACTS", c.status());
        } else {
            String expected = switch (c.status()) {
                case "ACTIVE" -> "RESOLVED";
                case "BLOCKED_BUNDLE" -> "BLOCKED";
                default -> c.status();
            };
            assertThat(res.status()).as(c.code() + " resolve").isIn(expected, "BLOCKED");
            assertThat(res.missingFacts()).isEmpty();
        }
        List<String> sellerFacts = new ArrayList<>();
        defs.forEach(d -> sellerFacts.add(d.key()));
        assertThat(sellerFacts).doesNotContain("exemption_confirmed", "exemption_window_active", "gift_card_event");
    }
}
