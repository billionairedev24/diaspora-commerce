package com.afrotransact.payments.domain;

import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * An amount a refund took from a seller whose transfer Stripe refused to reverse (typically {@code
 * balance_insufficient}: the money had already been paid out of the connected account). The customer was refunded
 * from the platform balance; the seller now owes the platform {@code amount}. The refund's debit stays on the
 * seller's payable (negative, like a label charge) and this row tracks how the debt is cleared: recovered from the
 * seller's later transfers ({@link #settledBy}), received outside Stripe or written off by Finance with four eyes
 * ({@link #propose}, {@link #approve}, {@link #reject}).
 *
 * @param transferId   the settlement transfer the reversal was refused for
 * @param amount       what the reversal asked for and Stripe refused
 * @param recovered    taken from later transfers so far
 * @param resolved     closed by an approved resolution (received outside Stripe, or written off)
 * @param stripeCode   Stripe's error code, e.g. {@code balance_insufficient}
 * @param reason       Stripe's message
 * @param pending      a resolution waiting for a second person, or null
 * @param decidedBy    who approved (or rejected) the latest proposal
 * @param decisionNote the approver's note
 * @param resolution   how an approved proposal closed it (RECOVERED / WRITE_OFF), null while open or when later
 *                     earnings paid it
 * @param resolutionRef the bank reference of money received outside Stripe, or null
 * @param journalId    the TRANSFER_RECOVERY_OPENED journal
 */
public record TransferRecovery(UUID id, UUID refundId, String orderId, String orgId, UUID transferId,
                               String stripeTransferId, Money amount, Money recovered, Money resolved,
                               TransferRecoveryStatus status, String stripeCode, String reason,
                               ProposedResolution pending, String decidedBy, Instant decidedAt,
                               String decisionNote, RecoveryResolution resolution, String resolutionRef,
                               UUID journalId, Instant createdAt, Instant updatedAt, long version) {

    static final int MIN_REASON = 15;

    /** A resolution one person proposed and a different one has yet to approve. */
    public record ProposedResolution(RecoveryResolution kind, String reason, String reference, String by,
                                     Instant at) {
    }

    public TransferRecovery {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(refundId, "refundId");
        Objects.requireNonNull(orgId, "orgId");
        Objects.requireNonNull(amount, "amount");
        Objects.requireNonNull(recovered, "recovered");
        Objects.requireNonNull(resolved, "resolved");
        Objects.requireNonNull(status, "status");
        amount.requireSameCurrency(recovered);
        amount.requireSameCurrency(resolved);
        if (!amount.isPositive()) {
            throw new PaymentsRuleViolation("payments.invalid-recovery", "A recovery needs a positive amount");
        }
        if (recovered.isNegative() || resolved.isNegative() || recovered.plus(resolved).greaterThan(amount)) {
            throw new PaymentsRuleViolation("payments.invalid-recovery",
                    "Recovered and resolved must stay within the amount owed");
        }
    }

    /** Opens the debt of a refused reversal. */
    public static TransferRecovery open(UUID id, UUID refundId, String orderId, String orgId, UUID transferId,
                                        String stripeTransferId, Money amount, String stripeCode, String reason,
                                        UUID journalId, Instant now) {
        Money zero = Money.zero(amount.currency());
        return new TransferRecovery(id, refundId, orderId, orgId, transferId, stripeTransferId, amount, zero, zero,
                TransferRecoveryStatus.OPEN, stripeCode, reason, null, null, null, null, null, null, journalId, now,
                now, 0);
    }

    /** Still owed: amount − recovered − resolved. */
    public Money outstanding() {
        return amount.minus(recovered).minus(resolved);
    }

    public boolean isOpen() {
        return status == TransferRecoveryStatus.OPEN;
    }

    /** {@code taken} from a later transfer of the seller (at most what is outstanding); RECOVERED once paid. */
    public TransferRecovery settledBy(Money taken, Instant now) {
        requireOpen();
        if (!taken.isPositive() || taken.greaterThan(outstanding())) {
            throw new PaymentsRuleViolation("payments.invalid-recovery-settlement", "Recovering " + taken
                    + " would overshoot what is outstanding (" + outstanding() + ")");
        }
        Money total = recovered.plus(taken);
        boolean paid = total.plus(resolved).equals(amount);
        return new TransferRecovery(id, refundId, orderId, orgId, transferId, stripeTransferId, amount, total,
                resolved, paid ? TransferRecoveryStatus.RECOVERED : status, stripeCode, reason,
                paid ? null : pending, decidedBy, decidedAt, decisionNote, resolution, resolutionRef, journalId,
                createdAt, now, version);
    }

    /** Finance proposes to close what is outstanding; a different person approves. */
    public TransferRecovery propose(RecoveryResolution kind, String why, String reference, String actor,
                                    Instant now) {
        requireOpen();
        if (pending != null) {
            throw new PaymentsStateConflict("payments.recovery-pending",
                    "A resolution of this recovery already waits for approval");
        }
        if (why == null || why.trim().length() < MIN_REASON) {
            throw new PaymentsRuleViolation("payments.reason-required",
                    "Say why (at least " + MIN_REASON + " characters)", Map.of("reason", "Required"));
        }
        String ref = reference == null || reference.isBlank() ? null : reference.trim();
        if (kind == RecoveryResolution.RECOVERED && ref == null) {
            throw new PaymentsRuleViolation("payments.recovery-reference-required",
                    "Give the bank reference of the money received", Map.of("reference", "Required"));
        }
        return new TransferRecovery(id, refundId, orderId, orgId, transferId, stripeTransferId, amount, recovered,
                resolved, status, stripeCode, reason, new ProposedResolution(kind, why.trim(), ref, actor, now),
                decidedBy, decidedAt, decisionNote, resolution, resolutionRef, journalId, createdAt, now, version);
    }

    /** The approver is never the proposer. Closes what is outstanding as proposed. */
    public TransferRecovery approve(String approver, String note, Instant now) {
        requireOpen();
        requirePending();
        if (approver.equals(pending.by())) {
            throw new PaymentsForbidden("payments.recovery-four-eyes",
                    "A different person must approve a resolution you proposed");
        }
        TransferRecoveryStatus next = pending.kind() == RecoveryResolution.WRITE_OFF
                ? TransferRecoveryStatus.WRITTEN_OFF : TransferRecoveryStatus.RECOVERED;
        return new TransferRecovery(id, refundId, orderId, orgId, transferId, stripeTransferId, amount, recovered,
                resolved.plus(outstanding()), next, stripeCode, reason, null, approver, now,
                note == null || note.isBlank() ? null : note.trim(), pending.kind(), pending.reference(), journalId,
                createdAt, now, version);
    }

    /** Anyone with the approval permission may reject, the proposer included (withdrawing it). */
    public TransferRecovery reject(String actor, String note, Instant now) {
        requireOpen();
        requirePending();
        if (note == null || note.isBlank()) {
            throw new PaymentsRuleViolation("payments.reason-required", "Say why it is rejected",
                    Map.of("note", "Required"));
        }
        return new TransferRecovery(id, refundId, orderId, orgId, transferId, stripeTransferId, amount, recovered,
                resolved, status, stripeCode, reason, null, actor, now, note.trim(), resolution, resolutionRef,
                journalId, createdAt, now, version);
    }

    private void requireOpen() {
        if (status != TransferRecoveryStatus.OPEN) {
            throw new PaymentsStateConflict("payments.recovery-closed", "This recovery is already " + status);
        }
    }

    private void requirePending() {
        if (pending == null) {
            throw new PaymentsStateConflict("payments.recovery-nothing-pending",
                    "No resolution of this recovery waits for approval");
        }
    }
}
