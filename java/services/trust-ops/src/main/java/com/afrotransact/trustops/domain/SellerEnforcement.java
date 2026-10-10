package com.afrotransact.trustops.domain;

import java.time.Instant;
import java.util.Map;
import java.util.Objects;

/**
 * A seller's standing and how far the upstream effects have been applied. Suspension holds payouts (seller-service)
 * and restricts the seller in the buy box (offers-service); reinstatement releases both. The step flags record what
 * upstream has confirmed, so a retried suspend / reinstate only repeats the steps still pending.
 */
public record SellerEnforcement(String orgId, EnforcementStatus status, SuspensionReason reason, String note,
                                String actorId, Instant changedAt, boolean payoutsHeld, boolean buyBoxRestricted,
                                long version) {

    public SellerEnforcement {
        Objects.requireNonNull(orgId, "orgId");
        Objects.requireNonNull(status, "status");
        Objects.requireNonNull(changedAt, "changedAt");
    }

    public static SellerEnforcement active(String orgId, Instant now) {
        return new SellerEnforcement(orgId, EnforcementStatus.ACTIVE, null, null, null, now, false, false, -1);
    }

    /** True when the row does not exist yet. */
    public boolean isNew() {
        return version < 0;
    }

    public boolean suspended() {
        return status == EnforcementStatus.SUSPENDED;
    }

    /** Suspends; suspending a suspended seller keeps the original reason (the call then only retries steps). */
    public SellerEnforcement suspend(SuspensionReason why, String text, String by, Instant now) {
        if (suspended()) {
            return this;
        }
        if (why == null) {
            throw new TrustRuleViolation("trustops.suspension-reason-required", "Pick a suspension reason",
                    Map.of("reason", "Required"));
        }
        return new SellerEnforcement(orgId, EnforcementStatus.SUSPENDED, why, Texts.required(text, "note", "Note",
                1000), by, now, payoutsHeld, buyBoxRestricted, version);
    }

    public SellerEnforcement reinstate(String text, String by, Instant now) {
        if (!suspended()) {
            return this;
        }
        return new SellerEnforcement(orgId, EnforcementStatus.ACTIVE, null, Texts.required(text, "note", "Note",
                1000), by, now, payoutsHeld, buyBoxRestricted, version);
    }

    /** Payouts should be held exactly while suspended. */
    public boolean payoutStepPending() {
        return payoutsHeld != suspended();
    }

    public boolean buyBoxStepPending() {
        return buyBoxRestricted != suspended();
    }

    public SellerEnforcement withPayoutsHeld(boolean held) {
        return new SellerEnforcement(orgId, status, reason, note, actorId, changedAt, held, buyBoxRestricted, version);
    }

    public SellerEnforcement withBuyBoxRestricted(boolean restricted) {
        return new SellerEnforcement(orgId, status, reason, note, actorId, changedAt, payoutsHeld, restricted,
                version);
    }
}
