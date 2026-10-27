package com.afrotransact.deliveryruns.adapter.in.messaging;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import com.afrotransact.deliveryruns.application.AttributionService;
import com.afrotransact.deliveryruns.application.OrderPlaced;
import com.afrotransact.deliveryruns.application.PreorderProjectionService;
import com.afrotransact.deliveryruns.application.RunProgressService;
import com.afrotransact.deliveryruns.domain.PreorderFulfilment;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

class OrderEventsListenerTest {

    private final JsonMapper json = JsonMapper.builder().build();

    @Test
    void readsPreorderPackagesAndAttribution() {
        UUID window = UUID.randomUUID();
        String body = """
                {"orderId": "8d1f0f6e-1111-4c3a-9a55-000000000001", "orderNumber": "AT-2026-7KQ3MD",
                 "customerId": "user-1", "currency": "usd", "placedAt": "2026-10-07T16:30:00Z",
                 "shipToName": "Ada Okafor", "shipToCity": "Lubbock", "shipToState": "TX", "shipToPostalCode": "79401",
                 "shipToLine1": "1902 19th St", "shipToPhone": "+1 806 555 0101",
                 "attribution": {"src": "ig", "cr": "story", "po": "LUB-7"},
                 "packages": [
                   {"shipmentId": "s-1", "sellerOrgId": "seller-1", "fulfilmentType": "PREORDER",
                    "windowId": "%s", "fulfilment": "PICKUP", "totalMinor": 2000, "shippingMinor": 0,
                    "lines": [{"variantAtin": "EGU", "title": "Egusi", "quantity": 2}]},
                   {"shipmentId": "s-2", "sellerOrgId": "seller-2", "fulfilmentType": "SELLER_FULFILLED",
                    "deliveryMethod": "CARRIER", "totalMinor": 1500, "lines": []}
                 ]}
                """.formatted(window);
        Optional<OrderPlaced> e = OrderEventsListener.placed(UUID.randomUUID(), json.readTree(body));
        assertThat(e).isPresent();
        OrderPlaced p = e.get();
        assertThat(p.currency()).isEqualTo("USD");
        assertThat(p.src()).isEqualTo("ig");
        assertThat(p.creative()).isEqualTo("story");
        assertThat(p.windowCode()).isEqualTo("LUB-7");
        assertThat(p.phone()).isEqualTo("+1 806 555 0101");
        assertThat(p.packages()).hasSize(1);
        OrderPlaced.Package pkg = p.packages().get(0);
        assertThat(pkg.windowId()).isEqualTo(window);
        assertThat(pkg.fulfilment()).isEqualTo(PreorderFulfilment.PICKUP);
        assertThat(pkg.lines()).singleElement().satisfies(l -> {
            assertThat(l.atin()).isEqualTo("EGU");
            assertThat(l.qty()).isEqualTo(2);
            assertThat(l.sellerOrgId()).isEqualTo("seller-1");
        });
    }

    @Test
    void anOrderOnAStaffScheduledRunHoldsASlotAndIsNotProjectedIntoAWindow() {
        String body = """
                {"orderId": "o-run", "placedAt": "2026-10-07T16:30:00Z",
                 "shipments": [
                   {"shipmentId": "s-1", "sellerOrgId": "org-a", "fulfilmentType": "PREORDER",
                    "windowId": "%s", "preorder": {"windowId": "%s", "source": "RUN"}, "fulfilment": "DOOR",
                    "totalMinor": 3000, "shippingMinor": 799, "lines": [{"variantAtin": "EGU", "quantity": 2}]}]}
                """.formatted(UUID.randomUUID(), UUID.randomUUID());
        assertThat(OrderEventsListener.placed(UUID.randomUUID(), json.readTree(body))).isEmpty();
    }

    @Test
    void ordersWithoutPreorderPackagesAreIgnored() {
        String body = """
                {"orderId": "o-1", "placedAt": "2026-10-07T16:30:00Z",
                 "shipments": [{"shipmentId": "s-1", "fulfilmentType": "SELLER_FULFILLED", "lines": []}]}
                """;
        assertThat(OrderEventsListener.placed(UUID.randomUUID(), json.readTree(body))).isEmpty();
    }

    @Test
    void everyLineKeepsItsOwnSellerAndFallsBackToThePackagesFirstSeller() {
        UUID window = UUID.randomUUID();
        String body = """
                {"orderId": "o-9", "placedAt": "2026-10-07T16:30:00Z",
                 "shipments": [
                   {"shipmentId": "s-1", "sellerOrgId": "org-a", "sellerName": "Mama Tee", "fulfilmentType": "PREORDER",
                    "windowId": "%s", "fulfilment": "DOOR", "totalMinor": 3000, "shippingMinor": 799,
                    "lines": [
                      {"variantAtin": "EGU", "title": "Egusi", "quantity": 2, "sellerOrgId": "org-a",
                       "sellerName": "Mama Tee"},
                      {"variantAtin": "PAL", "title": "Palm oil", "quantity": 1, "sellerOrgId": "org-b",
                       "sellerName": "Lagos Oils"},
                      {"variantAtin": "YAM", "title": "Yam", "quantity": 3}]}
                 ]}
                """.formatted(window);
        OrderPlaced p = OrderEventsListener.placed(UUID.randomUUID(), json.readTree(body)).orElseThrow();
        assertThat(p.packages().get(0).lines()).extracting(l -> l.sellerOrgId() + "/" + l.sellerName())
                .containsExactly("org-a/Mama Tee", "org-b/Lagos Oils", "org-a/Mama Tee");
    }

