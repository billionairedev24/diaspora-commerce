package com.afrotransact.taxreturns.adapter.out.http;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;

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
 * catalog-service {@code GET /v1/public/variants/{atin}} (public): product type and the {@code perishable} attribute.
 * Missing or failing answers are left out.
 */
@Component
class HttpProductFacts implements ProductFacts {

    private static final Logger LOG = LoggerFactory.getLogger(HttpProductFacts.class);

    private final Upstream catalog;

    HttpProductFacts(RestClient.Builder builder, TaxReturnsProperties props, JsonMapper json) {
        this.catalog = new Upstream("catalog", builder, props.upstreams().catalogUrl(), props.upstreams().timeout(),
                null, json);
    }

    @Override
    public Map<String, Facts> lookup(Collection<String> atins) {
        Map<String, Facts> out = new LinkedHashMap<>();
        for (String atin : atins) {
            try {
                JsonNode product = catalog.call(c -> c.get().uri("/v1/public/variants/{atin}", atin).retrieve()
                        .body(JsonNode.class)).path("product");
                String type = product.path("productType").asString(null);
                boolean perishable = "true".equalsIgnoreCase(product.path("attributes").path("perishable")
                        .asString(""));
                out.put(atin, new Facts(atin, type, perishable));
            } catch (UpstreamException ex) {
                LOG.debug("No catalog facts for {}: {}", atin, ex.getMessage());
            }
        }
        return out;
    }
}
