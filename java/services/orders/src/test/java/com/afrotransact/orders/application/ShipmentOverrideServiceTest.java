package com.afrotransact.orders.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import com.afrotransact.orders.domain.ActorKind;
import com.afrotransact.orders.domain.CourierProofPolicy;
import com.afrotransact.orders.domain.DefaultShipmentTransitionPolicy;
import com.afrotransact.orders.domain.DeliveryMethod;
import com.afrotransact.orders.domain.DeliveryPreferences;
import com.afrotransact.orders.domain.FulfilmentType;
import com.afrotransact.orders.domain.HandoverPolicy;
import com.afrotransact.orders.domain.LocalDelivery;
import com.afrotransact.orders.domain.Order;
import com.afrotransact.orders.domain.OrderLine;
import com.afrotransact.orders.domain.OrderStatus;
import com.afrotransact.orders.domain.OrderTotals;
import com.afrotransact.orders.domain.OrdersRuleViolation;
import com.afrotransact.orders.domain.ShipTo;
import com.afrotransact.orders.domain.Shipment;
import com.afrotransact.orders.domain.ShipmentStatus;
import com.afrotransact.orders.domain.StatusChange;
import com.afrotransact.orders.domain.TransitionRefused;
import com.afrotransact.orders.port.FollowUpRepository;
import com.afrotransact.orders.port.OrderRepository;
import com.afrotransact.orders.port.ShipmentFeed;
import com.afrotransact.platform.events.DomainEventPublisher;
import com.afrotransact.platform.test.tenant.TenantFixture;
import com.afrotransact.platform.web.error.ForbiddenActionException;
import com.afrotransact.platform.web.error.NotFoundException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * Who may move a shipment, per fulfilment mode: the override of a seller-shipped shipment (permission, reason,
 * forward only, audited), the general status change refused for people on AfroTransact-delivered shipments (the
 * delivery steps carry the proof and the audit), tenant isolation and idempotency.
 */
class ShipmentOverrideServiceTest {

    private final TenantFixture tenants = new TenantFixture();
    private final OrderRepository orders = mock(OrderRepository.class);
    private final DomainEventPublisher events = mock(DomainEventPublisher.class);
    private final AtomicReference<Order> stored = new AtomicReference<>();
    private ShipmentServiceImpl service;
    private String shipmentId;

    @BeforeEach
    void setUp() {
        Clock clock = Clock.fixed(Instant.parse("2026-11-14T15:00:00Z"), ZoneOffset.UTC);
        OrdersAccess access = new OrdersAccess(tenants.permissions(), tenants.access(), tenants.resolver());
        DefaultShipmentTransitionPolicy policy = new DefaultShipmentTransitionPolicy();
        ShipmentTransitions transitions = new ShipmentTransitions(orders, mock(FollowUpRepository.class), policy,
                events, mock(ShipmentFeed.class), clock);
        service = new ShipmentServiceImpl(access, orders, transitions, mock(CourierProofPolicy.class),
                mock(HandoverPolicy.class), mock(OrderViews.class), mock(OrdersSettings.class), clock);
    }

    private void place(DeliveryMethod method, LocalDelivery local, ShipmentStatus status) {
        String orderId = UUID.randomUUID().toString();
        OrderLine line = new OrderLine(UUID.randomUUID().toString(), 1, "ATIN-1", "P-1", "Jollof spice mix", null, 2,
                1500, 124, 0, "groceries", false, "US-TX");
        Shipment shipment = new Shipment(UUID.randomUUID().toString(), orderId, 1, "org-seller", "Mama Put Pantry",
                FulfilmentType.SELLER_FULFILLED, null, method, "local",
                method == DeliveryMethod.PICKUP ? "PICKUP-1" : null, DeliveryPreferences.defaults(), false, false,
                null, null, status, 599, null, null, LocalDate.now(), LocalDate.now().plusDays(3), null, null, null,
                null, Instant.now(), List.of(line), 0, local, null);
        shipmentId = shipment.id();
        Order order = new Order(orderId, "AT-2026-F7MGMD", "cust-1", "USD", OrderStatus.PLACED,
                OrderTotals.ofShipments(List.of(shipment)), new ShipTo("addr-1", "Ada Obi", "+17135550142",
                "1 Main St", null, "Houston", "TX", "77002", "US", null), UUID.randomUUID().toString(),
                "checkout-" + orderId, null, Instant.now(), Instant.now(), List.of(shipment), 0);
        stored.set(order);
        when(orders.orderIdOfShipment(shipmentId)).thenReturn(Optional.of(orderId));
        when(orders.lockById(orderId)).thenAnswer(i -> Optional.of(stored.get()));
        when(orders.findById(orderId)).thenAnswer(i -> Optional.of(stored.get()));
        doAnswer(i -> {
            stored.set(i.getArgument(0));
            return null;
        }).when(orders).update(any());
    }

