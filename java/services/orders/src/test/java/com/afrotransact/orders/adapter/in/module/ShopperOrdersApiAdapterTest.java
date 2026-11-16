package com.afrotransact.orders.adapter.in.module;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

import com.afrotransact.module.EndUserActor;
import com.afrotransact.module.InProcessCalls;
import com.afrotransact.module.ModuleApiException;
import com.afrotransact.orders.api.ShopperOrdersApi;
import com.afrotransact.orders.application.OrderPage;
import com.afrotransact.orders.application.OrderQueryService;
import com.afrotransact.orders.application.OrderView;
import com.afrotransact.orders.domain.OrdersRuleViolation;
import com.afrotransact.platform.test.InProcessKit;
import com.afrotransact.platform.web.error.ForbiddenActionException;
import org.junit.jupiter.api.Test;

/**
 * The published shopper-orders API runs orders' own query service as the signed-in person (never as the calling module), only for a module
 * that holds the delegation scope, and answers what {@code GET /v1/orders?customer=me} and {@code GET /v1/orders/{number}} answer.
 */
class ShopperOrdersApiAdapterTest {

    private final List<String> actorsInForce = new ArrayList<>();
    private final OrderQueryService queries = mock(OrderQueryService.class);
    private final ShopperOrdersApi api = new ShopperOrdersApiAdapter(queries, () -> {
        var actor = InProcessKit.ACTORS.current();
        actorsInForce.add(actor.subject() + "/" + actor.type());
        return actor;
    });
    private final EndUserActor shopper = InProcessKit.user("customer-1", "pt", "CUSTOMER");

    private <T> T asAiGateway(Supplier<T> call) {
        return InProcessCalls.as(InProcessKit.caller("ai-gateway", ShopperOrdersApi.CALLER_SCOPE), "orders", call);
    }

    private static OrderView order(String number, Instant placedAt, Instant createdAt, List<OrderView.ShipmentView> shipments) {
        OrderView o = mock(OrderView.class);
        when(o.orderNumber()).thenReturn(number);
        when(o.status()).thenReturn("SHIPPED");
        when(o.placedAt()).thenReturn(placedAt);
        when(o.createdAt()).thenReturn(createdAt);
        when(o.totalMinor()).thenReturn(4599L);
        when(o.currency()).thenReturn("USD");
        when(o.shipments()).thenReturn(shipments);
        return o;
    }

    @Test
    void listsTheOrdersOfThePersonNotOfTheCallingModule() {
        Instant created = Instant.parse("2026-10-12T10:00:00Z");
        OrderView view = order("AT-1", null, created, List.of(mock(OrderView.ShipmentView.class), mock(OrderView.ShipmentView.class)));
        when(queries.mine(any(), eq(5))).thenReturn(new OrderPage<>(List.of(view), null, 1));
        List<ShopperOrdersApi.OrderSummary> out = asAiGateway(() -> api.mine(shopper, 5));
        assertThat(out).containsExactly(new ShopperOrdersApi.OrderSummary("AT-1", "SHIPPED", created, 4599, "USD", 2));
        assertThat(actorsInForce).containsExactly("customer-1/CUSTOMER");
    }

    @Test
    void tracksOneOrderWithItsShipmentsAndNeverCarriesAHandoverCode() {
        OrderView.ShipmentView s = mock(OrderView.ShipmentView.class);
        when(s.id()).thenReturn("shp-1");
        when(s.status()).thenReturn("IN_TRANSIT");
        when(s.carrier()).thenReturn("UPS");
        when(s.trackingNumber()).thenReturn("1Z999");
        when(s.promisedBy()).thenReturn(LocalDate.parse("2026-10-20"));
        OrderView view = order("AT-1", Instant.now(), Instant.now(), List.of(s));
        when(queries.get("AT-1")).thenReturn(view);
        ShopperOrdersApi.TrackedOrder out = asAiGateway(() -> api.track(shopper, "AT-1"));
        assertThat(out).isEqualTo(new ShopperOrdersApi.TrackedOrder("AT-1", "SHIPPED", List.of(
                new ShopperOrdersApi.TrackedShipment("shp-1", "IN_TRANSIT", "UPS", "1Z999", LocalDate.parse("2026-10-20")))));
        assertThat(ShopperOrdersApi.TrackedShipment.class.getRecordComponents()).extracting(c -> c.getName())
                .noneMatch(n -> n.toLowerCase().contains("code"));
    }

    @Test
    void anotherCustomersOrderIsTheRefusalTheQueryServiceGivesAndNothingMore() {
        when(queries.get("AT-OTHER")).thenThrow(new ForbiddenActionException("orders.not-yours", "Not your order"));
        assertThatThrownBy(() -> asAiGateway(() -> api.track(shopper, "AT-OTHER"))).isInstanceOfSatisfying(ModuleApiException.class,
                e -> assertThat(e.status()).isEqualTo(403));
    }

    @Test
    void ordersOwnRuleViolationsKeepTheirStatusAndCode() {
        when(queries.get("x")).thenThrow(new OrdersRuleViolation("orders.bad", "bad"));
        assertThatThrownBy(() -> asAiGateway(() -> api.track(shopper, "x"))).isInstanceOfSatisfying(ModuleApiException.class, e -> {
            assertThat(e.status()).isEqualTo(422);
            assertThat(e.code()).isEqualTo("orders.bad");
        });
    }

    @Test
    void aModuleWithoutTheDelegationScopeIsRefusedBeforeAnyQuery() {
        assertThatThrownBy(() -> InProcessCalls.as(InProcessKit.caller("ai-gateway", "orders:lookup"), "orders",
                () -> api.mine(shopper, 5))).isInstanceOfSatisfying(ModuleApiException.class, e -> {
                    assertThat(e.status()).isEqualTo(403);
                    assertThat(e.code()).isEqualTo("auth.missing-permission");
                });
        verifyNoInteractions(queries);
    }

    @Test
    void enteredWithoutAnInProcessCallerItFailsClosed() {
        assertThatThrownBy(() -> api.mine(shopper, 5)).isInstanceOf(IllegalStateException.class);
    }
}
