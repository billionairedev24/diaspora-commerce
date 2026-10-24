package com.afrotransact.orders.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import com.afrotransact.orders.domain.ActorKind;
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
import com.afrotransact.orders.domain.TransitionActor;
import com.afrotransact.orders.domain.TransitionSource;
import com.afrotransact.orders.port.CouponsClient;
import com.afrotransact.orders.port.FollowUpRepository;
import com.afrotransact.orders.port.InventoryClient;
import com.afrotransact.orders.port.OrderCouponRepository;
import com.afrotransact.orders.port.OrderRepository;
import com.afrotransact.orders.port.PaymentsClient;
import com.afrotransact.orders.port.ShipmentFeed;
import com.afrotransact.platform.events.DomainEventPublisher;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.transaction.support.TransactionOperations;

/**
 * ADR 0005 §5: the seller is paid at delivery, after the settlement hold. A pre-order package follows the ordinary
 * path: delivery queues the SETTLE follow-up, which tells payments the order was delivered; payments then holds the
 * seller net for its settlement period (default 7 days) before transferring it. Nothing in orders is window specific.
 */
class PreorderSettlementTest {

    private static final Instant NOW = Instant.parse("2026-10-25T18:00:00Z");
    private static final String ORDER = UUID.randomUUID().toString();

    private final OrderRepository orders = mock(OrderRepository.class);
    private final FollowUpRepository followUps = mock(FollowUpRepository.class);
    private final DomainEventPublisher events = mock(DomainEventPublisher.class);
    private final ShipmentTransitions transitions = new ShipmentTransitions(orders, followUps,
            new DefaultShipmentTransitionPolicy(), events, mock(ShipmentFeed.class), Clock.fixed(NOW, ZoneOffset.UTC));

    private static Shipment preorder(int seq, DeliveryMethod method, ShipmentStatus status, Instant deliveredAt) {
        OrderLine line = new OrderLine(UUID.randomUUID().toString(), seq, "ATIN-" + seq, "P", "Egusi", null, 2, 1250,
                103, 0, "groceries", false, "US-TX");
        return new Shipment(UUID.randomUUID().toString(), ORDER, seq, "org-seller-" + seq, "Mama Tee's Pantry",
                FulfilmentType.PREORDER, null, method, "standard", method == DeliveryMethod.PICKUP ? "PICKUP-1" : null,
                DeliveryPreferences.defaults(), false, false, null, null, status, 799, null, null, LocalDate.now(),
                LocalDate.now(), "cap-" + seq, NOW.minusSeconds(7200), deliveredAt, null, NOW.minusSeconds(3600),
                List.of(line), 0, null, PreorderPackage.placed("win-1", "LBK-7", "Lubbock Pre-order #7",
                LocalDate.now(), NOW.minusSeconds(86_400 * 3L),
                method == DeliveryMethod.PICKUP ? PreorderFulfilment.PICKUP : PreorderFulfilment.DOOR));
    }

    private static Order order(Shipment... shipments) {
        return new Order(ORDER, "AT-2026-WIN1", "cust-1", "USD", OrderStatus.PLACED,
                OrderTotals.ofShipments(List.of(shipments)), new ShipTo("addr-1", "Ada Obi", "+17135550142",
                "1 Main St", null, "Lubbock", "TX", "79401", "US", null), "pay-1", "wf-1", null,
                NOW.minusSeconds(86_400 * 5L), NOW.minusSeconds(86_400 * 5L), List.of(shipments), 0);
    }

    private List<FollowUpRepository.FollowUp> queuedAfterDelivering(Order order, Shipment s, ActorKind actor,
                                                                   TransitionSource source, String reason) {
        transitions.step(order, s, ShipmentStatus.DELIVERED, new TransitionActor(actor, "driver-7", null), reason,
                source, "evt-delivered-" + s.id(), NOW, null);
        ArgumentCaptor<FollowUpRepository.FollowUp> tasks = ArgumentCaptor.forClass(FollowUpRepository.FollowUp.class);
        verify(followUps, atLeastOnce()).enqueue(tasks.capture());
        return tasks.getAllValues();
    }

