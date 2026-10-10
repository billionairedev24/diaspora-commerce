package com.afrotransact.retail.domain;

import java.util.Objects;

/**
 * A receiving discrepancy: shortages raise a claim against the supplier, damaged units a claim against the carrier;
 * accepted over-receipts need no claim.
 */
public record Discrepancy(String claimRef, DiscrepancyType type, int poLineNo, String sku, int expectedQty,
                          int receivedQty, int qty, String claimAgainst, ClaimStatus status, String note) {

    public Discrepancy {
        Objects.requireNonNull(claimRef, "claimRef");
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(status, "status");
    }

    public Discrepancy resolve(String resolution) {
        if (status != ClaimStatus.OPEN) {
            throw new RetailStateConflict("retail.claim-not-open", "Claim " + claimRef + " is " + status);
        }
        return new Discrepancy(claimRef, type, poLineNo, sku, expectedQty, receivedQty, qty, claimAgainst,
                ClaimStatus.RESOLVED, resolution);
    }
}
