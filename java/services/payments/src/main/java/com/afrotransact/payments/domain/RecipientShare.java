package com.afrotransact.payments.domain;

import java.util.Map;

/**
 * One recipient's part of a charge. For sellers, commission / connection fees / FBA fees / delivery fees / Stripe fee
 * are deductions and
 * {@code net = items + shipping − commission − connectionFees − fbaFees − deliveryFees − stripeFee}. AfroTransact
 * Retail pays no commission, no FBA fee and no delivery fee: {@code net = items + shipping − stripeFee}, booked as
 * AfroTransact revenue (4400), never as a payable. For the platform row, commission, connection fees, FBA fees and
 * delivery fees are the totals earned and
 * {@code net = shipping + commission + connectionFees + fbaFees + deliveryFees − stripeFee}. Tax is collected and
 * remitted by AfroTransact (never paid out) and only attributed to the seller.
 *
 * @param deliveryFees seller pickup-delivery fees of AfroTransact Delivery lines (S255, 4250 Local delivery fees); on
 *                     the platform row everything booked to 4250, pre-order delivery fees (S272, also part of the
 *                     platform's shipping) included
 * @param commission     product commission (4000)
 * @param connectionFees service connection fees (4050), reported apart from commission; a deduction for providers
 *                       like commission, the total earned on the platform row
 */
public record RecipientShare(String recipientId, RecipientKind kind, Money items, Money shipping, Money tax,
                             Map<String, Money> taxByJurisdiction, Money commission, Money fbaFees,
                             Money deliveryFees, Money stripeFee, Money net, Money connectionFees) {

    public RecipientShare {
        taxByJurisdiction = Map.copyOf(taxByJurisdiction);
        deliveryFees = deliveryFees == null ? Money.zero(items.currency()) : deliveryFees;
        connectionFees = connectionFees == null ? Money.zero(items.currency()) : connectionFees;
    }

    /** A share without connection fees. */
    public RecipientShare(String recipientId, RecipientKind kind, Money items, Money shipping, Money tax,
                          Map<String, Money> taxByJurisdiction, Money commission, Money fbaFees, Money deliveryFees,
                          Money stripeFee, Money net) {
        this(recipientId, kind, items, shipping, tax, taxByJurisdiction, commission, fbaFees, deliveryFees, stripeFee,
                net, null);
    }

    /** A share without delivery fees. */
    public RecipientShare(String recipientId, RecipientKind kind, Money items, Money shipping, Money tax,
                          Map<String, Money> taxByJurisdiction, Money commission, Money fbaFees, Money stripeFee,
                          Money net) {
        this(recipientId, kind, items, shipping, tax, taxByJurisdiction, commission, fbaFees, null, stripeFee, net);
    }

    /** This recipient's part of the charge (the weight used to apportion the Stripe fee). */
    public Money gross() {
        return items.plus(shipping).plus(tax);
    }

    public boolean isPlatform() {
        return kind == RecipientKind.PLATFORM;
    }

    /** AfroTransact Retail (first party): its net is AfroTransact revenue, not owed to anyone. */
    public boolean isRetail() {
        return kind == RecipientKind.RETAIL;
    }
}