    @Test
    void preorderShipmentMovesAndDeliveriesDriveTheRunProgress() {
        PreorderProjectionService projection = mock(PreorderProjectionService.class);
        RunProgressService progress = mock(RunProgressService.class);
        OrderEventsListener listener = new OrderEventsListener(projection, progress, mock(AttributionService.class),
                json);
        UUID e1 = UUID.randomUUID();
        UUID e2 = UUID.randomUUID();
        UUID e3 = UUID.randomUUID();

        listener.handle("orders.shipment.status_changed", e1, json.readTree("""
                {"orderId": "o-1", "shipmentId": "s-1", "fulfilmentType": "PREORDER", "from": "confirmed",
                 "to": "picked_up", "occurredAt": "2026-10-23T14:05:00Z"}"""));
        listener.handle("orders.shipment.delivered", e2, json.readTree("""
                {"orderId": "o-1", "shipmentId": "s-1", "fulfilmentType": "PREORDER",
                 "deliveredAt": "2026-10-24T18:30:00Z"}"""));
        listener.handle("orders.shipment.cancelled", e3, json.readTree("""
                {"orderId": "o-2", "shipmentId": "s-2", "cancelledAt": "2026-10-23T09:00:00Z"}"""));

        verify(progress).onShipmentMoved(e1, "o-1", "s-1", "picked_up", Instant.parse("2026-10-23T14:05:00Z"));
        verify(progress).onShipmentDelivered(e2, "o-1", "s-1", Instant.parse("2026-10-24T18:30:00Z"));
        verify(progress).onShipmentCancelled(e3, "o-2", "s-2", Instant.parse("2026-10-23T09:00:00Z"));
        verify(projection).onShipmentCancelled(e3, "o-2", "s-2", Instant.parse("2026-10-23T09:00:00Z"));
    }

    @Test
    void anOrderPlacedWithAnAdvertorialCreativeIsAttributedWhateverItCarries() {
        PreorderProjectionService projection = mock(PreorderProjectionService.class);
        AttributionService attribution = mock(AttributionService.class);
        OrderEventsListener listener = new OrderEventsListener(projection, mock(RunProgressService.class), attribution,
                json);
        listener.handle("orders.order.placed", UUID.randomUUID(), json.readTree("""
                {"orderId": "o-7", "placedAt": "2026-10-23T09:00:00Z",
                 "attribution": {"src": "page", "cr": "adv-0123456789abcdef0123456789abcdef"},
                 "shipments": [{"shipmentId": "s-1", "fulfilmentType": "SELLER_FULFILLED", "lines": []}]}"""));
        verify(attribution).orderPlaced("o-7", "adv-0123456789abcdef0123456789abcdef", "page",
                Instant.parse("2026-10-23T09:00:00Z"));
        verifyNoInteractions(projection);
    }

    @Test
    void anOrderWithoutAttributionIsNotRecorded() {
        AttributionService attribution = mock(AttributionService.class);
        OrderEventsListener listener = new OrderEventsListener(mock(PreorderProjectionService.class),
                mock(RunProgressService.class), attribution, json);
        listener.handle("orders.order.placed", UUID.randomUUID(), json.readTree("""
                {"orderId": "o-8", "placedAt": "2026-10-23T09:00:00Z", "shipments": []}"""));
        verifyNoInteractions(attribution);
    }

    @Test
    void shipmentsOfOtherOrdersThanPreordersNeverTouchARun() {
        PreorderProjectionService projection = mock(PreorderProjectionService.class);
        RunProgressService progress = mock(RunProgressService.class);
        OrderEventsListener listener = new OrderEventsListener(projection, progress, mock(AttributionService.class),
                json);
        listener.handle("orders.shipment.status_changed", UUID.randomUUID(), json.readTree("""
                {"orderId": "o-1", "shipmentId": "s-1", "fulfilmentType": "FBA_AFROTRANSACT", "to": "picked_up"}"""));
        listener.handle("orders.shipment.delivered", UUID.randomUUID(), json.readTree("""
                {"orderId": "o-1", "shipmentId": "s-1", "fulfilmentType": "SELLER_FULFILLED"}"""));
        listener.handle("orders.shipment.delivered", UUID.randomUUID(), json.readTree("""
                {"shipmentId": "s-1", "fulfilmentType": "PREORDER"}"""));
        verifyNoInteractions(progress);
    }
}
