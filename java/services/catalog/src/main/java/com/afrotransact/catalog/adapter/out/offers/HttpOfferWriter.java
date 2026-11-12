package com.afrotransact.catalog.adapter.out.offers;

import java.net.http.HttpClient;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;

import com.afrotransact.catalog.port.OfferWriter;
import com.afrotransact.catalog.port.ServiceTokens;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/**
 * offers-service {@code POST /v1/offers/batch} and {@code PUT /v1/offers/prices-and-stock} with catalog's service token
 * (scope {@code offers:write}), for the organisation that uploaded the file. A failure is never guessed: it is
 * {@link OffersUnavailable}, the rows stay pending and the upload is resumed (both calls are safe to repeat).
 */
@Component
class HttpOfferWriter implements OfferWriter {

    private final RestClient offers;
    private final ServiceTokens tokens;

    HttpOfferWriter(RestClient.Builder builder, ServiceTokens tokens,
                    @Value("${at.catalog.offers.base-url:http://localhost:8102}") String baseUrl,
                    @Value("${at.catalog.offers.batch-timeout:60s}") Duration timeout) {
        HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(http);
        factory.setReadTimeout(timeout);
        this.offers = builder.clone().baseUrl(baseUrl).requestFactory(factory).build();
        this.tokens = tokens;
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    record UpsertBody(String sellerOrgId, UUID importId, String uploadedBy, List<OfferTerms> rows) {
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    record PriceStockBody(String sellerOrgId, UUID importId, String uploadedBy, List<PriceStock> rows) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record BatchJson(List<Result> results) {
    }

    @Override
    public List<Result> upsert(String sellerOrgId, UUID importId, String uploadedBy, List<OfferTerms> rows) {
        return send(() -> offers.post().uri("/v1/offers/batch").headers(this::auth)
                .body(new UpsertBody(sellerOrgId, importId, uploadedBy, rows)).retrieve().body(BatchJson.class),
                rows.size());
    }

    @Override
    public List<Result> setPricesAndStock(String sellerOrgId, UUID importId, String uploadedBy, List<PriceStock> rows) {
        return send(() -> offers.put().uri("/v1/offers/prices-and-stock").headers(this::auth)
                .body(new PriceStockBody(sellerOrgId, importId, uploadedBy, rows)).retrieve().body(BatchJson.class),
                rows.size());
    }

    private List<Result> send(Supplier<BatchJson> call, int expected) {
        try {
            BatchJson body = call.get();
            if (body == null || body.results() == null || body.results().size() != expected) {
                throw new OffersUnavailable("offers-service answered with " + (body == null || body.results() == null ? 0
                        : body.results().size()) + " results for " + expected + " rows", null);
            }
            return body.results();
        } catch (OffersUnavailable e) {
            throw e;
        } catch (RuntimeException e) {
            throw new OffersUnavailable("offers-service could not apply the batch: " + e.getMessage(), e);
        }
    }

    private void auth(HttpHeaders headers) {
        String token = tokens.token();
        if (token != null) {
            headers.setBearerAuth(token);
        }
    }
}
