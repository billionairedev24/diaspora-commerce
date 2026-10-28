package com.afrotransact.payments.port;

/**
 * Grants free membership days to a customer (the membership program, S323). The referral program only models the
 * reward type and asks through this port; {@code MembershipProgramGrants} is the adapter.
 *
 * <p>When the program cannot take a grant (no published tier), membership rewards stay DEFERRED and are retried by the
 * reward job, so nothing is lost and nothing is granted twice.
 */
public interface MembershipGrants {

    /**
     * Grants {@code days} of membership, once per {@code idempotencyKey}.
     *
     * @return GRANTED, or UNAVAILABLE when the membership program cannot take it yet (try again later)
     */
    Outcome grant(String customerId, int days, String reason, String idempotencyKey);

    enum Outcome {
        GRANTED,
        UNAVAILABLE
    }
}
