package com.afrotransact.catalog.domain;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * A seller's request to sell an item without a barcode where the rules want one (S387). Until staff decide, the page is
 * {@link ProductStatus#PENDING_EXEMPTION} and not live.
 *
 * @param sellerReason the seller's own reason for having no barcode
 */
public record GtinExemptionRequest(UUID id, UUID productId, String productAtin, String requestedByOrg,
                                   String sellerReason, ExemptionRequirement requiredBecause, ExemptionStatus status,
                                   Instant requestedAt, Instant slaDueAt, String decidedBy, Instant decidedAt,
                                   String reason, long version) {

    /** What the seller reads while the request waits. */
    public static final String SELLER_MESSAGE = "We are checking that this item has no barcode.";

    public GtinExemptionRequest {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(productId, "productId");
        Objects.requireNonNull(requestedByOrg, "requestedByOrg");
        Objects.requireNonNull(requiredBecause, "requiredBecause");
        Objects.requireNonNull(status, "status");
    }

    public static GtinExemptionRequest filed(UUID productId, String productAtin, String requestedByOrg,
                                             String sellerReason, ExemptionRequirement requiredBecause, Instant now,
                                             Instant slaDueAt) {
        return new GtinExemptionRequest(UUID.randomUUID(), productId, productAtin, requestedByOrg, sellerReason,
                requiredBecause, ExemptionStatus.PENDING, now, slaDueAt, null, null, null, 0);
    }

    public boolean open() {
        return status == ExemptionStatus.PENDING;
    }

    public GtinExemptionRequest approved(String by, String why, Instant now) {
        return decided(ExemptionStatus.APPROVED, by, why, now);
    }

    public GtinExemptionRequest rejected(String by, String why, Instant now) {
        return decided(ExemptionStatus.REJECTED, by, why, now);
    }

    private GtinExemptionRequest decided(ExemptionStatus next, String by, String why, Instant now) {
        if (!open()) {
            throw new IllegalStateException("The request was already decided (" + status + ")");
        }
        if (why == null || why.strip().length() < ProductChangeRequest.MIN_REASON) {
            throw new IllegalArgumentException("Give a reason of at least " + ProductChangeRequest.MIN_REASON
                    + " characters");
        }
        return new GtinExemptionRequest(id, productId, productAtin, requestedByOrg, sellerReason, requiredBecause,
                next, requestedAt, slaDueAt, by, now, why.strip(), version);
    }
}
