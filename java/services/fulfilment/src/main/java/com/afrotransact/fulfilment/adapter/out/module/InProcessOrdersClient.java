package com.afrotransact.fulfilment.adapter.out.module;

import com.afrotransact.fulfilment.port.CourierEvent;
import com.afrotransact.fulfilment.port.OrdersClient;
import com.afrotransact.module.InProcessCalls;
import com.afrotransact.orders.api.ShipmentApi;
import org.springframework.beans.factory.ListableBeanFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.stereotype.Component;

/**
 * {@link OrdersClient} through orders' published {@link ShipmentApi} inside this JVM (mode {@code in-process}), entered as
 * fulfilment's caller principal (scopes {@code orders:update-status} and {@code delivery:relay}). Same payload as the HTTP
 * client (the note is cut at 500 characters, the proof is always sent) and the same {@code UpstreamFailure}s.
 */
@Component
@ConditionalOnExpression("'${at.fulfilment.orders.mode:${at.fulfilment.mode:http}}' == 'in-process'")
class InProcessOrdersClient implements OrdersClient {

    private static final String SERVICE = "orders";

    private final ObjectProvider<ShipmentApi> api;
    private final ModuleUpstream upstream;

    InProcessOrdersClient(ObjectProvider<ShipmentApi> api, ModuleUpstream upstream, ListableBeanFactory beans) {
        InProcessCalls.requireRunning(beans.getBeanNamesForType(ShipmentApi.class, true, false).length > 0,
                "fulfilment", "orders", "at.fulfilment.orders.mode");
        this.api = api;
        this.upstream = upstream;
    }

    @Override
    public void transition(String shipmentId, String to, String reason, String idempotencyKey) {
        upstream.run(SERVICE, () -> api.getObject().transition(shipmentId, to, reason, idempotencyKey));
    }

    @Override
    public void courierEvent(String shipmentId, CourierEvent e, String driverBearer) {
        // orders accepts courier events only from this relay; the driver was checked against the route here and is named.
        ShipmentApi.CourierEvent event = new ShipmentApi.CourierEvent(e.eventId(), e.status(), e.occurredAt(),
                e.note() == null || e.note().length() <= 500 ? e.note() : e.note().substring(0, 500), e.driverId(),
                new ShipmentApi.Proof(e.photoAssetId(), e.handoverMethod(), e.handoverCode(), e.recipientName(),
                        e.relationship(), e.signatureAssetId(), e.safePlace(), e.failureReason(), e.latitude(),
                        e.longitude()));
        upstream.run(SERVICE, () -> api.getObject().courierEvent(shipmentId, event));
    }

    @Override
    public void attachTracking(String shipmentId, String carrier, String trackingNumber) {
        upstream.run(SERVICE, () -> api.getObject().attachTracking(shipmentId, carrier, trackingNumber));
    }
}
