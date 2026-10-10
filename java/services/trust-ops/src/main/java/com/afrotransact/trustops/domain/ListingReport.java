package com.afrotransact.trustops.domain;

import java.util.Objects;
import java.util.UUID;

/**
 * Why a live listing is in the queue: the first report or flag that opened the item, and how many arrived in total
 * while it was open.
 *
 * @param riskLevel optional risk level of an automated flag; HIGH raises the item's priority
 * @param reports   number of reports and flags received while the item was open (at least 1)
 */
public record ListingReport(UUID flagId, ListingFlagSource source, ModerationReasonCode reasonCode, String details,
                            String reporterId, String riskLevel, int reports) {

    public ListingReport {
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(reasonCode, "reasonCode");
        details = details == null || details.isBlank() ? null
                : details.length() > 1000 ? details.substring(0, 1000) : details;
        reports = Math.max(reports, 1);
    }

    public boolean highRisk() {
        return "HIGH".equalsIgnoreCase(riskLevel);
    }

    /** Another report or flag arrived for the same open item. */
    public ListingReport another() {
        return new ListingReport(flagId, source, reasonCode, details, reporterId, riskLevel, reports + 1);
    }
}
