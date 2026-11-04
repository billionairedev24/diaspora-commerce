package com.afrotransact.payments.it;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.Reader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;
import tools.jackson.databind.JsonNode;

/**
 * The referral operations the service serves are the ones contracts/openapi/payments.yaml documents (contract first):
 * same method, path, operationId, and every request / response property.
 */
class ReferralContractIT extends PaymentsIntegrationTest {

    private static final Path CONTRACT = Path.of("../../../contracts/openapi/payments.yaml");
    private static final List<String> PREFIXES = List.of("/v1/referrals", "/v1/referral-settings");

    @SuppressWarnings("unchecked")
    private static Map<String, Object> contract() throws IOException {
        try (Reader in = Files.newBufferedReader(CONTRACT)) {
            return (Map<String, Object>) new Yaml().load(in);
        }
    }

    @Test
    @SuppressWarnings("unchecked")
    void everyServedOperationIsDocumentedWithTheSameOperationIdAndSuccessStatus() throws IOException {
        JsonNode served = ok(send("GET", "/v3/api-docs", null, null));
        Map<String, String> servedOperations = new TreeMap<>();
        served.path("paths").propertyNames().forEach(path -> {
            if (PREFIXES.stream().anyMatch(path::startsWith)) {
                served.path("paths").path(path).propertyNames().forEach(method -> servedOperations.put(
                        method.toUpperCase() + " " + path, served.path("paths").path(path).path(method)
                                .path("operationId").asString()));
            }
        });
        Map<String, String> documented = new TreeMap<>();
        Map<String, Map<String, Object>> paths = (Map<String, Map<String, Object>>) contract().get("paths");
        paths.forEach((path, item) -> {
            if (PREFIXES.stream().anyMatch(path::startsWith)) {
                item.forEach((method, op) -> documented.put(method.toUpperCase() + " " + path,
                        (String) ((Map<String, Object>) op).get("operationId")));
            }
        });
        assertThat(servedOperations).hasSize(10).isEqualTo(documented);
    }

    @Test
    @SuppressWarnings("unchecked")
    void documentedSchemasCarryEveryPropertyTheServiceSends() throws IOException {
        Map<String, Map<String, Object>> documented = (Map<String, Map<String, Object>>)
                ((Map<String, Object>) contract().get("components")).get("schemas");
        JsonNode served = ok(send("GET", "/v3/api-docs", null, null)).path("components").path("schemas");
        List<String> problems = new ArrayList<>();
        for (String name : List.of("ReferralOverview", "ReferralProgramTerms", "ReferralRewardTerms", "ReferralEarnings",
                "MyReferral", "MyReferralReward", "Referral", "ReferralRewardLine", "ReferralSummary",
                "ReferralDecisionRequest", "ReferralSettings", "ReferralRewardSetting", "ReferralSettingsUpdate",
                "ReferralSettingsChange")) {
            Map<String, Object> schema = documented.get(name);
            if (schema == null) {
                problems.add(name + " is not in the contract");
                continue;
            }
            Map<String, Object> properties = (Map<String, Object>) schema.getOrDefault("properties", Map.of());
            served.path(name).path("properties").propertyNames().forEach(property -> {
                if (!properties.containsKey(property)) {
                    problems.add(name + "." + property + " is served but not documented");
                }
            });
            properties.keySet().forEach(property -> {
                if (!served.path(name).path("properties").has(property)) {
                    problems.add(name + "." + property + " is documented but not served");
                }
            });
        }
        assertThat(problems).isEmpty();
    }

    /** Writes the served referral paths and schemas as JSON for the contract author ({@code REFERRAL_SPEC_OUT}). */
    @Test
    void dumpsTheServedSpecWhenAsked() throws IOException {
        String out = System.getenv("REFERRAL_SPEC_OUT");
        if (out == null) {
            return;
        }
        JsonNode served = ok(send("GET", "/v3/api-docs", null, null));
        Files.writeString(Path.of(out), served.toString());
    }
}
