package com.afrotransact.orders.application;

import java.util.List;

import com.afrotransact.orders.domain.QuoteContact;

/** The shopper's side of "Ask for a delivery quote" (listing rules D20). */
public interface DeliveryQuoteService {

    /**
     * Asks for a quote for the cart and the saved address. Idempotent: the same cart and address returns the same
     * request while it is live or inside the duplicate window. Limited per shopper per hour.
     *
     * @param customerBearer the shopper's token, relayed to cart and identity (never stored)
     * @param addressId      a saved address
     * @param lines          optional subset of the cart (default: every line)
     * @param contact        how customer care may reach the shopper
     * @param note           optional, up to 500 characters
     */
    DeliveryQuoteView request(String customerBearer, String addressId, List<CheckoutCommand.SelectedLine> lines,
                              QuoteContact contact, String note);

    /** My requests, newest first. */
    List<DeliveryQuoteView> mine();

    /** One of my requests (404 for anyone else's). */
    DeliveryQuoteView get(String id);
}
