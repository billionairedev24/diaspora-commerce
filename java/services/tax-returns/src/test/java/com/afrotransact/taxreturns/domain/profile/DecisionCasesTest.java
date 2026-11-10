package com.afrotransact.taxreturns.domain.profile;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

/**
 * Every golden decision case ({@code tools/taxonomy/decision_cases.jsonl}: the 105 scenarios, every combination of every
 * category's facts, and each required fact removed in turn) through this engine's evaluator. Catalog's local resolver
 * reads the same file ({@code LocalDatasetTaxResolverGoldenTest}) and the Python reference produced it, so the three
 * implementations give identical decisions on all of it.
 */
class DecisionCasesTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final TypeReference<Map<String, Object>> MAP = new TypeReference<>() { };

    private static Map<String, Object> map;
    private static Map<String, TaxProfile> profiles;
    private static List<Map<String, Object>> cases;

    @BeforeAll
    static void load() throws IOException {
        map = TaxMapFixture.read("category_tax_map.json");
        profiles = new LinkedHashMap<>();
        TaxMapFixture.profiles(map).forEach(p -> profiles.put(p.categoryCode(), p));
        cases = new ArrayList<>();
        try (InputStream in = DecisionCasesTest.class.getResourceAsStream("/taxonomy/decision_cases.jsonl");
             BufferedReader r = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
            String line;
            while ((line = r.readLine()) != null) {
                if (!line.isBlank()) {
                    cases.add(JSON.readValue(line, MAP));
                }
            }
        }
    }

    @Test
    @SuppressWarnings("unchecked")
    void theEngineReproducesEveryGoldenCase() {
        assertThat(cases).hasSizeGreaterThan(600);
        ProfileEvaluator evaluator = new ProfileEvaluator();
        List<String> failures = new ArrayList<>();
        for (Map<String, Object> c : cases) {
            Map<String, Object> expect = (Map<String, Object>) c.get("expect");
            Resolution r = evaluator.resolve(profiles.get((String) c.get("category")),
                    (Map<String, Object>) c.get("facts"), (Map<String, Object>) c.get("sellerBehaviour"),
                    (Map<String, Object>) c.get("context"), TaxMapFixture.ruleVersion(map));
            Map<String, Object> got = new LinkedHashMap<>();
            got.put("status", r.status().name());
            got.put("outcome", r.outcome() == null ? null : r.outcome().name());
            got.put("internalTaxCode", r.internalTaxCode());
            got.put("stripeCode", r.providerCodes().get("STRIPE"));
            got.put("rowId", r.rowId());
            got.put("missingFactIds", r.missingFacts().stream().map(MissingFact::factId).sorted().toList());
            Map<String, Object> want = new LinkedHashMap<>(expect);
            if (!got.equals(want)) {
                failures.add(c.get("id") + ": got " + got + " expected " + want);
            }
        }
        assertThat(failures).as("cases the engine answers differently from the reference").isEmpty();
    }

    @Test
    void everyCategoryIsCoveredAndMissingFactsNeverCarryAnOutcome() {
        assertThat(cases.stream().map(c -> c.get("category")).distinct().count()).isEqualTo(73);
        for (Map<String, Object> c : cases) {
            @SuppressWarnings("unchecked")
            Map<String, Object> expect = (Map<String, Object>) c.get("expect");
            if ("MISSING_FACTS".equals(expect.get("status"))) {
                assertThat(expect.get("outcome")).as(String.valueOf(c.get("id"))).isNull();
                assertThat(expect.get("internalTaxCode")).isNull();
            }
        }
    }
}
