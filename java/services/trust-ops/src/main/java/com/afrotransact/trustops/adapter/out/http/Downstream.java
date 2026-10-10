package com.afrotransact.trustops.adapter.out.http;

import java.net.http.HttpClient;
import java.time.Clock;
import java.time.Duration;
import java.util.function.Function;

import com.afrotransact.trustops.config.TrustOpsProperties;
import com.afrotransact.trustops.port.UpstreamException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Shared plumbing of the outbound adapters: one client-credentials token for every upstream, RestClients with
 * timeouts, and RFC 9457 problems mapped to {@link UpstreamException}.
 */
@Component
class Downstream {

    private final RestClient.Builder builder;
    private final ClientCredentialsTokens tokens;
    private final JsonMapper json;
    private final Duration timeout;

    Downstream(RestClient.Builder builder, TrustOpsProperties props, JsonMapper json, Clock clock) {
        this.builder = builder.clone();
        this.json = json;
        this.timeout = props.client().timeout();
        TrustOpsProperties.Client c = props.client();
        this.tokens = new ClientCredentialsTokens(client(null, timeout), c.tokenUri(), c.clientId(), c.clientSecret(),
                c.scopes(), clock);
    }

    /** A RestClient for {@code baseUrl}; {@code readTimeout} null = the default upstream timeout. */
    RestClient client(String baseUrl, Duration readTimeout) {
        Duration t = readTimeout == null ? timeout : readTimeout;
        HttpClient http = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).connectTimeout(t).build();
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(http);
        factory.setReadTimeout(t);
        RestClient.Builder b = builder.clone().requestFactory(factory);
        return baseUrl == null ? b.build() : b.baseUrl(baseUrl).build();
    }

    /** Sets the bearer token (when enabled) and the optional Idempotency-Key. */
    void headers(HttpHeaders h, String idempotencyKey) {
        if (tokens.enabled()) {
            h.setBearerAuth(tokens.token());
        }
        if (idempotencyKey != null) {
            h.set("Idempotency-Key", idempotencyKey);
        }
    }

    <T> T call(String service, RestClient client, Function<RestClient, JsonNode> request,
               Function<JsonNode, T> map) {
        try {
            JsonNode node = request.apply(client);
            return map.apply(node == null ? json.createObjectNode() : node);
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
            throw new UpstreamException(service, e.getStatusCode().value(), code, service + ": " + detail, e);
        } catch (RestClientException | IllegalStateException e) {
            throw new UpstreamException(service, 0, null, service + " unavailable: " + e.getMessage(), e);
        }
    }
}
