package com.afrotransact.retail.adapter.out.catalog;

import java.util.Optional;

import com.afrotransact.retail.port.CatalogClient;
import com.afrotransact.retail.port.CatalogUnavailableException;
import com.afrotransact.retail.port.CatalogVariant;
import com.afrotransact.retail.port.ServiceTokenProvider;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * catalog-service {@code GET /v1/variants/{atin}} with retail-service's token ({@code catalog:read}): a variant in any
 * status, so draft AfroTransact Retail products can be linked before they go live (the public endpoint hides them).
 */
public class HttpCatalogClient implements CatalogClient {

    private final RestClient api;
    private final ServiceTokenProvider tokens;
    private final JsonMapper json;

    public HttpCatalogClient(RestClient api, ServiceTokenProvider tokens, JsonMapper json) {
        this.api = api;
        this.tokens = tokens;
        this.json = json;
    }

    @Override
    public Optional<CatalogVariant> liveVariant(String atin) {
        try {
            String body = api.get().uri("/v1/variants/{atin}", atin).headers(h -> tokens.token().ifPresent(h::setBearerAuth))
                    .retrieve().body(String.class);
            JsonNode root = json.readTree(body == null ? "{}" : body);
            JsonNode product = root.path("product");
            JsonNode variant = root.path("variant");
            return Optional.of(new CatalogVariant(variant.path("atin").asString(atin),
                    product.path("atin").asString(null), product.path("title").asString(null),
                    product.path("ownerKind").asString(null), variant.path("gtin").asString(null),
                    variant.path("sellerSku").asString(null)));
        } catch (HttpClientErrorException.NotFound e) {
            return Optional.empty();
        } catch (RestClientException e) {
            throw new CatalogUnavailableException("catalog-service unavailable: " + e.getMessage(), e);
        }
    }
}
