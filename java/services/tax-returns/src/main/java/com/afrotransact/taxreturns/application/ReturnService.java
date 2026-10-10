package com.afrotransact.taxreturns.application;

import java.util.List;
import java.util.UUID;

import com.afrotransact.taxreturns.domain.returns.RefundBreakdown;
import com.afrotransact.taxreturns.domain.returns.Rma;

/** Returns (S114) and their lifecycle up to the refund (S115). */
public interface ReturnService {

    /** Return options for every delivered line of the order (the customer, or staff with returns:manage). */
    ReturnEligibility eligibility(UUID orderId);

    /** Starts a return; the same Idempotency-Key replays the same RMA. */
    Rma request(RequestReturnCommand command, String idempotencyKey);

    Rma get(UUID id);

    Rma getByNumber(String number);

    List<Rma> search(ReturnSearch search);

    /** The customer cancels before drop-off / pickup. */
    Rma cancel(UUID id);

    /** Seller (seller-fulfilled) or staff approves; the label / handover code / refund follows. */
    Rma approve(UUID id, String note);

    Rma reject(UUID id, String note);

    /** Retries the label (drop-off) or handover code (driver pickup) of an approved RMA. */
    Rma issueLabel(UUID id);

    /** The driver collected the parcel (handover code) — first scan. */
    Rma pickedUp(UUID id, String handoverCode);

    /** The parcel arrived at the seller or return centre. */
    Rma receive(UUID id);

    /** Inspection: restock or damaged per line; passed lines are refunded, all failed rejects the RMA. */
    Rma inspect(UUID id, InspectCommand command);

    /** What would be refunded now (from the order snapshot). */
    RefundBreakdown refundQuote(UUID id);

    /** Consumer of carrier.tracking.updated: first scan → in transit, delivered → received. False if unmatched. */
    boolean onCarrierScan(UUID eventId, CarrierScan scan);
}
