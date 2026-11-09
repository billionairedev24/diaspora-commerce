package com.afrotransact.catalog.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import com.afrotransact.catalog.adapter.out.tax.LocalDatasetTaxResolver;
import com.afrotransact.catalog.adapter.out.taxonomy.ClasspathTaxonomySource;
import com.afrotransact.catalog.domain.CategoryLevel;
import com.afrotransact.catalog.domain.CategoryNode;
import com.afrotransact.catalog.domain.ProductType;
import com.afrotransact.catalog.domain.tax.TaxDecision;
import com.afrotransact.catalog.domain.tax.TaxStatus;
import com.afrotransact.catalog.domain.tax.VariantTax;
import com.afrotransact.catalog.domain.taxonomy.imports.TaxonomyPlanner;
import com.afrotransact.catalog.domain.taxonomy.imports.TaxonomyPlanner.PlannedNode;
import com.afrotransact.catalog.port.TaxResolver;
import org.junit.jupiter.api.Test;

class TaxGateEvaluatorTest {

    private static final ClasspathTaxonomySource SOURCE = new ClasspathTaxonomySource();
    private static final TaxonomyPlanner.Plan PLAN = TaxonomyPlanner.plan(SOURCE.bundled(), SOURCE.schemas());
    private static final TaxResolver FAKE = new LocalDatasetTaxResolver(SOURCE);

    private static ProductType type(String code) {
        return PLAN.types().stream().filter(t -> t.code().equals(code)).findFirst().orElseThrow();
    }

    private static CategoryNode category(String code) {
        PlannedNode n = PLAN.nodes().stream().filter(x -> x.code().equals(code)).findFirst().orElseThrow();
        return new CategoryNode(n.id(), n.parentId(), n.code(), n.name(), CategoryLevel.CATEGORY, null, n.path(), 0, true, 0,
                null, null).withTax(n.taxProfileId(), n.taxStatus());
    }

