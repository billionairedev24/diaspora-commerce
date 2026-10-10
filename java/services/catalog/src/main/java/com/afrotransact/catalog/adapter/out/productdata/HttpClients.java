package com.afrotransact.catalog.adapter.out.productdata;

import java.net.http.HttpClient;
import java.net.http.HttpTimeoutException;
import java.time.Duration;
import java.util.function.Supplier;

import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;

/** RestClients for the providers: HTTP/1.1, connect and read timeouts, redirects followed, one retry. */
final class HttpClients {

    private HttpClients() {
    }

    static RestClient create(RestClient.Builder builder, String baseUrl, Duration timeout) {
        HttpClient http = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).connectTimeout(timeout)
                .followRedirects(HttpClient.Redirect.NORMAL).build();
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(http);
        factory.setReadTimeout(timeout);
        return builder.clone().requestFactory(factory).baseUrl(baseUrl).build();
    }

    /**
     * Runs a lookup, retrying once when a pooled connection was dropped (I/O error other than a timeout). Lookups
     * are reads, so the retry is safe; timeouts are not retried to keep the product builder responsive.
     */
    static <T> T withRetry(Supplier<T> call) {
        try {
            return call.get();
        } catch (ResourceAccessException e) {
            if (e.getCause() instanceof HttpTimeoutException) {
                throw e;
            }
            return call.get();
        }
    }
}
