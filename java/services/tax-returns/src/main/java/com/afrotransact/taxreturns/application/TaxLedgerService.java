package com.afrotransact.taxreturns.application;

import java.time.YearMonth;
import java.util.List;
import java.util.UUID;

import com.afrotransact.taxreturns.domain.tax.TaxTransaction;

/** Tax ledger: refund reversals, monthly liability and seller statements (S116). */
public interface TaxLedgerService {

    /** Marketplace liability per jurisdiction for a month (tax:read). */
    LiabilityReport liability(YearMonth month);

    /** Tax collected on a seller's behalf per jurisdiction (the seller with payouts:read, or staff with tax:read). */
    LiabilityReport sellerStatement(String sellerOrgId, YearMonth month);

    /** Ledger entries of an order (tax:read). */
    List<TaxTransaction> forOrder(UUID orderId);

    /**
     * Books a reversal for a refund made outside this service's orchestration (refunds:issue). Idempotent per
     * (refundRef, line).
     */
    int reverse(UUID orderId, String refundRef, String currency, List<ReversalLine> lines);

    /** Consumer of payments.refunded: books (or voids, when FAILED) the reversal. False for a re-delivery. */
    boolean onPaymentRefunded(UUID eventId, PaymentRefunded event);
}
