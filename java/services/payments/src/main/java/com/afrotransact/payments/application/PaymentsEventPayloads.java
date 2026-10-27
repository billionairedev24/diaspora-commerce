package com.afrotransact.payments.application;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import com.afrotransact.payments.domain.Capture;
import com.afrotransact.payments.domain.Dispute;
import com.afrotransact.payments.domain.Payment;
import com.afrotransact.payments.domain.PaymentLine;
import com.afrotransact.payments.domain.PaymentSplit;
import com.afrotransact.payments.domain.PayoutRequest;
import com.afrotransact.payments.domain.ProviderFee;
import com.afrotransact.payments.domain.Refund;
import com.afrotransact.payments.domain.SettlementTransfer;
import com.afrotransact.payments.domain.SupplierPayment;

/** JSON payloads of payments events (schemas in contracts/asyncapi/fragments/payments.yaml). */
public final class PaymentsEventPayloads {

    private PaymentsEventPayloads() {
    }

    /**
     * payments.intent.*. {@code parentPaymentId} is set for off-session follow-up charges (same order id), so
     * consumers can tell them from the order's primary payment.
     */
    public record IntentPayload(UUID paymentId, String orderId, String stripePaymentIntentId, String status,
                                long amountMinor, long capturedMinor, long refundedMinor, String currency,
                                String failureMessage, UUID parentPaymentId, boolean offSession) {

        static IntentPayload of(Payment p) {
            return new IntentPayload(p.id(), p.orderId(), p.stripePaymentIntentId(), p.status().name(),
                    p.amount().minor(), p.captured().minor(), p.refunded().minor(), p.currency(),
                    p.failureMessage(), p.parentPaymentId(), p.isOffSession());
        }
    }

    /** payments.authorization.released. */
    public record ReleasedPayload(UUID paymentId, String orderId, String stripePaymentIntentId, String status,
                                  long amountMinor, long capturedMinor, long releasedMinor, String currency,
                                  String reason, UUID parentPaymentId) {

        static ReleasedPayload of(Payment p, String reason) {
            return new ReleasedPayload(p.id(), p.orderId(), p.stripePaymentIntentId(), p.status().name(),
                    p.amount().minor(), p.captured().minor(), p.released().minor(), p.currency(), reason,
                    p.parentPaymentId());
        }
    }

    /**
     * payments.intent.action_required: notifications asks the customer to confirm the payment in the app; the
     * calling service reads the client secret with GET /v1/payments/{paymentId}.
     */
    public record ActionRequiredPayload(UUID paymentId, String orderId, UUID parentPaymentId, String customerId,
                                        String stripePaymentIntentId, long amountMinor, String currency,
                                        String reason, List<String> lineIds) {

        static ActionRequiredPayload of(Payment p, String customerId, String reason) {
            return new ActionRequiredPayload(p.id(), p.orderId(), p.parentPaymentId(), customerId,
                    p.stripePaymentIntentId(), p.amount().minor(), p.currency(), reason,
                    p.lines().stream().map(PaymentLine::lineId).toList());
        }
    }

    /** payments.provider_fee.*. */
    public record ProviderFeePayload(UUID feeId, String orgId, String bookingId, String bookingReference,
                                     long amountMinor, String currency, String reason, String status,
                                     String stripeDebitId, String lastError) {

        static ProviderFeePayload of(ProviderFee f) {
            return new ProviderFeePayload(f.id(), f.orgId(), f.bookingId(), f.bookingReference(), f.amount().minor(),
                    f.amount().currency(), f.reason(), f.status().name(), f.stripeDebitId(), f.lastError());
        }
    }

    /** payments.supplier_payment.scheduled / paid / updated (S226). */
    public record SupplierPaymentPayload(UUID supplierPaymentId, UUID poId, String poNumber, UUID supplierId,
                                         String supplierCode, long amountMinor, String currency, String status,
                                         LocalDate dueOn, String paymentReference, String holdReason,
                                         Instant paidAt) {

        static SupplierPaymentPayload of(SupplierPayment p) {
            return new SupplierPaymentPayload(p.id(), p.poId(), p.poNumber(), p.supplierId(), p.supplierCode(),
                    p.amount().minor(), p.amount().currency(), p.status().name(), p.dueOn(), p.paymentReference(),
                    p.holdReason(), p.paidAt());
        }
    }

