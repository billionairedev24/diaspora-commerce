package com.afrotransact.offers.adapter.in.messaging;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

import com.afrotransact.offers.application.BuyBoxView;
import com.afrotransact.offers.application.OfferListingService;
import com.afrotransact.offers.application.OfferService;
import com.afrotransact.offers.application.OfferView;
import com.afrotransact.offers.domain.Fulfilment;
import com.afrotransact.offers.domain.SellerKind;
import com.afrotransact.offers.it.OffersIntegrationTest;
import com.afrotransact.offers.it.TestActors;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.header.internals.RecordHeader;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * Kafka listeners invoked directly with records shaped like the outbox relay's (no broker): catalog product
 * lifecycle, inventory availability (versions + inbox de-duplication) and seller enforcement / org names.
 */
class EventListenersIT extends OffersIntegrationTest {

    private final CatalogEventListener catalogListener;
    private final InventoryEventListener inventoryListener;
    private final SellerEventListener sellerListener;
    private final OfferListingService listing;
    private final OfferService offers;

    @Autowired
    EventListenersIT(JdbcClient jdbc, CatalogEventListener catalogListener, InventoryEventListener inventoryListener,
                     SellerEventListener sellerListener, OfferListingService listing, OfferService offers) {
        super(jdbc);
        this.catalogListener = catalogListener;
        this.inventoryListener = inventoryListener;
        this.sellerListener = sellerListener;
        this.listing = listing;
        this.offers = offers;
    }

    @Test
    void catalogEventsCreateTheSellersOffersAndFollowModeration() {
        String product = atin();
        String red = atin();
        String gold = atin();
        String payload = """
                {"productId":"%s","atin":"%s","ownerKind":"SELLER","ownerOrgId":"%s","status":"IN_REVIEW",
                 "title":"Ankara Wrap Dress","categoryId":null,"productTypeCode":"dresses","note":null,
                 "variants":[
                   {"atin":"%s","sellerSku":"awd-m-red","gtin":null,"priceMinor":6499,"currency":"USD","quantity":5},
                   {"atin":"%s","sellerSku":"AWD-M-GLD","gtin":null,"priceMinor":6999,"currency":"USD","quantity":0}]}
                """.formatted(UUID.randomUUID(), product, LAGOS, red, gold);
        UUID created = UUID.randomUUID();
        catalogListener.onMessage(record("at.product.events", "catalog.product.created", created, payload));
        catalogListener.onMessage(record("at.product.events", "catalog.product.created", created, payload));

        TestActors.as(TestActors.seller(LAGOS));
        List<OfferView> mine = offers.list(null, product, null, 0, 10).items();
        assertThat(mine).hasSize(2).allSatisfy(v -> {
            assertThat(v.offer().productLive()).isFalse();
            assertThat(v.offer().fulfilment()).isEqualTo(Fulfilment.SELLER);
            assertThat(v.offer().sellerKind()).isEqualTo(SellerKind.SELLER);
        });
        assertThat(mine).extracting(v -> v.offer().sellerSku()).containsExactlyInAnyOrder("AWD-M-RED", "AWD-M-GLD");
        assertThat(outboxTypes(red)).containsExactly("offers.offer.created");
        assertThat(listing.offers(red, "USD").winner()).as("in review: not live yet").isNull();

        catalogListener.onMessage(record("at.product.events", "catalog.product.approved", UUID.randomUUID(),
                payload.replace("IN_REVIEW", "LIVE")));
        BuyBoxView live = listing.offers(red, "USD");
        assertThat(live.winner().view().offer().priceMinor()).isEqualTo(6499);
        assertThat(listing.offers(gold, "USD").winner()).as("seller quantity 0").isNull();
        assertThat(listing.productBuyBoxes(product, "USD")).hasSize(2);

        catalogListener.onMessage(record("at.product.events", "catalog.product.paused", UUID.randomUUID(),
                payload.replace("IN_REVIEW", "PAUSED")));
        assertThat(listing.offers(red, "USD").winner()).isNull();

        catalogListener.onMessage(record("at.product.events", "catalog.product.resumed", UUID.randomUUID(),
                payload.replace("IN_REVIEW", "LIVE")));
        assertThat(listing.offers(red, "USD").winner()).isNotNull();
        UUID takedown = UUID.randomUUID();
        String takenDown = payload.replace("IN_REVIEW", "TAKEN_DOWN");
        catalogListener.onMessage(record("at.product.events", "catalog.product.taken_down", takedown, takenDown));
        assertThat(listing.offers(red, "USD").winner()).as("taken down: cannot win").isNull();
        assertThat(listing.offers(red, "USD").offerCount()).as("taken down: not listed publicly").isZero();
        assertThat(listing.productSummaries(List.of(product), "USD").getFirst().offerCount()).isZero();
        catalogListener.onMessage(record("at.product.events", "catalog.product.reinstated", UUID.randomUUID(),
                payload.replace("IN_REVIEW", "LIVE")));
        assertThat(listing.offers(red, "USD").winner().view().offer().priceMinor()).isEqualTo(6499);
        catalogListener.onMessage(record("at.product.events", "catalog.product.taken_down", takedown, takenDown));
        assertThat(listing.offers(red, "USD").winner()).as("a redelivered takedown is ignored (inbox)").isNotNull();
        catalogListener.onMessage(record("at.product.events", "catalog.product.created", UUID.randomUUID(),
                "{not json"));
    }

