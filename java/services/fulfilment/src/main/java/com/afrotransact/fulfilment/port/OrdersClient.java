package com.afrotransact.fulfilment.port;

/** orders-service: the owner of shipment status. Failures throw {@link UpstreamFailure}. */
public interface OrdersClient {

    /** {@code POST /v1/shipments/{id}/transitions} with the service token (fulfilment ops). */
    void transition(String shipmentId, String to, String reason, String idempotencyKey);

    /**
     * {@code POST /v1/shipments/{id}/courier-events}. Always sent with the service token (orders accepts courier
     * events only from the fulfilment relay and takes the driver from {@code event.driverId()}); {@code driverBearer}
     * is no longer forwarded and is kept only so callers need not change.
     */
    void courierEvent(String shipmentId, CourierEvent event, String driverBearer);

    /** {@code POST /v1/shipments/{id}/tracking} with the carrier label of an FC carrier shipment. */
    void attachTracking(String shipmentId, String carrier, String trackingNumber);
}
