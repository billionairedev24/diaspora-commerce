package com.afrotransact.orders.application;

import java.util.List;

import com.afrotransact.orders.domain.ShipTo;
import com.afrotransact.orders.port.CartClient;

/** Applying an agreed quote at checkout: validated and consumed once, restored when the order does not complete. */
public interface DeliveryQuoteRedemptionService {

    /**
     * Validates the quote for this shopper, cart and address and marks it used by {@code orderId}, in one step.
     * Repeating it for the same order returns the same answer.
     *
     * @throws com.afrotransact.orders.domain.OrdersRuleViolation with a plain message and one of
     *         {@code orders.quote-not-found}, {@code orders.quote-changed}, {@code orders.quote-expired},
     *         {@code orders.quote-used}, {@code orders.quote-not-ready}
     */
    Redeemed redeem(String customerId, String quoteId, List<CartClient.Item> items, ShipTo shipTo, String orderId);

    /** The order did not complete (declined, abandoned, superseded): the quote is usable again while valid. Idempotent. */
    void restore(String orderId);

    /** What a redeemed quote charges. */
    record Redeemed(String quoteId, String reference, long amountMinor, String currency) {
    }
}
