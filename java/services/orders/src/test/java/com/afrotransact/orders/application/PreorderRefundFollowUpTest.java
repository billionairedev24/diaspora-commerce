package com.afrotransact.orders.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

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
import com.afrotransact.orders.port.CouponsClient;
import com.afrotransact.orders.port.FollowUpRepository;
import com.afrotransact.orders.port.InventoryClient;
import com.afrotransact.orders.port.OrderCouponRepository;
import com.afrotransact.orders.port.OrderRepository;
import com.afrotransact.orders.port.PaymentsClient;
import com.afrotransact.orders.port.UpstreamFailure;
import com.afrotransact.platform.events.DomainEventPublisher;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.transaction.support.TransactionOperations;

/**
 * The CANCEL follow-up of a PREORDER shipment (window or run cancelled): refund in full, or release the authorisation
 * when nothing was captured, then tell delivery-runs (and so staff) how it went.
 */
class PreorderRefundFollowUpTest {

    private static final Instant NOW = Instant.parse("2026-10-24T12:00:00Z");
    private static final String ORDER = UUID.randomUUID().toString();

    private final FollowUpRepository tasks = mock(FollowUpRepository.class);
    private final OrderRepository orders = mock(OrderRepository.class);
    private final PaymentsClient payments = mock(PaymentsClient.class);
    private final InventoryClient inventory = mock(InventoryClient.class);
    private final DomainEventPublisher events = mock(DomainEventPublisher.class);
    private FollowUpServiceImpl service;

    @BeforeEach
    void setUp() {
        OrdersSettings settings = new OrdersSettings("org-retail", Duration.ofMinutes(15), Duration.ofSeconds(5),
                Duration.ofHours(1), Set.of(), Map.of(), 10, 3, "America/Chicago");
        service = new FollowUpServiceImpl(tasks, orders, payments, inventory, mock(CouponsClient.class),
                mock(OrderCouponRepository.class), TransactionOperations.withoutTransaction(), settings, events,
                Clock.fixed(NOW, ZoneOffset.UTC));
    }

    private static Shipment preorder(ShipmentStatus status, boolean captured) {
        OrderLine line = new OrderLine(UUID.randomUUID().toString(), 1, "ATIN-1", "P", "Egusi", null, 2, 1250, 103, 0,
                "groceries", false, "US-TX");
        return new Shipment(UUID.randomUUID().toString(), ORDER, 1, "org-seller", "Mama Tee's Pantry",
                FulfilmentType.PREORDER, null, DeliveryMethod.AFROTRANSACT_DELIVERY, "standard", null,
                DeliveryPreferences.defaults(), false, false, null, null, status, 799, null, null, LocalDate.now(),
                LocalDate.now().plusDays(3), captured ? "cap-1" : null, captured ? NOW : null, null, null, NOW,
                List.of(line), 0, null, PreorderPackage.placed("win-1", "LBK-7", "Lubbock Pre-order #7",
                LocalDate.now().plusDays(3), NOW.minusSeconds(3600), PreorderFulfilment.DOOR));
    }

    private Shipment due(Shipment s) {
        Order order = new Order(ORDER, "AT-2026-WIN1", "cust-1", "USD", OrderStatus.CANCELLED,
                OrderTotals.ofShipments(List.of(s)), new ShipTo("addr-1", "Ada Obi", "+17135550142", "1 Main St", null,
                "Lubbock", "TX", "79401", "US", null), "pay-1", "wf-1", null, NOW.minusSeconds(7200), NOW,
                List.of(s), 0);
        when(orders.findById(ORDER)).thenReturn(Optional.of(order));
        when(tasks.claimDue(any(), any(), anyInt())).thenReturn(List.of(new FollowUpRepository.FollowUp(
                UUID.randomUUID().toString(), "cancel:" + s.id(), FollowUpRepository.Kind.CANCEL, ORDER, s.id(), 0,
                NOW)));
        return s;
    }

