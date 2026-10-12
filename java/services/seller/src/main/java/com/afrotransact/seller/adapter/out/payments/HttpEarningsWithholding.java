package com.afrotransact.seller.adapter.out.payments;

import java.net.http.HttpClient;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;

import com.afrotransact.seller.adapter.out.usage.ServiceTokens;
import com.afrotransact.seller.port.EarningsWithholdingGateway;
import com.afrotransact.seller.port.GatewayException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;
import tools.jackson.databind.JsonNode;

/**
 * payments-service plan fees (S298), with the {@code seller-service} client-credentials token (scope
 * {@code payments:plan-fees}):
 * <ul>
 *   <li>{@code POST /v1/orgs/{orgId}/plan-fee-withholdings} ({@code Idempotency-Key: plan-fee-<invoice>}) answers
 *   {@code {"id", "status": "WITHHELD" | "DECLINED", "withheldMinor", "reason"}};</li>
 *   <li>{@code POST /v1/orgs/{orgId}/plan-fee-withholdings/{invoice}/release} ({@code plan-fee-release-<…>}) answers
 *   the withholding with {@code releasedMinor}.</li>
 * </ul>
 * Only wired when the kill switch is on.
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
    public Withholding withhold(String orgId, String invoiceId, String invoiceNumber, long amountCents,
                                String currency) {
        Map<String, Object> body = new HashMap<>();
        body.put("invoiceId", invoiceId);
        body.put("amountMinor", amountCents);
        body.put("currency", currency);
        if (invoiceNumber != null) {
            body.put("invoiceNumber", invoiceNumber);
        }
        try {
            JsonNode r = client.post().uri("/v1/orgs/{org}/plan-fee-withholdings", orgId)
                    .headers(h -> headers(h, "plan-fee-" + invoiceId)).body(body)
                    .retrieve().body(JsonNode.class);
            if (r == null) {
                throw new GatewayException("seller.payments-invalid", "payments answered nothing", false, null);
            }
            boolean withheld = "WITHHELD".equals(r.path("status").asString(""));
            return new Withholding(withheld, withheld ? r.path("withheldMinor").asLong(0) : 0,
                    r.path("id").asString(null), r.path("reason").asString(null));
        } catch (RestClientResponseException e) {
            if (e.getStatusCode().value() == 422 || e.getStatusCode().value() == 409) {
                return new Withholding(false, 0, null, "payments refused: " + e.getStatusCode().value());
            }
            throw failed(e);
        } catch (RestClientException e) {
            throw unavailable(e);
        }
    }

    @Override
    public long release(String orgId, String invoiceId, long amountCents, String reason, String idempotencyKey) {
        try {
            JsonNode r = client.post().uri("/v1/orgs/{org}/plan-fee-withholdings/{invoice}/release", orgId, invoiceId)
                    .headers(h -> headers(h, idempotencyKey))
                    .body(Map.of("amountMinor", amountCents, "reason", reason))
                    .retrieve().body(JsonNode.class);
            return r == null ? 0 : r.path("releasedMinor").asLong(0);
        } catch (RestClientResponseException e) {
            if (e.getStatusCode().value() == 404) {
                // Nothing was withheld for this invoice: there is nothing to release.
                return 0;
            }
            throw failed(e);
        } catch (RestClientException e) {
            throw unavailable(e);
        }
    }

    private void headers(HttpHeaders h, String idempotencyKey) {
        h.set("Idempotency-Key", idempotencyKey);
        if (tokens != null && tokens.enabled()) {
            h.setBearerAuth(tokens.token());
        }
    }

    private static GatewayException failed(RestClientResponseException e) {
        return new GatewayException("seller.payments-failed", "payments answered " + e.getStatusCode().value(),
                e.getStatusCode().is4xxClientError(), e);
    }

    private static GatewayException unavailable(RestClientException e) {
        return new GatewayException("seller.payments-unavailable", "payments unavailable: " + e.getMessage(), false,
                e);
    }
}
