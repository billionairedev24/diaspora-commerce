package com.afrotransact.content.it;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

import com.afrotransact.platform.test.PostgresTestContainer;
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
 * One PostgreSQL container and one Spring context (random port) shared by every integration test class.
 * The placement lifecycle job is off; caches are invalidated by writes on this replica.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
    "at.events.relay-enabled=false",
    "spring.kafka.bootstrap-servers=localhost:1",
    "at.consumers.enabled=false",
    "at.content.jobs.enabled=false",
    "at.content.newsletter.token-key=integration-test-newsletter-key-0123456789abcdef"
})
@ActiveProfiles("local")
@Import(TestActors.class)
abstract class ContentIntegrationTest {

    static final PostgreSQLContainer POSTGRES = PostgresTestContainer.create();
    static final JsonMapper JSON = JsonMapper.builder().build();
    private static final HttpClient HTTP = HttpClient.newHttpClient();

    /** Set CONTENT_IT_JDBC_URL (with CONTENT_IT_DB_USER / CONTENT_IT_DB_PASSWORD) to run on an existing empty PostgreSQL instead of a container. */
    private static final String LOCAL_URL = System.getenv("CONTENT_IT_JDBC_URL");

    static {
        if (LOCAL_URL == null) {
            POSTGRES.start();
        }
    }

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry registry) {
        if (LOCAL_URL != null) {
            registry.add("spring.datasource.url", () -> LOCAL_URL);
            registry.add("spring.datasource.username", () -> System.getenv().getOrDefault("CONTENT_IT_DB_USER", "postgres"));
            registry.add("spring.datasource.password", () -> System.getenv().getOrDefault("CONTENT_IT_DB_PASSWORD", ""));
            return;
        }
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }

    JdbcClient jdbc;
    int port;

    @BeforeEach
    void wireBase(@Autowired JdbcClient jdbcClient, @LocalServerPort int serverPort) {
        this.jdbc = jdbcClient;
        this.port = serverPort;
    }

    @AfterEach
    void resetActor() {
        TestActors.reset();
    }

    HttpResponse<String> get(String path, String... headers) {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path)).GET();
        if (headers.length > 0) {
            b.headers(headers);
        }
        return send(b.build());
    }

    HttpResponse<String> post(String path, String json, String... headers) {
        return send(withBody(path, "POST", json, headers));
    }

    HttpResponse<String> put(String path, String json) {
        return send(withBody(path, "PUT", json));
    }

    HttpResponse<String> patch(String path, String json) {
        return send(withBody(path, "PATCH", json));
    }

    HttpResponse<String> patchWith(String path, String json, String... headers) {
        return send(withBody(path, "PATCH", json, headers));
    }

    HttpResponse<String> delete(String path, String... headers) {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path)).DELETE();
        if (headers.length > 0) {
            b.headers(headers);
        }
        return send(b.build());
    }

    private HttpRequest withBody(String path, String method, String json, String... headers) {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                .header("Content-Type", "application/json")
                .method(method, HttpRequest.BodyPublishers.ofString(json));
        if (headers.length > 0) {
            b.headers(headers);
        }
        return b.build();
    }

    private static HttpResponse<String> send(HttpRequest request) {
        try {
            return HTTP.send(request, HttpResponse.BodyHandlers.ofString());
        } catch (IOException ex) {
            throw new IllegalStateException(ex);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(ex);
        }
    }

    static JsonNode json(HttpResponse<String> response) {
        return JSON.readTree(response.body());
    }

    long events(String type, String aggregateId) {
        return jdbc.sql("select count(*) from platform_outbox where type = :type and aggregate_id = :id")
                .param("type", type).param("id", aggregateId).query(Long.class).single();
    }

    JsonNode lastEvent(String type) {
        return JSON.readTree(jdbc.sql("select payload::text from platform_outbox where type = :type order by occurred_at desc limit 1")
                .param("type", type).query(String.class).single());
    }
}
