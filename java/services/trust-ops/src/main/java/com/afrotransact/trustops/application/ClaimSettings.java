package com.afrotransact.trustops.application;

/**
 * Claim settings ({@code at.trustops.claims.*}).
 *
 * @param verifyOwnership check claimant, shipment and seller against orders-service's order lookup when a claim is
 *                        opened
 */
public record ClaimSettings(boolean verifyOwnership) {
}
