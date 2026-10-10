package com.afrotransact.fulfilment.domain;

/**
 * Decision on a reported problem.
 *
 * @param stopOutcome    what happens to the stop
 * @param packageStatus  status for the stop's packages (null = unchanged)
 * @param reportFailure  forward a delivery_failed courier event to orders-service
 * @param reason         human-readable reason recorded and forwarded
 */
public record ProblemDecision(StopOutcome stopOutcome, PackageStatus packageStatus, boolean reportFailure,
                              String reason) {
}
