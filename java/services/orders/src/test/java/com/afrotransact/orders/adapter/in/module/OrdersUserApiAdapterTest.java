package com.afrotransact.orders.adapter.in.module;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

import com.afrotransact.orders.api.OrdersUserApi;
import com.afrotransact.orders.adapter.in.web.OrdersEndpointViews;
import com.afrotransact.orders.domain.OrdersRuleViolation;

import com.afrotransact.module.EndUserActor;
import com.afrotransact.module.InProcessCalls;
import com.afrotransact.module.ModuleApiException;
import com.afrotransact.platform.security.Actor;
import com.afrotransact.platform.security.PrincipalType;
import com.afrotransact.platform.test.InProcessKit;
import com.afrotransact.platform.web.error.NotFoundException;
import org.junit.jupiter.api.Test;

/**
 * OrdersUserApi runs the endpoint behind the MCP tools AS THE SIGNED-IN PERSON: the module that calls needs the delegation scope, the actor in
 * force in the callee is the person (never the calling module, never a service), and failures leave with the HTTP status.
 */
class OrdersUserApiAdapterTest {

    private final OrdersEndpointViews views = mock(OrdersEndpointViews.class);
    private final OrdersUserApi api = new OrdersUserApiAdapter(views, InProcessKit.ACTORS);
    private final EndUserActor person = InProcessKit.user("u-1", "pt", "CUSTOMER", "perms", List.of("mcp:shopper"));

    private <T> T as(String scope, Supplier<T> call) {
        return InProcessCalls.as(InProcessKit.caller("mcp-server", scope), "orders", call::get);
    }

    @Test
    void returnsWhatTheEndpointReturnsToAModuleThatHoldsTheDelegationScope() {
        when(views.myOrders(null, 20)).thenReturn(Map.of("ok", true));
        assertThat(as(OrdersUserApi.READ_SCOPE, () -> api.myOrders(person, null, 20))).isEqualTo(Map.of("ok", true));
    }

    @Test
    void theEndpointRunsAsThePersonNeverAsTheCallingModule() {
        AtomicReference<Actor> seen = new AtomicReference<>();
        when(views.myOrders(null, 20)).thenAnswer(call -> {
            seen.set(InProcessKit.ACTORS.current());
            return Map.of();
        });
        as(OrdersUserApi.READ_SCOPE, () -> api.myOrders(person, null, 20));
        assertThat(seen.get().subject()).isEqualTo("u-1");
        assertThat(seen.get().type()).isEqualTo(PrincipalType.CUSTOMER);
        assertThat(seen.get().permissions()).containsExactly("mcp:shopper");
    }

    @Test
    void aModuleWithoutTheDelegationScopeIsRefusedAndNothingRuns() {
        assertThatThrownBy(() -> as("users:read", () -> api.myOrders(person, null, 20))).isInstanceOfSatisfying(ModuleApiException.class, e -> {
            assertThat(e.status()).isEqualTo(403);
            assertThat(e.code()).isEqualTo("auth.missing-permission");
        });
        verifyNoInteractions(views);
    }

    @Test
    void failuresLeaveWithTheStatusOfTheHttpProblem() {
        when(views.myOrders(null, 20)).thenThrow(new NotFoundException("x.not-found", "no such thing"));
        assertThatThrownBy(() -> as(OrdersUserApi.READ_SCOPE, () -> api.myOrders(person, null, 20))).isInstanceOfSatisfying(ModuleApiException.class,
                e -> {
                    assertThat(e.status()).isEqualTo(404);
                    assertThat(e.code()).isEqualTo("x.not-found");
                });
        reset(views);
        when(views.myOrders(null, 20)).thenThrow(new IllegalArgumentException("bad value"));
        assertThatThrownBy(() -> as(OrdersUserApi.READ_SCOPE, () -> api.myOrders(person, null, 20))).isInstanceOfSatisfying(ModuleApiException.class,
                e -> assertThat(e.status()).isEqualTo(400));
    }

    @Test
    void enteredWithoutAnInProcessCallerItFailsClosed() {
        assertThatThrownBy(() -> api.myOrders(person, null, 20)).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void theReadScopeDoesNotAllowAShipmentWrite() {
        assertThatThrownBy(() -> as(OrdersUserApi.READ_SCOPE, () -> api.transitionShipment(person, "shp-1", Map.of("to", "packed"))))
                .isInstanceOfSatisfying(ModuleApiException.class, e -> assertThat(e.status()).isEqualTo(403));
        assertThatThrownBy(() -> as(OrdersUserApi.READ_SCOPE, () -> api.attachTracking(person, "shp-1", "usps", "940")))
                .isInstanceOfSatisfying(ModuleApiException.class, e -> assertThat(e.status()).isEqualTo(403));
        verifyNoInteractions(views);
    }

    @Test
    void theShipmentWritesRunAsThePersonWithTheWriteScope() {
        when(views.transitionShipment("shp-1", Map.of("to", "packed"))).thenReturn(Map.of("status", "PACKED"));
        assertThat(as(OrdersUserApi.WRITE_SCOPE, () -> api.transitionShipment(person, "shp-1", Map.of("to", "packed"))))
                .isEqualTo(Map.of("status", "PACKED"));
        when(views.attachTracking("shp-1", "usps", "940")).thenReturn(Map.of("id", "o1"));
        assertThat(as(OrdersUserApi.WRITE_SCOPE, () -> api.attachTracking(person, "shp-1", "usps", "940")))
                .isEqualTo(Map.of("id", "o1"));
    }

    @Test
    void aRefusedStepIsTheOrdersStatusNotAnOutage() {
        when(views.transitionShipment("shp-1", Map.of("to", "delivered")))
                .thenThrow(new OrdersRuleViolation("orders.invalid-value", "no"));
        assertThatThrownBy(() -> as(OrdersUserApi.WRITE_SCOPE, () -> api.transitionShipment(person, "shp-1", Map.of("to", "delivered"))))
                .isInstanceOfSatisfying(ModuleApiException.class, e -> {
                    assertThat(e.status()).isEqualTo(422);
                    assertThat(e.code()).isEqualTo("orders.invalid-value");
                });
    }
}
