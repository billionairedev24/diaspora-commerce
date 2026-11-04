package com.afrotransact.payments.domain;

/**
 * Where an applied commission or connection-fee rate comes from, in precedence order (the first that matches wins).
 */
public enum RateSource {
    /** A negotiated rate of the organisation: an active {@link NegotiatedRate} or a version's commission override. */
    OVERRIDE,
    /** The organisation's plan rate for the category (the plan's {@code *} rate when the category is not listed). */
    PLAN,
    /** The fee schedule's rate for the category. */
    CATEGORY,
    /** The fee schedule's default rate. */
    DEFAULT,
    /** AfroTransact Retail, the merchant of record: never charged a commission or connection fee. */
    RETAIL
}
