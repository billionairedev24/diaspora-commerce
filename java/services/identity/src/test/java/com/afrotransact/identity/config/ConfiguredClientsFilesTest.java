package com.afrotransact.identity.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.EnumerablePropertySource;
import org.springframework.core.env.PropertySource;
import org.springframework.core.io.ClassPathResource;

/** F15: what the shipped YAML files say about the public mcp-client and the fail-closed defaults. */
class ConfiguredClientsFilesTest {

    private static Map<String, Object> load(String file) throws IOException {
        List<PropertySource<?>> sources = new YamlPropertySourceLoader().load(file, new ClassPathResource(file));
        EnumerablePropertySource<?> p = (EnumerablePropertySource<?>) sources.get(0);
        Map<String, Object> out = new LinkedHashMap<>();
        for (String name : p.getPropertyNames()) {
            out.put(name, p.getProperty(name));
        }
        return out;
    }

    private static List<String> scopesOf(Map<String, Object> props, String clientId) {
        for (int i = 0; i < 100; i++) {
            if (clientId.equals(props.get("at.identity.clients[" + i + "].client-id"))) {
                List<String> scopes = new ArrayList<>();
                for (int j = 0; props.containsKey("at.identity.clients[" + i + "].scopes[" + j + "]"); j++) {
                    scopes.add(String.valueOf(props.get("at.identity.clients[" + i + "].scopes[" + j + "]")));
                }
                return scopes;
            }
        }
        throw new AssertionError("no client " + clientId);
    }

    @Test
    void thePublicMcpClientCannotRequestStaffToolsOnProdAndUat() throws IOException {
        for (String file : List.of("application-prod.yml", "application-uat.yml")) {
            assertThat(scopesOf(load(file), "mcp-client")).as(file).contains("mcp:shopper", "mcp:seller")
                    .doesNotContain("mcp:staff");
        }
    }

    @Test
    void localAndDevKeepTheStaffToolsForDevelopers() throws IOException {
        assertThat(scopesOf(load("application-local.yml"), "mcp-client")).contains("mcp:staff");
    }

    @Test
    void reconcileDefaultsToReportAndSecretRotationToOff() throws IOException {
        Map<String, Object> base = load("application.yml");
        assertThat(String.valueOf(base.get("at.identity.provisioning.reconcile"))).contains("report");
        assertThat(String.valueOf(base.get("at.identity.provisioning.rotate-secrets"))).contains("false");
    }

    @Test
    void theBaseFileHoldsNoLocalDatabasePassword() throws IOException {
        assertThat(String.valueOf(load("application.yml").get("spring.datasource.password"))).isEqualTo("${DB_PASSWORD}");
        assertThat(String.valueOf(load("application-local.yml").get("spring.datasource.password")))
                .isEqualTo("${DB_PASSWORD:identity}");
    }
}
