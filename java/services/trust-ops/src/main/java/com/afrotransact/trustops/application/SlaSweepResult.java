package com.afrotransact.trustops.application;

/**
 * What one SLA sweep changed.
 *
 * @param sellerRemindersSent claims whose seller got the 24 h {@code trustops.claim.response_due_soon} reminder
 */
public record SlaSweepResult(int listingsEscalated, int sellerRemindersSent, int sellerWindowsLapsed,
                             int claimsEscalated) {
}
