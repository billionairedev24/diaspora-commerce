package com.afrotransact.payments.adapter.out.seller;

import java.net.http.HttpClient;
import java.time.Clock;
import java.time.Duration;
import java.util.Optional;

import com.afrotransact.payments.port.SellerPlanDirectory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;
import tools.jackson.databind.JsonNode;

/**
 * {@link SellerPlanDirectory} over the seller service's {@code GET /v1/orgs/{orgId}/billing} (contracts/openapi/
 * seller.yaml, operation billingOverview), read with the {@code payments-service} client's {@code seller:billing}
 * scope. Off (always empty) while {@code at.payments.seller.base-url} ({@code SELLER_URL}) is blank.
 */
@Component
class HttpSellerPlanDirectory implements SellerPlanDirectory {

    private static final Logger LOG = LoggerFactory.getLogger(HttpSellerPlanDirectory.class);

    private final RestClient client;
    private final SellerServiceTokens tokens;
    private final boolean enabled;

    HttpSellerPlanDirectory(RestClient.Builder builder, Clock clock,
                            @Value("${at.payments.seller.base-url:}") String baseUrl,
                            @Value("${at.payments.seller.token-uri:}") String tokenUri,
                            @Value("${at.payments.seller.client-id:payments-service}") String clientId,
                            @Value("${at.payments.seller.client-secret:}") String clientSecret,
                            @Value("${at.payments.seller.timeout:3s}") Duration timeout) {
        HttpClient http = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).connectTimeout(timeout).build();
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(http);
        factory.setReadTimeout(timeout);
        RestClient base = builder.clone().requestFactory(factory).build();
        this.enabled = baseUrl != null && !baseUrl.isBlank();
        this.client = enabled ? base.mutate().baseUrl(baseUrl).build() : base;
        this.tokens = new SellerServiceTokens(base, tokenUri, clientId, clientSecret, clock);
    }

    @Override
    public Optional<Plan> currentPlan(String orgId) {
        if (!enabled) {
            return Optional.empty();
        }
        try {
            JsonNode n = client.get().uri("/v1/orgs/{orgId}/billing", orgId)
                    .headers(h -> {
                        if (tokens.enabled()) {
                            h.setBearerAuth(tokens.token());
                        }
                    })
                    .retrieve().body(JsonNode.class);
            if (n == null) {
                return Optional.empty();
            }
            String plan = n.path("planCode").asString("");
            return Optional.of(new Plan(plan.isBlank() ? null : plan, n.path("status").asString(null)));
        } catch (RestClientResponseException e) {
            if (e.getStatusCode().value() == HttpStatus.NOT_FOUND.value()) {
                return Optional.of(new Plan(null, null));
            }
            LOG.warn("Seller service refused the plan of {} ({}): rates fall back to category rates",
                    orgId, e.getStatusCode());
            return Optional.empty();
        } catch (RestClientException | IllegalStateException e) {
            LOG.warn("Seller service unavailable for the plan of {} ({}): rates fall back to category rates",
                    orgId, e.getMessage());
            return Optional.empty();
        }
    }
}
