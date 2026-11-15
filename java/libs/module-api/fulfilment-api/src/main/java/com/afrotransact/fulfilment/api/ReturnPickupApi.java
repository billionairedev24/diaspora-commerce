package com.afrotransact.fulfilment.api;

import java.time.Instant;
import java.util.Optional;

/**
 * Return pickups tax-returns schedules over HTTP today ({@code POST /v1/return-pickups}, {@code GET /v1/return-pickups/{id}},
 * {@code POST /v1/return-pickups/{id}/cancel}), entered in-process under the calling module's principal. Over HTTP these
 * endpoints admit staff and service principals only (the {@code @StaffOrServiceOnly} guard); the caller of an in-process
 * call is a service principal, so the same holds here. Failures are {@code ModuleApiException} with the status the endpoint
 * answers (404, 409, 422).
 */
public interface ReturnPickupApi {

    /** One pickup per RMA: a retry returns the existing pickup; the id of the pickup. */
    String schedule(PickupRequest request);

    /** The driver assigned to the pickup, empty when none is. */
    Optional<String> assignedDriver(String pickupId);

    /** Cancels the pickup's stop unless the driver is already there (409); idempotent. */
    void cancel(String pickupId, String reason);

    /**
     * @param depotNodeId the return centre, null for the default
     * @param readyFrom   earliest collection time, null for now
     * @param collectBy   latest collection time, null for no deadline
     */
    record PickupRequest(String rmaId, String rmaNumber, String orderId, String orderNumber, String shipmentId,
                         String customerId, String sellerOrgId, Address address, String depotNodeId, Instant readyFrom,
                         Instant collectBy, int units) {
    }

    record Address(String name, String phone, String line1, String line2, String city, String state, String postalCode,
                   String country) {
    }
}
