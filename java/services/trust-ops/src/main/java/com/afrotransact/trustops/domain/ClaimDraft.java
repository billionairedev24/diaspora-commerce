package com.afrotransact.trustops.domain;

import java.util.List;

/** What a customer submits when opening a claim on an order or one of its shipments. */
public record ClaimDraft(ClaimType type, String orderId, String orderNumber, String shipmentId, String sellerOrgId,
                         String customerId, long amountMinor, String currency, String description,
                         List<String> evidenceAssetIds) {

    public ClaimDraft {
        evidenceAssetIds = evidenceAssetIds == null ? List.of() : List.copyOf(evidenceAssetIds);
    }
}
