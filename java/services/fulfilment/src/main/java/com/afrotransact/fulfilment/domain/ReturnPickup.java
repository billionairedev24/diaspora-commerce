package com.afrotransact.fulfilment.domain;

import java.time.Instant;
import java.time.LocalDate;
import java.util.Objects;

/**
 * A customer return to collect by AfroTransact Delivery, scheduled by tax-returns-service for a DRIVER_PICKUP RMA.
 *
 * @param id                pickup id
 * @param rmaId             tax-returns RMA id (one pickup per RMA)
 * @param rmaNumber         RMA-XXXXXXXX, shown to the driver
 * @param depotNodeId       FC the driver brings the parcel back to (return centre for AfroTransact-fulfilled orders)
 * @param readyFrom         first day the customer is ready
 * @param collectBy         last day of the pickup window
 * @param units             items in the parcel
 * @param routeId           route carrying the RETURN_PICKUP stop
 * @param stopId            the stop on that route
 * @param note              failure or cancellation reason
 */
public record ReturnPickup(String id, String rmaId, String rmaNumber, String orderId, String orderNumber,
                           String shipmentId, String customerId, String sellerOrgId, String name, String phone,
                           String line1, String line2, String city, String state, String postalCode, String country,
                           GeoPoint location, String depotNodeId, LocalDate readyFrom, LocalDate collectBy, int units,
                           ReturnPickupStatus status, String routeId, String stopId, String note, Instant createdAt,
                           Instant updatedAt) {

    public ReturnPickup {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(rmaId, "rmaId");
        Objects.requireNonNull(status, "status");
        if (line1 == null || line1.isBlank() || postalCode == null || postalCode.isBlank()) {
            throw new FulfilmentRuleViolation("fulfilment.return-address-required",
                    "A return pickup needs the customer's street and ZIP");
        }
        if (units <= 0) {
            throw new FulfilmentRuleViolation("fulfilment.return-units", "A return pickup needs at least one item");
        }
        if (readyFrom != null && collectBy != null && collectBy.isBefore(readyFrom)) {
            throw new FulfilmentRuleViolation("fulfilment.return-window", "collectBy must not be before readyFrom");
        }
    }

    /** Put on a route as a RETURN_PICKUP stop. */
    public ReturnPickup routed(String route, String stop, Instant at) {
        requireStatus(ReturnPickupStatus.REQUESTED, "route");
        return with(ReturnPickupStatus.ROUTED, route, stop, null, at);
    }

    public ReturnPickup collected(Instant at) {
        requireStatus(ReturnPickupStatus.ROUTED, "collect");
        return with(ReturnPickupStatus.COLLECTED, routeId, stopId, null, at);
    }

    public ReturnPickup failed(String why, Instant at) {
        requireStatus(ReturnPickupStatus.ROUTED, "fail");
        return with(ReturnPickupStatus.FAILED, routeId, stopId, why, at);
    }

    /** Cancelled by tax-returns (RMA cancelled) or dispatch; already-cancelled pickups stay as they are. */
    public ReturnPickup cancelled(String why, Instant at) {
        if (status == ReturnPickupStatus.CANCELLED) {
            return this;
        }
        if (!status.open()) {
            throw new FulfilmentConflict("fulfilment.return-pickup-closed", "Return pickup " + rmaNumber + " is "
                    + status);
        }
        return with(ReturnPickupStatus.CANCELLED, routeId, stopId, why, at);
    }

    /** Back in the pool (its stop was removed from an unstarted route). */
    public ReturnPickup unrouted(Instant at) {
        return status == ReturnPickupStatus.ROUTED ? with(ReturnPickupStatus.REQUESTED, null, null, null, at) : this;
    }

    /** One-line address for the stop. */
    public String addressLine() {
        return line2 == null || line2.isBlank() ? line1 : line1 + ", " + line2;
    }

    private void requireStatus(ReturnPickupStatus expected, String action) {
        if (status != expected) {
            throw new FulfilmentConflict("fulfilment.return-pickup-state", "Cannot " + action + " return pickup "
                    + rmaNumber + ": it is " + status);
        }
    }

    private ReturnPickup with(ReturnPickupStatus next, String route, String stop, String why, Instant at) {
        return new ReturnPickup(id, rmaId, rmaNumber, orderId, orderNumber, shipmentId, customerId, sellerOrgId, name,
                phone, line1, line2, city, state, postalCode, country, location, depotNodeId, readyFrom, collectBy,
                units, next, route, stop, why == null ? note : why, createdAt, at);
    }
}
