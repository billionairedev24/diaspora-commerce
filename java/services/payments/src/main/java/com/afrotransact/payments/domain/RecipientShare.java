package com.afrotransact.payments.domain;

import java.util.Map;

/**
 * One recipient's part of a charge. For sellers, commission / FBA fees / Stripe fee are deductions and
 * {@code net = items + shipping − commission − fbaFees − stripeFee}. For the platform row, commission and FBA fees are
 * the totals earned and {@code net = shipping + commission + fbaFees − stripeFee}. Tax is collected and remitted by
 * AfroTransact (never paid out) and only attributed to the seller.
 */
public record RecipientShare(String recipientId, RecipientKind kind, Money items, Money shipping, Money tax,
                             Map<String, Money> taxByJurisdiction, Money commission, Money fbaFees, Money stripeFee,
                             Money net) {

    public RecipientShare {
        taxByJurisdiction = Map.copyOf(taxByJurisdiction);
    }

    /** This recipient's part of the charge (the weight used to apportion the Stripe fee). */
    public Money gross() {
        return items.plus(shipping).plus(tax);
    }

    public boolean isPlatform() {
        return kind == RecipientKind.PLATFORM;
    }
}
