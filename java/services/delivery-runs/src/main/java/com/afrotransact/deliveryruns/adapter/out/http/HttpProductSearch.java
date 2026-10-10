package com.afrotransact.deliveryruns.adapter.out.http;

import java.time.Duration;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import com.afrotransact.deliveryruns.port.ProductSearch;
import com.afrotransact.deliveryruns.port.UpstreamUnavailableException;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import tools.jackson.databind.JsonNode;

/**
 * search-service {@code GET /v1/search?q=<category>&market=…&track=false}: hits whose category path names one of the
 * window's categories (hits without a path are kept).
 */
public class HttpProductSearch implements ProductSearch {

    private static final int PAGE = 60;

    private final RestClient search;

    public HttpProductSearch(String baseUrl, Duration timeout) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(timeout);
        factory.setReadTimeout(timeout);
        this.search = RestClient.builder().baseUrl(baseUrl).requestFactory(factory).build();
    }

    @Override
    public List<Product> inCategories(Set<String> categories, String market, int limit) {
        Map<String, Product> out = new LinkedHashMap<>();
        for (String category : categories) {
            JsonNode page;
            try {
                page = search.get().uri(b -> b.path("/v1/search").queryParam("q", category)
                        .queryParam("market", market.toLowerCase(Locale.ROOT)).queryParam("size", PAGE)
                        .queryParam("track", false).build()).retrieve().body(JsonNode.class);
            } catch (RestClientException e) {
                throw new UpstreamUnavailableException("search", "search-service failed: " + e.getMessage(), e);
            }
            if (page == null) {
                continue;
            }
            for (JsonNode h : page.path("hits")) {
                String atin = h.path("atin").asString("");
                if (atin.isEmpty() || h.path("sponsored").asBoolean(false) || !inCategory(h, category)) {
                    continue;
                }
                out.putIfAbsent(atin, new Product(atin, h.path("title").asString(atin), h.path("priceMinor").asLong(0),
                        h.path("currency").asString("USD"), h.path("sellerName").asString("")));
            }
        }
        return out.values().stream().sorted(Comparator.comparing(Product::name)).limit(Math.max(limit, 1)).toList();
    }

    private static boolean inCategory(JsonNode hit, String category) {
        JsonNode path = hit.path("categoryPath");
        if (!path.isArray() || path.isEmpty()) {
            return true;
        }
        for (JsonNode p : path) {
            if (p.asString("").equalsIgnoreCase(category)) {
                return true;
            }
        }
        return false;
    }
}
