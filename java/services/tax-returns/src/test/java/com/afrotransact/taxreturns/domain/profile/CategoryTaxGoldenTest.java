package com.afrotransact.taxreturns.domain.profile;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;

/**
 * The 105 Appendix A scenarios (tools/taxonomy/scenarios.json) through the Java evaluator, plus the validator's
 * exhaustive property: for every combination of a category's facts exactly one decision row matches the required facts.
 * The fixtures are read from the repository (see the pom's testResources), so CI sees the same files as the Python
 * validator ({@code make taxonomy-check}).
 */
class CategoryTaxGoldenTest {

    private static Map<String, Object> map;
    private static Map<String, TaxProfile> profiles;
    private static List<Map<String, Object>> scenarios;
    private final ProfileEvaluator evaluator = new ProfileEvaluator();

    @BeforeAll
    @SuppressWarnings("unchecked")
    static void load() {
        map = TaxMapFixture.read("category_tax_map.json");
        profiles = new LinkedHashMap<>();
        TaxMapFixture.profiles(map).forEach(p -> profiles.put(p.categoryCode(), p));
        scenarios = (List<Map<String, Object>>) TaxMapFixture.read("scenarios.json").get("scenarios");
    }

    @Test
    void theDatasetHasThePromisedShape() {
        assertThat(profiles).hasSize(73);
        assertThat(scenarios).hasSize(105);
        assertThat(profiles.values().stream().mapToInt(p -> p.rows().size()).sum()).isEqualTo(105);
        Map<ProfileStatus, Long> byStatus = profiles.values().stream()
                .collect(Collectors.groupingBy(TaxProfile::status, Collectors.counting()));
        assertThat(byStatus).containsEntry(ProfileStatus.ACTIVE, 57L).containsEntry(ProfileStatus.NEEDS_APPROVAL, 7L)
                .containsEntry(ProfileStatus.REFINE_CODE, 6L).containsEntry(ProfileStatus.CONFLICT_OPEN, 2L)
                .containsEntry(ProfileStatus.BLOCKED_BUNDLE, 1L);
        assertThat(profiles.values().stream().flatMap(p -> p.rows().stream()).map(DecisionRow::internalTaxCode)
                .distinct().count()).isEqualTo(105);
    }

    @TestFactory
    @SuppressWarnings("unchecked")
    List<DynamicTest> everyGoldenScenarioResolvesAsTheDocumentSays() {
        List<DynamicTest> tests = new ArrayList<>();
        for (Map<String, Object> s : scenarios) {
            int n = ((Number) s.get("scenario")).intValue();
            tests.add(DynamicTest.dynamicTest("scenario " + n + " " + s.get("condition"), () -> {
                Map<String, Object> inputs = (Map<String, Object>) s.get("inputs");
                Map<String, Object> expect = (Map<String, Object>) s.get("expect");
                Resolution r = evaluator.resolve(profiles.get((String) s.get("categoryCode")),
                        (Map<String, Object>) inputs.get("facts"), (Map<String, Object>) inputs.get("sellerBehaviour"),
                        (Map<String, Object>) inputs.get("context"), TaxMapFixture.ruleVersion(map));
                assertThat(r.outcome().name()).isEqualTo(expect.get("outcome"));
                assertThat(r.internalTaxCode()).isEqualTo(expect.get("internalTaxCode"));
                assertThat(r.providerCodes().get("STRIPE")).isEqualTo(expect.get("stripeCode"));
                assertThat(r.status().name()).isEqualTo(expect.get("resolveStatus"));
                assertThat(r.rowId()).isEqualTo(s.get("rowId"));
                assertThat(r.ruleVersion()).isEqualTo(TaxMapFixture.ruleVersion(map));
            }));
        }
        return tests;
    }

    @Test
    void forEveryCombinationOfFactsExactlyOneRowMatches() {
        for (TaxProfile p : profiles.values()) {
            List<List<Object>> domains = new ArrayList<>();
            for (ProfileFact f : p.facts()) {
                domains.add(domain(f));
            }
            Map<String, Object> vals = new LinkedHashMap<>();
            walk(p, domains, 0, vals);
        }
    }

    private void walk(TaxProfile p, List<List<Object>> domains, int index, Map<String, Object> vals) {
        if (index == domains.size()) {
            Map<String, Object> used = new LinkedHashMap<>();
            vals.forEach((k, v) -> {
                if (evaluator.requiredIds(p, vals).contains(k)) {
                    used.put(k, v);
                }
            });
            long n = p.rows().stream().filter(r -> r.matches(used)).count();
            assertThat(n).as("%s: rows matching %s", p.categoryCode(), used).isEqualTo(1L);
            return;
        }
        ProfileFact f = p.facts().get(index);
        for (Object v : domains.get(index)) {
            vals.put(f.id(), v);
            walk(p, domains, index + 1, vals);
        }
        vals.remove(f.id());
    }

    private static List<Object> domain(ProfileFact f) {
        return switch (f.definition().kind()) {
            case DECIMAL -> List.of(0.5, 2.4, 2.5, 9.0);
            case INTEGER -> List.of(0, 1, 2, 5);
            case PERCENT -> List.of(0, 10, 50, 50.5, 100);
            case FILE -> List.of("evidence://x");
            case BOOLEAN -> List.of(true, false);
            case CHOICE -> new ArrayList<Object>(f.allowedChoices());
        };
    }
}