    @Test
    void aDoorDeliveryByTheDriverStartsTheSettlementOfTheOrder() {
        Shipment s = preorder(1, DeliveryMethod.AFROTRANSACT_DELIVERY, ShipmentStatus.OUT_FOR_DELIVERY, null);

        List<FollowUpRepository.FollowUp> queued = queuedAfterDelivering(order(s), s, ActorKind.COURIER,
                TransitionSource.DRIVER, null);

        assertThat(queued).extracting(FollowUpRepository.FollowUp::key).containsExactly("settle:" + ORDER);
        assertThat(queued.getFirst().kind()).isEqualTo(FollowUpRepository.Kind.SETTLE);
        verify(events).publish(eq(OrdersEvents.SHIPMENT_DELIVERED), eq(OrdersEvents.AGGREGATE), eq(ORDER), any());
    }

    @Test
    void aPickupHandoverConfirmedByStaffStartsTheSettlementToo() {
        Shipment s = preorder(1, DeliveryMethod.PICKUP, ShipmentStatus.READY, null);

        List<FollowUpRepository.FollowUp> queued = queuedAfterDelivering(order(s), s, ActorKind.PLATFORM_ADMIN,
                TransitionSource.API, "Customer collected at the community hall");

        assertThat(queued).extracting(FollowUpRepository.FollowUp::kind)
                .containsExactly(FollowUpRepository.Kind.SETTLE);
    }

    @Test
    void inAMultiPackageOrderEachPackageSettlesOnItsOwnAndTheOrderOnceAllAreDelivered() {
        Shipment first = preorder(1, DeliveryMethod.AFROTRANSACT_DELIVERY, ShipmentStatus.OUT_FOR_DELIVERY, null);
        Shipment second = preorder(2, DeliveryMethod.AFROTRANSACT_DELIVERY, ShipmentStatus.DELIVERED, NOW.minusSeconds(60));

        List<FollowUpRepository.FollowUp> queued = queuedAfterDelivering(order(first, second), first,
                ActorKind.COURIER, TransitionSource.DRIVER, null);

        assertThat(queued).extracting(FollowUpRepository.FollowUp::key)
                .containsExactlyInAnyOrder("settle:" + ORDER, "settle-shipment:" + first.id());
    }

    @Test
    void theSettleFollowUpTellsPaymentsWhenTheOrderWasDeliveredSoItsHoldStartsThen() {
        PaymentsClient payments = mock(PaymentsClient.class);
        Instant deliveredAt = NOW.minus(Duration.ofHours(3));
        Shipment s = preorder(1, DeliveryMethod.AFROTRANSACT_DELIVERY, ShipmentStatus.DELIVERED, deliveredAt);
        OrderRepository repo = mock(OrderRepository.class);
        when(repo.findById(ORDER)).thenReturn(Optional.of(order(s)));
        FollowUpRepository tasks = mock(FollowUpRepository.class);
        when(tasks.claimDue(any(), any(), anyInt())).thenReturn(List.of(
                new FollowUpRepository.FollowUp(UUID.randomUUID().toString(), "settle:" + ORDER,
                        FollowUpRepository.Kind.SETTLE, ORDER, null, 0, NOW)));
        OrdersSettings settings = new OrdersSettings("org-retail", Duration.ofMinutes(15), Duration.ofSeconds(5),
                Duration.ofHours(1), Set.of(), Map.of(), 10, 3, "America/Chicago");
        FollowUpServiceImpl service = new FollowUpServiceImpl(tasks, repo, payments, mock(InventoryClient.class),
                mock(CouponsClient.class), mock(OrderCouponRepository.class), TransactionOperations.withoutTransaction(),
                settings, events, Clock.fixed(NOW, ZoneOffset.UTC));

        assertThat(service.runDue()).isEqualTo(1);

        verify(payments).delivered(ORDER, deliveredAt);
    }
}
