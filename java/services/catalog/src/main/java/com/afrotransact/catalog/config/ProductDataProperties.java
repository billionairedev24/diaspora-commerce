package com.afrotransact.catalog.config;

import java.time.Duration;
import java.util.List;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * {@code at.catalog.product-data.*}: GTIN prefill providers (S031).
 *
 * @param order         provider names in lookup order (GS1 first, then Open Food Facts)
 * @param cacheTtl      how long a found GTIN is cached
 * @param missTtl       how long "no provider knows this GTIN" is cached (provider errors are never cached)
 * @param gs1           Verified by GS1 (GS1 Registry Platform); needs an API key, off by default
 * @param openFoodFacts Open Food Facts product API v2 (food and groceries), on by default
 */
@ConfigurationProperties(prefix = "at.catalog.product-data")
public record ProductDataProperties(List<String> order, Duration cacheTtl, Duration missTtl, Gs1 gs1,
                                    OpenFoodFacts openFoodFacts) {

    public ProductDataProperties {
        order = order == null || order.isEmpty() ? List.of("GS1", "OPEN_FOOD_FACTS") : List.copyOf(order);
        cacheTtl = cacheTtl == null ? Duration.ofDays(7) : cacheTtl;
        missTtl = missTtl == null ? Duration.ofDays(1) : missTtl;
        gs1 = gs1 == null ? new Gs1(null, null, null, null) : gs1;
        openFoodFacts = openFoodFacts == null ? new OpenFoodFacts(null, null, null, null) : openFoodFacts;
    }

    /** Verified by GS1: {@code POST {baseUrl}/grp/v3.2/gtins/verified} with header {@code APIKey}. */
    public record Gs1(Boolean enabled, String baseUrl, String apiKey, Duration timeout) {

        public Gs1 {
            enabled = enabled != null && enabled;
            baseUrl = baseUrl == null || baseUrl.isBlank() ? "https://grp.gs1.org" : baseUrl;
            apiKey = apiKey == null ? "" : apiKey;
            timeout = timeout == null ? Duration.ofSeconds(3) : timeout;
        }
    }

    /** Open Food Facts: {@code GET {baseUrl}/api/v2/product/{code}.json}; a descriptive User-Agent is required. */
    public record OpenFoodFacts(Boolean enabled, String baseUrl, String userAgent, Duration timeout) {

        public OpenFoodFacts {
            enabled = enabled == null || enabled;
            baseUrl = baseUrl == null || baseUrl.isBlank() ? "https://world.openfoodfacts.org" : baseUrl;
            userAgent = userAgent == null || userAgent.isBlank()
                    ? "AfroTransact-Catalog/1.0 (catalog@afrotransact.com)" : userAgent;
            timeout = timeout == null ? Duration.ofSeconds(3) : timeout;
        }
    }
}
