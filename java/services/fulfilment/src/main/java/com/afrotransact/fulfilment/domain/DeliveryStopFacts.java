package com.afrotransact.fulfilment.domain;

/**
 * What the proof-of-delivery rules need to know about a drop-off stop.
 *
 * @param routeId          route the stop belongs to (must be stamped on the photo)
 * @param perishable       any package of the stop is perishable
 * @param handoverRequired the customer must give their 4-digit code
 * @param safePlaceAllowed the customer allows a safe-place drop (never with perishables or hand delivery)
 * @param address          geocoded address of the stop, null when unknown
 * @param expectedPackages packages for this stop
 * @param scannedPackages  packages scanned at the stop
 */
public record DeliveryStopFacts(String routeId, boolean perishable, boolean handoverRequired,
                                boolean safePlaceAllowed, GeoPoint address, int expectedPackages,
                                int scannedPackages) {
}
