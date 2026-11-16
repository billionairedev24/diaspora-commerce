package com.afrotransact.ai.adapter.out.http;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import com.afrotransact.ai.port.ServiceDirectory;
import com.afrotransact.ai.port.Shopper;
import com.afrotransact.module.ModuleMode;
import com.afrotransact.platform.web.module.ConditionalOnModuleMode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import tools.jackson.databind.JsonNode;

/** bookings-service {@code GET /v1/services/search?q=&zip=} (port 8109); empty list when it does not answer. */
@Component
@ConditionalOnModuleMode(caller = "ai-gateway", callee = "bookings", value = ModuleMode.HTTP)
class HttpServiceDirectory implements ServiceDirectory {

    private static final Logger LOG = LoggerFactory.getLogger(HttpServiceDirectory.class);

    private final RestClient client;

    HttpServiceDirectory(RestClient.Builder builder, @Value("${at.ai.downstream.bookings-url}") String baseUrl,
                         @Value("${at.ai.downstream.timeout:5s}") Duration timeout) {
        this.client = Downstream.client(builder, baseUrl, timeout);
    }

    @Override
    public List<Listing> find(String query, String zip, Shopper shopper) {
        String bearerToken = shopper.bearerToken();
        JsonNode body;
        try {
            body = client.get()
                    .uri(u -> {
                        u.path("/v1/services/search").queryParam("q", query).queryParam("size", 6);
                        if (zip != null) {
                            u.queryParam("zip", zip);
                        }
                        return u.build();
                    })
                    .headers(h -> Downstream.relay(h, bearerToken))
                    .retrieve().body(JsonNode.class);
        } catch (RestClientException e) {
            LOG.debug("service search unavailable: {}", e.getClass().getSimpleName());
            return List.of();
        }
        List<Listing> out = new ArrayList<>();
        if (body == null) {
            return out;
        }
        JsonNode items = body.isArray() ? body : body.path("services");
        for (JsonNode s : items) {
            out.add(new Listing(Downstream.text(s, "serviceId"), Downstream.text(s, "name"),
                    Downstream.text(s, "providerName"), Downstream.text(s, "description"),
                    Downstream.number(s, "priceFromMinor"),
                    Downstream.text(s, "currency").isBlank() ? "USD" : Downstream.text(s, "currency"),
                    Downstream.number(s, "depositMinor"), s.path("rating").asDouble(0),
                    Downstream.text(s, "nextAvailable"), Downstream.text(s, "location")));
        }
        return out;
    }
}
