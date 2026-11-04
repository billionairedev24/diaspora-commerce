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
 * The membership operations the service serves are the ones contracts/openapi/payments.yaml documents (contract first):
 * same method, path, operationId, and every request and response property.
 */
class MembershipContractIT extends PaymentsIntegrationTest {

    private static final Path CONTRACT = Path.of("../../../contracts/openapi/payments.yaml");
    private static final String PREFIX = "/v1/memberships";

    @SuppressWarnings("unchecked")
    private static Map<String, Object> contract() throws IOException {
        try (Reader in = Files.newBufferedReader(CONTRACT)) {
            return (Map<String, Object>) new Yaml().load(in);
        }
    }

    @Test
    @SuppressWarnings("unchecked")
    void everyServedMembershipOperationIsDocumentedWithTheSameOperationId() throws IOException {
        JsonNode served = ok(send("GET", "/v3/api-docs", null, null));
        Map<String, String> servedOperations = new TreeMap<>();
        served.path("paths").propertyNames().forEach(path -> {
            if (path.startsWith(PREFIX)) {
                served.path("paths").path(path).propertyNames().forEach(method -> servedOperations.put(
                        method.toUpperCase() + " " + path, served.path("paths").path(path).path(method)
                                .path("operationId").asString()));
            }
        });
        Map<String, String> documented = new TreeMap<>();
        Map<String, Map<String, Object>> paths = (Map<String, Map<String, Object>>) contract().get("paths");
        paths.forEach((path, item) -> {
            if (path.startsWith(PREFIX)) {
                item.forEach((method, op) -> documented.put(method.toUpperCase() + " " + path,
                        (String) ((Map<String, Object>) op).get("operationId")));
            }
        });
        assertThat(servedOperations).isNotEmpty().isEqualTo(documented);
    }

    @Test
    @SuppressWarnings("unchecked")
    void documentedSchemasCarryEveryPropertyTheServiceSends() throws IOException {
        Map<String, Map<String, Object>> documented = (Map<String, Map<String, Object>>)
                ((Map<String, Object>) contract().get("components")).get("schemas");
        JsonNode served = ok(send("GET", "/v3/api-docs", null, null)).path("components").path("schemas");
        List<String> problems = new ArrayList<>();
        List<String> names = new ArrayList<>(List.of("CreatePaymentRequest", "PaymentMembershipWaiver", "MyMembership"));
        served.propertyNames().forEach(name -> {
            if (name.startsWith("Membership")) {
                names.add(name);
            }
        });
        for (String name : names) {
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
        }
        assertThat(names).hasSizeGreaterThan(20);
        assertThat(problems).isEmpty();
    }

    @Test
    void operationIdsAreUniqueAcrossTheWholeContract() throws IOException {
        List<String> ids = new ArrayList<>();
        @SuppressWarnings("unchecked")
        Map<String, Map<String, Object>> paths = (Map<String, Map<String, Object>>) contract().get("paths");
        paths.values().forEach(item -> item.values().forEach(op -> {
            if (op instanceof Map<?, ?> operation && operation.get("operationId") != null) {
                ids.add((String) operation.get("operationId"));
            }
        }));
        assertThat(ids).doesNotHaveDuplicates();
    }
}
