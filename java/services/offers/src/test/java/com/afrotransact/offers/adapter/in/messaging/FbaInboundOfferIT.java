package com.afrotransact.offers.adapter.in.messaging;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;

import com.afrotransact.offers.application.BuyBoxView;
import com.afrotransact.offers.application.CreateOfferCommand;
import com.afrotransact.offers.application.OfferListingService;
import com.afrotransact.offers.application.OfferService;
import com.afrotransact.offers.application.OfferView;
import com.afrotransact.offers.application.UpdateOfferCommand;
import com.afrotransact.offers.domain.Fulfilment;
import com.afrotransact.offers.domain.OfferCondition;
import com.afrotransact.offers.domain.ShipFrom;
import com.afrotransact.offers.it.OffersIntegrationTest;
import com.afrotransact.offers.it.StubCatalog;
import com.afrotransact.offers.it.TestActors;
import com.afrotransact.platform.web.error.ConflictException;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.header.internals.RecordHeader;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.simple.JdbcClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * S257: FBA stock received at a fulfilment centre makes the seller's listing Fulfilled by AfroTransact while their
 * own-store offer stays open (one offer per channel). S259: optimistic locking of offer edits (If-Match → 409).
 */
class FbaInboundOfferIT extends OffersIntegrationTest {

    private static final ShipFrom HOUSTON = new ShipFrom("Houston", "TX", "US", "77002");

    private final InventoryInboundEventListener inbound;
    private final InventoryEventListener availability;
    private final OfferService offers;
    private final OfferListingService listing;
    private final JsonMapper json;
    private final int port;

    @Autowired
    FbaInboundOfferIT(JdbcClient jdbc, InventoryInboundEventListener inbound, InventoryEventListener availability,
                      OfferService offers, OfferListingService listing, JsonMapper json,
                      @LocalServerPort int port) {
        super(jdbc);
        this.inbound = inbound;
        this.availability = availability;
        this.offers = offers;
        this.listing = listing;
        this.json = json;
        this.port = port;
    }

    @Test
    void receivedFbaStockAddsAFulfilledByAfroTransactOfferNextToTheSellersOwnOffer() {
        String product = atin();
        String variant = atin();
        String stranded = atin();
        StubCatalog.live(product, variant, null, LAGOS);
        StubCatalog.live(atin(), stranded, null, LAGOS);
        TestActors.as(TestActors.seller(LAGOS));
        OfferView own = offers.create(new CreateOfferCommand(variant, null, null, "JOL-200", null,
                OfferCondition.NEW, 1299, 1499L, "USD", 499, Fulfilment.SELLER, 4, 2, HOUSTON));
        TestActors.reset();

        String sku = "ATF" + variant.substring(2, 9);
        UUID eventId = UUID.randomUUID();
        String payload = """
                {"shipmentId":"%s","number":"FBA-IN-007001","source":"FBA","ownerOrg":"%s","nodeId":"HOU-FC",
                 "receiptRef":"FBA-IN-007001-R1","lines":[
                   {"sku":"%s","atin":"%s","unitLabel":"%s","qtyGood":10,"qtyDamaged":1},
                   {"sku":"STRANDED-1","atin":"%s","qtyGood":5,"qtyDamaged":0}]}
                """.formatted(UUID.randomUUID(), LAGOS, sku, variant, sku, stranded);
        inbound.onMessage(record("at.inventory-inbound.events", "inventory.inbound.received", eventId, payload));
        inbound.onMessage(record("at.inventory-inbound.events", "inventory.inbound.received", eventId, payload));
        inbound.onMessage(record("at.inventory-inbound.events", "inventory.inbound.closed", UUID.randomUUID(),
                payload));

        TestActors.as(TestActors.seller(LAGOS));
        List<OfferView> mine = offers.list(null, variant, null, 0, 10).items();
        assertThat(mine).as("the own-store offer stays; one FBA offer is added (replay ignored)").hasSize(2);
        OfferView fba = mine.stream().filter(v -> v.offer().fulfilment() == Fulfilment.AFROTRANSACT).findFirst()
                .orElseThrow();
        assertThat(fba.offer().sellerSku()).as("same seller SKU in the other channel").isEqualTo("JOL-200");
        assertThat(fba.offer().inventorySku()).isEqualTo(sku);
        assertThat(fba.offer().priceMinor()).isEqualTo(1299);
        assertThat(fba.offer().listPriceMinor()).isEqualTo(1499L);
        assertThat(fba.offer().shippingMinor()).isZero();
        assertThat(offers.list(null, stranded, null, 0, 10).items()).as("no offer to copy: stays stranded").isEmpty();
        assertThat(outboxTypes(variant)).contains("offers.offer.created");

        TestActors.reset();
        availability.onMessage(record("at.inventory.events", "inventory.availability.changed", UUID.randomUUID(),
                """
                {"sku":"%s","nodeId":"HOU-FC","ownerOrg":"%s","available":10,"onHand":10,"reserved":0,"version":1}
                """.formatted(sku, LAGOS)));
        BuyBoxView box = listing.offers(variant, "USD");
        assertThat(box.offerCount()).isEqualTo(2);
        assertThat(box.winner().view().offer().fulfilment()).as("FBA wins: free shipping and the FBA bonus")
                .isEqualTo(Fulfilment.AFROTRANSACT);
        assertThat(box.winner().view().availableQty()).isEqualTo(10);

        TestActors.as(TestActors.seller(LAGOS));
        assertThat(offers.setSellerStock("JOL-200", 9, "COUNT", null).offerIds())
                .as("the shared SKU sets only the seller-fulfilled offer's stock").containsExactly(own.offer().id());
        assertThatThrownBy(() -> offers.create(new CreateOfferCommand(variant, null, null, "JOL-200-B", null,
                OfferCondition.NEW, 1199, null, "USD", 0, Fulfilment.SELLER, 2, 1, HOUSTON)))
                .as("still one seller-fulfilled offer per condition").isInstanceOf(ConflictException.class);
        assertThatThrownBy(() -> offers.update(own.offer().id(), new UpdateOfferCommand(null, null, null, null,
                Fulfilment.AFROTRANSACT, null, null, null, null)))
                .as("cannot move the own-store offer into the FBA channel that is taken")
                .isInstanceOf(ConflictException.class);
    }

