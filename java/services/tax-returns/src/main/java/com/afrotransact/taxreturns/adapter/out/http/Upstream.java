package com.afrotransact.taxreturns.adapter.out.http;

import java.time.Duration;
import java.util.function.Function;

import com.afrotransact.taxreturns.port.UpstreamException;
import org.springframework.http.HttpHeaders;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** One upstream service: base URL, bearer token, Idempotency-Key and RFC 9457 error mapping. */
final class Upstream {

    static final String IDEMPOTENCY_KEY = "Idempotency-Key";

    private final String name;
    private final RestClient client;
    private final ClientCredentialsTokens tokens;
    private final JsonMapper json;

    Upstream(String name, RestClient.Builder builder, String baseUrl, Duration timeout, ClientCredentialsTokens tokens,
             JsonMapper json) {
        this.name = name;
        this.client = builder.clone().requestFactory(ClientCredentialsTokens.factory(timeout)).baseUrl(baseUrl)
                .build();
        this.tokens = tokens;
        this.json = json;
    }

    void headers(HttpHeaders h, String idempotencyKey) {
        if (tokens != null && tokens.enabled()) {
            h.setBearerAuth(tokens.token());
        }
        if (idempotencyKey != null) {
            h.set(IDEMPOTENCY_KEY, idempotencyKey);
        }
    }

    /** Runs the call; maps HTTP errors (with the problem code) and I/O errors to {@link UpstreamException}. */
    JsonNode call(Function<RestClient, JsonNode> request) {
        try {
            JsonNode node = request.apply(client);
            return node == null ? json.createObjectNode() : node;
        } catch (RestClientResponseException e) {
            String code = null;
            String detail = e.getStatusText();
            try {
                JsonNode problem = json.readTree(e.getResponseBodyAsString());
                code = problem.path("code").asString(null);
                detail = problem.path("detail").asString(detail);
            } catch (RuntimeException ignored) {
                // not a problem document
            }
            throw new UpstreamException(name, e.getStatusCode().value(), code, name + ": " + detail, e);
        } catch (RestClientException | IllegalStateException e) {
            throw new UpstreamException(name, 0, null, name + " unavailable: " + e.getMessage(), e);
        }
    }
}
