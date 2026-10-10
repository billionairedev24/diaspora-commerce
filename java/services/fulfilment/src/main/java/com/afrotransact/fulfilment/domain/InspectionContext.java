package com.afrotransact.fulfilment.domain;

/**
 * Facts the inspection rules need from the route and vehicle.
 *
 * @param perishableOnBoard the route carries perishables (cold box must read 33–40 °F)
 * @param lastOdometerMiles last known odometer of the vehicle, null when unknown
 */
public record InspectionContext(boolean perishableOnBoard, Integer lastOdometerMiles) {
}
