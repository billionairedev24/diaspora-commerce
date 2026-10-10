package com.afrotransact.trustops.application;

/** What one SLA sweep changed. */
public record SlaSweepResult(int listingsEscalated, int sellerWindowsLapsed, int claimsEscalated) {
}
