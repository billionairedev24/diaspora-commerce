package com.afrotransact.orders.port;

import java.time.Instant;
import java.util.Optional;

/**
 * Signs and checks the token of a late pre-order add's pay-now link ({@code <storefront>/orders/<number>/pay?token=…}).
 * The token binds the order id, the order number and the link's expiry, so it is unguessable, expires and cannot be
 * moved to another order. It is single-use by state: once the order is paid (or voided) the pay page refuses it.
 * Nothing is stored; a replayed late add re-derives the same token.
 */
public interface PayLinkSigner {

    /** The token of the order's link, valid until {@code expiresAt}. */
    String sign(String orderId, String orderNumber, Instant expiresAt);

    /**
     * The expiry the token was signed with when it is this order's token; empty for any other or malformed token.
     * The caller checks the expiry against the clock.
     */
    Optional<Instant> verify(String orderId, String orderNumber, String token);
}
