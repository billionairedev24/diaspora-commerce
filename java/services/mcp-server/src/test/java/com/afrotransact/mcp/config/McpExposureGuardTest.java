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
}
