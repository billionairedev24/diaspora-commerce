package com.afrotransact.trustops.it;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Arrays;
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
 * One PostgreSQL container (local postgis image), one in-JVM WireMock standing in for identity's token endpoint,
 * catalog, payments, seller, offers and ai-gateway, and one Spring context shared by every integration test.
 * Kafka, the outbox relay and the jobs are off: listeners and jobs are called directly.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
    "at.events.relay-enabled=false",
    "at.consumers.enabled=false",
    "spring.kafka.bootstrap-servers=localhost:1",
    "spring.kafka.listener.auto-startup=false",
    "at.trustops.jobs.enabled=false",
    "at.trustops.client.client-secret=it-secret",
    "spring.datasource.hikari.maximum-pool-size=20"
})
@ActiveProfiles("local")
@Import(TestSupport.class)
public abstract class TrustOpsIntegrationTest {

    protected static final PostgreSQLContainer POSTGRES = PostgresTestContainer.create();
    protected static final WireMockServer UPSTREAM = new WireMockServer(WireMockConfiguration.options()
            .dynamicPort().globalTemplating(true));
    protected static final JsonMapper JSON = JsonMapper.builder().build();
    private static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();

    static {
        POSTGRES.start();
        UPSTREAM.start();
    }

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("at.trustops.client.token-uri", () -> UPSTREAM.baseUrl() + "/oauth2/token");
        registry.add("at.trustops.catalog.base-url", UPSTREAM::baseUrl);
        registry.add("at.trustops.payments.base-url", UPSTREAM::baseUrl);
        registry.add("at.trustops.seller.base-url", UPSTREAM::baseUrl);
        registry.add("at.trustops.offers.base-url", UPSTREAM::baseUrl);
        registry.add("at.trustops.ai.base-url", UPSTREAM::baseUrl);
    }

    protected JdbcClient jdbc;
    protected int port;

    @BeforeEach
    void wireBase(@Autowired JdbcClient jdbcClient, @LocalServerPort int serverPort) {
        this.jdbc = jdbcClient;
        this.port = serverPort;
        stubUpstreams();
    }

    @AfterEach
    void resetActorAndClock() {
        TestSupport.reset();
    }

    /** Happy-path stubs, fresh for each test. */
    protected static void stubUpstreams() {
        UPSTREAM.resetAll();
        UPSTREAM.stubFor(WireMock.post(WireMock.urlEqualTo("/oauth2/token"))
                .willReturn(WireMock.okJson("{\"access_token\":\"tok-trust-ops\",\"token_type\":\"Bearer\","
                        + "\"expires_in\":300}")));
        UPSTREAM.stubFor(WireMock.post(WireMock.urlPathMatching("/v1/products/[^/]+/(approve|reject|takedown)"))
                .willReturn(WireMock.okJson("{\"id\":\"{{request.pathSegments.[2]}}\"}")));
        UPSTREAM.stubFor(WireMock.post(WireMock.urlEqualTo("/v1/ai/triage"))
                .willReturn(WireMock.okJson("""
                        {"subjectType":"LISTING","subjectId":"x","summary":"Title claims to cure diabetes",
                         "riskLevel":"HIGH","flags":[{"code":"HEALTH_CLAIM","description":"Unapproved health claim",
                         "citedFields":["title"]}],"decision":"HUMAN_REVIEW_REQUIRED","model":"triage-model"}
                        """)));
        UPSTREAM.stubFor(WireMock.post(WireMock.urlPathMatching("/v1/payments/[^/]+/refunds"))
                .willReturn(WireMock.okJson("{\"id\":\"{{randomValue type='UUID'}}\",\"amountMinor\":"
                        + "{{jsonPath request.body '$.lines[0].itemsMinor'}},\"status\":\"SUCCEEDED\","
                        + "\"replayed\":false}")));
        UPSTREAM.stubFor(WireMock.post(WireMock.urlPathMatching("/v1/orgs/[^/]+/(suspend|reinstate)"))
                .willReturn(WireMock.okJson("{\"orgId\":\"{{request.pathSegments.[2]}}\"}")));
        UPSTREAM.stubFor(WireMock.get(WireMock.urlPathMatching("/v1/sellers/[^/]+/standing"))
                .willReturn(WireMock.okJson("{\"rating\":4.6}")));
        UPSTREAM.stubFor(WireMock.put(WireMock.urlPathMatching("/v1/sellers/[^/]+/performance"))
                .willReturn(WireMock.okJson("{}")));
    }

    /** Organisation id in identity's format. */
    protected static String orgId() {
        return "org_" + UUID.randomUUID().toString().replace("-", "").substring(0, 20);
    }

    protected HttpResponse<String> send(String method, String path, Object body) {
        try {
            HttpRequest.Builder b = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                    .timeout(Duration.ofSeconds(60)).header("Content-Type", "application/json");
            b.method(method, body == null ? HttpRequest.BodyPublishers.noBody()
                    : HttpRequest.BodyPublishers.ofString(JSON.writeValueAsString(body)));
            return HTTP.send(b.build(), HttpResponse.BodyHandlers.ofString());
        } catch (IOException e) {
            throw new IllegalStateException(e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }

    protected JsonNode ok(HttpResponse<String> response, int... statuses) {
        assertThat(statuses.length == 0 ? List.of(200, 201).contains(response.statusCode())
                : Arrays.stream(statuses).anyMatch(s -> s == response.statusCode()))
                .as("HTTP %s: %s", response.statusCode(), response.body()).isTrue();
        return response.body().isEmpty() ? JSON.createObjectNode() : JSON.readTree(response.body());
    }

    protected JsonNode problem(HttpResponse<String> response, int status, String code) {
        assertThat(response.statusCode()).as(response.body()).isEqualTo(status);
        JsonNode p = JSON.readTree(response.body());
        assertThat(p.path("code").asString()).isEqualTo(code);
        return p;
    }

    /** Types of the outbox events written for an aggregate id, oldest first. */
    protected List<String> outbox(String aggregateId) {
        return jdbc.sql("select type from platform_outbox where aggregate_id = :id order by occurred_at, id")
                .param("id", aggregateId).query(String.class).list();
    }
}
