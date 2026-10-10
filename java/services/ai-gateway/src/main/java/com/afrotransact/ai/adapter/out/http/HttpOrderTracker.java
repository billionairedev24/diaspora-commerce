package com.afrotransact.ai.adapter.out.http;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import com.afrotransact.ai.port.OrderTracker;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import tools.jackson.databind.JsonNode;

/**
 * orders-service {@code GET /v1/orders/{id}} (port 8104) with the shopper's token: 404/403 mean "not your order",
 * anything else (including the service not being deployed yet) means "not available".
 */
@Component
class HttpOrderTracker implements OrderTracker {

    private static final Logger LOG = LoggerFactory.getLogger(HttpOrderTracker.class);

    private final RestClient client;

    HttpOrderTracker(RestClient.Builder builder, @Value("${at.ai.downstream.orders-url}") String baseUrl,
                     @Value("${at.ai.downstream.timeout:5s}") Duration timeout) {
        this.client = Downstream.client(builder, baseUrl, timeout);
    }

    @Override
    public Lookup track(String orderId, String bearerToken) {
        JsonNode o;
        try {
            o = client.get().uri("/v1/orders/{id}", orderId)
                    .headers(h -> Downstream.relay(h, bearerToken))
                    .retrieve().body(JsonNode.class);
        } catch (HttpClientErrorException.NotFound | HttpClientErrorException.Forbidden e) {
            return Lookup.of(Availability.NOT_FOUND, orderId);
        } catch (RestClientException e) {
            LOG.debug("orders service unavailable: {}", e.getClass().getSimpleName());
            return Lookup.of(Availability.UNAVAILABLE, orderId);
        }
        if (o == null) {
            return Lookup.of(Availability.UNAVAILABLE, orderId);
        }
        List<Shipment> shipments = new ArrayList<>();
        for (JsonNode s : o.path("shipments")) {
            shipments.add(new Shipment(Downstream.text(s, "id"), Downstream.text(s, "status"),
                    Downstream.text(s, "carrier"), Downstream.text(s, "trackingNumber"),
                    Downstream.text(s, "promisedBy")));
        }
        String number = Downstream.text(o, "orderNumber");
        return new Lookup(Availability.FOUND, number.isBlank() ? orderId : number, Downstream.text(o, "status"),
                shipments);
    }
}
