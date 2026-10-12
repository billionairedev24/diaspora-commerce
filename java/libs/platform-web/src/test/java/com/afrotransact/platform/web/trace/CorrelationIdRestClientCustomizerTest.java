package com.afrotransact.platform.web.trace;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.http.HttpRequest;
import org.springframework.http.client.ClientHttpRequestExecution;
import org.springframework.http.client.ClientHttpRequestInterceptor;
import org.springframework.mock.http.client.MockClientHttpRequest;

class CorrelationIdRestClientCustomizerTest {

    private static final ClientHttpRequestExecution NO_OP = (request, body) -> null;

    @AfterEach
    void clearMdc() {
        MDC.clear();
    }

    @Test
    void forwardsMdcCorrelationId() throws IOException {
        MDC.put(CorrelationIdFilter.MDC_KEY, "req-42");
        ClientHttpRequestInterceptor interceptor = CorrelationIdRestClientCustomizer.interceptor();
        HttpRequest request = new MockClientHttpRequest();
        interceptor.intercept(request, new byte[0], NO_OP);
        assertThat(request.getHeaders().getFirst(CorrelationIdFilter.HEADER)).isEqualTo("req-42");
    }

    @Test
    void doesNotOverwriteExistingHeader() throws IOException {
        MDC.put(CorrelationIdFilter.MDC_KEY, "req-42");
        ClientHttpRequestInterceptor interceptor = CorrelationIdRestClientCustomizer.interceptor();
        HttpRequest request = new MockClientHttpRequest();
        request.getHeaders().set(CorrelationIdFilter.HEADER, "already-set");
        interceptor.intercept(request, new byte[0], NO_OP);
        assertThat(request.getHeaders().getFirst(CorrelationIdFilter.HEADER)).isEqualTo("already-set");
    }
}
