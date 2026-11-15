package com.afrotransact.fulfilment.adapter.out.module;

import java.util.List;
import java.util.Optional;

import com.afrotransact.fulfilment.port.InventoryClient;
import com.afrotransact.inventory.api.ReservationApi;
import com.afrotransact.module.InProcessCalls;
import com.afrotransact.module.ModuleMode;
import com.afrotransact.platform.web.module.ConditionalOnModuleMode;
import org.springframework.beans.factory.ListableBeanFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

/**
 * {@link InventoryClient} through inventory's published {@link ReservationApi} inside this JVM (mode {@code in-process}): the
 * node and lots reserved for an order, as picking needs them. No reservation is {@link Optional#empty()}, as a 404 is over HTTP.
 */
@Component
@ConditionalOnModuleMode(caller = "fulfilment", callee = "inventory", value = ModuleMode.IN_PROCESS)
class InProcessInventoryClient implements InventoryClient {

    private static final String SERVICE = "inventory";

    private final ObjectProvider<ReservationApi> api;
    private final ModuleUpstream upstream;

    InProcessInventoryClient(ObjectProvider<ReservationApi> api, ModuleUpstream upstream, ListableBeanFactory beans) {
        InProcessCalls.requireRunning(beans.getBeanNamesForType(ReservationApi.class, true, false).length > 0,
                "fulfilment", "inventory", "at.fulfilment.inventory.mode");
        this.api = api;
        this.upstream = upstream;
    }

    @Override
    public Optional<List<ReservedLine>> reservation(String orderId) {
        return upstream.call(SERVICE, () -> api.getObject().find(orderId)).map(r -> r.lines().stream()
                .map(l -> new ReservedLine(l.sku(), l.nodeId(), l.qty(), l.lotIds())).toList());
    }
}
