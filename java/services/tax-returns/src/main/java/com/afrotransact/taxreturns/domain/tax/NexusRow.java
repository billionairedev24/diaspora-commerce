package com.afrotransact.taxreturns.domain.tax;

import java.time.Instant;
import java.time.LocalDate;

/**
 * One state in the tracker: sales into it over the trailing twelve months against its thresholds.
 *
 * @param salesMinor    gross sales shipped to the state over the window, net of refunds
 * @param orders        orders shipped to it over the window
 * @param percent       the higher of the sales and transaction percentages of their thresholds (can pass 100)
 * @param customised    staff changed this state's thresholds or registration
 */
public record NexusRow(String jurisdiction, String name, boolean facilitator, long salesMinor, long orders,
                       long salesThresholdMinor, Integer transactionsThreshold, int percent, NexusStatus status,
                       boolean registered, String registrationNumber, LocalDate registeredOn, String note,
                       boolean customised, String updatedBy, Instant updatedAt) {

    /** Approaching starts here. */
    public static final int APPROACHING_PERCENT = 80;

    public static NexusStatus statusOf(boolean registered, int percent) {
        if (registered) {
            return NexusStatus.REGISTERED;
        }
        return percent >= 100 ? NexusStatus.EXCEEDED : percent >= APPROACHING_PERCENT ? NexusStatus.APPROACHING
                : NexusStatus.BELOW;
    }

    /** The higher of the two measures as a whole percent of its threshold (0 when a threshold is 0 or unset). */
    public static int percentOf(long sales, long salesThreshold, long orders, Integer transactionsThreshold) {
        long bySales = salesThreshold > 0 ? sales * 100 / salesThreshold : 0;
        long byOrders = transactionsThreshold != null && transactionsThreshold > 0
                ? orders * 100 / transactionsThreshold : 0;
        return (int) Math.min(Math.max(Math.max(bySales, byOrders), 0), 100_000);
    }
}
