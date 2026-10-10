package com.afrotransact.retail.adapter.out.inventory;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.afrotransact.retail.port.InventoryClient;
import com.afrotransact.retail.port.InventoryLot;
import com.afrotransact.retail.port.InventoryReceipt;
import com.afrotransact.retail.port.InventoryReceiptResult;
import com.afrotransact.retail.port.InventoryRejectedException;
import com.afrotransact.retail.port.InventoryUnavailableException;
import com.afrotransact.retail.port.ServiceTokenProvider;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * inventory-service over HTTP with the retail-service client-credentials token:
 * {@code POST /v1/receipts} (Idempotency-Key; QA-failed batches carry {@code holdReason}, inbound shipment
 * {@code inboundRef}).
 * 4xx answers (other than auth and in-flight) are rejections; 5xx, timeouts and auth failures are retryable.
 */
public class HttpInventoryClient implements InventoryClient {

    static final String IDEMPOTENCY_KEY = "Idempotency-Key";

    private final RestClient api;
    private final ServiceTokenProvider tokens;
    private final JsonMapper json;

    public HttpInventoryClient(RestClient api, ServiceTokenProvider tokens, JsonMapper json) {
        this.api = api;
        this.tokens = tokens;
        this.json = json;
    }

    @Override
    public InventoryReceiptResult receive(InventoryReceipt receipt, String idempotencyKey) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("receiptRef", receipt.receiptRef());
        body.put("nodeId", receipt.nodeId());
        if (receipt.ownerOrg() != null) {
            body.put("ownerOrg", receipt.ownerOrg());
        }
        if (receipt.inboundRef() != null) {
            body.put("inboundRef", receipt.inboundRef());
        }
        List<Map<String, Object>> lines = new ArrayList<>();
        for (InventoryReceipt.Line l : receipt.lines()) {
            Map<String, Object> line = new LinkedHashMap<>();
            line.put("sku", l.sku());
            line.put("lotNo", l.lotNo());
            line.put("mfgDate", l.mfgDate() == null ? null : l.mfgDate().toString());
            line.put("expDate", l.expDate() == null ? null : l.expDate().toString());
            line.put("qtyGood", l.qtyGood());
            line.put("qtyDamaged", l.qtyDamaged());
            line.put("bin", l.bin());
            line.put("unitLandedCostMinor", l.unitLandedCostMinor());
            line.put("currency", l.currency());
            line.put("shelfLifeDays", l.shelfLifeDays());
            if (l.holdReason() != null) {
                line.put("holdReason", l.holdReason());
            }
            lines.add(line);
        }
        body.put("lines", lines);
        String response = call(() -> api.post().uri("/v1/receipts").headers(this::auth)
                .header(IDEMPOTENCY_KEY, idempotencyKey).contentType(MediaType.APPLICATION_JSON)
                .body(json.writeValueAsString(body)).retrieve().body(String.class));
        JsonNode root = json.readTree(response == null ? "{}" : response);
        List<InventoryLot> lots = new ArrayList<>();
        for (JsonNode lot : root.path("lots")) {
            lots.add(new InventoryLot(UUID.fromString(lot.path("id").asString()), lot.path("sku").asString(),
                    lot.path("lotNo").asString(), lot.path("nodeId").asString(null), lot.path("qtyOnHand").asInt(),
                    lot.path("status").asString(), lot.path("unitLandedCostMinor").asLong()));
        }
        return new InventoryReceiptResult(root.path("receiptRef").asString(receipt.receiptRef()), lots,
                root.path("replayed").asBoolean(false));
    }

    private void auth(HttpHeaders headers) {
        tokens.token().ifPresent(headers::setBearerAuth);
    }

    private String call(Call call) {
        try {
            return call.run();
        } catch (RestClientResponseException e) {
            HttpStatusCode status = e.getStatusCode();
            JsonNode problem = problem(e.getResponseBodyAsString());
            String code = problem.path("code").asString("inventory.http-" + status.value());
            if (status.is5xxServerError() || status.value() == 401 || status.value() == 403
                    || "inventory.request-in-flight".equals(code)) {
                throw new InventoryUnavailableException("inventory-service answered " + status.value() + " ("
                        + code + ")", e);
            }
            Map<String, String> fieldErrors = new LinkedHashMap<>();
            problem.path("fieldErrors").properties().forEach(f -> fieldErrors.put(f.getKey(), f.getValue().asString()));
            throw new InventoryRejectedException(code, problem.path("detail").asString(e.getStatusText()),
                    fieldErrors);
        } catch (RestClientException e) {
            throw new InventoryUnavailableException("inventory-service unreachable: " + e.getMessage(), e);
        }
    }

    private JsonNode problem(String body) {
        try {
            return json.readTree(body == null || body.isBlank() ? "{}" : body);
        } catch (RuntimeException e) {
            return json.readTree("{}");
        }
    }

    @FunctionalInterface
    private interface Call {
        String run();
    }
}
