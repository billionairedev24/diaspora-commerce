package com.afrotransact.retail.application;

import java.util.List;
import java.util.UUID;

import com.afrotransact.retail.domain.GoodsReceipt;
import com.afrotransact.retail.domain.LandedCharge;
import com.afrotransact.retail.domain.ReceiptStatus;
import com.afrotransact.retail.domain.ReceiptValidation;
import com.afrotransact.retail.port.Page;

/** Goods receipt and validation, posting lots to inventory (S072) with landed cost and GL (S073). */
public interface ReceivingService {

    GoodsReceipt open(OpenReceiptCommand command);

    GoodsReceipt count(UUID id, List<ReceiptLineInput> lines, Long expectedVersion);

    /** Landed-cost charges of a receipt made without a shipment (shipment receipts use the shipment's charges). */
    GoodsReceipt updateCharges(UUID id, List<LandedCharge> charges, Long expectedVersion);

    /** Purchasing allows more than the over-receipt tolerance. */
    GoodsReceipt approveOverReceipt(UUID id, Long expectedVersion);

    /** Errors, warnings and variances without changing anything. */
    ReceiptValidation validate(UUID id);

    /**
     * Validates, allocates landed cost, posts the lots to inventory (Idempotency-Key {@code retail-<receipt id>}),
     * then updates the PO, shipment, supplier scorecard and GL. Safe to retry: a receipt left POSTING by an
     * inventory outage completes on the next call; a completed receipt is returned as is.
     */
    GoodsReceipt complete(UUID id);

    GoodsReceipt resolveClaim(UUID id, String claimRef, String resolution);

    GoodsReceipt get(UUID id);

    Page<GoodsReceipt> list(ReceiptStatus status, UUID poId, int page, int size);
}
