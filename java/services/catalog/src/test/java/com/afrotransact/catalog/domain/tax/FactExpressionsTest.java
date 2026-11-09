package com.afrotransact.catalog.domain.tax;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

/** The expression grammar behaves as the shared vectors say (the seller form and the reference evaluator run the same ones). */
class FactExpressionsTest {

    static final Path VECTORS = Path.of("../../../docs/engineering/fact-expression-vectors.json");

    @Test
    @SuppressWarnings("unchecked")
    void everySharedVectorHolds() throws Exception {
        Map<String, Object> doc = JsonMapper.builder().build().readValue(Files.readString(VECTORS),
                new TypeReference<Map<String, Object>>() { });
        List<Map<String, Object>> vectors = (List<Map<String, Object>>) doc.get("vectors");
        assertThat(vectors).hasSizeGreaterThan(25);
        for (Map<String, Object> v : vectors) {
            boolean got = FactExpressions.evaluate((Map<String, Object>) v.get("expr"), (Map<String, Object>) v.get("facts"));
            assertThat(got).as(String.valueOf(v.get("name"))).isEqualTo(v.get("expect"));
        }
    }

    @Test
    void collectsTheFactsAnExpressionReadsFrom() {
        Map<String, Object> expr = Map.of("all", List.of(Map.of("op", "isTrue", "fact", "a"),
                Map.of("not", Map.of("op", "gt", "fact", "b", "value", 1))));
        assertThat(FactExpressions.factsOf(expr)).containsExactlyInAnyOrder("a", "b");
        assertThat(FactExpressions.factsOf(null)).isEmpty();
    }
}
