package com.afrotransact.offers.port;

/** Counters of the offer tax hold (S384). The gauge {@code offers_active_without_tax_decision} reads the store itself. */
public interface OfferTaxMetrics {

    /** An offer was held for tax; {@code reason} is TAX_DECISION_MISSING, TAX_&lt;STATUS&gt; or TAX_UNAVAILABLE. */
    void held(String reason);

    /** A held offer was released because the seller has a RESOLVED decision. */
    void released();
}
