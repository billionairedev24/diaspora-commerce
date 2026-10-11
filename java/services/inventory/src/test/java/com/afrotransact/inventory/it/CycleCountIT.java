package com.afrotransact.inventory.it;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Locale;
import java.util.UUID;

import com.afrotransact.platform.security.Actor;
import com.github.tomakehurst.wiremock.client.WireMock;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.web.server.LocalServerPort;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * S281 over HTTP: a cycle count snapshots the lots, records counts, refuses to post variances without a reason,
 * posts each variance as an audited ADJUST (keeping units picked meanwhile), and an inbound plan carries the
 * packing-list batch (case pack, lot, made and expiry) that receiving applies when the receiver records none.
 */
class CycleCountIT extends InventoryIntegrationTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final HttpClient HTTP = HttpClient.newHttpClient();
    private static final Actor COUNTER = TestActors.staff("inventory:adjust", "inventory:read");

    private int port;

    @BeforeEach
    void wire(@LocalServerPort int serverPort) {
        this.port = serverPort;
    }

    @Test
    void countsLotsReviewsVariancesAndPostsAdjustmentsWithReasons() throws Exception {
        String sku = sku("CC");
        receive(NODE, line(sku, "LOT-A", 400, 20, 0, 300), line(sku, "LOT-B", 200, 10, 0, 300));
        TestActors.as(COUNTER);

        HttpResponse<String> started = send("POST", "/v1/cycle-counts", null,
                "{\"nodeId\":\"" + NODE + "\",\"sku\":\"" + sku + "\",\"note\":\"Weekly A-class\"}");
        assertThat(started.statusCode()).as(started.body()).isEqualTo(201);
        JsonNode count = JSON.readTree(started.body());
        String id = count.path("id").asString();
        assertThat(count.path("number").asString()).startsWith("CC-");
        assertThat(count.path("status").asString()).isEqualTo("OPEN");
        assertThat(count.path("lines")).hasSize(2);
        int lineA = lineNo(count, "LOT-A");
        int lineB = lineNo(count, "LOT-B");

        // Counted: A is 2 short, B matches. Posting without a reason for A is refused.
        HttpResponse<String> counted = send("PUT", "/v1/cycle-counts/" + id + "/counts", "\"0\"",
                "{\"lines\":[{\"lineNo\":" + lineA + ",\"countedQty\":18},{\"lineNo\":" + lineB
                        + ",\"countedQty\":10}]}");
        assertThat(counted.statusCode()).as(counted.body()).isEqualTo(200);
        JsonNode c = JSON.readTree(counted.body());
        assertThat(c.path("status").asString()).isEqualTo("COUNTED");
        assertThat(c.path("netVariance").asInt()).isEqualTo(-2);
        assertThat(c.path("varianceLines").asInt()).isEqualTo(1);
        HttpResponse<String> refused = send("POST", "/v1/cycle-counts/" + id + "/post", null, null);
        assertThat(refused.statusCode()).as(refused.body()).isEqualTo(422);
        assertThat(refused.body()).contains("inventory.cycle-count-incomplete");

        // A stale version is a conflict.
        assertThat(send("PUT", "/v1/cycle-counts/" + id + "/counts", "\"0\"",
                "{\"lines\":[{\"lineNo\":" + lineA + ",\"countedQty\":18,\"reasonCode\":\"LOST\"}]}").statusCode())
                .isEqualTo(409);
        HttpResponse<String> reasoned = send("PUT", "/v1/cycle-counts/" + id + "/counts", null,
                "{\"lines\":[{\"lineNo\":" + lineA + ",\"countedQty\":18,\"reasonCode\":\"LOST\","
                        + "\"note\":\"Torn case\"}]}");
        assertThat(reasoned.statusCode()).as(reasoned.body()).isEqualTo(200);

        // Meanwhile 3 units of lot A were written off: posting keeps that and applies −2 on top.
        TestActors.reset();
        String lotA = jdbc.sql("select id::text from lot where sku = :sku and lot_no = 'LOT-A'").param("sku", sku)
                .query(String.class).single();
        assertThat(send("POST", "/v1/lots/" + lotA + "/write-off", null, "{\"qty\":3,\"reason\":\"Crushed\"}")
                .statusCode()).isEqualTo(200);
        TestActors.as(COUNTER);

        HttpResponse<String> posted = send("POST", "/v1/cycle-counts/" + id + "/post", null, null);
        assertThat(posted.statusCode()).as(posted.body()).isEqualTo(200);
        JsonNode p = JSON.readTree(posted.body());
        assertThat(p.path("status").asString()).isEqualTo("POSTED");
        assertThat(p.path("postedBy").asString()).isNotBlank();
        assertThat(lineOf(p, "LOT-A").path("postedDelta").asInt()).isEqualTo(-2);
        assertThat(lineOf(p, "LOT-B").path("postedDelta").asInt()).isZero();
        assertThat(jdbc.sql("select qty_on_hand from lot where id = cast(:id as uuid)").param("id", lotA)
                .query(Integer.class).single()).isEqualTo(15);
        assertThat(ledgerRows(sku, "ADJUST")).isEqualTo(1);
        String reason = jdbc.sql("select reason from stock_movement where sku = :sku and type = 'ADJUST'")
                .param("sku", sku).query(String.class).single();
        assertThat(reason).contains(p.path("number").asString()).contains("lost").contains("Torn case");
        assertThat(reconcile().clean()).isTrue();

        // Posted counts are final.
        assertThat(send("POST", "/v1/cycle-counts/" + id + "/cancel", null, null).statusCode()).isEqualTo(409);
        HttpResponse<String> listed = send("GET", "/v1/cycle-counts?sku=" + sku, null, null);
        assertThat(JSON.readTree(listed.body()).path("total").asInt()).isEqualTo(1);
    }

    @Test
    void anEmptyCountIsRefusedAndAnOpenCountCanBeCancelled() throws Exception {
        TestActors.as(COUNTER);
        HttpResponse<String> empty = send("POST", "/v1/cycle-counts", null,
                "{\"nodeId\":\"" + NODE + "\",\"sku\":\"NOPE-" + UUID.randomUUID().toString().substring(0, 6) + "\"}");
        assertThat(empty.statusCode()).isEqualTo(422);
        assertThat(empty.body()).contains("inventory.cycle-count-empty");

        String sku = sku("CX");
        TestActors.reset();
        stock(sku, 4);
        TestActors.as(COUNTER);
        JsonNode count = JSON.readTree(send("POST", "/v1/cycle-counts", null,
                "{\"nodeId\":\"" + NODE + "\",\"sku\":\"" + sku + "\",\"blind\":true}").body());
        assertThat(count.path("blind").asBoolean()).isTrue();
        HttpResponse<String> cancelled = send("POST", "/v1/cycle-counts/" + count.path("id").asString() + "/cancel",
                null, null);
        assertThat(JSON.readTree(cancelled.body()).path("status").asString()).isEqualTo("CANCELLED");

        TestActors.as(TestActors.staff("inventory:read"));
        assertThat(send("POST", "/v1/cycle-counts", null, "{\"nodeId\":\"" + NODE + "\",\"sku\":\"" + sku + "\"}")
                .statusCode()).isEqualTo(403);
    }

    @Test
    void thePackingListBatchFillsReceiptsThatRecordNone() throws Exception {
        String org = "org_batch_" + UUID.randomUUID().toString().substring(0, 8);
        String atin = "AT" + UUID.randomUUID().toString().replace("-", "").substring(0, 8).toUpperCase(Locale.ROOT);
        String sku = sku("PL");
        DOWNSTREAM.stubFor(WireMock.get(WireMock.urlPathEqualTo("/v1/labels/units/" + atin))
                .willReturn(WireMock.aResponse().withStatus(200).withHeader("Content-Type", "image/svg+xml")
                        .withHeader("X-Unit-Label-Code", "ATF" + sku.substring(sku.length() - 7))
                        .withBody("<svg xmlns=\"http://www.w3.org/2000/svg\"/>")));
        String exp = today().plusDays(300).toString();
        String mfg = today().minusDays(20).toString();
        TestActors.as(TestActors.seller(org, "inventory:inbound", "inventory:read"));

        HttpResponse<String> bad = send("POST", "/v1/inbound/shipments", null, """
                {"nodeId":"ATL-FC","lines":[{"atin":"%s","sku":"%s","qty":10,"casePack":4,"lotNo":"B-1",
                 "mfgDate":"%s","expDate":"%s"}]}""".formatted(atin, sku, exp, mfg));
        assertThat(bad.statusCode()).isEqualTo(422);
        assertThat(bad.body()).contains("lines[0].qty").contains("lines[0].expDate");

        HttpResponse<String> created = send("POST", "/v1/inbound/shipments", null, """
                {"nodeId":"ATL-FC","lines":[{"atin":"%s","sku":"%s","qty":12,"casePack":6,"lotNo":"b-77",
                 "mfgDate":"%s","expDate":"%s"}]}""".formatted(atin, sku, mfg, exp));
        assertThat(created.statusCode()).as(created.body()).isEqualTo(201);
        JsonNode plan = JSON.readTree(created.body());
        JsonNode line = plan.path("lines").get(0);
        assertThat(line.path("casePack").asInt()).isEqualTo(6);
        assertThat(line.path("cases").asInt()).isEqualTo(2);
        assertThat(line.path("lotNo").asString()).isEqualTo("B-77");
        assertThat(line.path("expDate").asString()).isEqualTo(exp);
        String id = plan.path("id").asString();
        assertThat(send("POST", "/v1/inbound/shipments/" + id + "/ship", null, null).statusCode()).isEqualTo(200);

        TestActors.as(TestActors.staff("inventory:receive", "inventory:read"));
        HttpResponse<String> received = send("POST", "/v1/inbound/shipments/" + id + "/receipts", null,
                "{\"lines\":[{\"sku\":\"" + sku + "\",\"qtyGood\":11,\"qtyDamaged\":1}]}", UUID.randomUUID().toString());
        assertThat(received.statusCode()).as(received.body()).isEqualTo(200);
        JsonNode lots = JSON.readTree(received.body()).path("lots");
        JsonNode good = lots.get(0).path("lotNo").asString().endsWith("-DMG") ? lots.get(1) : lots.get(0);
        assertThat(good.path("lotNo").asString()).isEqualTo("B-77");
        assertThat(good.path("expDate").asString()).isEqualTo(exp);
        assertThat(good.path("mfgDate").asString()).isEqualTo(mfg);
        assertThat(good.path("qtyOnHand").asInt()).isEqualTo(11);
    }

    private static int lineNo(JsonNode count, String lotNo) {
        return lineOf(count, lotNo).path("lineNo").asInt();
    }

    private static JsonNode lineOf(JsonNode count, String lotNo) {
        for (JsonNode l : count.path("lines")) {
            if (l.path("lotNo").asString().equals(lotNo)) {
                return l;
            }
        }
        throw new AssertionError("No line for " + lotNo + " in " + count);
    }

    private HttpResponse<String> send(String method, String path, String ifMatch, String body) throws Exception {
        return send(method, path, ifMatch, body, null);
    }

    private HttpResponse<String> send(String method, String path, String ifMatch, String body, String key)
            throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                .header("Content-Type", "application/json")
                .method(method, body == null ? HttpRequest.BodyPublishers.noBody()
                        : HttpRequest.BodyPublishers.ofString(body));
        if (ifMatch != null) {
            b.header("If-Match", ifMatch);
        }
        if (key == null && method.equals("POST") && path.contains("/write-off")) {
            key = UUID.randomUUID().toString();
        }
        if (key != null) {
            b.header("Idempotency-Key", key);
        }
        return HTTP.send(b.build(), HttpResponse.BodyHandlers.ofString());
    }
}
