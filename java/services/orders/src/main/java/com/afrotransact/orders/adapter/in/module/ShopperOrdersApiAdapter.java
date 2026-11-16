package com.afrotransact.orders.adapter.in.module;

import java.time.Instant;
import java.util.List;

import com.afrotransact.module.EndUserActor;
import com.afrotransact.orders.api.ShopperOrdersApi;
import com.afrotransact.orders.application.OrderQueryService;
import com.afrotransact.orders.application.OrderView;
import com.afrotransact.platform.security.ActorResolver;
import com.afrotransact.platform.security.ModuleEntry;
import org.springframework.stereotype.Component;

/**
 * The in-process entry of {@link ShopperOrdersApi}: the use cases behind {@code GET /v1/orders?customer=me} and
 * {@code GET /v1/orders/{number}}, entered for the signed-in person. {@link OrderQueryService} reads the owner from the actor in force,
 * which inside {@code runAsUser} is the person's token subject (never the calling module), so a shopper sees their own orders and a
 * foreign order is the same 404 / 403 as over HTTP. Handover codes are not part of the published view.
 */
@Component
class ShopperOrdersApiAdapter implements ShopperOrdersApi {

    private final OrderQueryService queries;
    private final ModuleEntry entry;

    ShopperOrdersApiAdapter(OrderQueryService queries, ActorResolver actors) {
        this.queries = queries;
        this.entry = new ModuleEntry(actors);
    }

    @Override
    public List<OrderSummary> mine(EndUserActor user, int limit) {
        return entry.runAsUser(user, CALLER_SCOPE, () -> queries.mine(null, limit).items().stream()
                .map(ShopperOrdersApiAdapter::summary).toList(), OrdersFailures::map);
    }

    @Override
    public TrackedOrder track(EndUserActor user, String numberOrId) {
        return entry.runAsUser(user, CALLER_SCOPE, () -> tracked(queries.get(numberOrId)), OrdersFailures::map);
    }

    private static OrderSummary summary(OrderView o) {
        Instant placed = o.placedAt() == null ? o.createdAt() : o.placedAt();
        return new OrderSummary(o.orderNumber(), o.status(), placed, o.totalMinor(), o.currency(), o.shipments().size());
    }

    private static TrackedOrder tracked(OrderView o) {
        return new TrackedOrder(o.orderNumber(), o.status(), o.shipments().stream().map(s -> new TrackedShipment(s.id(),
                s.status(), s.carrier(), s.trackingNumber(), s.promisedBy())).toList());
    }
}
