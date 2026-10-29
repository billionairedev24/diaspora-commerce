package com.afrotransact.orders.application;

import java.time.Duration;

/**
 * Community pre-order settings (S272, {@code at.orders.preorders.*}).
 *
 * @param enabled                ask delivery-runs which cart lines are pre-orders (off: every line ships now)
 * @param runsEnabled            offer the next open delivery run staff scheduled (S261) to AfroTransact-held goods
 *                               before looking for a window; off: windows only
 * @param jobBatch               shipments / orders per scheduler run (missed deliveries, expired pay-now links)
 * @param linkExpiryHours        default life of a late add's pay-now link when the request names none (24)
 * @param payPageBaseUrl         the storefront (PUBLIC_WEB_URL): pay-now links are
 *                               {@code <payPageBaseUrl>/orders/<number>/pay?token=…}
 * @param lateAddDuplicateWindow a second late add for the same customer, window and items within this period is
 *                               refused (409 orders.late-add-duplicate) even with another Idempotency-Key
 */
public record PreorderSettings(boolean enabled, boolean runsEnabled, int jobBatch, int linkExpiryHours, String payPageBaseUrl,
                               Duration lateAddDuplicateWindow) {

    /** Settings with delivery runs offered. */
    public PreorderSettings(boolean enabled, int jobBatch, int linkExpiryHours, String payPageBaseUrl,
                            Duration lateAddDuplicateWindow) {
        this(enabled, true, jobBatch, linkExpiryHours, payPageBaseUrl, lateAddDuplicateWindow);
    }

    public PreorderSettings {
        jobBatch = jobBatch <= 0 ? 100 : jobBatch;
        linkExpiryHours = linkExpiryHours <= 0 ? 24 : linkExpiryHours;
        payPageBaseUrl = payPageBaseUrl == null || payPageBaseUrl.isBlank() ? "http://localhost:3000"
                : payPageBaseUrl.replaceAll("/+$", "");
        lateAddDuplicateWindow = lateAddDuplicateWindow == null || lateAddDuplicateWindow.isNegative()
                ? Duration.ofMinutes(10) : lateAddDuplicateWindow;
    }
}
