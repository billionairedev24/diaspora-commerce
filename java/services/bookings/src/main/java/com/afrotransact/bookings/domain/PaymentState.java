package com.afrotransact.bookings.domain;

/** Where the booking's money is (one manual-capture PaymentIntent in payments-service per booking). */
public enum PaymentState {
    /** PaymentIntent created; the customer has not authorised it yet. */
    PENDING,
    /** The last attempt failed; the customer may retry while the slot is held. */
    FAILED,
    /** Deposit (and balance) authorised on the card, nothing captured. */
    AUTHORIZED,
    /** Deposit captured on confirmation; the balance is still authorised. */
    DEPOSIT_CAPTURED,
    /** Everything due has been captured. */
    CAPTURED,
    /** Authorisation released without any capture. */
    RELEASED
}
