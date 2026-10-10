package com.afrotransact.taxreturns.port;

import java.time.LocalDate;
import java.util.UUID;

import com.afrotransact.taxreturns.domain.returns.ReturnAddress;

/**
 * fulfilment-service return pickups ({@code POST /v1/return-pickups}, client scope {@code delivery:returns}): a
 * driver collects a DRIVER_PICKUP return on a RETURN_PICKUP route stop. Throws {@link UpstreamException}.
 */
public interface ReturnPickupScheduler {

    /** Schedules the pickup (idempotent per RMA) and returns fulfilment's pickup id. */
    String schedule(PickupRequest request);

    /** Cancels a scheduled pickup (idempotent). */
    void cancel(String pickupId, String reason);

    /**
     * @param depotNodeId FC the parcel goes back to (return centre for AfroTransact-fulfilled orders), may be null
     * @param readyFrom   first day the customer is ready
     * @param collectBy   last day of the pickup window (the RMA's hand-over deadline)
     */
    record PickupRequest(UUID rmaId, String rmaNumber, UUID orderId, String orderNumber, UUID shipmentId,
                         String customerId, String sellerOrgId, ReturnAddress address, String depotNodeId,
                         LocalDate readyFrom, LocalDate collectBy, int units) {
    }
}
