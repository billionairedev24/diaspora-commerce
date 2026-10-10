package com.afrotransact.payments.domain;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Default split:
 * <ul>
 *   <li>commission = items × category rate (seller override first), rounded half up per line;</li>
 *   <li>FBA fee = units × fee per unit on marketplace sellers' FBA lines;</li>
 *   <li>shipping of a marketplace seller's FBA line is AfroTransact revenue (4200), seller-shipped lines keep their
 *       shipping;</li>
 *   <li>AfroTransact Delivery from the seller's store (S255): the customer's delivery charge (the line's shipping) is
 *       AfroTransact revenue (4200), the seller pays commission + the pickup-delivery fee quoted at checkout (4250
 *       Local delivery fees) + its Stripe share, and no FBA fee;</li>
 *   <li>community pre-orders (S272): the line's shipping (its share of the window's flat door-delivery fee) is
 *       AfroTransact's local delivery revenue (4250), for marketplace sellers and AfroTransact Retail alike; it is
 *       part of the platform row's shipping and delivery fees;</li>
 *   <li>AfroTransact Retail is the merchant of record, not a marketplace seller: no commission (even with an
 *       override), no FBA fee, and it keeps the shipping of all its lines, FBA or not, so
 *       {@code Retail net = items + shipping − Stripe-fee share} (booked to 4400 Retail sales);</li>
 *   <li>the Stripe fee is apportioned by each recipient's share of the charge (items + shipping + tax) with the
 *       largest-remainder method; AfroTransact takes the tie-break and any leftover cent, so totals always
 *       reconcile to the charge exactly.</li>
 * </ul>
 */
public class DefaultPaymentSplitter implements PaymentSplitter {

    private final String retailOrgId;

    public DefaultPaymentSplitter(String retailOrgId) {
        this.retailOrgId = Objects.requireNonNull(retailOrgId, "retailOrgId");
    }

    @Override
    public PaymentSplit split(List<OrderLine> lines, FeeSchedule schedule, Money stripeFee) {
        if (lines.isEmpty()) {
            throw new PaymentsRuleViolation("payments.invalid-request", "A split needs at least one line");
        }
        String currency = lines.getFirst().currency();
        if (!schedule.currency().equals(currency)) {
            throw new PaymentsRuleViolation("payments.currency-mismatch", "Fee schedule is in "
                    + schedule.currency() + " but the order is in " + currency);
        }
        stripeFee.requireSameCurrency(Money.zero(currency));
        Money zero = Money.zero(currency);
        Map<String, Acc> accs = new LinkedHashMap<>();
        Acc platform = new Acc(PLATFORM, RecipientKind.PLATFORM, zero);
        accs.put(PLATFORM, platform);
        List<LineSplit> lineSplits = new ArrayList<>();
        for (OrderLine line : lines) {
            line.items().requireSameCurrency(zero);
            boolean retail = retailOrgId.equals(line.sellerOrg());
            Acc acc = accs.computeIfAbsent(line.sellerOrg(),
                    org -> new Acc(org, retail ? RecipientKind.RETAIL : RecipientKind.SELLER, zero));
            int bps = retail ? 0 : schedule.commissionBps(line.sellerOrg(), line.category());
            Money commission = line.items().basisPoints(bps);
            boolean fba = line.fulfilment() == Fulfilment.FBA;
            Money fbaFee = fba && !retail ? schedule.fbaPerUnit().times(line.quantity()) : zero;
            Money deliveryFee = line.fulfilment() == Fulfilment.AFROTRANSACT_DELIVERY && !retail
                    ? line.deliveryFee() : zero;
            boolean preorder = line.fulfilment().shippingIsLocalDeliveryFee();
            boolean shippingToPlatform = preorder || line.fulfilment().platformShips() && !retail;
            acc.items = acc.items.plus(line.items());
            acc.tax = acc.tax.plus(line.tax());
            acc.taxByJurisdiction.merge(line.taxJurisdiction(), line.tax(), Money::plus);
            acc.commission = acc.commission.plus(commission);
            acc.fba = acc.fba.plus(fbaFee);
            acc.delivery = acc.delivery.plus(deliveryFee);
            if (shippingToPlatform) {
                platform.shipping = platform.shipping.plus(line.shipping());
            } else {
                acc.shipping = acc.shipping.plus(line.shipping());
            }
            if (preorder) {
                platform.preorder = platform.preorder.plus(line.shipping());
            }
            lineSplits.add(new LineSplit(line.lineId(), line.sellerOrg(), bps, commission, fbaFee,
                    shippingToPlatform, deliveryFee, preorder ? line.shipping() : zero));
        }

        List<Acc> ordered = new ArrayList<>(accs.values());
        long[] weights = new long[ordered.size()];
        Money charge = zero;
        for (int i = 0; i < ordered.size(); i++) {
            Money gross = ordered.get(i).gross();
            weights[i] = gross.minor();
            charge = charge.plus(gross);
        }
        long[] feeShares = Apportionment.largestRemainder(stripeFee.minor(), weights, 0);

        Money commissionTotal = zero;
        Money fbaTotal = zero;
        Money deliveryTotal = zero;
        for (Acc a : ordered) {
            if (a != platform) {
                commissionTotal = commissionTotal.plus(a.commission);
                fbaTotal = fbaTotal.plus(a.fba);
                deliveryTotal = deliveryTotal.plus(a.delivery);
            }
        }
        List<RecipientShare> shares = new ArrayList<>();
        for (int i = 0; i < ordered.size(); i++) {
            Acc a = ordered.get(i);
            Money fee = Money.of(feeShares[i], currency);
            if (a == platform) {
                Money net = a.shipping.plus(commissionTotal).plus(fbaTotal).plus(deliveryTotal).minus(fee);
                shares.add(new RecipientShare(a.id, a.kind, zero, a.shipping, zero, Map.of(), commissionTotal,
                        fbaTotal, deliveryTotal.plus(a.preorder), fee, net));
            } else {
                Money net = a.items.plus(a.shipping).minus(a.commission).minus(a.fba).minus(a.delivery).minus(fee);
                shares.add(new RecipientShare(a.id, a.kind, a.items, a.shipping, a.tax, a.taxByJurisdiction,
                        a.commission, a.fba, a.delivery, fee, net));
            }
        }
        return new PaymentSplit(charge, stripeFee, shares, lineSplits);
    }

    /** Mutable accumulator for one recipient. */
    private static final class Acc {
        private final String id;
        private final RecipientKind kind;
        private final Map<String, Money> taxByJurisdiction = new HashMap<>();
        private Money items;
        private Money shipping;
        private Money tax;
        private Money commission;
        private Money fba;
        private Money delivery;
        private Money preorder;

        private Acc(String id, RecipientKind kind, Money zero) {
            this.id = id;
            this.kind = kind;
            this.items = zero;
            this.shipping = zero;
            this.tax = zero;
            this.commission = zero;
            this.fba = zero;
            this.delivery = zero;
            this.preorder = zero;
        }

        private Money gross() {
            return items.plus(shipping).plus(tax);
        }
    }
}
