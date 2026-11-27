package com.afrotransact.seller.domain;

import java.time.Instant;
import java.util.UUID;

/**
 * A dispute (card chargeback or ACH unauthorised return) on a plan fee. Staff see the open ones; LOST reopens the
 * amount on the invoice and restarts dunning, WON just closes it.
 *
 * @param stripeStatus Stripe's status (warning_needs_response, needs_response, under_review, won, lost, ...)
 * @param outcome      OPEN, WON or LOST
 */
public record PlanFeeDispute(UUID id, String stripeDisputeId, String invoiceId, String orgId, String paymentIntentId,
                             String chargeId, long amountCents, String reason, String stripeStatus, String outcome,
                             boolean fundsWithdrawn, Instant createdAt, Instant updatedAt, Instant closedAt) {

    public static final String OPEN = "OPEN";
    public static final String WON = "WON";
    public static final String LOST = "LOST";

    public boolean isOpen() {
        return OPEN.equals(outcome);
    }

    /** Stripe's final statuses: won (also warning_closed, an inquiry that closed) and lost. */
    public static String outcomeOf(String stripeStatus) {
        if ("won".equals(stripeStatus) || "warning_closed".equals(stripeStatus)) {
            return WON;
        }
        return "lost".equals(stripeStatus) ? LOST : OPEN;
    }
}
