package com.afrotransact.deliveryruns.adapter.out.http;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;

import com.afrotransact.deliveryruns.domain.PreorderResolver.ProductFacts;
import com.afrotransact.deliveryruns.port.CatalogGateway;
import com.afrotransact.deliveryruns.port.UpstreamUnavailableException;
import org.springframework.http.HttpStatus;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import tools.jackson.databind.JsonNode;

/**
 * catalog-service {@code GET /v1/public/variants/{atin}} (public): the product's category and the pre-order flags
 * {@code preorderEligible} (default true) and {@code shipsNow} (default false), read from the product or variant.
 * The variant view names its category by id only; ids are resolved to the category path ("Groceries › Pantry ›
 * Grains & rice") with the public {@code GET /v1/categories}, cached for {@link #CATEGORY_TTL}.
 */
public class HttpCatalogClient implements CatalogGateway {

    static final Duration CATEGORY_TTL = Duration.ofMinutes(10);

    private static final String SERVICE = "catalog";

    private final RestClient catalog;
    private final Clock clock;
    private volatile Map<String, String> categoryPaths = Map.of();
    private volatile Instant categoriesFetched = Instant.EPOCH;

    public HttpCatalogClient(String baseUrl, Duration timeout) {
        this(baseUrl, timeout, Clock.systemUTC());
    }

    public HttpCatalogClient(String baseUrl, Duration timeout, Clock clock) {
        this.clock = clock;
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(timeout);
        factory.setReadTimeout(timeout);
        this.catalog = RestClient.builder().baseUrl(baseUrl).requestFactory(factory).build();
    }

    @Override
    public Map<String, ProductFacts> facts(Collection<String> atins) {
        Map<String, ProductFacts> out = new LinkedHashMap<>();
        for (String atin : atins) {
            JsonNode r;
            try {
                r = catalog.get().uri("/v1/public/variants/{atin}", atin).retrieve().body(JsonNode.class);
            } catch (HttpClientErrorException e) {
                if (e.getStatusCode().value() == HttpStatus.NOT_FOUND.value()) {
                    continue;
                }
                throw new UpstreamUnavailableException(SERVICE, "catalog refused " + atin, e);
            } catch (RestClientException e) {
                throw new UpstreamUnavailableException(SERVICE, "catalog-service failed: " + e.getMessage(), e);
            }
            if (r == null) {
                continue;
            }
            JsonNode p = r.path("product");
            JsonNode v = r.path("variant");
            out.put(atin, new ProductFacts(atin, categoryOf(p), flag(p, v, "shipsNow", false),
                    flag(p, v, "preorderEligible", true)));
        }
        return out;
    }

    /** The product's category name, or the path of its {@code categoryId}. */
    private String categoryOf(JsonNode p) {
        String named = category(p);
        if (named != null || !p.path("categoryId").isString()) {
            return named;
        }
        String id = p.path("categoryId").asString();
        String path = categoryPaths().get(id);
        if (path == null && categoriesFetched.isAfter(Instant.EPOCH)) {
            categoriesFetched = Instant.EPOCH; // a category added since the last read
            path = categoryPaths().get(id);
        }
        return path;
    }

    private Map<String, String> categoryPaths() {
        Instant now = clock.instant();
        if (now.isBefore(categoriesFetched.plus(CATEGORY_TTL))) {
            return categoryPaths;
        }
        JsonNode list;
        try {
            list = catalog.get().uri("/v1/categories").retrieve().body(JsonNode.class);
        } catch (RestClientException e) {
            throw new UpstreamUnavailableException(SERVICE, "catalog-service categories failed: " + e.getMessage(), e);
        }
        Map<String, String> paths = new LinkedHashMap<>();
        if (list != null) {
            for (JsonNode c : list) {
                String path = c.path("path").isString() ? c.path("path").asString() : c.path("name").asString(null);
                if (c.path("id").isString() && path != null) {
                    paths.put(c.path("id").asString(), path);
                }
            }
        }
        categoryPaths = Map.copyOf(paths);
        categoriesFetched = now;
        return categoryPaths;
    }

    private static String category(JsonNode p) {
        for (String f : new String[] {"categoryName", "category"}) {
            JsonNode c = p.path(f);
            if (c.isString()) {
                return c.asString();
            }
            if (c.isObject() && c.path("name").isString()) {
                return c.path("name").asString();
            }
        }
        JsonNode path = p.path("categoryPath");
        return path.isArray() && !path.isEmpty() ? path.get(path.size() - 1).asString() : null;
    }

    private static boolean flag(JsonNode product, JsonNode variant, String field, boolean fallback) {
        if (variant.path(field).isBoolean()) {
            return variant.path(field).asBoolean();
        }
        return product.path(field).isBoolean() ? product.path(field).asBoolean() : fallback;
    }
}
