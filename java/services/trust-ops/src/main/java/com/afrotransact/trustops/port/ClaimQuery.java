package com.afrotransact.trustops.port;

import com.afrotransact.trustops.domain.ClaimStatus;

/** Claim filters; null fields match everything. {@code openOnly} restricts to AWAITING_SELLER/UNDER_REVIEW/ESCALATED. */
public record ClaimQuery(ClaimStatus status, String sellerOrgId, String customerId, String orderId,
                         boolean openOnly) {
}
