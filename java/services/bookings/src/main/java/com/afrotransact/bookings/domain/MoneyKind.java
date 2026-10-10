package com.afrotransact.bookings.domain;

/** Money movements of a booking (the design's per-booking ledger: auth, capture, charge, release, refund, fee). */
public enum MoneyKind {
    /** Deposit and balance authorised on the card (recorded only). */
    AUTHORIZE,
    /** Capture one line of the PaymentIntent: the deposit on confirmation, the balance on completion. */
    CAPTURE,
    /** Cancel the uncaptured PaymentIntent: the authorisation is released. */
    RELEASE,
    /** Refund part or all of the captured deposit. */
    REFUND,
    /** Service completed (or forfeited): start payments-service's settlement hold. */
    SETTLE,
    /** Late provider-cancellation fee (recorded; debited from the provider's payouts). */
    PROVIDER_FEE
}
