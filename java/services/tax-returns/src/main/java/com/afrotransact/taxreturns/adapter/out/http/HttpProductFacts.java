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
 * answers (cached for five minutes: checkout quotes call this for every line).
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
                JsonNode product = catalog.call(c -> c.get().uri("/v1/public/variants/{atin}", atin).retrieve()
                        .body(JsonNode.class)).path("product");
                String type = product.path("productType").asString(null);
                JsonNode attributes = product.path("attributes");
                boolean perishable = "true".equalsIgnoreCase(attributes.path("perishable").asString(""));
                Map<String, String> answers = new LinkedHashMap<>();
                attributes.properties().forEach(e -> answers.put(e.getKey(), e.getValue().asString("")));
                Facts facts = new Facts(atin, type, perishable, answers);
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
}
