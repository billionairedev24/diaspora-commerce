package com.afrotransact.orders.adapter.out.module;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;

import com.afrotransact.inventory.api.ReservationApi;
import com.afrotransact.module.InProcessCalls;
import com.afrotransact.orders.port.InventoryClient;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.ListableBeanFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.stereotype.Component;

/**
 * {@link InventoryClient} through inventory's published {@link ReservationApi} inside this JVM (mode {@code in-process}),
 * entered as orders' caller principal (scope {@code inventory:reserve}). Same results and failures as over HTTP: a shortfall is
 * the non-retryable {@code inventory.insufficient-stock} (409) with one detail line per SKU, unknown groups are 404. The order
 * id stays the idempotency key. The API is looked up on each call (modules reach each other through ports, a constructor
 * dependency could be circular).
 */
@Component
@ConditionalOnExpression("'${at.orders.inventory.mode:${at.orders.mode:http}}' == 'in-process'")
class InProcessInventoryClient implements InventoryClient {

    private static final String SERVICE = "inventory";

    private final ObjectProvider<ReservationApi> api;
    private final ModuleUpstream upstream;

    InProcessInventoryClient(ObjectProvider<ReservationApi> api, ModuleUpstream upstream, ListableBeanFactory beans) {
        InProcessCalls.requireRunning(beans.getBeanNamesForType(ReservationApi.class, true, false).length > 0, "orders",
                "inventory", "at.orders.inventory.mode");
        this.api = api;
        this.upstream = upstream;
    }

    @Override
    public List<Reserved> reserve(String orderId, List<Item> items) {
        ReservationApi.ReservationInfo reservation = upstream.call(SERVICE, () -> api.getObject().reserve(orderId, orderId,
                items.stream().map(i -> new ReservationApi.Line(i.sku(), null, i.qty(), i.group())).toList()));
        List<Reserved> out = new ArrayList<>();
        for (ReservationApi.ReservedLine l : reservation.lines()) {
            out.add(new Reserved(l.sku(), l.nodeId(), l.qty(), l.group()));
        }
        return out;
    }

    @Override
    public void confirm(String orderId) {
        upstream.run(SERVICE, () -> api.getObject().confirm(orderId));
    }

    @Override
    public void commit(String orderId) {
        upstream.run(SERVICE, () -> api.getObject().commit(orderId));
    }

    @Override
    public void release(String orderId, String reason) {
        upstream.run(SERVICE, () -> api.getObject().release(orderId, reason == null ? "" : reason));
    }

    @Override
    public void commitGroups(String orderId, String shipmentId, List<String> groups) {
        upstream.run(SERVICE, () -> api.getObject().commitGroups(orderId, new LinkedHashSet<>(groups)));
    }

    @Override
    public void releaseGroups(String orderId, String shipmentId, List<String> groups, String reason) {
        upstream.run(SERVICE, () -> api.getObject().releaseGroups(orderId, new LinkedHashSet<>(groups),
                reason == null ? "" : reason));
    }
}
