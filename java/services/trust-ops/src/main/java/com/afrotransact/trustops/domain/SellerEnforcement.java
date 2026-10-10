package com.afrotransact.trustops.domain;

import java.time.Instant;
import java.util.Map;
import java.util.Objects;

/**
 * A seller's standing and how far the upstream effects have been applied. Suspension places seller-service's
 * suspension hold (payouts paused, listings hidden in catalog, offers, bookings and search) and restricts the seller in
 * the buy box (offers-service); reinstatement lifts both. The step flags record what upstream has confirmed, so a
 * retried suspend / reinstate only repeats the steps still pending.
 *
 * @param sellerSuspended seller-service holds its suspension hold for the organisation (payouts paused and listings
 *                        hidden by it; Stripe compliance holds are separate and may keep payouts paused after
 *                        reinstatement)
 */
public record SellerEnforcement(String orgId, EnforcementStatus status, SuspensionReason reason, String note,
                                String actorId, Instant changedAt, boolean sellerSuspended, boolean buyBoxRestricted,
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
                1000), by, now, sellerSuspended, buyBoxRestricted, version);
    }

    public SellerEnforcement reinstate(String text, String by, Instant now) {
        if (!suspended()) {
            return this;
        }
        return new SellerEnforcement(orgId, EnforcementStatus.ACTIVE, null, Texts.required(text, "note", "Note",
                1000), by, now, sellerSuspended, buyBoxRestricted, version);
    }

    /** seller-service should hold its suspension hold exactly while suspended. */
    public boolean sellerStepPending() {
        return sellerSuspended != suspended();
    }

    public boolean buyBoxStepPending() {
        return buyBoxRestricted != suspended();
    }

    public SellerEnforcement withSellerSuspended(boolean held) {
        return new SellerEnforcement(orgId, status, reason, note, actorId, changedAt, held, buyBoxRestricted, version);
    }

    public SellerEnforcement withBuyBoxRestricted(boolean restricted) {
        return new SellerEnforcement(orgId, status, reason, note, actorId, changedAt, sellerSuspended, restricted,
                version);
    }
}
