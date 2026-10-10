package com.afrotransact.fulfilment.domain;

/** What the server does with one queued driver event. */
public enum SyncDecision {
    /** Next in sequence: apply it. */
    APPLY,
    /** Same event id already received: return the stored outcome, never re-apply. */
    DUPLICATE,
    /** A different event already used this sequence number on this device. */
    SEQUENCE_CONFLICT,
    /** An earlier sequence number is missing: hold this and later events until the gap is filled. */
    GAP
}
