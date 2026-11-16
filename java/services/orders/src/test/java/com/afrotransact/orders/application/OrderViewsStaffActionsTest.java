package com.afrotransact.orders.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import com.afrotransact.orders.domain.DefaultShipmentTransitionPolicy;
import com.afrotransact.orders.domain.DeliveryMethod;
import com.afrotransact.orders.domain.DeliveryPreferences;
import com.afrotransact.orders.domain.FulfilmentType;
import com.afrotransact.orders.domain.LocalDelivery;
import com.afrotransact.orders.domain.Order;
import com.afrotransact.orders.domain.OrderLine;
import com.afrotransact.orders.domain.OrderStatus;
import com.afrotransact.orders.domain.OrderTotals;
import com.afrotransact.orders.domain.ReturnWindowPolicy;
import com.afrotransact.orders.domain.ShipTo;
import com.afrotransact.orders.domain.Shipment;
import com.afrotransact.orders.domain.ShipmentStatus;
import com.afrotransact.orders.port.HandoverCodeVault;
import com.afrotransact.orders.port.StaffDirectory;
import com.afrotransact.platform.test.tenant.TenantFixture;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * What the order sheet is offered per fulfilment mode and permission: the same list the Delivery queue offers for
 * AfroTransact-delivered shipments, nothing for seller-shipped ones but cancelling, and the override list only for
 * holders of orders:override-shipment.
 */
class OrderViewsStaffActionsTest {

    private final TenantFixture tenants = new TenantFixture();
    private OrderViews views;

    @BeforeEach
    void setUp() {
        Clock clock = Clock.fixed(Instant.parse("2026-11-14T15:00:00Z"), ZoneOffset.UTC);
        OrdersSettings settings = mock(OrdersSettings.class);
        when(settings.cancellationWindow()).thenReturn(Duration.ofHours(1));
        OrdersAccess access = new OrdersAccess(tenants.permissions(), tenants.access(), tenants.resolver());
        views = new OrderViews(new DefaultShipmentTransitionPolicy(), mock(HandoverCodeVault.class),
                mock(ReturnWindowPolicy.class), access, settings, mock(StaffDirectory.class), clock);
    }

    private OrderView.ShipmentView view(DeliveryMethod method, LocalDelivery local, ShipmentStatus status) {
        String orderId = UUID.randomUUID().toString();
        OrderLine line = new OrderLine(UUID.randomUUID().toString(), 1, "ATIN-1", "P-1", "Jollof spice mix", null, 2,
                1500, 124, 0, "groceries", false, "US-TX");
        Shipment shipment = new Shipment(UUID.randomUUID().toString(), orderId, 1, "org-seller", "Mama Put Pantry",
                FulfilmentType.SELLER_FULFILLED, null, method, "local",
                method == DeliveryMethod.PICKUP ? "PICKUP-1" : null, DeliveryPreferences.defaults(), false, false,
                null, null, status, 599, null, null, LocalDate.now(), LocalDate.now().plusDays(3), null, null, null,
                null, Instant.now(), List.of(line), 0, local, null);
        Order order = new Order(orderId, "AT-2026-F7MGMD", "cust-1", "USD", OrderStatus.PLACED,
                OrderTotals.ofShipments(List.of(shipment)), new ShipTo("addr-1", "Ada Obi", "+17135550142",
                "1 Main St", null, "Houston", "TX", "77002", "US", null), UUID.randomUUID().toString(),
                "checkout-" + orderId, null, Instant.now(), Instant.now(), List.of(shipment), 0);
        return views.of(order, new Viewer(Viewer.Kind.STAFF, null), List.of()).shipments().getFirst();
    }

    private static LocalDelivery sellerStore() {
        return new LocalDelivery("loc-1", 29.7, -95.3, 4.0, 5.0, "ROAD_OSRM", 599, 250, 1, 12.0);
    }

    @Test
    void fulfilledByAfroTransactOffersTheDeliveryStepsToHoldersOfDeliveryManage() {
        tenants.as(TenantFixture.staff("orders:manage", "orders:read", "delivery:manage"));
        assertThat(view(DeliveryMethod.AFROTRANSACT_DELIVERY, sellerStore(), ShipmentStatus.READY).nextActions())
                .containsExactly("picked_up", "cancelled");
        assertThat(view(DeliveryMethod.AFROTRANSACT_DELIVERY, sellerStore(), ShipmentStatus.PICKED_UP).nextActions())
                .containsExactly("in_transit", "out_for_delivery", "delivery_failed");
        assertThat(view(DeliveryMethod.AFROTRANSACT_DELIVERY, sellerStore(), ShipmentStatus.OUT_FOR_DELIVERY)
                .nextActions()).containsExactly("delivery_failed", "delivered");
        assertThat(view(DeliveryMethod.AFROTRANSACT_DELIVERY, sellerStore(), ShipmentStatus.DELIVERY_FAILED)
                .nextActions()).containsExactly("out_for_delivery", "returned");
    }

    @Test
    void withoutDeliveryManageTheDeliveryStepsAreNotOffered() {
        tenants.as(TenantFixture.staff("orders:manage", "orders:read", "delivery:read"));
        assertThat(view(DeliveryMethod.AFROTRANSACT_DELIVERY, sellerStore(), ShipmentStatus.READY).nextActions())
                .containsExactly("cancelled");
        assertThat(view(DeliveryMethod.AFROTRANSACT_DELIVERY, sellerStore(), ShipmentStatus.OUT_FOR_DELIVERY)
                .nextActions()).isEmpty();
        tenants.as(TenantFixture.staff("orders:read", "delivery:manage"));
        assertThat(view(DeliveryMethod.AFROTRANSACT_DELIVERY, sellerStore(), ShipmentStatus.READY).nextActions())
                .containsExactly("picked_up");
    }

    @Test
    void sellerShipsOffersNoStatusesButCancellingAndTheOverrideOnlyToItsHolders() {
        tenants.as(TenantFixture.staff("orders:manage", "orders:read", "delivery:manage"));
        OrderView.ShipmentView v = view(DeliveryMethod.SELLER_SHIPS, null, ShipmentStatus.PICKED_UP);
        assertThat(v.nextActions()).isEmpty();
        assertThat(v.overrideActions()).isEmpty();
        assertThat(view(DeliveryMethod.SELLER_SHIPS, null, ShipmentStatus.READY).nextActions())
                .containsExactly("cancelled");
        tenants.as(TenantFixture.staff("orders:manage", "orders:read", "orders:override-shipment"));
        assertThat(view(DeliveryMethod.SELLER_SHIPS, null, ShipmentStatus.PICKED_UP).overrideActions())
                .containsExactly("in_transit", "out_for_delivery", "delivery_failed", "delivered", "returned");
        assertThat(view(DeliveryMethod.AFROTRANSACT_DELIVERY, sellerStore(), ShipmentStatus.PICKED_UP)
                .overrideActions()).isEmpty();
    }

    @Test
    void pickupOffersReadyCollectedAndNotCollected() {
        tenants.as(TenantFixture.staff("orders:manage", "orders:read"));
        assertThat(view(DeliveryMethod.PICKUP, null, ShipmentStatus.PACKED).nextActions()).contains("ready");
        assertThat(view(DeliveryMethod.PICKUP, null, ShipmentStatus.READY).nextActions())
                .contains("delivered", "returned").doesNotContain("picked_up", "out_for_delivery");
    }
}
