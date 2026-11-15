package com.afrotransact.fulfilment.adapter.out.module;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Optional;
import java.util.Set;

import com.afrotransact.fulfilment.port.InventoryClient;
import com.afrotransact.fulfilment.port.OrdersClient;
import com.afrotransact.fulfilment.port.UpstreamFailure;
import com.afrotransact.inventory.api.ReservationApi;
import com.afrotransact.module.CallerPrincipal;
import com.afrotransact.module.CallerPrincipals;
import com.afrotransact.module.ModuleApiException;
import com.afrotransact.orders.api.ShipmentApi;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;

/**
 * The HTTP clients of fulfilment read a 404 for the reservation of an order as "none", pass every other callee failure on with its
 * status, and never carry a callee's crash text to the caller (a 5xx has none). The in-process clients do the same.
 */
class InProcessFulfilmentFailureParityTest {

    private static final CallerPrincipal FULFILMENT = new CallerPrincipal("fulfilment", "fulfilment-service",
            Set.of("orders:update-status", "delivery:relay", "inventory:reserve"));

    private final ShipmentApi shipments = Mockito.mock(ShipmentApi.class);
    private final ReservationApi reservations = Mockito.mock(ReservationApi.class);
    private final InventoryClient inventory;
    private final OrdersClient orders;

    InProcessFulfilmentFailureParityTest() {
        DefaultListableBeanFactory beans = new DefaultListableBeanFactory();
        beans.registerSingleton("shipments", shipments);
        beans.registerSingleton("reservations", reservations);
        beans.registerSingleton("callers", (CallerPrincipals) module -> FULFILMENT);
        ModuleUpstream upstream = new ModuleUpstream(beans.getBeanProvider(CallerPrincipals.class));
        inventory = new InProcessInventoryClient(beans.getBeanProvider(ReservationApi.class), upstream, beans);
        orders = new InProcessOrdersClient(beans.getBeanProvider(ShipmentApi.class), upstream, beans);
    }

    @Test
    void aReservationThatInventoryAnswersNotFoundForIsNone() {
        Mockito.when(reservations.find("o1")).thenThrow(new ModuleApiException(404, "inventory.reservation-not-found", "none"));
        assertThat(inventory.reservation("o1")).isEqualTo(Optional.empty());
    }

    @Test
    void anyOtherInventoryFailureGoesUpWithItsStatus() {
        Mockito.when(reservations.find("o1")).thenThrow(new ModuleApiException(409, "inventory.invalid-state", "wrong"));
        assertThatThrownBy(() -> inventory.reservation("o1")).isInstanceOfSatisfying(UpstreamFailure.class, f -> {
            assertThat(f.status()).isEqualTo(409);
            assertThat(f.retryable()).isFalse();
        });
    }

    @Test
    void aCrashInsideACalleeIsRetryableAndItsTextStaysInTheLog() {
        Mockito.doThrow(new IllegalStateException("could not execute statement; password=hunter2")).when(shipments)
                .attachTracking("s1", "UPS", "1Z");
        assertThatThrownBy(() -> orders.attachTracking("s1", "UPS", "1Z")).isInstanceOfSatisfying(UpstreamFailure.class,
                f -> {
                    assertThat(f.code()).isEqualTo("orders.unavailable");
                    assertThat(f.retryable()).isTrue();
                    assertThat(f.getMessage()).doesNotContain("hunter2").doesNotContain("statement");
                });
        Mockito.when(reservations.find("o1")).thenThrow(new IllegalStateException("db password=hunter2"));
        assertThatThrownBy(() -> inventory.reservation("o1")).isInstanceOfSatisfying(UpstreamFailure.class,
                f -> assertThat(f.getMessage()).doesNotContain("hunter2"));
    }

}
