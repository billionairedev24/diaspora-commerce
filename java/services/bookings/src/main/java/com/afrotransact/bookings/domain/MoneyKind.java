package com.afrotransact.bookings.domain;

/** Money movements of a booking (the design's per-booking ledger: auth, capture, charge, release, refund, fee). */
public enum MoneyKind {
    /** Deposit and balance authorised on the card (recorded only). */
    AUTHORIZE,
    /**
     * Capture one line of the PaymentIntent: the deposit on confirmation, the balance on completion (on the
     * off-session balance charge when the balance was charged off-session).
     */
    CAPTURE,
    /**
     * Cancel the uncaptured PaymentIntent: the authorisation is released. With line {@code balance}: cancel the
     * booking's open off-session balance charge.
     */
    RELEASE,
    /** Release the uncaptured rest of the authorisation after the deposit capture (payments {@code /release}). */
    RELEASE_REMAINING,
    /** Refund part or all of the captured deposit. */
    REFUND,
    /** Service completed (or forfeited): start payments-service's settlement hold. */
    SETTLE,
    /** Late provider-cancellation fee (recorded; debited from the provider's payouts). */
    PROVIDER_FEE
}
