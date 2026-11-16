package com.afrotransact.ai.adapter.out.module;

import com.afrotransact.ai.port.OrderTracker;
import com.afrotransact.ai.port.Shopper;
import com.afrotransact.module.InProcessCalls;
import com.afrotransact.module.ModuleMode;
import com.afrotransact.orders.api.ShopperOrdersApi;
import com.afrotransact.platform.web.module.ConditionalOnModuleMode;
import org.springframework.beans.factory.ListableBeanFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

/**
 * {@link OrderTracker} through orders' published {@link ShopperOrdersApi} inside this JVM (mode {@code in-process}), for the signed-in
 * shopper of the request (captured on the request thread, never taken from the worker thread or a tool argument): orders runs its own
 * query service as that person, so another customer's order is the 404 / 403 it is over HTTP, and it reads as "not found" here as the
 * HTTP adapter reads it. Anything else, no signed-in person included, is "unavailable".
 */
@Component
@ConditionalOnModuleMode(caller = "ai-gateway", callee = "orders", value = ModuleMode.IN_PROCESS)
class InProcessOrderTracker implements OrderTracker {

    private static final String SERVICE = "orders";

    private final ObjectProvider<ShopperOrdersApi> api;
    private final ModuleUpstream upstream;

    InProcessOrderTracker(ObjectProvider<ShopperOrdersApi> api, ModuleUpstream upstream, ListableBeanFactory beans) {
        InProcessCalls.requireRunning(beans.getBeanNamesForType(ShopperOrdersApi.class, true, false).length > 0, "ai-gateway",
                SERVICE, "at.aigateway.orders.mode");
        this.api = api;
        this.upstream = upstream;
    }

    @Override
    public Lookup track(String orderId, Shopper shopper) {
        return upstream.forShopper(SERVICE, shopper, user -> found(orderId, api.getObject().track(user, orderId)),
                e -> Lookup.of(e.status() == 404 || e.status() == 403 ? Availability.NOT_FOUND : Availability.UNAVAILABLE, orderId),
                () -> Lookup.of(Availability.UNAVAILABLE, orderId));
    }

    private static Lookup found(String orderId, ShopperOrdersApi.TrackedOrder o) {
        String number = o.orderNumber() == null ? "" : o.orderNumber();
        return new Lookup(Availability.FOUND, number.isBlank() ? orderId : number, text(o.status()), o.shipments().stream()
                .map(s -> new Shipment(text(s.shipmentId()), text(s.status()), text(s.carrier()), text(s.trackingNumber()),
                        s.promisedBy() == null ? "" : s.promisedBy().toString())).toList());
    }

    private static String text(String value) {
        return value == null ? "" : value;
    }
}
