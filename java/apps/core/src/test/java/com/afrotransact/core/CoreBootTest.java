package com.afrotransact.core;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.HashMap;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;

/**
 * Boots afrotransact-core with the modules on a real Postgres: {@code AT_TEST_PG_ADMIN_URL} (+ {@code AT_TEST_PG_ADMIN_USER},
 * {@code AT_TEST_PG_ADMIN_PASSWORD}) names a throw-away database with {@code btree_gist} (CI's Postgres service container, or a
 * local one). Orders (needs Valkey and Temporal), fulfilment (needs PostGIS), ai-gateway and mcp-server are off unless
 * {@code AT_TEST_CORE_FULL=true}. Checks: the context starts, every mounted module is ready, and the shared security chain keeps the
 * anonymous seller storefront and content permits while everything else needs a token.
 */
@EnabledIfEnvironmentVariable(named = "AT_TEST_PG_ADMIN_URL", matches = ".+")
class CoreBootTest {

    @Test
    void startsAndEveryMountedModuleIsReadyWithTheSharedSecurityChain() throws Exception {
        String url = System.getenv("AT_TEST_PG_ADMIN_URL");
        boolean full = "true".equals(System.getenv("AT_TEST_CORE_FULL"));
        Map<String, Object> properties = new HashMap<>(Map.of(
                "server.port", "0",
                "spring.profiles.active", "local",
                "at.security.enabled", "true",
                "at.consumers.enabled", "false",
                "CORE_DB_URL", url,
                "at.modules.schema-admin.url", url,
                "at.modules.schema-admin.username", System.getenv("AT_TEST_PG_ADMIN_USER"),
                "at.modules.schema-admin.password", System.getenv("AT_TEST_PG_ADMIN_PASSWORD")));
        if (!full) {
            for (String off : new String[] {"orders", "fulfilment", "ai-gateway", "mcp-server"}) {
                properties.put("at.modules." + off + ".enabled", "false");
            }
        }
        // Command-line arguments: highest precedence, so they win over the local profile's defaults.
        String[] args = properties.entrySet().stream().map(e -> "--" + e.getKey() + "=" + e.getValue())
                .toArray(String[]::new);
        try (ConfigurableApplicationContext context = new SpringApplicationBuilder(CoreApplication.class).run(args)) {
            int port = Integer.parseInt(context.getEnvironment().getProperty("local.server.port"));
            HttpClient client = HttpClient.newHttpClient();
            assertThat(status(client, "GET", port, "/actuator/health/readiness")).isEqualTo(200);
            assertThat(status(client, "GET", port, "/v1/public/storefronts")).isEqualTo(200);
            assertThat(status(client, "GET", port, "/v1/offers")).isEqualTo(401);
            assertThat(status(client, "POST", port, "/v1/webhooks/stripe-connect")).isNotEqualTo(401);
        }
    }

    private static int status(HttpClient client, String method, int port, String path) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                .header("Content-Type", "application/json").method(method, HttpRequest.BodyPublishers.ofString("{}")).build();
        return client.send(request, HttpResponse.BodyHandlers.discarding()).statusCode();
    }
}
