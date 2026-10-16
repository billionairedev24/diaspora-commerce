package com.afrotransact.seller.application;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import com.afrotransact.seller.domain.BillingGrant;
import com.afrotransact.seller.port.BillingGrantRepository;
import com.afrotransact.seller.port.Page;

/**
 * Credits, free periods, charge gates, waived invoices and trial changes: staff adjustments (billing:adjust, with a
 * reason code; replays of the same request key return the first grant) and the application of every grant, including
 * reward rules'. Every grant is recorded before it is applied and notified to the organisation once applied.
 */
public interface BillingAdjustmentService {

    /** Platform credit the next invoices use first. */
    BillingGrant credit(String orgId, long amountCents, String reasonCode, String note, String requestKey);

    /** Free days: the first charge moves out by {@code days} (see FreeDays). */
    BillingGrant freeDays(String orgId, int days, String reasonCode, String note, String requestKey);

    /** Free calendar months: the first charge moves out by {@code months}. */
    BillingGrant freeMonths(String orgId, int months, String reasonCode, String note, String requestKey);

    /** Waives the rest of an open invoice. */
    BillingGrant waiveInvoice(String orgId, String invoiceId, String reasonCode, String note, String requestKey);

    /**
     * Per-seller trial override: before activation the next subscription gets {@code days} of trial; while trialing
     * the trial ends {@code days} after it started (it must still end in the future).
     */
    BillingGrant setTrial(String orgId, int days, String reasonCode, String note, String requestKey);

    /**
     * Puts a condition on the plan charges (see {@code ChargeGate}): start charging after the seller reaches
     * {@code threshold} of {@code metric}, waive while they keep it, or waive until {@code until}.
     */
    BillingGrant setChargeGate(String orgId, GateInput gate, String reasonCode, String note, String requestKey);

    /** Removes the charge gate: plan charges apply as normal again. */
    BillingGrant clearChargeGate(String orgId, String reasonCode, String note, String requestKey);

    /** Grants log (staff). */
    Page<BillingGrant> grants(BillingGrantRepository.GrantFilter filter, int page, int size);

    List<BillingGrantRepository.ReasonCode> reasonCodes();

    /** Applies a recorded grant (no access check; idempotent). */
    BillingGrant apply(UUID grantId);

    /** Job: retries PENDING / FAILED grants. Returns how many were applied. */
    int retryPending();

    /**
     * A charge gate to set.
     *
     * @param mode      START_AFTER, WAIVE_WHILE or WAIVE_UNTIL
     * @param metric    GROSS_SALES, COMPLETED_ORDERS, COMPLETED_BOOKINGS or ACTIVE_LISTINGS (not for WAIVE_UNTIL)
     * @param threshold in cents for GROSS_SALES, else a count (not for WAIVE_UNTIL)
     * @param until     WAIVE_UNTIL only
     */
    record GateInput(String mode, String metric, Long threshold, Instant until) {
    }
}
