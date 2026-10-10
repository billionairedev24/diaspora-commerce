package com.afrotransact.fulfilment.domain;

/**
 * Result of a successful proof-of-delivery check.
 *
 * @param distanceMeters distance between the photo's GPS stamp and the address, null when the address is not geocoded
 */
public record PodAssessment(Double distanceMeters) {
}
