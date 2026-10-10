package com.afrotransact.deliveryruns.domain;

/** Communications a window sends (each can be switched off in the wizard's Comms step). */
public enum CommKind {
    /** At opening time: email, push, SMS (the window's smsCopy). */
    ANNOUNCE,
    /** 48 hours before close: email, push. */
    REMIND_48H,
    /** 6 hours before close: push, SMS. */
    LAST_CALL_6H,
    /** At closing time: email with the delivery date. */
    CLOSED,
    /** Delivery day, from fulfilment's driver events: SMS, push. */
    OUT_FOR_DELIVERY,
    /** Geo-targeted paid social from opening to closing. */
    PAID_SOCIAL
}
