package com.afrotransact.retail.it;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;

import com.afrotransact.retail.application.BookShipmentCommand;
import com.afrotransact.retail.application.PoDispatchService;
import com.afrotransact.retail.application.PoLineInput;
import com.afrotransact.retail.application.ShipmentService;
import com.afrotransact.retail.application.SupplierCommand;
import com.afrotransact.retail.domain.DispatchChannel;
import com.afrotransact.retail.domain.DispatchStatus;
import com.afrotransact.retail.domain.DocumentStatus;
import com.afrotransact.retail.domain.EdiProtocol;
import com.afrotransact.retail.domain.InboundShipment;
import com.afrotransact.retail.domain.PoDispatch;
import com.afrotransact.retail.domain.PurchaseOrder;
import com.afrotransact.retail.domain.RetailItem;
import com.afrotransact.retail.domain.RetailRuleViolation;
import com.afrotransact.retail.domain.ShipmentDocument;
import com.afrotransact.retail.domain.Supplier;
import com.afrotransact.retail.domain.SupplierContact;
import com.afrotransact.retail.domain.SupplierStatus;
import com.afrotransact.retail.domain.TransportMode;
import com.github.tomakehurst.wiremock.client.WireMock;
import com.github.tomakehurst.wiremock.stubbing.StubMapping;
import org.assertj.core.groups.Tuple;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * S225: sent POs reach the supplier — PDF stored in media and a signed link emailed through notifications'
 * notification.requested, X12 850 through the EDI transport for EDI-enabled suppliers — with retries; ASN /
 * shipment documents are stored as media asset ids.
 */
