package com.afrotransact.seller.adapter.out.payments;

import java.net.http.HttpClient;
import java.time.Duration;
import java.util.Map;

import com.afrotransact.platform.web.trace.CorrelationIdRestClientCustomizer;
import com.afrotransact.seller.adapter.out.usage.ServiceTokens;
import com.afrotransact.seller.port.ConnectedAccountRegistry;
import com.afrotransact.seller.port.GatewayException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

/**
 * {@code PUT /v1/connected-accounts/{orgId}} on payments-service ({@code payments:connected-accounts}).
 */
public class HttpConnectedAccountRegistry implements ConnectedAccountRegistry {

    private final RestClient client;
    private final ServiceTokens tokens;

    public HttpConnectedAccountRegistry(String paymentsUrl, Duration timeout, ServiceTokens tokens) {
        HttpClient http = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).connectTimeout(timeout).build();
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(http);
        factory.setReadTimeout(timeout);
        this.client = RestClient.builder().requestFactory(factory).baseUrl(paymentsUrl)
                .requestInterceptor(CorrelationIdRestClientCustomizer.interceptor()).build();
        this.tokens = tokens;
    }

    @Override
    public void register(String orgId, String stripeAccountId) {
        try {
            client.put().uri("/v1/connected-accounts/{orgId}", orgId)
                    .headers(this::auth)
                    .body(Map.of("stripeAccountId", stripeAccountId))
                    .retrieve()
                    .toBodilessEntity();
        } catch (RestClientResponseException ex) {
            throw failed(ex);
        } catch (RestClientException ex) {
            throw unavailable(ex);
        }
    }

    private void auth(HttpHeaders h) {
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
