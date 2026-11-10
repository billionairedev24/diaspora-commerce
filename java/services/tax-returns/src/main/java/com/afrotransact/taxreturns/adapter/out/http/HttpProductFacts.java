package com.afrotransact.taxreturns.adapter.out.http;

import java.time.Duration;
import java.time.Instant;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import com.afrotransact.taxreturns.config.TaxReturnsProperties;
import com.afrotransact.taxreturns.port.ProductFacts;
import com.afrotransact.taxreturns.port.UpstreamException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * catalog-service {@code GET /v1/public/variants/{atin}} (public): product type, {@code perishable} and the attribute
 * answers and, when catalog reports them, the tax category and typed tax facts (cached for five minutes: checkout quotes call this for every line).
 * Missing or failing answers are left out.
 */
@Component
class HttpProductFacts implements ProductFacts {

    private static final Logger LOG = LoggerFactory.getLogger(HttpProductFacts.class);

    private static final Duration TTL = Duration.ofMinutes(5);
    private static final int MAX_CACHED = 5_000;

    private final Upstream catalog;
    private final Map<String, Cached> cache = new ConcurrentHashMap<>();

    private record Cached(Facts facts, Instant at) {
    }

    HttpProductFacts(RestClient.Builder builder, TaxReturnsProperties props, JsonMapper json) {
        this.catalog = new Upstream("catalog", builder, props.upstreams().catalogUrl(), props.upstreams().timeout(),
                null, json);
    }

    @Override
    public Map<String, Facts> lookup(Collection<String> atins) {
        Map<String, Facts> out = new LinkedHashMap<>();
        Instant now = Instant.now();
        for (String atin : atins) {
            Cached hit = cache.get(atin);
            if (hit != null && hit.at().plus(TTL).isAfter(now)) {
                out.put(atin, hit.facts());
                continue;
            }
            try {
                // The answer is {"product": {...}, "variant": {...}}: the tax facts are the variant's, the category code
                // and the listing behaviour the product's.
                JsonNode answer = catalog.call(c -> c.get().uri("/v1/public/variants/{atin}", atin).retrieve()
                        .body(JsonNode.class));
                JsonNode variant = answer.path("variant");
                JsonNode product = answer.path("product");
                String type = product.path("productType").asString(null);
                JsonNode attributes = product.path("attributes");
                boolean perishable = "true".equalsIgnoreCase(attributes.path("perishable").asString(""));
                Map<String, String> answers = new LinkedHashMap<>();
                attributes.properties().forEach(e -> answers.put(e.getKey(), e.getValue().asString("")));
                String category = firstText(product.path("taxCategoryCode"), product.path("categoryCode"),
                        variant.path("categoryCode"));
                Map<String, Object> taxFacts = plain(variant.path("taxFacts"));
                Map<String, Object> behaviour = plain(product.path("listing").path("sellerBehaviour"));
                Facts facts = new Facts(atin, type, perishable, answers, category, taxFacts, behaviour,
                        product.path("sellerOrgId").asString(null));
                if (cache.size() >= MAX_CACHED) {
                    cache.clear();
                }
                cache.put(atin, new Cached(facts, now));
                out.put(atin, facts);
            } catch (UpstreamException ex) {
                LOG.debug("No catalog facts for {}: {}", atin, ex.getMessage());
            }
        }
        return out;
    }

    private static String firstText(JsonNode... nodes) {
        for (JsonNode n : nodes) {
            if (n.isString() && !n.asString().isBlank()) {
                return n.asString();
            }
        }
        return null;
    }

    /** Typed values as catalog sent them: booleans, numbers and text; anything nested is ignored. */
    private static Map<String, Object> plain(JsonNode node) {
        Map<String, Object> out = new LinkedHashMap<>();
        node.properties().forEach(e -> {
            JsonNode v = e.getValue();
            if (v.isBoolean()) {
                out.put(e.getKey(), v.asBoolean());
            } else if (v.isIntegralNumber()) {
                out.put(e.getKey(), v.asLong());
            } else if (v.isNumber()) {
                out.put(e.getKey(), v.decimalValue());
            } else if (v.isString()) {
                out.put(e.getKey(), v.asString());
            }
        });
        return out;
    }
}
