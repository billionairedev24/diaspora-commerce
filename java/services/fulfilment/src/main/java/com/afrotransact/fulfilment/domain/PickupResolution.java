package com.afrotransact.fulfilment.domain;

/** What the driver does about a pickup problem. */
public enum PickupResolution {
    /** Wait (up to 10 minutes) for the seller; the stop stays open. */
    WAIT,
    /** Skip the stop; the seller reschedules and dispatch is notified. */
    SKIP,
    /** Take what is ready (or accept damaged parcels with a photo); the rest is rescheduled. */
    PARTIAL
}
