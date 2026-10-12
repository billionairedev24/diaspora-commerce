package com.afrotransact.payments.port;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import com.afrotransact.payments.domain.Money;
import com.afrotransact.payments.domain.PlanFeeDeduction;
import com.afrotransact.payments.domain.PlanFeeWithholding;

/** Seller plan fees withheld from earnings (S298), one row per Stripe invoice id. */
public interface PlanFeeWithholdingRepository {

    /** false when the invoice already has a row (business-key de-duplication). */
    boolean insert(PlanFeeWithholding withholding);

    Optional<PlanFeeWithholding> findByInvoice(String invoiceId);

    /** SELECT … FOR UPDATE by invoice id. */
    Optional<PlanFeeWithholding> lockByInvoice(String invoiceId);

    /** Optimistic: the version must match; returns the withholding with version + 1. */
    PlanFeeWithholding update(PlanFeeWithholding withholding);

    /**
     * The organisation's withholdings with something left to settle (withheld − released − settled ≠ 0), oldest first,
     * locked.
     */
    List<PlanFeeWithholding> lockOutstanding(String orgId, String currency);

    /** Σ (withheld − released − settled) of the organisation: positive while plan fees await recovery. */
    long outstanding(String orgId, String currency);

    /** Newest first. */
    List<PlanFeeWithholding> ofOrg(String orgId, int limit);

    /** false when a release with this key exists already (it was posted before). */
    boolean insertRelease(String idempotencyKey, UUID withholdingId, Money amount, String reason, UUID journalId,
                          Instant at);

    /** The withholding a release key was posted for. */
    Optional<UUID> releaseOf(String idempotencyKey);

    void insertDeduction(UUID id, UUID withholdingId, String orderId, Money amount, UUID journalId, Instant at);

    /** Deductions per withholding, oldest first. */
    Map<UUID, List<PlanFeeDeduction>> deductions(Collection<UUID> withholdingIds);
}
