package com.afrotransact.mcp.config;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class McpExposureGuardTest {

    @Test
    void acceptsASecureHttpsDeployment() {
        assertThat(McpExposureGuard.problems(true, "https://mcp.afrotransact.com", "https://auth.afrotransact.com"))
                .isEmpty();
    }

    @Test
    void refusesToRunWithoutSecurity() {
        assertThat(McpExposureGuard.problems(false, "https://mcp.afrotransact.com", "https://auth.afrotransact.com"))
                .singleElement().asString().contains("at.security.enabled");
    }

    @Test
    void refusesLocalOrPlainHttpAddresses() {
        assertThat(McpExposureGuard.problems(true, "http://localhost:8114", "https://auth.afrotransact.com"))
                .singleElement().asString().contains("MCP_PUBLIC_URL");
        assertThat(McpExposureGuard.problems(true, "https://mcp.afrotransact.com", "http://localhost:8100"))
                .singleElement().asString().contains("AUTH_ISSUER_URL");
        assertThat(McpExposureGuard.problems(true, null, null)).hasSize(2);
    }

    @Test
    void acceptsTheInClusterServiceUrlWhenAuthIsOn() {
        assertThat(McpExposureGuard.problems(true, "http://mcp-server.afrotransact-prod.svc.cluster.local",
                "https://auth.afrotransact.com")).isEmpty();
        assertThat(McpExposureGuard.problems(true, "http://mcp-server:80", "https://auth.afrotransact.com")).isEmpty();
    }

    @Test
    void inClusterUrlNeverMakesAnUnauthenticatedServerAcceptable() {
        assertThat(McpExposureGuard.problems(false, "http://mcp-server.afrotransact-prod.svc.cluster.local",
                "https://auth.afrotransact.com")).singleElement().asString().contains("at.security.enabled");
    }

    @Test
    void refusesPlainHttpToAnythingThatIsNotClusterInternal() {
        assertThat(McpExposureGuard.problems(true, "http://mcp.example.com", "https://auth.afrotransact.com"))
                .singleElement().asString().contains("MCP_PUBLIC_URL");
        assertThat(McpExposureGuard.problems(true, "http://10.0.0.5:8080", "https://auth.afrotransact.com")).hasSize(1);
        assertThat(McpExposureGuard.problems(true, "http://localhost", "https://auth.afrotransact.com")).hasSize(1);
    }
}
