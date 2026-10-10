package com.afrotransact.trustops.adapter.out.http;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import com.afrotransact.trustops.config.TrustOpsProperties;
import com.afrotransact.trustops.port.OrdersClient;
import com.afrotransact.trustops.port.UpstreamException;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;

/** orders-service {@code GET /v1/lookup/orders/{idOrNumber}} (OrderLookup). */
@Component
class HttpOrdersClient implements OrdersClient {

    private static final String SERVICE = "orders";

    private final Downstream downstream;
    private final RestClient client;

    HttpOrdersClient(Downstream downstream, TrustOpsProperties props) {
        this.downstream = downstream;
        this.client = downstream.client(props.orders().baseUrl(), null);
    }

    @Override
    public Optional<OrderFacts> order(String orderIdOrNumber) {
        try {
            return Optional.of(downstream.call(SERVICE, client, c -> c.get()
                    .uri("/v1/lookup/orders/{id}", orderIdOrNumber).headers(h -> downstream.headers(h, null))
                    .retrieve().body(JsonNode.class), HttpOrdersClient::facts));
        } catch (UpstreamException e) {
            if (e.status() == 404) {
                return Optional.empty();
            }
            throw e;
        }
    }

    private static OrderFacts facts(JsonNode n) {
        List<ShipmentFacts> shipments = new ArrayList<>();
        for (JsonNode s : n.path("shipments")) {
            List<String> lines = new ArrayList<>();
            s.path("lines").forEach(l -> lines.add(l.path("lineId").asString()));
            shipments.add(new ShipmentFacts(s.path("shipmentId").asString(), s.path("sellerOrgId").asString(),
                    s.path("status").asString(null), lines));
        }
        return new OrderFacts(n.path("orderId").asString(), n.path("orderNumber").asString(null),
                n.path("customerId").asString(null), n.path("paymentId").asString(null),
                n.path("currency").asString(null), shipments);
    }
}
