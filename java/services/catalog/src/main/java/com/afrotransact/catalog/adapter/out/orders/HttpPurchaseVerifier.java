package com.afrotransact.catalog.adapter.out.orders;

import java.net.http.HttpClient;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

import com.afrotransact.catalog.domain.PurchaseProof;
import com.afrotransact.catalog.port.PurchaseVerifier;
import com.afrotransact.catalog.port.ServiceTokens;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/**
 * orders-service {@code GET /v1/lookup/customers/{customerId}/purchases/{atin}} with catalog's service token (scope
 * {@code orders:purchase-check}): did the customer receive the product? Any failure to get an answer is
 * {@link PurchaseCheckUnavailable}: a review is never accepted on a guess.
 */
@Component
class HttpPurchaseVerifier implements PurchaseVerifier {

    private final RestClient orders;
    private final ServiceTokens tokens;

    HttpPurchaseVerifier(RestClient.Builder builder, ServiceTokens tokens,
                         @Value("${at.catalog.orders.base-url}") String baseUrl,
                         @Value("${at.catalog.orders.timeout:3s}") Duration timeout) {
        HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(http);
        factory.setReadTimeout(timeout);
        this.orders = builder.clone().baseUrl(baseUrl).requestFactory(factory).build();
        this.tokens = tokens;
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record PurchaseJson(boolean delivered, String orderId, String orderNumber, Instant deliveredAt) {
    }

    @Override
    public Optional<PurchaseProof> deliveredPurchase(String customerId, String productAtin) {
        try {
            String token = tokens.token();
            PurchaseJson body = orders.get()
                    .uri("/v1/lookup/customers/{customerId}/purchases/{atin}", customerId, productAtin)
                    .headers(h -> {
                        if (token != null) {
                            h.setBearerAuth(token);
                        }
                    })
                    .retrieve().body(PurchaseJson.class);
            if (body == null || !body.delivered() || body.orderId() == null) {
                return Optional.empty();
            }
            return Optional.of(new PurchaseProof(body.orderId(),
                    body.orderNumber() == null ? body.orderId() : body.orderNumber(), body.deliveredAt()));
        } catch (RuntimeException e) {
            throw new PurchaseCheckUnavailable("orders-service could not confirm the purchase: " + e.getMessage(), e);
        }
    }
}
