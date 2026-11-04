package com.afrotransact.taxreturns.domain.tax;

import java.time.Instant;
import java.time.LocalDate;

/**
 * What staff recorded for one state: its thresholds (when they differ from the defaults) and the registration.
 *
 * @param transactionsThreshold the state's transaction-count threshold, or null when it has none
 */
public record NexusSetting(String jurisdiction, long salesThresholdMinor, Integer transactionsThreshold,
                           boolean registered, String registrationNumber, LocalDate registeredOn, String note,
                           String updatedBy, Instant updatedAt) {
}
