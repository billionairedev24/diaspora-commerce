package com.afrotransact.fulfilment.adapter.out.module;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import com.afrotransact.fulfilment.port.CourierEvent;
import com.afrotransact.fulfilment.port.InventoryClient;
import com.afrotransact.fulfilment.port.OrdersClient;
import com.afrotransact.fulfilment.port.UpstreamFailure;
import com.afrotransact.inventory.api.ReservationApi;
import com.afrotransact.module.CallerPrincipal;
import com.afrotransact.module.CallerPrincipals;
import com.afrotransact.module.InProcessCalls;
import com.afrotransact.module.ModuleApiException;
import com.afrotransact.orders.api.ShipmentApi;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;

/** Fulfilment reaches orders and inventory through their published APIs as its own principal. */
class InProcessFulfilmentEdgesTest {

    private static final CallerPrincipal FULFILMENT = new CallerPrincipal("fulfilment", "fulfilment-service",
            Set.of("orders:update-status", "delivery:relay", "inventory:reserve"));

    private final ShipmentApi shipments = Mockito.mock(ShipmentApi.class);
    private final ReservationApi reservations = Mockito.mock(ReservationApi.class);
    private final DefaultListableBeanFactory beans = new DefaultListableBeanFactory();
    private final ModuleUpstream upstream;

    InProcessFulfilmentEdgesTest() {
        beans.registerSingleton("shipments", shipments);
        beans.registerSingleton("reservations", reservations);
        beans.registerSingleton("callers", (CallerPrincipals) module -> FULFILMENT);
        upstream = new ModuleUpstream(beans.getBeanProvider(CallerPrincipals.class));
    }

    @Test
    void courierEventsAreRelayedAsFulfilmentWithAnAlwaysPresentProofAndACutNote() {
        Mockito.doAnswer(call -> {
            assertThat(InProcessCalls.require().clientId()).isEqualTo("fulfilment-service");
            ShipmentApi.CourierEvent e = call.getArgument(1);
            assertThat(e.note()).hasSize(500);
            assertThat(e.proof()).isNotNull();
            assertThat(e.driverId()).isEqualTo("driver-1");
            return null;
        }).when(shipments).courierEvent(Mockito.eq("s1"), Mockito.any());
        OrdersClient client = new InProcessOrdersClient(beans.getBeanProvider(ShipmentApi.class), upstream, beans);
        client.courierEvent("s1", CourierEvent.step("e1", "picked_up", Instant.now(), "driver-1", null, null,
                "x".repeat(900)), "bearer");
        Mockito.verify(shipments).courierEvent(Mockito.eq("s1"), Mockito.any());
    }

    @Test
    void aRefusedTransitionKeepsItsStatus() {
        Mockito.doThrow(new ModuleApiException(409, "orders.invalid-transition", "no")).when(shipments)
                .transition("s1", "delivered", null, "k");
        OrdersClient client = new InProcessOrdersClient(beans.getBeanProvider(ShipmentApi.class), upstream, beans);
        assertThatThrownBy(() -> client.transition("s1", "delivered", null, "k")).isInstanceOfSatisfying(
                UpstreamFailure.class, f -> {
                    assertThat(f.status()).isEqualTo(409);
                    assertThat(f.retryable()).isFalse();
                });
    }

    @Test
    void theReservationIsTheNodeAndLotsOrEmptyWhenThereIsNone() {
        Mockito.when(reservations.find("o1")).thenReturn(Optional.of(new ReservationApi.ReservationInfo("o1", "HELD", false,
                List.of(new ReservationApi.ReservedLine("SKU1", "FC1", 2, "g1", List.of("lot-1"))))));
        Mockito.when(reservations.find("o2")).thenReturn(Optional.empty());
        InventoryClient client = new InProcessInventoryClient(beans.getBeanProvider(ReservationApi.class), upstream, beans);
        assertThat(client.reservation("o1")).get().isEqualTo(List.of(new InventoryClient.ReservedLine("SKU1", "FC1", 2,
                List.of("lot-1"))));
        assertThat(client.reservation("o2")).isEmpty();
    }
}
