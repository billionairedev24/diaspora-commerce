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
 * The credit, gift card and money settings operations the service serves are the ones contracts/openapi/payments.yaml
 * documents (contract first): same method, path, operationId, success status, and every request / response property.
 */
class CreditContractIT extends PaymentsIntegrationTest {

    private static final Path CONTRACT = Path.of("../../../contracts/openapi/payments.yaml");
    private static final List<String> PREFIXES = List.of("/v1/credit", "/v1/gift-cards", "/v1/money",
            "/v1/orders/{orderId}/credit-refunds", "/v1/customers/me/orders/{orderId}/credit-refunds");

    @SuppressWarnings("unchecked")
    private static Map<String, Object> contract() throws IOException {
        try (Reader in = Files.newBufferedReader(CONTRACT)) {
            return (Map<String, Object>) new Yaml().load(in);
        }
    }

    /** method + path -> operationId, for the operations under the credit prefixes. */
    private static Map<String, String> contractOperations(Map<String, Object> contract) {
        Map<String, String> out = new TreeMap<>();
        @SuppressWarnings("unchecked")
        Map<String, Map<String, Object>> paths = (Map<String, Map<String, Object>>) contract.get("paths");
        paths.forEach((path, item) -> {
            if (PREFIXES.stream().anyMatch(path::startsWith)) {
                item.forEach((method, op) -> {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> operation = (Map<String, Object>) op;
                    out.put(method.toUpperCase() + " " + path, (String) operation.get("operationId"));
                });
            }
        });
        return out;
    }

    @Test
    void everyServedOperationIsDocumentedWithTheSameOperationId() throws IOException {
        JsonNode served = ok(send("GET", "/v3/api-docs", null, null));
        Map<String, String> servedOperations = new TreeMap<>();
        served.path("paths").propertyNames().forEach(path -> {
            if (PREFIXES.stream().anyMatch(path::startsWith)) {
                served.path("paths").path(path).propertyNames().forEach(method -> servedOperations.put(
                        method.toUpperCase() + " " + path, served.path("paths").path(path).path(method)
                                .path("operationId").asString()));
            }
        });
        Map<String, String> documented = contractOperations(contract());
        assertThat(servedOperations).isNotEmpty().isEqualTo(documented);
    }

    @Test
    @SuppressWarnings("unchecked")
    void documentedSchemasCarryEveryPropertyTheServiceSends() throws IOException {
        Map<String, Object> contract = contract();
        Map<String, Map<String, Object>> documented = (Map<String, Map<String, Object>>)
                ((Map<String, Object>) contract.get("components")).get("schemas");
        JsonNode served = ok(send("GET", "/v3/api-docs", null, null)).path("components").path("schemas");
        List<String> problems = new ArrayList<>();
        for (String name : List.of("CreditBalance", "CreditEntry", "CreditAdjustment", "CustomerCredit",
                "CreditLiabilities", "CreditGrantRequest", "CreditAdjustRequest", "GiftCardConfig",
                "GiftCardPurchase", "GiftCardPurchaseRequest", "GiftCardRedemption", "GiftCardRedeemRequest",
                "GiftCard", "GiftCardIssueRequest", "GiftCardReasonRequest", "MoneySettings", "MoneySettingsUpdate",
                "MoneySettingsChange", "CreditRefundChoice", "CreditRefundChoiceRequest", "CreatePaymentRequest", "PaymentResponse", "RefundRequest",
                "OrderRefundRequest", "RefundResponse")) {
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
        assertThat(problems).isEmpty();
    }
}
