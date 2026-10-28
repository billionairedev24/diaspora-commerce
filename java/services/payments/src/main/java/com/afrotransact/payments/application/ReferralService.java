package com.afrotransact.payments.application;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.afrotransact.payments.domain.Referral;
import com.afrotransact.payments.domain.ReferralRewardGrant;
import com.afrotransact.payments.domain.ReferralSettings;
import com.afrotransact.payments.domain.ReferralStatus;
import com.afrotransact.payments.port.ReferralRepository;

/**
 * The referral program as customers and staff use it (S322, ADR 0006). Referral is the platform's responsibility:
 * AfroTransact funds the rewards, granted as credit through the credit ledger (never cash) or as free membership days.
 *
 * <p>Lives in payments because the reward is a ledger grant: the grant, its program-cost booking and the once-only
 * guard (one idempotency key per referral and side) are one local transaction, with no second service to call, retry
 * or reconcile. The events it needs (sign-up, order placed, shipment delivered) arrive from identity and orders.
 */
public interface ReferralService {

    /**
     * The signed-in customer's code and what the program offers (customer). The code is created on first use and never
     * changes.
     */
    Overview myReferral();

    /** The friends the signed-in customer referred, newest first, with what they earned from each (customer). */
    List<Entry> myReferrals(int limit);

    /** Referrals for staff (payments:read), newest first; {@code customerId} matches either side. */
    List<Entry> list(ReferralStatus status, String customerId, int limit);

    /** One referral with its rewards (payments:read). */
    Entry get(UUID id);

    /** What the program has cost and how many referrals are in each status (payments:read). */
    Summary summary();

    /**
     * Clears a flagged referral (ledger:adjust, with a reason of at least 15 characters): it then waits for the return
     * window like any other, and is granted at once if that has passed.
     */
    Entry approve(UUID id, String reason);

    /** Rejects a flagged or waiting referral (ledger:adjust, with a reason): no reward is granted. */
    Entry reject(UUID id, String reason);

    /**
     * @param code         the customer's code in storage form (8 characters)
     * @param displayCode  the same as {@code XXXX-XXXX}
     * @param program      the offer in force
     * @param asFriend     the customer's own referral when someone referred them
     */
    record Overview(String code, String displayCode, ReferralSettings program, String currency,
                    ReferralRepository.Earnings earnings, Optional<Entry> asFriend) {
    }

    /** A referral with its rewards (both sides; the customer's view shows only what concerns them). */
    record Entry(Referral referral, List<ReferralRewardGrant> rewards) {
    }

    record Summary(ReferralRepository.Totals totals, ReferralSettings settings, String currency) {
    }
}
