package com.afrotransact.ai.it;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import com.afrotransact.platform.test.PostgresTestContainer;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.client.WireMock;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * One PostgreSQL container, one in-JVM WireMock (OpenRouter + downstream platform APIs) and one Spring context shared
 * by every integration test. Each test uses unique phrases so stubs never collide.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
    "at.events.relay-enabled=false",
    "spring.kafka.bootstrap-servers=localhost:1"
})
@ActiveProfiles({"local", "it"})
@Import(TestActors.class)
abstract class AiGatewayIntegrationTest {

    static final PostgreSQLContainer POSTGRES = PostgresTestContainer.create();
    static final WireMockServer WIREMOCK = new WireMockServer(WireMockConfiguration.options().dynamicPort());

    static {
        POSTGRES.start();
        WIREMOCK.start();
    }

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        String base = "http://localhost:" + WIREMOCK.port();
        registry.add("spring.ai.openai.base-url", () -> base + "/v1");
        for (String svc : List.of("search", "catalog", "offers", "promise", "orders", "bookings")) {
            registry.add("at.ai.downstream." + svc + "-url", () -> base);
        }
    }

    @LocalServerPort
    int port;

    JdbcClient jdbc;
    JsonMapper json;
    final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();

    @BeforeEach
    void wireBase(@Autowired JdbcClient jdbcClient, @Autowired JsonMapper jsonMapper) {
        this.jdbc = jdbcClient;
        this.json = jsonMapper;
        WireMock.configureFor("localhost", WIREMOCK.port());
    }

    @AfterEach
    void resetActor() {
        TestActors.reset();
    }

    static String unique(String prefix) {
        return prefix + "-" + UUID.randomUUID().toString().substring(0, 8);
    }

    HttpResponse<String> postJson(String path, Object body, String accept) throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                .timeout(Duration.ofSeconds(60))
                .header("Content-Type", "application/json")
                .header("Accept", accept)
                .header("Authorization", "Bearer shopper-token-123")
                .POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body)))
                .build();
        return http.send(request, HttpResponse.BodyHandlers.ofString());
    }

    JsonNode readJson(HttpResponse<String> response) {
        return json.readTree(response.body());
    }

    /** Parses a text/event-stream body into (event, data) pairs in order. */
    List<SseEvent> parseSse(String body) {
        List<SseEvent> events = new ArrayList<>();
        String name = null;
        StringBuilder data = new StringBuilder();
        for (String line : body.split("\n", -1)) {
            if (line.startsWith("event:")) {
                name = line.substring(6).trim();
            } else if (line.startsWith("data:")) {
                data.append(line.substring(5));
            } else if (line.isEmpty() && name != null) {
                events.add(new SseEvent(name, json.readTree(data.toString())));
                name = null;
                data.setLength(0);
            }
        }
        return events;
    }

    /** One server-sent event. */
    record SseEvent(String name, JsonNode data) {
    }
}
