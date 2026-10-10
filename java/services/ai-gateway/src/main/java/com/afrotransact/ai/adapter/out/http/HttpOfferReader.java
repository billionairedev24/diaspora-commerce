package com.afrotransact.ai.adapter.out.http;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import com.afrotransact.ai.port.OfferReader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import tools.jackson.databind.JsonNode;

/**
 * offers-service {@code GET /v1/public/products/{atin}/offers} (path agreed for the offers service, port 8102).
 * Until it is deployed every call returns an empty list.
 */
@Component
class HttpOfferReader implements OfferReader {

    private static final Logger LOG = LoggerFactory.getLogger(HttpOfferReader.class);

    private final RestClient client;

    HttpOfferReader(RestClient.Builder builder, @Value("${at.ai.downstream.offers-url}") String baseUrl,
                    @Value("${at.ai.downstream.timeout:5s}") Duration timeout) {
        this.client = Downstream.client(builder, baseUrl, timeout);
    }

    @Override
    public List<Offer> offers(String productAtin, String bearerToken) {
        JsonNode body;
        try {
            body = client.get().uri("/v1/public/products/{atin}/offers", productAtin)
                    .headers(h -> Downstream.relay(h, bearerToken))
                    .retrieve().body(JsonNode.class);
        } catch (RestClientException e) {
            LOG.debug("offers unavailable: {}", e.getClass().getSimpleName());
            return List.of();
        }
        List<Offer> out = new ArrayList<>();
        if (body == null) {
            return out;
        }
        JsonNode items = body.isArray() ? body : body.path("offers");
        for (JsonNode o : items) {
            out.add(new Offer(Downstream.text(o, "offerId"), Downstream.text(o, "sellerName"),
                    Downstream.text(o, "sellerKind"), Downstream.text(o, "fulfilment"), Downstream.number(o, "priceMinor"),
                    Downstream.text(o, "currency").isBlank() ? "USD" : Downstream.text(o, "currency"),
                    o.path("inStock").asBoolean(false), o.path("deliveryDays").asInt(0),
                    Downstream.text(o, "condition").isBlank() ? "NEW" : Downstream.text(o, "condition")));
        }
        return out;
    }
}
