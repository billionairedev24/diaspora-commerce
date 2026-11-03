package com.afrotransact.payments.application;

import java.util.List;
import java.util.UUID;

import com.afrotransact.payments.application.StripeLookupService.StripeLookup;
import com.afrotransact.payments.domain.Journal;
import com.afrotransact.payments.domain.LedgerAdjustment;
import com.afrotransact.payments.domain.ReconciliationItem;
import com.afrotransact.payments.domain.ReconciliationStatus;

/** Finance's work on the differences between the ledger and Stripe. */
public interface ReconciliationWorkflowService {

    /** Newest first; {@code status} null for all (payments:read). */
    List<ReconciliationItem> items(ReconciliationStatus status, int limit);

    /** One item with the ledger's journals for its Stripe object, Stripe's own view and the adjustments proposed. */
    ItemDetail item(UUID id);

    /** Marks an open difference explained, with the reason, and posts nothing (ledger:adjust). */
    ReconciliationItem explain(UUID id, String note);

    /**
     * @param stripe      Stripe's view of the object (never null; it says if Stripe could not be asked)
     * @param journals    everything the ledger booked against the object
     * @param adjustments the corrections proposed or posted for this item
     */
    record ItemDetail(ReconciliationItem item, StripeLookup stripe, List<Journal> journals,
                      List<LedgerAdjustment> adjustments) {
    }
}
