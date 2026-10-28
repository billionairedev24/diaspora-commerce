package com.afrotransact.payments.domain;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * A seller plan invoice (Stripe Billing, owned by the seller service) paid from the organisation's earnings instead of
 * its card (S298). One per invoice. The seller's payable is debited by {@code withheld} at once (unattributed to an
 * order, like a label charge) and the organisation's next transfers recover it; a release (voided or refunded
 * invoice) credits it back.
 *
 * @param requested what the seller service asked for (the invoice amount not covered by credits)
 * @param withheld  what was available and taken: at most {@code requested}; zero when DECLINED
 * @param released  credited back so far (voided / refunded invoice), at most {@code withheld}
 * @param settled   signed: recovered from transfers minus paid back with transfers
 * @param reason    why less than requested (or nothing) was withheld
 * @param journalId the PLAN_FEE_WITHHELD journal (null when DECLINED)
 */
public record PlanFeeWithholding(UUID id, String orgId, String invoiceId, String invoiceNumber, Money requested,
                                 Money withheld, Money released, Money settled, PlanFeeWithholdingStatus status,
                                 String reason, UUID journalId, Instant createdAt, Instant updatedAt, long version) {

    public PlanFeeWithholding {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(orgId, "orgId");
        Objects.requireNonNull(invoiceId, "invoiceId");
        Objects.requireNonNull(requested, "requested");
        Objects.requireNonNull(withheld, "withheld");
        Objects.requireNonNull(released, "released");
        Objects.requireNonNull(settled, "settled");
        Objects.requireNonNull(status, "status");
        requested.requireSameCurrency(withheld);
        requested.requireSameCurrency(released);
        requested.requireSameCurrency(settled);
        if (!requested.isPositive()) {
            throw new PaymentsRuleViolation("payments.invalid-plan-fee", "A plan fee needs a positive amount");
        }
        if (withheld.isNegative() || withheld.greaterThan(requested)) {
            throw new PaymentsRuleViolation("payments.invalid-plan-fee",
                    "Withheld must be between zero and the requested amount");
        }
        if (released.isNegative() || released.greaterThan(withheld)) {
            throw new PaymentsRuleViolation("payments.invalid-plan-fee",
                    "Released must be between zero and the withheld amount");
        }
        if ((status == PlanFeeWithholdingStatus.DECLINED) != withheld.isZero()) {
            throw new PaymentsRuleViolation("payments.invalid-plan-fee",
                    "A plan fee is DECLINED exactly when nothing was withheld");
        }
    }

    /**
     * Decides how much of {@code requested} to withhold from {@code available} earnings: all of it, part of it (the
     * card pays the rest) or nothing (DECLINED).
     */
    public static PlanFeeWithholding decide(UUID id, String orgId, String invoiceId, String invoiceNumber,
                                            Money requested, Money available, UUID journalId, Instant now) {
        Money withheld = requested.min(available.max(Money.zero(requested.currency())));
        if (withheld.isZero()) {
            return new PlanFeeWithholding(id, orgId, invoiceId, invoiceNumber, requested, withheld, withheld,
                    withheld, PlanFeeWithholdingStatus.DECLINED, "No delivered, undisputed earnings to collect from",
                    null, now, now, 0);
        }
        String reason = withheld.lessThan(requested)
                ? "Only " + withheld + " of " + requested + " was available; the card pays the rest" : null;
        Money zero = Money.zero(requested.currency());
        return new PlanFeeWithholding(id, orgId, invoiceId, invoiceNumber, requested, withheld, zero, zero,
                PlanFeeWithholdingStatus.WITHHELD, reason, journalId, now, now, 0);
    }

    /** Still releasable: withheld − released. */
    public Money releasable() {
        return withheld.minus(released);
    }

    /**
     * What the organisation's transfers still have to settle: positive while the seller owes the fee (recovered from
     * the next transfer), negative when a release left money to pay back with the next transfer.
     */
    public Money outstanding() {
        return withheld.minus(released).minus(settled);
    }

    /** Credits {@code amount} back (at most what is releasable); RELEASED once nothing is left. */
    public PlanFeeWithholding release(Money amount, Instant now) {
        if (status == PlanFeeWithholdingStatus.DECLINED) {
            throw new PaymentsStateConflict("payments.plan-fee-declined", "Nothing was withheld for invoice "
                    + invoiceId);
        }
        if (!amount.isPositive() || amount.greaterThan(releasable())) {
            throw new PaymentsRuleViolation("payments.invalid-plan-fee-release", "Release between 0.01 and "
                    + releasable() + " for invoice " + invoiceId);
        }
        Money total = released.plus(amount);
        PlanFeeWithholdingStatus next = total.equals(withheld) ? PlanFeeWithholdingStatus.RELEASED : status;
        return new PlanFeeWithholding(id, orgId, invoiceId, invoiceNumber, requested, withheld, total, settled, next,
                reason, journalId, createdAt, now, version);
    }

    /** {@code amount} (signed) settled by a transfer: positive recovered from it, negative paid back with it. */
    public PlanFeeWithholding settledBy(Money amount, Instant now) {
        Money open = outstanding();
        boolean sameSide = amount.isPositive() ? open.isPositive() : amount.isNegative() && open.isNegative();
        if (!sameSide || amount.abs().greaterThan(open.abs())) {
            throw new PaymentsRuleViolation("payments.invalid-plan-fee-settlement", "Settling " + amount
                    + " would overshoot what invoice " + invoiceId + " has outstanding (" + open + ")");
        }
        Money next = settled.plus(amount);
        return new PlanFeeWithholding(id, orgId, invoiceId, invoiceNumber, requested, withheld, released, next,
                status, reason, journalId, createdAt, now, version);
    }
}
