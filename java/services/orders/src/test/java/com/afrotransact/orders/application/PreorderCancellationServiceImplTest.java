package com.afrotransact.orders.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.afrotransact.orders.domain.DefaultShipmentTransitionPolicy;
import com.afrotransact.orders.domain.DeliveryMethod;
import com.afrotransact.orders.domain.DeliveryPreferences;
import com.afrotransact.orders.domain.FulfilmentType;
import com.afrotransact.orders.domain.Order;
import com.afrotransact.orders.domain.OrderLine;
import com.afrotransact.orders.domain.OrderStatus;
import com.afrotransact.orders.domain.OrderTotals;
import com.afrotransact.orders.domain.PreorderFulfilment;
import com.afrotransact.orders.domain.PreorderPackage;
import com.afrotransact.orders.domain.ShipTo;
import com.afrotransact.orders.domain.Shipment;
import com.afrotransact.orders.domain.ShipmentStatus;
import com.afrotransact.orders.domain.StatusChange;
import com.afrotransact.orders.port.FollowUpRepository;
import com.afrotransact.orders.port.OrderRepository;
import com.afrotransact.orders.port.ShipmentFeed;
import com.afrotransact.platform.events.DomainEventPublisher;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.transaction.support.TransactionOperations;

/**
 * ADR 0005 §1: cancelling a window (or the orders of a cancelled run) cancels every pre-order shipment through the
 * ordinary state machine, queues the usual CANCEL follow-up (refund or release), and does so exactly once.
 */
class PreorderCancellationServiceImplTest {

    private static final Instant NOW = Instant.parse("2026-10-24T12:00:00Z");

    private final OrderRepository orders = mock(OrderRepository.class);
    private final FollowUpRepository followUps = mock(FollowUpRepository.class);
    private final DomainEventPublisher events = mock(DomainEventPublisher.class);
    private final ShipmentFeed feed = mock(ShipmentFeed.class);
    private PreorderCancellationServiceImpl service;

    @BeforeEach
    void setUp() {
        Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
        ShipmentTransitions transitions = new ShipmentTransitions(orders, followUps,
                new DefaultShipmentTransitionPolicy(), events, feed, clock);
        service = new PreorderCancellationServiceImpl(orders, transitions, events,
                TransactionOperations.withoutTransaction(), clock);
    }

    private static Shipment preorder(String orderId, int seq, ShipmentStatus status, boolean captured, long fee) {
        OrderLine line = new OrderLine(UUID.randomUUID().toString(), 1, "ATIN-" + seq, "P", "Egusi", null, 2, 1250,
                103, 0, "groceries", false, "US-TX");
        return new Shipment(UUID.randomUUID().toString(), orderId, seq, "org-seller", "Mama Tee's Pantry",
                FulfilmentType.PREORDER, null, DeliveryMethod.AFROTRANSACT_DELIVERY, "standard", null,
                DeliveryPreferences.defaults(), false, false, null, null, status, fee, null, null, LocalDate.now(),
                LocalDate.now().plusDays(3), captured ? "cap-1" : null, captured ? NOW : null, null, null, NOW,
                List.of(line), 0, null, PreorderPackage.placed("win-1", "LBK-7", "Lubbock Pre-order #7",
                LocalDate.now().plusDays(3), NOW.minusSeconds(3600), PreorderFulfilment.DOOR));
    }

    private static Order order(String id, Instant placedAt, Shipment... shipments) {
        return new Order(id, "AT-" + id.substring(0, 6), "cust-" + id.substring(0, 4), "USD", OrderStatus.PLACED,
                OrderTotals.ofShipments(List.of(shipments)), new ShipTo("addr-1", "Ada Obi", "+17135550142",
                "1 Main St", null, "Lubbock", "TX", "79401", "US", null), UUID.randomUUID().toString(), "wf-" + id,
                null, placedAt, NOW, List.of(shipments), 0);
    }

    private void stored(Order o) {
        when(orders.findById(o.id())).thenReturn(Optional.of(o));
        when(orders.lockById(o.id())).thenReturn(Optional.of(o));
        for (Shipment s : o.shipments()) {
            when(orders.orderIdOfShipment(s.id())).thenReturn(Optional.of(o.id()));
        }
    }

