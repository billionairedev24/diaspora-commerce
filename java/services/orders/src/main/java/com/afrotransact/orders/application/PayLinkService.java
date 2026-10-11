package com.afrotransact.orders.application;

import java.time.Instant;

/**
 * Pay-now links of late pre-order adds (customer service sends them by SMS). The link opens AfroTransact's own pay page
 * ({@code <storefront>/orders/<number>/pay?token=…}), where the customer confirms the order's existing manual-capture
 * PaymentIntent with Stripe Elements; customers never get a Stripe-hosted page.
 */
public interface PayLinkService {

    /** The pay page of the order without its token (what the order records and staff may see). */
    String page(String orderNumber);

    /** The link sent to the customer: the pay page with the signed token, valid until {@code expiresAt}. */
    String link(String orderId, String orderNumber, Instant expiresAt);

    /**
     * What the pay page shows for a valid token: the amount and lines, and the PaymentIntent client secret while the
     * order awaits payment.
     *
     * @throws com.afrotransact.platform.web.error.NotFoundException 404 orders.pay-link-not-found for an unknown
     *         order or a token that is not the order's
     * @throws com.afrotransact.orders.domain.OrdersStateConflict 410 orders.pay-link-expired once the link expired
     *         or the order was voided
     */
    PayLinkView lookup(String orderNumber, String token);

    /**
     * After {@code stripe.confirmPayment} on the pay page: reads the intent from Stripe (through payments) and places
     * the order when it is authorised. Same refusals as {@link #lookup}.
     */
    PayLinkView confirm(String orderNumber, String token);
}
