package com.afrotransact.taxreturns.adapter.out.http;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import com.afrotransact.taxreturns.config.TaxReturnsProperties;
import com.afrotransact.taxreturns.domain.returns.FulfilmentType;
import com.afrotransact.taxreturns.domain.returns.PlacedOrder;
import com.afrotransact.taxreturns.domain.returns.ReturnAddress;
import com.afrotransact.taxreturns.domain.returns.ReturnLabel;
import com.afrotransact.taxreturns.port.CarrierLabels;
import com.afrotransact.taxreturns.port.ReturnPickupScheduler;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.json.JsonMapper;

/** The orders lookup, fulfilment pickup and carrier-gateway return-label adapters against a local HTTP server. */
class HttpReturnsAdaptersTest {

    private static final UUID ORDER = UUID.fromString("0b9f0f7e-6c39-4a77-9f2a-1c1f3b8e0a01");
    private static final UUID SHIPMENT = UUID.fromString("7a1f0f7e-6c39-4a77-9f2a-1c1f3b8e0a02");
    private static final ReturnAddress HOME = new ReturnAddress("Ada Obi", "1 Main St", null, "Houston", "TX",
            "77019", "US", "+17135550142");

    private final Map<String, String> requests = new ConcurrentHashMap<>();
    private HttpServer server;
    private String base;
    private JsonMapper json;
    private ClientCredentialsTokens tokens;

