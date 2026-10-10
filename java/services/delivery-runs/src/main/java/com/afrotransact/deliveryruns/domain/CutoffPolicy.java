package com.afrotransact.deliveryruns.domain;

/** What happens at cutoff when the minimum number of orders is not met. */
public enum CutoffPolicy {
    /** Move the cutoff (and the delivery window) by the run's extend days, once; cancel if still short after that. */
    EXTEND,
    /** Cancel straight away. */
    CANCEL
}