    private static LocalDelivery sellerStore() {
        return new LocalDelivery("loc-1", 29.7, -95.3, 4.0, 5.0, "ROAD_OSRM", 599, 250, 1, 12.0);
    }

    private ShipmentStatus status() {
        return stored.get().shipment(shipmentId).status();
    }

    // ----------------------------------------------------------------------------------------- the override

    @Test
    void anOverrideMovesASellerShippedShipmentAndRecordsWhoWhenAndWhy() {
        place(DeliveryMethod.SELLER_SHIPS, null, ShipmentStatus.PICKED_UP);
        tenants.as(TenantFixture.staff("orders:override-shipment", "orders:read"));

        service.override(shipmentId, "delivered", "Customer confirmed by phone, seller forgot to update", "k1");

        assertThat(status()).isEqualTo(ShipmentStatus.DELIVERED);
        ArgumentCaptor<StatusChange> change = ArgumentCaptor.forClass(StatusChange.class);
        verify(orders).appendHistory(change.capture(), any());
        assertThat(change.getValue().actorKind()).isEqualTo(ActorKind.ADMIN_OVERRIDE);
        assertThat(change.getValue().actorId()).isNotBlank();
        assertThat(change.getValue().reason()).isEqualTo("Customer confirmed by phone, seller forgot to update");
        assertThat(change.getValue().occurredAt()).isEqualTo(Instant.parse("2026-11-14T15:00:00Z"));
        verify(events).publish(eq(OrdersEvents.SHIPMENT_DELIVERED), eq(OrdersEvents.AGGREGATE), any(), any());
    }

    @Test
    void anOverrideNeedsTheOverridePermissionNotJustOrdersManage() {
        place(DeliveryMethod.SELLER_SHIPS, null, ShipmentStatus.PICKED_UP);
        tenants.as(TenantFixture.staff("orders:manage", "orders:update-status", "delivery:manage", "orders:read"));
        assertThatThrownBy(() -> service.override(shipmentId, "delivered", "Because the customer called", "k1"))
                .isInstanceOf(ForbiddenActionException.class);
        assertThat(status()).isEqualTo(ShipmentStatus.PICKED_UP);
    }

    @Test
    void aSellerACustomerAServiceAndADriverCannotOverride() {
        place(DeliveryMethod.SELLER_SHIPS, null, ShipmentStatus.PICKED_UP);
        for (var actor : List.of(
                TenantFixture.sellerOf("org-seller", "orders:override-shipment", "orders:update-status"),
                TenantFixture.customer("cust-1", "orders:override-shipment"),
                TenantFixture.service("orders:override-shipment"),
                TenantFixture.driver("drv-1", "orders:override-shipment"))) {
            tenants.as(actor);
            assertThatThrownBy(() -> service.override(shipmentId, "delivered", "Trying my luck here", "k1"))
                    .isInstanceOf(ForbiddenActionException.class);
        }
        assertThat(status()).isEqualTo(ShipmentStatus.PICKED_UP);
    }

    @Test
    void anOverrideAlwaysNeedsAReason() {
        place(DeliveryMethod.SELLER_SHIPS, null, ShipmentStatus.PICKED_UP);
        tenants.as(TenantFixture.staff("orders:override-shipment", "orders:read"));
        for (String reason : new String[] {null, "", "   ", "ab"}) {
            assertThatThrownBy(() -> service.override(shipmentId, "delivered", reason, "k1"))
                    .isInstanceOf(OrdersRuleViolation.class).extracting("code")
                    .isEqualTo("orders.override-reason-required");
        }
        assertThat(status()).isEqualTo(ShipmentStatus.PICKED_UP);
        verify(orders, never()).appendHistory(any(), any());
    }

