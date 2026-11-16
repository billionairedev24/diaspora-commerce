package com.afrotransact.ai.adapter.in.web;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.Reader;
import java.lang.reflect.RecordComponent;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;

/**
 * Contract first: contracts/openapi/ai-gateway.yaml describes the spend-cap routes and bodies and the controller must carry
 * exactly those fields (every field of a response is in the contract, every required one is carried).
 */
class AiSpendCapContractTest {

    private static final Map<String, Object> CONTRACT = load();

    @SuppressWarnings("unchecked")
    private static Map<String, Object> load() {
        Path file = Path.of("..", "..", "..", "contracts", "openapi", "ai-gateway.yaml");
        try (Reader reader = Files.newBufferedReader(file)) {
            return (Map<String, Object>) new Yaml().load(reader);
        } catch (IOException ex) {
            throw new IllegalStateException("Cannot read " + file.toAbsolutePath(), ex);
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> schema(String name) {
        Map<String, Object> components = (Map<String, Object>) CONTRACT.get("components");
        return (Map<String, Object>) ((Map<String, Object>) components.get("schemas")).get(name);
    }

    @SuppressWarnings("unchecked")
    private static Set<String> properties(String name) {
        return new TreeSet<>(((Map<String, Object>) schema(name).get("properties")).keySet());
    }

    @SuppressWarnings("unchecked")
    private static Set<String> required(String name) {
        return new TreeSet<>((List<String>) schema(name).get("required"));
    }

    private static Set<String> fields(Class<?> record) {
        return new TreeSet<>(Arrays.stream(record.getRecordComponents()).map(RecordComponent::getName).toList());
    }

    @Test
    void theCapResponseCarriesExactlyTheContractsFields() {
        assertThat(fields(AiSpendCapController.SpendCapResponse.class)).isEqualTo(properties("AiSpendCap"));
        assertThat(fields(AiSpendCapController.SpendCapResponse.class)).containsAll(required("AiSpendCap"));
    }

    @Test
    void aChangeCarriesExactlyTheContractsFields() {
        assertThat(fields(AiSpendCapController.ChangeResponse.class)).isEqualTo(properties("AiSpendCapChange"));
        assertThat(fields(AiSpendCapController.ChangeResponse.class)).containsAll(required("AiSpendCapChange"));
    }

    @Test
    void theUpdateBodyIsExactlyTheContractsFields() {
        assertThat(fields(AiSpendCapController.UpdateRequest.class)).isEqualTo(properties("AiSpendCapUpdate"));
        assertThat(required("AiSpendCapUpdate")).containsExactlyInAnyOrder("capMicros", "reason");
    }

    @Test
    @SuppressWarnings("unchecked")
    void theRoutesAreInTheContractWithTheRightGates() {
        Map<String, Object> paths = (Map<String, Object>) CONTRACT.get("paths");
        Map<String, Object> cap = (Map<String, Object>) paths.get("/v1/ai/spend-cap");
        assertThat(cap.keySet()).containsExactlyInAnyOrder("get", "put");
        Map<String, Object> put = (Map<String, Object>) cap.get("put");
        assertThat(((Map<String, Object>) put.get("responses")).keySet()).contains("200", "403", "412", "422", "428");
        assertThat((List<Map<String, Object>>) put.get("parameters")).anySatisfy(p -> {
            assertThat(p.get("name")).isEqualTo("If-Match");
            assertThat(p.get("required")).isEqualTo(true);
        });
        assertThat(String.valueOf(put.get("description"))).contains("ai:cap.manage");
    }
}
