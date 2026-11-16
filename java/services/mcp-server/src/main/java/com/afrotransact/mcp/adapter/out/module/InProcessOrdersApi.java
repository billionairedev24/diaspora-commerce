package com.afrotransact.mcp.adapter.out.module;

import java.util.Map;

import com.afrotransact.mcp.port.CallerCredentials;
import com.afrotransact.mcp.port.Downstream;
import com.afrotransact.mcp.port.OrdersApi;
import com.afrotransact.module.InProcessCalls;
import com.afrotransact.module.ModuleMode;
import com.afrotransact.orders.api.OrdersUserApi;
import com.afrotransact.platform.web.module.ConditionalOnModuleMode;
import org.springframework.beans.factory.ListableBeanFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

/**
 * {@link OrdersApi} through orders' published {@link OrdersUserApi} inside this JVM (mode {@code in-process}), run as the signed-in
 * person captured at request entry: a shipment step or a tracking label is written as that person, so the state machine's "who may take
 * this step" decides exactly as for their token. The bearer token the port still carries is not read.
 */
@Component
@ConditionalOnModuleMode(caller = "mcp-server", callee = "orders", value = ModuleMode.IN_PROCESS)
class InProcessOrdersApi implements OrdersApi {

    private static final String SERVICE = "orders";

    private final ObjectProvider<OrdersUserApi> api;
    private final ModuleUpstream upstream;

    InProcessOrdersApi(ObjectProvider<OrdersUserApi> api, ModuleUpstream upstream, ListableBeanFactory beans) {
        InProcessCalls.requireRunning(beans.getBeanNamesForType(OrdersUserApi.class, true, false).length > 0, "mcp-server",
                "orders", "at.mcpserver.orders.mode");
        this.api = api;
        this.upstream = upstream;
    }

    @Override
    public Downstream order(String orderId, CallerCredentials credentials) {
        return upstream.call(SERVICE, credentials, user -> api.getObject().order(user, orderId));
    }

    @Override
    public Downstream myOrders(String cursor, int limit, CallerCredentials credentials) {
        return upstream.call(SERVICE, credentials, user -> api.getObject().myOrders(user, cursor, limit));
    }

    @Override
    public Downstream sellerShipments(String status, String cursor, int limit, CallerCredentials credentials) {
        return upstream.call(SERVICE, credentials, user -> api.getObject().sellerShipments(user, status, cursor, limit));
    }

    @Override
    public Downstream transitionShipment(String shipmentId, Map<String, Object> body, CallerCredentials credentials) {
        return upstream.call(SERVICE, credentials, user -> api.getObject().transitionShipment(user, shipmentId, body));
    }

    @Override
    public Downstream attachTracking(String shipmentId, String carrier, String trackingNumber, CallerCredentials credentials) {
        return upstream.call(SERVICE, credentials, user -> api.getObject().attachTracking(user, shipmentId, carrier, trackingNumber));
    }

    @Override
    public Downstream paymentSplit(String orderId, CallerCredentials credentials) {
        return upstream.call(SERVICE, credentials, user -> api.getObject().paymentSplit(user, orderId));
    }
}
