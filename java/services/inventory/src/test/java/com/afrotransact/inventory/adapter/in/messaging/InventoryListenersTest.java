package com.afrotransact.inventory.adapter.in.messaging;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import com.afrotransact.inventory.application.InboundCommands;
import com.afrotransact.inventory.application.InboundShipmentService;
import com.afrotransact.inventory.application.OfferLinkSyncService;
import com.afrotransact.platform.events.consumer.NonRetryableEventException;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.header.internals.RecordHeader;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

/** Wire formats of the retail shipment (S226) and offers (S067) events inventory consumes. */
class InventoryListenersTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    @Test
    void retailStageChangesBecomeShipmentNotices() {
        List<InboundCommands.RetailShipmentNotice> seen = new ArrayList<>();
        InboundShipmentService inbound = (InboundShipmentService) Proxy.newProxyInstance(
                getClass().getClassLoader(), new Class<?>[] {InboundShipmentService.class}, (proxy, method, args) -> {
                    if (method.getName().equals("retailShipmentChanged")) {
                        seen.add((InboundCommands.RetailShipmentNotice) args[1]);
                    }
                    return null;
                });
        RetailShipmentEventListener listener = new RetailShipmentEventListener(inbound, JSON);
        String payload = """
                {"shipmentId":"%s","reference":"SHP-2610-GH01","poId":"%s","stage":"DEPARTED","mode":"OCEAN_FCL",
                 "eta":"2026-11-02","nodeId":"HOU-FC","units":1800,"note":null,
                 "lines":[{"sku":"AT-JOL-200061","qty":1200},{"sku":"AT-SHI-200124","qty":600},{"sku":null}]}
                """.formatted(UUID.randomUUID(), UUID.randomUUID());
        listener.onMessage(record("retail.shipment.stage-changed", UUID.randomUUID().toString(), payload));
        listener.onMessage(record("retail.shipment.booked", UUID.randomUUID().toString(), payload));
        assertThatThrownBy(() -> listener.onMessage(record("retail.shipment.stage-changed", "not-a-uuid", payload)))
                .isInstanceOf(NonRetryableEventException.class);
        assertThatThrownBy(() -> listener.onMessage(record("retail.shipment.stage-changed", UUID.randomUUID().toString(), "{oops")))
                .isInstanceOf(NonRetryableEventException.class);

        assertThat(seen).hasSize(1);
        assertThat(seen.getFirst().reference()).isEqualTo("SHP-2610-GH01");
        assertThat(seen.getFirst().stage()).isEqualTo("DEPARTED");
        assertThat(seen.getFirst().eta()).hasToString("2026-11-02");
        assertThat(seen.getFirst().lines()).extracting(InboundCommands.NoticeLine::sku)
                .containsExactly("AT-JOL-200061", "AT-SHI-200124");
    }

    @Test
    void offerEventsFeedTheFbaOfferProjection() {
        List<OfferLinkSyncService.OfferState> seen = new ArrayList<>();
        OfferLinkSyncService links = (eventId, offer) -> seen.add(offer);
        OffersEventListener listener = new OffersEventListener(links, JSON);
        UUID offerId = UUID.randomUUID();
        String payload = """
                {"offerId":"%s","atin":"AT0K7Q2M9H","sellerOrgId":"org_lagos","inventorySku":"ATF3XK8P2QL",
                 "fulfilment":"AFROTRANSACT","status":"ACTIVE","productLive":true,"priceMinor":1299}
                """.formatted(offerId);
        listener.onMessage(record("offers.offer.created", UUID.randomUUID().toString(), payload));
        listener.onMessage(record("offers.offer.withdrawn", UUID.randomUUID().toString(), payload));
        listener.onMessage(record("offers.buybox.changed", UUID.randomUUID().toString(), payload));

        assertThat(seen).hasSize(2);
        assertThat(seen.getFirst().inventorySku()).isEqualTo("ATF3XK8P2QL");
        assertThat(seen.getFirst().productLive()).isTrue();
        assertThat(seen.get(1).status()).as("withdrawn events say so whatever the payload").isEqualTo("WITHDRAWN");
    }

    private static ConsumerRecord<String, String> record(String type, String eventId, String value) {
        ConsumerRecord<String, String> rec = new ConsumerRecord<>("topic", 0, 0L, "key", value);
        rec.headers().add(new RecordHeader("event-id", eventId.getBytes(StandardCharsets.UTF_8)));
        rec.headers().add(new RecordHeader("event-type", type.getBytes(StandardCharsets.UTF_8)));
        return rec;
    }
}
