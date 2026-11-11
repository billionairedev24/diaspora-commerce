package com.afrotransact.orders.domain;

/** Timeline and audit actions of a delivery quote. */
public enum QuoteAction {
    REQUESTED,
    CLAIMED,
    ASSIGNED,
    RELEASED,
    ESCALATED,
    AGREED,
    DECLINED,
    EXPIRED,
    USED,
    /** An order that used the quote did not complete; the quote is usable again while it is valid. */
    RESTORED
}