    @Test
    void anOverrideNeverGoesBackwardsOrTouchesOtherModes() {
        place(DeliveryMethod.SELLER_SHIPS, null, ShipmentStatus.OUT_FOR_DELIVERY);
        tenants.as(TenantFixture.staff("orders:override-shipment", "orders:read"));
        assertThatThrownBy(() -> service.override(shipmentId, "picked_up", "Wrong status, going back", "k1"))
                .isInstanceOf(TransitionRefused.class);

        place(DeliveryMethod.AFROTRANSACT_DELIVERY, sellerStore(), ShipmentStatus.READY);
        assertThatThrownBy(() -> service.override(shipmentId, "picked_up", "Not mine to override", "k2"))
                .isInstanceOf(TransitionRefused.class);
        place(DeliveryMethod.PICKUP, null, ShipmentStatus.READY);
        assertThatThrownBy(() -> service.override(shipmentId, "delivered", "Not mine to override", "k3"))
                .isInstanceOf(TransitionRefused.class);
        assertThat(status()).isEqualTo(ShipmentStatus.READY);
    }

    @Test
    void theSameOverrideKeyTwiceChangesNothingMore() {
        place(DeliveryMethod.SELLER_SHIPS, null, ShipmentStatus.PICKED_UP);
        tenants.as(TenantFixture.staff("orders:override-shipment", "orders:read"));
        when(orders.seen(any(), eq("override:k1"))).thenReturn(false, true);
        service.override(shipmentId, "in_transit", "Carrier scan missing, parcel is on the way", "k1");
        service.override(shipmentId, "in_transit", "Carrier scan missing, parcel is on the way", "k1");
        verify(orders).appendHistory(any(), any());
    }

    // ------------------------------------------------------------- the general status change, per mode

    @Test
    void peopleCannotUseTheGeneralStatusChangeForAfroTransactDeliverySteps() {
        place(DeliveryMethod.AFROTRANSACT_DELIVERY, sellerStore(), ShipmentStatus.READY);
        tenants.as(TenantFixture.staff("orders:manage", "orders:update-status", "orders:read", "delivery:manage"));
        for (String to : new String[] {"picked_up", "in_transit", "out_for_delivery", "delivered", "delivery_failed"}) {
            assertThatThrownBy(() -> service.transition(shipmentId, to, "because", null, "k-" + to))
                    .isInstanceOf(ForbiddenActionException.class).extracting("code")
                    .isEqualTo("orders.use-delivery-steps");
        }
        assertThat(status()).isEqualTo(ShipmentStatus.READY);
    }

    @Test
    void cancellingBeforePickupStillGoesThroughOrdersManage() {
        place(DeliveryMethod.AFROTRANSACT_DELIVERY, sellerStore(), ShipmentStatus.READY);
        tenants.as(TenantFixture.staff("orders:manage", "orders:update-status", "orders:read"));
        service.transition(shipmentId, "cancelled", "Customer asked to cancel", null, "k1");
        assertThat(status()).isEqualTo(ShipmentStatus.CANCELLED);
    }

    @Test
    void aSellerCannotMoveAnotherSellersShipment() {
        place(DeliveryMethod.SELLER_SHIPS, null, ShipmentStatus.PACKED);
        tenants.as(TenantFixture.sellerOf("org-other", "orders:update-status", "orders:read"));
        assertThatThrownBy(() -> service.transition(shipmentId, "ready", null, null, "k1"))
                .isInstanceOf(NotFoundException.class);
        assertThatThrownBy(() -> service.override(shipmentId, "delivered", "Not my shipment at all", "k2"))
                .isInstanceOf(ForbiddenActionException.class);
        assertThat(status()).isEqualTo(ShipmentStatus.PACKED);
    }

    @Test
    void theSellerMovesTheirOwnSellerShippedShipment() {
        place(DeliveryMethod.SELLER_SHIPS, null, ShipmentStatus.PICKED_UP);
        tenants.as(TenantFixture.sellerOf("org-seller", "orders:update-status", "orders:read"));
        service.transition(shipmentId, "delivered", null, null, "k1");
        assertThat(status()).isEqualTo(ShipmentStatus.DELIVERED);
    }

    @Test
    void aSellerCannotMarkAnAfroTransactDeliveredShipmentPickedUp() {
        place(DeliveryMethod.AFROTRANSACT_DELIVERY, sellerStore(), ShipmentStatus.READY);
        tenants.as(TenantFixture.sellerOf("org-seller", "orders:update-status", "orders:read"));
        assertThatThrownBy(() -> service.transition(shipmentId, "picked_up", null, null, "k1"))
                .isInstanceOf(TransitionRefused.class);
        assertThat(status()).isEqualTo(ShipmentStatus.READY);
    }
}
