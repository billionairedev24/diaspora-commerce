package com.afrotransact.inventory.it;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import com.afrotransact.inventory.application.ReceiptResult;
import com.afrotransact.inventory.application.ReservationService;
import com.afrotransact.inventory.application.ReserveCommand;
import com.afrotransact.inventory.domain.Lot;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** S066: the staff inventory hub overview (value, expiry buckets, nodes, alerts) and lot recall traces. */
class InventoryHubIT extends InventoryIntegrationTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final HttpClient HTTP = HttpClient.newHttpClient();

    private int port;
    private ReservationService reservations;

    @BeforeEach
    void wire(@LocalServerPort int serverPort, @Autowired ReservationService reservationService) {
        this.port = serverPort;
        this.reservations = reservationService;
    }

    @Test
    void overviewShowsValueExpiryNodesAndAlertsAndALotCanBeTracedToItsOrders() throws Exception {
        String sku = sku("HUB-JOL");
        ReceiptResult received = stock(sku, 40);
        Lot lot = received.lots().getFirst();
        // The lot now expires in 20 days (node-local): picked first, flagged for markdown.
        jdbc.sql("update lot set exp_date = :d where id = :id").param("d", today().plusDays(20))
                .param("id", lot.id()).update();

        String orderId = "ORD-" + UUID.randomUUID();
        TestActors.as(TestActors.ORDERS_SERVICE);
        reservations.reserve(new ReserveCommand(orderId, "hub-" + orderId, List.of(
                new ReserveCommand.Line(sku, NODE, 3, "line-1"))));
        reservations.confirm(orderId);
        reservations.commit(orderId);

        TestActors.as(TestActors.staff("inventory:read"));
        JsonNode overview = get("/v1/overview?nodeId=" + NODE);
        List<String> statuses = new ArrayList<>();
        overview.path("byStatus").forEach(s -> statuses.add(s.path("status").asString()));
        assertThat(statuses).contains("SELLABLE");
        assertThat(overview.path("byStatus").get(statuses.indexOf("SELLABLE")).path("valueMinor").asLong())
                .isPositive();
        JsonNode soon = null;
        for (JsonNode b : overview.path("expiry")) {
            if ("D0_30".equals(b.path("bucket").asString())) {
                soon = b;
            }
        }
        assertThat(soon).isNotNull();
        assertThat(soon.path("units").asLong()).isGreaterThanOrEqualTo(37);
        assertThat(overview.path("nodes")).hasSize(1);
        assertThat(overview.path("nodes").get(0).path("nodeId").asString()).isEqualTo(NODE);
        List<String> alerts = new ArrayList<>();
        overview.path("alerts").forEach(a -> alerts.add(a.path("type").asString() + ":" + a.path("lotNo")
                .asString("")));
        assertThat(alerts).contains("LOT_EXPIRING:" + lot.lotNo());

        JsonNode trace = get("/v1/lots/" + lot.id() + "/trace");
        assertThat(trace.path("lot").path("lotNo").asString()).isEqualTo(lot.lotNo());
        List<String> moves = new ArrayList<>();
        trace.path("movements").forEach(m -> moves.add(m.path("type").asString()));
        assertThat(moves).contains("RECEIVE", "RESERVE", "SHIP");
        assertThat(trace.path("orders")).hasSize(1);
        assertThat(trace.path("orders").get(0).path("orderId").asString()).isEqualTo(orderId);
        assertThat(trace.path("orders").get(0).path("qty").asInt()).isEqualTo(3);

        TestActors.as(TestActors.seller("org_not_the_owner", "inventory:read"));
        HttpResponse<String> hidden = send("/v1/lots/" + lot.id() + "/trace");
        assertThat(hidden.statusCode()).as("other organisations' lots are invisible").isEqualTo(404);
        assertThat(get("/v1/overview").path("byStatus")).as("sellers see only their own stock").isEmpty();
        TestActors.as(TestActors.seller("org_not_the_owner"));
        assertThat(send("/v1/overview").statusCode()).as("inventory access only").isEqualTo(403);
    }

    private JsonNode get(String path) throws Exception {
        HttpResponse<String> r = send(path);
        assertThat(r.statusCode()).as(r.body()).isEqualTo(200);
        return JSON.readTree(r.body());
    }

    private HttpResponse<String> send(String path) throws Exception {
        return HTTP.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + path)).GET().build(),
                HttpResponse.BodyHandlers.ofString());
    }
}
