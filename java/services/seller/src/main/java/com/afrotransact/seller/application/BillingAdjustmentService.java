package com.afrotransact.seller.application;

import java.util.List;
import java.util.UUID;

import com.afrotransact.seller.domain.BillingGrant;
import com.afrotransact.seller.port.BillingGrantRepository;
import com.afrotransact.seller.port.Page;

/**
 * Credits, free periods, discounts, waived invoices and trial changes: staff adjustments (billing:adjust, with a reason
 * code; replays of the same request key return the first grant) and the application at Stripe of every grant,
 * including reward rules'. Every grant is recorded before Stripe is called and notified to the organisation once
 * applied.
 */
public interface BillingAdjustmentService {

    /** A customer balance credit the next invoices use. */
    BillingGrant credit(String orgId, long amountCents, String reasonCode, String note, String requestKey);

    /** Free days: the subscription's trial_end moves out (see FreeDays). */
    BillingGrant freeDays(String orgId, int days, String reasonCode, String note, String requestKey);

    /** Voids an open invoice. */
    BillingGrant waiveInvoice(String orgId, String invoiceId, String reasonCode, String note, String requestKey);

    /**
     * Per-seller trial override: before activation the next subscription gets {@code days} of trial; while trialing
     * the trial ends {@code days} after it started (it must still end in the future).
     */
    BillingGrant setTrial(String orgId, int days, String reasonCode, String note, String requestKey);

    /** Grants log (staff). */
    Page<BillingGrant> grants(BillingGrantRepository.GrantFilter filter, int page, int size);

    List<BillingGrantRepository.ReasonCode> reasonCodes();

    /** Applies a recorded grant at Stripe (no access check; idempotent). */
    BillingGrant apply(UUID grantId);

    /** Job: retries PENDING / FAILED grants. Returns how many were applied. */
    int retryPending();
}
