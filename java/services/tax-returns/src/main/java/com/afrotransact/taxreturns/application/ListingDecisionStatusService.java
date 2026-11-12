package com.afrotransact.taxreturns.application;

import java.util.List;
import java.util.Map;

import com.afrotransact.taxreturns.application.LineDecisionService.ListingStatus;

/**
 * The batched read behind the catalog publish gate: "is the stored tax decision of this variant, for this seller,
 * RESOLVED?". It reads the same classification record and rules checkout's quote reads, so the answer can never differ
 * from what checkout will do. Read-only (tax:classify, tax:read or tax:classify-admin).
 */
public interface ListingDecisionStatusService {

    /** Most items one call answers. */
    int MAX_ITEMS = 500;

    /** @return the status of each requested variant ATIN (the key is the ATIN as sent) */
    Map<String, ListingStatus> statusOf(List<Item> items);

    /** @param sellerOrgId the seller whose behaviour the decision is read under (the listing owner for catalog) */
    record Item(String atin, String sellerOrgId) {
    }
}
