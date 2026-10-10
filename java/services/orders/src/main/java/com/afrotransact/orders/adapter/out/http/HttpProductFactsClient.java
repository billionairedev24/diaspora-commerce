package com.afrotransact.orders.adapter.out.http;

import java.time.Duration;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

import com.afrotransact.orders.application.OrdersSettings;
import com.afrotransact.orders.port.ProductFactsClient;
import com.afrotransact.orders.port.UpstreamFailure;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;

/**
 * catalog-service (port 8101) {@code GET /v1/public/variants/{atin}}: product type → perishable flag and payments
 * commission category. Missing answers are left out (callers treat them as non-perishable, default commission).
 */
@Component
class HttpProductFactsClient implements ProductFactsClient {

    private static final Logger LOG = LoggerFactory.getLogger(HttpProductFactsClient.class);

    private final Upstream catalog;
    private final Set<String> perishableTypes;
    private final Map<String, String> categories;

    HttpProductFactsClient(RestClient.Builder builder, @Value("${at.orders.upstreams.catalog-url}") String baseUrl,
                           @Value("${at.orders.upstreams.timeout:5s}") Duration timeout, OrdersSettings settings) {
        this.catalog = new Upstream("catalog", builder, baseUrl, timeout);
        this.perishableTypes = settings.perishableTypes();
        this.categories = settings.commissionCategories();
    }

    @Override
    public Map<String, Facts> facts(Collection<String> variantAtins) {
        Map<String, Facts> out = new LinkedHashMap<>();
        for (String atin : variantAtins) {
            try {
                JsonNode v = catalog.call(c -> c.get().uri("/v1/public/variants/{atin}", atin)
                        .retrieve().body(JsonNode.class));
                if (v != null) {
                    JsonNode product = v.path("product");
                    String type = product.path("productType").asString(null);
                    boolean perishable = type != null && perishableTypes.contains(type)
                            || "true".equalsIgnoreCase(product.path("attributes").path("perishable").asString(""));
                    out.put(atin, new Facts(atin, type, perishable, type == null ? null
                            : categories.getOrDefault(type, type)));
                }
            } catch (UpstreamFailure f) {
                LOG.warn("catalog facts unavailable for {} ({})", atin, f.code());
            }
        }
        return out;
    }
}