class PoDispatchIT extends RetailIntegrationTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private final PoDispatchService dispatch;
    private final ShipmentService shipments;
    private final UUID asset = UUID.randomUUID();

    @LocalServerPort
    int port;

    @Autowired
    PoDispatchIT(PoDispatchService dispatch, ShipmentService shipments) {
        this.dispatch = dispatch;
        this.shipments = shipments;
    }

    @BeforeEach
    void media() {
        FakeEdi.SENT.clear();
        FakeEdi.down = false;
        DOWNSTREAM.stubFor(WireMock.post(WireMock.urlPathEqualTo("/v1/uploads"))
                .withHeader("Authorization", WireMock.equalTo("Bearer " + Stubs.TOKEN))
                .withRequestBody(WireMock.matchingJsonPath("$.purpose", WireMock.equalTo("purchase-order")))
                .willReturn(WireMock.aResponse().withStatus(201).withHeader("Content-Type", "application/json")
                        .withBody("{\"asset\":{\"id\":\"" + asset + "\",\"status\":\"PENDING\"},"
                                + "\"upload\":{\"method\":\"PUT\",\"url\":\"" + DOWNSTREAM.baseUrl()
                                + "/storage/" + asset + "\","
                                + "\"headers\":{\"x-amz-meta-purpose\":\"purchase-order\"}}}")));
        DOWNSTREAM.stubFor(WireMock.put(WireMock.urlPathMatching("/storage/.*")).willReturn(WireMock.ok()));
        DOWNSTREAM.stubFor(WireMock.post(WireMock.urlPathEqualTo("/v1/assets/" + asset + "/complete"))
                .willReturn(WireMock.okJson("{\"id\":\"" + asset + "\",\"purpose\":\"purchase-order\","
                        + "\"fileName\":\"po.pdf\",\"contentType\":\"application/pdf\",\"bytes\":2048,"
                        + "\"status\":\"READY\"}")));
        DOWNSTREAM.stubFor(WireMock.get(WireMock.urlPathEqualTo("/v1/assets/" + asset))
                .withQueryParam("urlTtlSeconds", WireMock.matching("[0-9]+"))
                .willReturn(WireMock.okJson("{\"id\":\"" + asset + "\",\"status\":\"READY\","
                        + "\"url\":\"https://files.example/po/" + asset + ".pdf?sig=abc\","
                        + "\"urlExpiresAt\":\"2030-01-01T00:00:00Z\"}")));
    }

    @AfterEach
    void edi() {
        FakeEdi.down = false;
    }

    @Test
    void sentPoIsEmailedAsAPdfLinkAndTransmittedAsEdi850() throws Exception {
        Supplier supplier = approvedSupplier("GHS");
        as(TestActors.BUYER);
        dispatch.setEdiProfile(supplier.id(), new PoDispatchService.EdiProfileCommand(true, EdiProtocol.SFTP,
                "accrapackedi", "sftp://edi.accrapack.example/inbound"));
        TestActors.reset();
        RetailItem item = item(supplier, "Shito Black Pepper Sauce, Hot, 12 oz", null, 3470);
        PurchaseOrder po = sentPo(supplier, List.of(new PoLineInput(item.id(), 480, null)));

        assertThat(dispatch.dispatches(po.id())).extracting(PoDispatch::channel, PoDispatch::status)
                .containsExactlyInAnyOrder(Tuple.tuple(DispatchChannel.EDI,
                        DispatchStatus.PENDING), Tuple.tuple(DispatchChannel.EMAIL,
                        DispatchStatus.PENDING));
        dispatchUntilIdle();

        List<PoDispatch> done = dispatch.dispatches(po.id());
        assertThat(done).allSatisfy(d -> assertThat(d.status()).isEqualTo(DispatchStatus.DONE));
        PoDispatch email = byChannel(done, DispatchChannel.EMAIL);
        assertThat(email.recipient()).isEqualTo("efua@accrapack.example");
        assertThat(email.assetId()).isNotNull();

        // The PDF went to storage (not through media), without our token.
        DOWNSTREAM.verify(WireMock.putRequestedFor(WireMock.urlEqualTo("/storage/" + asset))
                .withHeader("x-amz-meta-purpose", WireMock.equalTo("purchase-order"))
                .withoutHeader("Authorization"));
        byte[] pdf = DOWNSTREAM.findAll(WireMock.putRequestedFor(WireMock.urlEqualTo("/storage/" + asset)))
                .getFirst().getBody();
        assertThat(new String(pdf, 0, 5, StandardCharsets.ISO_8859_1)).isEqualTo("%PDF-");

        // notifications' generic request: supplier contact, template, signed link and the attachment reference.
        JsonNode request = outboxPayload(po.id(), "notification.requested");
        assertThat(request.path("templateKey").asString()).isEqualTo("retail.purchase_order.sent");
        assertThat(request.path("channels").get(0).asString()).isEqualTo("email");
        assertThat(request.path("recipient").path("email").asString()).isEqualTo("efua@accrapack.example");
        assertThat(request.path("data").path("poNumber").asString()).isEqualTo(po.number());
        assertThat(request.path("data").path("documentUrl").asString())
                .isEqualTo("https://files.example/po/" + email.assetId() + ".pdf?sig=abc");
        assertThat(request.path("data").path("attachments").get(0).path("assetId").asString())
                .isEqualTo(email.assetId().toString());
        assertThat(request.path("data").path("total").asString()).isEqualTo("GHS 16656.00");

        // EDI 850 addressed to the partner's interchange id.
        PoDispatch edi = byChannel(done, DispatchChannel.EDI);
        assertThat(FakeEdi.SENT).hasSize(1);
        assertThat(FakeEdi.SENT.getFirst().payload()).startsWith("ISA*").contains("*ZZ*ACCRAPACKEDI   *")
                .contains("BEG*00*SA*" + po.number());
        assertThat(edi.controlNumber()).isEqualTo(FakeEdi.SENT.getFirst().controlNumber()).hasSize(9);
        assertThat(edi.transmissionId()).startsWith("fake://ACCRAPACKEDI/");
        assertThat(outboxTypes(po.id()).stream().filter("retail.po.dispatched"::equals)).hasSize(2);

        // Another job round sends nothing twice.
        dispatch.dispatchDue();
        assertThat(FakeEdi.SENT).hasSize(1);

        // The PDF is also downloadable.
        HttpResponse<byte[]> download = HttpClient.newHttpClient().send(HttpRequest.newBuilder(
                URI.create("http://localhost:" + port + "/v1/purchase-orders/" + po.id() + "/pdf")).GET().build(),
                HttpResponse.BodyHandlers.ofByteArray());
        assertThat(download.statusCode()).isEqualTo(200);
        assertThat(download.headers().firstValue("Content-Type")).contains("application/pdf");
    }

    @Test
    void outagesAreRetriedThenFailedAndCanBeRetriedByABuyer() {
        Supplier supplier = approvedSupplier("USD");
        as(TestActors.BUYER);
        dispatch.setEdiProfile(supplier.id(), new PoDispatchService.EdiProfileCommand(true, EdiProtocol.AS2,
                "ACCRA2", "https://as2.accrapack.example/as2"));
        TestActors.reset();
        RetailItem item = item(supplier, "Gari, 1 kg", null, 300);
        PurchaseOrder po = sentPo(supplier, List.of(new PoLineInput(item.id(), 240, null)));
        StubMapping mediaDown = DOWNSTREAM.stubFor(WireMock.post(WireMock.urlPathEqualTo("/v1/uploads"))
                .atPriority(1).withRequestBody(WireMock.containing(po.number()))
                .willReturn(WireMock.serviceUnavailable()));
        FakeEdi.down = true;

        dispatch.dispatchDue();
        List<PoDispatch> first = dispatch.dispatches(po.id());
        assertThat(first).allSatisfy(d -> {
            assertThat(d.status()).isEqualTo(DispatchStatus.PENDING);
            assertThat(d.attempts()).isEqualTo(1);
            assertThat(d.lastError()).isNotBlank();
            assertThat(d.nextAttemptAt()).isAfter(d.updatedAt());
        });
        // Exhaust the attempts (each retry is made due at once).
        for (int i = 0; i < 5; i++) {
            jdbc.sql("update po_dispatch set next_attempt_at = now() - interval '1 second' where po_id = :po")
                    .param("po", po.id()).update();
            dispatch.dispatchDue();
        }
        assertThat(dispatch.dispatches(po.id())).allSatisfy(d -> {
            assertThat(d.status()).isEqualTo(DispatchStatus.FAILED);
            assertThat(d.attempts()).isEqualTo(5);
        });
        assertThat(outboxTypes(po.id()).stream().filter("retail.po.dispatch-failed"::equals)).hasSize(2);
        assertThat(outboxTypes(po.id())).doesNotContain("notification.requested");

        // Partner and media back: a buyer retries.
        DOWNSTREAM.removeStub(mediaDown);
        FakeEdi.down = false;
        as(TestActors.BUYER);
        assertThat(dispatch.retryFailed(po.id())).allSatisfy(d -> assertThat(d.status())
                .isEqualTo(DispatchStatus.PENDING));
        TestActors.reset();
        dispatch.dispatchDue();
        assertThat(dispatch.dispatches(po.id())).allSatisfy(d -> assertThat(d.status())
                .isEqualTo(DispatchStatus.DONE));
        assertThat(FakeEdi.SENT).singleElement().satisfies(m -> assertThat(m.protocol()).isEqualTo(EdiProtocol.AS2));
    }

    @Test
    void supplierWithoutAContactEmailFailsTheEmailAtOnceAndNonEdiSuppliersGetNoEdi() {
        as(TestActors.BUYER);
        Supplier s = suppliers.create(new SupplierCommand(unique("SUP-"), "Lagos Spice Co", "NG", "Lagos", "USD",
                "Net 30", 20, List.of(), List.of(new SupplierContact("Bola Ade", "Logistics", null, "+2348012345678"))));
        Supplier approved = suppliers.changeStatus(s.id(), SupplierStatus.APPROVED,
                null, null);
        TestActors.reset();
        RetailItem item = item(approved, "Suya spice, 200 g", null, 250);
        PurchaseOrder po = sentPo(approved, List.of(new PoLineInput(item.id(), 100, null)));

        dispatch.dispatchDue();
        List<PoDispatch> ds = dispatch.dispatches(po.id());
        assertThat(ds).singleElement().satisfies(d -> {
            assertThat(d.channel()).isEqualTo(DispatchChannel.EMAIL);
            assertThat(d.status()).isEqualTo(DispatchStatus.FAILED);
            assertThat(d.attempts()).isEqualTo(1);
            assertThat(d.lastError()).contains("no contact with an email");
        });
        assertThat(FakeEdi.SENT).isEmpty();
    }

    @Test
    void shipmentDocumentsAreStoredAsMediaAssetIds() {
        Supplier supplier = approvedSupplier("USD");
        RetailItem item = item(supplier, "Plantain chips, 85 g", null, 120);
        PurchaseOrder po = sentPo(supplier, List.of(new PoLineInput(item.id(), 600, null)));
        as(TestActors.BUYER);
        InboundShipment s = shipments.book(new BookShipmentCommand(po.id(), TransportMode.OCEAN_FCL, "MSC",
                "MSCU 1", "BL-1", "Tema, Ghana", "Port of Houston", TODAY, TODAY.plusDays(30), null, List.of()));
        String docName = s.documents().getFirst().name();
        UUID packingList = UUID.randomUUID();
        UUID pending = UUID.randomUUID();
        UUID unknown = UUID.randomUUID();
        DOWNSTREAM.stubFor(WireMock.get(WireMock.urlPathEqualTo("/v1/assets/" + packingList)).atPriority(1)
                .willReturn(WireMock.okJson("{\"id\":\"" + packingList + "\",\"purpose\":\"shipping-document\","
                        + "\"fileName\":\"asn.pdf\",\"contentType\":\"application/pdf\",\"status\":\"READY\"}")));
        DOWNSTREAM.stubFor(WireMock.get(WireMock.urlPathEqualTo("/v1/assets/" + pending)).atPriority(1)
                .willReturn(WireMock.okJson("{\"id\":\"" + pending + "\",\"status\":\"PENDING\"}")));
        DOWNSTREAM.stubFor(WireMock.get(WireMock.urlPathEqualTo("/v1/assets/" + unknown)).atPriority(1)
                .willReturn(WireMock.notFound()));

        InboundShipment withFile = shipments.setDocument(s.id(), docName, DocumentStatus.OK, "PL-7781", null,
                List.of(packingList, packingList));
        ShipmentDocument doc = withFile.documents().stream().filter(d -> d.name().equals(docName)).findFirst()
                .orElseThrow();
        assertThat(doc.assetIds()).containsExactly(packingList);
        // Status changes without assetIds keep the files.
        InboundShipment kept = shipments.setDocument(s.id(), docName, DocumentStatus.HOLD, "PL-7781", "recheck",
                null);
        assertThat(kept.documents().stream().filter(d -> d.name().equals(docName)).findFirst().orElseThrow()
                .assetIds()).containsExactly(packingList);
        assertThat(shipments.get(s.id()).documents().stream().filter(d -> d.name().equals(docName)).findFirst()
                .orElseThrow().assetIds()).containsExactly(packingList);

        assertThatThrownBy(() -> shipments.setDocument(s.id(), docName, DocumentStatus.OK, null, null,
                List.of(unknown))).isInstanceOf(RetailRuleViolation.class).hasMessageContaining("does not exist");
        assertThatThrownBy(() -> shipments.setDocument(s.id(), docName, DocumentStatus.OK, null, null,
                List.of(pending))).isInstanceOf(RetailRuleViolation.class).hasMessageContaining("PENDING");
        TestActors.reset();
    }

    private void dispatchUntilIdle() {
        for (int i = 0; i < 3 && dispatch.dispatchDue() > 0; i++) {
            // keep going while there is work
        }
    }

    private JsonNode outboxPayload(UUID aggregateId, String type) {
        return JSON.readTree(jdbc.sql("select payload::text from platform_outbox where aggregate_id = :id "
                + "and type = :t").param("id", aggregateId.toString()).param("t", type).query(String.class).single());
    }

    private static PoDispatch byChannel(List<PoDispatch> ds, DispatchChannel channel) {
        return ds.stream().filter(d -> d.channel() == channel).findFirst().orElseThrow();
    }
}
