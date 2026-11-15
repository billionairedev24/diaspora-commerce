package com.afrotransact.orders.adapter.in.module;

import java.util.Locale;

import com.afrotransact.orders.api.ShipmentApi;
import com.afrotransact.orders.application.CourierEventCommand;
import com.afrotransact.orders.application.ShipmentService;
import com.afrotransact.orders.domain.CourierProof;
import com.afrotransact.orders.domain.HandoverMethod;
import com.afrotransact.orders.domain.OrdersRuleViolation;
import com.afrotransact.platform.security.ActorResolver;
import com.afrotransact.platform.security.ModuleEntry;
import org.springframework.stereotype.Component;

/**
 * The in-process entry of {@link ShipmentApi}: the use cases behind the shipment transition, courier-event and tracking
 * endpoints, entered under the calling module's principal. {@link ShipmentService} applies the step and scope checks
 * ({@code delivery:relay} for courier events, who may take which step) as for a token.
 */
@Component
class ShipmentApiAdapter implements ShipmentApi {

    private final ShipmentService shipments;
    private final ModuleEntry entry;

    ShipmentApiAdapter(ShipmentService shipments, ActorResolver actors) {
        this.shipments = shipments;
        this.entry = new ModuleEntry(actors);
    }

    @Override
    public void transition(String shipmentId, String to, String reason, String idempotencyKey) {
        entry.runVoid(() -> shipments.transition(shipmentId, to, reason, null, idempotencyKey), ShipmentFailures::map);
    }

    @Override
    public void courierEvent(String shipmentId, CourierEvent e) {
        entry.runVoid(() -> shipments.courierEvent(shipmentId, new CourierEventCommand(e.eventId(), e.status(),
                e.occurredAt(), e.proof() == null ? null : proof(e.proof()), e.note(), e.driverId())),
                ShipmentFailures::map);
    }

    @Override
    public void attachTracking(String shipmentId, String carrier, String trackingNumber) {
        entry.runVoid(() -> shipments.attachTracking(shipmentId, carrier, trackingNumber), ShipmentFailures::map);
    }

    private static CourierProof proof(Proof p) {
        HandoverMethod method;
        try {
            method = p.handoverMethod() == null ? null
                    : HandoverMethod.valueOf(p.handoverMethod().trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ex) {
            throw new OrdersRuleViolation("orders.invalid-value", "Unknown HandoverMethod " + p.handoverMethod());
        }
        return new CourierProof(p.photoAssetId(), method, p.handoverCode(), p.recipientName(), p.relationship(),
                p.signatureAssetId(), p.safePlace(), p.failureReason(), p.latitude(), p.longitude());
    }
}
