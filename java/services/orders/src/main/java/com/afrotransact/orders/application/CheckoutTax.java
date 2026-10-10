package com.afrotransact.orders.application;

/**
 * Taxes a checkout draft per line once its shipments (and so its shipping) are known: the tax-returns quote, or the
 * interim per-state table when tax-returns is not used or unavailable.
 */
public interface CheckoutTax {

    /** The draft with tax, jurisdiction, local jurisdiction and shipping tax set on every line. */
    OrderDraft tax(OrderDraft draft);
}
