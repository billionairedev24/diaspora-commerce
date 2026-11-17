package com.afrotransact.catalog.adapter.out.search;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import com.afrotransact.catalog.port.CandidateRecall;
import com.afrotransact.catalog.port.CandidateRecall.RecallUnavailable;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

/** The HTTP edge to search-service: the URL it builds (also under the monolith's /modules/search prefix) and its failures. */
class HttpCandidateRecallTest {

    private HttpServer server;
    private final AtomicReference<String> seenUri = new AtomicReference<>();
    private final AtomicReference<String> seenAuth = new AtomicReference<>();
    private volatile int status = 200;
    private volatile String body = "{}";

    @BeforeEach
    void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            seenUri.set(exchange.getRequestURI().toString());
            seenAuth.set(exchange.getRequestHeaders().getFirst("Authorization"));
            byte[] out = body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(status, out.length);
            exchange.getResponseBody().write(out);
            exchange.close();
        });
        server.start();
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    private CandidateRecall recall(String basePath, Duration timeout) {
        return new HttpCandidateRecall(RestClient.builder(), "http://127.0.0.1:" + server.getAddress().getPort() + basePath,
                timeout, "us");
    }

    @Test
    void asksThePublicSearchEndpointWithoutATokenAndReadsTheAtins() {
        body = "{\"hits\":[{\"atin\":\"AT00000001\"},{\"atin\":\"\"},{\"atin\":\"AT00000002\"}]}";

        List<String> atins = recall("", Duration.ofSeconds(2)).recall("ground egusi", 30);

        assertThat(atins).containsExactly("AT00000001", "AT00000002");
        assertThat(seenUri.get()).startsWith("/v1/search?").contains("q=ground%20egusi", "market=us", "size=30", "track=false");
        assertThat(seenAuth.get()).isNull();
    }

    @Test
    void keepsTheModulePrefixOfTheMonolith() {
        body = "{\"hits\":[]}";

        recall("/modules/search", Duration.ofSeconds(2)).recall("ground egusi", 30);

        assertThat(seenUri.get()).startsWith("/modules/search/v1/search?");
    }

    @Test
    void anErrorStatusIsUnavailable() {
        status = 503;

        assertThatThrownBy(() -> recall("", Duration.ofSeconds(2)).recall("ground egusi", 30))
                .isInstanceOf(RecallUnavailable.class);
    }

    @Test
    void aNotFoundIsUnavailable() {
        status = 404;

        assertThatThrownBy(() -> recall("/nowhere", Duration.ofSeconds(2)).recall("ground egusi", 30))
                .isInstanceOf(RecallUnavailable.class);
    }

    @Test
    void nothingListeningIsUnavailable() {
        server.stop(0);

        assertThatThrownBy(() -> recall("", Duration.ofMillis(500)).recall("ground egusi", 30))
                .isInstanceOf(RecallUnavailable.class);
    }
}
