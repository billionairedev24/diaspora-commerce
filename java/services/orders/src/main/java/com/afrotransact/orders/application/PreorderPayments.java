package com.afrotransact.orders.application;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import com.afrotransact.orders.port.PreorderEligibility;

/**
 * Maps the payment methods a pre-order window allows (back-office payment setup: CARD, WALLET, ACH) onto Stripe
 * payment method types for the PaymentIntent (S272). Pure.
 * <ul>
 *   <li>every method allowed (the default) → automatic payment methods (empty list), as for any checkout;</li>
 *   <li>CARD or WALLET → {@code card} (Apple Pay and Google Pay are card wallets in Stripe, so WALLET alone cannot
 *       exclude typed cards); WALLET also → {@code link};</li>
 *   <li>ACH is left out of explicit lists: the order PaymentIntent uses manual capture, which {@code us_bank_account}
 *       does not support; a window that allows ACH only falls back to automatic methods (Stripe then offers the
 *       manual-capture-capable methods).</li>
 * </ul>
 */
final class PreorderPayments {

    static final String CARD = "CARD";
    static final String WALLET = "WALLET";
    static final String ACH = "ACH";

    private PreorderPayments() {
    }

    /** The intersection of the windows' allowed methods (all methods when there is no window). */
    static Set<String> allowed(Collection<Set<String>> perWindow) {
        Set<String> out = new LinkedHashSet<>(PreorderEligibility.ALL_METHODS);
        for (Set<String> w : perWindow) {
            Set<String> upper = new LinkedHashSet<>();
            w.forEach(m -> upper.add(m.toUpperCase(Locale.ROOT)));
            out.retainAll(upper);
        }
        return out;
    }

    /** Stripe payment method types for the allowed methods; empty = automatic payment methods. */
    static List<String> stripeTypes(Set<String> allowed) {
        if (allowed.containsAll(PreorderEligibility.ALL_METHODS)) {
            return List.of();
        }
        List<String> types = new ArrayList<>();
        if (allowed.contains(CARD) || allowed.contains(WALLET)) {
            types.add("card");
        }
        if (allowed.contains(WALLET)) {
            types.add("link");
        }
        return types;
    }
}
