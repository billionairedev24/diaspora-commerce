package com.afrotransact.mcp.it;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.ActiveProfiles;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** MCP authorization spec: unauthenticated calls get 401 with the RFC 9728 metadata URL; discovery is public. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
    "at.security.enabled=true",
    "spring.security.oauth2.resourceserver.jwt.issuer-uri=http://localhost:8100",
    "at.mcp.public-url=https://mcp.example.test",
    "spring.kafka.bootstrap-servers=localhost:1"
})
@ActiveProfiles("local")
class McpSecurityIT {

    @LocalServerPort
    int port;

    JsonMapper json;

    private final HttpClient http = HttpClient.newHttpClient();

    @BeforeEach
    void wire(@Autowired JsonMapper jsonMapper) {
        this.json = jsonMapper;
    }

    @Test
    void unauthenticatedMcpCallsPointClientsAtTheAuthorizationServer() throws Exception {
        HttpResponse<String> response = http.send(HttpRequest.newBuilder(URI.create(url("/mcp")))
                .header("Content-Type", "application/json")
                .header("Accept", "application/json, text/event-stream")
                .POST(HttpRequest.BodyPublishers.ofString(
                        "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/list\",\"params\":{}}"))
                .build(), HttpResponse.BodyHandlers.ofString());

        assertThat(response.statusCode()).isEqualTo(401);
        assertThat(response.headers().firstValue("WWW-Authenticate")).contains(
                "Bearer resource_metadata=\"https://mcp.example.test/.well-known/oauth-protected-resource\"");
        assertThat(response.body()).doesNotContain("search_products");
    }

    @Test
    void protectedResourceMetadataIsPublic() throws Exception {
        HttpResponse<String> response = http.send(HttpRequest.newBuilder(
                URI.create(url("/.well-known/oauth-protected-resource"))).GET().build(),
                HttpResponse.BodyHandlers.ofString());

        assertThat(response.statusCode()).isEqualTo(200);
        JsonNode metadata = json.readTree(response.body());
        assertThat(metadata.path("resource").asString()).isEqualTo("https://mcp.example.test/mcp");
        assertThat(metadata.path("authorization_servers").path(0).asString()).isEqualTo("http://localhost:8100");
        assertThat(metadata.path("scopes_supported").toString()).contains("mcp:shopper", "mcp:seller", "mcp:staff");
    }

    @Test
    void toolCatalogueIsPublicForTheDeveloperPortal() throws Exception {
        HttpResponse<String> response = http.send(HttpRequest.newBuilder(URI.create(url("/v1/tools"))).GET().build(),
                HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).isEqualTo(200);
    }

    private String url(String path) {
        return "http://localhost:" + port + path;
    }
}
