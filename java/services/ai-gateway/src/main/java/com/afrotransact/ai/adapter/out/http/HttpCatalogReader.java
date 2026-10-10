package com.afrotransact.ai.adapter.out.http;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

import com.afrotransact.ai.port.CatalogReader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import tools.jackson.databind.JsonNode;

/** catalog-service public reads; 404 and outages read as "not found" (Lola then refuses to invent the product). */
@Component
class HttpCatalogReader implements CatalogReader {

    private static final Logger LOG = LoggerFactory.getLogger(HttpCatalogReader.class);

    private final RestClient client;

    HttpCatalogReader(RestClient.Builder builder, @Value("${at.ai.downstream.catalog-url}") String baseUrl,
                      @Value("${at.ai.downstream.timeout:5s}") Duration timeout) {
        this.client = Downstream.client(builder, baseUrl, timeout);
    }

    @Override
    public Optional<Item> find(String atin, String bearerToken) {
        Optional<JsonNode> variant = get("/v1/public/variants/{atin}", atin, bearerToken);
        if (variant.isPresent()) {
            JsonNode v = variant.get().path("variant");
            JsonNode p = variant.get().path("product");
            return Optional.of(new Item(atin, Downstream.text(p, "atin"), Downstream.text(p, "title"),
                    attributes(p, v), price(v), currency(v), Downstream.text(p, "sellerOrgId")));
        }
        return get("/v1/public/products/{atin}", atin, bearerToken).map(p -> {
            JsonNode first = p.path("variants").path(0);
            return new Item(atin, Downstream.text(p, "atin"), Downstream.text(p, "title"), attributes(p, first),
                    price(first), currency(first), Downstream.text(p, "sellerOrgId"));
        });
    }

    private Optional<JsonNode> get(String path, String atin, String bearerToken) {
        try {
            return Optional.ofNullable(client.get().uri(path, atin)
                    .headers(h -> Downstream.relay(h, bearerToken))
                    .retrieve().body(JsonNode.class));
        } catch (HttpClientErrorException.NotFound e) {
            return Optional.empty();
        } catch (RestClientException e) {
            LOG.warn("catalog lookup failed: {}", e.getClass().getSimpleName());
            return Optional.empty();
        }
    }

    private static Map<String, String> attributes(JsonNode product, JsonNode variant) {
        Map<String, String> out = new LinkedHashMap<>();
        product.path("attributes").properties().forEach(e -> out.put(e.getKey(), e.getValue().asString("")));
        variant.path("options").properties().forEach(e -> out.put(e.getKey(), e.getValue().asString("")));
        return out;
    }

    private static long price(JsonNode variant) {
        if (variant.has("priceMinor")) {
            return variant.path("priceMinor").asLong(0);
        }
        return variant.path("price").path("amountMinor").asLong(0);
    }

    private static String currency(JsonNode variant) {
        String c = Downstream.text(variant, "currency");
        if (c.isBlank()) {
            c = Downstream.text(variant.path("price"), "currency");
        }
        return c.isBlank() ? "USD" : c;
    }
}