    @Test
    void aCapturedPreorderIsRefundedInFullWithItsDeliveryFeeAndReportedRefunded() {
        Shipment s = due(preorder(ShipmentStatus.CANCELLED, true));
        when(payments.refund(eq("pay-1"), eq("refund-" + s.id()), anyList(), anyString())).thenReturn("re_1");

        assertThat(service.runDue()).isEqualTo(1);

        ArgumentCaptor<List<PaymentsClient.RefundLine>> lines = ArgumentCaptor.captor();
        verify(payments).refund(eq("pay-1"), eq("refund-" + s.id()), lines.capture(), anyString());
        assertThat(lines.getValue().stream().mapToLong(l -> l.itemsMinor() + l.shippingMinor() + l.taxMinor()).sum())
                .as("items + tax + the 7.99 delivery fee").isEqualTo(s.totalMinor());
        assertThat(lines.getValue().stream().mapToLong(PaymentsClient.RefundLine::shippingMinor).sum())
                .isEqualTo(799);
        OrdersEventPayloads.PreorderRefundSettled settled = published(OrdersEvents.PREORDER_REFUND_SETTLED,
                OrdersEventPayloads.PreorderRefundSettled.class);
        assertThat(settled.status()).isEqualTo("REFUNDED");
        assertThat(settled.amountMinor()).isEqualTo(s.totalMinor());
        assertThat(settled.windowId()).isEqualTo("win-1");
        assertThat(settled.orderId()).isEqualTo(ORDER);
        verify(tasks).done(anyString(), any());
    }

    @Test
    void anUncapturedPreorderReleasesTheAuthorisationAndIsReportedVoided() {
        Shipment s = due(preorder(ShipmentStatus.CANCELLED, false));

        assertThat(service.runDue()).isEqualTo(1);

        verify(payments).cancel(eq("pay-1"), anyString());
        verify(payments, never()).refund(anyString(), anyString(), anyList(), anyString());
        OrdersEventPayloads.PreorderRefundSettled settled = published(OrdersEvents.PREORDER_REFUND_SETTLED,
                OrdersEventPayloads.PreorderRefundSettled.class);
        assertThat(settled.status()).isEqualTo("VOIDED");
        assertThat(settled.amountMinor()).isEqualTo(s.totalMinor());
        // Stock held for the pre-order's lines is released where inventory has a reservation for them.
        verify(inventory).releaseGroups(eq(ORDER), eq(s.id()), anyList(), anyString());
    }

    @Test
    void noSuchReservationDoesNotStopTheRefundFromBeingReported() {
        due(preorder(ShipmentStatus.CANCELLED, false));
        doThrow(new UpstreamFailure("inventory", 404, "inventory.not-found", "none", false, null,
                null)).when(inventory).releaseGroups(anyString(), anyString(), anyList(), anyString());

        assertThat(service.runDue()).isEqualTo(1);

        verify(events).publish(eq(OrdersEvents.PREORDER_REFUND_SETTLED), eq(OrdersEvents.AGGREGATE), eq(ORDER), any());
    }

    @Test
    void aRefundThatFailsForGoodIsReportedForStaff() {
        Shipment s = due(preorder(ShipmentStatus.CANCELLED, true));
        when(payments.refund(anyString(), anyString(), anyList(), anyString())).thenThrow(new UpstreamFailure(
                "payments", 422, "payments.charge-already-refunded", "Charge ch_1 has no refundable amount", false,
                null, null));

        assertThat(service.runDue()).isZero();

        verify(tasks).fail(anyString(), eq(1), anyString());
        OrdersEventPayloads.PreorderRefundFailed failed = published(OrdersEvents.PREORDER_REFUND_FAILED,
                OrdersEventPayloads.PreorderRefundFailed.class);
        assertThat(failed.code()).isEqualTo("payments.charge-already-refunded");
        assertThat(failed.shipmentId()).isEqualTo(s.id());
        assertThat(failed.windowId()).isEqualTo("win-1");
        verify(events, never()).publish(eq(OrdersEvents.PREORDER_REFUND_SETTLED), anyString(), anyString(), any());
    }

    @Test
    void aTransientFailureRetriesWithoutReportingAFailure() {
        due(preorder(ShipmentStatus.CANCELLED, true));
        when(payments.refund(anyString(), anyString(), anyList(), anyString())).thenThrow(new UpstreamFailure(
                "payments", 503, "payments.unavailable", "down", true, null, null));

        assertThat(service.runDue()).isZero();

        verify(tasks).retry(anyString(), eq(1), any(), anyString());
        verify(events, never()).publish(eq(OrdersEvents.PREORDER_REFUND_FAILED), anyString(), anyString(), any());
    }

    private <T> T published(String type, Class<T> payloadType) {
        ArgumentCaptor<Object> payload = ArgumentCaptor.forClass(Object.class);
        verify(events).publish(eq(type), eq(OrdersEvents.AGGREGATE), eq(ORDER), payload.capture());
        return payloadType.cast(payload.getValue());
    }
}