    @Test
    void cancellingAWindowCancelsEveryPlacedShipmentThroughTheOrdinaryRefundPath() {
        String o1 = UUID.randomUUID().toString();
        String o2 = UUID.randomUUID().toString();
        Shipment paidAndCaptured = preorder(o1, 1, ShipmentStatus.CONFIRMED, true, 799);
        Shipment authorised = preorder(o2, 1, ShipmentStatus.PLACED, false, 799);
        stored(order(o1, NOW.minusSeconds(7200), paidAndCaptured));
        stored(order(o2, NOW.minusSeconds(7200), authorised));
        when(orders.preorderShipmentsOfWindow("win-1")).thenReturn(List.of(paidAndCaptured.id(), authorised.id()));

        PreorderCancellationService.Result result = service.cancelWindow("win-1", "Window cancelled: low demand");

        assertThat(result).isEqualTo(new PreorderCancellationService.Result(2, 0, 0, 0));
        ArgumentCaptor<Order> saved = ArgumentCaptor.forClass(Order.class);
        verify(orders, times(2)).update(saved.capture());
        assertThat(saved.getAllValues()).allSatisfy(o -> assertThat(o.shipments().get(0).status())
                .isEqualTo(ShipmentStatus.CANCELLED));

        // The usual CANCEL follow-up refunds the captured one in full and voids the other, delivery fee included.
        ArgumentCaptor<FollowUpRepository.FollowUp> tasks = ArgumentCaptor.forClass(FollowUpRepository.FollowUp.class);
        verify(followUps, times(2)).enqueue(tasks.capture());
        assertThat(tasks.getAllValues()).extracting(FollowUpRepository.FollowUp::kind)
                .containsOnly(FollowUpRepository.Kind.CANCEL);
        assertThat(tasks.getAllValues()).extracting(FollowUpRepository.FollowUp::key)
                .containsExactlyInAnyOrder("cancel:" + paidAndCaptured.id(), "cancel:" + authorised.id());
        assertThat(PaymentLines.refundOf(paidAndCaptured).stream().mapToLong(r -> r.itemsMinor() + r.shippingMinor()
                + r.taxMinor()).sum()).as("the refund includes the delivery fee").isEqualTo(paidAndCaptured.totalMinor());

        // notifications tells each customer "refunded in full, delivery fee included" from these fields (S262): a
        // PREORDER shipment, cancelled by AfroTransact (not the customer), with its captured state and full total.
        ArgumentCaptor<Object> cancelledEvents = ArgumentCaptor.forClass(Object.class);
        verify(events, times(2)).publish(eq(OrdersEvents.SHIPMENT_CANCELLED), eq(OrdersEvents.AGGREGATE), any(),
                cancelledEvents.capture());
        assertThat(cancelledEvents.getAllValues()).extracting(e -> (OrdersEventPayloads.ShipmentCancelled) e)
                .extracting(OrdersEventPayloads.ShipmentCancelled::fulfilmentType,
                        OrdersEventPayloads.ShipmentCancelled::windowId, OrdersEventPayloads.ShipmentCancelled::actorType,
                        OrdersEventPayloads.ShipmentCancelled::captured, OrdersEventPayloads.ShipmentCancelled::totalMinor)
                .containsExactlyInAnyOrder(
                        tuple("PREORDER", "win-1", "PLATFORM_ADMIN", true,
                                paidAndCaptured.totalMinor()),
                        tuple("PREORDER", "win-1", "PLATFORM_ADMIN", false,
                                authorised.totalMinor()));
        verify(events, times(2)).publish(eq(OrdersEvents.ORDER_CANCELLED), eq(OrdersEvents.AGGREGATE), any(), any());
        ArgumentCaptor<StatusChange> history = ArgumentCaptor.forClass(StatusChange.class);
        verify(orders, times(2)).appendHistory(history.capture(), any());
        assertThat(history.getAllValues()).allSatisfy(h -> {
            assertThat(h.actorId()).isEqualTo(PreorderCancellationService.ACTOR);
            assertThat(h.reason()).isEqualTo("Window cancelled: low demand");
        });
    }

    @Test
    void cancellingTwiceIsHarmlessAndUnpaidOrDeliveredPackagesAreNotRefundedBlindly() {
        String o1 = UUID.randomUUID().toString();
        String o2 = UUID.randomUUID().toString();
        String o3 = UUID.randomUUID().toString();
        String o4 = UUID.randomUUID().toString();
        Shipment already = preorder(o1, 1, ShipmentStatus.CANCELLED, true, 799);
        Shipment unpaid = preorder(o2, 1, ShipmentStatus.PENDING_PAYMENT, false, 799);
        Shipment unplaced = preorder(o3, 1, ShipmentStatus.PLACED, false, 799);
        Shipment onTheRoad = preorder(o4, 1, ShipmentStatus.OUT_FOR_DELIVERY, true, 799);
        stored(order(o1, NOW.minusSeconds(7200), already));
        stored(order(o2, null, unpaid));
        stored(order(o3, null, unplaced));
        stored(order(o4, NOW.minusSeconds(7200), onTheRoad));
        when(orders.preorderShipmentsOfWindow("win-1")).thenReturn(List.of(already.id(), unpaid.id(), unplaced.id(),
                onTheRoad.id()));

        PreorderCancellationService.Result result = service.cancelWindow("win-1", "Window cancelled");

        assertThat(result).isEqualTo(new PreorderCancellationService.Result(0, 1, 2, 1));
        verify(orders, never()).update(any());
        verify(followUps, never()).enqueue(any());
        // The one that can no longer be cancelled is reported for staff instead of being silently skipped.
        ArgumentCaptor<Object> payload = ArgumentCaptor.forClass(Object.class);
        verify(events).publish(eq(OrdersEvents.PREORDER_REFUND_FAILED), eq(OrdersEvents.AGGREGATE), eq(o4),
                payload.capture());
        OrdersEventPayloads.PreorderRefundFailed failed = (OrdersEventPayloads.PreorderRefundFailed) payload
                .getValue();
        assertThat(failed.shipmentId()).isEqualTo(onTheRoad.id());
        assertThat(failed.windowId()).isEqualTo("win-1");
        assertThat(failed.code()).isEqualTo("orders.invalid-transition");
    }

    @Test
    void aCancelledRunCancelsThePreorderShipmentsOfItsOrdersOnly() {
        String o1 = UUID.randomUUID().toString();
        Shipment preorder = preorder(o1, 1, ShipmentStatus.CONFIRMED, false, 799);
        stored(order(o1, NOW.minusSeconds(7200), preorder));
        when(orders.findById("missing")).thenReturn(Optional.empty());

        PreorderCancellationService.Result result = service.cancelOrders(List.of(o1, o1, "missing"),
                "Delivery run cancelled: no driver");

        assertThat(result).as("the duplicate id is processed once, the unknown one skipped")
                .isEqualTo(new PreorderCancellationService.Result(1, 0, 0, 0));
        verify(followUps).enqueue(any());
    }
}