    @Test
    void concurrentOfferEditsAreRejectedWithConflict() throws Exception {
        String variant = atin();
        StubCatalog.live(atin(), variant, null, MAMATEE);
        TestActors.as(TestActors.seller(MAMATEE));
        OfferView created = offers.create(new CreateOfferCommand(variant, null, null, "LOCK-1", null,
                OfferCondition.NEW, 999, null, "USD", 0, Fulfilment.SELLER, 3, 1, HOUSTON));
        String id = created.offer().id().toString();
        HttpClient http = HttpClient.newHttpClient();

        HttpResponse<String> read = send(http, "GET", "/v1/offers/" + id, null, null);
        assertThat(read.statusCode()).isEqualTo(200);
        String etag = read.headers().firstValue("ETag").orElseThrow();
        assertThat(etag).isEqualTo("\"0\"");

        HttpResponse<String> first = send(http, "PATCH", "/v1/offers/" + id, etag, "{\"priceMinor\":1099}");
        assertThat(first.statusCode()).isEqualTo(200);
        assertThat(first.headers().firstValue("ETag")).contains("\"1\"");

        HttpResponse<String> stale = send(http, "PATCH", "/v1/offers/" + id, etag, "{\"priceMinor\":899}");
        assertThat(stale.statusCode()).as("second editor still holds version 0").isEqualTo(409);
        assertThat(json.readTree(stale.body()).path("code").asString()).isEqualTo("offers.version-mismatch");
        HttpResponse<String> staleBody = send(http, "PATCH", "/v1/offers/" + id, null,
                "{\"priceMinor\":899,\"version\":0}");
        assertThat(staleBody.statusCode()).isEqualTo(409);
        assertThat(send(http, "POST", "/v1/offers/" + id + "/pause", etag, null).statusCode()).isEqualTo(409);
        assertThat(send(http, "PATCH", "/v1/offers/" + id, "\"x\"", "{\"priceMinor\":899}").statusCode())
                .isEqualTo(400);

        HttpResponse<String> fresh = send(http, "PATCH", "/v1/offers/" + id, "W/\"1\"", "{\"priceMinor\":899}");
        assertThat(fresh.statusCode()).isEqualTo(200);
        JsonNode body = json.readTree(fresh.body());
        assertThat(body.path("priceMinor").asLong()).isEqualTo(899);
        assertThat(body.path("version").asLong()).isEqualTo(2);
        assertThat(send(http, "POST", "/v1/offers/" + id + "/pause", "\"2\"", null).statusCode()).isEqualTo(200);
        assertThat(send(http, "PATCH", "/v1/offers/" + id, null, "{\"handlingDays\":3}").statusCode())
                .as("without If-Match the edit applies to the current version").isEqualTo(200);
    }

    private HttpResponse<String> send(HttpClient http, String method, String path, String ifMatch, String body)
            throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                .header("Content-Type", "application/json")
                .method(method, body == null ? HttpRequest.BodyPublishers.noBody()
                        : HttpRequest.BodyPublishers.ofString(body));
        if (ifMatch != null) {
            b.header("If-Match", ifMatch);
        }
        return http.send(b.build(), HttpResponse.BodyHandlers.ofString());
    }

    private static ConsumerRecord<String, String> record(String topic, String type, UUID eventId, String value) {
        ConsumerRecord<String, String> rec = new ConsumerRecord<>(topic, 0, 0L, "key", value);
        rec.headers().add(new RecordHeader("event-id", eventId.toString().getBytes(StandardCharsets.UTF_8)));
        rec.headers().add(new RecordHeader("event-type", type.getBytes(StandardCharsets.UTF_8)));
        return rec;
    }
}
