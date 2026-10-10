package com.afrotransact.inventory.it;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

import com.afrotransact.inventory.application.InboundCommands;
import com.afrotransact.inventory.application.InboundShipmentService;
import com.afrotransact.inventory.application.OfferLinkSyncService;
import com.afrotransact.inventory.application.ReceiveCommand;
import com.afrotransact.platform.security.Actor;
import com.github.tomakehurst.wiremock.client.ResponseDefinitionBuilder;
import com.github.tomakehurst.wiremock.client.WireMock;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * S257 seller FBA inbound end to end over HTTP: plan with AT-FN unit labels from catalog and LPN cartons, FC
 * assignment that never mixes sellers, labels, tracking and a bought label (carrier-gateway), shipping, FC receiving
 * by scanned unit label with discrepancies, close; S067 seller FBA inventory with stranded flags; S226 retail ASNs.
 */
class FbaInboundIT extends InventoryIntegrationTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final HttpClient HTTP = HttpClient.newHttpClient();
    private static final String FC = "ATL-FC";

    private int port;
    private InboundShipmentService inbound;
    private OfferLinkSyncService offerLinks;

    @BeforeEach
    void wire(@LocalServerPort int serverPort, @Autowired InboundShipmentService inboundService,
              @Autowired OfferLinkSyncService offerLinkService) {
        this.port = serverPort;
        this.inbound = inboundService;
        this.offerLinks = offerLinkService;
    }

    private static String atin() {
        return "AT" + UUID.randomUUID().toString().replace("-", "").substring(0, 8).toUpperCase(Locale.ROOT);
    }

    private static String atfn() {
        return "ATF" + UUID.randomUUID().toString().replace("-", "").substring(0, 7).toUpperCase(Locale.ROOT);
    }

    /** catalog answers the unit label of {@code atin} for {@code org} with {@code code} in the SVG title. */
    private static void unitLabel(String atin, String org, String code, boolean header) {
        ResponseDefinitionBuilder response = WireMock.aResponse().withStatus(200)
                .withHeader("Content-Type", "image/svg+xml")
                .withBody("<?xml version=\"1.0\"?><svg xmlns=\"http://www.w3.org/2000/svg\"><title>" + code
                        + "</title><text>AFROTRANSACT UNIT LABEL</text></svg>");
        if (header) {
            response = response.withHeader("X-Unit-Label-Code", code);
        }
        DOWNSTREAM.stubFor(WireMock.get(WireMock.urlPathEqualTo("/v1/labels/units/" + atin))
                .withQueryParam("orgId", WireMock.equalTo(org)).willReturn(response));
    }

    private static Actor seller(String org) {
        return TestActors.seller(org, "inventory:inbound", "inventory:read");
    }

    private static final Actor RECEIVER = TestActors.staff("inventory:receive", "inventory:read");

    @Test
    void sellerSendsStockAndTheFcReceivesItAgainstThePlan() throws Exception {
        String org = "org_fba_" + UUID.randomUUID().toString().substring(0, 8);
        String jollof = atin();
        String shito = atin();
        String jollofCode = atfn();
        unitLabel(jollof, org, jollofCode, false);
        unitLabel(shito, org, atfn(), true);
        String shitoSku = sku("SHI");

        TestActors.as(seller(org));
        HttpResponse<String> created = send("POST", "/v1/inbound/shipments", null, """
                {"name":"October restock","nodeId":"%s",
                 "shipFrom":{"name":"Mama Tee's Pantry","line1":"12 Bissonnet St","city":"Houston","region":"TX",
                             "postalCode":"77005","country":"US"},
                 "lines":[{"atin":"%s","qty":10},{"atin":"%s","sku":"%s","qty":5}],
                 "boxes":[{"contents":[{"sku":"%s","qty":10}],"weightLb":12.5},
                          {"contents":[{"sku":"%s","qty":5}],"weightLb":6}]}
                """.formatted(FC, jollof, shito, shitoSku, jollofCode, shitoSku));
        assertThat(created.statusCode()).as(created.body()).isEqualTo(201);
        JsonNode plan = JSON.readTree(created.body());
        String id = plan.path("id").asString();
        assertThat(plan.path("number").asString()).startsWith("FBA-IN-");
        assertThat(plan.path("status").asString()).isEqualTo("WORKING");
        assertThat(plan.path("nodeId").asString()).isEqualTo(FC);
        assertThat(plan.path("shipTo").path("city").asString()).isEqualTo("Atlanta");
        assertThat(plan.path("lines").get(0).path("sku").asString()).as("default SKU = the seller's AT-FN code")
                .isEqualTo(jollofCode);
        assertThat(plan.path("lines").get(0).path("unitLabel").asString()).isEqualTo(jollofCode);
        assertThat(plan.path("lines").get(1).path("unitLabel").asString()).startsWith("ATF");
        assertThat(plan.path("boxes").get(0).path("lpn").asString()).matches("LPN[0-9A-Z]{8}");

        // Unit labels: one per unit with the AT-FN code; carton labels with the LPN (catalog renders both).
        DOWNSTREAM.stubFor(WireMock.post(WireMock.urlPathEqualTo("/v1/labels/batch"))
                .willReturn(WireMock.aResponse().withStatus(200).withHeader("Content-Type", "application/pdf")
                        .withBody("%PDF-1.7 labels")));
        HttpResponse<String> units = send("GET", "/v1/inbound/shipments/" + id + "/labels?kind=UNIT", null, null);
        assertThat(units.statusCode()).as(units.body()).isEqualTo(200);
        assertThat(units.headers().firstValue("Content-Type")).contains("application/pdf");
        DOWNSTREAM.verify(WireMock.postRequestedFor(WireMock.urlPathEqualTo("/v1/labels/batch"))
                .withQueryParam("format", WireMock.equalTo("PDF"))
                .withRequestBody(WireMock.matchingJsonPath("$.labels[0].data", WireMock.equalTo(jollofCode)))
                .withRequestBody(WireMock.matchingJsonPath("$.labels[0].copies", WireMock.equalTo("10"))));
        String lpn = plan.path("boxes").get(0).path("lpn").asString();
        assertThat(send("GET", "/v1/inbound/shipments/" + id + "/labels?kind=BOX&format=PDF", null, null)
                .statusCode()).isEqualTo(200);
        DOWNSTREAM.verify(WireMock.postRequestedFor(WireMock.urlPathEqualTo("/v1/labels/batch"))
                .withRequestBody(WireMock.matchingJsonPath("$.labels[0].data", WireMock.equalTo(lpn))));

        // Another seller cannot see it.
        TestActors.as(seller("org_someone_else"));
        assertThat(send("GET", "/v1/inbound/shipments/" + id, null, null).statusCode()).isEqualTo(404);

        // A label bought through carrier-gateway needs the FC's street address.
        TestActors.as(seller(org));
        assertThat(send("POST", "/v1/inbound/shipments/" + id + "/carrier-label", null,
                "{\"carrier\":\"ups\",\"service\":\"03\"}").statusCode()).isEqualTo(422);
        TestActors.reset();
        assertThat(send("PUT", "/v1/nodes/" + FC + "/address", null,
                "{\"line1\":\"4500 Inbound Way\",\"city\":\"Atlanta\",\"region\":\"GA\",\"postalCode\":\"30349\"}")
                .statusCode()).isEqualTo(200);
        DOWNSTREAM.stubFor(WireMock.post(WireMock.urlPathEqualTo("/v1/labels")).willReturn(WireMock.okJson("""
                {"id":"%s","carrier":"ups","service":"03","trackingNumber":"1Z999AA10123456784","costMinor":2412,
                 "currency":"USD","documentUrl":"https://labels.example/1Z.zpl","status":"purchased"}
                """.formatted(UUID.randomUUID())).withStatus(201)));
        TestActors.as(seller(org));
        HttpResponse<String> bought = send("POST", "/v1/inbound/shipments/" + id + "/carrier-label", null,
                "{\"carrier\":\"ups\",\"service\":\"03\"}");
        assertThat(bought.statusCode()).as(bought.body()).isEqualTo(200);
        assertThat(JSON.readTree(bought.body()).path("trackingNumber").asString()).isEqualTo("1Z999AA10123456784");
        assertThat(JSON.readTree(bought.body()).path("trackingSource").asString()).isEqualTo("CARRIER_GATEWAY");
        DOWNSTREAM.verify(WireMock.postRequestedFor(WireMock.urlPathEqualTo("/v1/labels"))
                .withHeader("Idempotency-Key", WireMock.equalTo("inbound-" + id + "-label"))
                .withRequestBody(WireMock.matchingJsonPath("$.fulfilment", WireMock.equalTo("FBA")))
                .withRequestBody(WireMock.matchingJsonPath("$.shipment.to.line1", WireMock.equalTo("4500 Inbound Way")))
                .withRequestBody(WireMock.matchingJsonPath("$.shipment.parcels.length()", WireMock.equalTo("2"))));

        // Shipped: expected units are inbound at the FC.
        HttpResponse<String> shipped = send("POST", "/v1/inbound/shipments/" + id + "/ship", "\"0\"", null);
        assertThat(shipped.statusCode()).as("stale version").isEqualTo(409);
        String etag = send("GET", "/v1/inbound/shipments/" + id, null, null).headers().firstValue("ETag")
                .orElseThrow();
        shipped = send("POST", "/v1/inbound/shipments/" + id + "/ship", etag, null);
        assertThat(shipped.statusCode()).as(shipped.body()).isEqualTo(200);
        assertThat(JSON.readTree(shipped.body()).path("status").asString()).isEqualTo("SHIPPED");
        assertThat(inbound(jollofCode, FC)).isEqualTo(10);
        assertThat(inbound(shitoSku, FC)).isEqualTo(5);
        assertThat(send("PUT", "/v1/inbound/shipments/" + id, null, "{\"lines\":[{\"atin\":\"" + jollof
                + "\",\"qty\":3}]}").statusCode()).as("plan frozen once shipped").isEqualTo(409);

        // FC receiving: sellers may not; receivers scan unit labels.
        assertThat(send("POST", "/v1/inbound/shipments/" + id + "/receipts", null, "{\"lines\":[]}",
                "rk-0").statusCode()).isEqualTo(403);
        TestActors.as(RECEIVER);
        String otherSku = sku("STRAY");
        String receipt = """
                {"lines":[{"unitLabel":"%s","qtyGood":8,"qtyDamaged":1,"bin":"A-01-01"},
                          {"sku":"%s","qtyGood":6,"qtyDamaged":0},
                          {"sku":"%s","qtyGood":2,"qtyDamaged":0}],
                 "boxesReceived":["%s"]}
                """.formatted(jollofCode, shitoSku, otherSku, lpn);
        String key = "rk-" + UUID.randomUUID();
        HttpResponse<String> received = send("POST", "/v1/inbound/shipments/" + id + "/receipts", null, receipt, key);
        assertThat(received.statusCode()).as(received.body()).isEqualTo(200);
        JsonNode after = JSON.readTree(received.body());
        assertThat(after.path("shipment").path("status").asString()).isEqualTo("RECEIVING");
        assertThat(after.path("lots")).hasSize(4);
        after.path("lots").forEach(l -> assertThat(l.path("ownerOrg").asString()).isEqualTo(org));
        assertThat(after.path("shipment").path("boxes").get(0).path("receivedAt").isNull()).isFalse();
        HttpResponse<String> replay = send("POST", "/v1/inbound/shipments/" + id + "/receipts", null, receipt, key);
        assertThat(JSON.readTree(replay.body()).path("replayed").asBoolean()).isTrue();
        assertThat(position(jollofCode, FC, "on_hand")).isEqualTo(8);
        assertThat(position(jollofCode, FC, "damaged")).isEqualTo(1);
        assertThat(inbound(jollofCode, FC)).as("1 unit still expected").isEqualTo(1);
        assertThat(inbound(shitoSku, FC)).isZero();
        assertThat(events("inventory.inbound.received", id)).isEqualTo(1);
        assertThat(after.path("shipment").path("discrepancies").toString()).contains("DAMAGED", "OVER", "UNEXPECTED");

        HttpResponse<String> closed = send("POST", "/v1/inbound/shipments/" + id + "/close", null, null);
        assertThat(closed.statusCode()).as(closed.body()).isEqualTo(200);
        JsonNode done = JSON.readTree(closed.body());
        assertThat(done.path("status").asString()).isEqualTo("CLOSED");
        List<String> types = new ArrayList<>();
        done.path("discrepancies").forEach(d -> types.add(d.path("sku").asString() + ":" + d.path("type").asString()
                + ":" + d.path("qty").asInt()));
        assertThat(types).containsExactlyInAnyOrder(jollofCode + ":DAMAGED:1", jollofCode + ":SHORT:1",
                shitoSku + ":OVER:1", otherSku + ":UNEXPECTED:2");
        assertThat(inbound(jollofCode, FC)).as("short units no longer inbound").isZero();
        assertThat(events("inventory.inbound.closed", id)).isEqualTo(1);
        assertThat(reconcile().clean()).isTrue();

        // S067: the seller's FBA inventory; stranded until a live Fulfilled-by-AfroTransact offer sells the SKU.
        TestActors.as(seller(org));
        JsonNode fba = JSON.readTree(send("GET", "/v1/fba/inventory", null, null).body());
        assertThat(fba.path("ownerOrg").asString()).isEqualTo(org);
        assertThat(fba.path("summary").path("onHand").asLong()).isEqualTo(16);
        JsonNode jollofRow = row(fba, jollofCode);
        assertThat(jollofRow.path("unitLabel").asString()).isEqualTo(jollofCode);
        assertThat(jollofRow.path("atin").asString()).isEqualTo(jollof);
        assertThat(jollofRow.path("unfulfillable").asInt()).isEqualTo(1);
        assertThat(jollofRow.path("quarantined").asInt()).as("damaged on receipt: the -DMG quarantine lot")
                .isEqualTo(1);
        assertThat(jollofRow.path("ageing").path("D0_90").asInt()).isEqualTo(9);
        assertThat(jollofRow.path("stranded").asBoolean()).isTrue();
        assertThat(jollofRow.path("strandedReason").asString()).isEqualTo("NO_OFFER");
        assertThat(jollofRow.path("cubicFeet").decimalValue()).isEqualByComparingTo("2.25");
        offerLinks.offerChanged(UUID.randomUUID(), new OfferLinkSyncService.OfferState(UUID.randomUUID(), org, jollof,
                jollofCode, "AFROTRANSACT", "ACTIVE", true, Instant.now()));
        fba = JSON.readTree(send("GET", "/v1/fba/inventory?stranded=true", null, null).body());
        assertThat(fba.path("items")).extracting(n -> n.path("sku").asString()).doesNotContain(jollofCode)
                .contains(shitoSku, otherSku);
        assertThat(fba.path("summary").path("strandedSkus").asInt()).isEqualTo(2);
        TestActors.as(seller("org_someone_else"));
        assertThat(JSON.readTree(send("GET", "/v1/fba/inventory?ownerOrg=" + org, null, null).body())
                .path("items")).as("sellers only ever see their own").isEmpty();
    }

    @Test
    void assignmentNeverMixesSellersAndRequestedFcsMustQualify() throws Exception {
        String sharedSku = sku("SHARED");
        String a = atin();
        unitLabel(a, "org_first", atfn(), false);
        receiving.receive(new ReceiveCommand("GRN-" + UUID.randomUUID(), "EWR-FC", "org_first",
                List.of(line(sharedSku, "L-" + UUID.randomUUID().toString().substring(0, 6), 400, 5, 0, 0))),
                UUID.randomUUID().toString());
        String b = atin();
        unitLabel(b, "org_second", atfn(), false);
        TestActors.as(seller("org_second"));
        HttpResponse<String> refused = send("POST", "/v1/inbound/shipments", null, """
                {"nodeId":"EWR-FC","lines":[{"atin":"%s","sku":"%s","qty":3}]}""".formatted(b, sharedSku));
        assertThat(refused.statusCode()).isEqualTo(422);
        assertThat(JSON.readTree(refused.body()).path("code").asString()).isEqualTo("inventory.fc-unavailable");
        HttpResponse<String> assigned = send("POST", "/v1/inbound/shipments", null, """
                {"lines":[{"atin":"%s","sku":"%s","qty":3}]}""".formatted(b, sharedSku));
        assertThat(assigned.statusCode()).as(assigned.body()).isEqualTo(201);
        assertThat(JSON.readTree(assigned.body()).path("nodeId").asString()).isNotEqualTo("EWR-FC");
        assertThat(JSON.readTree(assigned.body()).path("assignmentReason").asString()).isNotBlank();

        DOWNSTREAM.stubFor(WireMock.get(WireMock.urlPathEqualTo("/v1/labels/units/ATDOWN0001"))
                .willReturn(WireMock.serverError()));
        HttpResponse<String> catalogDown = send("POST", "/v1/inbound/shipments", null,
                "{\"lines\":[{\"atin\":\"ATDOWN0001\",\"qty\":3}]}");
        assertThat(catalogDown.statusCode()).isEqualTo(503);
        assertThat(JSON.readTree(catalogDown.body()).path("code").asString())
                .isEqualTo("inventory.catalog-unavailable");

        String planId = JSON.readTree(assigned.body()).path("id").asString();
        DOWNSTREAM.stubFor(WireMock.post(WireMock.urlPathEqualTo("/v1/tracking"))
                .willReturn(WireMock.okJson("{\"carrier\":\"fedex\"}").withStatus(201)));
        JsonNode tracked = JSON.readTree(send("PUT", "/v1/inbound/shipments/" + planId + "/tracking", null,
                "{\"carrier\":\"FedEx\",\"trackingNumber\":\"7489 2231 0099\"}").body());
        assertThat(tracked.path("trackingRegistered").asBoolean()).isTrue();
        assertThat(tracked.path("shipment").path("trackingNumber").asString()).isEqualTo("7489 2231 0099");
        JsonNode cancelled = JSON.readTree(send("POST", "/v1/inbound/shipments/" + planId + "/cancel", null, null)
                .body());
        assertThat(cancelled.path("status").asString()).isEqualTo("CANCELLED");
    }

    @Test
    void retailAsnsBecomeInboundAtDepartureAndCloseWhenReceived() {
        String sku = sku("AT-JOL");
        String reference = "SHP-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase(Locale.ROOT);
        inbound.retailShipmentChanged(UUID.randomUUID(), new InboundCommands.RetailShipmentNotice(
                UUID.randomUUID().toString(), reference, "BOOKED", NODE, null,
                List.of(new InboundCommands.NoticeLine(sku, 100))));
        assertThat(jdbc.sql("select count(*) from inbound_shipment where external_ref = :r").param("r", reference)
                .query(Long.class).single()).as("not before departure").isZero();
        UUID departed = UUID.randomUUID();
        InboundCommands.RetailShipmentNotice notice = new InboundCommands.RetailShipmentNotice(
                UUID.randomUUID().toString(), reference, "DEPARTED", NODE, today().plusDays(20),
                List.of(new InboundCommands.NoticeLine(sku, 60), new InboundCommands.NoticeLine(sku, 40)));
        inbound.retailShipmentChanged(departed, notice);
        inbound.retailShipmentChanged(departed, notice);
        inbound.retailShipmentChanged(UUID.randomUUID(), new InboundCommands.RetailShipmentNotice(
                UUID.randomUUID().toString(), reference, "ARRIVED_PORT", NODE, null, notice.lines()));
        assertThat(position(sku, "inbound")).as("one notice, replays and later stages ignored").isEqualTo(100);

        receiving.receive(new ReceiveCommand("GRN-" + UUID.randomUUID(), NODE, null, reference,
                List.of(line(sku, "L2610-JOL-9", 500, 90, 2, 250))), UUID.randomUUID().toString());
        assertThat(position(sku, "inbound")).isEqualTo(8);
        assertThat(position(sku, "on_hand")).isEqualTo(90);
        inbound.retailShipmentChanged(UUID.randomUUID(), new InboundCommands.RetailShipmentNotice(
                UUID.randomUUID().toString(), reference, "RECEIVED", NODE, null, notice.lines()));
        assertThat(position(sku, "inbound")).as("the 8 units that never arrived").isZero();
        assertThat(jdbc.sql("select status from inbound_shipment where external_ref = :r").param("r", reference)
                .query(String.class).single()).isEqualTo("CLOSED");
        assertThat(reconcile().clean()).isTrue();
    }

    private static JsonNode row(JsonNode fba, String sku) {
        for (JsonNode n : fba.path("items")) {
            if (sku.equals(n.path("sku").asString())) {
                return n;
            }
        }
        throw new AssertionError(sku + " not in " + fba);
    }

    private int inbound(String sku, String node) {
        return position(sku, node, "inbound");
    }

    private int position(String sku, String node, String column) {
        return jdbc.sql("select " + column + " from inventory_position where sku = :sku and node_id = :node")
                .param("sku", sku).param("node", node).query(Integer.class).single();
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
        if (key != null) {
            b.header("Idempotency-Key", key);
        }
        return HTTP.send(b.build(), HttpResponse.BodyHandlers.ofString());
    }
}
