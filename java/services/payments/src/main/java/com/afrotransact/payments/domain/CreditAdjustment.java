package com.afrotransact.payments.domain;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * A staff grant or correction of a customer's credit. Up to the staff-set limit one person posts it; above it, one
 * person proposes and a different one approves (the maker-checker of {@link LedgerAdjustment}).
 */
public record CreditAdjustment(UUID id, String customerId, Money amount, CreditSource source, String reason,
                               AdjustmentStatus status, String proposedBy, Instant proposedAt, String decidedBy,
                               Instant decidedAt, String decisionNote, UUID entryId) {

    static final int MIN_REASON = 15;

    public static CreditAdjustment propose(UUID id, String customerId, Money amount, CreditSource source,
                                           String reason, String actor, Instant now) {
        if (customerId == null || customerId.isBlank()) {
            throw new PaymentsRuleViolation("payments.invalid-request", "Name the customer",
                    Map.of("customerId", "Required"));
        }
        if (amount.isZero()) {
            throw new PaymentsRuleViolation("payments.credit-amount", "The amount cannot be zero",
                    Map.of("amountMinor", "Must not be 0"));
        }
        if (source != CreditSource.STAFF && source != CreditSource.PROMO) {
            throw new PaymentsRuleViolation("payments.credit-source", "Staff grant credit as STAFF or PROMO",
                    Map.of("source", "STAFF or PROMO"));
        }
        if (reason == null || reason.trim().length() < MIN_REASON) {
            throw new PaymentsRuleViolation("payments.reason-required",
                    "Say why (at least " + MIN_REASON + " characters)", Map.of("reason", "Required"));
        }
        return new CreditAdjustment(id, customerId.trim(), amount, source, reason.trim(), AdjustmentStatus.PROPOSED,
                actor, now, null, null, null, null);
    }

    /** The approver is never the proposer. */
    public CreditAdjustment approve(String approver, String note, UUID postedEntry, Instant now) {
        requireProposed();
        if (approver.equals(proposedBy)) {
            throw new PaymentsForbidden("payments.adjustment-four-eyes",
                    "A different person must approve an adjustment you proposed");
        }
        return new CreditAdjustment(id, customerId, amount, source, reason, AdjustmentStatus.POSTED, proposedBy,
                proposedAt, approver, now, blankToNull(note), postedEntry);
    }

    /** Posted by its maker alone because it is within the staff-set limit. */
    public CreditAdjustment postedByMaker(UUID postedEntry, Instant now) {
        requireProposed();
        return new CreditAdjustment(id, customerId, amount, source, reason, AdjustmentStatus.POSTED, proposedBy,
                proposedAt, proposedBy, now, null, postedEntry);
    }

    /** Anyone with the approval permission may reject, the proposer included (withdrawing it). */
    public CreditAdjustment reject(String actor, String note, Instant now) {
        requireProposed();
        if (note == null || note.isBlank()) {
            throw new PaymentsRuleViolation("payments.reason-required", "Say why it is rejected",
                    Map.of("note", "Required"));
        }
        return new CreditAdjustment(id, customerId, amount, source, reason, AdjustmentStatus.REJECTED, proposedBy,
                proposedAt, actor, now, note.trim(), null);
    }

    private void requireProposed() {
        if (status != AdjustmentStatus.PROPOSED) {
            throw new PaymentsRuleViolation("payments.adjustment-decided", "This adjustment was already " + status);
        }
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }
}
