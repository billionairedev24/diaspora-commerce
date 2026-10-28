package com.afrotransact.payments.application;

import java.time.Instant;

import com.afrotransact.payments.domain.Referral;

/**
 * Grants the rewards of one referral, once per side. Every method runs in the caller's SERIALIZABLE transaction with the
 * referral row locked, so a job run, a staff approval and a retry can never grant the same reward twice (and the credit
 * ledger's idempotency key is the second guard).
 */
interface ReferralRewarder {

    /**
     * Tries every reward of the referral that is still open, as of {@code now}, and returns the referral in its new
     * state. Nothing happens while the program is off or the return window runs. Before anything is granted the
     * qualifying order must still be worth the minimum after refunds. Per-customer caps are applied side by side.
     */
    Referral settle(Referral locked, Instant now);

    /** Rejects the referral for a reason of the system, voiding its open rewards. */
    Referral rejectBySystem(Referral locked, String reason, Instant now);

    /** Rejects the referral for staff, voiding its open rewards. */
    Referral rejectByStaff(Referral locked, String staff, String reason, Instant now);
}
