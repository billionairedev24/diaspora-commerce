package com.afrotransact.bookings.domain;

/** Where a service happens. */
public enum ServiceMode {
    /** At the provider's studio, salon, office or venue. */
    IN_PERSON,
    /** Home visit at the customer's address. */
    AT_HOME,
    /** Online / video. */
    VIRTUAL
}
