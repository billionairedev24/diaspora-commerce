package com.afrotransact.orders.adapter.in.module;

import java.util.Map;
import java.util.function.Supplier;

import com.afrotransact.module.EndUserActor;
import com.afrotransact.orders.adapter.in.web.OrdersEndpointViews;
import com.afrotransact.orders.api.OrdersUserApi;
import com.afrotransact.platform.security.ActorResolver;
import com.afrotransact.platform.security.ModuleEntry;
import org.springframework.stereotype.Component;

/**
 * The in-process entry of {@link OrdersUserApi}: the order and shipment endpoints behind the MCP tools, entered AS the signed-in person
 * ({@code ModuleEntry.runAsUser}: their subject, type, organisation and permissions, never the calling module's). Orders' use cases
 * apply their own checks exactly as for the HTTP request (a customer's own orders, a seller organisation's own shipments, who may take
 * which shipment step); a shipment write is never made as a service.
 */
@Component
class OrdersUserApiAdapter implements OrdersUserApi {

    private final OrdersEndpointViews views;
    private final ModuleEntry entry;

    OrdersUserApiAdapter(OrdersEndpointViews views, ActorResolver actors) {
        this.views = views;
        this.entry = new ModuleEntry(actors);
    }

    @Override
    public Object order(EndUserActor user, String orderId) {
        return read(user, () -> views.order(orderId));
    }

    @Override
    public Object myOrders(EndUserActor user, String cursor, int limit) {
        return read(user, () -> views.myOrders(cursor, limit));
    }

    @Override
    public Object sellerShipments(EndUserActor user, String status, String cursor, int limit) {
        return read(user, () -> views.sellerShipments(status, cursor, limit));
    }

    @Override
    public Object paymentSplit(EndUserActor user, String orderNumber) {
        return read(user, () -> views.paymentSplit(orderNumber));
    }

    @Override
    public Object transitionShipment(EndUserActor user, String shipmentId, Map<String, Object> body) {
        return entry.runAsUser(user, WRITE_SCOPE, () -> views.transitionShipment(shipmentId, body), ShipmentFailures::map);
    }

    @Override
    public Object attachTracking(EndUserActor user, String shipmentId, String carrier, String trackingNumber) {
        return entry.runAsUser(user, WRITE_SCOPE, () -> views.attachTracking(shipmentId, carrier, trackingNumber),
                ShipmentFailures::map);
    }

    private Object read(EndUserActor user, Supplier<Object> body) {
        return entry.runAsUser(user, READ_SCOPE, body, ShipmentFailures::map);
    }
}
