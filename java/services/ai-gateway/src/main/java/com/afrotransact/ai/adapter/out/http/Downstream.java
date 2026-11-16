package com.afrotransact.ai.adapter.out.http;

import java.net.http.HttpClient;
import java.time.Duration;
import java.util.List;
import java.util.function.Supplier;

import com.afrotransact.ai.port.AccountReader;
import com.afrotransact.platform.security.ActingAsActorResolver;
import org.slf4j.Logger;
import org.springframework.http.HttpHeaders;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import tools.jackson.databind.JsonNode;

/** Shared plumbing of the downstream HTTP adapters: timeouts, bearer relay and lenient JSON reads. */
final class Downstream {

    private Downstream() {
    }

    static RestClient client(RestClient.Builder builder, String baseUrl, Duration timeout) {
        HttpClient http = HttpClient.newBuilder().connectTimeout(timeout).build();
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(http);
        factory.setReadTimeout(timeout);
        return builder.clone().baseUrl(baseUrl).requestFactory(factory).build();
    }

    /**
     * Relays the caller's bearer token (token exchange is future work). Every relay is a Lola shopper tool, so it acts
     * as a shopper: a person whose token carries their seller organisation still sees their own orders.
     */
    static void relay(HttpHeaders headers, String bearerToken) {
        if (bearerToken != null && !bearerToken.isBlank()) {
            headers.setBearerAuth(bearerToken);
            headers.set(ActingAsActorResolver.HEADER, ActingAsActorResolver.CUSTOMER);
        }
    }

    static String text(JsonNode node, String field) {
        JsonNode v = node.path(field);
        return v.isMissingNode() || v.isNull() ? "" : v.asString("");
    }

    static long number(JsonNode node, String field) {
        return node.path(field).asLong(0);
    }

    /** The list of a response that is either a bare array or an object with an {@code items} array. */
    static Iterable<JsonNode> items(JsonNode body) {
        if (body == null) {
            return List.of();
        }
        return body.isArray() ? body : body.path("items");
    }

    /** One account read: anything that fails is "not available", as Lola says it. */
    static <T> AccountReader.Result<T> read(Logger log, String what, Supplier<T> call) {
        try {
            T value = call.get();
            return value == null ? AccountReader.Result.unavailable() : AccountReader.Result.of(value);
        } catch (RestClientException e) {
            log.debug("{} service unavailable: {}", what, e.getClass().getSimpleName());
            return AccountReader.Result.unavailable();
        }
    }
}
