package com.afrotransact.inventory.application;

import java.util.List;

import com.afrotransact.inventory.domain.Lot;

/** Lots created by a receipt (sellable, short-dated quarantine and -DMG quarantine lots). */
public record ReceiptResult(String receiptRef, String nodeId, String ownerOrg, List<Lot> lots, boolean replayed) {

    public ReceiptResult {
        lots = lots == null ? List.of() : List.copyOf(lots);
    }

    ReceiptResult asReplay() {
        return new ReceiptResult(receiptRef, nodeId, ownerOrg, lots, true);
    }
}
