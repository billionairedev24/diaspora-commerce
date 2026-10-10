package com.afrotransact.payments.domain;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Builds the double-entry journal of every money movement (architecture §5 Ledger, §17b). */
public final class Postings {

    private Postings() {
    }

    /**
     * Capture of a charge: Dr 1000 Stripe clearing (charge − fee) and Dr 5000 Stripe fees (AfroTransact's share) /
     * Cr 2000 seller payable (net), Cr 2100 sales tax payable per jurisdiction, Cr 4000 commission, Cr 4100 FBA fees,
     * Cr 4200 AfroTransact shipping. Sellers bear their fee share through a lower payable.
     */
    public static Journal capture(UUID id, Instant at, String key, String orderId, String paymentId,
                                  String chargeId, PaymentSplit split) {
        Journal.Builder b = Journal.builder(JournalType.CAPTURE, key).order(orderId).payment(paymentId)
                .stripeRef(chargeId).description("Capture of order " + orderId);
        RecipientShare platform = split.platform();
        b.debit(LedgerAccount.of(Chart.STRIPE_CLEARING), split.charge().minus(split.stripeFee()));
        b.debit(LedgerAccount.of(Chart.STRIPE_FEES), platform.stripeFee());
        for (RecipientShare s : split.sellers()) {
            b.credit(LedgerAccount.sellerPayable(s.recipientId()), s.net(), s.recipientId());
            for (Map.Entry<String, Money> tax : s.taxByJurisdiction().entrySet()) {
                b.credit(LedgerAccount.salesTax(tax.getKey()), tax.getValue(), s.recipientId());
            }
            b.credit(LedgerAccount.of(Chart.COMMISSION_REVENUE), s.commission(), s.recipientId());
            b.credit(LedgerAccount.of(Chart.FBA_FEE_REVENUE), s.fbaFees(), s.recipientId());
        }
        b.credit(LedgerAccount.of(Chart.SHIPPING_REVENUE), platform.shipping());
        return b.build(id, at);
    }

    /**
     * Refund: Dr 2100 tax, Dr 2000 seller payable (items + own shipping − commission returned), Dr 4000 commission
     * returned, Dr 4200 AfroTransact shipping / Cr 2200 customer refunds payable. FBA fees and Stripe fee shares
     * are not given back.
     */
    public static Journal refund(UUID id, Instant at, String key, String orderId, String paymentId, String refundRef,
                                 List<RefundLine> lines) {
        Journal.Builder b = Journal.builder(JournalType.REFUND, key).order(orderId).payment(paymentId)
                .stripeRef(refundRef).description("Refund on order " + orderId);
        Money total = null;
        for (RefundLine l : lines) {
            total = total == null ? l.total() : total.plus(l.total());
            b.debit(LedgerAccount.salesTax(l.taxJurisdiction()), l.tax(), l.sellerOrg());
            b.debit(LedgerAccount.sellerPayable(l.sellerOrg()), l.sellerDebit(), l.sellerOrg());
            b.debit(LedgerAccount.of(Chart.COMMISSION_REVENUE), l.commissionReversed(), l.sellerOrg());
            if (l.shippingToPlatform()) {
                b.debit(LedgerAccount.of(Chart.SHIPPING_REVENUE), l.shipping());
            }
        }
        if (total == null) {
            throw new PaymentsRuleViolation("payments.invalid-refund", "A refund needs at least one line");
        }
        b.credit(LedgerAccount.of(Chart.CUSTOMER_REFUNDS_PAYABLE), total);
        return b.build(id, at);
    }

    /** Stripe paid the refund out of the platform balance: Dr 2200 / Cr 1000. */
    public static Journal refundSettled(UUID id, Instant at, String key, Refund refund) {
        return Journal.builder(JournalType.REFUND_SETTLED, key).order(refund.orderId())
                .payment(refund.paymentId().toString()).stripeRef(refund.stripeRefundId())
                .description("Refund " + refund.stripeRefundId() + " paid by Stripe")
                .debit(LedgerAccount.of(Chart.CUSTOMER_REFUNDS_PAYABLE), refund.amount())
                .credit(LedgerAccount.of(Chart.STRIPE_CLEARING), refund.amount())
                .build(id, at);
    }

    /** Settlement transfer to a connected account: Dr 2000 seller payable / Cr 1000 Stripe clearing. */
    public static Journal transfer(UUID id, Instant at, String key, String orderId, String orgId, Money amount,
                                   String stripeTransferId) {
        return Journal.builder(JournalType.TRANSFER, key).order(orderId).stripeRef(stripeTransferId)
                .description("Transfer to " + orgId + " for order " + orderId)
                .debit(LedgerAccount.sellerPayable(orgId), amount, orgId)
                .credit(LedgerAccount.of(Chart.STRIPE_CLEARING), amount)
                .build(id, at);
    }

    /** Transfer (partly) reversed back to the platform: Dr 1000 Stripe clearing / Cr 2000 seller payable. */
    public static Journal transferReversal(UUID id, Instant at, String key, String orderId, String orgId,
                                           Money amount, String stripeReversalRef) {
        return Journal.builder(JournalType.TRANSFER_REVERSAL, key).order(orderId).stripeRef(stripeReversalRef)
                .description("Transfer reversal from " + orgId + " for order " + orderId)
                .debit(LedgerAccount.of(Chart.STRIPE_CLEARING), amount)
                .credit(LedgerAccount.sellerPayable(orgId), amount, orgId)
                .build(id, at);
    }

