package com.afrotransact.deliveryruns.domain;

/** Why an advertorial ended. */
public enum AdvertorialEnd {
    /** Its window closed (or its run's cutoff passed, or the run was confirmed, driven or completed). */
    SOURCE_CLOSED,
    /** Its window or run was cancelled: nothing to order any more. */
    CANCELLED,
    /** Its own end time passed. */
    SCHEDULE,
    /** Staff ended it. */
    STAFF
}
