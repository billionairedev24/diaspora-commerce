package com.afrotransact.mcp.it;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.client.WireMock;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.client.transport.HttpClientStreamableHttpTransport;
import io.modelcontextprotocol.spec.McpError;
import io.modelcontextprotocol.spec.McpSchema;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.kafka.KafkaContainer;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * End to end through the MCP endpoint with the official MCP Java client (Streamable HTTP): tool registration per scope,
 * a tool call against WireMock-backed platform APIs, permission filtering, global (Valkey) rate limiting and the audit
 * event on Kafka.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
    "at.mcp.rate-limit.capacity=5",
    "at.mcp.rate-limit.refill-per-minute=1"
})
@ActiveProfiles("local")
@Import(TestActors.class)
class McpServerIT {

    static final WireMockServer WIREMOCK = new WireMockServer(WireMockConfiguration.options().dynamicPort());
    static final KafkaContainer KAFKA = ItContainers.KAFKA;

    static {
        WIREMOCK.start();
    }

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.kafka.bootstrap-servers", KAFKA::getBootstrapServers);
        registry.add("spring.data.redis.url", ItContainers::valkeyUrl);
        String base = "http://localhost:" + WIREMOCK.port();
        for (String svc : List.of("search", "catalog", "cart", "inventory", "geo", "promise", "orders", "seller",
                "payments", "bookings")) {
            registry.add("at.mcp.downstream." + svc + "-url", () -> base);
        }
    }

    @LocalServerPort
    int port;

    JsonMapper json;

    private final List<McpSyncClient> clients = new ArrayList<>();

    @BeforeEach
    void wire(@Autowired JsonMapper jsonMapper) {
        this.json = jsonMapper;
        WireMock.configureFor("localhost", WIREMOCK.port());
    }

    @AfterEach
    void close() {
        clients.forEach(McpSyncClient::closeGracefully);
        TestActors.reset();
    }

    @Test
    void listsOnlyTheToolsTheCallersScopesAllow() {
        TestActors.as(TestActors.shopper(unique("shopper")));
        assertThat(toolNames(client("agent-a"))).containsExactlyInAnyOrder("search_products", "get_product",
                "check_delivery", "get_cart", "propose_add_to_cart", "track_order", "start_return_proposal",
                "find_service");

        TestActors.as(TestActors.seller(unique("seller"), "org_kente"));
        assertThat(toolNames(client("agent-b"))).hasSize(14).contains("list_my_products", "update_shipment_status",
                "check_inventory", "request_category").doesNotContain("order_payment_split");

        TestActors.as(TestActors.reviewer(unique("reviewer")));
        assertThat(toolNames(client("agent-c"))).containsExactlyInAnyOrder("applications_queue",
                "application_detail");
    }

    @Test
    void toolSchemasAreAdvertised() {
        TestActors.as(TestActors.shopper(unique("shopper")));
        McpSchema.Tool search = client("agent-d").listTools().tools().stream()
                .filter(t -> t.name().equals("search_products")).findFirst().orElseThrow();
        assertThat(search.description()).contains("never invent products");
        assertThat(search.inputSchema().toString()).contains("query", "maxPriceMinor");
        assertThat(String.valueOf(search.inputSchema().get("required"))).contains("query")
                .doesNotContain("maxPriceMinor");
    }

    @Test
    void callsAToolEndToEndRelayingTheCallersToken() {
        String subject = unique("shopper");
        TestActors.as(TestActors.shopper(subject));
        WireMock.stubFor(WireMock.get(WireMock.urlPathEqualTo("/v1/search"))
                .withQueryParam("q", WireMock.equalTo("egusi"))
                .willReturn(WireMock.okJson("""
                        {"hits":[{"atin":"ATEGUSI001","title":"Ground egusi 500g","priceMinor":899,"currency":"USD",
                                  "inStock":true,"sellerName":"AfroTransact Retail"}],
                         "total":1,"page":0,"size":10,"facets":{}}""")));

        McpSchema.CallToolResult result = client("agent-e").callTool(McpSchema.CallToolRequest.builder()
                .name("search_products").arguments(Map.of("query", "egusi", "size", 5)).build());

        assertThat(result.isError()).isNotEqualTo(Boolean.TRUE);
        JsonNode body = json.readTree(((McpSchema.TextContent) result.content().getFirst()).text());
        assertThat(body.path("status").asString()).isEqualTo("OK");
        assertThat(body.path("data").path("hits").path(0).path("atin").asString()).isEqualTo("ATEGUSI001");
        WireMock.verify(WireMock.getRequestedFor(WireMock.urlPathEqualTo("/v1/search"))
                .withQueryParam("q", WireMock.equalTo("egusi"))
                .withQueryParam("size", WireMock.equalTo("5"))
                .withQueryParam("inStock", WireMock.equalTo("true"))
                .withHeader("Authorization", WireMock.equalTo("Bearer agent-token-" + "agent-e")));

        ConsumerRecord<String, String> audit = auditFor(subject, "search_products");
        JsonNode event = json.readTree(audit.value());
        assertThat(event.path("outcome").asString()).isEqualTo("ok");
        assertThat(event.path("toolset").asString()).isEqualTo("SHOPPER");
        assertThat(event.path("clientId").asString()).isEqualTo("agent-e");
        assertThat(event.path("argumentsHash").asString()).startsWith("sha256:");
        assertThat(audit.value()).doesNotContain("egusi");
        assertThat(new String(audit.headers().lastHeader("event-type").value(), StandardCharsets.UTF_8))
                .isEqualTo("mcp.tool.invoked");
    }

    @Test
    void proposalsNeverPerformTheAction() {
        TestActors.as(TestActors.shopper(unique("shopper")));
        WireMock.stubFor(WireMock.get("/v1/public/variants/ATPALM0001").willReturn(WireMock.okJson("""
                {"product":{"atin":"ATPALM0001","title":"Red palm oil 1L"},
                 "variant":{"atin":"ATPALM0001","priceMinor":1299,"currency":"USD"}}""")));

        McpSchema.CallToolResult result = client("agent-f").callTool(McpSchema.CallToolRequest.builder()
                .name("propose_add_to_cart").arguments(Map.of("atin", "atpalm0001", "quantity", 2)).build());

        JsonNode body = json.readTree(((McpSchema.TextContent) result.content().getFirst()).text());
        assertThat(body.path("status").asString()).isEqualTo("PROPOSED");
        assertThat(body.path("data").path("requiresConfirmation").asBoolean()).isTrue();
        assertThat(body.path("data").path("apiCall").path("path").asString()).isEqualTo("/v1/cart/items");
        WireMock.verify(0, WireMock.postRequestedFor(WireMock.urlPathMatching("/v1/cart.*")));
    }

    @Test
    void toolsOutsideTheCallersScopesAreRefusedAndAudited() {
        String subject = unique("shopper");
        TestActors.as(TestActors.shopper(subject));
        McpSyncClient client = client("agent-g");

        assertThatThrownBy(() -> client.callTool(McpSchema.CallToolRequest.builder().name("order_payment_split")
                .arguments(Map.of("orderId", "AT-1045")).build()))
                .isInstanceOf(McpError.class)
                .satisfies(e -> {
                    McpError error = (McpError) e;
                    assertThat(error.getJsonRpcError().code()).isEqualTo(-32003);
                    assertThat(error.getMessage()).contains("requires scope mcp:staff");
                });
        WireMock.verify(0, WireMock.getRequestedFor(WireMock.urlPathMatching("/v1/orders/.*")));
        assertThat(json.readTree(auditFor(subject, "order_payment_split").value()).path("outcome").asString())
                .isEqualTo("denied");
    }

    @Test
    void rateLimitsEachClientAndSubject() {
        String subject = unique("busy-shopper");
        TestActors.as(TestActors.shopper(subject));
        WireMock.stubFor(WireMock.get("/v1/cart").willReturn(WireMock.okJson("{\"packages\":[],\"itemCount\":0}")));
        McpSyncClient client = client("agent-h");
        for (int i = 0; i < 5; i++) {
            assertThat(client.callTool(McpSchema.CallToolRequest.builder().name("get_cart").arguments(Map.of())
                    .build()).isError()).isNotEqualTo(Boolean.TRUE);
        }
        assertThatThrownBy(() -> client.callTool(McpSchema.CallToolRequest.builder().name("get_cart")
                .arguments(Map.of()).build()))
                .isInstanceOf(McpError.class)
                .satisfies(e -> assertThat(((McpError) e).getJsonRpcError().code()).isEqualTo(-32029));
        // another agent of the same user has its own bucket
        assertThat(client("agent-i").callTool(McpSchema.CallToolRequest.builder().name("get_cart")
                .arguments(Map.of()).build()).isError()).isNotEqualTo(Boolean.TRUE);
    }

    @Test
    void restCatalogueListsEveryToolWithItsScope() throws Exception {
        HttpResponse<String> response = HttpClient.newHttpClient().send(HttpRequest.newBuilder(
                URI.create("http://localhost:" + port + "/v1/tools")).GET().build(),
                HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).isEqualTo(200);
        JsonNode tools = json.readTree(response.body());
        assertThat(tools.size()).isEqualTo(18);
        JsonNode update = tools.valueStream().filter(t -> t.path("name").asString().equals("update_shipment_status"))
                .findFirst().orElseThrow();
        assertThat(update.path("requiredScope").asString()).isEqualTo("mcp:seller");
        assertThat(update.path("requiredPermission").asString()).isEqualTo("orders:update-status");
        assertThat(update.path("readOnly").asBoolean()).isFalse();
        assertThat(update.path("inputSchema").path("properties").has("trackingNumber")).isTrue();
    }

    private McpSyncClient client(String clientId) {
        HttpClientStreamableHttpTransport transport = HttpClientStreamableHttpTransport
                .builder("http://localhost:" + port)
                .endpoint("/mcp")
                .requestBuilder(HttpRequest.newBuilder()
                        .header("X-MCP-Client", clientId)
                        .header("Authorization", "Bearer agent-token-" + clientId))
                .build();
        McpSyncClient client = McpClient.sync(transport).requestTimeout(Duration.ofSeconds(20)).build();
        client.initialize();
        clients.add(client);
        return client;
    }

    private static List<String> toolNames(McpSyncClient client) {
        return client.listTools().tools().stream().map(McpSchema.Tool::name).toList();
    }

    private ConsumerRecord<String, String> auditFor(String subject, String tool) {
        Properties props = new Properties();
        props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, KAFKA.getBootstrapServers());
        props.put(ConsumerConfig.GROUP_ID_CONFIG, "audit-test-" + UUID.randomUUID());
        props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        props.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
        props.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
        try (KafkaConsumer<String, String> consumer = new KafkaConsumer<>(props)) {
            consumer.subscribe(List.of("at.mcp-tool.events"));
            long deadline = System.currentTimeMillis() + 30_000;
            while (System.currentTimeMillis() < deadline) {
                for (ConsumerRecord<String, String> rec : consumer.poll(Duration.ofMillis(500))) {
                    if (subject.equals(rec.key()) && rec.value().contains("\"tool\":\"" + tool + "\"")) {
                        return rec;
                    }
                }
            }
        }
        throw new AssertionError("No mcp.tool.invoked event for " + subject + " / " + tool);
    }

    private static String unique(String prefix) {
        return prefix + "-" + UUID.randomUUID().toString().substring(0, 8);
    }
}
