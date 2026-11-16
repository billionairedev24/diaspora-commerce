package com.afrotransact.trustops.adapter.out.module;

import java.util.List;
import java.util.Optional;

import com.afrotransact.module.InProcessCalls;
import com.afrotransact.module.ModuleMode;
import com.afrotransact.orders.api.OrderLookupApi;
import com.afrotransact.platform.web.module.ConditionalOnModuleMode;
import com.afrotransact.trustops.port.OrdersClient;
import com.afrotransact.trustops.port.UpstreamException;
import org.springframework.beans.factory.ListableBeanFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

/**
 * {@link OrdersClient} through orders' published {@link OrderLookupApi#lookup} inside this JVM (mode {@code in-process}), entered as
 * trust-ops' caller principal (scope {@code orders:lookup}). An unknown order is empty, as the HTTP client reads a 404; anything else
 * is an {@link UpstreamException}.
 */
@Component
@ConditionalOnModuleMode(caller = "trust-ops", callee = "orders", value = ModuleMode.IN_PROCESS)
class InProcessOrdersClient implements OrdersClient {

    private static final String SERVICE = "orders";

    private final ObjectProvider<OrderLookupApi> api;
    private final ModuleUpstream upstream;

    InProcessOrdersClient(ObjectProvider<OrderLookupApi> api, ModuleUpstream upstream, ListableBeanFactory beans) {
        InProcessCalls.requireRunning(beans.getBeanNamesForType(OrderLookupApi.class, true, false).length > 0, "trust-ops",
                "orders", "at.trustops.orders.mode");
        this.api = api;
        this.upstream = upstream;
    }

    @Override
    public Optional<OrderFacts> order(String orderIdOrNumber) {
        try {
            OrderLookupApi.OrderView o = upstream.call(SERVICE, () -> api.getObject().lookup(orderIdOrNumber));
            return Optional.of(new OrderFacts(o.orderId(), o.orderNumber(), o.customerId(), o.paymentId(), o.currency(),
                    o.shipments().stream().map(s -> new ShipmentFacts(s.shipmentId(), s.sellerOrgId(), s.status(),
                            List.copyOf(s.lines().stream().map(OrderLookupApi.Line::lineId).toList()))).toList()));
        } catch (UpstreamException e) {
            if (e.status() == 404) {
                return Optional.empty();
            }
            throw e;
        }
    }
}
