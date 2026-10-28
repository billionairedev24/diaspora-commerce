package com.afrotransact.payments.domain;

import java.time.Instant;
import java.util.Locale;
import java.util.UUID;

/**
 * What one side of a referral earns and whether it was granted. One per referral and side; the idempotency key
 * ({@code referral:<id>:referrer|referred}) makes the ledger grant happen once however often the job runs.
 *
 * @param entryId the credit ledger entry of a granted credit reward
 */
public record ReferralRewardGrant(UUID referralId, ReferralSide side, String customerId, ReferralReward reward,
                                  String currency, Status status, UUID entryId, Instant grantedAt,
                                  String idempotencyKey) {

    /** PENDING: not yet tried. DEFERRED: the membership program is unavailable. CAPPED: a per-customer cap. VOID. */
    public enum Status {
        PENDING,
        GRANTED,
        DEFERRED,
        CAPPED,
        VOID
    }

    public static ReferralRewardGrant pending(UUID referralId, ReferralSide side, String customerId,
                                              ReferralReward reward, String currency) {
        return new ReferralRewardGrant(referralId, side, customerId, reward, currency, Status.PENDING, null, null,
                "referral:" + referralId + ":" + side.name().toLowerCase(Locale.ROOT));
    }

    /** Whether the grant still has to be tried. */
    public boolean open() {
        return status == Status.PENDING || status == Status.DEFERRED;
    }

    public ReferralRewardGrant granted(UUID entry, Instant at) {
        return new ReferralRewardGrant(referralId, side, customerId, reward, currency, Status.GRANTED, entry, at,
                idempotencyKey);
    }

    public ReferralRewardGrant with(Status next) {
        return new ReferralRewardGrant(referralId, side, customerId, reward, currency, next, entryId, grantedAt,
                idempotencyKey);
    }
}
