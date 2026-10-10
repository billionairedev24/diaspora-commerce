package com.afrotransact.fulfilment.application;

import java.util.List;

import com.afrotransact.fulfilment.port.CourierEvent;
import com.afrotransact.fulfilment.port.OrdersCommandQueue.Command;

/**
 * Moves shipments in orders-service (the owner of shipment status). Calls are tried right away and fall back to the
 * durable {@code orders_command} queue when orders is unreachable; commands of one shipment never overtake each other.
 */
public interface OrdersRelayService {

    /** Tries each command now (driver token for courier events when given); never throws. */
    void submit(List<Command> commands, String driverBearer);

    /**
     * Forwards a proof of delivery now. orders-service checks the handover code: a refusal throws
     * {@code FulfilmentRuleViolation} with orders' code so the driver can fix it.
     *
     * @return true when orders accepted it, false when it was queued (orders unreachable)
     */
    boolean deliverNow(Command command, String driverBearer);

    /** Retries due queued commands with the service token. @return commands processed */
    int drain(int limit);

    /** Builds a status transition command (service token, fulfilment ops). */
    static Command transition(String shipmentId, String to, String reason, String idempotencyKey) {
        return new Command(0, shipmentId, "TRANSITION", idempotencyKey, to, reason, null, 0, null);
    }

    /** Builds a tracking command (carrier label of an FC carrier shipment). */
    static Command tracking(String shipmentId, String carrier, String trackingNumber) {
        return new Command(0, shipmentId, "TRACKING", "tracking:" + shipmentId + ":" + trackingNumber, carrier,
                trackingNumber, null, 0, null);
    }

    /** Builds a courier event command (idempotency key = courier event id). */
    static Command courier(String shipmentId, CourierEvent event) {
        return new Command(0, shipmentId, "COURIER", event.eventId(), event.status(), null, event, 0, null);
    }
}
