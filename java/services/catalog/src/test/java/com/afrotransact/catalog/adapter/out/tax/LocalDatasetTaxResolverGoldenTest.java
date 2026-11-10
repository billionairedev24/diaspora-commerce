package com.afrotransact.catalog.adapter.out.tax;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.afrotransact.catalog.adapter.out.taxonomy.ClasspathTaxonomySource;
import com.afrotransact.catalog.domain.tax.MissingTaxFact;
import com.afrotransact.catalog.port.TaxResolver;
import com.afrotransact.catalog.port.TaxResolver.Resolution;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

/**
 * The catalog's local fake of the resolve endpoint against the golden decision cases
 * ({@code tools/taxonomy/decision_cases.jsonl}): the 105 scenarios, every combination of every category's facts and each
 * required fact removed in turn. tax-returns' evaluator reproduces the same file ({@code DecisionCasesTest}) and the Python
 * reference wrote it, so the fake and the real engine return identical decisions on every case.
 */
class LocalDatasetTaxResolverGoldenTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final TypeReference<Map<String, Object>> MAP = new TypeReference<>() { };

    private static final List<String> CONTEXT_FACTS = List.of("exemption_window_active", "gift_card_event");

    private static List<Map<String, Object>> cases;
    private static TaxResolver resolver;

    @BeforeAll
    static void load() throws IOException {
        resolver = new LocalDatasetTaxResolver(new ClasspathTaxonomySource());
        cases = new ArrayList<>();
        try (InputStream in = LocalDatasetTaxResolverGoldenTest.class.getResourceAsStream("/taxonomy/decision_cases.jsonl");
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
    void theLocalResolverReproducesEveryGoldenCase() {
        assertThat(cases).hasSizeGreaterThan(600);
        List<String> failures = new ArrayList<>();
        for (Map<String, Object> c : cases) {
            if ("MISSING".equals(c.get("kind")) && CONTEXT_FACTS.contains(c.get("removed"))) {
                // System facts are never asked of the seller: like tax-returns' resolve endpoint, the fake supplies them
                // (the tax holiday calendar is not built, so the window is never active and the ordinary sale applies).
                continue;
            }
            Map<String, Object> expect = (Map<String, Object>) c.get("expect");
            Map<String, Object> facts = new HashMap<>((Map<String, Object>) c.get("facts"));
            // The local fake has no separate context: system facts such as exemption_window_active travel with the facts.
            facts.putAll((Map<String, Object>) c.get("context"));
            Resolution r = resolver.resolve(new TaxResolver.Request((String) c.get("category"), null, facts,
                    (Map<String, Object>) c.get("sellerBehaviour")));
            Map<String, Object> got = new LinkedHashMap<>();
            got.put("status", r.status());
            got.put("outcome", r.outcome());
            got.put("internalTaxCode", r.internalTaxCode());
            got.put("rowId", r.rowId());
            got.put("missingFactIds", r.missingFacts().stream().map(MissingTaxFact::factId).sorted().toList());
            Map<String, Object> want = new LinkedHashMap<>(expect);
            want.remove("stripeCode");
            if (!got.equals(want)) {
                failures.add(c.get("id") + ": got " + got + " expected " + want);
            }
        }
        assertThat(failures).as("cases the local resolver answers differently from the reference").isEmpty();
    }
}
