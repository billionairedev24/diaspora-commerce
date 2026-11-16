package com.afrotransact.ai.adapter.out.http;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import com.afrotransact.ai.port.AccountReader;
import com.afrotransact.ai.port.OrderHistory;
import com.afrotransact.ai.port.Shopper;
import com.afrotransact.module.ModuleMode;
import com.afrotransact.platform.web.module.ConditionalOnModuleMode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;

/** The shopper's own orders (orders {@code GET /v1/orders?customer=me}) under their token. Anything that fails is "not available". */
@Component
@ConditionalOnModuleMode(caller = "ai-gateway", callee = "orders", value = ModuleMode.HTTP)
class HttpOrderHistory implements OrderHistory {

    private static final Logger LOG = LoggerFactory.getLogger(HttpOrderHistory.class);

    private final RestClient orders;

    HttpOrderHistory(RestClient.Builder builder, @Value("${at.ai.downstream.orders-url}") String ordersUrl,
                     @Value("${at.ai.downstream.timeout:5s}") Duration timeout) {
        this.orders = Downstream.client(builder, ordersUrl, timeout);
    }

    @Override
    public AccountReader.Result<List<AccountReader.OrderSummary>> recent(Shopper shopper, int limit) {
        return Downstream.read(LOG, "orders", () -> {
            JsonNode body = orders.get().uri(u -> u.path("/v1/orders").queryParam("customer", "me")
                    .queryParam("limit", limit).build()).headers(h -> Downstream.relay(h, shopper.bearerToken())).retrieve()
                    .body(JsonNode.class);
            List<AccountReader.OrderSummary> list = new ArrayList<>();
            for (JsonNode o : Downstream.items(body)) {
                String placed = Downstream.text(o, "placedAt");
                list.add(new AccountReader.OrderSummary(Downstream.text(o, "orderNumber"), Downstream.text(o, "status"),
                        placed.isBlank() ? Downstream.text(o, "createdAt") : placed, Downstream.number(o, "totalMinor"),
                        Downstream.text(o, "currency"), o.path("shipments").size()));
            }
            return list;
        });
    }
}
