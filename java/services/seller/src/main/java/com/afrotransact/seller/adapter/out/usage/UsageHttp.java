package com.afrotransact.seller.adapter.out.usage;

import java.net.http.HttpClient;
import java.time.Duration;
import java.time.LocalDate;

import com.afrotransact.seller.port.GatewayException;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;
import tools.jackson.databind.JsonNode;

/** One usage source: base URL, service token, {@code from}/{@code to} query and error mapping. */
final class UsageHttp {

    private final String name;
    private final RestClient client;
    private final ServiceTokens tokens;

    UsageHttp(String name, String baseUrl, Duration timeout, ServiceTokens tokens) {
        this.name = name;
        this.client = RestClient.builder().requestFactory(factory(timeout)).baseUrl(baseUrl).build();
        this.tokens = tokens;
    }

    static JdkClientHttpRequestFactory factory(Duration timeout) {
        HttpClient http = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).connectTimeout(timeout).build();
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(http);
        factory.setReadTimeout(timeout);
        return factory;
    }

    /** GETs {@code path?from=&to=} and returns its {@code items} array; failures become {@link GatewayException}. */
    JsonNode items(String path, LocalDate from, LocalDate to) {
        try {
            JsonNode body = client.get().uri(b -> b.path(path).queryParam("from", from).queryParam("to", to).build())
                    .headers(h -> {
                        if (tokens != null && tokens.enabled()) {
                            h.setBearerAuth(tokens.token());
                        }
                    })
                    .retrieve()
                    .body(JsonNode.class);
            JsonNode items = body == null ? null : body.path("items");
            if (items == null || !items.isArray()) {
                throw new GatewayException("seller.usage-source-invalid", name + " returned no items", false, null);
            }
            return items;
        } catch (RestClientResponseException e) {
            throw new GatewayException("seller.usage-source-failed", name + " answered " + e.getStatusCode().value(),
                    e.getStatusCode().is4xxClientError(), e);
        } catch (RestClientException | IllegalStateException e) {
            throw new GatewayException("seller.usage-source-unavailable", name + " unavailable: " + e.getMessage(),
                    false, e);
        }
    }

    static LocalDate day(JsonNode n) {
        return LocalDate.parse(n.path("day").asString());
    }
}
