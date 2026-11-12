package com.afrotransact.offers.adapter.out.tax;

import java.net.http.HttpClient;
import java.time.Duration;
import java.util.List;
import java.util.Map;

import com.afrotransact.offers.port.ServiceTokenProvider;
import com.afrotransact.offers.port.TaxDecisions;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/**
 * tax-returns {@code GET /v1/tax/listing-decisions} (read) and {@code POST /v1/tax/listing-decisions/request} with offers'
 * own client-credentials token (scopes {@code tax:read} and {@code tax:classify}). Any failure is {@link TaxUnavailable}:
 * the offer is held, never given the benefit of the doubt.
 */
@Component
class HttpTaxDecisions implements TaxDecisions {

    private final RestClient client;
    private final ServiceTokenProvider tokens;

    HttpTaxDecisions(RestClient.Builder builder, @Value("${at.offers.tax.base-url:http://localhost:8111}") String baseUrl,
                     @Value("${at.offers.tax.timeout:5s}") Duration timeout, ServiceTokenProvider tokens) {
        this.tokens = tokens;
        HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(http);
        factory.setReadTimeout(timeout);
        this.client = builder.clone().baseUrl(baseUrl).requestFactory(factory).build();
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record StateJson(String status, boolean resolved, List<String> reasons) {
    }

    record RequestJson(String atin, String sellerOrgId, Map<String, Object> sellerBehaviour) {
    }

    @Override
    public DecisionState find(String atin, String sellerOrgId) {
        try {
            StateJson body = client.get().uri(b -> b.path("/v1/tax/listing-decisions").queryParam("atin", atin)
                    .queryParam("sellerOrgId", sellerOrgId).build())
                    .headers(h -> h.setBearerAuth(tokens.token())).retrieve().body(StateJson.class);
            return state(body, atin);
        } catch (RestClientException ex) {
            throw new TaxUnavailable("tax-returns could not say whether " + sellerOrgId + " has a decision on " + atin
                    + ": " + ex.getMessage(), ex);
        }
    }

    @Override
    public DecisionState request(String atin, String sellerOrgId, Map<String, Object> sellerBehaviour) {
        try {
            StateJson body = client.post().uri("/v1/tax/listing-decisions/request")
                    .headers(h -> h.setBearerAuth(tokens.token()))
                    .body(new RequestJson(atin, sellerOrgId, sellerBehaviour == null ? Map.of() : sellerBehaviour))
                    .retrieve().body(StateJson.class);
            return state(body, atin);
        } catch (RestClientException ex) {
            throw new TaxUnavailable("tax-returns could not decide for " + sellerOrgId + " on " + atin + ": "
                    + ex.getMessage(), ex);
        }
    }

    private static DecisionState state(StateJson body, String atin) {
        if (body == null || body.status() == null) {
            throw new TaxUnavailable("tax-returns returned no decision state for " + atin, null);
        }
        // Resolved only when the status says so too: a body that claims resolved=true with another status is not trusted.
        boolean resolved = body.resolved() && "RESOLVED".equals(body.status());
        return new DecisionState(body.status(), resolved, body.reasons());
    }
}
