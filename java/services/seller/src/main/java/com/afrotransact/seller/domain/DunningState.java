package com.afrotransact.seller.domain;

/** Smart-retries state after a failed invoice payment. */
public enum DunningState {
    NONE,
    RETRYING,
    FINAL_ATTEMPT_FAILED
}
