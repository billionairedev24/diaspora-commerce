package com.afrotransact.taxreturns.domain.tax;

/** Where AfroTransact stands in one state. */
public enum NexusStatus {
    /** Registered to collect and remit there. */
    REGISTERED,
    /** At or past a threshold and not registered: the one that needs action. */
    EXCEEDED,
    /** At 80 % or more of a threshold. */
    APPROACHING,
    /** Below every threshold. */
    BELOW
}
