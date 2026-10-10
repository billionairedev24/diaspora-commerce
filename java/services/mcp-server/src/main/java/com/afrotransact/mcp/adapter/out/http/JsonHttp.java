package com.afrotransact.mcp.adapter.out.http;

import java.net.http.HttpClient;
import java.time.Duration;
import java.util.function.Function;

import com.afrotransact.mcp.port.Downstream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

/**
 * Shared HTTP plumbing of the platform API adapters: per-service base URL and timeouts, bearer relay, and mapping of
 * HTTP outcomes to {@link Downstream} (404 → NOT_FOUND, 401/403 → FORBIDDEN, other 4xx → REJECTED,
 * 5xx / connection errors → UNAVAILABLE).
 */
final class JsonHttp {

    private static final Logger LOG = LoggerFactory.getLogger(JsonHttp.class);

    private final RestClient client;
    private final String service;

    JsonHttp(RestClient.Builder builder, String service, String baseUrl, Duration timeout) {
        HttpClient http = HttpClient.newBuilder().connectTimeout(timeout).build();
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(http);
        factory.setReadTimeout(timeout);
        this.client = builder.clone().baseUrl(baseUrl).requestFactory(factory).build();
        this.service = service;
    }

    Downstream call(String bearerToken, Function<RestClient, RestClient.RequestHeadersSpec<?>> request) {
        try {
            Object body = request.apply(client)
                    .headers(h -> relay(h, bearerToken))
                    .retrieve()
                    .body(Object.class);
            return Downstream.ok(body);
        } catch (RestClientResponseException e) {
            return Downstream.of(status(e.getStatusCode(), e));
        } catch (RestClientException e) {
            LOG.debug("{} unavailable: {}", service, e.getClass().getSimpleName());
            return Downstream.of(Downstream.Status.UNAVAILABLE);
        }
    }

    private Downstream.Status status(HttpStatusCode code, RestClientResponseException e) {
        if (code.value() == 404) {
            return Downstream.Status.NOT_FOUND;
        }
        if (code.value() == 401 || code.value() == 403) {
            return Downstream.Status.FORBIDDEN;
        }
        if (code.is4xxClientError()) {
            return Downstream.Status.REJECTED;
        }
        LOG.warn("{} answered {}", service, code.value());
        return Downstream.Status.UNAVAILABLE;
    }

    private static void relay(HttpHeaders headers, String bearerToken) {
        if (bearerToken != null && !bearerToken.isBlank()) {
            headers.setBearerAuth(bearerToken);
        }
    }
}