    @BeforeEach
    void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/lookup/orders", ex -> {
            record(ex);
            if (!ex.getRequestURI().getPath().endsWith(ORDER.toString())) {
                respond(ex, 404, "{\"code\":\"orders.order-not-found\"}");
                return;
            }
            respond(ex, 200, """
                    {"orderId":"%s","orderNumber":"AT-2026-ABC123","customerId":"cust-1","status":"placed",
                     "currency":"usd","paymentId":"pay-1","placedAt":"2026-10-01T10:00:00Z",
                     "shipTo":{"fullName":"Ada Obi","phone":"+17135550142","line1":"1 Main St","city":"Houston",
                               "state":"TX","postalCode":"77019","country":"US"},
                     "shipments":[{"shipmentId":"%s","sellerOrgId":"org_a","sellerName":"Lagos Hub",
                       "fulfilmentType":"FBA_AFROTRANSACT","deliveryMethod":"CARRIER","status":"delivered",
                       "shippingMinor":799,"totalMinor":15669,"deliveredAt":"2026-10-03T15:00:00Z",
                       "lines":[{"lineId":"l1","variantAtin":"ATDRESS001","title":"Dress","productType":"dresses",
                                 "perishable":false,"quantity":2,"unitPriceMinor":6499,"discountMinor":500,
                                 "taxMinor":1072}]}]}
                    """.formatted(ORDER, SHIPMENT));
        });
        server.createContext("/v1/return-pickups", ex -> {
            record(ex);
            respond(ex, ex.getRequestURI().getPath().endsWith("/cancel") ? 200 : 201,
                    "{\"id\":\"rp-1\",\"status\":\"REQUESTED\"}");
        });
        server.createContext("/v1/return-labels", ex -> {
            record(ex);
            respond(ex, 201, "{\"id\":\"lbl-1\",\"carrier\":\"usps\",\"trackingNumber\":\"9400\",\"kind\":\"return\","
                    + "\"qrPayload\":\"QR-123\",\"dropOffNetwork\":\"Post Office\"}");
        });
        server.start();
        base = "http://127.0.0.1:" + server.getAddress().getPort();
        json = JsonMapper.builder().build();
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    private TaxReturnsProperties props(TaxReturnsProperties.Label label) {
        TaxReturnsProperties.Returns returns = new TaxReturnsProperties.Returns(0, null, null, 0, 0, null, 0, 0, 0,
                null, label, null);
        TaxReturnsProperties p = new TaxReturnsProperties(null, returns, new TaxReturnsProperties.Upstreams(
                base + "/oauth2/token", null, "", Duration.ofSeconds(5), base, base, base, base, base, base), null,
                null);
        tokens = new ClientCredentialsTokens(RestClient.builder(), p, Clock.systemUTC());
        return p;
    }

    private void record(HttpExchange ex) throws IOException {
        String body = new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        requests.put(ex.getRequestMethod() + " " + ex.getRequestURI().getPath(), body);
    }

    private static void respond(HttpExchange ex, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().add("Content-Type", status >= 400 ? "application/problem+json" : "application/json");
        ex.sendResponseHeaders(status, bytes.length);
        try (OutputStream out = ex.getResponseBody()) {
            out.write(bytes);
        }
    }

    @Test
    void orderLookupBuildsTheSnapshotWithTheCustomersAddress() {
        HttpOrderLookup lookup = new HttpOrderLookup(RestClient.builder(), props(null), tokens, json);
        PlacedOrder o = lookup.placedOrder(ORDER).orElseThrow();
        assertThat(o.orderNumber()).isEqualTo("AT-2026-ABC123");
        assertThat(o.currency()).isEqualTo("USD");
        assertThat(o.returnFrom()).contains(HOME);
        PlacedOrder.Shipment s = o.shipments().getFirst();
        assertThat(s.fulfilment()).isEqualTo(FulfilmentType.FBA_AFROTRANSACT);
        assertThat(s.shippingMinor()).isEqualTo(799);
        assertThat(s.deliveredAt()).isEqualTo(Instant.parse("2026-10-03T15:00:00Z"));
        assertThat(s.lines().getFirst().itemsMinor()).isEqualTo(2 * 6_499 - 500);
        assertThat(s.lines().getFirst().productType()).isEqualTo("dresses");
        assertThat(lookup.placedOrder(UUID.randomUUID())).isEmpty();
    }

    @Test
    void pickupsAreScheduledAndCancelledAtFulfilment() {
        HttpReturnPickupScheduler pickups = new HttpReturnPickupScheduler(RestClient.builder(), props(null), tokens,
                json);
        UUID rma = UUID.randomUUID();
        String id = pickups.schedule(new ReturnPickupScheduler.PickupRequest(rma, "RMA-1", ORDER, "AT-2026-ABC123",
                SHIPMENT, "cust-1", "org_b", HOME, "RET-FC", LocalDate.of(2026, 10, 11), LocalDate.of(2026, 10, 24),
                2));
        assertThat(id).isEqualTo("rp-1");
        assertThat(requests.get("POST /v1/return-pickups")).contains("\"rmaId\":\"" + rma + "\"",
                "\"line1\":\"1 Main St\"", "\"depotNodeId\":\"RET-FC\"", "\"collectBy\":\"2026-10-24\"",
                "\"units\":2");
        pickups.cancel("rp-1", "Return RMA-1 cancelled");
        assertThat(requests.get("POST /v1/return-pickups/rp-1/cancel")).contains("Return RMA-1 cancelled");
    }

    @Test
    void returnLabelsAskForTheRateChoiceAndBoxFreeWhenPreferred() {
        HttpCarrierLabels labels = new HttpCarrierLabels(RestClient.builder(), props(
                new TaxReturnsProperties.Label(null, null, 0, "FASTEST", List.of("usps", "ups"), "PREFERRED")), tokens,
                json);
        ReturnLabel l = labels.buyReturnLabel(new CarrierLabels.LabelRequest("RMA-1", "RET-FC", HOME, HOME, 2.0),
                "rma-label-1");
        assertThat(l.boxFree()).isTrue();
        assertThat(l.qrPayload()).isEqualTo("QR-123");
        assertThat(l.dropOffNetwork()).isEqualTo("Post Office");
        assertThat(requests.get("POST /v1/return-labels")).contains("\"rateChoice\":\"FASTEST\"",
                "\"carriers\":[\"usps\",\"ups\"]", "\"mode\":\"BOX_FREE_QR\"", "\"fallbackToLabel\":true",
                "\"reference\":\"RMA-1\"").doesNotContain("\"service\"");

        HttpCarrierLabels service = new HttpCarrierLabels(RestClient.builder(), props(
                new TaxReturnsProperties.Label("ups", "03", 0, "SERVICE", null, null)), tokens, json);
        service.buyReturnLabel(new CarrierLabels.LabelRequest("RMA-2", "RET-FC", HOME, HOME, 2.0), "rma-label-2");
        assertThat(requests.get("POST /v1/return-labels")).contains("\"rateChoice\":\"SERVICE\"",
                "\"carrier\":\"ups\"", "\"service\":\"03\"").doesNotContain("BOX_FREE_QR");
    }
}