    private static List<VariantTax> none(int n) {
        List<VariantTax> out = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            out.add(null);
        }
        return out;
    }

    @Test
    void missingAnswersRefuseTheSubmissionPerVariantWithPlainMessages() {
        TaxGateEvaluator gate = new TaxGateEvaluator(FAKE, true);
        var e = gate.evaluate(type("packaged-snacks"), category("packaged-snacks"),
                List.of(Map.of("is_candy", false), Map.of("is_candy", true)), none(2), Map.of());
        assertThat(e.errors()).containsKeys("variants[0].taxFacts.net_weight_oz",
                "variants[0].taxFacts.servings_per_package", "variants[0].taxFacts.individual_serving")
                .doesNotContainKey("variants[1].taxFacts.net_weight_oz");
        assertThat(e.variants().get(0).status()).isEqualTo(TaxStatus.MISSING_FACTS);
        assertThat(e.variants().get(1).status()).isEqualTo(TaxStatus.PENDING_REVIEW);
    }

    @Test
    void aResolvedVariantWaitsForTaxReviewWhenReviewIsRequiredAndIsReadyWhenNot() {
        var facts = List.<Map<String, Object>>of(Map.of("is_candy", false, "net_weight_oz", 8, "servings_per_package", 4,
                "individual_serving", false));
        var review = new TaxGateEvaluator(FAKE, true).evaluate(type("packaged-snacks"), category("packaged-snacks"), facts,
                none(1), Map.of());
        assertThat(review.errors()).isEmpty();
        assertThat(review.variants().getFirst().status()).isEqualTo(TaxStatus.PENDING_REVIEW);
        assertThat(review.variants().getFirst().decision().internalTaxCode()).startsWith("AT.PACKAGED_SNACKS.");
        var direct = new TaxGateEvaluator(FAKE, false).evaluate(type("packaged-snacks"), category("packaged-snacks"), facts,
                none(1), Map.of());
        assertThat(direct.allReady()).isTrue();
        assertThat(direct.variants().getFirst().decision().outcome()).isEqualTo("EXEMPT");
    }

    @Test
    void theOutcomeFollowsTheFactsNeverADefault() {
        TaxGateEvaluator gate = new TaxGateEvaluator(FAKE, false);
        var small = gate.evaluate(type("packaged-snacks"), category("packaged-snacks"), List.of(Map.of("is_candy", false,
                "net_weight_oz", 1, "servings_per_package", 1, "individual_serving", true)), none(1), Map.of());
        assertThat(small.variants().getFirst().decision().outcome()).isEqualTo("TAXABLE");
    }

    @Test
    void sellerBehaviourFactsComeFromTheListingAndHiddenOnesAreDropped() {
        TaxGateEvaluator gate = new TaxGateEvaluator(FAKE, false);
        var e = gate.evaluate(type("bakery-products"), category("bakery-products"), List.of(Map.of()), none(1),
                Map.of("sold_heated", false, "heated_by_seller", true, "utensils_provided", false));
        assertThat(e.errors()).isEmpty();
        assertThat(e.behaviour()).containsOnlyKeys("sold_heated", "utensils_provided");
        var missing = gate.evaluate(type("bakery-products"), category("bakery-products"), List.of(Map.of()), none(1),
                Map.of("sold_heated", true));
        assertThat(missing.errors()).containsKeys("sellerBehaviour.heated_by_seller", "sellerBehaviour.utensils_provided");
    }

    @Test
    void aClosedCategoryRefusesTheSubmissionWithItsPlainMessage() {
        TaxGateEvaluator gate = new TaxGateEvaluator(FAKE, false);
        var e = gate.evaluate(type("gift-baskets-bundles"), category("gift-baskets-bundles"),
                List.of(Map.of("bundle_line_kind", "UNITEMIZED_MIXED_BUNDLE")), none(1), Map.of());
        assertThat(e.errors().get("category")).contains("Gift baskets and bundles cannot be published yet");
        assertThat(e.variants().getFirst().status()).isEqualTo(TaxStatus.BLOCKED);
        var fabric = gate.evaluate(type("fabrics-textiles"), category("fabrics-textiles"), List.of(Map.of()), none(1), Map.of());
        assertThat(fabric.errors().get("category")).contains("not open for publishing yet");
        assertThat(fabric.variants().getFirst().status()).isEqualTo(TaxStatus.NEEDS_APPROVAL);
    }

    @Test
    void aRefineCodeCategoryMayBeSubmittedButNeverGoesLive() {
        var e = new TaxGateEvaluator(FAKE, false).evaluate(type("toys-games"), category("toys-games"), List.of(Map.of()),
                none(1), Map.of());
        assertThat(e.errors()).isEmpty();
        assertThat(e.variants().getFirst().status()).isEqualTo(TaxStatus.REFINE_CODE);
    }

    @Test
    void anApprovedVariantStaysApprovedWhileFactsAndDecisionAreUnchanged() {
        TaxGateEvaluator gate = new TaxGateEvaluator(FAKE, true);
        Map<String, Object> facts = Map.of("is_candy", false, "net_weight_oz", 8, "servings_per_package", 4,
                "individual_serving", false);
        VariantTax first = gate.evaluate(type("packaged-snacks"), category("packaged-snacks"), List.of(facts), none(1), Map.of())
                .variants().getFirst();
        VariantTax approved = first.withStatus(TaxStatus.READY);
        var same = gate.evaluate(type("packaged-snacks"), category("packaged-snacks"), List.of(first.facts()),
                List.of(approved), Map.of());
        assertThat(same.variants().getFirst().status()).isEqualTo(TaxStatus.READY);
        var changed = gate.evaluate(type("packaged-snacks"), category("packaged-snacks"),
                List.of(Map.of("is_candy", false, "net_weight_oz", 1, "servings_per_package", 1, "individual_serving", true)),
                List.of(approved), Map.of());
        assertThat(changed.variants().getFirst().status()).isEqualTo(TaxStatus.PENDING_REVIEW);
        assertThat(approved.decision()).isInstanceOf(TaxDecision.class);
    }

    @Test
    void evidenceMayFollowTheSubmissionButKeepsTheVariantFromBeingReady() {
        TaxGateEvaluator gate = new TaxGateEvaluator(FAKE, false);
        var facts = List.<Map<String, Object>>of(Map.of("label_type", "DRUG_FACTS", "qualifying_label", true));
        var e = gate.evaluate(type("medicated-skincare"), category("medicated-skincare"), facts, none(1), Map.of());
        assertThat(e.errors()).doesNotContainKey("variants[0].taxFacts.label_evidence");
        assertThat(e.variants().getFirst().status()).isEqualTo(TaxStatus.MISSING_FACTS);
        assertThat(e.variants().getFirst().missing()).extracting(m -> m.factId()).contains("label_evidence");
    }
}