    /**
     * Late provider-cancellation fee accrued: Dr 1200 provider fees receivable (per organisation) / Cr 4300 provider
     * fee revenue. No money moves yet, so the platform clearing account (and reconciliation) is untouched.
     */
    public static Journal providerFee(UUID id, Instant at, String key, ProviderFee fee) {
        return Journal.builder(JournalType.PROVIDER_FEE, key).order(fee.bookingReference())
                .description("Late cancellation fee of " + fee.orgId() + " for booking " + fee.bookingReference())
                .debit(LedgerAccount.providerFeesReceivable(fee.orgId()), fee.amount(), fee.orgId())
                .credit(LedgerAccount.of(Chart.PROVIDER_FEE_REVENUE), fee.amount(), fee.orgId())
                .build(id, at);
    }

    /**
     * Fee collected with a Stripe account debit of the provider's connected account: the platform balance grows
     * (Dr 1000 Stripe clearing, matched to the debit's balance transaction by reconciliation) / Cr 1200.
     */
    public static Journal providerFeeCollected(UUID id, Instant at, String key, ProviderFee fee,
                                               String stripeDebitId) {
        return Journal.builder(JournalType.PROVIDER_FEE_COLLECTED, key).order(fee.bookingReference())
                .stripeRef(stripeDebitId)
                .description("Late cancellation fee debited from " + fee.orgId() + "'s connected account")
                .debit(LedgerAccount.of(Chart.STRIPE_CLEARING), fee.amount())
                .credit(LedgerAccount.providerFeesReceivable(fee.orgId()), fee.amount(), fee.orgId())
                .build(id, at);
    }

    /**
     * Dispute opened: Stripe withdraws the amount (Dr 1100 chargeback receivable / Cr 1000) and each responsible
     * seller's payable is debited into a chargeback reserve (Dr 2000 / Cr 2300) until the outcome. Stripe's dispute fee
     * is AfroTransact's cost (Dr 5100).
     */
    public static Journal disputeOpened(UUID id, Instant at, String key, Dispute d) {
        Journal.Builder b = Journal.builder(JournalType.DISPUTE_OPENED, key).order(d.orderId())
                .payment(d.paymentId().toString()).stripeRef(d.id())
                .description("Chargeback " + d.id() + " opened (" + d.reason() + ")")
                .debit(LedgerAccount.of(Chart.CHARGEBACK_RECEIVABLE), d.amount())
                .debit(LedgerAccount.of(Chart.CHARGEBACK_LOSSES), d.fee())
                .credit(LedgerAccount.of(Chart.STRIPE_CLEARING), d.amount().plus(d.fee()));
        for (DisputeAllocation a : d.sellerAllocations()) {
            b.debit(LedgerAccount.sellerPayable(a.recipientId()), a.amount(), a.recipientId());
            b.credit(LedgerAccount.chargebackReserve(a.recipientId()), a.amount(), a.recipientId());
        }
        return b.build(id, at);
    }

    /** Won: Stripe reinstates the funds (Dr 1000 / Cr 1100) and the reserve goes back to the sellers (Dr 2300 / Cr 2000). */
    public static Journal disputeWon(UUID id, Instant at, String key, Dispute d) {
        Journal.Builder b = Journal.builder(JournalType.DISPUTE_WON, key).order(d.orderId())
                .payment(d.paymentId().toString()).stripeRef(d.id())
                .description("Chargeback " + d.id() + " won")
                .debit(LedgerAccount.of(Chart.STRIPE_CLEARING), d.amount())
                .credit(LedgerAccount.of(Chart.CHARGEBACK_RECEIVABLE), d.amount());
        for (DisputeAllocation a : d.sellerAllocations()) {
            b.debit(LedgerAccount.chargebackReserve(a.recipientId()), a.amount(), a.recipientId());
            b.credit(LedgerAccount.sellerPayable(a.recipientId()), a.amount(), a.recipientId());
        }
        return b.build(id, at);
    }

    /** Lost: the sellers' reserve covers the receivable (Dr 2300 / Cr 1100); AfroTransact's part is a loss (Dr 5100). */
    public static Journal disputeLost(UUID id, Instant at, String key, Dispute d) {
        Journal.Builder b = Journal.builder(JournalType.DISPUTE_LOST, key).order(d.orderId())
                .payment(d.paymentId().toString()).stripeRef(d.id() + ":lost")
                .description("Chargeback " + d.id() + " lost");
        for (DisputeAllocation a : d.allocations()) {
            if (PaymentSplitter.PLATFORM.equals(a.recipientId())) {
                b.debit(LedgerAccount.of(Chart.CHARGEBACK_LOSSES), a.amount());
            } else {
                b.debit(LedgerAccount.chargebackReserve(a.recipientId()), a.amount(), a.recipientId());
            }
        }
        b.credit(LedgerAccount.of(Chart.CHARGEBACK_RECEIVABLE), d.amount());
        return b.build(id, at);
    }
}
