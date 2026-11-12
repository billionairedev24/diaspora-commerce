package com.afrotransact.inventory.adapter.in.web;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.Reader;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;

import com.afrotransact.inventory.adapter.in.web.StockImportController.ImportResponse;
import com.afrotransact.inventory.adapter.in.web.StockImportController.RowResponse;
import com.afrotransact.inventory.domain.StockImport;
import com.afrotransact.inventory.domain.StockImportMode;
import com.afrotransact.inventory.domain.StockImportRow;
import com.afrotransact.inventory.domain.StockImportStatus;
import com.afrotransact.inventory.domain.StockRowOutcome;
import org.junit.jupiter.api.Test;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.yaml.snakeyaml.Yaml;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** S395: the stock import endpoints match contracts/openapi/inventory.yaml (routes, fields, enums). */
class StockImportContractTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final Instant NOW = Instant.parse("2026-11-12T10:00:00Z");

    @SuppressWarnings("unchecked")
    private static Map<String, Object> contract() throws IOException {
        Path file = Path.of("..", "..", "..", "contracts", "openapi", "inventory.yaml");
        try (Reader reader = Files.newBufferedReader(file)) {
            return (Map<String, Object>) new Yaml().load(reader);
        }
    }

    @SuppressWarnings("unchecked")
    private static Set<String> properties(Map<String, Object> doc, String schema) {
        Map<String, Object> components = (Map<String, Object>) doc.get("components");
        Map<String, Object> schemas = (Map<String, Object>) components.get("schemas");
        return new TreeSet<>(((Map<String, Object>) ((Map<String, Object>) schemas.get(schema)).get("properties")).keySet());
    }

    @SuppressWarnings("unchecked")
    private static List<String> enumOf(Map<String, Object> doc, String schema, String property) {
        Map<String, Object> components = (Map<String, Object>) doc.get("components");
        Map<String, Object> schemas = (Map<String, Object>) components.get("schemas");
        Map<String, Object> props = (Map<String, Object>) ((Map<String, Object>) schemas.get(schema)).get("properties");
        return (List<String>) ((Map<String, Object>) props.get(property)).get("enum");
    }

    @Test
    void everyRouteTheControllerServesIsInTheContract() throws IOException {
        @SuppressWarnings("unchecked")
        Set<String> paths = ((Map<String, Object>) contract().get("paths")).keySet();
        assertThat(paths).contains("/v1/stock/imports", "/v1/stock/imports/template", "/v1/stock/imports/{id}",
                "/v1/stock/imports/{id}/rows", "/v1/stock/imports/{id}/report", "/v1/stock/imports/{id}/start");
        Set<String> served = new TreeSet<>();
        String base = StockImportController.class.getAnnotation(RequestMapping.class).value()[0];
        for (Method m : StockImportController.class.getDeclaredMethods()) {
            GetMapping get = m.getAnnotation(GetMapping.class);
            PostMapping post = m.getAnnotation(PostMapping.class);
            if (get != null) {
                served.add(base + (get.value().length == 0 ? "" : get.value()[0]));
            }
            if (post != null) {
                served.add(base + (post.value().length == 0 ? "" : post.value()[0]));
            }
        }
        assertThat(paths).containsAll(served);
        assertThat(served).hasSize(6);
    }

    @Test
    void theImportAndRowResponsesCarryExactlyWhatTheContractDescribes() throws IOException {
        Map<String, Object> doc = contract();
        StockImport u = new StockImport(UUID.randomUUID(), "SI-000001", "org_b", "user_b", "stock.csv", "abc", UUID.randomUUID(),
                StockImportMode.DELTA, List.of("Seller SKU"), StockImportStatus.COMPLETED, 2, 1, 1, 0, 2, 1, 1, 0, NOW,
                NOW, NOW, NOW, 3);
        StockImportRow row = new StockImportRow(2, "SKU-1", "HOU-FC", null, 5, null, "VALID", List.of(), List.of("SKU-1"),
                StockRowOutcome.APPLIED, null, 10, 15);

        JsonNode importJson = JSON.valueToTree(ImportResponse.of(u));
        JsonNode rowJson = JSON.valueToTree(RowResponse.of(row));

        assertThat(keys(importJson)).isSubsetOf(properties(doc, "StockImport"));
        assertThat(keys(rowJson)).isSubsetOf(properties(doc, "StockImportRow"));
        assertThat(enumOf(doc, "StockImport", "mode")).containsExactlyInAnyOrder("ABSOLUTE", "DELTA");
        assertThat(enumOf(doc, "StockImportRow", "outcome")).containsExactlyInAnyOrder("PENDING", "APPLIED", "UNCHANGED",
                "FAILED", "SKIPPED");
        assertThat(importJson.get("mode").asString()).isEqualTo("DELTA");
        assertThat(rowJson.get("outcome").asString()).isEqualTo("APPLIED");
    }

    private static Set<String> keys(JsonNode json) {
        Set<String> out = new TreeSet<>();
        json.propertyNames().forEach(out::add);
        return out;
    }
}
