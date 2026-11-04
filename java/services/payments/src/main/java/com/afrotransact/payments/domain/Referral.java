package com.afrotransact.payments.domain;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * One friend referred by one customer, from sign-up to rewards. Immutable: every transition returns the next state.
 *
 * @param referredName        first name and last initial of the friend, as the referrer sees them
 * @param qualifyingOrderId   the first order of the friend that was delivered with at least the minimum value
 * @param eligibleAt          when the rewards are due (delivery plus the return window)
 * @param decision            how a flagged or rejected referral was decided; null before
 * @param version             optimistic lock
 */
public record Referral(UUID id, String referrerId, String referredId, String code, String referredName,
                       ReferralStatus status, List<ReferralFlag> flags, Instant signedUpAt, Instant expiresAt,
                       String qualifyingOrderId, Long qualifyingMinor, Instant deliveredAt, Instant eligibleAt,
                       Decision decision, String decisionReason, String decidedBy, Instant decidedAt,
                       Instant createdAt, Instant updatedAt, long version) {

    /** How a referral was decided: staff approved or rejected it, or the system rejected it (cap, refund). */
    public enum Decision {
        APPROVED,
        REJECTED,
        SYSTEM
    }

    public Referral {
        flags = List.copyOf(flags);
        if (referrerId.equals(referredId)) {
            throw new PaymentsRuleViolation("payments.referral-self", "A customer cannot refer themselves");
        }
    }

    /** A friend signed up with a code. */
    public static Referral signedUp(UUID id, String referrerId, String referredId, String code, String referredName,
                                    List<ReferralFlag> flags, Instant signedUpAt, int validityDays, Instant now) {
        return new Referral(id, referrerId, referredId, code, referredName, ReferralStatus.PENDING, flags, signedUpAt,
                signedUpAt.plusSeconds(validityDays * 86_400L), null, null, null, null, null, null, null, null, now,
                now, 0);
    }

    /**
     * The friend's order qualified. Abuse checks that found something hold it for staff; otherwise the return window
     * runs.
     */
    public Referral qualified(String orderId, long deliveredMinor, Instant delivered, int returnWindowDays,
                              List<ReferralFlag> found, Instant now) {
        requireStatus(ReferralStatus.PENDING);
        List<ReferralFlag> all = merge(flags, found);
        return copy(all.isEmpty() ? ReferralStatus.WAITING : ReferralStatus.IN_REVIEW, all, orderId, deliveredMinor,
                delivered, delivered.plusSeconds(returnWindowDays * 86_400L), decision, decisionReason, decidedBy,
                decidedAt, now);
    }

    /** Staff cleared a flagged referral: it waits for the return window like any other. */
    public Referral approved(String by, String reason, Instant now) {
        requireStatus(ReferralStatus.IN_REVIEW);
        return copy(ReferralStatus.WAITING, flags, qualifyingOrderId, qualifyingMinor, deliveredAt, eligibleAt,
                Decision.APPROVED, reason, by, now, now);
    }

    /** Staff reject a flagged (or waiting) referral: no reward is granted. */
    public Referral rejectedByStaff(String by, String reason, Instant now) {
        if (status != ReferralStatus.IN_REVIEW && status != ReferralStatus.WAITING) {
            throw new PaymentsStateConflict("payments.referral-not-reviewable",
                    "Only referrals in review or waiting for their rewards can be rejected");
        }
        return copy(ReferralStatus.REJECTED, flags, qualifyingOrderId, qualifyingMinor, deliveredAt, eligibleAt,
                Decision.REJECTED, reason, by, now, now);
    }

    /** The system ends it with a reason (cap reached, the order was refunded). */
    public Referral rejectedBySystem(String reason, Instant now) {
        return copy(ReferralStatus.REJECTED, flags, qualifyingOrderId, qualifyingMinor, deliveredAt, eligibleAt,
                Decision.SYSTEM, reason, "system", now, now);
    }

    public Referral expired(Instant now) {
        requireStatus(ReferralStatus.PENDING);
        return copy(ReferralStatus.EXPIRED, flags, qualifyingOrderId, qualifyingMinor, deliveredAt, eligibleAt,
                decision, decisionReason, decidedBy, decidedAt, now);
    }

    /** After the rewards were tried: rewarded when every side is settled, partially when one still waits. */
    public Referral settled(ReferralStatus next, Instant now) {
        return copy(next, flags, qualifyingOrderId, qualifyingMinor, deliveredAt, eligibleAt, decision, decisionReason,
                decidedBy, decidedAt, now);
    }

    public Referral withFlags(List<ReferralFlag> next, Instant now) {
        return copy(status, merge(flags, next), qualifyingOrderId, qualifyingMinor, deliveredAt, eligibleAt, decision,
                decisionReason, decidedBy, decidedAt, now);
    }

    /** Whether the rewards can be granted at {@code now}. */
    public boolean due(Instant now) {
        return (status == ReferralStatus.WAITING || status == ReferralStatus.PARTIALLY_REWARDED)
                && eligibleAt != null && !eligibleAt.isAfter(now);
    }

    private void requireStatus(ReferralStatus expected) {
        if (status != expected) {
            throw new PaymentsStateConflict("payments.referral-state", "The referral is " + status + ", not "
                    + expected);
        }
    }

    private static List<ReferralFlag> merge(List<ReferralFlag> a, List<ReferralFlag> b) {
        List<ReferralFlag> out = new ArrayList<>(a);
        for (ReferralFlag f : b) {
            if (!out.contains(f)) {
                out.add(f);
            }
        }
        return out;
    }

    @SuppressWarnings("checkstyle:ParameterNumber")
    private Referral copy(ReferralStatus nextStatus, List<ReferralFlag> nextFlags, String orderId, Long minor,
                          Instant delivered, Instant eligible, Decision nextDecision, String reason, String by,
                          Instant decided, Instant now) {
        return new Referral(id, referrerId, referredId, code, referredName, nextStatus, nextFlags, signedUpAt,
                expiresAt, orderId, minor, delivered, eligible, nextDecision, reason, by, decided, createdAt, now,
                version);
    }
}
