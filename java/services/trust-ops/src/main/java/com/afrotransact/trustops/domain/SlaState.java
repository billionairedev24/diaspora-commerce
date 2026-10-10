package com.afrotransact.trustops.domain;

/** Where a timer stands: plenty of time left, in its last quarter, or past due. */
public enum SlaState {
    ON_TRACK,
    AT_RISK,
    BREACHED
}
