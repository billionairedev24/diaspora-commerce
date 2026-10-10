package com.afrotransact.bookings.domain;

/**
 * Where the balance (price − deposit) of a booking stands. Card authorisations lapse after about 7 days, so for
 * appointments further out the balance hold is released right after the deposit capture and the balance is charged
 * off-session on the customer's saved card shortly before the appointment (a new payment of the same order).
 */
public enum BalanceState {
    /** The balance is still part of the booking's original authorisation (captured on completion). */
    ON_AUTHORISATION,
    /** Authorisation released; the balance will be charged off-session before the appointment. */
    DUE,
    /** Off-session charge submitted; payments has not decided yet. */
    PROCESSING,
    /** The bank wants the customer to authenticate (SCA): they confirm with the charge's client secret. */
    ACTION_REQUIRED,
    /** The last off-session attempt was declined; it is retried with a new key until the attempt limit. */
    FAILED,
    /** Off-session charge authorised; captured on completion. */
    AUTHORIZED,
    /** Balance captured. */
    CAPTURED,
    /** No more automatic attempts: the provider and the customer need to sort the balance out. */
    NEEDS_ATTENTION,
    /** Nothing more will be charged (booking cancelled, no-show without full price): holds released. */
    RELEASED;

    /** An off-session charge exists that is not final and can still be cancelled. */
    public boolean chargeOpen() {
        return this == PROCESSING || this == ACTION_REQUIRED || this == AUTHORIZED;
    }

    /** The job may (re)try an off-session charge. */
    public boolean chargeable() {
        return this == DUE || this == FAILED;
    }
}
