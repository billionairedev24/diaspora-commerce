package com.afrotransact.catalog.application;

import java.util.UUID;

import com.afrotransact.catalog.domain.ListingReasonCode;
import com.afrotransact.catalog.domain.Product;
import com.afrotransact.catalog.domain.ProductFlag;

/**
 * Trust and Safety on live listings: reports and flags (published as {@code catalog.product.flagged} for the
 * trust-ops moderation queue) and takedowns ({@code catalog.product.taken_down} / {@code catalog.product.reinstated}).
 */
public interface ListingModerationService {

    /**
     * A signed-in shopper reports a publicly visible listing by product ATIN. One report per shopper and listing:
     * a repeated report returns the earlier one and publishes nothing.
     */
    ReportResult report(String atin, ListingReasonCode reasonCode, String details);

    /**
     * Staff (source STAFF) or a service client (principal type SERVICE, source AUTOMATED) flags a live or paused
     * listing for review; catalog:moderate.
     */
    ProductFlag flag(UUID productId, ListingReasonCode reasonCode, String details, String riskLevel);

    /**
     * Takes a live or paused listing down (status TAKEN_DOWN; hidden from public reads); catalog:moderate. Idempotent:
     * a listing that is already taken down is returned unchanged.
     */
    Product takeDown(UUID productId, ListingReasonCode reasonCode, String note);

    /** Reverses the active takedown (back to LIVE or PAUSED); catalog:moderate. Idempotent. */
    Product restore(UUID productId, String note);

    /** Result of {@link #report}: the stored report and whether it was new. */
    record ReportResult(ProductFlag flag, boolean created) {
    }
}
