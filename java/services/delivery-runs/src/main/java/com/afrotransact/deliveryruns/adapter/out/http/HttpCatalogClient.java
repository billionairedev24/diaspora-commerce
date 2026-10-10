package com.afrotransact.deliveryruns.adapter.out.http;

import java.time.Duration;
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
 * catalog-service {@code GET /v1/public/variants/{atin}} (public): category name and the pre-order flags
 * {@code preorderEligible} (default true) and {@code shipsNow} (default false), read from the product or variant.
 */
public class HttpCatalogClient implements CatalogGateway {

    private static final String SERVICE = "catalog";

    private final RestClient catalog;

    public HttpCatalogClient(String baseUrl, Duration timeout) {
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
            out.put(atin, new ProductFacts(atin, category(p), flag(p, v, "shipsNow", false),
                    flag(p, v, "preorderEligible", true)));
        }
        return out;
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
