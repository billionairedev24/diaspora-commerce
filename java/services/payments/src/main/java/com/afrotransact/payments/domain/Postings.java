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
     * Cr 2000 seller payable (net), Cr 2100 sales tax payable per jurisdiction, Cr 4000 commission (products), Cr 4050
     * connection fees (service bookings), Cr 4100 FBA fees,
     * Cr 4200 AfroTransact shipping, Cr 4250 local delivery fees (AfroTransact Delivery from seller stores, and the
     * pre-order delivery fees of S272, which are part of the platform's shipping but not of 4200). Sellers
     * bear their fee share through a lower payable. AfroTransact Retail's net
     * (items + shipping − its fee share) is AfroTransact revenue: Cr 4400 Retail sales, never 2000.
     */
    public static Journal capture(UUID id, Instant at, String key, String orderId, String paymentId,
                                  String chargeId, PaymentSplit split) {
        return capture(id, at, key, orderId, paymentId, chargeId, split, Money.zero(split.charge().currency()));
    }

    /**
     * Capture of an order partly paid with credit: the credit share of the capture is Dr 2600 customer credit instead
     * of money from Stripe, so Dr 1000 is the card share less the fee. The credit becomes revenue here, at shipment.
     */
    public static Journal capture(UUID id, Instant at, String key, String orderId, String paymentId,
                                  String chargeId, PaymentSplit split, Money creditShare) {
        Journal.Builder b = Journal.builder(JournalType.CAPTURE, key).order(orderId).payment(paymentId)
                .stripeRef(chargeId).description("Capture of order " + orderId);
        RecipientShare platform = split.platform();
        b.debit(LedgerAccount.of(Chart.STRIPE_CLEARING), split.charge().minus(split.stripeFee()).minus(creditShare));
        b.debit(LedgerAccount.of(Chart.CUSTOMER_CREDIT), creditShare);
        b.debit(LedgerAccount.of(Chart.STRIPE_FEES), platform.stripeFee());
        for (RecipientShare s : split.sellers()) {
            LedgerAccount owner = s.isRetail() ? LedgerAccount.retailSales()
                    : LedgerAccount.sellerPayable(s.recipientId());
            b.credit(owner, s.net(), s.recipientId());
            for (Map.Entry<String, Money> tax : s.taxByJurisdiction().entrySet()) {
                b.credit(LedgerAccount.salesTax(tax.getKey()), tax.getValue(), s.recipientId());
            }
            b.credit(LedgerAccount.of(Chart.COMMISSION_REVENUE), s.commission(), s.recipientId());
            b.credit(LedgerAccount.of(Chart.CONNECTION_FEE_REVENUE), s.connectionFees(), s.recipientId());
            b.credit(LedgerAccount.of(Chart.FBA_FEE_REVENUE), s.fbaFees(), s.recipientId());
            b.credit(LedgerAccount.of(Chart.LOCAL_DELIVERY_FEE_REVENUE), s.deliveryFees(), s.recipientId());
        }
        Money preorderFees = split.lines().stream().map(LineSplit::preorderDeliveryFee)
                .reduce(Money.zero(split.currency()), Money::plus);
        b.credit(LedgerAccount.of(Chart.SHIPPING_REVENUE), platform.shipping().minus(preorderFees));
        b.credit(LedgerAccount.of(Chart.LOCAL_DELIVERY_FEE_REVENUE), preorderFees);
        return b.build(id, at);
    }

    /**
     * Refund: Dr 2100 tax, Dr 2000 seller payable (items + own shipping − commission returned), Dr 4000 commission
     * returned (Dr 4050 for the connection fee of a service line), Dr 4200 AfroTransact shipping, Dr 4250 delivery fee returned (customer-fault refunds of AfroTransact
     * Delivery lines) / Cr 2200 customer refunds payable. FBA fees and Stripe fee shares are not given back. Lines of AfroTransact Retail ({@code retailOrgId}) debit 4400 Retail sales instead of a
     * payable (Retail paid no commission, so none is returned).
     */
    public static Journal refund(UUID id, Instant at, String key, String orderId, String paymentId, String refundRef,
                                 List<RefundLine> lines, String retailOrgId) {
        return refund(id, at, key, orderId, paymentId, refundRef, lines, retailOrgId, null);
    }

    /**
     * Refund with a credit part: the part given back as credit is Cr 2600 customer credit instead of Cr 2200 refunds
     * payable (no Stripe refund follows for it).
     */
    public static Journal refund(UUID id, Instant at, String key, String orderId, String paymentId, String refundRef,
                                 List<RefundLine> lines, String retailOrgId, Money creditPart) {
        Journal.Builder b = Journal.builder(JournalType.REFUND, key).order(orderId).payment(paymentId)
                .stripeRef(refundRef).description("Refund on order " + orderId);
        Money total = null;
        for (RefundLine l : lines) {
            total = total == null ? l.total() : total.plus(l.total());
            b.debit(LedgerAccount.salesTax(l.taxJurisdiction()), l.tax(), l.sellerOrg());
            b.debit(owner(l.sellerOrg(), retailOrgId), l.sellerDebit(), l.sellerOrg());
            b.debit(LedgerAccount.of(l.connectionFee() ? Chart.CONNECTION_FEE_REVENUE : Chart.COMMISSION_REVENUE),
                    l.commissionReversed(), l.sellerOrg());
            b.debit(LedgerAccount.of(Chart.LOCAL_DELIVERY_FEE_REVENUE), l.deliveryFeeReversed(), l.sellerOrg());
            if (l.shippingToPlatform()) {
                b.debit(LedgerAccount.of(l.preorderDeliveryFee() ? Chart.LOCAL_DELIVERY_FEE_REVENUE
                        : Chart.SHIPPING_REVENUE), l.shipping());
            }
        }
        if (total == null) {
            throw new PaymentsRuleViolation("payments.invalid-refund", "A refund needs at least one line");
        }
        Money credit = creditPart == null ? Money.zero(total.currency()) : creditPart;
        b.credit(LedgerAccount.of(Chart.CUSTOMER_REFUNDS_PAYABLE), total.minus(credit));
        b.credit(LedgerAccount.of(Chart.CUSTOMER_CREDIT), credit);
        return b.build(id, at);
    }

    /** Stripe paid the refund out of the platform balance: Dr 2200 / Cr 1000. */
    public static Journal refundSettled(UUID id, Instant at, String key, Refund refund) {
        return Journal.builder(JournalType.REFUND_SETTLED, key).order(refund.orderId())
                .payment(refund.paymentId().toString()).stripeRef(refund.stripeRefundId())
                .description("Refund " + refund.stripeRefundId() + " paid by Stripe")
                .debit(LedgerAccount.of(Chart.CUSTOMER_REFUNDS_PAYABLE), refund.stripeAmount())
                .credit(LedgerAccount.of(Chart.STRIPE_CLEARING), refund.stripeAmount())
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
     * A seller plan invoice paid from earnings (S298): Dr 2000:org seller payable / Cr 4600 plan fee revenue. Like a
     * label charge the debit carries no order, so it never changes what one order's settlement owes; the
     * organisation's next transfers recover it ({@link #planFeeSettled}). No money moves (the seller service credits
     * the Stripe customer balance instead of charging the card), so 1000 and reconciliation are untouched.
     */
    public static Journal planFeeWithheld(UUID id, Instant at, String key, PlanFeeWithholding w) {
        return Journal.builder(JournalType.PLAN_FEE_WITHHELD, key)
                .description(planFeeLabel(w))
                .debit(LedgerAccount.sellerPayable(w.orgId()), w.withheld(), w.orgId())
                .credit(LedgerAccount.of(Chart.PLAN_FEE_REVENUE), w.withheld(), w.orgId())
                .build(id, at);
    }

    /** A withheld plan fee credited back (voided or refunded invoice): the reverse of {@link #planFeeWithheld}. */
    public static Journal planFeeReleased(UUID id, Instant at, String key, PlanFeeWithholding w, Money amount,
                                          String why) {
        return Journal.builder(JournalType.PLAN_FEE_RELEASED, key)
                .description(planFeeLabel(w) + " released" + (why == null || why.isBlank() ? "" : " (" + why + ")"))
                .debit(LedgerAccount.of(Chart.PLAN_FEE_REVENUE), amount, w.orgId())
                .credit(LedgerAccount.sellerPayable(w.orgId()), amount, w.orgId())
                .build(id, at);
    }

    /**
     * Settles a plan fee against an order's payable before that order is transferred: a positive {@code amount} is
     * recovered from the order (Dr 2000:org on the order / Cr 2000:org unattributed), so the transfer is smaller by the
     * fee; a negative one pays a released fee back with the transfer (the opposite lines). Both lines stay on 2000:org,
     * so the seller's total payable is unchanged.
     */
    public static Journal planFeeSettled(UUID id, Instant at, String key, String orderId, PlanFeeWithholding w,
                                         Money amount) {
        Journal.Builder b = Journal.builder(JournalType.PLAN_FEE_SETTLED, key)
                .description(planFeeLabel(w) + (amount.isNegative() ? " paid back with order " : " deducted from order ")
                        + orderId);
        if (amount.isNegative()) {
            b.debit(LedgerAccount.sellerPayable(w.orgId()), amount.negate(), w.orgId())
                    .creditOrder(LedgerAccount.sellerPayable(w.orgId()), amount.negate(), w.orgId(), orderId);
        } else {
            b.debitOrder(LedgerAccount.sellerPayable(w.orgId()), amount, w.orgId(), orderId)
                    .credit(LedgerAccount.sellerPayable(w.orgId()), amount, w.orgId());
        }
        return b.build(id, at);
    }

    /**
     * Stripe refused to reverse a transfer for a refund (the seller's balance was already paid out). The refund journal
     * debited the seller's payable on the order and nothing credited it back, so the order's payable is short by
     * {@code r.amount()}; this moves that debt to the organisation's unattributed payable (Dr 2000:org / Cr 2000:org on
     * the order), exactly like a label charge: the order's payable is whole again, the organisation's total payable is
     * negative by what it owes, and its next transfers recover it ({@link #transferRecoverySettled}). 1000 is untouched
     * (no money came back).
     */
    public static Journal transferRecoveryOpened(UUID id, Instant at, String key, TransferRecovery r) {
        return Journal.builder(JournalType.TRANSFER_RECOVERY_OPENED, key)
                .description("Transfer " + r.stripeTransferId() + " could not be reversed for order " + r.orderId()
                        + " (" + (r.stripeCode() == null ? "refused" : r.stripeCode()) + "): " + r.orgId()
                        + " owes " + r.amount())
                .debit(LedgerAccount.sellerPayable(r.orgId()), r.amount(), r.orgId())
                .creditOrder(LedgerAccount.sellerPayable(r.orgId()), r.amount(), r.orgId(), r.orderId())
                .build(id, at);
    }

    /**
     * A transfer recovers {@code amount} of a refused reversal: Dr 2000:org on the order being transferred / Cr 2000:org
     * unattributed. The transfer is smaller by {@code amount}, which stays with the platform.
     */
    public static Journal transferRecoverySettled(UUID id, Instant at, String key, String orderId,
                                                  TransferRecovery r, Money amount) {
        return Journal.builder(JournalType.TRANSFER_RECOVERY_SETTLED, key)
                .description("Refund recovery for order " + r.orderId() + " deducted from order " + orderId)
                .debitOrder(LedgerAccount.sellerPayable(r.orgId()), amount, r.orgId(), orderId)
                .credit(LedgerAccount.sellerPayable(r.orgId()), amount, r.orgId())
                .build(id, at);
    }

    /** The seller paid the platform back outside Stripe: Dr 1050 operating bank / Cr 2000:org unattributed. */
    public static Journal transferRecoveryReceived(UUID id, Instant at, String key, TransferRecovery r, Money amount,
                                                   String reference) {
        return Journal.builder(JournalType.TRANSFER_RECOVERY_RECEIVED, key)
                .description("Refund recovery for order " + r.orderId() + " received from " + r.orgId()
                        + ", reference " + reference)
                .debit(LedgerAccount.of(Chart.OPERATING_BANK), amount, r.orgId())
                .credit(LedgerAccount.sellerPayable(r.orgId()), amount, r.orgId())
                .build(id, at);
    }

    /** The platform absorbs what is left: Dr 5300 transfer recovery losses / Cr 2000:org unattributed. */
    public static Journal transferRecoveryWrittenOff(UUID id, Instant at, String key, TransferRecovery r,
                                                     Money amount) {
        return Journal.builder(JournalType.TRANSFER_RECOVERY_WRITE_OFF, key)
                .description("Refund recovery for order " + r.orderId() + " written off for " + r.orgId())
                .debit(LedgerAccount.of(Chart.TRANSFER_RECOVERY_LOSSES), amount, r.orgId())
                .credit(LedgerAccount.sellerPayable(r.orgId()), amount, r.orgId())
                .build(id, at);
    }

    /** "Plan fee (invoice INV-0042)": the line sellers see on statements and payouts. */
    public static String planFeeLabel(PlanFeeWithholding w) {
        return "Plan fee (invoice " + (w.invoiceNumber() == null || w.invoiceNumber().isBlank() ? w.invoiceId()
                : w.invoiceNumber()) + ")";
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

    // ---------------------------------------------------------------- credit and gift cards (S320, S321)

    /** Credit granted (staff, promotion, referral, membership): the program's cost. Dr 5200 / Cr 2600. */
    public static Journal creditGranted(UUID id, Instant at, String key, String customerId, Money amount,
                                        String reason) {
        return Journal.builder(JournalType.CREDIT_GRANT, key).description("Credit granted to " + customerId + ": "
                + reason).debit(LedgerAccount.of(Chart.CREDIT_GRANTED_EXPENSE), amount)
                .credit(LedgerAccount.of(Chart.CUSTOMER_CREDIT), amount).build(id, at);
    }

    /** Credit taken back by a staff correction. Dr 2600 / Cr 5200. */
    public static Journal creditTakenBack(UUID id, Instant at, String key, String customerId, Money amount,
                                          String reason) {
        return Journal.builder(JournalType.CREDIT_ADJUSTMENT, key).description("Credit corrected for " + customerId
                + ": " + reason).debit(LedgerAccount.of(Chart.CUSTOMER_CREDIT), amount)
                .credit(LedgerAccount.of(Chart.CREDIT_GRANTED_EXPENSE), amount).build(id, at);
    }

    /**
     * A gift card paid by card: the money is a liability until the card is redeemed. Dr 1000 (charge less fee), Dr 5000
     * (fee) / Cr 2650. Matched to the charge by reconciliation.
     */
    public static Journal giftCardSold(UUID id, Instant at, String key, GiftCard card, String chargeId, Money fee) {
        return Journal.builder(JournalType.GIFT_CARD_SOLD, key).stripeRef(chargeId)
                .description("Gift card " + card.id() + " sold")
                .debit(LedgerAccount.of(Chart.STRIPE_CLEARING), card.amount().minus(fee))
                .debit(LedgerAccount.of(Chart.STRIPE_FEES), fee)
                .credit(LedgerAccount.of(Chart.GIFT_CARD_LIABILITY), card.amount()).build(id, at);
    }

    /**
     * A membership fee charged to the member's card (S323). Dr 1000 (charge less fee), Dr 5000 (fee) / Cr 4700. Matched
     * to the charge by reconciliation.
     */
    public static Journal membershipCharged(UUID id, Instant at, String key, MembershipCharge charge, Money fee) {
        return Journal.builder(JournalType.MEMBERSHIP_CHARGE, key).stripeRef(charge.chargeId())
                .description("Membership " + charge.tierCode() + " fee of " + charge.customerId())
                .debit(LedgerAccount.of(Chart.STRIPE_CLEARING), charge.amount().minus(fee))
                .debit(LedgerAccount.of(Chart.STRIPE_FEES), fee)
                .credit(LedgerAccount.of(Chart.MEMBERSHIP_REVENUE), charge.amount()).build(id, at);
    }

    /** A membership fee refunded to the card, matched to the Stripe refund. Dr 4700 / Cr 1000. */
    public static Journal membershipRefunded(UUID id, Instant at, String key, MembershipCharge charge,
                                             String refundId) {
        return Journal.builder(JournalType.MEMBERSHIP_REFUND, key).stripeRef(refundId)
                .description("Membership " + charge.tierCode() + " fee of " + charge.customerId() + " refunded")
                .debit(LedgerAccount.of(Chart.MEMBERSHIP_REVENUE), charge.amount())
                .credit(LedgerAccount.of(Chart.STRIPE_CLEARING), charge.amount()).build(id, at);
    }

    /** A gift card issued by staff. Dr 5200 / Cr 2650. */
    public static Journal giftCardIssued(UUID id, Instant at, String key, GiftCard card) {
        return Journal.builder(JournalType.GIFT_CARD_ISSUED, key).description("Gift card " + card.id()
                + " issued by staff: " + card.issueReason())
                .debit(LedgerAccount.of(Chart.CREDIT_GRANTED_EXPENSE), card.amount())
                .credit(LedgerAccount.of(Chart.GIFT_CARD_LIABILITY), card.amount()).build(id, at);
    }

    /** A gift card redeemed into the recipient's credit. Dr 2650 / Cr 2600. */
    public static Journal giftCardRedeemed(UUID id, Instant at, String key, GiftCard card, String customerId) {
        return Journal.builder(JournalType.GIFT_CARD_REDEEMED, key).description("Gift card " + card.id()
                + " redeemed by " + customerId)
                .debit(LedgerAccount.of(Chart.GIFT_CARD_LIABILITY), card.amount())
                .credit(LedgerAccount.of(Chart.CUSTOMER_CREDIT), card.amount()).build(id, at);
    }

    /**
     * An unredeemed gift card voided. A purchased one is refunded to the purchaser's card (Dr 2650 / Cr 1000, matched
     * to the Stripe refund); one staff issued just ends the liability (Dr 2650 / Cr 5200).
     */
    public static Journal giftCardVoided(UUID id, Instant at, String key, GiftCard card, String refundRef) {
        Journal.Builder b = Journal.builder(JournalType.GIFT_CARD_VOIDED, key).description("Gift card " + card.id()
                + " voided").debit(LedgerAccount.of(Chart.GIFT_CARD_LIABILITY), card.amount());
        if (refundRef != null) {
            b.stripeRef(refundRef).credit(LedgerAccount.of(Chart.STRIPE_CLEARING), card.amount());
        } else {
            b.credit(LedgerAccount.of(Chart.CREDIT_GRANTED_EXPENSE), card.amount());
        }
        return b.build(id, at);
    }
}
