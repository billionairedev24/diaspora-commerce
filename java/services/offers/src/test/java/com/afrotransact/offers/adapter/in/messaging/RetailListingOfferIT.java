package com.afrotransact.offers.adapter.in.messaging;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

import com.afrotransact.offers.application.BuyBoxView;
import com.afrotransact.offers.application.OfferListingService;
import com.afrotransact.offers.domain.OfferStatus;
import com.afrotransact.offers.it.OffersIntegrationTest;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.header.internals.RecordHeader;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * S281: AfroTransact Retail's "list on storefront" — the retail item's listing (LIVE / PAUSED) drives the first-party
 * offer of the linked variant: the inventory SKU becomes the retail item SKU (where retail receipts put the lots), so
 * received stock reaches the storefront; PAUSED takes it off sale.
 */
class RetailListingOfferIT extends OffersIntegrationTest {

    private final CatalogEventListener catalog;
    private final InventoryEventListener availability;
    private final RetailEventListener retail;
    private final OfferListingService listing;

    @Autowired
    RetailListingOfferIT(JdbcClient jdbc, CatalogEventListener catalog, InventoryEventListener availability,
                         RetailEventListener retail, OfferListingService listing) {
        super(jdbc);
        this.catalog = catalog;
        this.availability = availability;
        this.retail = retail;
        this.listing = listing;
    }

    @Test
    void aListedRetailItemSellsTheStockReceivedUnderItsSku() {
        String product = atin();
        String variant = atin();
        catalog.onMessage(record("at.product.events", "catalog.product.created", UUID.randomUUID(), """
                {"productId":"%s","atin":"%s","ownerKind":"PLATFORM","ownerOrgId":"%s","status":"LIVE",
                 "variants":[{"atin":"%s","priceMinor":1099,"currency":"USD","quantity":0}]}
                """.formatted(UUID.randomUUID(), product, RETAIL_ORG, variant)));
        assertThat(listing.offers(variant, "USD").winner()).as("no stock yet").isNull();

        // Lots received by retail are stocked under the retail SKU, which the offer does not sell yet.
        availability.onMessage(record("at.inventory.events", "inventory.availability.changed", UUID.randomUUID(),
                stock("AT-JOL-200061", 24, 1)));
        assertThat(listing.offers(variant, "USD").winner()).isNull();

        UUID listed = UUID.randomUUID();
        String item = """
                {"itemId":"%s","sku":"AT-JOL-200061","gtin":"086001200061","atin":"%s","name":"Jollof Seasoning",
                 "listingStatus":"%s","priceMinor":1199,"currency":"USD"}
                """;
        UUID itemId = UUID.randomUUID();
        retail.onMessage(record("at.retail.events", "retail.item.listing-changed", listed,
                item.formatted(itemId, variant, "LIVE")));
        retail.onMessage(record("at.retail.events", "retail.item.listing-changed", listed,
                item.formatted(itemId, variant, "LIVE")));
        BuyBoxView box = listing.offers(variant, "USD");
        assertThat(box.winner()).as("listed: the offer now sells the retail SKU's stock").isNotNull();
        assertThat(box.winner().view().offer().inventorySku()).isEqualTo("AT-JOL-200061");
        assertThat(box.winner().view().offer().priceMinor()).as("retail price").isEqualTo(1199);
        assertThat(box.winner().view().availableQty()).isEqualTo(24);
        assertThat(outboxTypes(variant)).contains("offers.offer.updated");

        retail.onMessage(record("at.retail.events", "retail.item.listing-changed", UUID.randomUUID(),
                item.formatted(itemId, variant, "PAUSED")));
        assertThat(listing.offers(variant, "USD").winner()).as("paused: off sale").isNull();
        assertThat(jdbcStatus(variant)).isEqualTo(OfferStatus.PAUSED.name());

        retail.onMessage(record("at.retail.events", "retail.item.listing-changed", UUID.randomUUID(),
                item.formatted(itemId, variant, "LIVE")));
        assertThat(listing.offers(variant, "USD").winner().view().availableQty()).isEqualTo(24);

        // Unlinked items, drafts and other retail events change nothing.
        retail.onMessage(record("at.retail.events", "retail.item.listing-changed", UUID.randomUUID(),
                "{\"itemId\":\"%s\",\"sku\":\"AT-X-1\",\"listingStatus\":\"LIVE\"}".formatted(UUID.randomUUID())));
        retail.onMessage(record("at.retail.events", "retail.po.approved", UUID.randomUUID(), "{}"));
        assertThat(jdbcStatus(variant)).isEqualTo(OfferStatus.ACTIVE.name());
    }

    private String jdbcStatus(String variant) {
        return jdbc.sql("select status from offer where atin = :atin and seller_org = :org")
                .param("atin", variant).param("org", RETAIL_ORG).query(String.class).single();
    }

    private static String stock(String sku, int available, long version) {
        return """
                {"sku":"%s","nodeId":"HOU-FC","ownerOrg":"%s","available":%d,"onHand":%d,"reserved":0,"version":%d}
                """.formatted(sku, RETAIL_ORG, available, available, version);
    }

    private static ConsumerRecord<String, String> record(String topic, String type, UUID eventId, String value) {
        ConsumerRecord<String, String> rec = new ConsumerRecord<>(topic, 0, 0L, "key", value);
        rec.headers().add(new RecordHeader("event-id", eventId.toString().getBytes(StandardCharsets.UTF_8)));
        rec.headers().add(new RecordHeader("event-type", type.getBytes(StandardCharsets.UTF_8)));
        return rec;
    }
}
