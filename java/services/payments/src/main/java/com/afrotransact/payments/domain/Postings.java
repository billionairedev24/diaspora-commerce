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
     * Cr 4200 AfroTransact shipping, Cr 4250 local delivery fees (AfroTransact Delivery from seller stores). Sellers
     * bear their fee share through a lower payable. AfroTransact Retail's net
     * (items + shipping − its fee share) is AfroTransact revenue: Cr 4400 Retail sales, never 2000.
     */
    public static Journal capture(UUID id, Instant at, String key, String orderId, String paymentId,
                                  String chargeId, PaymentSplit split) {
        Journal.Builder b = Journal.builder(JournalType.CAPTURE, key).order(orderId).payment(paymentId)
                .stripeRef(chargeId).description("Capture of order " + orderId);
        RecipientShare platform = split.platform();
        b.debit(LedgerAccount.of(Chart.STRIPE_CLEARING), split.charge().minus(split.stripeFee()));
        b.debit(LedgerAccount.of(Chart.STRIPE_FEES), platform.stripeFee());
        for (RecipientShare s : split.sellers()) {
            LedgerAccount owner = s.isRetail() ? LedgerAccount.retailSales()
                    : LedgerAccount.sellerPayable(s.recipientId());
            b.credit(owner, s.net(), s.recipientId());
            for (Map.Entry<String, Money> tax : s.taxByJurisdiction().entrySet()) {
                b.credit(LedgerAccount.salesTax(tax.getKey()), tax.getValue(), s.recipientId());
            }
            b.credit(LedgerAccount.of(Chart.COMMISSION_REVENUE), s.commission(), s.recipientId());
            b.credit(LedgerAccount.of(Chart.FBA_FEE_REVENUE), s.fbaFees(), s.recipientId());
            b.credit(LedgerAccount.of(Chart.LOCAL_DELIVERY_FEE_REVENUE), s.deliveryFees(), s.recipientId());
        }
        b.credit(LedgerAccount.of(Chart.SHIPPING_REVENUE), platform.shipping());
        return b.build(id, at);
    }

    /**
     * Refund: Dr 2100 tax, Dr 2000 seller payable (items + own shipping − commission returned), Dr 4000 commission
     * returned, Dr 4200 AfroTransact shipping, Dr 4250 delivery fee returned (customer-fault refunds of AfroTransact
     * Delivery lines) / Cr 2200 customer refunds payable. FBA fees and Stripe fee shares are not given back. Lines of AfroTransact Retail ({@code retailOrgId}) debit 4400 Retail sales instead of a
     * payable (Retail paid no commission, so none is returned).
     */
    public static Journal refund(UUID id, Instant at, String key, String orderId, String paymentId, String refundRef,
                                 List<RefundLine> lines, String retailOrgId) {
        Journal.Builder b = Journal.builder(JournalType.REFUND, key).order(orderId).payment(paymentId)
                .stripeRef(refundRef).description("Refund on order " + orderId);
        Money total = null;
        for (RefundLine l : lines) {
            total = total == null ? l.total() : total.plus(l.total());
            b.debit(LedgerAccount.salesTax(l.taxJurisdiction()), l.tax(), l.sellerOrg());
            b.debit(owner(l.sellerOrg(), retailOrgId), l.sellerDebit(), l.sellerOrg());
            b.debit(LedgerAccount.of(Chart.COMMISSION_REVENUE), l.commissionReversed(), l.sellerOrg());
            b.debit(LedgerAccount.of(Chart.LOCAL_DELIVERY_FEE_REVENUE), l.deliveryFeeReversed(), l.sellerOrg());
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
     * Shipping label bought by a seller through AfroTransact (S256): Dr 2000:org seller payable (cost + handling) /
     * Cr 2400 carrier labels payable (the carrier's negotiated cost — the carrier bills AfroTransact's platform
     * account) and Cr 4500 label handling revenue (the markup, usually zero). The lines carry no order: a label
     * charge is the organisation's, not one order's, so it never changes what an order's settlement owes; it is
     * recovered from the organisation's next transfers ({@link #labelRecovery}). No money moves, so 1000 and
     * reconciliation are untouched. The payable may go negative.
     */
    public static Journal labelCharge(UUID id, Instant at, String key, LabelCharge c) {
        return Journal.builder(JournalType.LABEL_CHARGE, key)
                .description("Shipping label " + c.carrier() + " " + c.trackingNumber() + " for shipment "
                        + c.shipmentId())
                .debit(LedgerAccount.sellerPayable(c.orgId()), c.amount(), c.orgId())
                .credit(LedgerAccount.of(Chart.CARRIER_LABELS_PAYABLE), c.cost(), c.orgId())
                .credit(LedgerAccount.of(Chart.LABEL_HANDLING_REVENUE), c.handling(), c.orgId())
                .build(id, at);
    }

    /**
     * AfroTransact Retail supplier invoices released for payment (S226): Dr 1400 Retail purchases released / Cr
     * 2500:supplier. Lines carry the Retail organisation for attribution; nothing touches a seller payable.
     */
    public static Journal supplierPayable(UUID id, Instant at, String key, SupplierPayment p, String retailOrgId) {
        return Journal.builder(JournalType.SUPPLIER_PAYABLE, key)
                .description("Supplier payment released for purchase order " + p.poNumber() + " (supplier "
                        + (p.supplierCode() == null ? p.supplierId() : p.supplierCode()) + ")")
                .debit(LedgerAccount.of(Chart.RETAIL_PURCHASES_RELEASED), p.amount(), retailOrgId)
                .credit(LedgerAccount.supplierPayable(p.supplierId().toString()), p.amount(), retailOrgId)
                .build(id, at);
    }

    /** A supplier paid from the operating bank account: Dr 2500:supplier / Cr 1050. */
    public static Journal supplierPayment(UUID id, Instant at, String key, SupplierPayment p, String reference,
                                          String retailOrgId) {
        return Journal.builder(JournalType.SUPPLIER_PAYMENT, key)
                .description("Supplier payment for purchase order " + p.poNumber() + " paid, reference " + reference)
                .debit(LedgerAccount.supplierPayable(p.supplierId().toString()), p.amount(), retailOrgId)
                .credit(LedgerAccount.of(Chart.OPERATING_BANK), p.amount(), retailOrgId)
                .build(id, at);
    }

    /** Voided label credited back to the seller: the exact reverse of {@link #labelCharge}. */
    public static Journal labelCredit(UUID id, Instant at, String key, LabelCharge c) {
        return Journal.builder(JournalType.LABEL_CREDIT, key)
                .description("Voided shipping label " + c.carrier() + " " + c.trackingNumber() + " for shipment "
                        + c.shipmentId() + " credited back")
                .debit(LedgerAccount.of(Chart.CARRIER_LABELS_PAYABLE), c.cost(), c.orgId())
                .debit(LedgerAccount.of(Chart.LABEL_HANDLING_REVENUE), c.handling(), c.orgId())
                .credit(LedgerAccount.sellerPayable(c.orgId()), c.amount(), c.orgId())
                .build(id, at);
    }

    /**
     * Settles the organisation's unattributed label balance against an order's payable before that order is
     * transferred: a positive {@code amount} recovers label charges (Dr 2000:org on the order / Cr 2000:org
     * unattributed), so the transfer is smaller; a negative one pays a credit from voided labels back with the
     * transfer (the opposite lines). Both lines stay on 2000:org, so the seller's total payable is unchanged.
     */
    public static Journal labelRecovery(UUID id, Instant at, String key, String orderId, String orgId,
                                        Money amount) {
        Journal.Builder b = Journal.builder(JournalType.LABEL_RECOVERY, key)
                .description((amount.isNegative() ? "Voided label credits paid with order " : "Label charges "
                        + "recovered from order ") + orderId);
        if (amount.isNegative()) {
            b.debit(LedgerAccount.sellerPayable(orgId), amount.negate(), orgId)
                    .creditOrder(LedgerAccount.sellerPayable(orgId), amount.negate(), orgId, orderId);
        } else {
            b.debitOrder(LedgerAccount.sellerPayable(orgId), amount, orgId, orderId)
                    .credit(LedgerAccount.sellerPayable(orgId), amount, orgId);
        }
        return b.build(id, at);
    }

    /**
     * Dispute opened: Stripe withdraws the amount (Dr 1100 chargeback receivable / Cr 1000) and each responsible
     * seller's payable is debited into a chargeback reserve (Dr 2000 / Cr 2300) until the outcome. Stripe's dispute fee
     * is AfroTransact's cost (Dr 5100). AfroTransact Retail's part has no payable to reserve: it stays in 1100 until
     * the outcome.
     */
    public static Journal disputeOpened(UUID id, Instant at, String key, Dispute d, String retailOrgId) {
        Journal.Builder b = Journal.builder(JournalType.DISPUTE_OPENED, key).order(d.orderId())
                .payment(d.paymentId().toString()).stripeRef(d.id())
                .description("Chargeback " + d.id() + " opened (" + d.reason() + ")")
                .debit(LedgerAccount.of(Chart.CHARGEBACK_RECEIVABLE), d.amount())
                .debit(LedgerAccount.of(Chart.CHARGEBACK_LOSSES), d.fee())
                .credit(LedgerAccount.of(Chart.STRIPE_CLEARING), d.amount().plus(d.fee()));
        for (DisputeAllocation a : d.sellerAllocations(retailOrgId)) {
            b.debit(LedgerAccount.sellerPayable(a.recipientId()), a.amount(), a.recipientId());
            b.credit(LedgerAccount.chargebackReserve(a.recipientId()), a.amount(), a.recipientId());
        }
        return b.build(id, at);
    }

    /**
     * Won: Stripe reinstates the funds (Dr 1000 / Cr 1100) and the reserve goes back to the sellers (Dr 2300 / Cr 2000);
     * AfroTransact Retail's part simply returns with the funds.
     */
    public static Journal disputeWon(UUID id, Instant at, String key, Dispute d, String retailOrgId) {
        Journal.Builder b = Journal.builder(JournalType.DISPUTE_WON, key).order(d.orderId())
                .payment(d.paymentId().toString()).stripeRef(d.id())
                .description("Chargeback " + d.id() + " won")
                .debit(LedgerAccount.of(Chart.STRIPE_CLEARING), d.amount())
                .credit(LedgerAccount.of(Chart.CHARGEBACK_RECEIVABLE), d.amount());
        for (DisputeAllocation a : d.sellerAllocations(retailOrgId)) {
            b.debit(LedgerAccount.chargebackReserve(a.recipientId()), a.amount(), a.recipientId());
            b.credit(LedgerAccount.sellerPayable(a.recipientId()), a.amount(), a.recipientId());
        }
        return b.build(id, at);
    }

    /**
     * Lost: the sellers' reserve covers the receivable (Dr 2300 / Cr 1100); AfroTransact's part is a loss (Dr 5100);
     * AfroTransact Retail's part reverses its sales (Dr 4400).
     */
    public static Journal disputeLost(UUID id, Instant at, String key, Dispute d, String retailOrgId) {
        Journal.Builder b = Journal.builder(JournalType.DISPUTE_LOST, key).order(d.orderId())
                .payment(d.paymentId().toString()).stripeRef(d.id() + ":lost")
                .description("Chargeback " + d.id() + " lost");
        for (DisputeAllocation a : d.allocations()) {
            if (PaymentSplitter.PLATFORM.equals(a.recipientId())) {
                b.debit(LedgerAccount.of(Chart.CHARGEBACK_LOSSES), a.amount());
            } else if (a.recipientId().equals(retailOrgId)) {
                b.debit(LedgerAccount.retailSales(), a.amount(), a.recipientId());
            } else {
                b.debit(LedgerAccount.chargebackReserve(a.recipientId()), a.amount(), a.recipientId());
            }
        }
        b.credit(LedgerAccount.of(Chart.CHARGEBACK_RECEIVABLE), d.amount());
        return b.build(id, at);
    }

    /** Who carries an order line's money: 4400 Retail sales for AfroTransact Retail, else the seller's 2000 payable. */
    public static LedgerAccount owner(String orgId, String retailOrgId) {
        return orgId.equals(retailOrgId) ? LedgerAccount.retailSales() : LedgerAccount.sellerPayable(orgId);
    }
}
