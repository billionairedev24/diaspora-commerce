package com.afrotransact.taxreturns.adapter.out.http;

import java.util.LinkedHashMap;
import java.util.Map;

import com.afrotransact.taxreturns.config.TaxReturnsProperties;
import com.afrotransact.taxreturns.domain.returns.ItemCondition;
import com.afrotransact.taxreturns.port.InventoryGateway;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** inventory-service {@code POST /v1/returns} (inventory:adjust): RETURN_RESTOCK or RETURN_DAMAGED (lot -DMG). */
@Component
class HttpInventoryGateway implements InventoryGateway {

    private final Upstream inventory;

    HttpInventoryGateway(RestClient.Builder builder, TaxReturnsProperties props, ClientCredentialsTokens tokens,
                         JsonMapper json) {
        this.inventory = new Upstream("inventory", builder, props.upstreams().inventoryUrl(),
                props.upstreams().timeout(), tokens, json);
    }

    @Override
    public void acceptReturn(String orderId, String sku, int quantity, ItemCondition condition, String reason,
                             String receivingNodeId, String idempotencyKey) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("orderId", orderId);
        body.put("sku", sku);
        body.put("qty", quantity);
        body.put("condition", condition.name());
        body.put("reason", reason.length() > 500 ? reason.substring(0, 500) : reason);
        if (receivingNodeId != null && !receivingNodeId.isBlank()) {
            body.put("receivingNodeId", receivingNodeId);
        }
        inventory.call(c -> c.post().uri("/v1/returns").headers(h -> inventory.headers(h, idempotencyKey))
                .body(body).retrieve().body(JsonNode.class));
    }
}
