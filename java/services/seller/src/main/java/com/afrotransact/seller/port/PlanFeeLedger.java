package com.afrotransact.seller.port;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.afrotransact.seller.domain.PlanFeeDispute;
import com.afrotransact.seller.domain.PlanFeeRefund;

/** Refunds and disputes of plan fees (invoice_refund, invoice_dispute). */
public interface PlanFeeLedger {

    /** False when a refund with the same idempotency key exists (a retried request). */
    boolean addRefund(PlanFeeRefund refund);

    Optional<PlanFeeRefund> refundByKey(String idempotencyKey);

    List<PlanFeeRefund> refunds(String invoiceId);

    /** Refunds of the PaymentIntent, oldest first. */
    List<PlanFeeRefund> refundsOfIntent(String paymentIntentId);

    void setRefund(UUID id, String status, String stripeRefundId, Instant at);

    /** False when the dispute was recorded before. */
    boolean addDispute(PlanFeeDispute dispute);

    Optional<PlanFeeDispute> lockDispute(String stripeDisputeId);

    void updateDispute(String stripeDisputeId, String stripeStatus, String outcome, boolean fundsWithdrawn,
                       Instant closedAt, Instant at);

    List<PlanFeeDispute> disputes(String invoiceId);

    /** Disputes for staff: {@code outcome} OPEN / WON / LOST, or null for all; newest first. */
    List<PlanFeeDispute> disputesByOutcome(String outcome, int limit);
}
