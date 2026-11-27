package com.afrotransact.seller.application;

import java.time.Instant;

/**
 * seller.billing.dispute_opened / dispute_closed / refunded: a dispute or refund on a plan fee.
 *
 * @param kind    DISPUTE or REFUND
 * @param status  dispute: OPEN, WON or LOST; refund: PENDING, SUCCEEDED or FAILED
 * @param origin  refund: STAFF or DASHBOARD; dispute: Stripe's reason
 */
public record PlanFeeAdjustmentPayload(String orgId, String invoiceId, String kind, String reference,
                                       long amountCents, String status, String origin, Instant occurredAt) {
}
