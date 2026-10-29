package com.afrotransact.platform.web.trace;

import org.slf4j.MDC;
import org.springframework.boot.restclient.RestClientCustomizer;
import org.springframework.http.client.ClientHttpRequestInterceptor;
import org.springframework.web.client.RestClient;

/** Forwards the inbound {@link CorrelationIdFilter} MDC value on every {@link RestClient} outbound call. */
public final class CorrelationIdRestClientCustomizer implements RestClientCustomizer {

    @Override
    public void customize(RestClient.Builder builder) {
        builder.requestInterceptor(interceptor());
    }

    /** Shared by adapters that build a {@link RestClient} without Spring's injected builder. */
    public static ClientHttpRequestInterceptor interceptor() {
        return (request, body, execution) -> {
            String id = MDC.get(CorrelationIdFilter.MDC_KEY);
            if (id != null && !id.isBlank() && !request.getHeaders().containsHeader(CorrelationIdFilter.HEADER)) {
                request.getHeaders().set(CorrelationIdFilter.HEADER, id);
            }
            return execution.execute(request, body);
        };
    }
}
