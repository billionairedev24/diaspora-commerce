package com.afrotransact.fulfilment.application;

import java.time.LocalDate;

/**
 * tax-returns asks for a driver to collect a DRIVER_PICKUP return.
 *
 * @param depotNodeId FC the parcel goes back to (the return centre for AfroTransact-fulfilled orders; default
 *                    {@code at.fulfilment.default-node})
 * @param readyFrom   first day the customer is ready (default today)
 * @param collectBy   last day of the pickup window (the RMA's hand-over deadline)
 */
public record ScheduleReturnPickupCommand(String rmaId, String rmaNumber, String orderId, String orderNumber,
                                          String shipmentId, String customerId, String sellerOrgId, Address address,
                                          String depotNodeId, LocalDate readyFrom, LocalDate collectBy, int units) {

    /** The customer's address (from the order: no address typed in the app). */
    public record Address(String name, String phone, String line1, String line2, String city, String state,
                          String postalCode, String country, Double lat, Double lon) {
    }
}
