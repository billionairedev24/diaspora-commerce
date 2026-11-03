package com.afrotransact.payments.application;

import java.time.Instant;
import java.util.UUID;

/**
 * The port programs use to give a customer free membership days (S323): the referral program grants days instead of
 * credit when staff configure it that way (ADR 0006 section 3). In-process callers use this interface; other services
 * use {@code POST /v1/memberships/grants} with {@code payments:credit.grant}.
 *
 * <p>A customer without a live membership gets a complimentary one for the days (no card needed, ends on its date).
 * A paid or trialling member has the days added to the period they are in, which moves their next charge back. A
 * customer who joins on a card while complimentary continues as a paid member when the free days are over.
 */
public interface MembershipGrants {

    /**
     * Grants {@code days} of the tier. Idempotent per key: a replay returns the first grant and adds nothing; the same
     * key for another customer is a conflict.
     *
     * @param tierCode a published (or retired) tier; the days are of this tier when the customer had no membership
     * @param reason   why, for the audit trail (at least 5 characters)
     */
    Grant grantDays(String customerId, String tierCode, int days, String reason, String idempotencyKey);

    /** The API entry of {@link #grantDays}: the caller must be a program service with payments:credit.grant. */
    Grant grantDaysForProgram(String customerId, String tierCode, int days, String reason, String idempotencyKey);

    /**
     * @param membershipId  the membership that received the days
     * @param replay        true when the key had been used before
     * @param benefitsUntil the end of the period the member now has
     * @param newMembership true when the grant started a complimentary membership
     */
    record Grant(UUID membershipId, String customerId, String tierCode, int days, boolean replay,
                 Instant benefitsUntil, boolean newMembership) {
    }
}