    /** payments.captured. */
    public record CapturedPayload(UUID paymentId, String orderId, UUID captureId, String shipmentId,
                                  long amountMinor, long stripeFeeMinor, boolean feeEstimated, String currency,
                                  String stripeChargeId, boolean finalCapture, UUID journalId,
                                  List<RecipientNet> recipients) {

        static CapturedPayload of(Payment p, Capture c, PaymentSplit split) {
            return new CapturedPayload(p.id(), p.orderId(), c.id(), c.shipmentId(), c.amount().minor(),
                    c.stripeFee().minor(), c.feeEstimated(), p.currency(), c.stripeChargeId(), c.finalCapture(),
                    c.journalId(), split.recipients().stream().map(r -> new RecipientNet(r.recipientId(),
                            r.kind().name(), r.net().minor(), r.commission().minor(), r.tax().minor(),
                            r.stripeFee().minor())).toList());
        }
    }

    /** Net of one recipient in a capture. */
    public record RecipientNet(String recipientId, String kind, long netMinor, long commissionMinor, long taxMinor,
                               long stripeFeeMinor) {
    }

    /** payments.refunded. */
    public record RefundedPayload(UUID paymentId, String orderId, UUID refundId, String stripeRefundId,
                                  long amountMinor, String currency, String status, String reason,
                                  List<RefundedLine> lines, List<RefundResult.Reversal> reversals) {

        static RefundedPayload of(Refund r, List<RefundResult.Reversal> reversals) {
            return new RefundedPayload(r.paymentId(), r.orderId(), r.id(), r.stripeRefundId(), r.amount().minor(),
                    r.amount().currency(), r.status().name(), r.reason(), r.lines().stream()
                    .map(l -> new RefundedLine(l.lineId(), l.sellerOrg(), l.items().minor(), l.shipping().minor(),
                            l.tax().minor(), l.commissionReversed().minor())).toList(), reversals);
        }
    }

    /** One refunded line. */
    public record RefundedLine(String lineId, String sellerOrg, long itemsMinor, long shippingMinor, long taxMinor,
                               long commissionReversedMinor) {
    }

    /** payments.transfer.created. */
    public record TransferPayload(String orderId, String orgId, UUID transferId, String stripeTransferId,
                                  long amountMinor, String currency, String transferGroup) {

        static TransferPayload of(SettlementTransfer t) {
            return new TransferPayload(t.orderId(), t.orgId(), t.id(), t.stripeTransferId(), t.amount().minor(),
                    t.amount().currency(), t.orderId());
        }
    }

    /** payments.payout.*. */
    public record PayoutPayload(UUID payoutRequestId, String orgId, long amountMinor, String currency, String status,
                                String stripePayoutId, String failureMessage, Instant requestedAt) {

        static PayoutPayload of(PayoutRequest r) {
            return new PayoutPayload(r.id(), r.orgId(), r.amount().minor(), r.amount().currency(), r.status().name(),
                    r.stripePayoutId(), r.failureMessage(), r.requestedAt());
        }
    }

    /** payments.dispute.*: the responsible sellers are notified from it. */
    public record DisputePayload(String disputeId, UUID paymentId, String orderId, String chargeId, long amountMinor,
                                 long feeMinor, String currency, String status, String reason,
                                 List<Allocation> allocations) {

        static DisputePayload of(Dispute d) {
            return new DisputePayload(d.id(), d.paymentId(), d.orderId(), d.chargeId(), d.amount().minor(),
                    d.fee().minor(), d.amount().currency(), d.status().name(), d.reason(), d.allocations().stream()
                    .map(a -> new Allocation(a.recipientId(), a.amount().minor())).toList());
        }
    }

    /** Part of a disputed amount borne by a recipient. */
    public record Allocation(String recipientId, long amountMinor) {
    }

    /** Email address of a gift card recipient (notifications' recipient block). */
    public record GiftCardRecipient(String email) {
    }

    /**
     * payments.gift_card.deliver: the email that carries the code. The code appears here and nowhere else at rest (the
     * database keeps only its hash); a resend makes a new one.
     */
    public record GiftCardDeliveryPayload(GiftCardRecipient recipient, String locale, String giftCardId,
                                          String recipientName, String senderName, String message, long amountMinor,
                                          String currency, String code) {
    }

    /**
     * payments.gift_card.issued / .redeemed / .voided / .resent and payments.credit.adjusted: the audit trail of staff
     * actions and redemptions (no code). {@code actor} is the staff member or customer.
     */
    public record GiftCardAuditPayload(String giftCardId, String action, String actor, String reason,
                                       long amountMinor, String currency, String status) {
    }

    /** payments.credit.adjusted: a staff grant or correction of credit posted to a customer. */
    public record CreditAdjustedPayload(String adjustmentId, String customerId, long amountMinor, String currency,
                                        String source, String reason, String proposedBy, String decidedBy) {
    }
}
