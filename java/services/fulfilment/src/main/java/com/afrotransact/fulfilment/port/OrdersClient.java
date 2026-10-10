package com.afrotransact.fulfilment.port;

/** orders-service: the owner of shipment status. Failures throw {@link UpstreamFailure}. */
public interface OrdersClient {

    /** {@code POST /v1/shipments/{id}/transitions} with the service token (fulfilment ops). */
    void transition(String shipmentId, String to, String reason, String idempotencyKey);

    /**
     * {@code POST /v1/shipments/{id}/courier-events}. Sent with the driver's own token when {@code driverBearer} is
     * given (live sync), otherwise with the service token (queued retries).
     */
    void courierEvent(String shipmentId, CourierEvent event, String driverBearer);

    /** {@code POST /v1/shipments/{id}/tracking} with the carrier label of an FC carrier shipment. */
    void attachTracking(String shipmentId, String carrier, String trackingNumber);
}
