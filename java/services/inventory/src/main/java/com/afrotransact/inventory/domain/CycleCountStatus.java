package com.afrotransact.inventory.domain;

/** Lifecycle of a cycle count: lines are counted while OPEN, every line counted makes it COUNTED, posting the
 * variances makes it POSTED; a count can be cancelled until it is posted. */
public enum CycleCountStatus {
    OPEN,
    COUNTED,
    POSTED,
    CANCELLED
}
