package com.afrotransact.orders.application;

/**
 * Community pre-order settings (S272, {@code at.orders.preorders.*}).
 *
 * @param enabled          ask delivery-runs which cart lines are pre-orders (off: every line ships now)
 * @param jobBatch         shipments / orders per scheduler run (missed deliveries, expired payment links)
 * @param linkExpiryHours  default life of a late add's payment link when the request names none (24)
 */
public record PreorderSettings(boolean enabled, int jobBatch, int linkExpiryHours) {

    public PreorderSettings {
        jobBatch = jobBatch <= 0 ? 100 : jobBatch;
        linkExpiryHours = linkExpiryHours <= 0 ? 24 : linkExpiryHours;
    }
}
