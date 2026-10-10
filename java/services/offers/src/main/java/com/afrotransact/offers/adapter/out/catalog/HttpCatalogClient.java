package com.afrotransact.offers.adapter.out.catalog;

import java.net.http.HttpClient;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.afrotransact.offers.port.CatalogClient;
import com.afrotransact.offers.port.ServiceTokenProvider;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/**
 * catalog-service (port 8101) over HTTP. Variant lookups use the public, cacheable
 * {@code GET /v1/public/variants/{atin}}; GTIN lookups call {@code GET /v1/identifiers/gtin/{code}} with offers'
 * own client-credentials token ({@code offers-service}, scope {@code catalog:read}; the seller's token is never
 * relayed), then read the product page to find the variant carrying that GTIN. 404 means "no such live
 * variant"; other failures raise {@link CatalogUnavailableException} (HTTP 503).
 */
@Component
class HttpCatalogClient implements CatalogClient {

    private final RestClient client;
    private final ServiceTokenProvider tokens;

    HttpCatalogClient(RestClient.Builder builder, @Value("${at.offers.catalog.base-url}") String baseUrl,
                      @Value("${at.offers.catalog.timeout:3s}") Duration timeout, ServiceTokenProvider tokens) {
        this.tokens = tokens;
        HttpClient http = HttpClient.newBuilder().connectTimeout(timeout).build();
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(http);
        factory.setReadTimeout(timeout);
        this.client = builder.clone().baseUrl(baseUrl).requestFactory(factory).build();
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record ProductJson(UUID id, String atin, String ownerKind, String sellerOrgId, UUID categoryId, String title,
                       List<VariantJson> variants) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record VariantJson(UUID id, String atin, String sellerSku, String gtin) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record PublicVariantJson(ProductJson product, VariantJson variant) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record GtinCheckJson(String digits, boolean valid, String existingAtin) {
    }

    @Override
    public Optional<CatalogVariant> liveVariant(String atin) {
        PublicVariantJson body = get(() -> client.get().uri("/v1/public/variants/{atin}", atin)
                .retrieve().body(PublicVariantJson.class));
        if (body == null || body.product() == null || body.variant() == null) {
            return Optional.empty();
        }
        return Optional.of(variant(body.product(), body.variant()));
    }

    @Override
    public Optional<CatalogVariant> liveVariantByGtin(String gtin) {
        GtinCheckJson check = get(() -> client.get().uri("/v1/identifiers/gtin/{code}", gtin)
                .headers(h -> h.setBearerAuth(tokens.token()))
                .retrieve().body(GtinCheckJson.class));
        if (check == null || !check.valid() || check.existingAtin() == null) {
            return Optional.empty();
        }
        ProductJson product = get(() -> client.get().uri("/v1/public/products/{atin}", check.existingAtin())
                .retrieve().body(ProductJson.class));
        if (product == null || product.variants() == null) {
            return Optional.empty();
        }
        String wanted = stripZeros(gtin);
        return product.variants().stream()
                .filter(v -> v.gtin() != null && stripZeros(v.gtin()).equals(wanted))
                .findFirst()
                .map(v -> variant(product, v));
    }

    private static CatalogVariant variant(ProductJson p, VariantJson v) {
        return new CatalogVariant(p.id(), p.atin(), v.atin(), v.gtin(), p.categoryId(), p.title(), p.sellerOrgId());
    }

    private static <T> T get(Call<T> call) {
        try {
            return call.run();
        } catch (HttpClientErrorException.NotFound ex) {
            return null;
        } catch (RestClientException ex) {
            throw new CatalogUnavailableException("catalog-service is unavailable: " + ex.getMessage(), ex);
        }
    }

    private static String stripZeros(String gtin) {
        String digits = gtin.replaceAll("[^0-9]", "");
        int i = 0;
        while (i < digits.length() - 1 && digits.charAt(i) == '0') {
            i++;
        }
        return digits.substring(i);
    }

    @FunctionalInterface
    private interface Call<T> {
        T run();
    }
}
