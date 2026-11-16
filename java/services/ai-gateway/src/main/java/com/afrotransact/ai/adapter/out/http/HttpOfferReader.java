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
 * offers-service {@code GET /v1/public/products/{productAtin}/buy-boxes} (port 8102): the buy box of every variant of the product page,
 * flattened to one row per listed offer, the winner first. Any failure (the service not deployed included) is an empty list. (The path
 * used to be {@code /v1/public/products/{atin}/offers}, which offers never served, so compare_offers always came back empty.)
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
            body = client.get().uri("/v1/public/products/{atin}/buy-boxes", productAtin)
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
        JsonNode boxes = body.isArray() ? body : body.path("items");
        for (JsonNode box : boxes) {
            JsonNode winner = box.path("buyBox");
            if (!winner.isMissingNode() && !winner.isNull()) {
                out.add(offer(winner));
            }
            for (JsonNode other : box.path("otherOffers")) {
                out.add(offer(other));
            }
        }
        return out;
    }

    private static Offer offer(JsonNode o) {
        JsonNode seller = o.path("seller");
        String name = Downstream.text(seller, "name");
        String currency = Downstream.text(o, "currency");
        String condition = Downstream.text(o, "condition");
        return new Offer(Downstream.text(o, "offerId"), name.isBlank() ? Downstream.text(o, "soldBy") : name,
                Downstream.text(seller, "kind"), Downstream.text(o, "fulfilment"), Downstream.number(o, "priceMinor"),
                currency.isBlank() ? "USD" : currency, !"OUT_OF_STOCK".equals(Downstream.text(o, "stockStatus")),
                o.path("deliveryDays").asInt(0), condition.isBlank() ? "NEW" : condition);
    }
}
