package com.afrotransact.seller.adapter.out.payments;

import java.net.http.HttpClient;
import java.time.Duration;
import java.util.Map;

import com.afrotransact.seller.adapter.out.usage.ServiceTokens;
import com.afrotransact.seller.port.EarningsWithholdingGateway;
import com.afrotransact.seller.port.GatewayException;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;
import tools.jackson.databind.JsonNode;

/**
 * payments-service {@code POST /v1/orgs/{orgId}/plan-fee-withholdings} (the payments side is backlog story S298):
 * withhold a plan invoice's amount from the next settlement transfer, idempotent on the invoice id. Answer
 * {@code {"id", "status": "WITHHELD" | "DECLINED", "reason"}}. Only wired when the feature flag is on.
 */
public class HttpEarningsWithholding implements EarningsWithholdingGateway {

    private final RestClient client;
    private final ServiceTokens tokens;

    public HttpEarningsWithholding(String paymentsUrl, Duration timeout, ServiceTokens tokens) {
        HttpClient http = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).connectTimeout(timeout).build();
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(http);
        factory.setReadTimeout(timeout);
        this.client = RestClient.builder().requestFactory(factory).baseUrl(paymentsUrl).build();
        this.tokens = tokens;
    }

    @Override
    public boolean enabled() {
        return true;
    }

    @Override
    public Withholding withhold(String orgId, String invoiceId, long amountCents, String currency) {
        try {
            JsonNode body = client.post().uri("/v1/orgs/{org}/plan-fee-withholdings", orgId)
                    .header("Idempotency-Key", "plan-fee-" + invoiceId)
                    .headers(h -> {
                        if (tokens != null && tokens.enabled()) {
                            h.setBearerAuth(tokens.token());
                        }
                    })
                    .body(Map.of("invoiceId", invoiceId, "amountMinor", amountCents, "currency", currency))
                    .retrieve().body(JsonNode.class);
            if (body == null) {
                throw new GatewayException("seller.payments-invalid", "payments answered nothing", false, null);
            }
            boolean withheld = "WITHHELD".equals(body.path("status").asString(""));
            return new Withholding(withheld, body.path("id").asString(null), body.path("reason").asString(null));
        } catch (RestClientResponseException e) {
            if (e.getStatusCode().value() == 422 || e.getStatusCode().value() == 409) {
                return new Withholding(false, null, "payments refused: " + e.getStatusCode().value());
            }
            throw new GatewayException("seller.payments-failed", "payments answered " + e.getStatusCode().value(),
                    e.getStatusCode().is4xxClientError(), e);
        } catch (RestClientException e) {
            throw new GatewayException("seller.payments-unavailable", "payments unavailable: " + e.getMessage(), false,
                    e);
        }
    }
}