    @Test
    void inventoryAvailabilityIsVersionedAndDeduplicated() {
        String product = atin();
        String variant = atin();
        String payload = """
                {"productId":"%s","atin":"%s","ownerKind":"PLATFORM","ownerOrgId":"%s","status":"LIVE",
                 "variants":[{"atin":"%s","sellerSku":"AT-BAS-10","priceMinor":2199,"currency":"USD","quantity":0}]}
                """.formatted(UUID.randomUUID(), product, RETAIL_ORG, variant);
        catalogListener.onMessage(record("at.product.events", "catalog.product.created", UUID.randomUUID(), payload));
        assertThat(listing.offers(variant, "USD").winner()).isNull();

        UUID first = UUID.randomUUID();
        inventoryListener.onMessage(record("at.inventory.events", "inventory.availability.changed", first,
                stock("AT-BAS-10", "HOU-FC", 7, 5)));
        BuyBoxView box = listing.offers(variant, "USD");
        assertThat(box.winner().view().offer().fulfilment()).isEqualTo(Fulfilment.AFROTRANSACT);
        assertThat(box.winner().view().offer().sellerKind()).isEqualTo(SellerKind.PLATFORM);
        assertThat(box.winner().view().availableQty()).isEqualTo(7);

        inventoryListener.onMessage(record("at.inventory.events", "inventory.availability.changed",
                UUID.randomUUID(), stock("AT-BAS-10", "HOU-FC", 0, 4)));
        inventoryListener.onMessage(record("at.inventory.events", "inventory.availability.changed", first,
                stock("AT-BAS-10", "HOU-FC", 0, 9)));
        assertThat(listing.offers(variant, "USD").winner().view().availableQty())
                .as("older version and duplicate event id are ignored").isEqualTo(7);

        inventoryListener.onMessage(record("at.inventory.events", "inventory.availability.changed",
                UUID.randomUUID(), stock("AT-BAS-10", "HOU-FC", 0, 6)));
        assertThat(listing.offers(variant, "USD").winner()).isNull();
        inventoryListener.onMessage(record("at.inventory.events", "inventory.lot.created", UUID.randomUUID(), "{}"));
        assertThat(outboxTypes(variant)).filteredOn("offers.buybox.changed"::equals).hasSize(2);
    }

    @Test
    void sellerEnforcementSuppressesAndRestoresAnOrgsOffers() {
        String org = "org_" + atin().substring(2).toLowerCase(Locale.ROOT) + "suppress0000";
        String product = atin();
        String variant = atin();
        catalogListener.onMessage(record("at.product.events", "catalog.product.created", UUID.randomUUID(), """
                {"productId":"%s","atin":"%s","ownerKind":"SELLER","ownerOrgId":"%s","status":"LIVE",
                 "variants":[{"atin":"%s","sellerSku":"SUP-1","priceMinor":1500,"currency":"USD","quantity":3}]}
                """.formatted(UUID.randomUUID(), product, org, variant)));
        sellerListener.onMessage(record("at.seller-org.events", "seller.org.updated", UUID.randomUUID(),
                "{\"orgId\":\"" + org + "\",\"kind\":\"SELLER\",\"displayName\":\"Ife Bridal\","
                        + "\"status\":\"APPROVED\"}"));
        assertThat(listing.offers(variant, "USD").winner().view().sellerName()).isEqualTo("Ife Bridal");

        String enforcement = "{\"orgId\":\"" + org + "\",\"accountId\":\"acct_1\",\"reason\":\"CHARGES_DISABLED\","
                + "\"occurredAt\":\"2026-10-10T10:00:00Z\"}";
        sellerListener.onMessage(record("at.seller-compliance.events", "seller.listings.hidden", UUID.randomUUID(),
                enforcement));
        BuyBoxView hidden = listing.offers(variant, "USD");
        assertThat(hidden.winner()).isNull();
        assertThat(hidden.offerCount()).isZero();

        sellerListener.onMessage(record("at.seller-compliance.events", "seller.payouts.paused", UUID.randomUUID(),
                enforcement));
        sellerListener.onMessage(record("at.seller-compliance.events", "seller.listings.restored",
                UUID.randomUUID(), enforcement));
        assertThat(listing.offers(variant, "USD").winner()).isNotNull();
        assertThat(outboxTypes(variant)).filteredOn("offers.buybox.changed"::equals).hasSize(3);
    }

    private static String stock(String sku, String node, int available, long version) {
        return """
                {"sku":"%s","nodeId":"%s","ownerOrg":"%s","available":%d,"onHand":%d,"reserved":0,"inbound":0,
                 "damaged":0,"version":%d}
                """.formatted(sku, node, RETAIL_ORG, available, available, version);
    }

    private static ConsumerRecord<String, String> record(String topic, String type, UUID eventId, String value) {
        ConsumerRecord<String, String> rec = new ConsumerRecord<>(topic, 0, 0L, "key", value);
        rec.headers().add(new RecordHeader("event-id", eventId.toString().getBytes(StandardCharsets.UTF_8)));
        rec.headers().add(new RecordHeader("event-type", type.getBytes(StandardCharsets.UTF_8)));
        return rec;
    }
}
