package com.afrotransact.seller.application;

import com.afrotransact.seller.port.InvoiceSnapshot;

/**
 * Optional collection of plan fees from earnings (feature flag {@code at.seller.billing.collect-from-earnings},
 * default off; per organisation opt-in by staff). When Stripe creates a renewal invoice (draft, about an hour before
 * it finalises) the seller service asks payments to withhold the amount from the organisation's next settlement
 * transfer; once payments confirms, it posts a customer balance credit for the same amount, so the invoice is paid
 * from the balance when it finalises and the card is not charged. If payments declines (or does not answer in time)
 * nothing changes and Stripe charges the card as usual.
 */
public interface EarningsCollectionService {

    /** invoice.created (inside the webhook transaction): claims the invoice when the organisation is opted in. */
    String onInvoiceCreated(String orgId, InvoiceSnapshot invoice);

    /** Job: asks payments for the claimed invoices and posts the credits. Returns how many were credited. */
    int processOpen();

    /** Staff (billing:adjust): opt an organisation in or out. */
    void setOptIn(String orgId, boolean on);

    boolean enabled();
}
