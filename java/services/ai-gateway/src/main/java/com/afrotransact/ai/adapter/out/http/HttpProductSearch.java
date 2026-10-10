package com.afrotransact.ai.adapter.out.http;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import com.afrotransact.ai.port.ProductSearch;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;

/** {@code GET {search-url}/v1/search} (contracts/openapi/search.yaml). Errors propagate: the tool reports them. */
@Component
class HttpProductSearch implements ProductSearch {

    private final RestClient client;

    HttpProductSearch(RestClient.Builder builder, @Value("${at.ai.downstream.search-url}") String baseUrl,
                      @Value("${at.ai.downstream.timeout:5s}") Duration timeout) {
        this.client = Downstream.client(builder, baseUrl, timeout);
    }

    @Override
    public List<Hit> search(Query query, String bearerToken) {
        JsonNode page = client.get()
                .uri(u -> {
                    u.path("/v1/search").queryParam("q", query.text()).queryParam("size", query.size());
                    if (query.maxPriceMinor() != null) {
                        u.queryParam("maxPrice", query.maxPriceMinor());
                    }
                    if (query.inStockOnly()) {
                        u.queryParam("inStock", true);
                    }
                    if (query.community() != null) {
                        u.queryParam("community", query.community());
                    }
                    return u.build();
                })
                .headers(h -> Downstream.relay(h, bearerToken))
                .retrieve()
                .body(JsonNode.class);
        List<Hit> hits = new ArrayList<>();
        if (page == null) {
            return hits;
        }
        for (JsonNode h : page.path("hits")) {
            String atin = Downstream.text(h, "atin");
            if (atin.isBlank()) {
                continue;
            }
            hits.add(new Hit(atin, Downstream.text(h, "title"), Downstream.text(h, "brand"),
                    Downstream.text(h, "sellerName"), Downstream.text(h, "sellerKind"), Downstream.text(h, "fulfilment"),
                    Downstream.number(h, "priceMinor"), Downstream.text(h, "currency"), h.path("rating").asDouble(0),
                    h.path("reviewCount").asInt(0), h.path("inStock").asBoolean(false),
                    h.path("lowStock").asBoolean(false), h.path("availableQty").asInt(0),
                    h.path("deliveryDays").asInt(0), Downstream.text(h, "imageUrl"), h.path("offerCount").asInt(0)));
        }
        return hits;
    }
}
