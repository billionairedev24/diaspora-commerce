package com.afrotransact.fulfilment.adapter.out.http;

import java.net.http.HttpClient;
import java.time.Duration;
import java.util.function.Function;

import com.afrotransact.fulfilment.port.UpstreamFailure;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** One upstream service: a RestClient with timeouts, and error mapping to {@link UpstreamFailure}. */
final class Upstream {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private final String service;
    private final RestClient client;

    Upstream(String service, RestClient.Builder builder, String baseUrl, Duration timeout) {
        this.service = service;
        // HTTP/1.1: plain-HTTP h2c upgrades of POST bodies are reset by some servers (and gain nothing in-cluster).
        HttpClient http = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).connectTimeout(timeout).build();
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(http);
        factory.setReadTimeout(timeout);
        this.client = builder.clone().baseUrl(baseUrl).requestFactory(factory).build();
    }

    /** Runs a call, mapping HTTP errors (4xx non-retryable except 408/429; 5xx and I/O retryable). */
    <T> T call(Function<RestClient, T> call) {
        try {
            return call.apply(client);
        } catch (RestClientResponseException e) {
            throw failure(e);
        } catch (ResourceAccessException e) {
            throw new UpstreamFailure(service, 0, service + ".unavailable", service + " did not answer: "
                    + e.getMessage(), true, e);
        } catch (RestClientException e) {
            throw new UpstreamFailure(service, 0, service + ".invalid-response", service + " sent an unreadable "
                    + "response: " + e.getMessage(), true, e);
        }
    }

    private UpstreamFailure failure(RestClientResponseException e) {
        int status = e.getStatusCode().value();
        String code = service + ".http-" + status;
        String detail = e.getStatusText();
        try {
            String body = e.getResponseBodyAsString();
            if (body != null && !body.isBlank()) {
                JsonNode problem = JSON.readTree(body);
                code = problem.path("code").asString(code);
                detail = problem.path("detail").asString(detail);
            }
        } catch (RuntimeException parse) {
            // not a problem document; keep the status text
        }
        boolean retryable = status >= 500 || status == 408 || status == 429;
        return new UpstreamFailure(service, status, code, detail, retryable, e);
    }
}
