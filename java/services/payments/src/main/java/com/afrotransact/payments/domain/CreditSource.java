package com.afrotransact.payments.domain;

/** Where credit comes from (or, for {@link #ORDER}, where it went). The base for referral and membership (ADR 0006). */
public enum CreditSource {
    REFERRAL,
    GIFT_CARD,
    MEMBERSHIP,
    /** Credit returned by a refund (credit portion, or card portion converted to credit by choice). */
    REFUND,
    STAFF,
    PROMO,
    /** Credit spent on an order. */
    ORDER;

    /** Sources a calling service may grant from (referral and membership programs, promotions). */
    public boolean grantableByService() {
        return this == REFERRAL || this == MEMBERSHIP || this == PROMO;
    }
}
